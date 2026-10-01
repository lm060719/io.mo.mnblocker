package io.mo.mnblocker;

import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;

/** Small root-shell helpers used only by the settings UI process. */
final class ShellUtils {

    private ShellUtils() {}

    static String suReadFile(String path) {
        try {
            ShellResult r = runSu("cat '" + path + "'");
            return r.exitCode == 0 ? r.stdout : null;
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean suWriteFile(String path, String content) {
        try {
            String b64 = Base64.encodeToString(content.getBytes("UTF-8"), Base64.NO_WRAP);
            File parent = new File(path).getParentFile();
            String dir = parent == null ? "/data/system/mnblocker" : parent.getAbsolutePath();
            // chown to system (UID 1000) so system_server can write files
            // (hook.log, detected_channels.json, etc.) into this directory.
            // Without this, the directory is owned by root and system_server
            // gets EACCES on OnePlus / ColorOS ROMs with strict DAC checks.
            String cmd = "mkdir -p '" + dir + "'"
                    + " && chown 1000:1000 '" + dir + "'"
                    + " && chmod 0771 '" + dir + "'"
                    + " && chcon u:object_r:system_data_file:s0 '" + dir + "' 2>/dev/null; "
                    + "printf '%s' '" + b64 + "' | base64 -d > '" + path + "'"
                    + " && chmod 0666 '" + path + "'"
                    + " && chown 1000:1000 '" + path + "'";
            ShellResult r = runSu(cmd);
            return r.exitCode == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * One-shot repair: ensure /data/system/mnblocker/ is owned by system (UID
     * 1000) with correct permissions and SELinux label. Call from the settings
     * UI on every startup so existing broken installs are healed without
     * requiring a manual "su chown".
     */
    static boolean fixDirPermissions() {
        try {
            String dir = "/data/system/mnblocker";
            // content_block_log.json holds notification text and must stay
            // owner-only (0600); re-lock it AFTER the blanket 0666 above so this
            // startup repair cannot silently widen it to world-readable.
            String cmd = "mkdir -p '" + dir + "'"
                    + " && chown -R 1000:1000 '" + dir + "'"
                    + " && chmod 0771 '" + dir + "'"
                    + " && chmod 0666 '" + dir + "'/* 2>/dev/null"
                    + "; chmod 0600 '" + dir + "/content_block_log.json' 2>/dev/null"
                    + "; chcon -R u:object_r:system_data_file:s0 '" + dir + "' 2>/dev/null"
                    + "; true";
            ShellResult r = runSu(cmd);
            return r.exitCode == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private static final String SAFE_MODE_FILE = "/data/system/mnblocker/safe_mode";
    private static final String DISABLE_SAFE_MODE_FILE =
            HookLogger.DIR + "/" + SafetyManager.DISABLE_FLAG_FILE_NAME;

    /** Persist the debug override and clear any previously tripped protection. */
    static boolean setDisableSafeMode(boolean disabled) {
        if (disabled) {
            if (!suWriteFile(DISABLE_SAFE_MODE_FILE, "1\n")) {
                return false;
            }
            if (!clearSafeMode()) {
                // Roll back the override if the existing trip flag could not be cleared.
                setDisableSafeMode(false);
                return false;
            }
            return true;
        }
        try {
            return runSu("rm -f '" + DISABLE_SAFE_MODE_FILE + "'").exitCode == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean isDisableSafeMode() {
        try {
            if (new File(DISABLE_SAFE_MODE_FILE).exists()) {
                return true;
            }
            if (missIsConclusive(DISABLE_SAFE_MODE_FILE)) {
                return false;
            }
            return runSu("test -f '" + DISABLE_SAFE_MODE_FILE + "'").exitCode == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Whether "we could not read {@code path}" is a conclusive answer rather than
     * a permission problem: our uid can list the module directory, and the file
     * genuinely is not in it.
     *
     * The su fallbacks in the *ForApp readers exist for strict SELinux ROMs that
     * hide /data/system from a normal app uid. Without this check they also fired
     * in the healthy case — a missing file (fresh install, nothing recorded yet)
     * looks identical to a denied read — so every read spawned a ~100 ms–1 s su
     * process on the main thread to rediscover that the file is absent.
     */
    static boolean missIsConclusive(String path) {
        try {
            return new File(HookLogger.DIR).canRead() && !new File(path).exists();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Clear the safe-mode flag so the observer restores hooks without a reboot.
     * The file lives in /data/system (owned by system, UID 1000), so the
     * settings UI process cannot delete it directly — it must go through su.
     */
    static boolean clearSafeMode() {
        try {
            return runSu("rm -f '" + SAFE_MODE_FILE + "'").exitCode == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Whether the safe-mode flag is currently set. The settings app runs as a
     * normal app uid and on strict SELinux ROMs cannot stat /data/system
     * directly, so this falls back to su — the same way detected_channels.json
     * and config.json are read.
     *
     * The fallback only fires when the direct check is INCONCLUSIVE. Absent flag
     * file is the healthy, overwhelmingly common case: treating that as "could
     * not tell" and shelling out meant every call spawned su (~100 ms–1 s), on
     * the main thread, for a file we had already correctly determined was not
     * there. If the directory is readable, exists() is authoritative.
     */
    static boolean isSafeModeTripped() {
        if (isDisableSafeMode()) {
            return false;
        }
        try {
            if (new File(SAFE_MODE_FILE).exists()) {
                return true;
            }
            if (missIsConclusive(SAFE_MODE_FILE)) {
                return false; // readable dir + no flag file => genuinely not tripped
            }
            return runSu("test -f '" + SAFE_MODE_FILE + "'").exitCode == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---- debug logging helpers (used by DebugActivity) ---------------------

    private static final String FLAG_FILE = "/data/system/mnblocker/debug_logging";
    private static final String HOOK_LOG  = "/data/system/mnblocker/hook.log";

    /** Create or remove the flag file that HookLogger checks. */
    static boolean setDebugLogging(boolean enabled) {
        try {
            if (enabled) {
                String cmd = "mkdir -p '/data/system/mnblocker'"
                        + " && echo 1 > '" + FLAG_FILE + "'"
                        + " && chmod 0666 '" + FLAG_FILE + "'"
                        + " && chown 1000:1000 '" + FLAG_FILE + "'";
                return runSu(cmd).exitCode == 0;
            } else {
                return runSu("rm -f '" + FLAG_FILE + "'").exitCode == 0;
            }
        } catch (Throwable t) {
            return false;
        }
    }

    /** Read current debug logging state. Tries direct file check, falls back to su. */
    static boolean isDebugLogging() {
        try {
            if (new java.io.File(FLAG_FILE).exists()) {
                return true;
            }
            ShellResult r = runSu("test -f '" + FLAG_FILE + "'");
            return r.exitCode == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---- Xposed log control helpers (used by DebugActivity) -----------------

    private static final String DISABLE_XPOSED_LOG_FILE =
            "/data/system/mnblocker/disable_xposed_log";
    private static final String XPOSED_LOG_LEVEL_FILE =
            "/data/system/mnblocker/xposed_log_level";

    /** Create or remove the flag file that disables XposedBridge.log output. */
    static boolean setDisableXposedLog(boolean disabled) {
        try {
            if (disabled) {
                String cmd = "mkdir -p '/data/system/mnblocker'"
                        + " && echo 1 > '" + DISABLE_XPOSED_LOG_FILE + "'"
                        + " && chmod 0666 '" + DISABLE_XPOSED_LOG_FILE + "'"
                        + " && chown 1000:1000 '" + DISABLE_XPOSED_LOG_FILE + "'";
                return runSu(cmd).exitCode == 0;
            } else {
                return runSu("rm -f '" + DISABLE_XPOSED_LOG_FILE + "'").exitCode == 0;
            }
        } catch (Throwable t) {
            return false;
        }
    }

    /** Read current disable-xposed-log state. */
    static boolean isDisableXposedLog() {
        try {
            if (new java.io.File(DISABLE_XPOSED_LOG_FILE).exists()) {
                return true;
            }
            ShellResult r = runSu("test -f '" + DISABLE_XPOSED_LOG_FILE + "'");
            return r.exitCode == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Write the Xposed log level (0-4) to the level file. */
    static boolean setXposedLogLevel(int level) {
        try {
            String cmd = "mkdir -p '/data/system/mnblocker'"
                    + " && echo " + level + " > '" + XPOSED_LOG_LEVEL_FILE + "'"
                    + " && chmod 0666 '" + XPOSED_LOG_LEVEL_FILE + "'"
                    + " && chown 1000:1000 '" + XPOSED_LOG_LEVEL_FILE + "'";
            return runSu(cmd).exitCode == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Read the current Xposed log level. Returns 4 (ERROR) if not set. */
    static int getXposedLogLevel() {
        try {
            // Try direct read first. The open must be attempted rather than
            // predicted from canRead(): access(2) checks DAC only, so on a
            // SELinux ROM it says "readable" for a file the open then denies —
            // and letting that exception escape would skip the su fallback below.
            java.io.File f = new java.io.File(XPOSED_LOG_LEVEL_FILE);
            byte[] buf = new byte[8];
            try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
                int n = fis.read(buf);
                if (n > 0) {
                    return Integer.parseInt(new String(buf, 0, n, "UTF-8").trim());
                }
            } catch (Throwable ignored) {
                // absent or denied — su below is the authority
            }
            // Fall back to su.
            ShellResult r = runSu("cat '" + XPOSED_LOG_LEVEL_FILE + "' 2>/dev/null");
            if (r.exitCode == 0 && r.stdout != null && !r.stdout.trim().isEmpty()) {
                return Integer.parseInt(r.stdout.trim());
            }
        } catch (Throwable t) {
            // fall through
        }
        return 4; // default: ERROR
    }

    /** Check whether hook.log exists at all. */
    static boolean hookLogExists() {
        try {
            if (new java.io.File(HOOK_LOG).canRead()
                    && new java.io.File(HOOK_LOG).length() > 0) {
                return true;
            }
            ShellResult r = runSu("test -s '" + HOOK_LOG + "'");
            return r.exitCode == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Copy hook.log to /sdcard/ so the user can share it. */
    static boolean exportHookLog() {
        try {
            String cmd = "cp '" + HOOK_LOG + "' '/sdcard/hook.log'"
                    + " && chmod 0644 '/sdcard/hook.log'";
            return runSu(cmd).exitCode == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private static ShellResult runSu(String command) throws Exception {
        Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
        StreamReader out = new StreamReader(p.getInputStream());
        StreamReader err = new StreamReader(p.getErrorStream());
        out.start();
        err.start();
        int code = p.waitFor();
        out.join();
        err.join();
        return new ShellResult(code, out.text(), err.text());
    }

    private static final class ShellResult {
        final int exitCode;
        final String stdout;
        @SuppressWarnings("unused")
        final String stderr;

        ShellResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }

    private static final class StreamReader extends Thread {
        private final InputStream in;
        private String text = "";

        StreamReader(InputStream in) {
            this.in = in;
        }

        @Override
        public void run() {
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    bos.write(buf, 0, n);
                }
                text = bos.toString("UTF-8");
            } catch (Throwable ignored) {
                text = "";
            }
        }

        String text() {
            return text;
        }
    }
}
