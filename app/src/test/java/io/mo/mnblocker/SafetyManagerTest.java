package io.mo.mnblocker;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class SafetyManagerTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File tripFlag() {
        return new File(tmp.getRoot(), SafetyManager.FLAG_FILE_NAME);
    }

    private File disableFlag() {
        return new File(tmp.getRoot(), SafetyManager.DISABLE_FLAG_FILE_NAME);
    }

    @Test
    public void defaultProtectionTripsOnThirdDeathAndSurvivesRestart() {
        SafetyManager safety = new SafetyManager(tmp.getRoot());
        assertTrue(safety.hookingAllowed());
        safety.onSystemUiDied();
        safety.onSystemUiDied();
        assertTrue(safety.hookingAllowed());
        safety.onSystemUiDied();
        assertFalse(safety.hookingAllowed());
        assertTrue(tripFlag().exists());
        assertTrue(new SafetyManager(tmp.getRoot()).isSafeMode());
    }

    @Test
    public void disabledProtectionBypassesExistingTripAtStartup() throws Exception {
        assertTrue(tripFlag().createNewFile());
        assertTrue(disableFlag().createNewFile());
        SafetyManager safety = new SafetyManager(tmp.getRoot());
        assertTrue(safety.hookingAllowed());
        assertFalse(safety.isSafeMode());
        assertFalse(safety.syncFromDisk());
    }

    @Test
    public void disabledProtectionDoesNotTripAfterRepeatedDeathsOrRestart() throws Exception {
        assertTrue(disableFlag().createNewFile());
        SafetyManager safety = new SafetyManager(tmp.getRoot());
        for (int i = 0; i < 10; i++) {
            safety.onSystemUiDied();
        }
        assertTrue(safety.hookingAllowed());
        assertFalse(tripFlag().exists());
        assertTrue(new SafetyManager(tmp.getRoot()).hookingAllowed());
    }

    @Test
    public void runtimeDisableImmediatelyResumesTrippedHooks() throws Exception {
        assertTrue(tripFlag().createNewFile());
        SafetyManager safety = new SafetyManager(tmp.getRoot());
        assertFalse(safety.hookingAllowed());
        assertTrue(disableFlag().createNewFile());
        assertFalse(safety.syncFromDisk());
        assertTrue(safety.hookingAllowed());
        // The UI clears the old trip while the override is active.
        assertTrue(tripFlag().delete());
        assertTrue(disableFlag().delete());
        assertFalse(safety.syncFromDisk());
        safety.onSystemUiDied();
        safety.onSystemUiDied();
        assertTrue(safety.hookingAllowed());
        safety.onSystemUiDied();
        assertTrue(safety.isSafeMode());
    }

    @Test
    public void togglingProtectionResetsCrashWindowEvenBeforeObserverRuns() throws Exception {
        SafetyManager safety = new SafetyManager(tmp.getRoot());
        safety.onSystemUiDied();
        safety.onSystemUiDied();
        assertTrue(disableFlag().createNewFile());
        safety.onSystemUiDied();
        assertFalse(tripFlag().exists());
        for (int i = 0; i < 5; i++) {
            safety.onSystemUiDied();
        }
        assertTrue(disableFlag().delete());
        safety.onSystemUiDied();
        safety.onSystemUiDied();
        assertTrue(safety.hookingAllowed());
        safety.onSystemUiDied();
        assertTrue(safety.isSafeMode());
    }

    @Test
    public void clearingTripStartsFreshCrashWindow() {
        SafetyManager safety = new SafetyManager(tmp.getRoot());
        for (int i = 0; i < 3; i++) {
            safety.onSystemUiDied();
        }
        assertTrue(tripFlag().delete());
        assertFalse(safety.syncFromDisk());
        safety.onSystemUiDied();
        assertTrue(safety.hookingAllowed());
    }
}
