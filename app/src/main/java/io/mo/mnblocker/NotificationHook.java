package io.mo.mnblocker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Bundle;
import android.os.FileObserver;
import android.service.notification.StatusBarNotification;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Installs the actual hooks inside the system framework (system_server).
 *
 * Channel and content hooks live in the "android" package:
 *
 *  1. The notification-channel store. Whenever an app creates or updates a
 *     channel, we test its id / name / description against the regex rules and,
 *     on a match, force importance to IMPORTANCE_NONE — i.e. the channel is
 *     silenced ("一键关闭").
 *
 *  2. Notification enqueue for optional content filtering, and
 *     ActivityManagerService app-death to feed {@link SafetyManager}.
 *     The notification menu integration lives separately in {@link SystemUiHook}.
 *
 * Compatibility:
 *  - "api101" / modern Android: channel store is
 *    com.android.server.notification.PreferencesHelper.
 *  - "legacy" (Android 8.0-8.1): it was com.android.server.notification.RankingHelper.
 *  Method overloads differ across releases, so we hook *every* overload by name
 *  (hookAllMethods), but only known owner signatures may modify a channel.
 *  Queries only observe; configuration changes use explicit NMS writes.
 */
final class NotificationHook {

    private static final String SYSTEMUI_PKG = "com.android.systemui";
    private static final String SAFE_MODE_FILE_NAME = SafetyManager.FLAG_FILE_NAME;
    private static final int CLEANUP_PROTECTED_FLAGS = cleanupProtectedFlags();

    private final SafetyManager safety;
    private volatile RegexConfig config;
    private LoadPackageParam lpparam;
    private volatile boolean hooksInstalled;
    private ChannelConfigReconciler reconciler;
    private final ThreadLocal<Boolean> reconciling = new ThreadLocal<>();
    /** Retained as a field so the observer thread keeps it (and us) alive. */
    private FileObserver flagObserver;
    /** Live record of every channel seen, surfaced to the settings UI. */
    private final DetectedChannelsStore detectedStore = new DetectedChannelsStore();
    private final OriginalChannelStateStore originalStateStore = new OriginalChannelStateStore();
    /** Cumulative counter for content-level blocks, surfaced to the settings UI. */
    private final ContentStatsStore contentStats = new ContentStatsStore();
    /** Per-app detail of the notifications content blocking dropped (private file). */
    private final ContentBlockLogStore blockLog = new ContentBlockLogStore();
    private final RootNotificationCleanup notificationCleanup = new RootNotificationCleanup(
            this::contentCleanupEnabled, this::cleanupExempt);

    NotificationHook(SafetyManager safety) {
        this.safety = safety;
    }

    void install(LoadPackageParam lpparam) {
        this.lpparam = lpparam;

        // Always watch the flag file, even in safe mode: this is what lets a
        // runtime clear (from the settings app, a different process) re-enable
        // the hooks in place, and a runtime trip disable them — both without a
        // reboot.
        watchSafeModeFlag();
        safety.syncFromDisk();

        if (safety.hookingAllowed()) {
            installHooks();
        } else {
            HookLogger.w("Safe mode active at startup — hooks deferred (module stays "
                    + "inert). Clearing " + HookLogger.DIR + "/" + SAFE_MODE_FILE_NAME
                    + " re-enables them without a reboot.");
        }
    }

    /**
     * Place the actual method hooks. Idempotent: safe to call again once the
     * flag is cleared at runtime (the guard makes repeat calls no-ops).
     */
    private synchronized void installHooks() {
        if (hooksInstalled) {
            return;
        }
        // Loaded once here; the callback then calls config.reloadIfChanged() on
        // every event, so UI edits (rules + per-channel switches) take effect on
        // a channel's next create/update without needing a reboot.
        this.config = RegexConfig.load();

        // The channel store is always hooked: even when the master switch is off
        // we still want to *populate the detected list* for the UI — we just
        // won't modify importance in that case (decided inside the callback).
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            hookChannelStore(lpparam);
            hookChannelQueries(lpparam);
        }

        // Content-level interception on the notification-post path. Always hooked,
        // but the callback self-gates on the master + content-enabled switches, so
        // it is inert unless the user opts in.
        hookNotificationEnqueue(lpparam);

        // Safety watcher is installed regardless of the master switch.
        hookAmsForSafety(lpparam);

        if (android.os.Build.VERSION.SDK_INT >= 26) {
            try {
                reconciler = new ChannelConfigReconciler(detectedStore, this::reconcileChannel);
                reconciler.start();
            } catch (Throwable t) {
                HookLogger.e("Could not start channel configuration sync", t);
            }
        }
        hooksInstalled = true;
        // Tell the settings UI, in the only way the two processes can talk, that
        // a hook of this version is actually live. Deliberately placed after the
        // guard above, so the beacon means "hooks are in", not "install() ran".
        HookAliveStore.mark();
        HookLogger.i("NotificationHook hooks installed for package=" + lpparam.packageName);
    }

    /**
     * Watch {@link HookLogger#DIR} for changes to the safe-mode flag so recovery
     * needs no reboot. The settings app (a different uid) can only delete the
     * on-disk flag; this observer, living inside system_server, notices and
     * re-enables — or disables — the hooks live.
     *
     * The deprecated String constructor is used deliberately: the File-based one
     * arrived in API 29 while the module supports minSdk 24, and the String form
     * still works on modern Android. Any failure here is non-fatal — recovery
     * simply falls back to a reboot.
     */
    @SuppressWarnings("deprecation")
    private void watchSafeModeFlag() {
        try {
            HookLogger.ensureDir();
            int mask = FileObserver.CREATE | FileObserver.DELETE
                    | FileObserver.MOVED_TO | FileObserver.MOVED_FROM;
            flagObserver = new FileObserver(HookLogger.DIR, mask) {
                @Override
                public void onEvent(int event, String path) {
                    if (SAFE_MODE_FILE_NAME.equals(path)
                            || SafetyManager.DISABLE_FLAG_FILE_NAME.equals(path)) {
                        onSafeModeFlagChanged();
                    }
                }
            };
            flagObserver.startWatching();
            HookLogger.i("Watching " + HookLogger.DIR + " for safe-mode flag changes "
                    + "(reboot-free recovery active).");
        } catch (Throwable t) {
            HookLogger.e("Could not start safe_mode watcher — recovery will need a reboot.", t);
        }
    }

    private synchronized void onSafeModeFlagChanged() {
        boolean tripped = safety.syncFromDisk();
        if (tripped) {
            HookLogger.w("Safe mode flag set at runtime — hooks now gated off.");
        } else {
            // No-op if hooks were already installed this boot; installs them
            // live if they had been deferred because safe mode was on at start.
            installHooks();
            HookLogger.i("Safe mode cleared — hooks re-enabled without a reboot.");
        }
    }

    // ------------------------------------------------------------------
    // (1) notification channel store
    // ------------------------------------------------------------------

    private void hookChannelStore(LoadPackageParam lpparam) {
        String[] candidateClasses = {
                "com.android.server.notification.PreferencesHelper", // modern
                "com.android.server.notification.RankingHelper"      // legacy (8.0/8.1)
        };
        String[] candidateMethods = {
                "createNotificationChannel",
                "updateNotificationChannel"
        };

        int hookedMethods = 0;
        for (String className : candidateClasses) {
            Class<?> clazz = XposedHelpers.findClassIfExists(className, lpparam.classLoader);
            if (clazz == null) {
                continue;
            }
            for (String method : candidateMethods) {
                try {
                    XposedBridge.hookAllMethods(clazz, method, channelCallback);
                    hookedMethods++;
                    HookLogger.i("Hooked " + className + "#" + method);
                } catch (Throwable t) {
                    HookLogger.e("Failed to hook " + className + "#" + method, t);
                }
            }
        }

        if (hookedMethods == 0) {
            HookLogger.e("No channel-store method could be hooked — "
                    + "Android version may be unsupported.", null);
        }
    }

    /**
     * Observe the read-side APIs used by Android Settings. This backfills
     * channels that already existed before the module was enabled and therefore
     * never pass through create/update while the hook is active.
     */
    private void hookChannelQueries(LoadPackageParam lpparam) {
        int hooked = 0;
        // Observe only the helpers whose first two arguments identify the target owner.
        // BinderService has callingPkg/targetPkg variants and must not be guessed.
        String[] classes = {
                "com.android.server.notification.PreferencesHelper",
                "com.android.server.notification.RankingHelper"
        };
        String[] methods = {
                "getNotificationChannel", "getNotificationChannels",
                "getConversationNotificationChannel"
        };
        for (String className : classes) {
            Class<?> clazz = XposedHelpers.findClassIfExists(className, lpparam.classLoader);
            if (clazz == null) {
                continue;
            }
            for (String method : methods) {
                try {
                    XposedBridge.hookAllMethods(clazz, method, channelQueryCallback);
                    hooked++;
                    HookLogger.i("Hooked " + className + "#" + method + " for channel backfill");
                } catch (Throwable ignored) {
                    // Method availability varies across Android versions and ROMs.
                }
            }
        }

        if (hooked == 0) {
            HookLogger.w("Could not hook any NotificationManagerService channel query method.");
        }
    }

    private final XC_MethodHook channelCallback = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            // Re-check safe mode on every event: it can be tripped at runtime.
            if (!safety.hookingAllowed()) {
                return;
            }
            RegexConfig cfg = config;
            if (cfg == null) {
                return;
            }

            try {
                // Pick up any edits the settings UI made (rules + per-channel
                // switches) since the last event.
                cfg.reloadIfChanged();

                NotificationChannel channel = findChannelArg(param.args);
                if (channel == null) {
                    return;
                }

                if (Boolean.TRUE.equals(reconciling.get())) return;
                ChannelIdentity owner = NotificationOwnerResolver.channel((Method) param.method, param.args);
                if (owner == null) return; // Unknown signature: do not guess or modify it.
                String pkg = owner.pkg;
                String id = channel.getId();
                CharSequence nameCs = channel.getName();
                String name = nameCs == null ? null : nameCs.toString();
                String rawDesc = channel.getDescription();
                // Only feed description to the matcher when the toggle is on, but
                // always persist the raw description so the UI can match it too.
                String matchDesc = cfg.isMatchDescription() ? rawDesc : null;
                int before = channel.getImportance();

                // allow rule wins over block rule (verification codes / IM safety valve)
                String allowRule = cfg.firstAllowMatch(id, name, matchDesc);
                String blockRule = allowRule != null ? null : cfg.firstMatch(id, name, matchDesc);
                boolean regexBlocked = allowRule == null && blockRule != null;

                // ---- always record the channel for the settings UI list ----
                recordObservedChannel(owner, channel, before, regexBlocked, rawDesc);

                // ---- decide: override > app-whitelist/allow-regex > block-regex ----
                Boolean override = cfg.overrideFor(pkg, owner.userId, id);
                boolean shouldBlock;
                String decisionReason;
                if (override != null) {
                    shouldBlock = override;
                    decisionReason = "override(" + (override ? "block" : "allow") + ")";
                } else if (cfg.isAppWhitelisted(pkg)) {
                    shouldBlock = false;
                    decisionReason = "app-whitelist";
                } else if (allowRule != null) {
                    shouldBlock = false;
                    decisionReason = "allow:" + allowRule;
                } else {
                    shouldBlock = blockRule != null;
                    decisionReason = blockRule != null ? "regex:" + blockRule : "no-match";
                }

                if (!cfg.isMasterEnabled() || !cfg.isRootModeActive()) {
                    // List is populated, but the master switch forbids changes.
                    if (shouldBlock) {
                        HookLogger.i("Master OFF — would block but skipping"
                                + " | caller=" + pkg + " | user=" + owner.userId + " | id=" + HookLogger.safe(id)
                                + " | reason=" + decisionReason);
                    }
                    if (restoreChannelIfAllowed(owner, channel, before, override, true, decisionReason)) {
                        param.setObjectExtra("mnblocker.restored", owner);
                    }
                    return;
                }

                if (!shouldBlock) {
                    // Either no rule matched, or the user explicitly allowed it.
                    if (override != null) {
                        HookLogger.i("ALLOWED by user override"
                                + " | caller=" + pkg + " | user=" + owner.userId + " | id=" + HookLogger.safe(id));
                    }
                    if (restoreChannelIfAllowed(owner, channel, before, override, false, decisionReason)) {
                        param.setObjectExtra("mnblocker.restored", owner);
                    }
                    return;
                }

                if (before == NotificationManager.IMPORTANCE_NONE) {
                    HookLogger.i("Channel already silenced, no change"
                            + " | caller=" + pkg + " | user=" + owner.userId + " | id=" + HookLogger.safe(id)
                            + " | reason=" + decisionReason);
                    return;
                }

                // ---- the actual "关闭" ----
                if (!saveOriginal(owner, channel, before)) return;
                channel.setImportance(NotificationManager.IMPORTANCE_NONE);
                try {
                    channel.setShowBadge(false);
                    channel.setBypassDnd(false);
                } catch (Throwable ignored) {
                    // non-essential extras; ignore if a ROM strips them
                }

                HookLogger.i("BLOCKED notification channel"
                        + " | caller=" + pkg + " | user=" + owner.userId
                        + " | id=" + HookLogger.safe(id)
                        + " | name=" + HookLogger.safe(name)
                        + " | reason=" + decisionReason
                        + " | importance " + before + " -> 0");
            } catch (Throwable t) {
                // Never let our logic break channel creation for the user.
                HookLogger.e("channelCallback error — passing through untouched", t);
            }
        }
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (param.hasThrowable() || Boolean.FALSE.equals(param.getResult())
                    || Boolean.TRUE.equals(reconciling.get())) return;
            ChannelIdentity owner = (ChannelIdentity) param.getObjectExtra("mnblocker.restored");
            NotificationChannel channel = findChannelArg(param.args);
            if (owner != null && channel != null && channel.getImportance() > 0) {
                originalStateStore.remove(owner, channel.getId());
            }
        }
    };

    private final XC_MethodHook channelQueryCallback = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (!safety.hookingAllowed()) {
                return;
            }
            RegexConfig cfg = config;
            if (cfg == null) {
                return;
            }

            try {
                cfg.reloadIfChanged();
                ChannelIdentity owner = NotificationOwnerResolver.channel((Method) param.method, param.args);
                if (owner == null || param.hasThrowable()) return;
                // Read-only: never change importance, restore state, or persist NMS config here.
                ChannelQueryObserver.collect(owner, param.getResult(), cfg.channelMatcher(),
                        cfg.isMatchDescription(), detectedStore::record);

            } catch (Throwable t) {
                HookLogger.e("channelQueryCallback error", t);
            }
        }
    };

    /** Locate the NotificationChannel argument regardless of overload shape. */
    private static NotificationChannel findChannelArg(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object a : args) {
            if (a instanceof NotificationChannel) {
                return (NotificationChannel) a;
            }
        }
        return null;
    }

    private void recordObservedChannel(ChannelIdentity owner, NotificationChannel channel,
                                       int importance, boolean regexMatched, String desc) {
        try {
            CharSequence nameCs = channel.getName();
            detectedStore.record(new ChannelRecord(
                    owner.pkg, owner.userId,
                    channel.getId(),
                    nameCs == null ? null : nameCs.toString(),
                    desc,
                    importance,
                    regexMatched,
                    System.currentTimeMillis()));
        } catch (Throwable t) {
            HookLogger.e("Failed to record detected channel", t);
        }
    }

    @android.annotation.TargetApi(26)
    private boolean saveOriginal(ChannelIdentity owner, NotificationChannel channel, int importance) {
        return originalStateStore.saveIfAbsent(owner, channel.getId(),
                new OriginalChannelStateStore.OriginalState(importance,
                        channel.canShowBadge(), channel.canBypassDnd()));
    }

    @android.annotation.TargetApi(26)
    private void reconcileChannel(Object notificationService, ChannelIdentity owner, int uid,
                                  NotificationChannel copy) throws Throwable {
        if (!safety.hookingAllowed()) return;
        RegexConfig cfg = config;
        if (cfg == null) return;
        cfg.reloadIfChanged();
        int before = copy.getImportance();
        String desc = cfg.isMatchDescription() ? copy.getDescription() : null;
        String name = copy.getName() == null ? null : copy.getName().toString();
        Boolean override = cfg.overrideFor(owner.pkg, owner.userId, copy.getId());
        boolean block = cfg.isRootModeActive() && cfg.isMasterEnabled()
                && (override != null ? override : !cfg.isAppWhitelisted(owner.pkg)
                && cfg.firstAllowMatch(copy.getId(), name, desc) == null
                && cfg.firstMatch(copy.getId(), name, desc) != null);
        if (block) {
            if (before == NotificationManager.IMPORTANCE_NONE || !saveOriginal(owner, copy, before)) return;
            copy.setImportance(NotificationManager.IMPORTANCE_NONE);
            copy.setShowBadge(false);
            copy.setBypassDnd(false);
        } else if (!restoreChannelIfAllowed(owner, copy, before, override,
                !cfg.isMasterEnabled() || !cfg.isRootModeActive(), "config-change")) {
            return;
        }
        reconciling.set(true);
        try {
            XposedHelpers.callMethod(notificationService, "updateNotificationChannelForPackage",
                    owner.pkg, uid, copy);
            if (!block) originalStateStore.remove(owner, copy.getId());
            HookLogger.i("Applied explicit channel config | caller=" + owner.pkg
                    + " | user=" + owner.userId + " | id=" + HookLogger.safe(copy.getId()));
        } finally {
            reconciling.remove();
        }
    }

    private boolean restoreChannelIfAllowed(ChannelIdentity owner, NotificationChannel channel, int before,
                                            Boolean override, boolean masterOff, String reason) {
        String pkg = owner.pkg;
        OriginalChannelStateStore.OriginalState original =
                originalStateStore.get(owner, channel.getId());

        if (before != NotificationManager.IMPORTANCE_NONE) {
            return false;
        }

        boolean explicitAllow = Boolean.FALSE.equals(override);
        if (original == null && !explicitAllow) {
            return false;
        }

        int restoredImportance = original != null && original.importance > NotificationManager.IMPORTANCE_NONE
                ? original.importance
                : NotificationManager.IMPORTANCE_DEFAULT;
        channel.setImportance(restoredImportance);

        try {
            if (original != null) {
                channel.setShowBadge(original.showBadge);
                channel.setBypassDnd(original.bypassDnd);
            }
        } catch (Throwable ignored) {
        }

        HookLogger.i("RESTORED notification channel"
                + " | caller=" + pkg + " | user=" + owner.userId
                + " | id=" + HookLogger.safe(channel.getId())
                + " | name=" + HookLogger.safe(channel.getName() == null
                ? null : channel.getName().toString())
                + " | reason=" + reason
                + " | source=" + (original == null ? "fallback-default" : "saved-original")
                + " | importance " + before + " -> " + restoredImportance);
        return true;
    }

    // ------------------------------------------------------------------
    // (1b) content-level interception (notification title / text)
    // ------------------------------------------------------------------

    /**
     * Hook the notification-post choke point so we can suppress an individual
     * notification by its title / text — catching marketing pushed on a shared
     * or "default" channel that channel-level blocking cannot reach.
     *
     * {@code enqueueNotificationInternal} is the single internal entry every
     * post funnels through; its signature drifts across releases, so we hook all
     * overloads by name and find the {@link Notification} argument by type.
     */
    private void hookNotificationEnqueue(LoadPackageParam lpparam) {
        Class<?> nms = XposedHelpers.findClassIfExists(
                "com.android.server.notification.NotificationManagerService", lpparam.classLoader);
        if (nms == null) {
            HookLogger.w("NotificationManagerService not found — content-level "
                    + "interception unavailable on this ROM.");
            return;
        }
        try {
            XposedBridge.hookAllMethods(nms, "enqueueNotificationInternal", enqueueCallback);
            HookLogger.i("Hooked NotificationManagerService#enqueueNotificationInternal "
                    + "for content-level interception");
        } catch (Throwable t) {
            HookLogger.e("Failed to hook enqueueNotificationInternal — content-level "
                    + "interception disabled.", t);
        }
    }

    private final XC_MethodHook enqueueCallback = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            // Even an allowed/whitelisted update must invalidate an older queued cleanup.
            notificationCleanup.onEnqueue(param.args);
            if (!safety.hookingAllowed()) {
                return;
            }
            RegexConfig cfg = config;
            if (cfg == null) {
                return;
            }
            try {
                cfg.reloadIfChanged();
                // Only the selected root path may perform enqueue-time content interception.
                if (!cfg.isRootModeActive() || !cfg.isMasterEnabled() || !cfg.isContentEnabled()) {
                    return;
                }

                ChannelIdentity owner = NotificationOwnerResolver.enqueue((Method) param.method, param.args);
                if (owner == null) return;
                Notification n = findNotificationArg(param.args);
                if (n == null) {
                    return;
                }
                // NEVER suppress a foreground-service notification: the platform
                // requires it and killing it can crash the owning service.
                if ((n.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0) {
                    return;
                }

                // Whole-app whitelist: exempt this app entirely.
                if (cfg.isAppWhitelisted(owner.pkg)) {
                    return;
                }

                String[] candidates = extractContentCandidates(n);
                if (candidates.length == 0) {
                    return;
                }

                // Whitelist wins — protects verification codes / IM.
                if (cfg.contentAllowMatch(candidates) != null) {
                    return;
                }
                String blockRule = cfg.contentBlockMatch(candidates);
                if (blockRule == null) {
                    return;
                }

                // Suppress: skip the original enqueue entirely.
                // Newer Android releases also have a boolean-returning enqueue overload.
                param.setResult(((Method) param.method).getReturnType() == boolean.class
                        ? Boolean.FALSE : null);
                notificationCleanup.onBlocked(param.thisObject, param.args, n);
                String pkg = owner.pkg;
                contentStats.recordBlock(pkg);
                blockLog.record(pkg, extractField(n, Notification.EXTRA_TITLE),
                        firstNonEmpty(new String[]{
                                extractField(n, Notification.EXTRA_TEXT),
                                extractField(n, Notification.EXTRA_BIG_TEXT)}),
                        blockRule);
                HookLogger.i("BLOCKED notification (content)"
                        + " | caller=" + pkg + " | user=" + owner.userId
                        + " | title=" + HookLogger.safe(firstNonEmpty(candidates))
                        + " | reason=content:" + blockRule);
            } catch (Throwable t) {
                // A failure here must never break notification delivery.
                HookLogger.e("enqueueCallback error — passing through untouched", t);
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            notificationCleanup.onEnqueueFinished(param.thisObject);
        }
    };

    private boolean contentCleanupEnabled() {
        if (!safety.hookingAllowed()) return false;
        RegexConfig cfg = config;
        if (cfg == null) return false;
        cfg.reloadIfChanged();
        return cfg.isRootModeActive() && cfg.isMasterEnabled() && cfg.isContentEnabled();
    }

    private boolean cleanupExempt(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        // Inspect the stored flags as well: an update may omit the old FGS flag.
        if ((n.flags & CLEANUP_PROTECTED_FLAGS) != 0) return true;
        RegexConfig cfg = config;
        return cfg == null || "io.mo.mnblocker".equals(sbn.getPackageName())
                || cfg.isAppWhitelisted(sbn.getPackageName())
                || cfg.contentAllowMatch(extractContentCandidates(n)) != null;
    }

    private static int cleanupProtectedFlags() {
        int flags = Notification.FLAG_FOREGROUND_SERVICE | Notification.FLAG_ONGOING_EVENT
                | Notification.FLAG_NO_CLEAR;
        try {
            // Hidden on some SDKs; absent entirely on Android 11.
            flags |= XposedHelpers.getStaticIntField(Notification.class, "FLAG_USER_INITIATED_JOB");
        } catch (Throwable ignored) {
        }
        return flags;
    }

    private static Notification findNotificationArg(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object a : args) {
            if (a instanceof Notification) {
                return (Notification) a;
            }
        }
        return null;
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
        if (cs != null) {
            String s = cs.toString();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
    }

    private static String firstNonEmpty(String[] arr) {
        for (String s : arr) {
            if (s != null && !s.isEmpty()) {
                return s;
            }
        }
        return null;
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

    // ------------------------------------------------------------------
    // (2) AMS app-death watcher -> SafetyManager
    // ------------------------------------------------------------------

    private void hookAmsForSafety(LoadPackageParam lpparam) {
        Class<?> ams = XposedHelpers.findClassIfExists(
                "com.android.server.am.ActivityManagerService", lpparam.classLoader);
        if (ams == null) {
            HookLogger.w("ActivityManagerService not found — SystemUI crash-loop "
                    + "detection unavailable on this ROM.");
            return;
        }

        int hooked = 0;
        // Method name varies across versions; hook whatever exists.
        for (String m : new String[]{"appDiedLocked", "handleAppDiedLocked"}) {
            try {
                XposedBridge.hookAllMethods(ams, m, appDeathCallback);
                hooked++;
                HookLogger.i("Hooked ActivityManagerService#" + m + " for safety watch");
            } catch (Throwable t) {
                // not every version has both — that's fine
            }
        }

        if (hooked == 0) {
            HookLogger.w("Could not hook any AMS app-death method — safe mode "
                    + "will not auto-trip (manual flag still works).");
        }
    }

    private final XC_MethodHook appDeathCallback = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                String processName = extractProcessName(param.args);
                if (SYSTEMUI_PKG.equals(processName)) {
                    safety.onSystemUiDied();
                }
            } catch (Throwable t) {
                HookLogger.e("appDeathCallback error", t);
            }
        }
    };

    /** Pull the process name out of a ProcessRecord-like argument. */
    private static String extractProcessName(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object a : args) {
            if (a == null) {
                continue;
            }
            // ProcessRecord exposes a public String field "processName".
            String name = readStringField(a, "processName");
            if (name != null) {
                return name;
            }
            // Fallback: ProcessRecord.info.packageName
            try {
                Object info = XposedHelpers.getObjectField(a, "info");
                if (info != null) {
                    String pkg = readStringField(info, "packageName");
                    if (pkg != null) {
                        return pkg;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String readStringField(Object obj, String field) {
        try {
            Field f = findField(obj.getClass(), field);
            if (f == null) {
                return null;
            }
            f.setAccessible(true);
            Object v = f.get(obj);
            return (v instanceof String) ? (String) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Field findField(Class<?> cls, String name) {
        while (cls != null) {
            try {
                return cls.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                cls = cls.getSuperclass();
            }
        }
        return null;
    }
}
