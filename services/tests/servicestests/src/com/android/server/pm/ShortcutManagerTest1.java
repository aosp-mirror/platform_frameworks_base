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

import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertAllDisabled;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertAllDynamic;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertAllDynamicOrPinned;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertAllEnabled;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertAllHaveIcon;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertAllHaveIconFile;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertAllHaveIconResId;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertAllHaveIntents;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertAllHaveTitle;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertAllImmutable;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertAllKeyFieldsOnly;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertAllManifest;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertAllNotHaveIntents;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertAllNotHaveTitle;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertAllNotKeyFieldsOnly;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertAllNotManifest;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertAllPinned;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertAllStringsResolved;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertAllUnique;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertBitmapSize;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertBundleEmpty;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertCallbackNotReceived;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertCallbackReceived;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertCannotUpdateImmutable;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertDynamicAndPinned;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertDynamicOnly;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertDynamicShortcutCountExceeded;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertEmpty;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertExpectException;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertShortcutIds;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.filterByActivity;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.findShortcut;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.hashSet;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.list;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.makeBundle;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.pfdToBitmap;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.resetAll;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.set;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.Manifest.permission;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherApps;
import android.content.pm.LauncherApps.ShortcutQuery;
import android.content.pm.ShortcutInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;
import android.util.SparseArray;

import com.android.frameworks.servicestests.R;
import com.android.server.pm.ShortcutService.ConfigConstants;
import com.android.server.pm.ShortcutService.FileOutputStreamWithPath;
import com.android.server.pm.ShortcutUser.PackageWithUser;

import org.mockito.ArgumentCaptor;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Tests for ShortcutService and ShortcutManager.
 *
 m FrameworksServicesTests &&
 adb install \
 -r -g ${ANDROID_PRODUCT_OUT}/data/app/FrameworksServicesTests/FrameworksServicesTests.apk &&
 adb shell am instrument -e class com.android.server.pm.ShortcutManagerTest1 \
 -w com.android.frameworks.servicestests/android.support.test.runner.AndroidJUnitRunner


 * TODO More tests for pinning + manifest shortcuts
 * TODO Manifest shortcuts + app upgrade -> launcher callback.
 *      Also locale change should trigger launcehr callbacks too, when they use strign resoucres.
 *      (not implemented yet.)
 * TODO: Add checks with assertAllNotHaveIcon()
 * TODO: Detailed test for hasShortcutPermissionInner().
 * TODO: Add tests for the command line functions too.
 */
@SmallTest
public class ShortcutManagerTest1 extends BaseShortcutManagerTest {

    /**
     * Test for the first launch path, no settings file available.
     */
    public void testFirstInitialize() {
        assertResetTimes(START_TIME, START_TIME + INTERVAL);
    }

    /**
     * Test for {@link ShortcutService#getLastResetTimeLocked()} and
     * {@link ShortcutService#getNextResetTimeLocked()}.
     */
    public void testUpdateAndGetNextResetTimeLocked() {
        assertResetTimes(START_TIME, START_TIME + INTERVAL);

        // Advance clock.
        mInjectedCurrentTimeMillis += 100;

        // Shouldn't have changed.
        assertResetTimes(START_TIME, START_TIME + INTERVAL);

        // Advance clock, almost the reset time.
        mInjectedCurrentTimeMillis = START_TIME + INTERVAL - 1;

        // Shouldn't have changed.
        assertResetTimes(START_TIME, START_TIME + INTERVAL);

        // Advance clock.
        mInjectedCurrentTimeMillis += 1;

        assertResetTimes(START_TIME + INTERVAL, START_TIME + 2 * INTERVAL);

        // Advance further; 4 hours since start.
        mInjectedCurrentTimeMillis = START_TIME + 4 * INTERVAL + 50;

        assertResetTimes(START_TIME + 4 * INTERVAL, START_TIME + 5 * INTERVAL);
    }

    /**
     * Test for the restoration from saved file.
     */
    public void testInitializeFromSavedFile() {

        mInjectedCurrentTimeMillis = START_TIME + 4 * INTERVAL + 50;
        assertResetTimes(START_TIME + 4 * INTERVAL, START_TIME + 5 * INTERVAL);

        mService.saveBaseStateLocked();

        dumpBaseStateFile();

        mService.saveDirtyInfo();

        // Restore.
        initService();

        assertResetTimes(START_TIME + 4 * INTERVAL, START_TIME + 5 * INTERVAL);
    }

    /**
     * Test for the restoration from restored file.
     */
    public void testLoadFromBrokenFile() {
        // TODO Add various broken cases.
    }

    public void testLoadConfig() {
        mService.updateConfigurationLocked(
                ConfigConstants.KEY_RESET_INTERVAL_SEC + "=123,"
                        + ConfigConstants.KEY_MAX_SHORTCUTS + "=4,"
                        + ConfigConstants.KEY_MAX_UPDATES_PER_INTERVAL + "=5,"
                        + ConfigConstants.KEY_MAX_ICON_DIMENSION_DP + "=100,"
                        + ConfigConstants.KEY_MAX_ICON_DIMENSION_DP_LOWRAM + "=50,"
                        + ConfigConstants.KEY_ICON_FORMAT + "=WEBP,"
                        + ConfigConstants.KEY_ICON_QUALITY + "=75");
        assertEquals(123000, mService.getResetIntervalForTest());
        assertEquals(4, mService.getMaxDynamicShortcutsForTest());
        assertEquals(5, mService.getMaxUpdatesPerIntervalForTest());
        assertEquals(100, mService.getMaxIconDimensionForTest());
        assertEquals(CompressFormat.WEBP, mService.getIconPersistFormatForTest());
        assertEquals(75, mService.getIconPersistQualityForTest());

        mInjectedIsLowRamDevice = true;
        mService.updateConfigurationLocked(
                ConfigConstants.KEY_MAX_ICON_DIMENSION_DP + "=100,"
                        + ConfigConstants.KEY_MAX_ICON_DIMENSION_DP_LOWRAM + "=50,"
                        + ConfigConstants.KEY_ICON_FORMAT + "=JPEG");
        assertEquals(ShortcutService.DEFAULT_RESET_INTERVAL_SEC * 1000,
                mService.getResetIntervalForTest());

        assertEquals(ShortcutService.DEFAULT_MAX_SHORTCUTS_PER_APP,
                mService.getMaxDynamicShortcutsForTest());

        assertEquals(ShortcutService.DEFAULT_MAX_UPDATES_PER_INTERVAL,
                mService.getMaxUpdatesPerIntervalForTest());

        assertEquals(50, mService.getMaxIconDimensionForTest());

        assertEquals(CompressFormat.JPEG, mService.getIconPersistFormatForTest());

        assertEquals(ShortcutService.DEFAULT_ICON_PERSIST_QUALITY,
                mService.getIconPersistQualityForTest());
    }

    // === Test for app side APIs ===

    /** Test for {@link android.content.pm.ShortcutManager#getMaxShortcutCountForActivity()} */
    public void testGetMaxDynamicShortcutCount() {
        assertEquals(MAX_SHORTCUTS, mManager.getMaxShortcutCountForActivity());
    }

    /** Test for {@link android.content.pm.ShortcutManager#getRemainingCallCount()} */
    public void testGetRemainingCallCount() {
        assertEquals(MAX_UPDATES_PER_INTERVAL, mManager.getRemainingCallCount());
    }

    public void testGetIconMaxDimensions() {
        assertEquals(MAX_ICON_DIMENSION, mManager.getIconMaxWidth());
        assertEquals(MAX_ICON_DIMENSION, mManager.getIconMaxHeight());
    }

    /** Test for {@link android.content.pm.ShortcutManager#getRateLimitResetTime()} */
    public void testGetRateLimitResetTime() {
        assertEquals(START_TIME + INTERVAL, mManager.getRateLimitResetTime());

        mInjectedCurrentTimeMillis = START_TIME + 4 * INTERVAL + 50;

        assertEquals(START_TIME + 5 * INTERVAL, mManager.getRateLimitResetTime());
    }

    public void testSetDynamicShortcuts() {
        setCaller(CALLING_PACKAGE_1, USER_0);

        final Icon icon1 = Icon.createWithResource(getTestContext(), R.drawable.icon1);
        final Icon icon2 = Icon.createWithBitmap(BitmapFactory.decodeResource(
                getTestContext().getResources(), R.drawable.icon2));

        final ShortcutInfo si1 = makeShortcut(
                "shortcut1",
                "Title 1",
                makeComponent(ShortcutActivity.class),
                icon1,
                makeIntent(Intent.ACTION_ASSIST, ShortcutActivity2.class,
                        "key1", "val1", "nest", makeBundle("key", 123)),
                /* weight */ 10);

        final ShortcutInfo si2 = makeShortcut(
                "shortcut2",
                "Title 2",
                /* activity */ null,
                icon2,
                makeIntent(Intent.ACTION_ASSIST, ShortcutActivity3.class),
                /* weight */ 12);
        final ShortcutInfo si3 = makeShortcut("shortcut3");

        assertTrue(mManager.setDynamicShortcuts(list(si1, si2)));
        assertShortcutIds(assertAllNotKeyFieldsOnly(
                mManager.getDynamicShortcuts()),
                "shortcut1", "shortcut2");
        assertEquals(2, mManager.getRemainingCallCount());

        // TODO: Check fields

        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertShortcutIds(assertAllNotKeyFieldsOnly(
                mManager.getDynamicShortcuts()),
                "shortcut1");
        assertEquals(1, mManager.getRemainingCallCount());

        assertTrue(mManager.setDynamicShortcuts(list()));
        assertEquals(0, mManager.getDynamicShortcuts().size());
        assertEquals(0, mManager.getRemainingCallCount());

        dumpsysOnLogcat();

        mInjectedCurrentTimeMillis++; // Need to advance the clock for reset to work.
        mService.resetThrottlingInner(UserHandle.USER_SYSTEM);

        dumpsysOnLogcat();

        assertTrue(mManager.setDynamicShortcuts(list(si2, si3)));
        assertEquals(2, mManager.getDynamicShortcuts().size());

        // TODO Check max number

        runWithCaller(CALLING_PACKAGE_2, USER_10, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(makeShortcut("s1"))));
        });
    }

    public void testAddDynamicShortcuts() {
        setCaller(CALLING_PACKAGE_1, USER_0);

        final ShortcutInfo si1 = makeShortcut("shortcut1");
        final ShortcutInfo si2 = makeShortcut("shortcut2");
        final ShortcutInfo si3 = makeShortcut("shortcut3");

        assertEquals(3, mManager.getRemainingCallCount());

        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(2, mManager.getRemainingCallCount());
        assertShortcutIds(assertAllNotKeyFieldsOnly(
                mManager.getDynamicShortcuts()),
                "shortcut1");

        assertTrue(mManager.addDynamicShortcuts(list(si2, si3)));
        assertEquals(1, mManager.getRemainingCallCount());
        assertShortcutIds(assertAllNotKeyFieldsOnly(
                mManager.getDynamicShortcuts()),
                "shortcut1", "shortcut2", "shortcut3");

        // This should not crash.  It'll still consume the quota.
        assertTrue(mManager.addDynamicShortcuts(list()));
        assertEquals(0, mManager.getRemainingCallCount());
        assertShortcutIds(assertAllNotKeyFieldsOnly(
                mManager.getDynamicShortcuts()),
                "shortcut1", "shortcut2", "shortcut3");

        mInjectedCurrentTimeMillis += INTERVAL; // reset

        // Add with the same ID
        assertTrue(mManager.addDynamicShortcuts(list(makeShortcut("shortcut1"))));
        assertEquals(2, mManager.getRemainingCallCount());
        assertShortcutIds(assertAllNotKeyFieldsOnly(
                mManager.getDynamicShortcuts()),
                "shortcut1", "shortcut2", "shortcut3");

        // TODO Check max number

        // TODO Check fields.

        runWithCaller(CALLING_PACKAGE_2, USER_10, () -> {
            assertTrue(mManager.addDynamicShortcuts(list(makeShortcut("s1"))));
        });
    }

    public void testDeleteDynamicShortcuts() {
        final ShortcutInfo si1 = makeShortcut("shortcut1");
        final ShortcutInfo si2 = makeShortcut("shortcut2");
        final ShortcutInfo si3 = makeShortcut("shortcut3");
        final ShortcutInfo si4 = makeShortcut("shortcut4");

        assertTrue(mManager.setDynamicShortcuts(list(si1, si2, si3, si4)));
        assertShortcutIds(assertAllNotKeyFieldsOnly(
                mManager.getDynamicShortcuts()),
                "shortcut1", "shortcut2", "shortcut3", "shortcut4");

        assertEquals(2, mManager.getRemainingCallCount());

        mManager.removeDynamicShortcuts(list("shortcut1"));
        assertShortcutIds(assertAllNotKeyFieldsOnly(
                mManager.getDynamicShortcuts()),
                "shortcut2", "shortcut3", "shortcut4");

        mManager.removeDynamicShortcuts(list("shortcut1"));
        assertShortcutIds(assertAllNotKeyFieldsOnly(
                mManager.getDynamicShortcuts()),
                "shortcut2", "shortcut3", "shortcut4");

        mManager.removeDynamicShortcuts(list("shortcutXXX"));
        assertShortcutIds(assertAllNotKeyFieldsOnly(
                mManager.getDynamicShortcuts()),
                "shortcut2", "shortcut3", "shortcut4");

        mManager.removeDynamicShortcuts(list("shortcut2", "shortcut4"));
        assertShortcutIds(assertAllNotKeyFieldsOnly(
                mManager.getDynamicShortcuts()),
                "shortcut3");

        mManager.removeDynamicShortcuts(list("shortcut3"));
        assertShortcutIds(assertAllNotKeyFieldsOnly(
                mManager.getDynamicShortcuts()));

        // Still 2 calls left.
        assertEquals(2, mManager.getRemainingCallCount());
    }

    public void testDeleteAllDynamicShortcuts() {
        final ShortcutInfo si1 = makeShortcut("shortcut1");
        final ShortcutInfo si2 = makeShortcut("shortcut2");
        final ShortcutInfo si3 = makeShortcut("shortcut3");

        assertTrue(mManager.setDynamicShortcuts(list(si1, si2, si3)));
        assertShortcutIds(assertAllNotKeyFieldsOnly(
                mManager.getDynamicShortcuts()),
                "shortcut1", "shortcut2", "shortcut3");

        assertEquals(2, mManager.getRemainingCallCount());

        mManager.removeAllDynamicShortcuts();
        assertEquals(0, mManager.getDynamicShortcuts().size());
        assertEquals(2, mManager.getRemainingCallCount());

        // Note delete shouldn't affect throttling, so...
        assertEquals(0, mManager.getDynamicShortcuts().size());
        assertEquals(0, mManager.getDynamicShortcuts().size());
        assertEquals(0, mManager.getDynamicShortcuts().size());

        // This should still work.
        assertTrue(mManager.setDynamicShortcuts(list(si1, si2, si3)));
        assertEquals(3, mManager.getDynamicShortcuts().size());

        // Still 1 call left
        assertEquals(1, mManager.getRemainingCallCount());
    }

    public void testIcons() throws IOException {
        final Icon res32x32 = Icon.createWithResource(getTestContext(), R.drawable.black_32x32);
        final Icon res64x64 = Icon.createWithResource(getTestContext(), R.drawable.black_64x64);
        final Icon res512x512 = Icon.createWithResource(getTestContext(), R.drawable.black_512x512);

        final Icon bmp32x32 = Icon.createWithBitmap(BitmapFactory.decodeResource(
                getTestContext().getResources(), R.drawable.black_32x32));
        final Icon bmp64x64 = Icon.createWithBitmap(BitmapFactory.decodeResource(
                getTestContext().getResources(), R.drawable.black_64x64));
        final Icon bmp512x512 = Icon.createWithBitmap(BitmapFactory.decodeResource(
                getTestContext().getResources(), R.drawable.black_512x512));

        // Set from package 1
        setCaller(CALLING_PACKAGE_1);
        assertTrue(mManager.setDynamicShortcuts(list(
                makeShortcutWithIcon("res32x32", res32x32),
                makeShortcutWithIcon("res64x64", res64x64),
                makeShortcutWithIcon("bmp32x32", bmp32x32),
                makeShortcutWithIcon("bmp64x64", bmp64x64),
                makeShortcutWithIcon("bmp512x512", bmp512x512),
                makeShortcut("none")
        )));

        // getDynamicShortcuts() shouldn't return icons, thus assertAllNotHaveIcon().
        assertShortcutIds(assertAllNotHaveIcon(mManager.getDynamicShortcuts()),
                "res32x32",
                "res64x64",
                "bmp32x32",
                "bmp64x64",
                "bmp512x512",
                "none");

        // Call from another caller with the same ID, just to make sure storage is per-package.
        setCaller(CALLING_PACKAGE_2);
        assertTrue(mManager.setDynamicShortcuts(list(
                makeShortcutWithIcon("res32x32", res512x512),
                makeShortcutWithIcon("res64x64", res512x512),
                makeShortcutWithIcon("none", res512x512)
        )));
        assertShortcutIds(assertAllNotHaveIcon(mManager.getDynamicShortcuts()),
                "res32x32",
                "res64x64",
                "none");

        // Different profile.  Note the names and the contents don't match.
        setCaller(CALLING_PACKAGE_1, USER_P0);
        assertTrue(mManager.setDynamicShortcuts(list(
                makeShortcutWithIcon("res32x32", res512x512),
                makeShortcutWithIcon("bmp32x32", bmp512x512)
        )));
        assertShortcutIds(assertAllNotHaveIcon(mManager.getDynamicShortcuts()),
                "res32x32",
                "bmp32x32");

        // Re-initialize and load from the files.
        mService.saveDirtyInfo();
        initService();

        // Load from launcher.
        Bitmap bmp;

        setCaller(LAUNCHER_1);
        // Check hasIconResource()/hasIconFile().
        assertShortcutIds(assertAllHaveIconResId(
                list(getShortcutInfoAsLauncher(CALLING_PACKAGE_1, "res32x32", USER_0))),
                "res32x32");

        assertShortcutIds(assertAllHaveIconResId(
                list(getShortcutInfoAsLauncher(CALLING_PACKAGE_1, "res64x64", USER_0))),
                "res64x64");

        assertShortcutIds(assertAllHaveIconFile(
                list(getShortcutInfoAsLauncher(CALLING_PACKAGE_1, "bmp32x32", USER_0))),
                "bmp32x32");

        assertShortcutIds(assertAllHaveIconFile(
                list(getShortcutInfoAsLauncher(CALLING_PACKAGE_1, "bmp64x64", USER_0))),
                "bmp64x64");

        assertShortcutIds(assertAllHaveIconFile(
                list(getShortcutInfoAsLauncher(CALLING_PACKAGE_1, "bmp512x512", USER_0))),
                "bmp512x512");

        assertShortcutIds(assertAllHaveIconResId(
                list(getShortcutInfoAsLauncher(CALLING_PACKAGE_1, "res32x32", USER_P0))),
                "res32x32");
        assertShortcutIds(assertAllHaveIconFile(
                list(getShortcutInfoAsLauncher(CALLING_PACKAGE_1, "bmp32x32", USER_P0))),
                "bmp32x32");

        // Check
        assertEquals(
                R.drawable.black_32x32,
                mLauncherApps.getShortcutIconResId(
                        getShortcutInfoAsLauncher(CALLING_PACKAGE_1, "res32x32", USER_0)));

        assertEquals(
                R.drawable.black_64x64,
                mLauncherApps.getShortcutIconResId(
                        getShortcutInfoAsLauncher(CALLING_PACKAGE_1, "res64x64", USER_0)));

        assertEquals(
                0, // because it's not a resource
                mLauncherApps.getShortcutIconResId(
                        getShortcutInfoAsLauncher(CALLING_PACKAGE_1, "bmp32x32", USER_0)));
        assertEquals(
                0, // because it's not a resource
                mLauncherApps.getShortcutIconResId(
                        getShortcutInfoAsLauncher(CALLING_PACKAGE_1, "bmp64x64", USER_0)));
        assertEquals(
                0, // because it's not a resource
                mLauncherApps.getShortcutIconResId(
                        getShortcutInfoAsLauncher(CALLING_PACKAGE_1, "bmp512x512", USER_0)));

        bmp = pfdToBitmap(mLauncherApps.getShortcutIconFd(
                getShortcutInfoAsLauncher(CALLING_PACKAGE_1, "bmp32x32", USER_0)));
        assertBitmapSize(32, 32, bmp);

        bmp = pfdToBitmap(mLauncherApps.getShortcutIconFd(
                getShortcutInfoAsLauncher(CALLING_PACKAGE_1, "bmp64x64", USER_0)));
        assertBitmapSize(64, 64, bmp);

        bmp = pfdToBitmap(mLauncherApps.getShortcutIconFd(
                getShortcutInfoAsLauncher(CALLING_PACKAGE_1, "bmp512x512", USER_0)));
        assertBitmapSize(128, 128, bmp);

        assertEquals(
                R.drawable.black_512x512,
                mLauncherApps.getShortcutIconResId(
                        getShortcutInfoAsLauncher(CALLING_PACKAGE_1, "res32x32", USER_P0)));
        // Should be 512x512, so shrunk.
        bmp = pfdToBitmap(mLauncherApps.getShortcutIconFd(
                getShortcutInfoAsLauncher(CALLING_PACKAGE_1, "bmp32x32", USER_P0)));
        assertBitmapSize(128, 128, bmp);

        // Also check the overload APIs too.
        assertEquals(
                R.drawable.black_32x32,
                mLauncherApps.getShortcutIconResId(CALLING_PACKAGE_1, "res32x32", HANDLE_USER_0));
        assertEquals(
                R.drawable.black_64x64,
                mLauncherApps.getShortcutIconResId(CALLING_PACKAGE_1, "res64x64", HANDLE_USER_0));
        assertEquals(
                R.drawable.black_512x512,
                mLauncherApps.getShortcutIconResId(CALLING_PACKAGE_1, "res32x32", HANDLE_USER_P0));
        bmp = pfdToBitmap(
                mLauncherApps.getShortcutIconFd(CALLING_PACKAGE_1, "bmp32x32", HANDLE_USER_P0));
        assertBitmapSize(128, 128, bmp);
    }

    public void testCleanupDanglingBitmaps() throws Exception {
        assertBitmapDirectories(USER_0, EMPTY_STRINGS);
        assertBitmapDirectories(USER_10, EMPTY_STRINGS);

        // Make some shortcuts with bitmap icons.
        final Icon bmp32x32 = Icon.createWithBitmap(BitmapFactory.decodeResource(
                getTestContext().getResources(), R.drawable.black_32x32));

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            mManager.setDynamicShortcuts(list(
                    makeShortcutWithIcon("s1", bmp32x32),
                    makeShortcutWithIcon("s2", bmp32x32),
                    makeShortcutWithIcon("s3", bmp32x32)
            ));
        });

        // Increment the time (which actually we don't have to), which is used for filenames.
        mInjectedCurrentTimeMillis++;

        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            mManager.setDynamicShortcuts(list(
                    makeShortcutWithIcon("s4", bmp32x32),
                    makeShortcutWithIcon("s5", bmp32x32),
                    makeShortcutWithIcon("s6", bmp32x32)
            ));
        });

        // Increment the time, which is used for filenames.
        mInjectedCurrentTimeMillis++;

        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            mManager.setDynamicShortcuts(list(
            ));
        });

        // For USER-10, let's try without updating the times.
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            mManager.setDynamicShortcuts(list(
                    makeShortcutWithIcon("10s1", bmp32x32),
                    makeShortcutWithIcon("10s2", bmp32x32),
                    makeShortcutWithIcon("10s3", bmp32x32)
            ));
        });
        runWithCaller(CALLING_PACKAGE_2, USER_10, () -> {
            mManager.setDynamicShortcuts(list(
                    makeShortcutWithIcon("10s4", bmp32x32),
                    makeShortcutWithIcon("10s5", bmp32x32),
                    makeShortcutWithIcon("10s6", bmp32x32)
            ));
        });
        runWithCaller(CALLING_PACKAGE_3, USER_10, () -> {
            mManager.setDynamicShortcuts(list(
            ));
        });

        dumpsysOnLogcat();

        // Check files and directories.
        // Package 3 has no bitmaps, so we don't create a directory.
        assertBitmapDirectories(USER_0, CALLING_PACKAGE_1, CALLING_PACKAGE_2);
        assertBitmapDirectories(USER_10, CALLING_PACKAGE_1, CALLING_PACKAGE_2);

        assertBitmapFiles(USER_0, CALLING_PACKAGE_1,
                getBitmapFilename(USER_0, CALLING_PACKAGE_1, "s1"),
                getBitmapFilename(USER_0, CALLING_PACKAGE_1, "s2"),
                getBitmapFilename(USER_0, CALLING_PACKAGE_1, "s3")
        );
        assertBitmapFiles(USER_0, CALLING_PACKAGE_2,
                getBitmapFilename(USER_0, CALLING_PACKAGE_2, "s4"),
                getBitmapFilename(USER_0, CALLING_PACKAGE_2, "s5"),
                getBitmapFilename(USER_0, CALLING_PACKAGE_2, "s6")
        );
        assertBitmapFiles(USER_0, CALLING_PACKAGE_3,
                EMPTY_STRINGS
        );
        assertBitmapFiles(USER_10, CALLING_PACKAGE_1,
                getBitmapFilename(USER_10, CALLING_PACKAGE_1, "10s1"),
                getBitmapFilename(USER_10, CALLING_PACKAGE_1, "10s2"),
                getBitmapFilename(USER_10, CALLING_PACKAGE_1, "10s3")
        );
        assertBitmapFiles(USER_10, CALLING_PACKAGE_2,
                getBitmapFilename(USER_10, CALLING_PACKAGE_2, "10s4"),
                getBitmapFilename(USER_10, CALLING_PACKAGE_2, "10s5"),
                getBitmapFilename(USER_10, CALLING_PACKAGE_2, "10s6")
        );
        assertBitmapFiles(USER_10, CALLING_PACKAGE_3,
                EMPTY_STRINGS
        );

        // Then create random directories and files.
        makeFile(mService.getUserBitmapFilePath(USER_0), "a.b.c").mkdir();
        makeFile(mService.getUserBitmapFilePath(USER_0), "d.e.f").mkdir();
        makeFile(mService.getUserBitmapFilePath(USER_0), "d.e.f", "123").createNewFile();
        makeFile(mService.getUserBitmapFilePath(USER_0), "d.e.f", "456").createNewFile();

        makeFile(mService.getUserBitmapFilePath(USER_0), CALLING_PACKAGE_3).mkdir();

        makeFile(mService.getUserBitmapFilePath(USER_0), CALLING_PACKAGE_1, "1").createNewFile();
        makeFile(mService.getUserBitmapFilePath(USER_0), CALLING_PACKAGE_1, "2").createNewFile();
        makeFile(mService.getUserBitmapFilePath(USER_0), CALLING_PACKAGE_1, "3").createNewFile();
        makeFile(mService.getUserBitmapFilePath(USER_0), CALLING_PACKAGE_1, "4").createNewFile();

        makeFile(mService.getUserBitmapFilePath(USER_10), "10a.b.c").mkdir();
        makeFile(mService.getUserBitmapFilePath(USER_10), "10d.e.f").mkdir();
        makeFile(mService.getUserBitmapFilePath(USER_10), "10d.e.f", "123").createNewFile();
        makeFile(mService.getUserBitmapFilePath(USER_10), "10d.e.f", "456").createNewFile();

        makeFile(mService.getUserBitmapFilePath(USER_10), CALLING_PACKAGE_2, "1").createNewFile();
        makeFile(mService.getUserBitmapFilePath(USER_10), CALLING_PACKAGE_2, "2").createNewFile();
        makeFile(mService.getUserBitmapFilePath(USER_10), CALLING_PACKAGE_2, "3").createNewFile();
        makeFile(mService.getUserBitmapFilePath(USER_10), CALLING_PACKAGE_2, "4").createNewFile();

        assertBitmapDirectories(USER_0, CALLING_PACKAGE_1, CALLING_PACKAGE_2, CALLING_PACKAGE_3,
                "a.b.c", "d.e.f");

        // Save and load.  When a user is loaded, we do the cleanup.
        mService.saveDirtyInfo();
        initService();

        mService.handleUnlockUser(USER_0);
        mService.handleUnlockUser(USER_10);
        mService.handleUnlockUser(20); // Make sure the logic will still work for nonexistent user.

        // The below check is the same as above, except this time USER_0 use the CALLING_PACKAGE_3
        // directory.

        assertBitmapDirectories(USER_0, CALLING_PACKAGE_1, CALLING_PACKAGE_2, CALLING_PACKAGE_3);
        assertBitmapDirectories(USER_10, CALLING_PACKAGE_1, CALLING_PACKAGE_2);

        assertBitmapFiles(USER_0, CALLING_PACKAGE_1,
                getBitmapFilename(USER_0, CALLING_PACKAGE_1, "s1"),
                getBitmapFilename(USER_0, CALLING_PACKAGE_1, "s2"),
                getBitmapFilename(USER_0, CALLING_PACKAGE_1, "s3")
        );
        assertBitmapFiles(USER_0, CALLING_PACKAGE_2,
                getBitmapFilename(USER_0, CALLING_PACKAGE_2, "s4"),
                getBitmapFilename(USER_0, CALLING_PACKAGE_2, "s5"),
                getBitmapFilename(USER_0, CALLING_PACKAGE_2, "s6")
        );
        assertBitmapFiles(USER_0, CALLING_PACKAGE_3,
                EMPTY_STRINGS
        );
        assertBitmapFiles(USER_10, CALLING_PACKAGE_1,
                getBitmapFilename(USER_10, CALLING_PACKAGE_1, "10s1"),
                getBitmapFilename(USER_10, CALLING_PACKAGE_1, "10s2"),
                getBitmapFilename(USER_10, CALLING_PACKAGE_1, "10s3")
        );
        assertBitmapFiles(USER_10, CALLING_PACKAGE_2,
                getBitmapFilename(USER_10, CALLING_PACKAGE_2, "10s4"),
                getBitmapFilename(USER_10, CALLING_PACKAGE_2, "10s5"),
                getBitmapFilename(USER_10, CALLING_PACKAGE_2, "10s6")
        );
        assertBitmapFiles(USER_10, CALLING_PACKAGE_3,
                EMPTY_STRINGS
        );
    }

    protected void checkShrinkBitmap(int expectedWidth, int expectedHeight, int resId, int maxSize) {
        assertBitmapSize(expectedWidth, expectedHeight,
                ShortcutService.shrinkBitmap(BitmapFactory.decodeResource(
                        getTestContext().getResources(), resId),
                        maxSize));
    }

    public void testShrinkBitmap() {
        checkShrinkBitmap(32, 32, R.drawable.black_512x512, 32);
        checkShrinkBitmap(511, 511, R.drawable.black_512x512, 511);
        checkShrinkBitmap(512, 512, R.drawable.black_512x512, 512);

        checkShrinkBitmap(1024, 4096, R.drawable.black_1024x4096, 4096);
        checkShrinkBitmap(1024, 4096, R.drawable.black_1024x4096, 4100);
        checkShrinkBitmap(512, 2048, R.drawable.black_1024x4096, 2048);

        checkShrinkBitmap(4096, 1024, R.drawable.black_4096x1024, 4096);
        checkShrinkBitmap(4096, 1024, R.drawable.black_4096x1024, 4100);
        checkShrinkBitmap(2048, 512, R.drawable.black_4096x1024, 2048);
    }

    protected File openIconFileForWriteAndGetPath(int userId, String packageName)
            throws IOException {
        // Shortcut IDs aren't used in the path, so just pass the same ID.
        final FileOutputStreamWithPath out =
                mService.openIconFileForWrite(userId, makePackageShortcut(packageName, "id"));
        out.close();
        return out.getFile();
    }

    public void testOpenIconFileForWrite() throws IOException {
        mInjectedCurrentTimeMillis = 1000;

        final File p10_1_1 = openIconFileForWriteAndGetPath(10, CALLING_PACKAGE_1);
        final File p10_1_2 = openIconFileForWriteAndGetPath(10, CALLING_PACKAGE_1);

        final File p10_2_1 = openIconFileForWriteAndGetPath(10, CALLING_PACKAGE_2);
        final File p10_2_2 = openIconFileForWriteAndGetPath(10, CALLING_PACKAGE_2);

        final File p11_1_1 = openIconFileForWriteAndGetPath(11, CALLING_PACKAGE_1);
        final File p11_1_2 = openIconFileForWriteAndGetPath(11, CALLING_PACKAGE_1);

        mInjectedCurrentTimeMillis++;

        final File p10_1_3 = openIconFileForWriteAndGetPath(10, CALLING_PACKAGE_1);
        final File p10_1_4 = openIconFileForWriteAndGetPath(10, CALLING_PACKAGE_1);
        final File p10_1_5 = openIconFileForWriteAndGetPath(10, CALLING_PACKAGE_1);

        final File p10_2_3 = openIconFileForWriteAndGetPath(10, CALLING_PACKAGE_2);
        final File p11_1_3 = openIconFileForWriteAndGetPath(11, CALLING_PACKAGE_1);

        // Make sure their paths are all unique
        assertAllUnique(list(
                p10_1_1,
                p10_1_2,
                p10_1_3,
                p10_1_4,
                p10_1_5,

                p10_2_1,
                p10_2_2,
                p10_2_3,

                p11_1_1,
                p11_1_2,
                p11_1_3
        ));

        // Check each set has the same parent.
        assertEquals(p10_1_1.getParent(), p10_1_2.getParent());
        assertEquals(p10_1_1.getParent(), p10_1_3.getParent());
        assertEquals(p10_1_1.getParent(), p10_1_4.getParent());
        assertEquals(p10_1_1.getParent(), p10_1_5.getParent());

        assertEquals(p10_2_1.getParent(), p10_2_2.getParent());
        assertEquals(p10_2_1.getParent(), p10_2_3.getParent());

        assertEquals(p11_1_1.getParent(), p11_1_2.getParent());
        assertEquals(p11_1_1.getParent(), p11_1_3.getParent());

        // Check the parents are still unique.
        assertAllUnique(list(
                p10_1_1.getParent(),
                p10_2_1.getParent(),
                p11_1_1.getParent()
        ));

        // All files created at the same time for the same package/user, expcet for the first ones,
        // will have "_" in the path.
        assertFalse(p10_1_1.getName().contains("_"));
        assertTrue(p10_1_2.getName().contains("_"));
        assertFalse(p10_1_3.getName().contains("_"));
        assertTrue(p10_1_4.getName().contains("_"));
        assertTrue(p10_1_5.getName().contains("_"));

        assertFalse(p10_2_1.getName().contains("_"));
        assertTrue(p10_2_2.getName().contains("_"));
        assertFalse(p10_2_3.getName().contains("_"));

        assertFalse(p11_1_1.getName().contains("_"));
        assertTrue(p11_1_2.getName().contains("_"));
        assertFalse(p11_1_3.getName().contains("_"));
    }

    public void testUpdateShortcuts() {
        runWithCaller(CALLING_PACKAGE_1, UserHandle.USER_SYSTEM, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"),
                    makeShortcut("s2"),
                    makeShortcut("s3"),
                    makeShortcut("s4"),
                    makeShortcut("s5")
            )));
        });
        runWithCaller(CALLING_PACKAGE_2, UserHandle.USER_SYSTEM, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"),
                    makeShortcut("s2"),
                    makeShortcut("s3"),
                    makeShortcut("s4"),
                    makeShortcut("s5")
            )));
        });
        runWithCaller(LAUNCHER_1, UserHandle.USER_SYSTEM, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("s2", "s3"),
                    getCallingUser());
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2, list("s4", "s5"),
                    getCallingUser());
        });
        runWithCaller(CALLING_PACKAGE_1, UserHandle.USER_SYSTEM, () -> {
            mManager.removeDynamicShortcuts(list("s1"));
            mManager.removeDynamicShortcuts(list("s2"));
        });
        runWithCaller(CALLING_PACKAGE_2, UserHandle.USER_SYSTEM, () -> {
            mManager.removeDynamicShortcuts(list("s1"));
            mManager.removeDynamicShortcuts(list("s3"));
            mManager.removeDynamicShortcuts(list("s5"));
        });
        runWithCaller(CALLING_PACKAGE_1, UserHandle.USER_SYSTEM, () -> {
            assertShortcutIds(assertAllDynamic(
                    mManager.getDynamicShortcuts()),
                    "s3", "s4", "s5");
            assertShortcutIds(assertAllPinned(
                    mManager.getPinnedShortcuts()),
                    "s2", "s3");
        });
        runWithCaller(CALLING_PACKAGE_2, UserHandle.USER_SYSTEM, () -> {
            assertShortcutIds(assertAllDynamic(
                    mManager.getDynamicShortcuts()),
                    "s2", "s4");
            assertShortcutIds(assertAllPinned(
                    mManager.getPinnedShortcuts()),
                    "s4", "s5");
        });

        runWithCaller(CALLING_PACKAGE_1, UserHandle.USER_SYSTEM, () -> {
            ShortcutInfo s2 = makeShortcutBuilder()
                    .setId("s2")
                    .setIcon(Icon.createWithResource(getTestContext(), R.drawable.black_32x32))
                    .build();

            ShortcutInfo s4 = makeShortcutBuilder()
                    .setId("s4")
                    .setTitle("new title")
                    .build();

            mManager.updateShortcuts(list(s2, s4));
        });
        runWithCaller(CALLING_PACKAGE_2, UserHandle.USER_SYSTEM, () -> {
            ShortcutInfo s2 = makeShortcutBuilder()
                    .setId("s2")
                    .setIntent(makeIntent(Intent.ACTION_ANSWER, ShortcutActivity.class,
                            "key1", "val1"))
                    .build();

            ShortcutInfo s4 = makeShortcutBuilder()
                    .setId("s4")
                    .setIntent(new Intent(Intent.ACTION_ALL_APPS))
                    .build();

            mManager.updateShortcuts(list(s2, s4));
        });

        runWithCaller(CALLING_PACKAGE_1, UserHandle.USER_SYSTEM, () -> {
            assertShortcutIds(assertAllDynamic(
                    mManager.getDynamicShortcuts()),
                    "s3", "s4", "s5");
            assertShortcutIds(assertAllPinned(
                    mManager.getPinnedShortcuts()),
                    "s2", "s3");

            ShortcutInfo s = getCallerShortcut("s2");
            assertTrue(s.hasIconResource());
            assertEquals(R.drawable.black_32x32, s.getIconResourceId());
            assertEquals("Title-s2", s.getTitle());

            s = getCallerShortcut("s4");
            assertFalse(s.hasIconResource());
            assertEquals(0, s.getIconResourceId());
            assertEquals("new title", s.getTitle());
        });
        runWithCaller(CALLING_PACKAGE_2, UserHandle.USER_SYSTEM, () -> {
            assertShortcutIds(assertAllDynamic(
                    mManager.getDynamicShortcuts()),
                    "s2", "s4");
            assertShortcutIds(assertAllPinned(
                    mManager.getPinnedShortcuts()),
                    "s4", "s5");

            ShortcutInfo s = getCallerShortcut("s2");
            assertFalse(s.hasIconResource());
            assertEquals(0, s.getIconResourceId());
            assertEquals("Title-s2", s.getTitle());
            assertEquals(Intent.ACTION_ANSWER, s.getIntent().getAction());
            assertEquals(1, s.getIntent().getExtras().size());

            s = getCallerShortcut("s4");
            assertFalse(s.hasIconResource());
            assertEquals(0, s.getIconResourceId());
            assertEquals("Title-s4", s.getTitle());
            assertEquals(Intent.ACTION_ALL_APPS, s.getIntent().getAction());
            assertBundleEmpty(s.getIntent().getExtras());
        });
        // TODO Check with other fields too.

        // TODO Check bitmap removal too.

        runWithCaller(CALLING_PACKAGE_2, USER_11, () -> {
            mManager.updateShortcuts(list());
        });
    }

    // === Test for launcher side APIs ===

    public void testGetShortcuts() {

        // Set up shortcuts.

        setCaller(CALLING_PACKAGE_1);
        final ShortcutInfo s1_1 = makeShortcutWithTimestamp("s1", 5000);
        final ShortcutInfo s1_2 = makeShortcutWithTimestamp("s2", 1000);

        assertTrue(mManager.setDynamicShortcuts(list(s1_1, s1_2)));

        setCaller(CALLING_PACKAGE_2);
        final ShortcutInfo s2_2 = makeShortcutWithTimestamp("s2", 1500);
        final ShortcutInfo s2_3 = makeShortcutWithTimestampWithActivity("s3", 3000,
                makeComponent(ShortcutActivity2.class));
        final ShortcutInfo s2_4 = makeShortcutWithTimestampWithActivity("s4", 500,
                makeComponent(ShortcutActivity.class));
        assertTrue(mManager.setDynamicShortcuts(list(s2_2, s2_3, s2_4)));

        setCaller(CALLING_PACKAGE_3);
        final ShortcutInfo s3_2 = makeShortcutWithTimestamp("s3", START_TIME + 5000);
        assertTrue(mManager.setDynamicShortcuts(list(s3_2)));

        setCaller(LAUNCHER_1);

        // Get dynamic
        assertAllDynamic(assertAllHaveTitle(assertAllNotHaveIntents(assertAllStringsResolved(
                assertShortcutIds(
                        assertAllNotKeyFieldsOnly(
                                mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                        /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), getCallingUser())),
                        "s1", "s2")))));

        // Get pinned
        assertShortcutIds(
                mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                        /* activity =*/ null,
                        ShortcutQuery.FLAG_GET_PINNED), getCallingUser())
                /* none */);

        // Get both, with timestamp
        assertAllDynamic(assertAllHaveTitle(assertAllNotHaveIntents(assertShortcutIds(
                assertAllNotKeyFieldsOnly(mLauncherApps.getShortcuts(buildQuery(
                        /* time =*/ 1000, CALLING_PACKAGE_2,
                        /* activity =*/ null,
                        ShortcutQuery.FLAG_GET_PINNED | ShortcutQuery.FLAG_GET_DYNAMIC),
                        getCallingUser())),
                "s2", "s3"))));

        // FLAG_GET_KEY_FIELDS_ONLY
        assertAllDynamic(assertAllNotHaveTitle(assertAllNotHaveIntents(assertShortcutIds(
                assertAllKeyFieldsOnly(mLauncherApps.getShortcuts(buildQuery(
                        /* time =*/ 1000, CALLING_PACKAGE_2,
                        /* activity =*/ null,
                        ShortcutQuery.FLAG_GET_DYNAMIC | ShortcutQuery.FLAG_GET_KEY_FIELDS_ONLY),
                        getCallingUser())),
                "s2", "s3"))));

        // Filter by activity
        assertAllDynamic(assertAllHaveTitle(assertAllNotHaveIntents(assertShortcutIds(
                assertAllNotKeyFieldsOnly(mLauncherApps.getShortcuts(buildQuery(
                        /* time =*/ 0, CALLING_PACKAGE_2,
                        new ComponentName(CALLING_PACKAGE_2, ShortcutActivity.class.getName()),
                        ShortcutQuery.FLAG_GET_PINNED | ShortcutQuery.FLAG_GET_DYNAMIC),
                        getCallingUser())),
                "s4"))));

        // With ID.
        assertAllDynamic(assertAllNotHaveTitle(assertAllNotHaveIntents(assertShortcutIds(
                assertAllKeyFieldsOnly(mLauncherApps.getShortcuts(buildQuery(
                        /* time =*/ 1000, CALLING_PACKAGE_2, list("s3"),
                        /* activity =*/ null,
                        ShortcutQuery.FLAG_GET_DYNAMIC | ShortcutQuery.FLAG_GET_KEY_FIELDS_ONLY),
                        getCallingUser())),
                "s3"))));
        assertAllDynamic(assertAllNotHaveTitle(assertAllNotHaveIntents(assertShortcutIds(
                assertAllKeyFieldsOnly(mLauncherApps.getShortcuts(buildQuery(
                        /* time =*/ 1000, CALLING_PACKAGE_2, list("s3", "s2", "ss"),
                        /* activity =*/ null,
                        ShortcutQuery.FLAG_GET_DYNAMIC | ShortcutQuery.FLAG_GET_KEY_FIELDS_ONLY),
                        getCallingUser())),
                "s2", "s3"))));
        assertAllDynamic(assertAllNotHaveTitle(assertAllNotHaveIntents(assertShortcutIds(
                assertAllKeyFieldsOnly(mLauncherApps.getShortcuts(buildQuery(
                        /* time =*/ 1000, CALLING_PACKAGE_2, list("s3x", "s2x"),
                        /* activity =*/ null,
                        ShortcutQuery.FLAG_GET_DYNAMIC | ShortcutQuery.FLAG_GET_KEY_FIELDS_ONLY),
                        getCallingUser()))
                /* empty */))));
        assertAllDynamic(assertAllNotHaveTitle(assertAllNotHaveIntents(assertShortcutIds(
                assertAllKeyFieldsOnly(mLauncherApps.getShortcuts(buildQuery(
                        /* time =*/ 1000, CALLING_PACKAGE_2, list(),
                        /* activity =*/ null,
                        ShortcutQuery.FLAG_GET_DYNAMIC | ShortcutQuery.FLAG_GET_KEY_FIELDS_ONLY),
                        getCallingUser()))
                /* empty */))));

        // Pin some shortcuts.
        mLauncherApps.pinShortcuts(CALLING_PACKAGE_2,
                list("s3", "s4"), getCallingUser());

        // Pinned ones only
        assertAllPinned(assertAllHaveTitle(assertAllNotHaveIntents(assertShortcutIds(
                assertAllNotKeyFieldsOnly(mLauncherApps.getShortcuts(buildQuery(
                        /* time =*/ 1000, CALLING_PACKAGE_2,
                        /* activity =*/ null,
                        ShortcutQuery.FLAG_GET_PINNED),
                        getCallingUser())),
                "s3"))));

        // All packages.
        assertShortcutIds(assertAllNotKeyFieldsOnly(
                mLauncherApps.getShortcuts(buildQuery(
                        /* time =*/ 5000, /* package= */ null,
                        /* activity =*/ null,
                        ShortcutQuery.FLAG_GET_DYNAMIC | ShortcutQuery.FLAG_GET_PINNED),
                        getCallingUser())),
                "s1", "s3");

        assertExpectException(
                IllegalArgumentException.class, "package name must also be set", () -> {
                    mLauncherApps.getShortcuts(buildQuery(
                    /* time =*/ 0, /* package= */ null, list("id"),
                    /* activity =*/ null, /* flags */ 0), getCallingUser());
                });

        // TODO More tests: pinned but dynamic.
    }

    public void testGetShortcuts_resolveStrings() throws Exception {
        doAnswer(pmInvocation -> {
            assertEquals(Process.SYSTEM_UID, mInjectedCallingUid);

            final String packageName = (String) pmInvocation.getArguments()[0];
            final int userId = (Integer) pmInvocation.getArguments()[1];

            final Resources res = mock(Resources.class);
            doAnswer(resInvocation -> {
                final int resId = (Integer) resInvocation.getArguments()[0];

                return "string-" + packageName + "-user:" + userId + "-res:" + resId;
            }).when(res).getString(anyInt());
            return res;
        }).when(mMockPackageManager).getResourcesForApplicationAsUser(anyString(), anyInt());

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            ShortcutInfo si = new ShortcutInfo.Builder(mClientContext)
                    .setId("id")
                    .setActivity(new ComponentName(mClientContext, "dummy"))
                    .setTitleResId(10)
                    .setTextResId(11)
                    .setDisabledMessageResId(12)
                    .setIntent(makeIntent("action", ShortcutActivity.class))
                    .build();
            mManager.setDynamicShortcuts(list(si));
        });
        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            ShortcutInfo si = new ShortcutInfo.Builder(mClientContext)
                    .setId("id")
                    .setActivity(new ComponentName(mClientContext, "dummy"))
                    .setTitleResId(10)
                    .setTextResId(11)
                    .setDisabledMessageResId(12)
                    .setIntent(makeIntent("action", ShortcutActivity.class))
                    .build();
            mManager.setDynamicShortcuts(list(si));
        });

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            final ShortcutQuery q = new ShortcutQuery();
            q.setQueryFlags(ShortcutQuery.FLAG_GET_DYNAMIC);

            // USER 0
            List<ShortcutInfo> ret = assertShortcutIds(
                    assertAllStringsResolved(mLauncherApps.getShortcuts(q, HANDLE_USER_0)),
                    "id");
            assertEquals("string-com.android.test.1-user:0-res:10", ret.get(0).getTitle());
            assertEquals("string-com.android.test.1-user:0-res:11", ret.get(0).getText());
            assertEquals("string-com.android.test.1-user:0-res:12",
                    ret.get(0).getDisabledMessage());

            // USER P0
            ret = assertShortcutIds(
                    assertAllStringsResolved(mLauncherApps.getShortcuts(q, HANDLE_USER_P0)),
                    "id");
            assertEquals("string-com.android.test.1-user:20-res:10", ret.get(0).getTitle());
            assertEquals("string-com.android.test.1-user:20-res:11", ret.get(0).getText());
            assertEquals("string-com.android.test.1-user:20-res:12",
                    ret.get(0).getDisabledMessage());
        });
    }

    // TODO resource
    public void testGetShortcutInfo() {
        // Create shortcuts.
        setCaller(CALLING_PACKAGE_1);
        final ShortcutInfo s1_1 = makeShortcut(
                "s1",
                "Title 1",
                makeComponent(ShortcutActivity.class),
                /* icon =*/ null,
                makeIntent(Intent.ACTION_ASSIST, ShortcutActivity2.class,
                        "key1", "val1", "nest", makeBundle("key", 123)),
                /* weight */ 10);

        final ShortcutInfo s1_2 = makeShortcut(
                "s2",
                "Title 2",
                /* activity */ null,
                /* icon =*/ null,
                makeIntent(Intent.ACTION_ASSIST, ShortcutActivity3.class),
                /* weight */ 12);

        assertTrue(mManager.setDynamicShortcuts(list(s1_1, s1_2)));
        dumpsysOnLogcat();

        setCaller(CALLING_PACKAGE_2);
        final ShortcutInfo s2_1 = makeShortcut(
                "s1",
                "ABC",
                makeComponent(ShortcutActivity2.class),
                /* icon =*/ null,
                makeIntent(Intent.ACTION_ANSWER, ShortcutActivity2.class,
                        "key1", "val1", "nest", makeBundle("key", 123)),
                /* weight */ 10);
        assertTrue(mManager.setDynamicShortcuts(list(s2_1)));
        dumpsysOnLogcat();

        // Pin some.
        setCaller(LAUNCHER_1);

        mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                list("s2"), getCallingUser());

        dumpsysOnLogcat();

        // Delete some.
        setCaller(CALLING_PACKAGE_1);
        assertShortcutIds(mManager.getPinnedShortcuts(), "s2");
        mManager.removeDynamicShortcuts(list("s2"));
        assertShortcutIds(mManager.getPinnedShortcuts(), "s2");

        dumpsysOnLogcat();

        setCaller(LAUNCHER_1);
        List<ShortcutInfo> list;

        // Note we don't guarantee the orders.
        list = assertShortcutIds(assertAllHaveTitle(assertAllNotHaveIntents(
                assertAllNotKeyFieldsOnly(
                        mLauncherApps.getShortcutInfo(CALLING_PACKAGE_1,
                                list("s2", "s1", "s3", null), getCallingUser())))),
                "s1", "s2");
        assertEquals("Title 1", findById(list, "s1").getTitle());
        assertEquals("Title 2", findById(list, "s2").getTitle());

        assertShortcutIds(assertAllHaveTitle(assertAllNotHaveIntents(
                mLauncherApps.getShortcutInfo(CALLING_PACKAGE_1,
                        list("s3"), getCallingUser())))
                /* none */);

        list = assertShortcutIds(assertAllHaveTitle(assertAllNotHaveIntents(
                mLauncherApps.getShortcutInfo(CALLING_PACKAGE_2,
                        list("s1", "s2", "s3"), getCallingUser()))),
                "s1");
        assertEquals("ABC", findById(list, "s1").getTitle());
    }

    public void testPinShortcutAndGetPinnedShortcuts() {
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            final ShortcutInfo s1_1 = makeShortcutWithTimestamp("s1", 1000);
            final ShortcutInfo s1_2 = makeShortcutWithTimestamp("s2", 2000);

            assertTrue(mManager.setDynamicShortcuts(list(s1_1, s1_2)));
        });

        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            final ShortcutInfo s2_2 = makeShortcutWithTimestamp("s2", 1500);
            final ShortcutInfo s2_3 = makeShortcutWithTimestamp("s3", 3000);
            final ShortcutInfo s2_4 = makeShortcutWithTimestamp("s4", 500);
            assertTrue(mManager.setDynamicShortcuts(list(s2_2, s2_3, s2_4)));
        });

        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            final ShortcutInfo s3_2 = makeShortcutWithTimestamp("s2", 1000);
            assertTrue(mManager.setDynamicShortcuts(list(s3_2)));
        });

        // Pin some.
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                    list("s2", "s3"), getCallingUser());

            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2,
                    list("s3", "s4", "s5"), getCallingUser());

            mLauncherApps.pinShortcuts(CALLING_PACKAGE_3,
                    list("s3"), getCallingUser());  // Note ID doesn't exist
        });

        // Delete some.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertShortcutIds(mManager.getPinnedShortcuts(), "s2");
            mManager.removeDynamicShortcuts(list("s2"));
            assertShortcutIds(mManager.getPinnedShortcuts(), "s2");

            assertShortcutIds(mManager.getDynamicShortcuts(), "s1");
        });

        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertShortcutIds(mManager.getPinnedShortcuts(), "s3", "s4");
            mManager.removeDynamicShortcuts(list("s3"));
            assertShortcutIds(mManager.getPinnedShortcuts(), "s3", "s4");

            assertShortcutIds(mManager.getDynamicShortcuts(), "s2", "s4");
        });

        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            assertShortcutIds(mManager.getPinnedShortcuts() /* none */);
            mManager.removeDynamicShortcuts(list("s2"));
            assertShortcutIds(mManager.getPinnedShortcuts() /* none */);

            assertEmpty(mManager.getDynamicShortcuts());
        });

        // Get pinned shortcuts from launcher
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            // CALLING_PACKAGE_1 deleted s2, but it's pinned, so it still exists.
            assertShortcutIds(assertAllPinned(assertAllNotKeyFieldsOnly(assertAllEnabled(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser())))),
                    "s2");

            assertShortcutIds(assertAllPinned(assertAllNotKeyFieldsOnly(assertAllEnabled(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser())))),
                    "s3", "s4");

            assertShortcutIds(assertAllPinned(assertAllNotKeyFieldsOnly(assertAllEnabled(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_3,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser()))))
                    /* none */);
        });
    }

    /**
     * This is similar to the above test, except it used "disable" instead of "remove".  It also
     * does "enable".
     */
    public void testDisableAndEnableShortcuts() {
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            final ShortcutInfo s1_1 = makeShortcutWithTimestamp("s1", 1000);
            final ShortcutInfo s1_2 = makeShortcutWithTimestamp("s2", 2000);

            assertTrue(mManager.setDynamicShortcuts(list(s1_1, s1_2)));
        });

        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            final ShortcutInfo s2_2 = makeShortcutWithTimestamp("s2", 1500);
            final ShortcutInfo s2_3 = makeShortcutWithTimestamp("s3", 3000);
            final ShortcutInfo s2_4 = makeShortcutWithTimestamp("s4", 500);
            assertTrue(mManager.setDynamicShortcuts(list(s2_2, s2_3, s2_4)));
        });

        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            final ShortcutInfo s3_2 = makeShortcutWithTimestamp("s2", 1000);
            assertTrue(mManager.setDynamicShortcuts(list(s3_2)));
        });

        // Pin some.
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                    list("s2", "s3"), getCallingUser());

            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2,
                    list("s3", "s4", "s5"), getCallingUser());

            mLauncherApps.pinShortcuts(CALLING_PACKAGE_3,
                    list("s3"), getCallingUser());  // Note ID doesn't exist
        });

        // Disable some.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertShortcutIds(mManager.getPinnedShortcuts(), "s2");

            mManager.disableShortcuts(list("s2"));

            assertShortcutIds(mManager.getPinnedShortcuts(), "s2");
            assertShortcutIds(mManager.getDynamicShortcuts(), "s1");
        });

        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertShortcutIds(mManager.getPinnedShortcuts(), "s3", "s4");

            // disable should work even if a shortcut is not dynamic, so try calling "remove" first
            // here.
            mManager.removeDynamicShortcuts(list("s3"));
            mManager.disableShortcuts(list("s3"));

            assertShortcutIds(mManager.getPinnedShortcuts(), "s3", "s4");
            assertShortcutIds(mManager.getDynamicShortcuts(), "s2", "s4");
        });

        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            assertShortcutIds(mManager.getPinnedShortcuts() /* none */);

            mManager.disableShortcuts(list("s2"));

            assertShortcutIds(mManager.getPinnedShortcuts() /* none */);

            assertEmpty(mManager.getDynamicShortcuts());
            assertEmpty(getCallerShortcuts());
        });

        // Get pinned shortcuts from launcher
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            // CALLING_PACKAGE_1 deleted s2, but it's pinned, so it still exists.
            assertShortcutIds(assertAllPinned(assertAllNotKeyFieldsOnly(assertAllDisabled(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser())))),
                    "s2");
            assertFalse(mLauncherApps.startShortcut(
                    CALLING_PACKAGE_1, "s2", null, null, HANDLE_USER_0));

            assertShortcutIds(assertAllPinned(assertAllNotKeyFieldsOnly(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser()))),
                    "s3", "s4");
            assertFalse(mLauncherApps.startShortcut(
                    CALLING_PACKAGE_2, "s3", null, null, HANDLE_USER_0));
            assertTrue(mLauncherApps.startShortcut(
                    CALLING_PACKAGE_2, "s4", null, null, HANDLE_USER_0));

            assertShortcutIds(assertAllPinned(assertAllNotKeyFieldsOnly(assertAllEnabled(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_3,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser()))))
                    /* none */);
        });

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            mManager.enableShortcuts(list("s2"));

            assertShortcutIds(mManager.getPinnedShortcuts(), "s2");
            assertShortcutIds(mManager.getDynamicShortcuts(), "s1");
        });
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            // CALLING_PACKAGE_1 deleted s2, but it's pinned, so it still exists.
            assertShortcutIds(assertAllPinned(assertAllNotKeyFieldsOnly(assertAllEnabled(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser())))),
                    "s2");
            assertTrue(mLauncherApps.startShortcut(
                    CALLING_PACKAGE_1, "s2", null, null, HANDLE_USER_0));
        });
    }

    public void testPinShortcutAndGetPinnedShortcuts_multi() {
        // Create some shortcuts.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"))));
        });

        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"))));
        });

        dumpsysOnLogcat();

        // Pin some.
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                    list("s3", "s4"), getCallingUser());

            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2,
                    list("s1", "s2", "s4"), getCallingUser());
        });

        dumpsysOnLogcat();

        // Delete some.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertShortcutIds(mManager.getPinnedShortcuts(), "s3");
            mManager.removeDynamicShortcuts(list("s3"));
            assertShortcutIds(mManager.getPinnedShortcuts(), "s3");
        });

        dumpsysOnLogcat();

        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertShortcutIds(mManager.getPinnedShortcuts(), "s1", "s2");
            mManager.removeDynamicShortcuts(list("s1"));
            mManager.removeDynamicShortcuts(list("s3"));
            assertShortcutIds(mManager.getPinnedShortcuts(), "s1", "s2");
        });

        dumpsysOnLogcat();

        // Get pinned shortcuts from launcher
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertShortcutIds(assertAllPinned(assertAllNotKeyFieldsOnly(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser()))),
                    "s3");

            assertShortcutIds(assertAllPinned(assertAllNotKeyFieldsOnly(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser()))),
                    "s1", "s2");

            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED
                                    | ShortcutQuery.FLAG_GET_DYNAMIC), getCallingUser())),
                    "s1", "s2", "s3");

            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED
                                    | ShortcutQuery.FLAG_GET_DYNAMIC), getCallingUser())),
                    "s1", "s2");
        });

        dumpsysOnLogcat();

        runWithCaller(LAUNCHER_2, USER_0, () -> {
            // Launcher2 still has no pinned ones.
            assertShortcutIds(assertAllPinned(assertAllNotKeyFieldsOnly(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser())))
                    /* none */);
            assertShortcutIds(assertAllPinned(assertAllNotKeyFieldsOnly(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser())))
                    /* none */);

            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED
                                    | ShortcutQuery.FLAG_GET_DYNAMIC), getCallingUser())),
                    "s1", "s2");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED
                                    | ShortcutQuery.FLAG_GET_DYNAMIC), getCallingUser())),
                    "s2");

            // Now pin some.
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                    list("s1", "s2"), getCallingUser());

            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2,
                    list("s1", "s2"), getCallingUser());

            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED
                                    | ShortcutQuery.FLAG_GET_DYNAMIC), getCallingUser())),
                    "s1", "s2");

            // S1 was not visible to it, so shouldn't be pinned.
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED
                                    | ShortcutQuery.FLAG_GET_DYNAMIC), getCallingUser())),
                    "s2");
        });

        // Re-initialize and load from the files.
        mService.saveDirtyInfo();
        initService();

        // Load from file.
        mService.handleUnlockUser(USER_0);

        // Make sure package info is restored too.
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertShortcutIds(assertAllPinned(assertAllNotKeyFieldsOnly(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser()))),
                    "s3");
            assertShortcutIds(assertAllPinned(assertAllNotKeyFieldsOnly(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser()))),
                    "s1", "s2");
        });
        runWithCaller(LAUNCHER_2, USER_0, () -> {
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED
                                    | ShortcutQuery.FLAG_GET_DYNAMIC), getCallingUser())),
                    "s1", "s2");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED
                                    | ShortcutQuery.FLAG_GET_DYNAMIC), getCallingUser())),
                    "s2");
        });

        // Delete all dynamic.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            mManager.removeAllDynamicShortcuts();

            assertEquals(0, mManager.getDynamicShortcuts().size());
            assertShortcutIds(assertAllPinned(mManager.getPinnedShortcuts()), "s1", "s2", "s3");
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            mManager.removeAllDynamicShortcuts();

            assertEquals(0, mManager.getDynamicShortcuts().size());
            assertShortcutIds(assertAllPinned(mManager.getPinnedShortcuts()), "s2", "s1");
        });

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertShortcutIds(assertAllPinned(assertAllNotKeyFieldsOnly(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser()))),
                    "s3");

            assertShortcutIds(assertAllPinned(assertAllNotKeyFieldsOnly(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser()))),
                    "s1", "s2");

            // from all packages.
            assertShortcutIds(assertAllPinned(assertAllNotKeyFieldsOnly(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, null,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser()))),
                    "s1", "s2", "s3");

            // Update pined.  Note s2 and s3 are actually available, but not visible to this
            // launcher, so still can't be pinned.
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("s1", "s2", "s3", "s4"),
                    getCallingUser());

            assertShortcutIds(assertAllPinned(assertAllNotKeyFieldsOnly(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser()))),
                    "s3");
        });
        // Re-publish s1.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.addDynamicShortcuts(list(makeShortcut("s1"))));

            assertShortcutIds(assertAllDynamic(mManager.getDynamicShortcuts()), "s1");
            assertShortcutIds(assertAllPinned(mManager.getPinnedShortcuts()), "s1", "s2", "s3");
        });
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertShortcutIds(assertAllPinned(assertAllNotKeyFieldsOnly(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser()))),
                    "s3");

            // Now "s1" is visible, so can be pinned.
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("s1", "s2", "s3", "s4"),
                    getCallingUser());

            assertShortcutIds(assertAllPinned(assertAllNotKeyFieldsOnly(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser()))),
                    "s1", "s3");
        });

        // Now clear pinned shortcuts.  First, from launcher 1.
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list(), getCallingUser());
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2, list(), getCallingUser());

            assertEquals(0,
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser()).size());
            assertEquals(0,
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser()).size());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertShortcutIds(assertAllDynamic(mManager.getDynamicShortcuts()), "s1");
            assertShortcutIds(assertAllPinned(mManager.getPinnedShortcuts()), "s1", "s2");
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertEquals(0, mManager.getDynamicShortcuts().size());
            assertShortcutIds(assertAllPinned(mManager.getPinnedShortcuts()), "s2");
        });

        // Clear all pins from launcher 2.
        runWithCaller(LAUNCHER_2, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list(), getCallingUser());
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2, list(), getCallingUser());

            assertEquals(0,
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser()).size());
            assertEquals(0,
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser()).size());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertShortcutIds(assertAllDynamic(mManager.getDynamicShortcuts()), "s1");
            assertEquals(0, mManager.getPinnedShortcuts().size());
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertEquals(0, mManager.getDynamicShortcuts().size());
            assertEquals(0, mManager.getPinnedShortcuts().size());
        });
    }

    public void testPinShortcutAndGetPinnedShortcuts_crossProfile_plusLaunch() {
        // Create some shortcuts.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"))));
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"))));
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"),
                    makeShortcut("s4"), makeShortcut("s5"), makeShortcut("s6"))));
        });

        // Pin some shortcuts and see the result.

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                    list("s1"), HANDLE_USER_0);

            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2,
                    list("s1", "s2", "s3"), HANDLE_USER_0);
        });

        runWithCaller(LAUNCHER_1, USER_P0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                    list("s2"), HANDLE_USER_0);

            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2,
                    list("s2", "s3"), HANDLE_USER_0);
        });

        runWithCaller(LAUNCHER_2, USER_P0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                    list("s3"), HANDLE_USER_0);

            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2,
                    list("s3"), HANDLE_USER_0);
        });

        runWithCaller(LAUNCHER_2, USER_10, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                    list("s1", "s2", "s3"), HANDLE_USER_10);
        });

        // Cross profile pinning.
        final int PIN_AND_DYNAMIC = ShortcutQuery.FLAG_GET_PINNED | ShortcutQuery.FLAG_GET_DYNAMIC;

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s1");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2", "s3");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2", "s3");

            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s1", "s2", "s3");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2", "s3");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2", "s3");

            assertShortcutLaunchable(CALLING_PACKAGE_1, "s1", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_1, "s2", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_1, "s3", USER_0);

            assertShortcutLaunchable(CALLING_PACKAGE_2, "s1", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s2", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s3", USER_0);

            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s1", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s2", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s3", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s4", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s5", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s6", USER_10);
        });
        runWithCaller(LAUNCHER_1, USER_P0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s2");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2", "s3");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2", "s3");

            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s2", "s3");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2", "s3");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2", "s3");

            assertShortcutLaunchable(CALLING_PACKAGE_1, "s1", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_1, "s2", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_1, "s3", USER_0);

            assertShortcutLaunchable(CALLING_PACKAGE_2, "s1", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s2", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s3", USER_0);

            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s1", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s2", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s3", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s4", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s5", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s6", USER_10);
        });
        runWithCaller(LAUNCHER_2, USER_P0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s3");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2", "s3");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2", "s3");

            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s3");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2", "s3");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2", "s3");

            assertShortcutLaunchable(CALLING_PACKAGE_1, "s1", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_1, "s2", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_1, "s3", USER_0);

            assertShortcutLaunchable(CALLING_PACKAGE_2, "s1", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s2", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s3", USER_0);

            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s1", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s2", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s3", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s4", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s5", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s6", USER_10);
        });
        runWithCaller(LAUNCHER_2, USER_10, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_10)),
                    "s1", "s2", "s3");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_10)),
                    "s1", "s2", "s3", "s4", "s5", "s6");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_10)),
                    "s1", "s2", "s3", "s4", "s5", "s6");
        });

        // Remove some dynamic shortcuts.

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"))));
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"))));
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"))));
        });

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s1");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1");

            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s1", "s2", "s3");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2", "s3");

            assertShortcutLaunchable(CALLING_PACKAGE_1, "s1", USER_0);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s2", USER_0);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s3", USER_0);

            assertShortcutLaunchable(CALLING_PACKAGE_2, "s1", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s2", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s3", USER_0);

            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s1", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s2", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s3", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s4", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s5", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s6", USER_10);
        });
        runWithCaller(LAUNCHER_1, USER_P0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s2");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2");

            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s2", "s3");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2", "s3");

            assertShortcutLaunchable(CALLING_PACKAGE_1, "s1", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_1, "s2", USER_0);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s3", USER_0);

            assertShortcutLaunchable(CALLING_PACKAGE_2, "s1", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s2", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s3", USER_0);

            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s1", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s2", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s3", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s4", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s5", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s6", USER_10);
        });
        runWithCaller(LAUNCHER_2, USER_P0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s3");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s3");

            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s3");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s3");

            assertShortcutLaunchable(CALLING_PACKAGE_1, "s1", USER_0);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s2", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_1, "s3", USER_0);

            assertShortcutLaunchable(CALLING_PACKAGE_2, "s1", USER_0);
            assertShortcutNotLaunchable(CALLING_PACKAGE_2, "s2", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s3", USER_0);

            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s1", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s2", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s3", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s4", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s5", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s6", USER_10);
        });
        runWithCaller(LAUNCHER_2, USER_10, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_10)),
                    "s1", "s2", "s3");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_10)),
                    "s1");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_10)),
                    "s1", "s2", "s3");

            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s1", USER_0);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s2", USER_0);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s3", USER_0);

            assertShortcutNotLaunchable(CALLING_PACKAGE_2, "s1", USER_0);
            assertShortcutNotLaunchable(CALLING_PACKAGE_2, "s2", USER_0);
            assertShortcutNotLaunchable(CALLING_PACKAGE_2, "s3", USER_0);

            assertShortcutLaunchable(CALLING_PACKAGE_1, "s1", USER_10);
            assertShortcutLaunchable(CALLING_PACKAGE_1, "s2", USER_10);
            assertShortcutLaunchable(CALLING_PACKAGE_1, "s3", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s4", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s5", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s6", USER_10);
        });

        // Save & load and make sure we still have the same information.
        mService.saveDirtyInfo();
        initService();
        mService.handleUnlockUser(USER_0);

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s1");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1");

            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s1", "s2", "s3");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2", "s3");

            assertShortcutLaunchable(CALLING_PACKAGE_1, "s1", USER_0);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s2", USER_0);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s3", USER_0);

            assertShortcutLaunchable(CALLING_PACKAGE_2, "s1", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s2", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s3", USER_0);

            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s1", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s2", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s3", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s4", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s5", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s6", USER_10);
        });
        runWithCaller(LAUNCHER_1, USER_P0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s2");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2");

            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s2", "s3");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2", "s3");

            assertShortcutLaunchable(CALLING_PACKAGE_1, "s1", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_1, "s2", USER_0);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s3", USER_0);

            assertShortcutLaunchable(CALLING_PACKAGE_2, "s1", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s2", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s3", USER_0);

            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s1", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s2", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s3", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s4", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s5", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s6", USER_10);
        });
        runWithCaller(LAUNCHER_2, USER_P0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s3");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s3");

            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s3");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s3");

            assertShortcutLaunchable(CALLING_PACKAGE_1, "s1", USER_0);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s2", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_1, "s3", USER_0);

            assertShortcutLaunchable(CALLING_PACKAGE_2, "s1", USER_0);
            assertShortcutNotLaunchable(CALLING_PACKAGE_2, "s2", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s3", USER_0);

            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s1", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s2", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s3", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s4", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s5", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s6", USER_10);
        });
    }

    public void testStartShortcut() {
        // Create some shortcuts.
        setCaller(CALLING_PACKAGE_1);
        final ShortcutInfo s1_1 = makeShortcut(
                "s1",
                "Title 1",
                makeComponent(ShortcutActivity.class),
                /* icon =*/ null,
                makeIntent(Intent.ACTION_ASSIST, ShortcutActivity2.class,
                        "key1", "val1", "nest", makeBundle("key", 123)),
                /* weight */ 10);

        final ShortcutInfo s1_2 = makeShortcut(
                "s2",
                "Title 2",
                /* activity */ null,
                /* icon =*/ null,
                makeIntent(Intent.ACTION_ASSIST, ShortcutActivity3.class),
                /* weight */ 12);

        assertTrue(mManager.setDynamicShortcuts(list(s1_1, s1_2)));

        setCaller(CALLING_PACKAGE_2);
        final ShortcutInfo s2_1 = makeShortcut(
                "s1",
                "ABC",
                makeComponent(ShortcutActivity.class),
                /* icon =*/ null,
                makeIntent(Intent.ACTION_ANSWER, ShortcutActivity.class,
                        "key1", "val1", "nest", makeBundle("key", 123)),
                /* weight */ 10);
        assertTrue(mManager.setDynamicShortcuts(list(s2_1)));

        // Pin all.
        setCaller(LAUNCHER_1);
        mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                list("s1", "s2"), getCallingUser());

        mLauncherApps.pinShortcuts(CALLING_PACKAGE_2,
                list("s1"), getCallingUser());

        // Just to make it complicated, delete some.
        setCaller(CALLING_PACKAGE_1);
        mManager.removeDynamicShortcuts(list("s2"));

        // intent and check.
        setCaller(LAUNCHER_1);

        Intent intent;
        intent = launchShortcutAndGetIntent(CALLING_PACKAGE_1, "s1", USER_0);
        assertEquals(ShortcutActivity2.class.getName(), intent.getComponent().getClassName());


        intent = launchShortcutAndGetIntent(CALLING_PACKAGE_1, "s2", USER_0);
        assertEquals(ShortcutActivity3.class.getName(), intent.getComponent().getClassName());

        intent = launchShortcutAndGetIntent(CALLING_PACKAGE_2, "s1", USER_0);
        assertEquals(ShortcutActivity.class.getName(), intent.getComponent().getClassName());

        // TODO Check extra, etc
    }

    public void testLauncherCallback() throws Throwable {
        LauncherApps.Callback c0 = mock(LauncherApps.Callback.class);

        // Set listeners

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.registerCallback(c0, new Handler(Looper.getMainLooper()));
        });

        runWithCaller(CALLING_PACKAGE_1, UserHandle.USER_SYSTEM, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"))));
        });

        waitOnMainThread();
        ArgumentCaptor<List> shortcuts = ArgumentCaptor.forClass(List.class);
        verify(c0).onShortcutsChanged(
                eq(CALLING_PACKAGE_1),
                shortcuts.capture(),
                eq(HANDLE_USER_0)
        );
        assertShortcutIds(assertAllDynamic(shortcuts.getValue()),
                "s1", "s2", "s3");

        // From different package.
        reset(c0);
        runWithCaller(CALLING_PACKAGE_2, UserHandle.USER_SYSTEM, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"))));
        });
        waitOnMainThread();
        shortcuts = ArgumentCaptor.forClass(List.class);
        verify(c0).onShortcutsChanged(
                eq(CALLING_PACKAGE_2),
                shortcuts.capture(),
                eq(HANDLE_USER_0)
        );
        assertShortcutIds(assertAllDynamic(shortcuts.getValue()),
                "s1", "s2", "s3");

        // Different user, callback shouldn't be called.
        reset(c0);
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"))));
        });
        waitOnMainThread();
        verify(c0, times(0)).onShortcutsChanged(
                anyString(),
                any(List.class),
                any(UserHandle.class)
        );

        // Test for addDynamicShortcuts.
        reset(c0);
        runWithCaller(CALLING_PACKAGE_1, UserHandle.USER_SYSTEM, () -> {
            dumpsysOnLogcat("before addDynamicShortcuts");
            assertTrue(mManager.addDynamicShortcuts(list(makeShortcut("s4"))));
        });

        waitOnMainThread();
        shortcuts = ArgumentCaptor.forClass(List.class);
        verify(c0).onShortcutsChanged(
                eq(CALLING_PACKAGE_1),
                shortcuts.capture(),
                eq(HANDLE_USER_0)
        );
        assertShortcutIds(assertAllDynamic(shortcuts.getValue()),
                "s1", "s2", "s3", "s4");

        // Test for remove
        reset(c0);
        runWithCaller(CALLING_PACKAGE_1, UserHandle.USER_SYSTEM, () -> {
            mManager.removeDynamicShortcuts(list("s1"));
        });

        waitOnMainThread();
        shortcuts = ArgumentCaptor.forClass(List.class);
        verify(c0).onShortcutsChanged(
                eq(CALLING_PACKAGE_1),
                shortcuts.capture(),
                eq(HANDLE_USER_0)
        );
        assertShortcutIds(assertAllDynamic(shortcuts.getValue()),
                "s2", "s3", "s4");

        // Test for update
        reset(c0);
        runWithCaller(CALLING_PACKAGE_1, UserHandle.USER_SYSTEM, () -> {
            assertTrue(mManager.updateShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"))));
        });

        waitOnMainThread();
        shortcuts = ArgumentCaptor.forClass(List.class);
        verify(c0).onShortcutsChanged(
                eq(CALLING_PACKAGE_1),
                shortcuts.capture(),
                eq(HANDLE_USER_0)
        );
        assertShortcutIds(assertAllDynamic(shortcuts.getValue()),
                "s2", "s3", "s4");

        // Test for deleteAll
        reset(c0);
        runWithCaller(CALLING_PACKAGE_1, UserHandle.USER_SYSTEM, () -> {
            mManager.removeAllDynamicShortcuts();
        });

        waitOnMainThread();
        shortcuts = ArgumentCaptor.forClass(List.class);
        verify(c0).onShortcutsChanged(
                eq(CALLING_PACKAGE_1),
                shortcuts.capture(),
                eq(HANDLE_USER_0)
        );
        assertEquals(0, shortcuts.getValue().size());

        // Remove CALLING_PACKAGE_2
        reset(c0);
        uninstallPackage(USER_0, CALLING_PACKAGE_2);
        mService.cleanUpPackageLocked(CALLING_PACKAGE_2, USER_0, USER_0);

        // Should get a callback with an empty list.
        waitOnMainThread();
        shortcuts = ArgumentCaptor.forClass(List.class);
        verify(c0).onShortcutsChanged(
                eq(CALLING_PACKAGE_2),
                shortcuts.capture(),
                eq(HANDLE_USER_0)
        );
        assertEquals(0, shortcuts.getValue().size());
    }

    public void testLauncherCallback_crossProfile() throws Throwable {
        prepareCrossProfileDataSet();

        final Handler h = new Handler(Looper.getMainLooper());

        final LauncherApps.Callback c0_1 = mock(LauncherApps.Callback.class);
        final LauncherApps.Callback c0_2 = mock(LauncherApps.Callback.class);
        final LauncherApps.Callback c0_3 = mock(LauncherApps.Callback.class);
        final LauncherApps.Callback c0_4 = mock(LauncherApps.Callback.class);

        final LauncherApps.Callback cP0_1 = mock(LauncherApps.Callback.class);
        final LauncherApps.Callback c10_1 = mock(LauncherApps.Callback.class);
        final LauncherApps.Callback c10_2 = mock(LauncherApps.Callback.class);
        final LauncherApps.Callback c11_1 = mock(LauncherApps.Callback.class);

        final List<LauncherApps.Callback> all =
                list(c0_1, c0_2, c0_3, c0_4, cP0_1, c10_1, c11_1);

        setDefaultLauncherChecker((pkg, userId) -> {
            switch (userId) {
                case USER_0:
                    return LAUNCHER_2.equals(pkg);
                case USER_P0:
                    return LAUNCHER_1.equals(pkg);
                case USER_10:
                    return LAUNCHER_1.equals(pkg);
                case USER_11:
                    return LAUNCHER_1.equals(pkg);
                default:
                    return false;
            }
        });

        runWithCaller(LAUNCHER_1, USER_0, () -> mLauncherApps.registerCallback(c0_1, h));
        runWithCaller(LAUNCHER_2, USER_0, () -> mLauncherApps.registerCallback(c0_2, h));
        runWithCaller(LAUNCHER_3, USER_0, () -> mLauncherApps.registerCallback(c0_3, h));
        runWithCaller(LAUNCHER_4, USER_0, () -> mLauncherApps.registerCallback(c0_4, h));
        runWithCaller(LAUNCHER_1, USER_P0, () -> mLauncherApps.registerCallback(cP0_1, h));
        runWithCaller(LAUNCHER_1, USER_10, () -> mLauncherApps.registerCallback(c10_1, h));
        runWithCaller(LAUNCHER_2, USER_10, () -> mLauncherApps.registerCallback(c10_2, h));
        runWithCaller(LAUNCHER_1, USER_11, () -> mLauncherApps.registerCallback(c11_1, h));

        // User 0.

        resetAll(all);
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            mManager.removeDynamicShortcuts(list());
        });
        waitOnMainThread();

        assertCallbackNotReceived(c0_1);
        assertCallbackNotReceived(c0_3);
        assertCallbackNotReceived(c0_4);
        assertCallbackNotReceived(c10_1);
        assertCallbackNotReceived(c10_2);
        assertCallbackNotReceived(c11_1);
        assertCallbackReceived(c0_2, HANDLE_USER_0, CALLING_PACKAGE_1, "s1", "s2", "s3");
        assertCallbackReceived(cP0_1, HANDLE_USER_0, CALLING_PACKAGE_1, "s1", "s2", "s3", "s4");

        // User 0, different package.

        resetAll(all);
        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            mManager.removeDynamicShortcuts(list());
        });
        waitOnMainThread();

        assertCallbackNotReceived(c0_1);
        assertCallbackNotReceived(c0_3);
        assertCallbackNotReceived(c0_4);
        assertCallbackNotReceived(c10_1);
        assertCallbackNotReceived(c10_2);
        assertCallbackNotReceived(c11_1);
        assertCallbackReceived(c0_2, HANDLE_USER_0, CALLING_PACKAGE_3, "s1", "s2", "s3", "s4");
        assertCallbackReceived(cP0_1, HANDLE_USER_0, CALLING_PACKAGE_3,
                "s1", "s2", "s3", "s4", "s5", "s6");

        // Work profile, but not running, so don't send notifications.

        resetAll(all);
        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            mManager.removeDynamicShortcuts(list());
        });
        waitOnMainThread();

        assertCallbackNotReceived(c0_1);
        assertCallbackNotReceived(c0_2);
        assertCallbackNotReceived(c0_3);
        assertCallbackNotReceived(c0_4);
        assertCallbackNotReceived(cP0_1);
        assertCallbackNotReceived(c10_1);
        assertCallbackNotReceived(c10_2);
        assertCallbackNotReceived(c11_1);

        // Work profile, now running.
        doAnswer(new AnswerIsUserRunning(false)).when(mMockUserManager).isUserRunning(anyInt());
        doAnswer(new AnswerIsUserRunning(true)).when(mMockUserManager).isUserRunning(eq(USER_P0));

        resetAll(all);
        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            mManager.removeDynamicShortcuts(list());
        });
        waitOnMainThread();

        assertCallbackNotReceived(c0_1);
        assertCallbackNotReceived(c0_3);
        assertCallbackNotReceived(c0_4);
        assertCallbackNotReceived(c10_1);
        assertCallbackNotReceived(c10_2);
        assertCallbackNotReceived(c11_1);
        assertCallbackReceived(c0_2, HANDLE_USER_P0, CALLING_PACKAGE_1, "s1", "s2", "s3", "s5");
        assertCallbackReceived(cP0_1, HANDLE_USER_P0, CALLING_PACKAGE_1, "s1", "s2", "s3", "s4");

        // Normal secondary user.

        doAnswer(new AnswerIsUserRunning(false)).when(mMockUserManager).isUserRunning(anyInt());
        doAnswer(new AnswerIsUserRunning(true)).when(mMockUserManager).isUserRunning(eq(USER_10));

        resetAll(all);
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            mManager.removeDynamicShortcuts(list());
        });
        waitOnMainThread();

        assertCallbackNotReceived(c0_1);
        assertCallbackNotReceived(c0_2);
        assertCallbackNotReceived(c0_3);
        assertCallbackNotReceived(c0_4);
        assertCallbackNotReceived(cP0_1);
        assertCallbackNotReceived(c10_2);
        assertCallbackNotReceived(c11_1);
        assertCallbackReceived(c10_1, HANDLE_USER_10, CALLING_PACKAGE_1,
                "x1", "x2", "x3", "x4", "x5");
    }

    // === Test for persisting ===

    public void testSaveAndLoadUser_empty() {
        assertTrue(mManager.setDynamicShortcuts(list()));

        Log.i(TAG, "Saved state");
        dumpsysOnLogcat();
        dumpUserFile(0);

        // Restore.
        mService.saveDirtyInfo();
        initService();

        assertEquals(0, mManager.getDynamicShortcuts().size());
    }

    /**
     * Try save and load, also stop/start the user.
     */
    public void testSaveAndLoadUser() {
        // First, create some shortcuts and save.
        runWithCaller(CALLING_PACKAGE_1, UserHandle.USER_SYSTEM, () -> {
            final Icon icon1 = Icon.createWithResource(getTestContext(), R.drawable.black_64x16);
            final Icon icon2 = Icon.createWithBitmap(BitmapFactory.decodeResource(
                    getTestContext().getResources(), R.drawable.icon2));

            final ShortcutInfo si1 = makeShortcut(
                    "s1",
                    "title1-1",
                    makeComponent(ShortcutActivity.class),
                    icon1,
                    makeIntent(Intent.ACTION_ASSIST, ShortcutActivity2.class,
                            "key1", "val1", "nest", makeBundle("key", 123)),
                        /* weight */ 10);

            final ShortcutInfo si2 = makeShortcut(
                    "s2",
                    "title1-2",
                        /* activity */ null,
                    icon2,
                    makeIntent(Intent.ACTION_ASSIST, ShortcutActivity3.class),
                        /* weight */ 12);

            assertTrue(mManager.setDynamicShortcuts(list(si1, si2)));

            assertEquals(START_TIME + INTERVAL, mManager.getRateLimitResetTime());
            assertEquals(2, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_2, UserHandle.USER_SYSTEM, () -> {
            final Icon icon1 = Icon.createWithResource(getTestContext(), R.drawable.black_16x64);
            final Icon icon2 = Icon.createWithBitmap(BitmapFactory.decodeResource(
                    getTestContext().getResources(), R.drawable.icon2));

            final ShortcutInfo si1 = makeShortcut(
                    "s1",
                    "title2-1",
                    makeComponent(ShortcutActivity.class),
                    icon1,
                    makeIntent(Intent.ACTION_ASSIST, ShortcutActivity2.class,
                            "key1", "val1", "nest", makeBundle("key", 123)),
                        /* weight */ 10);

            final ShortcutInfo si2 = makeShortcut(
                    "s2",
                    "title2-2",
                        /* activity */ null,
                    icon2,
                    makeIntent(Intent.ACTION_ASSIST, ShortcutActivity3.class),
                        /* weight */ 12);

            assertTrue(mManager.setDynamicShortcuts(list(si1, si2)));

            assertEquals(START_TIME + INTERVAL, mManager.getRateLimitResetTime());
            assertEquals(2, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            final Icon icon1 = Icon.createWithResource(getTestContext(), R.drawable.black_64x64);
            final Icon icon2 = Icon.createWithBitmap(BitmapFactory.decodeResource(
                    getTestContext().getResources(), R.drawable.icon2));

            final ShortcutInfo si1 = makeShortcut(
                    "s1",
                    "title10-1-1",
                    makeComponent(ShortcutActivity.class),
                    icon1,
                    makeIntent(Intent.ACTION_ASSIST, ShortcutActivity2.class,
                            "key1", "val1", "nest", makeBundle("key", 123)),
                        /* weight */ 10);

            final ShortcutInfo si2 = makeShortcut(
                    "s2",
                    "title10-1-2",
                        /* activity */ null,
                    icon2,
                    makeIntent(Intent.ACTION_ASSIST, ShortcutActivity3.class),
                        /* weight */ 12);

            assertTrue(mManager.setDynamicShortcuts(list(si1, si2)));

            assertEquals(START_TIME + INTERVAL, mManager.getRateLimitResetTime());
            assertEquals(2, mManager.getRemainingCallCount());
        });

        mService.getShortcutsForTest().get(UserHandle.USER_SYSTEM).setDefaultLauncherComponent(
                new ComponentName("pkg1", "class"));

        // Restore.
        mService.saveDirtyInfo();
        initService();

        // Before the load, the map should be empty.
        assertEquals(0, mService.getShortcutsForTest().size());

        // this will pre-load the per-user info.
        mService.handleUnlockUser(UserHandle.USER_SYSTEM);

        // Now it's loaded.
        assertEquals(1, mService.getShortcutsForTest().size());

        runWithCaller(CALLING_PACKAGE_1, UserHandle.USER_SYSTEM, () -> {
            assertShortcutIds(assertAllDynamic(assertAllHaveIntents(assertAllHaveIcon(
                    mManager.getDynamicShortcuts()))), "s1", "s2");
            assertEquals(2, mManager.getRemainingCallCount());

            assertEquals("title1-1", getCallerShortcut("s1").getTitle());
            assertEquals("title1-2", getCallerShortcut("s2").getTitle());
        });
        runWithCaller(CALLING_PACKAGE_2, UserHandle.USER_SYSTEM, () -> {
            assertShortcutIds(assertAllDynamic(assertAllHaveIntents(assertAllHaveIcon(
                    mManager.getDynamicShortcuts()))), "s1", "s2");
            assertEquals(2, mManager.getRemainingCallCount());

            assertEquals("title2-1", getCallerShortcut("s1").getTitle());
            assertEquals("title2-2", getCallerShortcut("s2").getTitle());
        });

        assertEquals("pkg1", mService.getShortcutsForTest().get(UserHandle.USER_SYSTEM)
                .getDefaultLauncherComponent().getPackageName());

        // Start another user
        mService.handleUnlockUser(USER_10);

        // Now the size is 2.
        assertEquals(2, mService.getShortcutsForTest().size());

        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertShortcutIds(assertAllDynamic(assertAllHaveIntents(assertAllHaveIcon(
                    mManager.getDynamicShortcuts()))), "s1", "s2");
            assertEquals(2, mManager.getRemainingCallCount());

            assertEquals("title10-1-1", getCallerShortcut("s1").getTitle());
            assertEquals("title10-1-2", getCallerShortcut("s2").getTitle());
        });
        assertNull(mService.getShortcutsForTest().get(USER_10).getDefaultLauncherComponent());

        // Try stopping the user
        mService.handleCleanupUser(USER_10);

        // Now it's unloaded.
        assertEquals(1, mService.getShortcutsForTest().size());

        // TODO Check all other fields
    }

    public void testCleanupPackage() {
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s0_1"))));
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s0_2"))));
        });
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("s0_1"),
                    HANDLE_USER_0);
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2, list("s0_2"),
                    HANDLE_USER_0);
        });
        runWithCaller(LAUNCHER_2, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("s0_1"),
                    HANDLE_USER_0);
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2, list("s0_2"),
                    HANDLE_USER_0);
        });

        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s10_1"))));
        });
        runWithCaller(CALLING_PACKAGE_2, USER_10, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s10_2"))));
        });
        runWithCaller(LAUNCHER_1, USER_10, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("s10_1"),
                    HANDLE_USER_10);
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2, list("s10_2"),
                    HANDLE_USER_10);
        });
        runWithCaller(LAUNCHER_2, USER_10, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("s10_1"),
                    HANDLE_USER_10);
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2, list("s10_2"),
                    HANDLE_USER_10);
        });

        // Remove all dynamic shortcuts; now all shortcuts are just pinned.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            mManager.removeAllDynamicShortcuts();
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            mManager.removeAllDynamicShortcuts();
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            mManager.removeAllDynamicShortcuts();
        });
        runWithCaller(CALLING_PACKAGE_2, USER_10, () -> {
            mManager.removeAllDynamicShortcuts();
        });


        final SparseArray<ShortcutUser> users =  mService.getShortcutsForTest();
        assertEquals(2, users.size());
        assertEquals(USER_0, users.keyAt(0));
        assertEquals(USER_10, users.keyAt(1));

        final ShortcutUser user0 =  users.get(USER_0);
        final ShortcutUser user10 =  users.get(USER_10);


        // Check the registered packages.
        dumpsysOnLogcat();
        assertEquals(set(CALLING_PACKAGE_1, CALLING_PACKAGE_2),
                hashSet(user0.getAllPackagesForTest().keySet()));
        assertEquals(set(CALLING_PACKAGE_1, CALLING_PACKAGE_2),
                hashSet(user10.getAllPackagesForTest().keySet()));
        assertEquals(
                set(PackageWithUser.of(USER_0, LAUNCHER_1),
                        PackageWithUser.of(USER_0, LAUNCHER_2)),
                hashSet(user0.getAllLaunchersForTest().keySet()));
        assertEquals(
                set(PackageWithUser.of(USER_10, LAUNCHER_1),
                        PackageWithUser.of(USER_10, LAUNCHER_2)),
                hashSet(user10.getAllLaunchersForTest().keySet()));
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_1, USER_0),
                "s0_1", "s0_2");
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_2, USER_0),
                "s0_1", "s0_2");
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_1, USER_10),
                "s10_1", "s10_2");
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_2, USER_10),
                "s10_1", "s10_2");
        assertShortcutExists(CALLING_PACKAGE_1, "s0_1", USER_0);
        assertShortcutExists(CALLING_PACKAGE_2, "s0_2", USER_0);
        assertShortcutExists(CALLING_PACKAGE_1, "s10_1", USER_10);
        assertShortcutExists(CALLING_PACKAGE_2, "s10_2", USER_10);

        mService.saveDirtyInfo();

        // Nonexistent package.
        uninstallPackage(USER_0, "abc");
        mService.cleanUpPackageLocked("abc", USER_0, USER_0);

        // No changes.
        assertEquals(set(CALLING_PACKAGE_1, CALLING_PACKAGE_2),
                hashSet(user0.getAllPackagesForTest().keySet()));
        assertEquals(set(CALLING_PACKAGE_1, CALLING_PACKAGE_2),
                hashSet(user10.getAllPackagesForTest().keySet()));
        assertEquals(
                set(PackageWithUser.of(USER_0, LAUNCHER_1),
                        PackageWithUser.of(USER_0, LAUNCHER_2)),
                hashSet(user0.getAllLaunchersForTest().keySet()));
        assertEquals(
                set(PackageWithUser.of(USER_10, LAUNCHER_1),
                        PackageWithUser.of(USER_10, LAUNCHER_2)),
                hashSet(user10.getAllLaunchersForTest().keySet()));
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_1, USER_0),
                "s0_1", "s0_2");
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_2, USER_0),
                "s0_1", "s0_2");
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_1, USER_10),
                "s10_1", "s10_2");
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_2, USER_10),
                "s10_1", "s10_2");
        assertShortcutExists(CALLING_PACKAGE_1, "s0_1", USER_0);
        assertShortcutExists(CALLING_PACKAGE_2, "s0_2", USER_0);
        assertShortcutExists(CALLING_PACKAGE_1, "s10_1", USER_10);
        assertShortcutExists(CALLING_PACKAGE_2, "s10_2", USER_10);

        mService.saveDirtyInfo();

        // Remove a package.
        uninstallPackage(USER_0, CALLING_PACKAGE_1);
        mService.cleanUpPackageLocked(CALLING_PACKAGE_1, USER_0, USER_0);

        assertEquals(set(CALLING_PACKAGE_2),
                hashSet(user0.getAllPackagesForTest().keySet()));
        assertEquals(set(CALLING_PACKAGE_1, CALLING_PACKAGE_2),
                hashSet(user10.getAllPackagesForTest().keySet()));
        assertEquals(
                set(PackageWithUser.of(USER_0, LAUNCHER_1),
                        PackageWithUser.of(USER_0, LAUNCHER_2)),
                hashSet(user0.getAllLaunchersForTest().keySet()));
        assertEquals(
                set(PackageWithUser.of(USER_10, LAUNCHER_1),
                        PackageWithUser.of(USER_10, LAUNCHER_2)),
                hashSet(user10.getAllLaunchersForTest().keySet()));
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_1, USER_0),
                "s0_2");
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_2, USER_0),
                "s0_2");
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_1, USER_10),
                "s10_1", "s10_2");
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_2, USER_10),
                "s10_1", "s10_2");
        assertShortcutNotExists(CALLING_PACKAGE_1, "s0_1", USER_0);
        assertShortcutExists(CALLING_PACKAGE_2, "s0_2", USER_0);
        assertShortcutExists(CALLING_PACKAGE_1, "s10_1", USER_10);
        assertShortcutExists(CALLING_PACKAGE_2, "s10_2", USER_10);

        mService.saveDirtyInfo();

        // Remove a launcher.
        uninstallPackage(USER_10, LAUNCHER_1);
        mService.cleanUpPackageLocked(LAUNCHER_1, USER_10, USER_10);

        assertEquals(set(CALLING_PACKAGE_2),
                hashSet(user0.getAllPackagesForTest().keySet()));
        assertEquals(set(CALLING_PACKAGE_1, CALLING_PACKAGE_2),
                hashSet(user10.getAllPackagesForTest().keySet()));
        assertEquals(
                set(PackageWithUser.of(USER_0, LAUNCHER_1),
                        PackageWithUser.of(USER_0, LAUNCHER_2)),
                hashSet(user0.getAllLaunchersForTest().keySet()));
        assertEquals(
                set(PackageWithUser.of(USER_10, LAUNCHER_2)),
                hashSet(user10.getAllLaunchersForTest().keySet()));
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_1, USER_0),
                "s0_2");
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_2, USER_0),
                "s0_2");
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_2, USER_10),
                "s10_1", "s10_2");
        assertShortcutNotExists(CALLING_PACKAGE_1, "s0_1", USER_0);
        assertShortcutExists(CALLING_PACKAGE_2, "s0_2", USER_0);
        assertShortcutExists(CALLING_PACKAGE_1, "s10_1", USER_10);
        assertShortcutExists(CALLING_PACKAGE_2, "s10_2", USER_10);

        mService.saveDirtyInfo();

        // Remove a package.
        uninstallPackage(USER_10, CALLING_PACKAGE_2);
        mService.cleanUpPackageLocked(CALLING_PACKAGE_2, USER_10, USER_10);

        assertEquals(set(CALLING_PACKAGE_2),
                hashSet(user0.getAllPackagesForTest().keySet()));
        assertEquals(set(CALLING_PACKAGE_1),
                hashSet(user10.getAllPackagesForTest().keySet()));
        assertEquals(
                set(PackageWithUser.of(USER_0, LAUNCHER_1),
                        PackageWithUser.of(USER_0, LAUNCHER_2)),
                hashSet(user0.getAllLaunchersForTest().keySet()));
        assertEquals(
                set(PackageWithUser.of(USER_10, LAUNCHER_2)),
                hashSet(user10.getAllLaunchersForTest().keySet()));
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_1, USER_0),
                "s0_2");
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_2, USER_0),
                "s0_2");
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_2, USER_10),
                "s10_1");
        assertShortcutNotExists(CALLING_PACKAGE_1, "s0_1", USER_0);
        assertShortcutExists(CALLING_PACKAGE_2, "s0_2", USER_0);
        assertShortcutExists(CALLING_PACKAGE_1, "s10_1", USER_10);
        assertShortcutNotExists(CALLING_PACKAGE_2, "s10_2", USER_10);

        mService.saveDirtyInfo();

        // Remove the other launcher from user 10 too.
        uninstallPackage(USER_10, LAUNCHER_2);
        mService.cleanUpPackageLocked(LAUNCHER_2, USER_10, USER_10);

        assertEquals(set(CALLING_PACKAGE_2),
                hashSet(user0.getAllPackagesForTest().keySet()));
        assertEquals(set(CALLING_PACKAGE_1),
                hashSet(user10.getAllPackagesForTest().keySet()));
        assertEquals(
                set(PackageWithUser.of(USER_0, LAUNCHER_1),
                        PackageWithUser.of(USER_0, LAUNCHER_2)),
                hashSet(user0.getAllLaunchersForTest().keySet()));
        assertEquals(
                set(),
                hashSet(user10.getAllLaunchersForTest().keySet()));
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_1, USER_0),
                "s0_2");
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_2, USER_0),
                "s0_2");

        // Note the pinned shortcuts on user-10 no longer referred, so they should both be removed.
        assertShortcutNotExists(CALLING_PACKAGE_1, "s0_1", USER_0);
        assertShortcutExists(CALLING_PACKAGE_2, "s0_2", USER_0);
        assertShortcutNotExists(CALLING_PACKAGE_1, "s10_1", USER_10);
        assertShortcutNotExists(CALLING_PACKAGE_2, "s10_2", USER_10);

        mService.saveDirtyInfo();

        // More remove.
        uninstallPackage(USER_10, CALLING_PACKAGE_1);
        mService.cleanUpPackageLocked(CALLING_PACKAGE_1, USER_10, USER_10);

        assertEquals(set(CALLING_PACKAGE_2),
                hashSet(user0.getAllPackagesForTest().keySet()));
        assertEquals(set(),
                hashSet(user10.getAllPackagesForTest().keySet()));
        assertEquals(
                set(PackageWithUser.of(USER_0, LAUNCHER_1),
                        PackageWithUser.of(USER_0, LAUNCHER_2)),
                hashSet(user0.getAllLaunchersForTest().keySet()));
        assertEquals(set(),
                hashSet(user10.getAllLaunchersForTest().keySet()));
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_1, USER_0),
                "s0_2");
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_2, USER_0),
                "s0_2");

        // Note the pinned shortcuts on user-10 no longer referred, so they should both be removed.
        assertShortcutNotExists(CALLING_PACKAGE_1, "s0_1", USER_0);
        assertShortcutExists(CALLING_PACKAGE_2, "s0_2", USER_0);
        assertShortcutNotExists(CALLING_PACKAGE_1, "s10_1", USER_10);
        assertShortcutNotExists(CALLING_PACKAGE_2, "s10_2", USER_10);

        mService.saveDirtyInfo();
    }

    public void testHandleGonePackage_crossProfile() {
        // Create some shortcuts.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"))));
        });
        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"))));
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"))));
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"))));
        });

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_0));

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_P0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_P0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_P0));

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_2, "s1", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_2, "s2", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_2, "s3", USER_0));

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_10));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_10));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_10));

        // Pin some.

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                    list("s1"), HANDLE_USER_0);

            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                    list("s2"), UserHandle.of(USER_P0));

            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2,
                    list("s3"), HANDLE_USER_0);
        });

        runWithCaller(LAUNCHER_1, USER_P0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                    list("s2"), HANDLE_USER_0);

            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                    list("s3"), UserHandle.of(USER_P0));

            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2,
                    list("s1"), HANDLE_USER_0);
        });

        runWithCaller(LAUNCHER_1, USER_10, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                    list("s3"), HANDLE_USER_10);
        });

        // Check the state.

        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_0));

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_P0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_P0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_P0));

        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_2, "s1", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_2, "s2", USER_0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_2, "s3", USER_0));

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_10));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_10));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_10));

        // Make sure all the information is persisted.
        mService.saveDirtyInfo();
        initService();
        mService.handleUnlockUser(USER_0);
        mService.handleUnlockUser(USER_P0);
        mService.handleUnlockUser(USER_10);

        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_0));

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_P0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_P0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_P0));

        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_2, "s1", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_2, "s2", USER_0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_2, "s3", USER_0));

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_10));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_10));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_10));

        // Start uninstalling.
        uninstallPackage(USER_10, LAUNCHER_1);
        mService.checkPackageChanges(USER_10);

        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_0));

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_P0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_P0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_P0));

        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_2, "s1", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_2, "s2", USER_0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_2, "s3", USER_0));

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_10));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_10));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_10));

        // Uninstall.
        uninstallPackage(USER_10, CALLING_PACKAGE_1);
        mService.checkPackageChanges(USER_10);

        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_0));

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_P0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_P0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_P0));

        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_2, "s1", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_2, "s2", USER_0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_2, "s3", USER_0));

        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_10));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_10));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_10));

        uninstallPackage(USER_P0, LAUNCHER_1);
        mService.checkPackageChanges(USER_0);

        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_0));

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_P0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_P0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_P0));

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_2, "s1", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_2, "s2", USER_0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_2, "s3", USER_0));

        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_10));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_10));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_10));

        mService.checkPackageChanges(USER_P0);

        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_0));

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_P0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_P0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_P0));

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_2, "s1", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_2, "s2", USER_0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_2, "s3", USER_0));

        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_10));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_10));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_10));

        uninstallPackage(USER_P0, CALLING_PACKAGE_1);

        mService.saveDirtyInfo();
        initService();
        mService.handleUnlockUser(USER_0);
        mService.handleUnlockUser(USER_P0);
        mService.handleUnlockUser(USER_10);

        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_0));

        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_P0));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_P0));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_P0));

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_2, "s1", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_2, "s2", USER_0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_2, "s3", USER_0));

        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_10));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_10));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_10));

        // Uninstall
        uninstallPackage(USER_0, LAUNCHER_1);

        mService.saveDirtyInfo();
        initService();
        mService.handleUnlockUser(USER_0);
        mService.handleUnlockUser(USER_P0);
        mService.handleUnlockUser(USER_10);

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_0));

        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_P0));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_P0));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_P0));

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_2, "s1", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_2, "s2", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_2, "s3", USER_0));

        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_10));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_10));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_10));

        uninstallPackage(USER_0, CALLING_PACKAGE_2);

        mService.saveDirtyInfo();
        initService();
        mService.handleUnlockUser(USER_0);
        mService.handleUnlockUser(USER_P0);
        mService.handleUnlockUser(USER_10);

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_0));

        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_P0));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_P0));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_P0));

        assertNull(getPackageShortcut(CALLING_PACKAGE_2, "s1", USER_0));
        assertNull(getPackageShortcut(CALLING_PACKAGE_2, "s2", USER_0));
        assertNull(getPackageShortcut(CALLING_PACKAGE_2, "s3", USER_0));

        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_10));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_10));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_10));
    }

    protected void checkCanRestoreTo(boolean expected, ShortcutPackageInfo spi,
            int version, String... signatures) {
        assertEquals(expected, spi.canRestoreTo(mService, genPackage(
                "dummy", /* uid */ 0, version, signatures)));
    }

    public void testCanRestoreTo() {
        addPackage(CALLING_PACKAGE_1, CALLING_UID_1, 10, "sig1");
        addPackage(CALLING_PACKAGE_2, CALLING_UID_1, 10, "sig1", "sig2");

        final ShortcutPackageInfo spi1 = ShortcutPackageInfo.generateForInstalledPackage(
                mService, CALLING_PACKAGE_1, USER_0);
        final ShortcutPackageInfo spi2 = ShortcutPackageInfo.generateForInstalledPackage(
                mService, CALLING_PACKAGE_2, USER_0);

        checkCanRestoreTo(true, spi1, 10, "sig1");
        checkCanRestoreTo(true, spi1, 10, "x", "sig1");
        checkCanRestoreTo(true, spi1, 10, "sig1", "y");
        checkCanRestoreTo(true, spi1, 10, "x", "sig1", "y");
        checkCanRestoreTo(true, spi1, 11, "sig1");

        checkCanRestoreTo(false, spi1, 10 /* empty */);
        checkCanRestoreTo(false, spi1, 10, "x");
        checkCanRestoreTo(false, spi1, 10, "x", "y");
        checkCanRestoreTo(false, spi1, 10, "x");
        checkCanRestoreTo(false, spi1, 9, "sig1");

        checkCanRestoreTo(true, spi2, 10, "sig1", "sig2");
        checkCanRestoreTo(true, spi2, 10, "sig2", "sig1");
        checkCanRestoreTo(true, spi2, 10, "x", "sig1", "sig2");
        checkCanRestoreTo(true, spi2, 10, "x", "sig2", "sig1");
        checkCanRestoreTo(true, spi2, 10, "sig1", "sig2", "y");
        checkCanRestoreTo(true, spi2, 10, "sig2", "sig1", "y");
        checkCanRestoreTo(true, spi2, 10, "x", "sig1", "sig2", "y");
        checkCanRestoreTo(true, spi2, 10, "x", "sig2", "sig1", "y");
        checkCanRestoreTo(true, spi2, 11, "x", "sig2", "sig1", "y");

        checkCanRestoreTo(false, spi2, 10, "sig1", "sig2x");
        checkCanRestoreTo(false, spi2, 10, "sig2", "sig1x");
        checkCanRestoreTo(false, spi2, 10, "x", "sig1x", "sig2");
        checkCanRestoreTo(false, spi2, 10, "x", "sig2x", "sig1");
        checkCanRestoreTo(false, spi2, 10, "sig1", "sig2x", "y");
        checkCanRestoreTo(false, spi2, 10, "sig2", "sig1x", "y");
        checkCanRestoreTo(false, spi2, 10, "x", "sig1x", "sig2", "y");
        checkCanRestoreTo(false, spi2, 10, "x", "sig2x", "sig1", "y");
        checkCanRestoreTo(false, spi2, 11, "x", "sig2x", "sig1", "y");
    }

    public void testHandlePackageDelete() {
        final Icon bmp32x32 = Icon.createWithBitmap(BitmapFactory.decodeResource(
                getTestContext().getResources(), R.drawable.black_32x32));
        setCaller(CALLING_PACKAGE_1, USER_0);
        assertTrue(mManager.addDynamicShortcuts(list(
                makeShortcutWithIcon("s1", bmp32x32), makeShortcutWithIcon("s2", bmp32x32)
        )));

        setCaller(CALLING_PACKAGE_2, USER_0);
        assertTrue(mManager.addDynamicShortcuts(list(makeShortcutWithIcon("s1", bmp32x32))));

        setCaller(CALLING_PACKAGE_3, USER_0);
        assertTrue(mManager.addDynamicShortcuts(list(makeShortcutWithIcon("s1", bmp32x32))));

        setCaller(CALLING_PACKAGE_1, USER_10);
        assertTrue(mManager.addDynamicShortcuts(list(makeShortcutWithIcon("s1", bmp32x32))));

        setCaller(CALLING_PACKAGE_2, USER_10);
        assertTrue(mManager.addDynamicShortcuts(list(makeShortcutWithIcon("s1", bmp32x32))));

        setCaller(CALLING_PACKAGE_3, USER_10);
        assertTrue(mManager.addDynamicShortcuts(list(makeShortcutWithIcon("s1", bmp32x32))));

        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_2, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_3, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "s1", USER_10));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_2, "s1", USER_10));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_3, "s1", USER_10));

        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_1, USER_0));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_2, USER_0));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_3, USER_0));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_1, USER_10));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_2, USER_10));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_3, USER_10));

        uninstallPackage(USER_0, CALLING_PACKAGE_1);
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageDeleteIntent(CALLING_PACKAGE_1, USER_0));

        assertNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_2, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_3, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "s1", USER_10));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_2, "s1", USER_10));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_3, "s1", USER_10));

        assertFalse(bitmapDirectoryExists(CALLING_PACKAGE_1, USER_0));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_2, USER_0));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_3, USER_0));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_1, USER_10));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_2, USER_10));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_3, USER_10));

        uninstallPackage(USER_10, CALLING_PACKAGE_2);
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageDeleteIntent(CALLING_PACKAGE_2, USER_10));

        assertNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_2, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_3, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "s1", USER_10));
        assertNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_2, "s1", USER_10));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_3, "s1", USER_10));

        assertFalse(bitmapDirectoryExists(CALLING_PACKAGE_1, USER_0));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_2, USER_0));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_3, USER_0));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_1, USER_10));
        assertFalse(bitmapDirectoryExists(CALLING_PACKAGE_2, USER_10));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_3, USER_10));

        mInjectedPackages.remove(CALLING_PACKAGE_1);
        mInjectedPackages.remove(CALLING_PACKAGE_3);

        mService.handleUnlockUser(USER_0);

        assertNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_2, "s1", USER_0));
        assertNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_3, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "s1", USER_10));
        assertNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_2, "s1", USER_10));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_3, "s1", USER_10));

        assertFalse(bitmapDirectoryExists(CALLING_PACKAGE_1, USER_0));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_2, USER_0));
        assertFalse(bitmapDirectoryExists(CALLING_PACKAGE_3, USER_0));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_1, USER_10));
        assertFalse(bitmapDirectoryExists(CALLING_PACKAGE_2, USER_10));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_3, USER_10));

        mService.handleUnlockUser(USER_10);

        assertNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_2, "s1", USER_0));
        assertNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_3, "s1", USER_0));
        assertNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "s1", USER_10));
        assertNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_2, "s1", USER_10));
        assertNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_3, "s1", USER_10));

        assertFalse(bitmapDirectoryExists(CALLING_PACKAGE_1, USER_0));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_2, USER_0));
        assertFalse(bitmapDirectoryExists(CALLING_PACKAGE_3, USER_0));
        assertFalse(bitmapDirectoryExists(CALLING_PACKAGE_1, USER_10));
        assertFalse(bitmapDirectoryExists(CALLING_PACKAGE_2, USER_10));
        assertFalse(bitmapDirectoryExists(CALLING_PACKAGE_3, USER_10));
    }

    /** Almost ame as testHandlePackageDelete, except it doesn't uninstall packages. */
    public void testHandlePackageClearData() {
        final Icon bmp32x32 = Icon.createWithBitmap(BitmapFactory.decodeResource(
                getTestContext().getResources(), R.drawable.black_32x32));
        setCaller(CALLING_PACKAGE_1, USER_0);
        assertTrue(mManager.addDynamicShortcuts(list(
                makeShortcutWithIcon("s1", bmp32x32), makeShortcutWithIcon("s2", bmp32x32)
        )));

        setCaller(CALLING_PACKAGE_2, USER_0);
        assertTrue(mManager.addDynamicShortcuts(list(makeShortcutWithIcon("s1", bmp32x32))));

        setCaller(CALLING_PACKAGE_3, USER_0);
        assertTrue(mManager.addDynamicShortcuts(list(makeShortcutWithIcon("s1", bmp32x32))));

        setCaller(CALLING_PACKAGE_1, USER_10);
        assertTrue(mManager.addDynamicShortcuts(list(makeShortcutWithIcon("s1", bmp32x32))));

        setCaller(CALLING_PACKAGE_2, USER_10);
        assertTrue(mManager.addDynamicShortcuts(list(makeShortcutWithIcon("s1", bmp32x32))));

        setCaller(CALLING_PACKAGE_3, USER_10);
        assertTrue(mManager.addDynamicShortcuts(list(makeShortcutWithIcon("s1", bmp32x32))));

        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_2, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_3, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "s1", USER_10));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_2, "s1", USER_10));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_3, "s1", USER_10));

        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_1, USER_0));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_2, USER_0));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_3, USER_0));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_1, USER_10));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_2, USER_10));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_3, USER_10));

        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageDataClear(CALLING_PACKAGE_1, USER_0));

        assertNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_2, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_3, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "s1", USER_10));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_2, "s1", USER_10));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_3, "s1", USER_10));

        assertFalse(bitmapDirectoryExists(CALLING_PACKAGE_1, USER_0));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_2, USER_0));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_3, USER_0));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_1, USER_10));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_2, USER_10));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_3, USER_10));

        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageDataClear(CALLING_PACKAGE_2, USER_10));

        assertNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_2, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_3, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "s1", USER_10));
        assertNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_2, "s1", USER_10));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_3, "s1", USER_10));

        assertFalse(bitmapDirectoryExists(CALLING_PACKAGE_1, USER_0));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_2, USER_0));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_3, USER_0));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_1, USER_10));
        assertFalse(bitmapDirectoryExists(CALLING_PACKAGE_2, USER_10));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_3, USER_10));
    }

    public void testHandlePackageUpdate() throws Throwable {

        // Set up shortcuts and launchers.

        final Icon res32x32 = Icon.createWithResource(getTestContext(), R.drawable.black_32x32);
        final Icon bmp32x32 = Icon.createWithBitmap(BitmapFactory.decodeResource(
                getTestContext().getResources(), R.drawable.black_32x32));

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"),
                    makeShortcutWithIcon("s2", res32x32),
                    makeShortcutWithIcon("s3", res32x32),
                    makeShortcutWithIcon("s4", bmp32x32))));
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"),
                    makeShortcutWithIcon("s2", bmp32x32))));
        });
        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcutWithIcon("s1", res32x32))));
        });

        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcutWithIcon("s1", res32x32),
                    makeShortcutWithIcon("s2", res32x32))));
        });
        runWithCaller(CALLING_PACKAGE_2, USER_10, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcutWithIcon("s1", bmp32x32),
                    makeShortcutWithIcon("s2", bmp32x32))));
        });

        LauncherApps.Callback c0 = mock(LauncherApps.Callback.class);
        LauncherApps.Callback c10 = mock(LauncherApps.Callback.class);

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.registerCallback(c0, new Handler(Looper.getMainLooper()));
        });
        runWithCaller(LAUNCHER_1, USER_10, () -> {
            mLauncherApps.registerCallback(c10, new Handler(Looper.getMainLooper()));
        });

        mInjectedCurrentTimeMillis = START_TIME + 100;

        ArgumentCaptor<List> shortcuts;

        // First, call the event without updating the versions.
        reset(c0);
        reset(c10);

        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageUpdateIntent(CALLING_PACKAGE_1, USER_0));
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageUpdateIntent(CALLING_PACKAGE_1, USER_10));

        waitOnMainThread();

        // Version not changed, so no callback.
        verify(c0, times(0)).onShortcutsChanged(
                eq(CALLING_PACKAGE_1),
                any(List.class),
                any(UserHandle.class));
        verify(c10, times(0)).onShortcutsChanged(
                eq(CALLING_PACKAGE_1),
                any(List.class),
                any(UserHandle.class));

        // Next, update the version info for package 1.
        reset(c0);
        reset(c10);
        updatePackageVersion(CALLING_PACKAGE_1, 1);

        // Then send the broadcast, to only user-0.
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageUpdateIntent(CALLING_PACKAGE_1, USER_0));

        waitOnMainThread();

        // User-0 should get the notification.
        shortcuts = ArgumentCaptor.forClass(List.class);
        verify(c0).onShortcutsChanged(
                eq(CALLING_PACKAGE_1),
                shortcuts.capture(),
                eq(HANDLE_USER_0));

        // User-10 shouldn't yet get the notification.
        verify(c10, times(0)).onShortcutsChanged(
                eq(CALLING_PACKAGE_1),
                any(List.class),
                any(UserHandle.class));
        assertShortcutIds(shortcuts.getValue(), "s1", "s2", "s3", "s4");
        assertEquals(START_TIME,
                findShortcut(shortcuts.getValue(), "s1").getLastChangedTimestamp());
        assertEquals(START_TIME + 100,
                findShortcut(shortcuts.getValue(), "s2").getLastChangedTimestamp());
        assertEquals(START_TIME + 100,
                findShortcut(shortcuts.getValue(), "s3").getLastChangedTimestamp());
        assertEquals(START_TIME,
                findShortcut(shortcuts.getValue(), "s4").getLastChangedTimestamp());

        // Next, send unlock even on user-10.  Now we scan packages on this user and send a
        // notification to the launcher.
        mInjectedCurrentTimeMillis = START_TIME + 200;

        doAnswer(new AnswerIsUserRunning(true)).when(mMockUserManager).isUserRunning(eq(USER_10));

        reset(c0);
        reset(c10);
        mService.handleUnlockUser(USER_10);

        shortcuts = ArgumentCaptor.forClass(List.class);
        verify(c0, times(0)).onShortcutsChanged(
                eq(CALLING_PACKAGE_1),
                any(List.class),
                any(UserHandle.class));

        verify(c10).onShortcutsChanged(
                eq(CALLING_PACKAGE_1),
                shortcuts.capture(),
                eq(HANDLE_USER_10));

        assertShortcutIds(shortcuts.getValue(), "s1", "s2");
        assertEquals(START_TIME + 200,
                findShortcut(shortcuts.getValue(), "s1").getLastChangedTimestamp());
        assertEquals(START_TIME + 200,
                findShortcut(shortcuts.getValue(), "s2").getLastChangedTimestamp());


        // Do the same thing for package 2, which doesn't have resource icons.
        mInjectedCurrentTimeMillis = START_TIME + 300;

        reset(c0);
        reset(c10);
        updatePackageVersion(CALLING_PACKAGE_2, 10);

        // Then send the broadcast, to only user-0.
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageUpdateIntent(CALLING_PACKAGE_2, USER_0));
        mService.handleUnlockUser(USER_10);

        waitOnMainThread();

        verify(c0, times(0)).onShortcutsChanged(
                eq(CALLING_PACKAGE_1),
                any(List.class),
                any(UserHandle.class));

        verify(c10, times(0)).onShortcutsChanged(
                eq(CALLING_PACKAGE_1),
                any(List.class),
                any(UserHandle.class));

        // Do the same thing for package 3
        mInjectedCurrentTimeMillis = START_TIME + 400;

        reset(c0);
        reset(c10);
        updatePackageVersion(CALLING_PACKAGE_3, 100);

        // Then send the broadcast, to only user-0.
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageUpdateIntent(CALLING_PACKAGE_3, USER_0));
        mService.handleUnlockUser(USER_10);

        waitOnMainThread();

        shortcuts = ArgumentCaptor.forClass(List.class);
        verify(c0).onShortcutsChanged(
                eq(CALLING_PACKAGE_3),
                shortcuts.capture(),
                eq(HANDLE_USER_0));

        // User 10 doesn't have package 3, so no callback.
        verify(c10, times(0)).onShortcutsChanged(
                eq(CALLING_PACKAGE_3),
                any(List.class),
                any(UserHandle.class));

        assertShortcutIds(shortcuts.getValue(), "s1");
        assertEquals(START_TIME + 400,
                findShortcut(shortcuts.getValue(), "s1").getLastChangedTimestamp());
    }

    protected void prepareForBackupTest() {

        prepareCrossProfileDataSet();

        backupAndRestore();
    }

    /**
     * Make sure the backup data doesn't have the following information:
     * - Launchers on other users.
     * - Non-backup app information.
     *
     * But restores all other infomation.
     *
     * It also omits the following pieces of information, but that's tested in
     * {@link ShortcutManagerTest2#testShortcutInfoSaveAndLoad_forBackup}.
     * - Unpinned dynamic shortcuts
     * - Bitmaps
     */
    public void testBackupAndRestore() {
        prepareForBackupTest();

        checkBackupAndRestore_success();
    }

    public void testBackupAndRestore_backupRestoreTwice() {
        prepareForBackupTest();

        // Note doing a backup & restore again here shouldn't affect the result.
        dumpsysOnLogcat("Before second backup");

        backupAndRestore();

        dumpsysOnLogcat("After second backup");

        checkBackupAndRestore_success();
    }

    public void testBackupAndRestore_backupRestoreMultiple() {
        prepareForBackupTest();

        // Note doing a backup & restore again here shouldn't affect the result.
        backupAndRestore();

        // This also shouldn't affect the result.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"),
                    makeShortcut("s4"), makeShortcut("s5"), makeShortcut("s6"))));
        });

        backupAndRestore();

        checkBackupAndRestore_success();
    }

    public void testBackupAndRestore_restoreToNewVersion() {
        prepareForBackupTest();

        // Note doing a backup & restore again here shouldn't affect the result.
        backupAndRestore();

        addPackage(CALLING_PACKAGE_1, CALLING_UID_1, 2);
        addPackage(LAUNCHER_1, LAUNCHER_UID_1, 5);

        checkBackupAndRestore_success();
    }

    public void testBackupAndRestore_restoreToSuperSetSignatures() {
        prepareForBackupTest();

        // Note doing a backup & restore again here shouldn't affect the result.
        backupAndRestore();

        // Change package signatures.
        addPackage(CALLING_PACKAGE_1, CALLING_UID_1, 1, "sigx", CALLING_PACKAGE_1);
        addPackage(LAUNCHER_1, LAUNCHER_UID_1, 4, LAUNCHER_1, "sigy");

        checkBackupAndRestore_success();
    }

    protected void checkBackupAndRestore_success() {
        // Make sure non-system user is not restored.
        final ShortcutUser userP0 = mService.getUserShortcutsLocked(USER_P0);
        assertEquals(0, userP0.getAllPackagesForTest().size());
        assertEquals(0, userP0.getAllLaunchersForTest().size());

        // Make sure only "allowBackup" apps are restored, and are shadow.
        final ShortcutUser user0 = mService.getUserShortcutsLocked(USER_0);
        assertExistsAndShadow(user0.getAllPackagesForTest().get(CALLING_PACKAGE_1));
        assertExistsAndShadow(user0.getAllPackagesForTest().get(CALLING_PACKAGE_2));
        assertExistsAndShadow(user0.getAllLaunchersForTest().get(PackageWithUser.of(USER_0, LAUNCHER_1)));
        assertExistsAndShadow(user0.getAllLaunchersForTest().get(PackageWithUser.of(USER_0, LAUNCHER_2)));

        assertNull(user0.getAllPackagesForTest().get(CALLING_PACKAGE_3));
        assertNull(user0.getAllLaunchersForTest().get(PackageWithUser.of(USER_0, LAUNCHER_3)));
        assertNull(user0.getAllLaunchersForTest().get(PackageWithUser.of(USER_P0, LAUNCHER_1)));

        installPackage(USER_0, CALLING_PACKAGE_1);
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertEquals(0, mManager.getDynamicShortcuts().size());
            assertShortcutIds(assertAllPinned(
                    mManager.getPinnedShortcuts()),
                    "s1", "s2");
        });

        installPackage(USER_0, LAUNCHER_1);
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_0)),
                    "s1");
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_2), HANDLE_USER_0))
                    /* empty, not restored */ );
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_3), HANDLE_USER_0))
                    /* empty, not restored */ );

            assertEquals(0, mLauncherApps.getShortcuts(QUERY_ALL, HANDLE_USER_P0).size());
        });

        installPackage(USER_0, CALLING_PACKAGE_2);
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertEquals(0, mManager.getDynamicShortcuts().size());
            assertShortcutIds(assertAllPinned(
                    mManager.getPinnedShortcuts()),
                    "s1", "s2", "s3");
        });

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_0)),
                    "s1");
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_2), HANDLE_USER_0)),
                    "s1", "s2");
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_3), HANDLE_USER_0))
                    /* empty, not restored */ );

            assertEquals(0, mLauncherApps.getShortcuts(QUERY_ALL, HANDLE_USER_P0).size());
        });

        // 3 shouldn't be backed up, so no pinned shortcuts.
        installPackage(USER_0, CALLING_PACKAGE_3);
        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            assertEquals(0, mManager.getDynamicShortcuts().size());
            assertEquals(0, mManager.getPinnedShortcuts().size());
        });

        // Launcher on a different profile shouldn't be restored.
        runWithCaller(LAUNCHER_1, USER_P0, () -> {
            assertEquals(0,
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_0)
                            .size());
            assertEquals(0,
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_2), HANDLE_USER_0)
                            .size());
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_3), HANDLE_USER_0))
                    /* wasn't restored, so still empty */ );
        });

        // Package on a different profile, no restore.
        installPackage(USER_P0, CALLING_PACKAGE_1);
        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            assertEquals(0, mManager.getDynamicShortcuts().size());
            assertEquals(0, mManager.getPinnedShortcuts().size());
        });

        // Restore launcher 2 on user 0.
        installPackage(USER_0, LAUNCHER_2);
        runWithCaller(LAUNCHER_2, USER_0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_0)),
                    "s2");
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_2), HANDLE_USER_0)),
                    "s2", "s3");
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_3), HANDLE_USER_0))
                    /* wasn't restored, so still empty */ );

            assertEquals(0, mLauncherApps.getShortcuts(QUERY_ALL, HANDLE_USER_P0).size());
        });


        // Restoration of launcher2 shouldn't affect other packages; so do the same checks and
        // make sure they still have the same result.
        installPackage(USER_0, CALLING_PACKAGE_1);
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertEquals(0, mManager.getDynamicShortcuts().size());
            assertShortcutIds(assertAllPinned(
                    mManager.getPinnedShortcuts()),
                    "s1", "s2");
        });

        installPackage(USER_0, LAUNCHER_1);
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_0)),
                    "s1");
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_2), HANDLE_USER_0)),
                    "s1", "s2");
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_3), HANDLE_USER_0))
                    /* wasn't restored, so still empty */ );

            assertEquals(0, mLauncherApps.getShortcuts(QUERY_ALL, HANDLE_USER_P0).size());
        });

        installPackage(USER_0, CALLING_PACKAGE_2);
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertEquals(0, mManager.getDynamicShortcuts().size());
            assertShortcutIds(assertAllPinned(
                    mManager.getPinnedShortcuts()),
                    "s1", "s2", "s3");
        });
    }

    public void testBackupAndRestore_publisherLowerVersion() {
        prepareForBackupTest();

        // Note doing a backup & restore again here shouldn't affect the result.
        backupAndRestore();

        addPackage(CALLING_PACKAGE_1, CALLING_UID_1, 0); // Lower version

        checkBackupAndRestore_publisherNotRestored();
    }

    public void testBackupAndRestore_publisherWrongSignature() {
        prepareForBackupTest();

        // Note doing a backup & restore again here shouldn't affect the result.
        backupAndRestore();

        addPackage(CALLING_PACKAGE_1, CALLING_UID_1, 10, "sigx"); // different signature

        checkBackupAndRestore_publisherNotRestored();
    }

    public void testBackupAndRestore_publisherNoLongerBackupTarget() {
        prepareForBackupTest();

        // Note doing a backup & restore again here shouldn't affect the result.
        backupAndRestore();

        updatePackageInfo(CALLING_PACKAGE_1,
                pi -> pi.applicationInfo.flags &= ~ApplicationInfo.FLAG_ALLOW_BACKUP);

        checkBackupAndRestore_publisherNotRestored();
    }

    protected void checkBackupAndRestore_publisherNotRestored() {
        installPackage(USER_0, CALLING_PACKAGE_1);
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertEquals(0, mManager.getDynamicShortcuts().size());
            assertEquals(0, mManager.getPinnedShortcuts().size());
        });

        installPackage(USER_0, CALLING_PACKAGE_2);
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertEquals(0, mManager.getDynamicShortcuts().size());
            assertShortcutIds(assertAllPinned(
                    mManager.getPinnedShortcuts()),
                    "s1", "s2", "s3");
        });

        installPackage(USER_0, LAUNCHER_1);
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_0))
                    /* empty */);
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_2), HANDLE_USER_0)),
                    "s1", "s2");
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_3), HANDLE_USER_0))
                    /* empty */);
        });
        installPackage(USER_0, LAUNCHER_2);
        runWithCaller(LAUNCHER_2, USER_0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_0))
                    /* empty */);
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_2), HANDLE_USER_0)),
                    "s2", "s3");
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_3), HANDLE_USER_0))
                    /* empty */);
        });

        installPackage(USER_0, CALLING_PACKAGE_3);
        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            assertEquals(0, mManager.getDynamicShortcuts().size());
            assertEquals(0, mManager.getPinnedShortcuts().size());
        });

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_0))
                    /* empty */);
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_2), HANDLE_USER_0)),
                    "s1", "s2");
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_3), HANDLE_USER_0))
                    /* empty */);
        });
        runWithCaller(LAUNCHER_2, USER_0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_0))
                    /* empty */);
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_2), HANDLE_USER_0)),
                    "s2", "s3");
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_3), HANDLE_USER_0))
                    /* empty */);
        });
    }

    public void testBackupAndRestore_launcherLowerVersion() {
        prepareForBackupTest();

        // Note doing a backup & restore again here shouldn't affect the result.
        backupAndRestore();

        addPackage(LAUNCHER_1, LAUNCHER_UID_1, 0); // Lower version

        checkBackupAndRestore_launcherNotRestored();
    }

    public void testBackupAndRestore_launcherWrongSignature() {
        prepareForBackupTest();

        // Note doing a backup & restore again here shouldn't affect the result.
        backupAndRestore();

        addPackage(LAUNCHER_1, LAUNCHER_UID_1, 10, "sigx"); // different signature

        checkBackupAndRestore_launcherNotRestored();
    }

    public void testBackupAndRestore_launcherNoLongerBackupTarget() {
        prepareForBackupTest();

        // Note doing a backup & restore again here shouldn't affect the result.
        backupAndRestore();

        updatePackageInfo(LAUNCHER_1,
                pi -> pi.applicationInfo.flags &= ~ApplicationInfo.FLAG_ALLOW_BACKUP);

        checkBackupAndRestore_launcherNotRestored();
    }

    protected void checkBackupAndRestore_launcherNotRestored() {
        installPackage(USER_0, CALLING_PACKAGE_1);
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertEquals(0, mManager.getDynamicShortcuts().size());

            // s1 was pinned by launcher 1, which is not restored, yet, so we still see "s1" here.
            assertShortcutIds(assertAllPinned(
                    mManager.getPinnedShortcuts()),
                    "s1", "s2");
        });

        installPackage(USER_0, CALLING_PACKAGE_2);
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertEquals(0, mManager.getDynamicShortcuts().size());
            assertShortcutIds(assertAllPinned(
                    mManager.getPinnedShortcuts()),
                    "s1", "s2", "s3");
        });

        // Now we try to restore launcher 1.  Then we realize it's not restorable, so L1 has no pinned
        // shortcuts.
        installPackage(USER_0, LAUNCHER_1);
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_0))
                    /* empty */);
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_2), HANDLE_USER_0))
                    /* empty */);
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_3), HANDLE_USER_0))
                    /* empty */);
        });

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertEquals(0, mManager.getDynamicShortcuts().size());

            // Now CALLING_PACKAGE_1 realizes "s1" is no longer pinned.
            assertShortcutIds(assertAllPinned(
                    mManager.getPinnedShortcuts()),
                    "s2");
        });

        installPackage(USER_0, LAUNCHER_2);
        runWithCaller(LAUNCHER_2, USER_0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_0)),
                    "s2");
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_2), HANDLE_USER_0)),
                    "s2", "s3");
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_3), HANDLE_USER_0))
                    /* empty */);
        });

        installPackage(USER_0, CALLING_PACKAGE_3);
        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            assertEquals(0, mManager.getDynamicShortcuts().size());
            assertEquals(0, mManager.getPinnedShortcuts().size());
        });

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_0))
                    /* empty */);
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_2), HANDLE_USER_0))
                    /* empty */);
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_3), HANDLE_USER_0))
                    /* empty */);
        });
        runWithCaller(LAUNCHER_2, USER_0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_0)),
                    "s2");
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_2), HANDLE_USER_0)),
                    "s2", "s3");
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_3), HANDLE_USER_0))
                    /* empty */);
        });
    }

    public void testBackupAndRestore_launcherAndPackageNoLongerBackupTarget() {
        prepareForBackupTest();

        // Note doing a backup & restore again here shouldn't affect the result.
        backupAndRestore();

        updatePackageInfo(CALLING_PACKAGE_1,
                pi -> pi.applicationInfo.flags &= ~ApplicationInfo.FLAG_ALLOW_BACKUP);

        updatePackageInfo(LAUNCHER_1,
                pi -> pi.applicationInfo.flags &= ~ApplicationInfo.FLAG_ALLOW_BACKUP);

        checkBackupAndRestore_publisherAndLauncherNotRestored();
    }

    protected void checkBackupAndRestore_publisherAndLauncherNotRestored() {
        installPackage(USER_0, CALLING_PACKAGE_1);
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertEquals(0, mManager.getDynamicShortcuts().size());
            assertEquals(0, mManager.getPinnedShortcuts().size());
        });

        installPackage(USER_0, CALLING_PACKAGE_2);
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertEquals(0, mManager.getDynamicShortcuts().size());
            assertShortcutIds(assertAllPinned(
                    mManager.getPinnedShortcuts()),
                    "s1", "s2", "s3");
        });

        installPackage(USER_0, LAUNCHER_1);
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_0))
                    /* empty */);
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_2), HANDLE_USER_0))
                    /* empty */);
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_3), HANDLE_USER_0))
                    /* empty */);
        });
        installPackage(USER_0, LAUNCHER_2);
        runWithCaller(LAUNCHER_2, USER_0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_0))
                    /* empty */);
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_2), HANDLE_USER_0)),
                    "s2", "s3");
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_3), HANDLE_USER_0))
                    /* empty */);
        });

        // Because launcher 1 wasn't restored, "s1" is no longer pinned.
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertEquals(0, mManager.getDynamicShortcuts().size());
            assertShortcutIds(assertAllPinned(
                    mManager.getPinnedShortcuts()),
                    "s2", "s3");
        });

        installPackage(USER_0, CALLING_PACKAGE_3);
        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            assertEquals(0, mManager.getDynamicShortcuts().size());
            assertEquals(0, mManager.getPinnedShortcuts().size());
        });

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_0))
                    /* empty */);
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_2), HANDLE_USER_0))
                    /* empty */);
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_3), HANDLE_USER_0))
                    /* empty */);
        });
        runWithCaller(LAUNCHER_2, USER_0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_0))
                    /* empty */);
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_2), HANDLE_USER_0)),
                    "s2", "s3");
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_3), HANDLE_USER_0))
                    /* empty */);
        });
    }

    public void testSaveAndLoad_crossProfile() {
        prepareCrossProfileDataSet();

        dumpsysOnLogcat("Before save & load");

        mService.saveDirtyInfo();
        initService();

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertShortcutIds(assertAllDynamic(mManager.getDynamicShortcuts()),
                    "s1", "s2", "s3");
            assertShortcutIds(assertAllPinned(mManager.getPinnedShortcuts()),
                    "s1", "s2", "s3", "s4");
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertShortcutIds(assertAllDynamic(mManager.getDynamicShortcuts()),
                    "s1", "s2", "s3");
            assertShortcutIds(assertAllPinned(mManager.getPinnedShortcuts()),
                    "s1", "s2", "s3", "s4", "s5");
        });
        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            assertShortcutIds(assertAllDynamic(mManager.getDynamicShortcuts()),
                    "s1", "s2", "s3");
            assertShortcutIds(assertAllPinned(mManager.getPinnedShortcuts()),
                    "s1", "s2", "s3", "s4", "s5", "s6");
        });
        runWithCaller(CALLING_PACKAGE_4, USER_0, () -> {
            assertShortcutIds(assertAllDynamic(mManager.getDynamicShortcuts())
                    /* empty */);
            assertShortcutIds(assertAllPinned(mManager.getPinnedShortcuts())
                    /* empty */);
        });
        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            assertShortcutIds(assertAllDynamic(mManager.getDynamicShortcuts()),
                    "s1", "s2", "s3");
            assertShortcutIds(assertAllPinned(mManager.getPinnedShortcuts()),
                    "s1", "s2", "s3", "s4", "s5", "s6");
        });
        runWithCaller(CALLING_PACKAGE_2, USER_P0, () -> {
            assertShortcutIds(assertAllDynamic(mManager.getDynamicShortcuts())
                    /* empty */);
            assertShortcutIds(assertAllPinned(mManager.getPinnedShortcuts())
                    /* empty */);
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertShortcutIds(assertAllDynamic(mManager.getDynamicShortcuts()),
                    "x1", "x2", "x3");
            assertShortcutIds(assertAllPinned(mManager.getPinnedShortcuts()),
                    "x4", "x5");
        });
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_1), HANDLE_USER_0),
                    "s1");
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_2), HANDLE_USER_0),
                    "s1", "s2");
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_3), HANDLE_USER_0),
                    "s1", "s2", "s3");
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_4), HANDLE_USER_0)
                    /* empty */);
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_1), HANDLE_USER_P0),
                    "s1", "s4");
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_2), HANDLE_USER_P0)
                    /* empty */);
            assertExpectException(
                    SecurityException.class, "", () -> {
                        mLauncherApps.getShortcuts(
                                buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_10);
                    });
        });
        runWithCaller(LAUNCHER_2, USER_0, () -> {
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_1), HANDLE_USER_0),
                    "s2");
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_2), HANDLE_USER_0),
                    "s2", "s3");
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_3), HANDLE_USER_0),
                    "s2", "s3", "s4");
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_4), HANDLE_USER_0)
                    /* empty */);
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_1), HANDLE_USER_P0),
                    "s2", "s5");
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_2), HANDLE_USER_P0)
                    /* empty */);
        });
        runWithCaller(LAUNCHER_3, USER_0, () -> {
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_1), HANDLE_USER_0),
                    "s3");
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_2), HANDLE_USER_0),
                    "s3", "s4");
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_3), HANDLE_USER_0),
                    "s3", "s4", "s5");
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_4), HANDLE_USER_0)
                    /* empty */);
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_1), HANDLE_USER_P0),
                    "s3", "s6");
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_2), HANDLE_USER_P0)
                    /* empty */);
        });
        runWithCaller(LAUNCHER_4, USER_0, () -> {
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_1), HANDLE_USER_0)
                    /* empty */);
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_2), HANDLE_USER_0)
                    /* empty */);
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_3), HANDLE_USER_0)
                    /* empty */);
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_4), HANDLE_USER_0)
                    /* empty */);
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_1), HANDLE_USER_P0)
                    /* empty */);
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_2), HANDLE_USER_P0)
                    /* empty */);
        });
        runWithCaller(LAUNCHER_1, USER_P0, () -> {
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_1), HANDLE_USER_0),
                    "s3", "s4");
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_2), HANDLE_USER_0),
                    "s3", "s4", "s5");
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_3), HANDLE_USER_0),
                    "s3", "s4", "s5", "s6");
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_1), HANDLE_USER_P0),
                    "s1", "s4");
            assertExpectException(
                    SecurityException.class, "unrelated profile", () -> {
                        mLauncherApps.getShortcuts(
                                buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_10);
                    });
        });
        runWithCaller(LAUNCHER_1, USER_10, () -> {
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_1), HANDLE_USER_10),
                    "x4", "x5");
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_2), HANDLE_USER_10)
                    /* empty */);
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_3), HANDLE_USER_10)
                    /* empty */);
            assertExpectException(
                    SecurityException.class, "unrelated profile", () -> {
                        mLauncherApps.getShortcuts(
                                buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_0);
                    });
            assertExpectException(
                    SecurityException.class, "unrelated profile", () -> {
                        mLauncherApps.getShortcuts(
                                buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_P0);
                    });
        });
    }

    public void testOnApplicationActive_permission() {
        assertExpectException(SecurityException.class, "Missing permission", () ->
                mService.onApplicationActive(CALLING_PACKAGE_1, USER_0));

        // Has permission, now it should pass.
        mCallerPermissions.add(permission.RESET_SHORTCUT_MANAGER_THROTTLING);
        mService.onApplicationActive(CALLING_PACKAGE_1, USER_0);
    }

    public void testDumpsys_crossProfile() {
        prepareCrossProfileDataSet();
        dumpsysOnLogcat("test1", /* force= */ true);
    }

    public void testDumpsys_withIcons() throws IOException {
        testIcons();
        // Dump after having some icons.
        dumpsysOnLogcat("test1", /* force= */ true);
    }

    public void testManifestShortcut_publishOnUnlockUser() {
        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                R.xml.shortcut_1);
        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_2, ShortcutActivity.class.getName()),
                R.xml.shortcut_2);
        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_3, ShortcutActivity.class.getName()),
                R.xml.shortcut_5);

        // Unlock user-0.
        mService.handleUnlockUser(USER_0);

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertShortcutIds(assertAllManifest(assertAllImmutable(assertAllEnabled(
                    mManager.getManifestShortcuts()))),
                    "ms1");
            assertEmpty(mManager.getPinnedShortcuts());
        });

        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertShortcutIds(assertAllManifest(assertAllImmutable(assertAllEnabled(
                    mManager.getManifestShortcuts()))),
                    "ms1", "ms2");
            assertEmpty(mManager.getPinnedShortcuts());
        });

        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            assertShortcutIds(assertAllManifest(assertAllImmutable(assertAllEnabled(
                    mManager.getManifestShortcuts()))),
                    "ms1", "ms2", "ms3", "ms4", "ms5");
            assertEmpty(mManager.getPinnedShortcuts());
        });

        // Try on another user, with some packages uninstalled.
        uninstallPackage(USER_10, CALLING_PACKAGE_1);
        uninstallPackage(USER_10, CALLING_PACKAGE_3);

        mService.handleUnlockUser(USER_10);

        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertEmpty(mManager.getManifestShortcuts());
            assertEmpty(mManager.getPinnedShortcuts());
        });

        runWithCaller(CALLING_PACKAGE_2, USER_10, () -> {
            assertShortcutIds(assertAllManifest(assertAllImmutable(assertAllEnabled(
                    mManager.getManifestShortcuts()))),
                    "ms1", "ms2");
            assertEmpty(mManager.getPinnedShortcuts());
        });

        runWithCaller(CALLING_PACKAGE_3, USER_10, () -> {
            assertEmpty(mManager.getManifestShortcuts());
            assertEmpty(mManager.getPinnedShortcuts());
        });

        // Now change the resources for package 1, and unlock again.
        // But we still see *old* shortcuts, because the package version and install time
        // hasn't changed.
        shutdownServices();

        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                R.xml.shortcut_5);
        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_3, ShortcutActivity.class.getName()),
                R.xml.shortcut_1);

        initService();
        mService.handleUnlockUser(USER_0);

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertShortcutIds(assertAllManifest(assertAllImmutable(assertAllEnabled(
                    mManager.getManifestShortcuts()))),
                    "ms1");
            assertEmpty(mManager.getPinnedShortcuts());
        });

        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertShortcutIds(assertAllManifest(assertAllImmutable(assertAllEnabled(
                    mManager.getManifestShortcuts()))),
                    "ms1", "ms2");
            assertEmpty(mManager.getPinnedShortcuts());
        });

        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            assertShortcutIds(assertAllManifest(assertAllImmutable(assertAllEnabled(
                    mManager.getManifestShortcuts()))),
                    "ms1", "ms2", "ms3", "ms4", "ms5");
            assertEmpty(mManager.getPinnedShortcuts());
        });

        // Do it again, but this time we change the app version, so we do detect the changes.
        shutdownServices();

        updatePackageVersion(CALLING_PACKAGE_1, 1);
        updatePackageLastUpdateTime(CALLING_PACKAGE_3, 1);

        initService();
        mService.handleUnlockUser(USER_0);

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertShortcutIds(assertAllManifest(assertAllImmutable(assertAllEnabled(
                    mManager.getManifestShortcuts()))),
                    "ms1", "ms2", "ms3", "ms4", "ms5");
            assertEmpty(mManager.getPinnedShortcuts());
        });

        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertShortcutIds(assertAllManifest(assertAllImmutable(assertAllEnabled(
                    mManager.getManifestShortcuts()))),
                    "ms1", "ms2");
            assertEmpty(mManager.getPinnedShortcuts());
        });

        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            assertShortcutIds(assertAllManifest(assertAllImmutable(assertAllEnabled(
                    mManager.getManifestShortcuts()))),
                    "ms1");
            assertEmpty(mManager.getPinnedShortcuts());
        });

        // Next, try removing all shortcuts, with some of them pinned.
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("ms3"), HANDLE_USER_0);
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2, list("ms2"), HANDLE_USER_0);
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_3, list("ms1"), HANDLE_USER_0);
        });
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertShortcutIds(assertAllManifest(assertAllImmutable(assertAllEnabled(
                    mManager.getManifestShortcuts()))),
                    "ms1", "ms2", "ms3", "ms4", "ms5");
            assertShortcutIds(assertAllImmutable(assertAllPinned(assertAllManifest(
                    assertAllEnabled(mManager.getPinnedShortcuts())))),
                    "ms3");
        });

        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertShortcutIds(assertAllManifest(assertAllImmutable(assertAllEnabled(
                    mManager.getManifestShortcuts()))),
                    "ms1", "ms2");
            assertShortcutIds(assertAllImmutable(assertAllPinned(assertAllManifest(
                    assertAllEnabled(mManager.getPinnedShortcuts())))),
                    "ms2");
        });

        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            assertShortcutIds(assertAllManifest(assertAllImmutable(assertAllEnabled(
                    mManager.getManifestShortcuts()))),
                    "ms1");
            assertShortcutIds(assertAllImmutable(assertAllPinned(assertAllManifest(
                    assertAllEnabled(mManager.getPinnedShortcuts())))),
                    "ms1");
        });

        shutdownServices();

        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                R.xml.shortcut_0);
        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_2, ShortcutActivity.class.getName()),
                R.xml.shortcut_1);
        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_3, ShortcutActivity.class.getName()),
                R.xml.shortcut_0);

        updatePackageVersion(CALLING_PACKAGE_1, 1);
        updatePackageVersion(CALLING_PACKAGE_2, 1);
        updatePackageVersion(CALLING_PACKAGE_3, 1);

        initService();
        mService.handleUnlockUser(USER_0);

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertEmpty(mManager.getManifestShortcuts());
            assertShortcutIds(assertAllImmutable(assertAllPinned(assertAllNotManifest(
                    assertAllDisabled(mManager.getPinnedShortcuts())))),
                    "ms3");
        });

        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertShortcutIds(assertAllManifest(assertAllImmutable(assertAllEnabled(
                    mManager.getManifestShortcuts()))),
                    "ms1");
            assertShortcutIds(assertAllImmutable(assertAllPinned(assertAllNotManifest(
                    assertAllDisabled(mManager.getPinnedShortcuts())))),
                    "ms2");
        });

        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            assertEmpty(mManager.getManifestShortcuts());
            assertShortcutIds(assertAllImmutable(assertAllPinned(assertAllNotManifest(
                    assertAllDisabled(mManager.getPinnedShortcuts())))),
                    "ms1");
        });

        // Make sure we don't have ShortcutPackage for packages that don't have shortcuts.
        assertNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_4, USER_0));
        assertNull(mService.getPackageShortcutForTest(LAUNCHER_1, USER_0));
    }


    public void testManifestShortcut_publishOnBroadcast() {
        // First, no packages are installed.
        uninstallPackage(USER_0, CALLING_PACKAGE_1);
        uninstallPackage(USER_0, CALLING_PACKAGE_2);
        uninstallPackage(USER_0, CALLING_PACKAGE_3);
        uninstallPackage(USER_0, CALLING_PACKAGE_4);
        uninstallPackage(USER_10, CALLING_PACKAGE_1);
        uninstallPackage(USER_10, CALLING_PACKAGE_2);
        uninstallPackage(USER_10, CALLING_PACKAGE_3);
        uninstallPackage(USER_10, CALLING_PACKAGE_4);

        mService.handleUnlockUser(USER_0);
        mService.handleUnlockUser(USER_10);

        // Originally no manifest shortcuts.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertEmpty(mManager.getManifestShortcuts());
            assertEmpty(mManager.getPinnedShortcuts());
        });

        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertEmpty(mManager.getManifestShortcuts());
            assertEmpty(mManager.getPinnedShortcuts());
        });

        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            assertEmpty(mManager.getManifestShortcuts());
            assertEmpty(mManager.getPinnedShortcuts());
        });

        // Package 1 updated, with manifest shortcuts.
        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                R.xml.shortcut_1);
        updatePackageVersion(CALLING_PACKAGE_1, 1);
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageAddIntent(CALLING_PACKAGE_1, USER_0));

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertShortcutIds(assertAllManifest(assertAllImmutable(assertAllEnabled(
                    mManager.getManifestShortcuts()))),
                    "ms1");
            assertEmpty(mManager.getPinnedShortcuts());
        });

        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertEmpty(mManager.getManifestShortcuts());
            assertEmpty(mManager.getPinnedShortcuts());
        });

        // Package 2 updated, with manifest shortcuts.

        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_2, ShortcutActivity.class.getName()),
                R.xml.shortcut_5);
        updatePackageVersion(CALLING_PACKAGE_2, 1);
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageAddIntent(CALLING_PACKAGE_2, USER_0));

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertShortcutIds(assertAllManifest(assertAllImmutable(assertAllEnabled(
                    mManager.getManifestShortcuts()))),
                    "ms1");
            assertEmpty(mManager.getPinnedShortcuts());
        });

        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertShortcutIds(assertAllManifest(assertAllImmutable(assertAllEnabled(
                    mManager.getManifestShortcuts()))),
                    "ms1", "ms2", "ms3", "ms4", "ms5");
            assertEmpty(mManager.getPinnedShortcuts());
        });

        // Package 2 updated, with less manifest shortcuts.
        // This time we use updatePackageLastUpdateTime() instead of updatePackageVersion().

        dumpsysOnLogcat("Before pinning");

        // Also pin some.
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2, list("ms2", "ms3"), HANDLE_USER_0);
        });

        dumpsysOnLogcat("After pinning");

        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_2, ShortcutActivity.class.getName()),
                R.xml.shortcut_2);
        updatePackageLastUpdateTime(CALLING_PACKAGE_2, 1);
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageAddIntent(CALLING_PACKAGE_2, USER_0));

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertShortcutIds(assertAllManifest(assertAllImmutable(assertAllEnabled(
                    mManager.getManifestShortcuts()))),
                    "ms1");
            assertEmpty(mManager.getPinnedShortcuts());
        });

        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertShortcutIds(assertAllManifest(assertAllImmutable(assertAllEnabled(
                    mManager.getManifestShortcuts()))),
                    "ms1", "ms2");
            assertShortcutIds(assertAllImmutable(assertAllPinned(
                    mManager.getPinnedShortcuts())),
                    "ms2", "ms3");
            // ms3 is no longer in manifest, so should be disabled.
            // but ms1 and ms2 should be enabled.
            assertAllEnabled(list(getCallerShortcut("ms1")));
            assertAllEnabled(list(getCallerShortcut("ms2")));
            assertAllDisabled(list(getCallerShortcut("ms3")));
        });

        // Package 2 on user 10 has no shortcuts yet.
        runWithCaller(CALLING_PACKAGE_2, USER_10, () -> {
            assertEmpty(mManager.getManifestShortcuts());
            assertEmpty(mManager.getPinnedShortcuts());
        });
        // Send PACKAGE_ADD broadcast to have Package 2 on user-10 publish manifest shortcuts.
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageAddIntent(CALLING_PACKAGE_2, USER_10));

        runWithCaller(CALLING_PACKAGE_2, USER_10, () -> {
            assertShortcutIds(assertAllManifest(assertAllImmutable(assertAllEnabled(
                    mManager.getManifestShortcuts()))),
                    "ms1", "ms2");
            assertEmpty(mManager.getPinnedShortcuts());
        });

        // But it shouldn't affect user-0.
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertShortcutIds(assertAllManifest(assertAllImmutable(assertAllEnabled(
                    mManager.getManifestShortcuts()))),
                    "ms1", "ms2");
            assertShortcutIds(assertAllImmutable(assertAllPinned(
                    mManager.getPinnedShortcuts())),
                    "ms2", "ms3");
            assertAllEnabled(list(getCallerShortcut("ms1")));
            assertAllEnabled(list(getCallerShortcut("ms2")));
            assertAllDisabled(list(getCallerShortcut("ms3")));
        });

        // Package 2 now has no manifest shortcuts.
        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_2, ShortcutActivity.class.getName()),
                R.xml.shortcut_0);
        updatePackageLastUpdateTime(CALLING_PACKAGE_2, 1);
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageAddIntent(CALLING_PACKAGE_2, USER_0));

        // No manifest shortcuts, and pinned ones are disabled.
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertEmpty(mManager.getManifestShortcuts());
            assertShortcutIds(assertAllImmutable(assertAllPinned(assertAllDisabled(
                    mManager.getPinnedShortcuts()))),
                    "ms2", "ms3");
        });
    }

    public void testManifestShortcuts_missingMandatoryFields() {
        // Start with no apps installed.
        uninstallPackage(USER_0, CALLING_PACKAGE_1);
        uninstallPackage(USER_0, CALLING_PACKAGE_2);
        uninstallPackage(USER_0, CALLING_PACKAGE_3);
        uninstallPackage(USER_0, CALLING_PACKAGE_4);

        mService.handleUnlockUser(USER_0);

        // Make sure no manifest shortcuts.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertEmpty(mManager.getManifestShortcuts());
        });

        // Package 1 updated, which has one valid manifest shortcut and one invalid.
        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                R.xml.shortcut_error_1);
        updatePackageVersion(CALLING_PACKAGE_1, 1);
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageAddIntent(CALLING_PACKAGE_1, USER_0));

        // Only the valid one is published.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertShortcutIds(assertAllManifest(assertAllImmutable(assertAllEnabled(
                    mManager.getManifestShortcuts()))),
                    "x1");
        });

        // Package 1 updated, which has one valid manifest shortcut and one invalid.
        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                R.xml.shortcut_error_2);
        updatePackageVersion(CALLING_PACKAGE_1, 1);
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageAddIntent(CALLING_PACKAGE_1, USER_0));

        // Only the valid one is published.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertShortcutIds(assertAllManifest(assertAllImmutable(assertAllEnabled(
                    mManager.getManifestShortcuts()))),
                    "x2");
        });

        // Package 1 updated, which has one valid manifest shortcut and one invalid.
        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                R.xml.shortcut_error_3);
        updatePackageVersion(CALLING_PACKAGE_1, 1);
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageAddIntent(CALLING_PACKAGE_1, USER_0));

        // Only the valid one is published.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertShortcutIds(assertAllManifest(assertAllImmutable(assertAllEnabled(
                    mManager.getManifestShortcuts()))),
                    "x3");
        });
    }

    public void testManifestShortcuts_checkAllFields() {
        mService.handleUnlockUser(USER_0);

        // Package 1 updated, which has one valid manifest shortcut and one invalid.
        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                R.xml.shortcut_5);
        updatePackageVersion(CALLING_PACKAGE_1, 1);
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageAddIntent(CALLING_PACKAGE_1, USER_0));

        // Only the valid one is published.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertShortcutIds(assertAllManifest(assertAllImmutable(assertAllEnabled(
                    mManager.getManifestShortcuts()))),
                    "ms1", "ms2", "ms3", "ms4", "ms5");

            // check first shortcut.
            ShortcutInfo si = getCallerShortcut("ms1");

            assertEquals("ms1", si.getId());
            assertEquals(R.drawable.icon1, si.getIconResourceId());
            assertEquals(new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                    si.getActivity());
            assertEquals(R.string.shortcut_title1, si.getTitleResId());
            assertEquals(R.string.shortcut_text1, si.getTextResId());
            assertEquals(R.string.shortcut_disabled_message1, si.getDisabledMessageResourceId());
            assertEquals(set("android.shortcut.conversation", "android.shortcut.media"),
                    si.getCategories());
            assertEquals("action1", si.getIntent().getAction());
            assertEquals(Uri.parse("http://a.b.c/1"), si.getIntent().getData());
            assertEquals(0, si.getRank());

            // check another
            si = getCallerShortcut("ms2");

            assertEquals("ms2", si.getId());
            assertEquals(R.drawable.icon2, si.getIconResourceId());
            assertEquals(R.string.shortcut_title2, si.getTitleResId());
            assertEquals(R.string.shortcut_text2, si.getTextResId());
            assertEquals(R.string.shortcut_disabled_message2, si.getDisabledMessageResourceId());
            assertEquals(set("android.shortcut.conversation"), si.getCategories());
            assertEquals("action2", si.getIntent().getAction());
            assertEquals(null, si.getIntent().getData());
            assertEquals(1, si.getRank());

            // check another
            si = getCallerShortcut("ms3");

            assertEquals("ms3", si.getId());
            assertEquals(0, si.getIconResourceId());
            assertEquals(R.string.shortcut_title1, si.getTitleResId());
            assertEquals(0, si.getTextResId());
            assertEquals(0, si.getDisabledMessageResourceId());
            assertEquals(null, si.getCategories());
            assertEquals("android.intent.action.VIEW", si.getIntent().getAction());
            assertEquals(null, si.getIntent().getData());
            assertEquals(2, si.getRank());
        });
    }

    public void testManifestShortcuts_updateAndDisabled_notPinned() {
        mService.handleUnlockUser(USER_0);

        // First, just publish a manifest shortcut.
        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                R.xml.shortcut_1);
        updatePackageVersion(CALLING_PACKAGE_1, 1);
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageAddIntent(CALLING_PACKAGE_1, USER_0));

        // Only the valid one is published.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertShortcutIds(assertAllManifest(assertAllImmutable(assertAllEnabled(
                    mManager.getManifestShortcuts()))),
                    "ms1");
            assertEmpty(mManager.getPinnedShortcuts());

            // Make sure there's no other dangling shortcuts.
            assertShortcutIds(getCallerShortcuts(), "ms1");
        });

        // Now version up, the manifest shortcut is disabled now.
        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                R.xml.shortcut_1_disable);
        updatePackageVersion(CALLING_PACKAGE_1, 1);
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageAddIntent(CALLING_PACKAGE_1, USER_0));

        // Because shortcut 1 wasn't pinned, it'll just go away.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertEmpty(mManager.getManifestShortcuts());
            assertEmpty(mManager.getPinnedShortcuts());

            // Make sure there's no other dangling shortcuts.
            assertEmpty(getCallerShortcuts());
        });
    }

    public void testManifestShortcuts_updateAndDisabled_pinned() {
        mService.handleUnlockUser(USER_0);

        // First, just publish a manifest shortcut.
        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                R.xml.shortcut_1);
        updatePackageVersion(CALLING_PACKAGE_1, 1);
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageAddIntent(CALLING_PACKAGE_1, USER_0));

        // Only the valid one is published.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertShortcutIds(assertAllManifest(assertAllImmutable(assertAllEnabled(
                    mManager.getManifestShortcuts()))),
                    "ms1");
            assertEmpty(mManager.getPinnedShortcuts());

            // Make sure there's no other dangling shortcuts.
            assertShortcutIds(getCallerShortcuts(), "ms1");
        });

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("ms1"), HANDLE_USER_0);
        });

        // Now upgrade, the manifest shortcut is disabled now.
        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                R.xml.shortcut_1_disable);
        updatePackageVersion(CALLING_PACKAGE_1, 1);
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageAddIntent(CALLING_PACKAGE_1, USER_0));

        // Because shortcut 1 was pinned, it'll still exist as pinned, but disabled.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertEmpty(mManager.getManifestShortcuts());
            assertShortcutIds(assertAllNotManifest(assertAllImmutable(assertAllDisabled(
                    mManager.getPinnedShortcuts()))),
                    "ms1");

            // Make sure the fields are updated.
            ShortcutInfo si = getCallerShortcut("ms1");

            assertEquals("ms1", si.getId());
            assertEquals(R.drawable.icon2, si.getIconResourceId());
            assertEquals(R.string.shortcut_title2, si.getTitleResId());
            assertEquals(R.string.shortcut_text2, si.getTextResId());
            assertEquals(R.string.shortcut_disabled_message2, si.getDisabledMessageResourceId());
            assertEquals(Intent.ACTION_VIEW, si.getIntent().getAction());

            // Make sure there's no other dangling shortcuts.
            assertShortcutIds(getCallerShortcuts(), "ms1");
        });
    }

    public void testManifestShortcuts_duplicateInSingleActivity() {
        mService.handleUnlockUser(USER_0);

        // The XML has two shortcuts with the same ID.
        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                R.xml.shortcut_2_duplicate);
        updatePackageVersion(CALLING_PACKAGE_1, 1);
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageAddIntent(CALLING_PACKAGE_1, USER_0));

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertShortcutIds(assertAllManifest(assertAllImmutable(assertAllEnabled(
                    mManager.getManifestShortcuts()))),
                    "ms1");

            // Make sure the first one has survived.  (the second one has a different title.)
            ShortcutInfo si = getCallerShortcut("ms1");
            assertEquals(R.string.shortcut_title1, si.getTitleResId());

            // Make sure there's no other dangling shortcuts.
            assertShortcutIds(getCallerShortcuts(), "ms1");
        });
    }

    public void testManifestShortcuts_duplicateInTwoActivities() {
        mService.handleUnlockUser(USER_0);

        // ShortcutActivity has shortcut ms1
        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                R.xml.shortcut_1);

        // ShortcutActivity2 has two shortcuts, ms1 and ms2.
        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_1, ShortcutActivity2.class.getName()),
                R.xml.shortcut_2);
        updatePackageVersion(CALLING_PACKAGE_1, 1);
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageAddIntent(CALLING_PACKAGE_1, USER_0));

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertShortcutIds(assertAllManifest(assertAllImmutable(assertAllEnabled(
                    mManager.getManifestShortcuts()))),
                    "ms1", "ms2");

            // ms1 should belong to ShortcutActivity.
            ShortcutInfo si = getCallerShortcut("ms1");
            assertEquals(R.string.shortcut_title1, si.getTitleResId());
            assertEquals(new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                    si.getActivity());

            // ms2 should belong to ShortcutActivity*2*.
            si = getCallerShortcut("ms2");
            assertEquals(R.string.shortcut_title2, si.getTitleResId());
            assertEquals(new ComponentName(CALLING_PACKAGE_1, ShortcutActivity2.class.getName()),
                    si.getActivity());

            // Make sure there's no other dangling shortcuts.
            assertShortcutIds(getCallerShortcuts(), "ms1", "ms2");
        });
    }

    /**
     * Manifest shortcuts cannot override shortcuts that were published via the APIs.
     */
    public void testManifestShortcuts_cannotOverrideNonManifest() {
        mService.handleUnlockUser(USER_0);

        // Create a non-pinned dynamic shortcut and a non-dynamic pinned shortcut.

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            mManager.setDynamicShortcuts(list(
                    makeShortcut("ms1", "title1",
                            new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                    /* icon */ null, new Intent("action1"), /* rank */ 0),
                    makeShortcut("ms2", "title2",
                            new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                    /* icon */ null, new Intent("action1"), /* rank */ 0)));
        });

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("ms2"), HANDLE_USER_0);
        });

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            mManager.removeDynamicShortcuts(list("ms2"));

            assertShortcutIds(mManager.getDynamicShortcuts(), "ms1");
            assertShortcutIds(mManager.getPinnedShortcuts(), "ms2");
            assertEmpty(mManager.getManifestShortcuts());
        });

        // Then update the app with 5 manifest shortcuts.
        // Make sure "ms1" and "ms2" won't be replaced.
        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                R.xml.shortcut_5);
        updatePackageVersion(CALLING_PACKAGE_1, 1);
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageAddIntent(CALLING_PACKAGE_1, USER_0));

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertShortcutIds(assertAllNotManifest(mManager.getDynamicShortcuts()), "ms1");
            assertShortcutIds(assertAllNotManifest(mManager.getPinnedShortcuts()), "ms2");
            assertShortcutIds(assertAllManifest(mManager.getManifestShortcuts()),
                    "ms3", "ms4", "ms5");

            // ms1 and ms2 shouold keep the original title.
            ShortcutInfo si = getCallerShortcut("ms1");
            assertEquals("title1", si.getTitle());

            si = getCallerShortcut("ms2");
            assertEquals("title2", si.getTitle());
        });
    }

    protected void checkManifestShortcuts_immutable_verify() {
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertShortcutIds(assertAllNotManifest(assertAllEnabled(
                    mManager.getDynamicShortcuts())),
                    "s1");
            assertShortcutIds(assertAllManifest(assertAllEnabled(
                    mManager.getManifestShortcuts())),
                    "ms1");
            assertShortcutIds(assertAllNotManifest(assertAllDisabled(
                    mManager.getPinnedShortcuts())),
                    "ms2");

            assertEquals("t1", getCallerShortcut("s1").getTitle());

            // Make sure there are no other shortcuts.
            assertShortcutIds(getCallerShortcuts(), "s1", "ms1", "ms2");
        });
    }

    /**
     * Make sure the APIs won't work on manifest shortcuts.
     */
    public void testManifestShortcuts_immutable() {
        mService.handleUnlockUser(USER_0);

        // Create a non-pinned manifest shortcut, a pinned shortcut that was originally
        // a manifest shortcut, as well as a dynamic shortcut.

        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                R.xml.shortcut_2);
        updatePackageVersion(CALLING_PACKAGE_1, 1);
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageAddIntent(CALLING_PACKAGE_1, USER_0));

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("ms2"), HANDLE_USER_0);
        });

        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                R.xml.shortcut_1);
        updatePackageVersion(CALLING_PACKAGE_1, 1);
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageAddIntent(CALLING_PACKAGE_1, USER_0));

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            mManager.addDynamicShortcuts(list(makeShortcutWithTitle("s1", "t1")));
        });

        checkManifestShortcuts_immutable_verify();

        // Note that even though the first argument is not immutable and only the second one
        // is immutable, the first argument should not be executed either.

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertCannotUpdateImmutable(() -> {
                mManager.setDynamicShortcuts(list(makeShortcut("xx"), makeShortcut("ms1")));
            });
            assertCannotUpdateImmutable(() -> {
                mManager.setDynamicShortcuts(list(makeShortcut("xx"), makeShortcut("ms2")));
            });
        });
        checkManifestShortcuts_immutable_verify();

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertCannotUpdateImmutable(() -> {
                mManager.addDynamicShortcuts(list(makeShortcut("xx"), makeShortcut("ms1")));
            });
            assertCannotUpdateImmutable(() -> {
                mManager.addDynamicShortcuts(list(makeShortcut("xx"), makeShortcut("ms2")));
            });
        });
        checkManifestShortcuts_immutable_verify();


        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertCannotUpdateImmutable(() -> {
                mManager.updateShortcuts(list(makeShortcut("s1"), makeShortcut("ms1")));
            });
            assertCannotUpdateImmutable(() -> {
                mManager.updateShortcuts(list(makeShortcut("s1"), makeShortcut("ms2")));
            });
        });
        checkManifestShortcuts_immutable_verify();

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertCannotUpdateImmutable(() -> {
                mManager.removeDynamicShortcuts(list("s1", "ms1"));
            });
            assertCannotUpdateImmutable(() -> {
                mManager.removeDynamicShortcuts(list("s2", "ms2"));
            });
        });
        checkManifestShortcuts_immutable_verify();

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertCannotUpdateImmutable(() -> {
                mManager.disableShortcuts(list("s1", "ms1"));
            });
        });
        checkManifestShortcuts_immutable_verify();

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertCannotUpdateImmutable(() -> {
                mManager.enableShortcuts(list("s1", "ms2"));
            });
        });
        checkManifestShortcuts_immutable_verify();
    }


    /**
     * Make sure the APIs won't work on manifest shortcuts.
     */
    public void testManifestShortcuts_tooMany() {
        // Change the max number of shortcuts.
        mService.updateConfigurationLocked(ConfigConstants.KEY_MAX_SHORTCUTS + "=3");

        mService.handleUnlockUser(USER_0);

        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                R.xml.shortcut_5);
        updatePackageVersion(CALLING_PACKAGE_1, 1);
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageAddIntent(CALLING_PACKAGE_1, USER_0));

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            // Only the first 3 should be published.
            assertShortcutIds(mManager.getManifestShortcuts(), "ms1", "ms2", "ms3");
        });
    }

    public void testMaxShortcutCount_set() {
        // Change the max number of shortcuts.
        mService.updateConfigurationLocked(ConfigConstants.KEY_MAX_SHORTCUTS + "=3");

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            final ComponentName a1 = new ComponentName(mClientContext, ShortcutActivity.class);
            final ComponentName a2 = new ComponentName(mClientContext, ShortcutActivity2.class);
            final ShortcutInfo s1_1 = makeShortcutWithActivity("s11", a1);
            final ShortcutInfo s1_2 = makeShortcutWithActivity("s12", a1);
            final ShortcutInfo s1_3 = makeShortcutWithActivity("s13", a1);
            final ShortcutInfo s1_4 = makeShortcutWithActivity("s14", a1);
            final ShortcutInfo s1_5 = makeShortcutWithActivity("s15", a1);
            final ShortcutInfo s1_6 = makeShortcutWithActivity("s16", a1);
            final ShortcutInfo s2_1 = makeShortcutWithActivity("s21", a2);
            final ShortcutInfo s2_2 = makeShortcutWithActivity("s22", a2);
            final ShortcutInfo s2_3 = makeShortcutWithActivity("s23", a2);
            final ShortcutInfo s2_4 = makeShortcutWithActivity("s24", a2);

            // 3 shortcuts for 2 activities -> okay
            mManager.setDynamicShortcuts(list(s1_1, s1_2, s1_3, s2_1, s2_2, s2_3));
            assertShortcutIds(mManager.getDynamicShortcuts(),
                    "s11", "s12", "s13", "s21", "s22", "s23");

            mManager.removeAllDynamicShortcuts();

            // 4 shortcut for activity 1 -> too many.
            assertDynamicShortcutCountExceeded(() -> {
                mManager.setDynamicShortcuts(list(s1_1, s1_2, s1_3, s1_4, s2_1, s2_2, s2_3));
            });
            assertEmpty(mManager.getDynamicShortcuts());

            // 4 shortcut for activity 2 -> too many.
            assertDynamicShortcutCountExceeded(() -> {
                mManager.setDynamicShortcuts(list(s1_1, s1_2, s1_3, s2_1, s2_2, s2_3, s2_4));
            });
            assertEmpty(mManager.getDynamicShortcuts());

            // First, set 3.  Then set 4, which should be ignored.
            mManager.setDynamicShortcuts(list(s1_1, s1_2, s1_3));
            assertShortcutIds(mManager.getDynamicShortcuts(),
                    "s11", "s12", "s13");
            assertDynamicShortcutCountExceeded(() -> {
                mManager.setDynamicShortcuts(list(s2_1, s2_2, s2_3, s2_4));
            });
            assertShortcutIds(mManager.getDynamicShortcuts(),
                    "s11", "s12", "s13");

            // Set will remove the old dynamic set, unlike add, so the following should pass.
            mManager.setDynamicShortcuts(list(s1_1, s1_2, s1_3));
            assertShortcutIds(mManager.getDynamicShortcuts(),
                    "s11", "s12", "s13");
            mManager.setDynamicShortcuts(list(s1_4, s1_5, s1_6));
            assertShortcutIds(mManager.getDynamicShortcuts(),
                    "s14", "s15", "s16");

            // Now, test with 2 manifest shortcuts.
            mManager.removeAllDynamicShortcuts();
            addManifestShortcutResource(
                    new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                    R.xml.shortcut_2);
            updatePackageVersion(CALLING_PACKAGE_1, 1);
            mService.mPackageMonitor.onReceive(getTestContext(),
                    genPackageAddIntent(CALLING_PACKAGE_1, USER_0));
            assertEquals(2, mManager.getManifestShortcuts().size());

            // Setting 1 to activity 1 will work.
            mManager.setDynamicShortcuts(list(s1_1, s2_1, s2_2, s2_3));
            assertShortcutIds(mManager.getDynamicShortcuts(),
                    "s11", "s21", "s22", "s23");
            assertEquals(2, mManager.getManifestShortcuts().size());

            // But setting 2 will not.
            mManager.removeAllDynamicShortcuts();
            assertDynamicShortcutCountExceeded(() -> {
                mManager.setDynamicShortcuts(list(s1_1, s1_2, s2_1, s2_2, s2_3));
            });
            assertEmpty(mManager.getDynamicShortcuts());
            assertEquals(2, mManager.getManifestShortcuts().size());
        });
    }

    public void testMaxShortcutCount_add() {
        // Change the max number of shortcuts.
        mService.updateConfigurationLocked(ConfigConstants.KEY_MAX_SHORTCUTS + "=3");

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            final ComponentName a1 = new ComponentName(mClientContext, ShortcutActivity.class);
            final ComponentName a2 = new ComponentName(mClientContext, ShortcutActivity2.class);
            final ShortcutInfo s1_1 = makeShortcutWithActivity("s11", a1);
            final ShortcutInfo s1_2 = makeShortcutWithActivity("s12", a1);
            final ShortcutInfo s1_3 = makeShortcutWithActivity("s13", a1);
            final ShortcutInfo s1_4 = makeShortcutWithActivity("s14", a1);
            final ShortcutInfo s2_1 = makeShortcutWithActivity("s21", a2);
            final ShortcutInfo s2_2 = makeShortcutWithActivity("s22", a2);
            final ShortcutInfo s2_3 = makeShortcutWithActivity("s23", a2);
            final ShortcutInfo s2_4 = makeShortcutWithActivity("s24", a2);

            // 3 shortcuts for 2 activities -> okay
            mManager.addDynamicShortcuts(list(s1_1, s1_2, s1_3, s2_1, s2_2, s2_3));
            assertShortcutIds(mManager.getDynamicShortcuts(),
                    "s11", "s12", "s13", "s21", "s22", "s23");

            mManager.removeAllDynamicShortcuts();

            // 4 shortcut for activity 1 -> too many.
            assertDynamicShortcutCountExceeded(() -> {
                mManager.addDynamicShortcuts(list(s1_1, s1_2, s1_3, s1_4, s2_1, s2_2, s2_3));
            });
            assertEmpty(mManager.getDynamicShortcuts());

            // 4 shortcut for activity 2 -> too many.
            assertDynamicShortcutCountExceeded(() -> {
                mManager.addDynamicShortcuts(list(s1_1, s1_2, s1_3, s2_1, s2_2, s2_3, s2_4));
            });
            assertEmpty(mManager.getDynamicShortcuts());

            // First, set 3.  Then add 1 more, which should be ignored.
            mManager.setDynamicShortcuts(list(s1_1, s1_2, s1_3));
            assertShortcutIds(mManager.getDynamicShortcuts(),
                    "s11", "s12", "s13");
            assertDynamicShortcutCountExceeded(() -> {
                mManager.addDynamicShortcuts(list(s1_4, s2_1));
            });
            assertShortcutIds(mManager.getDynamicShortcuts(),
                    "s11", "s12", "s13");

            // Update existing one, which should work.
            mManager.addDynamicShortcuts(list(makeShortcutWithActivityAndTitle(
                    "s11", a1, "xxx"), s2_1));
            assertShortcutIds(mManager.getDynamicShortcuts(),
                    "s11", "s12", "s13", "s21");
            assertEquals("xxx", getCallerShortcut("s11").getTitle());

            // Make sure pinned shortcuts won't affect.
            // - Pin s11 - s13, and remove all dynamic.
            runWithCaller(LAUNCHER_1, USER_0, () -> {
                mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("s11", "s12", "s13"),
                        HANDLE_USER_0);
            });
            mManager.removeAllDynamicShortcuts();

            assertEmpty(mManager.getDynamicShortcuts());
            assertShortcutIds(mManager.getPinnedShortcuts(),
                    "s11", "s12", "s13");

            // Then add dynamic.
            mManager.addDynamicShortcuts(list(s1_4, s2_1, s2_2, s2_3));

            assertShortcutIds(mManager.getDynamicShortcuts(),
                    "s14", "s21", "s22", "s23");
            assertShortcutIds(mManager.getPinnedShortcuts(),
                    "s11", "s12", "s13");

            // Adding "s11" and "s12" back, should work
            mManager.addDynamicShortcuts(list(s1_1, s1_2));

            assertShortcutIds(mManager.getDynamicShortcuts(),
                    "s14", "s11", "s12", "s21", "s22", "s23");
            assertShortcutIds(mManager.getPinnedShortcuts(),
                    "s11", "s12", "s13");

            // Adding back s13 doesn't work.
            assertDynamicShortcutCountExceeded(() -> {
                mManager.addDynamicShortcuts(list(s1_3));
            });

            assertShortcutIds(filterByActivity(mManager.getDynamicShortcuts(), a1),
                    "s11", "s12", "s14");
            assertShortcutIds(filterByActivity(mManager.getDynamicShortcuts(), a2),
                    "s21", "s22", "s23");

            // Now swap the activities.
            mManager.updateShortcuts(list(
                    makeShortcutWithActivity("s11", a2),
                    makeShortcutWithActivity("s21", a1)));

            assertShortcutIds(filterByActivity(mManager.getDynamicShortcuts(), a1),
                    "s21", "s12", "s14");
            assertShortcutIds(filterByActivity(mManager.getDynamicShortcuts(), a2),
                    "s11", "s22", "s23");

            // Now, test with 2 manifest shortcuts.
            mManager.removeAllDynamicShortcuts();
            addManifestShortcutResource(
                    new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                    R.xml.shortcut_2);
            updatePackageVersion(CALLING_PACKAGE_1, 1);
            mService.mPackageMonitor.onReceive(getTestContext(),
                    genPackageAddIntent(CALLING_PACKAGE_1, USER_0));

            assertEquals(2, mManager.getManifestShortcuts().size());

            // Adding one shortcut to activity 1 works fine.
            mManager.addDynamicShortcuts(list(s1_1, s2_1, s2_2, s2_3));
            assertShortcutIds(mManager.getDynamicShortcuts(),
                    "s11", "s21", "s22", "s23");
            assertEquals(2, mManager.getManifestShortcuts().size());

            // But adding one more doesn't.
            assertDynamicShortcutCountExceeded(() -> {
                mManager.addDynamicShortcuts(list(s1_4, s2_1));
            });
            assertShortcutIds(mManager.getDynamicShortcuts(),
                    "s11", "s21", "s22", "s23");
            assertEquals(2, mManager.getManifestShortcuts().size());
        });
    }

    public void testMaxShortcutCount_update() {
        // Change the max number of shortcuts.
        mService.updateConfigurationLocked(ConfigConstants.KEY_MAX_SHORTCUTS + "=3");

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            final ComponentName a1 = new ComponentName(mClientContext, ShortcutActivity.class);
            final ComponentName a2 = new ComponentName(mClientContext, ShortcutActivity2.class);
            final ShortcutInfo s1_1 = makeShortcutWithActivity("s11", a1);
            final ShortcutInfo s1_2 = makeShortcutWithActivity("s12", a1);
            final ShortcutInfo s1_3 = makeShortcutWithActivity("s13", a1);
            final ShortcutInfo s1_4 = makeShortcutWithActivity("s14", a1);
            final ShortcutInfo s1_5 = makeShortcutWithActivity("s15", a1);
            final ShortcutInfo s2_1 = makeShortcutWithActivity("s21", a2);
            final ShortcutInfo s2_2 = makeShortcutWithActivity("s22", a2);
            final ShortcutInfo s2_3 = makeShortcutWithActivity("s23", a2);
            final ShortcutInfo s2_4 = makeShortcutWithActivity("s24", a2);

            // 3 shortcuts for 2 activities -> okay
            mManager.setDynamicShortcuts(list(s1_1, s1_2, s1_3, s2_1, s2_2, s2_3));
            assertShortcutIds(mManager.getDynamicShortcuts(),
                    "s11", "s12", "s13", "s21", "s22", "s23");

            // Trying to move s11 from a1 to a2 should fail.
            assertDynamicShortcutCountExceeded(() -> {
                mManager.updateShortcuts(list(makeShortcutWithActivity("s11", a2)));
            });
            assertShortcutIds(mManager.getDynamicShortcuts(),
                    "s11", "s12", "s13", "s21", "s22", "s23");

            // Trying to move s21 from a2 to a1 should also fail.
            assertDynamicShortcutCountExceeded(() -> {
                mManager.updateShortcuts(list(makeShortcutWithActivity("s21", a1)));
            });
            assertShortcutIds(mManager.getDynamicShortcuts(),
                    "s11", "s12", "s13", "s21", "s22", "s23");

            // But, if we do these two at the same time, it should work.
            mManager.updateShortcuts(list(
                    makeShortcutWithActivity("s11", a2),
                    makeShortcutWithActivity("s21", a1)));
            assertShortcutIds(mManager.getDynamicShortcuts(),
                    "s11", "s12", "s13", "s21", "s22", "s23");
            assertShortcutIds(filterByActivity(mManager.getDynamicShortcuts(), a1),
                    "s21", "s12", "s13");
            assertShortcutIds(filterByActivity(mManager.getDynamicShortcuts(), a2),
                    "s11", "s22", "s23");

            // Then reset.
            mManager.setDynamicShortcuts(list(s1_1, s1_2, s1_3, s2_1, s2_2, s2_3));
            assertShortcutIds(mManager.getDynamicShortcuts(),
                    "s11", "s12", "s13", "s21", "s22", "s23");

            // Pin some to have more shortcuts for a1.
            runWithCaller(LAUNCHER_1, USER_0, () -> {
                mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("s11", "s12", "s13"),
                        HANDLE_USER_0);
            });
            mManager.setDynamicShortcuts(list(s1_4, s1_5, s2_1, s2_2, s2_3));
            assertShortcutIds(mManager.getDynamicShortcuts(),
                    "s14", "s15", "s21", "s22", "s23");
            assertShortcutIds(mManager.getPinnedShortcuts(),
                    "s11", "s12", "s13");

            // a1 already has 2 dynamic shortcuts (and 3 pinned shortcuts that used to belong on it)
            // But that doesn't matter for update -- the following should still work.
            mManager.updateShortcuts(list(
                    makeShortcutWithActivityAndTitle("s11", a1, "xxx1"),
                    makeShortcutWithActivityAndTitle("s12", a1, "xxx2"),
                    makeShortcutWithActivityAndTitle("s13", a1, "xxx3"),
                    makeShortcutWithActivityAndTitle("s14", a1, "xxx4"),
                    makeShortcutWithActivityAndTitle("s15", a1, "xxx5")));
            // All the shortcuts should still exist they all belong on same activities,
            // with the updated titles.
            assertShortcutIds(mManager.getDynamicShortcuts(),
                    "s14", "s15", "s21", "s22", "s23");
            assertShortcutIds(mManager.getPinnedShortcuts(),
                    "s11", "s12", "s13");

            assertShortcutIds(filterByActivity(mManager.getDynamicShortcuts(), a1),
                    "s14", "s15");
            assertShortcutIds(filterByActivity(mManager.getDynamicShortcuts(), a2),
                    "s21", "s22", "s23");

            assertEquals("xxx1", getCallerShortcut("s11").getTitle());
            assertEquals("xxx2", getCallerShortcut("s12").getTitle());
            assertEquals("xxx3", getCallerShortcut("s13").getTitle());
            assertEquals("xxx4", getCallerShortcut("s14").getTitle());
            assertEquals("xxx5", getCallerShortcut("s15").getTitle());
        });
    }

    public void testShortcutsPushedOutByManifest() {
        // Change the max number of shortcuts.
        mService.updateConfigurationLocked(ConfigConstants.KEY_MAX_SHORTCUTS + "=3");

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            final ComponentName a1 = new ComponentName(mClientContext, ShortcutActivity.class);
            final ComponentName a2 = new ComponentName(mClientContext, ShortcutActivity2.class);
            final ShortcutInfo s1_1 = makeShortcutWithActivityAndRank("s11", a1, 4);
            final ShortcutInfo s1_2 = makeShortcutWithActivityAndRank("s12", a1, 3);
            final ShortcutInfo s1_3 = makeShortcutWithActivityAndRank("s13", a1, 2);
            final ShortcutInfo s1_4 = makeShortcutWithActivityAndRank("s14", a1, 1);
            final ShortcutInfo s1_5 = makeShortcutWithActivityAndRank("s15", a1, 0);
            final ShortcutInfo s2_1 = makeShortcutWithActivityAndRank("s21", a2, 0);
            final ShortcutInfo s2_2 = makeShortcutWithActivityAndRank("s22", a2, 1);
            final ShortcutInfo s2_3 = makeShortcutWithActivityAndRank("s23", a2, 2);
            final ShortcutInfo s2_4 = makeShortcutWithActivityAndRank("s24", a2, 3);
            final ShortcutInfo s2_5 = makeShortcutWithActivityAndRank("s25", a2, 4);

            // Initial state.
            mManager.setDynamicShortcuts(list(s1_1, s1_2, s1_3, s2_1, s2_2, s2_3));
            runWithCaller(LAUNCHER_1, USER_0, () -> {
                mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("s11", "s12", "s21", "s22"),
                        HANDLE_USER_0);
            });
            mManager.setDynamicShortcuts(list(s1_2, s1_3, s1_4, s2_2, s2_3, s2_4));
            assertShortcutIds(assertAllEnabled(mManager.getDynamicShortcuts()),
                    "s12", "s13", "s14",
                    "s22", "s23", "s24");
            assertShortcutIds(assertAllEnabled(mManager.getPinnedShortcuts()),
                    "s11", "s12",
                    "s21", "s22");

            // Add 1 manifest shortcut to a1.
            addManifestShortcutResource(
                    new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                    R.xml.shortcut_1);
            updatePackageVersion(CALLING_PACKAGE_1, 1);
            mService.mPackageMonitor.onReceive(getTestContext(),
                    genPackageAddIntent(CALLING_PACKAGE_1, USER_0));
            assertEquals(1, mManager.getManifestShortcuts().size());

            // s12 removed.
            assertShortcutIds(assertAllEnabled(mManager.getDynamicShortcuts()),
                    "s13", "s14",
                    "s22", "s23", "s24");
            assertShortcutIds(assertAllEnabled(mManager.getPinnedShortcuts()),
                    "s11", "s12",
                    "s21", "s22");

            // Add more manifest shortcuts.
            addManifestShortcutResource(
                    new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                    R.xml.shortcut_2);
            addManifestShortcutResource(
                    new ComponentName(CALLING_PACKAGE_1, ShortcutActivity2.class.getName()),
                    R.xml.shortcut_1_alt);
            updatePackageVersion(CALLING_PACKAGE_1, 1);
            mService.mPackageMonitor.onReceive(getTestContext(),
                    genPackageAddIntent(CALLING_PACKAGE_1, USER_0));
            assertEquals(3, mManager.getManifestShortcuts().size());

            // Note the ones with the highest rank values (== least important) will be removed.
            assertShortcutIds(assertAllEnabled(mManager.getDynamicShortcuts()),
                    "s14",
                    "s22", "s23");
            assertShortcutIds(assertAllEnabled(mManager.getPinnedShortcuts()),
                    "s11", "s12",
                    "s21", "s22");

            // Add more manifest shortcuts.
            addManifestShortcutResource(
                    new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                    R.xml.shortcut_2);
            addManifestShortcutResource(
                    new ComponentName(CALLING_PACKAGE_1, ShortcutActivity2.class.getName()),
                    R.xml.shortcut_5_alt); // manifest has 5, but max is 3, so a2 will have 3.
            updatePackageVersion(CALLING_PACKAGE_1, 1);
            mService.mPackageMonitor.onReceive(getTestContext(),
                    genPackageAddIntent(CALLING_PACKAGE_1, USER_0));
            assertEquals(5, mManager.getManifestShortcuts().size());

            assertShortcutIds(assertAllEnabled(mManager.getDynamicShortcuts()),
                    "s14" // a1 has 1 dynamic
            ); // a2 has no dynamic
            assertShortcutIds(assertAllEnabled(mManager.getPinnedShortcuts()),
                    "s11", "s12",
                    "s21", "s22");

            // Update, no manifest shortucts.  This doesn't affect anything.
            addManifestShortcutResource(
                    new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                    R.xml.shortcut_0);
            addManifestShortcutResource(
                    new ComponentName(CALLING_PACKAGE_1, ShortcutActivity2.class.getName()),
                    R.xml.shortcut_0);
            updatePackageVersion(CALLING_PACKAGE_1, 1);
            mService.mPackageMonitor.onReceive(getTestContext(),
                    genPackageAddIntent(CALLING_PACKAGE_1, USER_0));
            assertEquals(0, mManager.getManifestShortcuts().size());

            assertShortcutIds(assertAllEnabled(mManager.getDynamicShortcuts()),
                    "s14");
            assertShortcutIds(assertAllEnabled(mManager.getPinnedShortcuts()),
                    "s11", "s12",
                    "s21", "s22");
        });
    }
}
