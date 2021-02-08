/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.server.pm;

import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.array;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertContains;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertExpectException;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertHaveIds;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertSuccess;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertWith;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.list;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.readAll;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.resultContains;

import android.content.ComponentName;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutManager;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.frameworks.servicestests.R;
import com.android.server.pm.ShortcutService.ConfigConstants;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unit test for "cmd shortcut" and "dumpsys shortcut".
 *
 * Launcher related commands are tested in
 */
@SmallTest
public class ShortcutManagerTest7 extends BaseShortcutManagerTest {

    private static final int CACHE_OWNER = LauncherApps.FLAG_CACHE_NOTIFICATION_SHORTCUTS;

    private List<String> callShellCommand(String... args) throws IOException, RemoteException {

        // For reset to work, the current time needs to be incrementing.
        mInjectedCurrentTimeMillis++;

        final AtomicInteger resultCode = new AtomicInteger(Integer.MIN_VALUE);

        final ResultReceiver rr = new ResultReceiver(mHandler) {
            @Override
            public void send(int resultCode_, Bundle resultData) {
                resultCode.set(resultCode_);
            }
        };
        final File out = File.createTempFile("shellout-", ".tmp",
                getTestContext().getCacheDir());
        try {
            try (final ParcelFileDescriptor fd = ParcelFileDescriptor.open(out,
                    ParcelFileDescriptor.MODE_READ_WRITE)) {
                mService.onShellCommand(
                    /* fdin*/ null,
                    /* fdout*/ fd.getFileDescriptor(),
                    /* fderr*/ fd.getFileDescriptor(),
                        args, null, rr);
            }
            return readAll(out);
        } finally {
            out.delete();
        }
    }

    public void testNonShell() throws Exception {
        mService.mMaxUpdatesPerInterval = 99;

        mInjectedCallingUid = 12345;
        assertExpectException(SecurityException.class, "must be shell",
                () -> callShellCommand("reset-config"));

        mInjectedCallingUid = Process.SYSTEM_UID;
        assertExpectException(SecurityException.class, "must be shell",
                () -> callShellCommand("reset-config"));

        assertEquals(99, mService.mMaxUpdatesPerInterval);
    }

    public void testRoot() throws Exception {
        mService.mMaxUpdatesPerInterval = 99;

        mInjectedCallingUid = Process.ROOT_UID;
        assertSuccess(callShellCommand("reset-config"));

        assertEquals(3, mService.mMaxUpdatesPerInterval);
    }

    public void testRestConfig() throws Exception {
        mService.mMaxUpdatesPerInterval = 99;

        mInjectedCallingUid = Process.SHELL_UID;
        assertSuccess(callShellCommand("reset-config"));

        assertEquals(3, mService.mMaxUpdatesPerInterval);
    }

    public void testOverrideConfig() throws Exception {
        mService.mMaxUpdatesPerInterval = 99;

        mInjectedCallingUid = Process.SHELL_UID;
        assertSuccess(callShellCommand("override-config",
                ConfigConstants.KEY_MAX_UPDATES_PER_INTERVAL + "=1"));

        assertEquals(1, mService.mMaxUpdatesPerInterval);
    }

    public void testResetThrottling() throws Exception {
        prepareCrossProfileDataSet();

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.getRemainingCallCount() < 3);
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertTrue(mManager.getRemainingCallCount() < 3);
        });

        mInjectedCallingUid = Process.SHELL_UID;
        assertSuccess(callShellCommand("reset-throttling"));

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertEquals(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertTrue(mManager.getRemainingCallCount() < 3);
        });
    }

    public void testResetThrottling_user_not_running() throws Exception {
        prepareCrossProfileDataSet();

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.getRemainingCallCount() < 3);
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertTrue(mManager.getRemainingCallCount() < 3);
        });

        mInjectedCallingUid = Process.SHELL_UID;

        mRunningUsers.put(USER_10, false);

        assertTrue(resultContains(
                callShellCommand("reset-throttling", "--user", "10"),
                "User 10 is not running or locked"));

        mRunningUsers.put(USER_10, true);

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.getRemainingCallCount() < 3);
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertTrue(mManager.getRemainingCallCount() < 3);
        });
    }

    public void testResetThrottling_user_running() throws Exception {
        prepareCrossProfileDataSet();

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.getRemainingCallCount() < 3);
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertTrue(mManager.getRemainingCallCount() < 3);
        });

        mRunningUsers.put(USER_10, true);
        mUnlockedUsers.put(USER_10, true);

        mInjectedCallingUid = Process.SHELL_UID;
        assertSuccess(callShellCommand("reset-throttling", "--user", "10"));

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.getRemainingCallCount() < 3);
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertEquals(3, mManager.getRemainingCallCount());
        });
    }

    public void testResetAllThrottling() throws Exception {
        prepareCrossProfileDataSet();

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.getRemainingCallCount() < 3);
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertTrue(mManager.getRemainingCallCount() < 3);
        });

        mInjectedCallingUid = Process.SHELL_UID;
        assertSuccess(callShellCommand("reset-all-throttling"));

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertEquals(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertEquals(3, mManager.getRemainingCallCount());
        });
    }

    // This command is deprecated. Will remove the test later.
    public void testLauncherCommands() throws Exception {
        prepareGetRoleHoldersAsUser(getSystemLauncher().activityInfo.packageName, USER_0);
        prepareGetHomeActivitiesAsUser(
                /* preferred */ getSystemLauncher().activityInfo.getComponentName(),
                list(getSystemLauncher(), getFallbackLauncher()),
                USER_0);

        prepareGetRoleHoldersAsUser(CALLING_PACKAGE_2, USER_10);
        prepareGetHomeActivitiesAsUser(
                /* preferred */ cn(CALLING_PACKAGE_2, "name"),
                list(getSystemLauncher(), getFallbackLauncher(),
                        ri(CALLING_PACKAGE_1, "name", false, 0),
                        ri(CALLING_PACKAGE_2, "name", false, 0)
                ),
                USER_10);

        // First, test "get".
        mRunningUsers.put(USER_10, true);
        mUnlockedUsers.put(USER_10, true);
        mInjectedCallingUid = Process.SHELL_UID;
        assertContains(
                assertSuccess(callShellCommand("get-default-launcher")),
                "Launcher: ComponentInfo{com.android.systemlauncher/systemlauncher_name}");

        assertContains(
                assertSuccess(callShellCommand("get-default-launcher", "--user", "10")),
                "Launcher: ComponentInfo{com.android.test.2/name}");

        // Change user-0's launcher.
        prepareGetRoleHoldersAsUser(CALLING_PACKAGE_1, USER_0);
        prepareGetHomeActivitiesAsUser(
                /* preferred */ cn(CALLING_PACKAGE_1, "name"),
                list(ri(CALLING_PACKAGE_1, "name", false, 0)),
                USER_0);
        assertContains(
                assertSuccess(callShellCommand("get-default-launcher")),
                "Launcher: ComponentInfo{com.android.test.1/name}");
    }

    public void testUnloadUser() throws Exception {
        prepareCrossProfileDataSet();

        assertNotNull(mService.getShortcutsForTest().get(USER_10));

        mRunningUsers.put(USER_10, true);
        mUnlockedUsers.put(USER_10, true);

        mInjectedCallingUid = Process.SHELL_UID;
        assertSuccess(callShellCommand("unload-user", "--user", "10"));

        assertNull(mService.getShortcutsForTest().get(USER_10));
    }

    public void testClearShortcuts() throws Exception {

        mRunningUsers.put(USER_10, true);

        // Add two manifests and two dynamics.
        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                R.xml.shortcut_2);
        updatePackageVersion(CALLING_PACKAGE_1, 1);
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageAddIntent(CALLING_PACKAGE_1, USER_10));

        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertTrue(mManager.addDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"))));
        });
        runWithCaller(LAUNCHER_1, USER_10, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("ms2", "s2"), HANDLE_USER_10);
        });

        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertWith(getCallerShortcuts())
                    .haveIds("ms1", "ms2", "s1", "s2")
                    .areAllEnabled()

                    .selectPinned()
                    .haveIds("ms2", "s2");
        });

        // First, call for a different package.

        mRunningUsers.put(USER_10, true);
        mUnlockedUsers.put(USER_10, true);

        mInjectedCallingUid = Process.SHELL_UID;
        assertSuccess(callShellCommand("clear-shortcuts", "--user", "10", CALLING_PACKAGE_2));

        // Shouldn't be cleared yet.
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertWith(getCallerShortcuts())
                    .haveIds("ms1", "ms2", "s1", "s2")
                    .areAllEnabled()

                    .selectPinned()
                    .haveIds("ms2", "s2");
        });

        mInjectedCallingUid = Process.SHELL_UID;
        assertSuccess(callShellCommand("clear-shortcuts", "--user", "10", CALLING_PACKAGE_1));

        // Only manifest shortcuts will remain, and are no longer pinned.
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertWith(getCallerShortcuts())
                    .haveIds("ms1", "ms2")
                    .areAllEnabled()
                    .areAllNotPinned();
        });
    }

    public void testGetShortcuts() throws Exception {

        mRunningUsers.put(USER_10, true);

        // Add two manifests and two dynamics.
        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                R.xml.shortcut_2);
        updatePackageVersion(CALLING_PACKAGE_1, 1);
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageAddIntent(CALLING_PACKAGE_1, USER_10));

        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertTrue(mManager.addDynamicShortcuts(list(
                    makeLongLivedShortcut("s1"), makeShortcut("s2"))));
        });
        runWithCaller(LAUNCHER_1, USER_10, () -> {
            mInjectCheckAccessShortcutsPermission = true;
            mLauncherApps.cacheShortcuts(CALLING_PACKAGE_1, list("s1"), HANDLE_USER_10,
                    CACHE_OWNER);
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("ms2", "s2"), HANDLE_USER_10);
        });

        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertWith(getCallerShortcuts())
                    .haveIds("ms1", "ms2", "s1", "s2")
                    .areAllEnabled()

                    .selectPinned()
                    .haveIds("ms2", "s2");
        });


        mRunningUsers.put(USER_10, true);
        mUnlockedUsers.put(USER_10, true);

        mInjectedCallingUid = Process.SHELL_UID;

        assertHaveIds(callShellCommand("get-shortcuts", "--user", "10", "--flags",
                Integer.toString(ShortcutManager.FLAG_MATCH_CACHED), CALLING_PACKAGE_1),
                "s1");

        assertHaveIds(callShellCommand("get-shortcuts", "--user", "10", "--flags",
                Integer.toString(ShortcutManager.FLAG_MATCH_DYNAMIC), CALLING_PACKAGE_1),
                "s1", "s2");

        assertHaveIds(callShellCommand("get-shortcuts", "--user", "10", "--flags",
                Integer.toString(ShortcutManager.FLAG_MATCH_MANIFEST), CALLING_PACKAGE_1),
                "ms1", "ms2");

        assertHaveIds(callShellCommand("get-shortcuts", "--user", "10", "--flags",
                Integer.toString(ShortcutManager.FLAG_MATCH_PINNED), CALLING_PACKAGE_1),
                "ms2", "s2");

        assertHaveIds(callShellCommand("get-shortcuts", "--user", "10", "--flags",
                Integer.toString(ShortcutManager.FLAG_MATCH_DYNAMIC
                        | ShortcutManager.FLAG_MATCH_PINNED), CALLING_PACKAGE_1),
                "ms2", "s1", "s2");

        assertHaveIds(callShellCommand("get-shortcuts", "--user", "10", "--flags",
                Integer.toString(ShortcutManager.FLAG_MATCH_MANIFEST
                        | ShortcutManager.FLAG_MATCH_CACHED), CALLING_PACKAGE_1),
                "ms1", "ms2", "s1");

    }

    public void testDumpsysArgs() {
        checkDumpsysArgs(null, true, false, false);
        checkDumpsysArgs(array("-u"), true, true, false);
        checkDumpsysArgs(array("--uid"), true, true, false);

        checkDumpsysArgs(array("-f"), true, false, true);
        checkDumpsysArgs(array("--files"), true, false, true);

        checkDumpsysArgs(array("-a"), true, true, true);
        checkDumpsysArgs(array("--all"), true, true, true);

        checkDumpsysArgs(array("-a", "-n"), false, true, true);
        checkDumpsysArgs(array("-a", "--no-main"), false, true, true);
    }

    private void checkDumpsysArgs(String[] args, boolean expectMain, boolean expectUid,
            boolean expectDumpFiles) {
        String dump = dumpsys(args);

        assertEquals(expectMain, dump.contains("Icon format: PNG"));
        assertEquals(expectUid, dump.contains("SHORTCUT MANAGER UID STATES"));
        assertEquals(expectDumpFiles, dump.contains("SHORTCUT MANAGER FILES"));
    }

}
