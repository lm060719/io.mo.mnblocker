package io.mo.mnblocker;

import org.json.JSONObject;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XSharedPreferences;

/**
 * Configuration shared from the settings UI to the hook.
 *
 * Two kinds of decision data:
 *
 *  1. Regex rules — whether a never-before-overridden channel counts as
 *     "marketing". Sources merged together:
 *       a. built-in default ( .*营销.* )
 *       b. user rules from XSharedPreferences (key {@code rules})
 *       c. plain-text fallback /data/system/mnblocker/rules.txt
 *     Plus an optional allow / whitelist rule set (key {@code allow_rules}) that
 *     protects matching channels (verification codes, IM, ...) from ever being
 *     blocked by a regex. The actual matching lives in {@link RuleMatcher}, which
 *     is shared with the settings UI so both sides agree.
 *
 *  2. Per-channel overrides — an explicit user decision for ONE channel,
 *     keyed by "user:N|pkg|id", stored as a JSON object under {@code overrides}:
 *       value true  -> always block this channel
 *       value false -> always allow this channel (even if a regex matches)
 *     An override ALWAYS wins over the regex rules.
 *
 * The instance is reloadable: {@link #reloadIfChanged()} cheaply re-reads the
 * prefs file when the UI has written to it, so toggles take effect without a
 * reboot (on the channel's next create/update event).
 */
final class RegexConfig {

    static final String PREFS_NAME = "mnblocker_prefs";
    static final String KEY_RULES = "rules";
    static final String KEY_ALLOW_RULES = "allow_rules";
    static final String KEY_MASTER_ENABLED = "master_enabled";
    static final String KEY_MATCH_DESC = "match_description";
    static final String KEY_OVERRIDES = "overrides";
    static final String KEY_CONTENT_ENABLED = "content_enabled";
    static final String KEY_CONTENT_RULES = "content_rules";
    static final String KEY_APP_WHITELIST = "app_whitelist";
    /** Which interception path is active: {@link #MODE_ROOT} or {@link #MODE_ROOTFREE}. */
    static final String KEY_OPERATING_MODE = "operating_mode";
    static final String MODE_ROOT = "root";
    static final String MODE_ROOTFREE = "rootfree";

    private static final String MODULE_PKG = "io.mo.mnblocker";
    private static final String RULES_FILE = HookLogger.DIR + "/rules.txt";

    private final XSharedPreferences xsp;

    /** Channel block/allow engine; rebuilt on every {@link #reload()}. Never null. */
    private volatile RuleMatcher matcher = RuleMatcher.compile(null, null);
    /**
     * Content-level engine: its OWN block rules (matched against a notification's
     * title / text) but the SAME allow whitelist as {@link #matcher}, so the
     * verification-code / IM safety valve protects content interception too.
     */
    private volatile RuleMatcher contentMatcher = RuleMatcher.compileContent(null, null);
    private long configFileLastModified = -1L;
    /** User-scoped key -> true (force block) / false (force allow). Legacy keys are inert. */
    private final Map<String, Boolean> overrides = new HashMap<>();
    private boolean masterEnabled = true;
    private boolean matchDescription = true;
    /** Content-level interception master toggle (default OFF — it is invasive). */
    private boolean contentEnabled = false;
    /** Packages whose notifications are fully exempt from any interception. */
    private final Set<String> appWhitelist = new LinkedHashSet<>();
    /**
     * Which path the user picked: {@link #MODE_ROOT} (default, hook stays active)
     * or {@link #MODE_ROOTFREE} (hook restores every channel and stops touching
     * anything — see docs/rootfree-mode-plan.md §9.1). Old installs without this
     * key in either source must keep behaving as root mode, never go silent.
     */
    private String operatingMode = MODE_ROOT;

    private RegexConfig(XSharedPreferences xsp) {
        this.xsp = xsp;
    }

    /** Build and fully populate a config instance (called once at hook install). */
    static RegexConfig load() {
        XSharedPreferences xsp = null;
        try {
            xsp = new XSharedPreferences(MODULE_PKG, PREFS_NAME);
            xsp.makeWorldReadable();
        } catch (Throwable t) {
            HookLogger.e("Could not open XSharedPreferences", t);
        }
        RegexConfig c = new RegexConfig(xsp);
        c.reload();
        return c;
    }

    /** Re-read everything only if the prefs file or /data/system config changed. */
    void reloadIfChanged() {
        try {
            boolean prefsChanged = xsp != null && xsp.hasFileChanged();
            long lm = ConfigFileStore.lastModifiedForHook();
            boolean configChanged = lm != configFileLastModified;
            if (prefsChanged || configChanged) {
                HookLogger.i("Config changed — reloading"
                        + " | prefsChanged=" + prefsChanged
                        + " configChanged=" + configChanged);
                reload();
            }
        } catch (Throwable t) {
            HookLogger.e("reloadIfChanged failed", t);
        }
    }

    /** Full (re)load from all sources. */
    synchronized void reload() {
        Set<String> blockRaw = new LinkedHashSet<>();
        Set<String> allowRaw = new LinkedHashSet<>();
        Set<String> contentRaw = new LinkedHashSet<>();
        overrides.clear();
        appWhitelist.clear();

        // ---- (b) XSharedPreferences ----
        try {
            if (xsp != null) {
                xsp.reload();
                if (xsp.getFile().canRead()) {
                    masterEnabled = xsp.getBoolean(KEY_MASTER_ENABLED, true);
                    matchDescription = xsp.getBoolean(KEY_MATCH_DESC, true);
                    contentEnabled = xsp.getBoolean(KEY_CONTENT_ENABLED, false);
                    operatingMode = xsp.getString(KEY_OPERATING_MODE, MODE_ROOT);
                    addLines(blockRaw, xsp.getString(KEY_RULES, ""));
                    addLines(allowRaw, xsp.getString(KEY_ALLOW_RULES, ""));
                    addLines(contentRaw, xsp.getString(KEY_CONTENT_RULES, ""));
                    addLines(appWhitelist, xsp.getString(KEY_APP_WHITELIST, ""));
                    parseOverrides(xsp.getString(KEY_OVERRIDES, ""));
                } else {
                    HookLogger.w("XSharedPreferences not readable yet, using defaults + file");
                }
            }
        } catch (Throwable t) {
            HookLogger.e("Failed reading XSharedPreferences", t);
        }

        // ---- (c) root-writable JSON bridge under /data/system ----
        try {
            ConfigFileStore.ConfigSnapshot disk = ConfigFileStore.readForHook();
            configFileLastModified = disk.lastModified;
            if (disk.hasValue) {
                masterEnabled = disk.masterEnabled;
                matchDescription = disk.matchDescription;
                contentEnabled = disk.contentEnabled;
                operatingMode = disk.operatingMode;
                addLines(blockRaw, disk.rules);
                addLines(allowRaw, disk.allowRules);
                addLines(contentRaw, disk.contentRules);
                addLines(appWhitelist, disk.appWhitelist);
                parseOverrides(disk.overrides);
            }
        } catch (Throwable t) {
            HookLogger.e("Failed reading config.json", t);
        }

        // ---- (d) plain text fallback file (block rules only, legacy) ----
        try {
            File f = new File(RULES_FILE);
            if (f.exists() && f.canRead()) {
                byte[] buf = new byte[(int) f.length()];
                try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
                    int n = fis.read(buf);
                    if (n > 0) {
                        addLines(blockRaw, new String(buf, 0, n, "UTF-8"));
                    }
                }
            }
        } catch (Throwable t) {
            HookLogger.e("Failed reading rules.txt", t);
        }

        // ---- compile via the shared engine (default block rule injected there) ----
        matcher = RuleMatcher.compile(blockRaw, allowRaw);
        // Content engine reuses the SAME allow whitelist as the safety valve,
        // but NOT the built-in default rule — that one describes a channel.
        contentMatcher = RuleMatcher.compileContent(contentRaw, allowRaw);

        HookLogger.i("Loaded " + matcher.blockRules().size() + " block rule(s) + "
                + matcher.allowRules().size() + " allow rule(s) + "
                + contentMatcher.blockRules().size() + " content rule(s) + "
                + overrides.size() + " override(s)"
                + " | masterEnabled=" + masterEnabled
                + " matchDescription=" + matchDescription
                + " contentEnabled=" + contentEnabled
                + " operatingMode=" + operatingMode);
    }

    private void parseOverrides(String json) {
        if (json == null || json.trim().isEmpty()) {
            return;
        }
        try {
            JSONObject o = new JSONObject(json);
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                overrides.put(k, o.optBoolean(k, false));
            }
        } catch (Throwable t) {
            HookLogger.e("Failed to parse channel overrides JSON", t);
        }
    }

    private static void addLines(Set<String> dst, String blob) {
        if (blob == null) {
            return;
        }
        for (String line : blob.split("\\r?\\n")) {
            if (!line.trim().isEmpty()) {
                dst.add(line.trim());
            }
        }
    }

    boolean isMasterEnabled() {
        return masterEnabled;
    }

    /**
     * @return true if the root (Xposed hook) path should be active. False means
     *         the user switched to the root-free listener path, and the hook
     *         must restore every channel it previously silenced rather than
     *         just going quiet (see docs/rootfree-mode-plan.md §9.1).
     */
    boolean isRootModeActive() {
        return !MODE_ROOTFREE.equals(operatingMode);
    }

    boolean isMatchDescription() {
        return matchDescription;
    }

    RuleMatcher channelMatcher() {
        return matcher;
    }

    /** @return true if the whole app is whitelisted (never intercept it). */
    boolean isAppWhitelisted(String pkg) {
        if (pkg == null) {
            return false;
        }
        synchronized (this) {
            return appWhitelist.contains(pkg);
        }
    }

    List<String> rawRules() {
        return Collections.unmodifiableList(matcher.blockRules());
    }

    List<String> rawAllowRules() {
        return Collections.unmodifiableList(matcher.allowRules());
    }

    /**
     * Per-channel override decision.
     *
     * @return Boolean.TRUE  -> user forced "block"
     *         Boolean.FALSE -> user forced "allow"
     *         null          -> no override, fall back to regex
     */
    Boolean overrideFor(String pkg, int userId, String channelId) {
        synchronized (this) {
            return userId < 0 ? null : overrides.get(ChannelIdentity.key(pkg, userId, channelId));
        }
    }

    /**
     * @return the first block rule that matched, or {@code null} if none did.
     */
    String firstMatch(String... candidates) {
        return matcher.firstBlockMatch(candidates);
    }

    /**
     * @return the first allow (whitelist) rule that matched, or {@code null}.
     *         A non-null result means the channel must NOT be blocked by regex.
     */
    String firstAllowMatch(String... candidates) {
        return matcher.firstAllowMatch(candidates);
    }

    // ---- content-level interception (notification title / text) --------------

    /** Whether content-level interception is turned on by the user. */
    boolean isContentEnabled() {
        return contentEnabled;
    }

    /**
     * @return the first content block rule that matched, or {@code null}.
     *         Matched against notification title / text, not channel metadata.
     */
    String contentBlockMatch(String... candidates) {
        return contentMatcher.firstBlockMatch(candidates);
    }

    /**
     * @return the first allow (whitelist) rule that matched the content, or
     *         {@code null}. Shares the same whitelist as the channel matcher.
     */
    String contentAllowMatch(String... candidates) {
        return contentMatcher.firstAllowMatch(candidates);
    }
}
