package io.mo.mnblocker;

import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/** User-scoped restoration data. Legacy unscoped entries are retained but never guessed. */
@android.annotation.TargetApi(26)
final class OriginalChannelStateStore {
    private final File file;
    private final Map<String, OriginalState> states = new LinkedHashMap<>();
    private boolean loaded;

    OriginalChannelStateStore() {
        this(new File(HookLogger.DIR, "original_channel_states.json"));
    }

    OriginalChannelStateStore(File file) { this.file = file; }

    synchronized boolean saveIfAbsent(ChannelIdentity owner, String channelId, OriginalState state) {
        if (!ensureLoaded()) return false;
        String key = owner.key(channelId);
        if (states.containsKey(key)) return true;
        states.put(key, state);
        if (flush()) return true;
        states.remove(key);
        return false; // Never silence a channel if its original state could not be saved.
    }

    synchronized OriginalState get(ChannelIdentity owner, String channelId) {
        return ensureLoaded() ? states.get(owner.key(channelId)) : null;
    }

    synchronized boolean remove(ChannelIdentity owner, String channelId) {
        if (!ensureLoaded()) return false;
        String key = owner.key(channelId);
        OriginalState old = states.remove(key);
        if (old == null || flush()) return true;
        states.put(key, old);
        return false;
    }

    private boolean ensureLoaded() {
        if (loaded) return true;
        try {
            Map<String, OriginalState> disk = new LinkedHashMap<>();
            if (file.exists()) {
                JSONObject root = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                for (java.util.Iterator<String> it = root.keys(); it.hasNext();) {
                    String key = it.next();
                    JSONObject o = root.optJSONObject(key);
                    if (o != null) disk.put(key, new OriginalState(o.optInt("importance", -1),
                            o.optBoolean("showBadge", true), o.optBoolean("bypassDnd", false)));
                }
            }
            states.clear();
            states.putAll(disk);
            loaded = true;
            return true;
        } catch (Exception ignored) {
            return false; // Preserve unreadable/corrupt evidence instead of overwriting it.
        }
    }

    private boolean flush() {
        try {
            JSONObject root = new JSONObject();
            for (Map.Entry<String, OriginalState> e : states.entrySet()) {
                OriginalState s = e.getValue();
                root.put(e.getKey(), new JSONObject().put("importance", s.importance)
                        .put("showBadge", s.showBadge).put("bypassDnd", s.bypassDnd));
            }
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;
            File temp = new File(file.getPath() + ".new");
            Files.write(temp.toPath(), root.toString().getBytes(StandardCharsets.UTF_8));
            //noinspection ResultOfMethodCallIgnored
            temp.setReadable(true, false);
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    static final class OriginalState {
        final int importance;
        final boolean showBadge;
        final boolean bypassDnd;

        OriginalState(int importance, boolean showBadge, boolean bypassDnd) {
            this.importance = importance;
            this.showBadge = showBadge;
            this.bypassDnd = bypassDnd;
        }
    }
}
