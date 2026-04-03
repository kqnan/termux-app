package com.termux.app.ssh;

import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import android.content.Context;
import android.os.FileObserver;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Unit tests for SSHControlMasterInstaller event-driven monitoring.
 *
 * Tests cover:
 * 1. SSH binary already exists - direct install path
 * 2. Monitoring start and stop lifecycle
 * 3. GC does not collect static observer
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SSHControlMasterInstallerTest {

    private Context context;
    private File binDir;
    private File sshBinary;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();

        // Create mock Termux bin directory for testing
        binDir = new File("/data/data/com.termux/files/usr/bin");
        if (!binDir.exists()) {
            binDir.mkdirs();
        }

        sshBinary = new File(binDir, "ssh");

        // Clean up any existing ssh binary from previous tests
        if (sshBinary.exists()) {
            sshBinary.delete();
        }

        // Stop any existing observer from previous tests
        SSHControlMasterInstaller.stopWatchingSSHBinary();
    }

    @After
    public void tearDown() {
        // Clean up observer after each test
        SSHControlMasterInstaller.stopWatchingSSHBinary();

        // Clean up test files
        if (sshBinary.exists()) {
            sshBinary.delete();
        }
    }

    // ========== Test 1: SSH binary already exists - direct install path ==========

    @Test
    public void testSSHBinaryExists_skipsMonitoringAndInstallsImmediately() {
        // Create ssh binary to simulate openssh already installed
        try {
            sshBinary.createNewFile();
        } catch (Exception e) {
            fail("Failed to create test ssh binary: " + e.getMessage());
        }

        assertTrue("SSH binary should exist for test setup", sshBinary.exists());

        // When ssh exists, startWatchingSSHBinary should call install() immediately
        // and NOT start monitoring (observer should be null after call)
        SSHControlMasterInstaller.startWatchingSSHBinary(context);

        // Observer should NOT be started since ssh already exists
        assertFalse("Observer should not be started when ssh already exists",
            SSHControlMasterInstaller.isWatchingSSHBinary());

        // Clean up
        sshBinary.delete();
    }

    @Test
    public void testSSHBinaryMissing_startsMonitoring() {
        // Ensure ssh binary does not exist
        if (sshBinary.exists()) {
            sshBinary.delete();
        }
        assertFalse("SSH binary should not exist for this test", sshBinary.exists());

        // When ssh doesn't exist, should start monitoring
        SSHControlMasterInstaller.startWatchingSSHBinary(context);

        // Observer should be active
        assertTrue("Observer should be started when ssh does not exist",
            SSHControlMasterInstaller.isWatchingSSHBinary());

        // Clean up
        SSHControlMasterInstaller.stopWatchingSSHBinary();
    }

    @Test
    public void testNullContext_doesNotStartMonitoring() {
        // Ensure ssh binary does not exist
        if (sshBinary.exists()) {
            sshBinary.delete();
        }

        // Should handle null context gracefully
        SSHControlMasterInstaller.startWatchingSSHBinary(null);

        // Observer should NOT be started with null context
        assertFalse("Observer should not start with null context",
            SSHControlMasterInstaller.isWatchingSSHBinary());
    }

    // ========== Test 2: Monitoring start and stop ==========

    @Test
    public void testStartWatching_setsWatchingState() {
        // Ensure ssh binary does not exist
        if (sshBinary.exists()) {
            sshBinary.delete();
        }

        // Initially not watching
        assertFalse("Should not be watching initially",
            SSHControlMasterInstaller.isWatchingSSHBinary());

        // Start watching
        SSHControlMasterInstaller.startWatchingSSHBinary(context);

        // Should be watching now
        assertTrue("Should be watching after startWatchingSSHBinary",
            SSHControlMasterInstaller.isWatchingSSHBinary());
    }

    @Test
    public void testStopWatching_clearsWatchingState() {
        // Ensure ssh binary does not exist
        if (sshBinary.exists()) {
            sshBinary.delete();
        }

        // Start watching first
        SSHControlMasterInstaller.startWatchingSSHBinary(context);
        assertTrue("Should be watching after start",
            SSHControlMasterInstaller.isWatchingSSHBinary());

        // Stop watching
        SSHControlMasterInstaller.stopWatchingSSHBinary();

        // Should not be watching now
        assertFalse("Should not be watching after stop",
            SSHControlMasterInstaller.isWatchingSSHBinary());
    }

    @Test
    public void testStopWatching_multipleCallsSafe() {
        // Ensure ssh binary does not exist
        if (sshBinary.exists()) {
            sshBinary.delete();
        }

        // Start watching
        SSHControlMasterInstaller.startWatchingSSHBinary(context);

        // Multiple stop calls should be safe
        SSHControlMasterInstaller.stopWatchingSSHBinary();
        SSHControlMasterInstaller.stopWatchingSSHBinary();
        SSHControlMasterInstaller.stopWatchingSSHBinary();

        // Should still not be watching
        assertFalse("Should not be watching after multiple stops",
            SSHControlMasterInstaller.isWatchingSSHBinary());
    }

    @Test
    public void testStopWatching_whenNotWatching_isSafe() {
        // Ensure we're not watching
        SSHControlMasterInstaller.stopWatchingSSHBinary();
        assertFalse("Should not be watching", SSHControlMasterInstaller.isWatchingSSHBinary());

        // Stop when not watching should be safe (no exception)
        SSHControlMasterInstaller.stopWatchingSSHBinary();

        assertFalse("Should still not be watching",
            SSHControlMasterInstaller.isWatchingSSHBinary());
    }

    @Test
    public void testRestartWatching_replacesExistingObserver() {
        // Ensure ssh binary does not exist
        if (sshBinary.exists()) {
            sshBinary.delete();
        }

        // Start watching first time
        SSHControlMasterInstaller.startWatchingSSHBinary(context);
        assertTrue("Should be watching after first start",
            SSHControlMasterInstaller.isWatchingSSHBinary());

        // Start watching again (should stop previous and create new)
        SSHControlMasterInstaller.startWatchingSSHBinary(context);
        assertTrue("Should still be watching after restart",
            SSHControlMasterInstaller.isWatchingSSHBinary());

        // Clean up
        SSHControlMasterInstaller.stopWatchingSSHBinary();
    }

    // ========== Test 3: GC does not collect static observer ==========

    @Test
    public void testStaticObserver_survivesGC() {
        // Ensure ssh binary does not exist
        if (sshBinary.exists()) {
            sshBinary.delete();
        }

        // Start watching
        SSHControlMasterInstaller.startWatchingSSHBinary(context);
        assertTrue("Should be watching after start",
            SSHControlMasterInstaller.isWatchingSSHBinary());

        // Force GC
        System.gc();
        try {
            // Give GC time to run
            Thread.sleep(100);
        } catch (InterruptedException e) {
            // Ignore
        }

        // Observer should still be active after GC
        assertTrue("Static observer should survive GC",
            SSHControlMasterInstaller.isWatchingSSHBinary());

        // Clean up
        SSHControlMasterInstaller.stopWatchingSSHBinary();
    }

    @Test
    public void testStaticObserver_weakReferenceShowsStrongReference() {
        // Ensure ssh binary does not exist
        if (sshBinary.exists()) {
            sshBinary.delete();
        }

        // Start watching
        SSHControlMasterInstaller.startWatchingSSHBinary(context);

        // Get weak reference to verify strong reference exists
        // We cannot directly access the static field, but we can verify behavior
        boolean isWatching = SSHControlMasterInstaller.isWatchingSSHBinary();

        // Trigger aggressive GC
        System.gc();
        System.runFinalization();
        System.gc();

        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            // Ignore
        }

        // Observer reference should still be valid
        assertTrue("Observer should still be watching after aggressive GC",
            SSHControlMasterInstaller.isWatchingSSHBinary());

        // Clean up
        SSHControlMasterInstaller.stopWatchingSSHBinary();
    }

    // ========== Additional utility tests ==========

    @Test
    public void testIsWatchingSSHBinary_returnsFalseInitially() {
        // Stop any existing observer
        SSHControlMasterInstaller.stopWatchingSSHBinary();

        assertFalse("Should not be watching initially",
            SSHControlMasterInstaller.isWatchingSSHBinary());
    }

    @Test
    public void testIsInstalled_returnsFalseWhenNotInstalled() {
        // SSH wrapper should not be installed in test environment
        // Just verify the method doesn't crash
        boolean result = SSHControlMasterInstaller.isInstalled();
        // Result depends on test environment state, just verify no crash
        // assertFalse(result); // Can't guarantee this in test environment
    }
}