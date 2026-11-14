package io.mo.mnblocker;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The list of notification channels the hook has seen.
 *
 * Direction: hook (system_server) -> app UI.
 *
 * The hook side keeps a live in-memory map and re-writes the whole JSON file on
 * every change ({@link #record}). The app side just reads the file
 * ({@link #readAllFromDisk}) whenever the user opens the screen or taps refresh.
 *
 * File: /data/system/mnblocker/detected_channels.json
 * It is made world-readable so the module app (a different uid) can read it.
 * NOTE: on a few strict SELinux ROMs a foreign uid may still be denied; the UI
 * degrades gracefully and just shows an empty list with a hint in that case.
 */
final class DetectedChannelsStore {

    private static final String FILE = HookLogger.DIR + "/detected_channels.json";
    /** Hard cap so a misbehaving device cannot grow the file without bound. */
    private static final int MAX_ENTRIES = 1000;

    // ---- hook-side live state ----
    private final LinkedHashMap<String, ChannelRecord> live = new LinkedHashMap<>();
    private boolean loadedFromDisk;

    DetectedChannelsStore() {}

    /**
     * Record (insert or update) a channel and persist. Called from the hook.
     * Synchronised: system_server may invoke channel APIs from several threads.
     */
    synchronized void record(ChannelRecord r) {
        ensureLoaded();
        ChannelRecord prev = live.get(r.key());
        if (prev != null && prev.importance == r.importance && prev.regexMatched == r.regexMatched
                && prev.name.equals(r.name) && prev.desc.equals(r.desc)) return;

        // Re-insert at the end so most-recently-seen sorts last by insertion.
        live.remove(r.key());
        live.put(r.key(), r);

        // Trim oldest if we somehow exceed the cap.
        while (live.size() > MAX_ENTRIES) {
            String oldest = live.keySet().iterator().next();
            live.remove(oldest);
        }

        flush();
    }

    private void flush() {
        try {
            HookLogger.ensureDir();
            JSONArray arr = new JSONArray();
            for (ChannelRecord r : live.values()) {
                arr.put(r.toJson());
            }
            File f = new File(FILE);
            try (FileWriter fw = new FileWriter(f, false)) {
                fw.write(arr.toString());
            }
            // make readable by the module app (different uid)
            //noinspection ResultOfMethodCallIgnored
            f.setReadable(true, false);
        } catch (Throwable t) {
            HookLogger.e("Failed to persist detected channels", t);
        }
    }

    /**
     * Read the persisted list. Safe to call from either side.
     * Returns an empty list on any error.
     */
    static List<ChannelRecord> readAllFromDisk() {
        return parse(readNormal(FILE));
    }

    /**
     * App UI variant: direct read first; if SELinux denies it, fall back to su cat.
     *
     * "Read succeeded but there is nothing here" and "could not read" must stay
     * distinguishable. Falling back on an empty LIST conflated the two, so a
     * fresh install — where the hook has legitimately recorded no channels yet —
     * spawned su on every call, on the main thread, only to be handed back the
     * same empty list.
     */
    static List<ChannelRecord> readAllFromDiskForApp() {
        String normal = readNormal(FILE);
        if (normal != null) {
            return parse(normal);
        }
        if (ShellUtils.missIsConclusive(FILE)) {
            return new ArrayList<>(); // directory visible, file absent => no data yet
        }
        return parse(ShellUtils.suReadFile(FILE));
    }

    private static String readNormal(String path) {
        try {
            File f = new File(path);
            if (!f.exists() || !f.canRead()) {
                return null;
            }
            byte[] buf = new byte[(int) f.length()];
            try (FileInputStream fis = new FileInputStream(f)) {
                int n = fis.read(buf);
                return n > 0 ? new String(buf, 0, n, "UTF-8") : "";
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static List<ChannelRecord> parse(String json) {
        List<ChannelRecord> out = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) {
            return out;
        }
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) {
                    out.add(ChannelRecord.fromJson(o));
                }
            }
        } catch (Throwable t) {
            // app side has no HookLogger file access guarantee; swallow quietly
        }
        return out;
    }

    /** Hook-side snapshot, for logging/debugging. */
    synchronized Collection<ChannelRecord> snapshot() {
        ensureLoaded();
        return new ArrayList<>(live.values());
    }

    private void ensureLoaded() {
        if (loadedFromDisk) return;
        for (ChannelRecord prev : readAllFromDisk()) live.put(prev.key(), prev);
        loadedFromDisk = true;
    }

    /** Replace the whole file (used by the UI's "clear list" if ever needed). */
    static boolean clear() {
        try {
            File f = new File(FILE);
            if (f.exists()) {
                try (FileWriter fw = new FileWriter(f, false)) {
                    fw.write("[]");
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @SuppressWarnings("unused")
    static Map<String, ChannelRecord> indexByKey() {
        Map<String, ChannelRecord> m = new LinkedHashMap<>();
        for (ChannelRecord r : readAllFromDisk()) {
            m.put(r.key(), r);
        }
        return m;
    }
}
