package io.mo.mnblocker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.os.Bundle;
import android.os.FileObserver;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Installs the actual hooks inside the system framework (system_server).
 *
 * Two things are hooked, both living in the "android" package:
 *
 *  1. The notification-channel store. Whenever an app creates or updates a
 *     channel, we test its id / name / description against the regex rules and,
 *     on a match, force importance to IMPORTANCE_NONE — i.e. the channel is
 *     silenced ("一键关闭").
 *
 *  2. ActivityManagerService app-death, purely to feed {@link SafetyManager}
 *     so it can detect a SystemUI crash loop. We do NOT hook anything inside
 *     SystemUI itself — only the framework, as required.
 *
 * Compatibility:
 *  - "api101" / modern Android: channel store is
 *    com.android.server.notification.PreferencesHelper.
 *  - "legacy" (Android 8.0-8.1): it was com.android.server.notification.RankingHelper.
 *  Method overloads differ across releases, so we hook *every* overload by name
 *  (hookAllMethods) and locate the NotificationChannel argument by type instead
 *  of relying on a fixed parameter index.
 */
final class NotificationHook {

    private static final String SYSTEMUI_PKG = "com.android.systemui";
    private static final String SAFE_MODE_FILE_NAME = SafetyManager.FLAG_FILE_NAME;

    private final SafetyManager safety;
    private volatile RegexConfig config;
    private LoadPackageParam lpparam;
    private volatile boolean hooksInstalled;
    /** Retained as a field so the observer thread keeps it (and us) alive. */
    private FileObserver flagObserver;
    /** Live record of every channel seen, surfaced to the settings UI. */
    private final DetectedChannelsStore detectedStore = new DetectedChannelsStore();
    private final OriginalChannelStateStore originalStateStore = new OriginalChannelStateStore();
    /** Cumulative counter for content-level blocks, surfaced to the settings UI. */
    private final ContentStatsStore contentStats = new ContentStatsStore();
    /** Per-app detail of the notifications content blocking dropped (private file). */
    private final ContentBlockLogStore blockLog = new ContentBlockLogStore();

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
        hookChannelStore(lpparam);
        hookChannelQueries(lpparam);

        // Content-level interception on the notification-post path. Always hooked,
        // but the callback self-gates on the master + content-enabled switches, so
        // it is inert unless the user opts in.
        hookNotificationEnqueue(lpparam);

        // Safety watcher is installed regardless of the master switch.
        hookAmsForSafety(lpparam);

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
        String[] classes = {
                "com.android.server.notification.NotificationManagerService",
                "com.android.server.notification.NotificationManagerService$BinderService",
                "com.android.server.notification.PreferencesHelper",
                "com.android.server.notification.RankingHelper"
        };
        String[] methods = {
                "getNotificationChannel",
                "getNotificationChannels",
                "getNotificationChannelsForPackage",
                "getConversationNotificationChannel",
                "getConversations",
                "getConversationsForPackage",
                "getNotificationChannelGroup",
                "getNotificationChannelGroups",
                "getNotificationChannelGroupsForPackage",
                "getNotificationChannelGroupForPackage"
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

                String pkg = describeCaller(param.args);
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
                recordObservedChannel(pkg, channel, before, regexBlocked, rawDesc);

                // ---- decide: override > app-whitelist/allow-regex > block-regex ----
                Boolean override = cfg.overrideFor(pkg, id);
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
                                + " | caller=" + pkg + " | id=" + HookLogger.safe(id)
                                + " | reason=" + decisionReason);
                    }
                    restoreChannelIfAllowed(pkg, channel, before, override, true, decisionReason);
                    return;
                }

                if (!shouldBlock) {
                    // Either no rule matched, or the user explicitly allowed it.
                    if (override != null) {
                        HookLogger.i("ALLOWED by user override"
                                + " | caller=" + pkg + " | id=" + HookLogger.safe(id));
                    }
                    restoreChannelIfAllowed(pkg, channel, before, override, false, decisionReason);
                    return;
                }

                if (before == NotificationManager.IMPORTANCE_NONE) {
                    HookLogger.i("Channel already silenced, no change"
                            + " | caller=" + pkg + " | id=" + HookLogger.safe(id)
                            + " | reason=" + decisionReason);
                    return;
                }

                // ---- the actual "关闭" ----
                originalStateStore.saveIfAbsent(pkg, channel, before);
                channel.setImportance(NotificationManager.IMPORTANCE_NONE);
                try {
                    channel.setShowBadge(false);
                    channel.setBypassDnd(false);
                } catch (Throwable ignored) {
                    // non-essential extras; ignore if a ROM strips them
                }

                HookLogger.i("BLOCKED notification channel"
                        + " | caller=" + pkg
                        + " | id=" + HookLogger.safe(id)
                        + " | name=" + HookLogger.safe(name)
                        + " | reason=" + decisionReason
                        + " | importance " + before + " -> 0");
            } catch (Throwable t) {
                // Never let our logic break channel creation for the user.
                HookLogger.e("channelCallback error — passing through untouched", t);
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
                String pkg = describeCaller(param.args);
                if ("<unknown>".equals(pkg)) {
                    return;
                }
                int[] count = new int[]{0};
                int[] changed = new int[]{0};
                collectAndApplyChannels(pkg, param.getResult(), cfg, new HashSet<>(), count, changed);
                if (changed[0] > 0) {
                    persistNotificationPreferences(param.thisObject);
                }
                if (count[0] > 0) {
                    HookLogger.i("Backfilled " + count[0] + " notification channels"
                            + " | caller=" + pkg
                            + " | changed=" + changed[0]);
                }
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

    private void recordObservedChannel(String pkg, NotificationChannel channel,
                                       int importance, boolean regexMatched, String desc) {
        try {
            CharSequence nameCs = channel.getName();
            detectedStore.record(new ChannelRecord(
                    pkg,
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

    private void collectAndApplyChannels(String pkg, Object value, RegexConfig cfg,
                                         Set<Object> seen, int[] count, int[] applied) {
        if (value == null || seen.contains(value)) {
            return;
        }
        seen.add(value);

        if (value instanceof NotificationChannel) {
            NotificationChannel c = (NotificationChannel) value;
            CharSequence nameCs = c.getName();
            String name = nameCs == null ? null : nameCs.toString();
            String rawDesc = c.getDescription();
            String matchDesc = cfg.isMatchDescription() ? rawDesc : null;
            String allowRule = cfg.firstAllowMatch(c.getId(), name, matchDesc);
            String blockRule = allowRule != null ? null : cfg.firstMatch(c.getId(), name, matchDesc);
            boolean regexBlocked = allowRule == null && blockRule != null;
            int before = c.getImportance();
            recordObservedChannel(pkg, c, before, regexBlocked, rawDesc);
            if (applyExistingChannelDecision(pkg, c, before, allowRule, blockRule, cfg)) {
                applied[0]++;
            }
            count[0]++;
            return;
        }

        if (value instanceof NotificationChannelGroup) {
            collectAndApplyChannels(pkg, ((NotificationChannelGroup) value).getChannels(),
                    cfg, seen, count, applied);
            return;
        }

        if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) {
                collectAndApplyChannels(pkg, item, cfg, seen, count, applied);
            }
            return;
        }

        Class<?> cls = value.getClass();
        if (cls.isArray()) {
            int n = Array.getLength(value);
            for (int i = 0; i < n; i++) {
                collectAndApplyChannels(pkg, Array.get(value, i), cfg, seen, count, applied);
            }
            return;
        }

        // android.content.pm.ParceledListSlice and ROM variants commonly expose
        // getList(). Use reflection so this module does not depend on hidden APIs.
        Object list = invokeNoArg(value, "getList");
        if (list != null && list != value) {
            collectAndApplyChannels(pkg, list, cfg, seen, count, applied);
        }
    }

    private boolean applyExistingChannelDecision(String pkg, NotificationChannel channel,
                                                 int before, String allowRule,
                                                 String blockRule, RegexConfig cfg) {
        if (!cfg.isMasterEnabled() || !cfg.isRootModeActive()) {
            return restoreChannelIfAllowed(pkg, channel, before, cfg.overrideFor(pkg, channel.getId()),
                    true, "master-off");
        }

        Boolean override = cfg.overrideFor(pkg, channel.getId());
        boolean shouldBlock;
        String reason;
        if (override != null) {
            shouldBlock = override;
            reason = "override(" + (override ? "block" : "allow") + ")";
        } else if (cfg.isAppWhitelisted(pkg)) {
            shouldBlock = false;
            reason = "app-whitelist";
        } else if (allowRule != null) {
            shouldBlock = false;
            reason = "allow:" + allowRule;
        } else {
            shouldBlock = blockRule != null;
            reason = blockRule != null ? "regex:" + blockRule : "no-match";
        }

        if (!shouldBlock) {
            return restoreChannelIfAllowed(pkg, channel, before, override, false, reason);
        }

        if (before == NotificationManager.IMPORTANCE_NONE) {
            return false;
        }

        originalStateStore.saveIfAbsent(pkg, channel, before);
        channel.setImportance(NotificationManager.IMPORTANCE_NONE);
        try {
            channel.setShowBadge(false);
            channel.setBypassDnd(false);
        } catch (Throwable ignored) {
        }

        HookLogger.i("APPLIED existing notification channel decision"
                + " | caller=" + pkg
                + " | id=" + HookLogger.safe(channel.getId())
                + " | name=" + HookLogger.safe(channel.getName() == null
                ? null : channel.getName().toString())
                + " | reason=" + reason
                + " | importance " + before + " -> 0");
        return true;
    }

    private boolean restoreChannelIfAllowed(String pkg, NotificationChannel channel, int before,
                                            Boolean override, boolean masterOff, String reason) {
        OriginalChannelStateStore.OriginalState original =
                originalStateStore.get(pkg, channel.getId());

        if (before != NotificationManager.IMPORTANCE_NONE) {
            if (original != null && (masterOff || Boolean.FALSE.equals(override))) {
                originalStateStore.remove(pkg, channel.getId());
            }
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

        originalStateStore.remove(pkg, channel.getId());
        HookLogger.i("RESTORED notification channel"
                + " | caller=" + pkg
                + " | id=" + HookLogger.safe(channel.getId())
                + " | name=" + HookLogger.safe(channel.getName() == null
                ? null : channel.getName().toString())
                + " | reason=" + reason
                + " | source=" + (original == null ? "fallback-default" : "saved-original")
                + " | importance " + before + " -> " + restoredImportance);
        return true;
    }

    private void persistNotificationPreferences(Object owner) {
        if (owner == null) {
            return;
        }
        if (invokeNoArgIfExists(owner, "updateConfig")
                || invokeNoArgIfExists(owner, "updateConfigLocked")) {
            return;
        }

        Object helper = readObjectField(owner, "mPreferencesHelper");
        if (helper == null) {
            helper = readObjectField(owner, "mRankingHelper");
        }
        if (helper != null && helper != owner) {
            persistNotificationPreferences(helper);
            return;
        }

        Object outer = readObjectField(owner, "this$0");
        if (outer != null && outer != owner) {
            persistNotificationPreferences(outer);
        }
    }

    private static Object invokeNoArg(Object obj, String methodName) {
        try {
            Method m = findNoArgMethod(obj.getClass(), methodName);
            if (m == null) {
                return null;
            }
            m.setAccessible(true);
            return m.invoke(obj);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean invokeNoArgIfExists(Object obj, String methodName) {
        try {
            Method m = findNoArgMethod(obj.getClass(), methodName);
            if (m == null) {
                return false;
            }
            m.setAccessible(true);
            m.invoke(obj);
            HookLogger.i("Persisted notification channel config via "
                    + obj.getClass().getName() + "#" + methodName);
            return true;
        } catch (Throwable t) {
            HookLogger.e("Failed to persist notification channel config via "
                    + obj.getClass().getName() + "#" + methodName, t);
            return false;
        }
    }

    private static Method findNoArgMethod(Class<?> cls, String name) {
        while (cls != null) {
            try {
                Method m = cls.getDeclaredMethod(name);
                if (m.getParameterTypes().length == 0) {
                    return m;
                }
            } catch (NoSuchMethodException ignored) {
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    private static Object readObjectField(Object obj, String fieldName) {
        try {
            Field f = findField(obj.getClass(), fieldName);
            if (f == null) {
                return null;
            }
            f.setAccessible(true);
            return f.get(obj);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Best-effort: the calling package is usually the first String arg. */
    private static String describeCaller(Object[] args) {
        if (args == null) {
            return "<unknown>";
        }
        for (Object a : args) {
            if (a instanceof String) {
                return (String) a;
            }
        }
        return "<unknown>";
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
            if (!safety.hookingAllowed()) {
                return;
            }
            RegexConfig cfg = config;
            if (cfg == null) {
                return;
            }
            try {
                cfg.reloadIfChanged();
                // Double-gated: master switch AND the content-interception toggle.
                if (!cfg.isMasterEnabled() || !cfg.isContentEnabled()) {
                    return;
                }

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
                if (cfg.isAppWhitelisted(describeCaller(param.args))) {
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
                param.setResult(null);
                String pkg = describeCaller(param.args);
                contentStats.recordBlock(pkg);
                blockLog.record(pkg, extractField(n, Notification.EXTRA_TITLE),
                        firstNonEmpty(new String[]{
                                extractField(n, Notification.EXTRA_TEXT),
                                extractField(n, Notification.EXTRA_BIG_TEXT)}),
                        blockRule);
                HookLogger.i("BLOCKED notification (content)"
                        + " | caller=" + pkg
                        + " | title=" + HookLogger.safe(firstNonEmpty(candidates))
                        + " | reason=content:" + blockRule);
            } catch (Throwable t) {
                // A failure here must never break notification delivery.
                HookLogger.e("enqueueCallback error — passing through untouched", t);
            }
        }
    };

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
