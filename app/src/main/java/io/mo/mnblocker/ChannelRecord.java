package io.mo.mnblocker;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * One detected notification channel, as observed by the hook inside
 * system_server and surfaced to the settings UI.
 *
 * Serialised to JSON for the hook -> app hand-off (see {@link DetectedChannelsStore}).
 */
final class ChannelRecord {

    /** Owning app package, e.g. "com.taobao.taobao". */
    final String pkg;
    /** -1 means an old record with unknown ownership; never use it for a write. */
    final int userId;
    /** Channel id as registered by the app. */
    final String id;
    /** Human-readable channel name (may equal id on some apps). */
    final String name;
    /** Channel description text, if any. Persisted so the UI can match it too. */
    final String desc;
    /** Importance last seen BEFORE the module touched it. */
    final int importance;
    /** True if a regex rule matched this channel. */
    final boolean regexMatched;
    /** Wall-clock time the channel was last observed. */
    final long lastSeen;

    ChannelRecord(String pkg, String id, String name, String desc,
                  int importance, boolean regexMatched, long lastSeen) {
        this(pkg, ChannelIdentity.UNKNOWN_USER, id, name, desc, importance, regexMatched, lastSeen);
    }

    ChannelRecord(String pkg, int userId, String id, String name, String desc,
                  int importance, boolean regexMatched, long lastSeen) {
        this.pkg = pkg == null ? "<unknown>" : pkg;
        this.userId = userId;
        this.id = id == null ? "" : id;
        this.name = name == null ? "" : name;
        this.desc = desc == null ? "" : desc;
        this.importance = importance;
        this.regexMatched = regexMatched;
        this.lastSeen = lastSeen;
    }

    /** Package + Android user + channel, including channels containing '|'. */
    String key() {
        return ChannelIdentity.key(pkg, userId, id);
    }

    JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("pkg", pkg);
            o.put("userId", userId);
            o.put("id", id);
            o.put("name", name);
            o.put("desc", desc);
            o.put("importance", importance);
            o.put("regexMatched", regexMatched);
            o.put("lastSeen", lastSeen);
        } catch (JSONException ignored) {
            // keys are constant; this cannot realistically throw
        }
        return o;
    }

    static ChannelRecord fromJson(JSONObject o) {
        return new ChannelRecord(
                o.optString("pkg", "<unknown>"),
                o.optInt("userId", ChannelIdentity.UNKNOWN_USER),
                o.optString("id", ""),
                o.optString("name", ""),
                o.optString("desc", ""),
                o.optInt("importance", -1),
                o.optBoolean("regexMatched", false),
                o.optLong("lastSeen", 0L));
    }
}
