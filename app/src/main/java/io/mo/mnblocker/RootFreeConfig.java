package io.mo.mnblocker;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Root-free mirror of {@link RegexConfig}. The listener and the settings UI
 * run in the same process/uid, so there is no XSharedPreferences / config.json
 * bridge to read through — {@code mnblocker_prefs} is already the single
 * source of truth (see docs/rootfree-mode-plan.md §2.4).
 *
 * Rebuilt whenever the underlying SharedPreferences changes, via a listener
 * rather than {@link RegexConfig}'s mtime-polling {@code reloadIfChanged()} —
 * same-process notification is simpler and immediate.
 */
final class RootFreeConfig {

    private final SharedPreferences prefs;
    private final SharedPreferences.OnSharedPreferenceChangeListener listener =
            (sp, key) -> reload();

    private volatile RuleMatcher channelMatcher = RuleMatcher.compile(null, null);
    private volatile RuleMatcher contentMatcher = RuleMatcher.compileContent(null, null);
    private volatile boolean masterEnabled = true;
    private volatile boolean matchDescription = true;
    private volatile boolean contentEnabled = false;
    private volatile boolean rootFreeMode = false;
    private final Map<String, Boolean> overrides = new HashMap<>();
    private final Set<String> appWhitelist = new LinkedHashSet<>();

    RootFreeConfig(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(RegexConfig.PREFS_NAME, Context.MODE_PRIVATE);
        reload();
        prefs.registerOnSharedPreferenceChangeListener(listener);
    }

    /** Stop reacting to pref changes. Call from the listener's onDestroy. */
    void close() {
        prefs.unregisterOnSharedPreferenceChangeListener(listener);
    }

    private synchronized void reload() {
        masterEnabled = prefs.getBoolean(RegexConfig.KEY_MASTER_ENABLED, true);
        matchDescription = prefs.getBoolean(RegexConfig.KEY_MATCH_DESC, true);
        contentEnabled = prefs.getBoolean(RegexConfig.KEY_CONTENT_ENABLED, false);
        rootFreeMode = RegexConfig.MODE_ROOTFREE.equals(
                prefs.getString(RegexConfig.KEY_OPERATING_MODE, RegexConfig.MODE_ROOT));

        Set<String> blockRaw = splitLines(prefs.getString(RegexConfig.KEY_RULES, ""));
        Set<String> allowRaw = splitLines(prefs.getString(RegexConfig.KEY_ALLOW_RULES, ""));
        Set<String> contentRaw = splitLines(prefs.getString(RegexConfig.KEY_CONTENT_RULES, ""));
        channelMatcher = RuleMatcher.compile(blockRaw, allowRaw);
        // Content engine reuses the SAME allow whitelist but not the built-in
        // default rule, same as RegexConfig.
        contentMatcher = RuleMatcher.compileContent(contentRaw, allowRaw);

        appWhitelist.clear();
        appWhitelist.addAll(splitLines(prefs.getString(RegexConfig.KEY_APP_WHITELIST, "")));

        overrides.clear();
        parseOverrides(prefs.getString(RegexConfig.KEY_OVERRIDES, ""));
    }

    private void parseOverrides(String json) {
        if (json == null || json.trim().isEmpty()) {
            return;
        }
        try {
            JSONObject o = new JSONObject(json);
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                overrides.put(k, o.optBoolean(k, false));
            }
        } catch (Throwable ignored) {
            // app side has no HookLogger; swallow quietly like RegexConfig does.
        }
    }

    private static Set<String> splitLines(String blob) {
        Set<String> out = new LinkedHashSet<>();
        if (blob == null) {
            return out;
        }
        for (String line : blob.split("\\r?\\n")) {
            String t = line.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    boolean isMasterEnabled() {
        return masterEnabled;
    }

    boolean isRootFreeMode() {
        return rootFreeMode;
    }

    boolean isMatchDescription() {
        return matchDescription;
    }

    boolean isContentEnabled() {
        return contentEnabled;
    }

    boolean isAppWhitelisted(String pkg) {
        if (pkg == null) {
            return false;
        }
        synchronized (this) {
            return appWhitelist.contains(pkg);
        }
    }

    Boolean overrideFor(String pkg, int userId, String channelId) {
        synchronized (this) {
            return userId < 0 ? null : overrides.get(ChannelIdentity.key(pkg, userId, channelId));
        }
    }

    RuleMatcher channelMatcher() {
        return channelMatcher;
    }

    RuleMatcher contentMatcher() {
        return contentMatcher;
    }
}
