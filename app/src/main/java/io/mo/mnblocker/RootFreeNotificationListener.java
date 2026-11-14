package io.mo.mnblocker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.List;

/**
 * Root-free interception entry point: a standard {@link NotificationListenerService},
 * needing only the user-granted "notification access" permission — no root, no
 * LSPosed. See {@code docs/rootfree-mode-plan.md} for the full design and the
 * capability trade-offs against the root/Xposed path ({@link NotificationHook}).
 *
 * There is no pre-post interception API available to a non-privileged listener
 * (no enqueue-time hook, and {@code updateNotificationChannel()} is blocked by
 * {@code NotificationManagerService.verifyPrivilegedListener()} for a plain
 * notification-access grant — see plan §2.1). So every block, channel-level or
 * content-level, goes through {@link #cancelNotification(String)} AFTER the
 * notification has already briefly appeared (and possibly made a sound).
 *
 * Mirrors {@link NotificationHook}'s hard rules where they still apply here:
 * never touch this app's own notifications, never touch a foreground-service
 * notification, and never let a failure here crash the process — this runs in
 * the app's own uid, so unlike the hook a crash only kills this app, but it
 * must still never happen.
 */
public final class RootFreeNotificationListener extends NotificationListenerService {

    /**
     * Whether the system currently has this listener BOUND — which is not the
     * same thing as the user having granted notification access. The two come
     * apart whenever the binding is dropped (replacing the APK does it, so does
     * a force-stop): the Settings.Secure grant survives, no notifications
     * arrive, and nothing rebinds on its own. The settings UI shares this
     * process and reads this to tell "running" from "granted but deaf" —
     * previously it only checked the grant and cheerfully reported running
     * while nothing was being intercepted.
     *
     * Static because the UI has no handle on the service instance; false by
     * default, which is also the truth after a process death (no process, no
     * binding). See {@link NotificationAccessUtils#requestRebind}.
     */
    private static volatile boolean connected;

    private volatile RootFreeConfig config;
    private RootFreeChannelStore channelStore;
    private RootFreeStatsStore statsStore;
    private RootFreeBlockLogStore blockLogStore;

    /** @return true while the system has this listener bound and delivering. */
    static boolean isConnected() {
        return connected;
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        // The system can disconnect then reconnect without destroying the service;
        // drop the previous config's pref-change listener before making a new one.
        RootFreeConfig old = config;
        if (old != null) {
            old.close();
        }
        config = new RootFreeConfig(this);
        channelStore = new RootFreeChannelStore(this);
        statsStore = new RootFreeStatsStore(this);
        blockLogStore = new RootFreeBlockLogStore(this);
        connected = true;
    }

    @Override
    public void onListenerDisconnected() {
        connected = false;
        super.onListenerDisconnected();
    }

    @Override
    public void onDestroy() {
        connected = false;
        RootFreeConfig cfg = config;
        if (cfg != null) {
            cfg.close();
        }
        super.onDestroy();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
        try {
            RootFreeConfig cfg = config;
            if (cfg == null || !cfg.isRootFreeMode()) {
                return;
            }

            String pkg = sbn.getPackageName();
            if (getPackageName().equals(pkg)) {
                return; // never touch — or even record — our own notifications
            }

            Notification n = sbn.getNotification();
            if (n == null) {
                return;
            }

            NotificationChannel channel = null; // Ranking.getChannel() needs API 26+
            if (Build.VERSION.SDK_INT >= 26 && rankingMap != null) {
                Ranking r = new Ranking();
                if (rankingMap.getRanking(sbn.getKey(), r)) {
                    channel = r.getChannel();
                }
            }

            // Observe the channel for the settings UI list BEFORE any gate below.
            // Master-off, the app whitelist and foreground-service suppress the
            // ACTION, not the observation — mirrors NotificationHook, which records
            // the channel before checking master/whitelist so the list stays
            // complete. (Recording after those gates froze the list while the
            // master switch was off, and hid every channel of a whitelisted app.)
            recordObserved(cfg, pkg, sbn.getUserId(), channel);

            if (!cfg.isMasterEnabled()) {
                return;
            }
            if (cfg.isAppWhitelisted(pkg)) {
                return;
            }
            // NEVER suppress a foreground-service notification: the platform
            // requires it and killing it can crash the owning service. Mirrors
            // NotificationHook's enqueueCallback rule.
            if (sbn.isOngoing() || (n.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0) {
                return;
            }

            // ---- channel-level judgment ----
            BlockDecision.Result d = BlockDecision.decide(
                    cfg.channelMatcher(),
                    channel != null ? cfg.overrideFor(pkg, sbn.getUserId(), channel.getId()) : null,
                    false, // whole-app whitelist already handled by the early return above
                    channelCandidates(channel, cfg.isMatchDescription()));

            boolean contentBlock = false;
            // ---- content-level judgment (only if channel-level missed and enabled) ----
            // The channel override deliberately does NOT carry over here, and passing
            // it in is a mistake that looks like a fix. It answers "blanket-block this
            // channel?", and content rules exist precisely for the channels whose
            // answer is no — a shared or "default" channel you have to keep open but
            // still want spam dropped from. An explicit allow override is only ever
            // needed when the regex DOES match the channel, i.e. "this looks like
            // marketing but keep it", which is exactly when the finer content filter
            // still has to run. Content's own safety valves are the allow rules
            // (shared with the channel matcher, applied inside BlockDecision) and the
            // app whitelist handled above. Matches NotificationHook's content path.
            if (!d.block && cfg.isContentEnabled()) {
                String[] candidates = extractContentCandidates(n);
                if (candidates.length > 0) {
                    d = BlockDecision.decide(cfg.contentMatcher(), null, false, candidates);
                    if (d.block) {
                        contentBlock = true;
                        // Only content-level blocks leave a per-notification detail
                        // entry — channel-level blocking never intercepts an
                        // individual post. Mirrors ContentBlockLogStore's semantics.
                        blockLogStore.record(pkg, extractField(n, Notification.EXTRA_TITLE),
                                firstNonEmpty(new String[]{
                                        extractField(n, Notification.EXTRA_TEXT),
                                        extractField(n, Notification.EXTRA_BIG_TEXT)}),
                                d.reason);
                    }
                }
            }

            if (d.block) {
                cancelNotification(sbn.getKey());
                // Stats mirror ContentStatsStore: count ONLY content-level drops so
                // the "内容级累计拦截" tile and its per-app detail log stay in
                // agreement. A channel-level block silences the channel; it does not
                // drop an individual post, so counting it here would inflate the
                // content total with events that have no matching detail entry.
                if (contentBlock && statsStore != null) {
                    statsStore.recordBlock(pkg);
                }
            }
        } catch (Throwable t) {
            // A failure here must never crash this app's process.
        }
    }

    /** Always record the channel for the settings UI list, regardless of verdict. */
    private void recordObserved(RootFreeConfig cfg, String pkg, int userId, NotificationChannel channel) {
        if (channel == null || channelStore == null) {
            return;
        }
        boolean matched = cfg.channelMatcher().shouldBlock(
                channelCandidates(channel, cfg.isMatchDescription()));
        channelStore.record(new ChannelRecord(pkg, userId, channel.getId(),
                channel.getName() == null ? null : channel.getName().toString(),
                channel.getDescription(), channel.getImportance(), matched,
                System.currentTimeMillis()));
    }

    private static String[] channelCandidates(NotificationChannel channel, boolean matchDescription) {
        if (channel == null) {
            return new String[0];
        }
        String name = channel.getName() == null ? null : channel.getName().toString();
        String desc = matchDescription ? channel.getDescription() : null;
        return new String[]{channel.getId(), name, desc};
    }

    /** Pull every user-visible text field out of a notification for matching. */
    private static String[] extractContentCandidates(Notification n) {
        List<String> out = new ArrayList<>();
        try {
            Bundle b = n.extras;
            if (b != null) {
                addText(out, b.getCharSequence(Notification.EXTRA_TITLE));
                addText(out, b.getCharSequence(Notification.EXTRA_TEXT));
                addText(out, b.getCharSequence(Notification.EXTRA_BIG_TEXT));
                addText(out, b.getCharSequence(Notification.EXTRA_SUB_TEXT));
                addText(out, b.getCharSequence(Notification.EXTRA_TITLE_BIG));
                addText(out, b.getCharSequence(Notification.EXTRA_INFO_TEXT));
                addText(out, b.getCharSequence(Notification.EXTRA_SUMMARY_TEXT));
            }
            addText(out, n.tickerText);
        } catch (Throwable ignored) {
            // extras access can throw on odd ROMs; match on whatever we got.
        }
        return out.toArray(new String[0]);
    }

    private static void addText(List<String> out, CharSequence cs) {
        if (cs != null && cs.length() > 0) {
            out.add(cs.toString());
        }
    }

    /** Read one extras field as a String; null-safe on odd ROMs. */
    private static String extractField(Notification n, String key) {
        try {
            Bundle b = n.extras;
            if (b != null) {
                CharSequence cs = b.getCharSequence(key);
                if (cs != null) {
                    return cs.toString();
                }
            }
        } catch (Throwable ignored) {
            // extras access can throw on some ROMs; treat as absent.
        }
        return null;
    }

    private static String firstNonEmpty(String[] arr) {
        for (String s : arr) {
            if (s != null && !s.isEmpty()) {
                return s;
            }
        }
        return null;
    }
}
