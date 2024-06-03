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

import static android.content.pm.ShortcutInfo.DISABLED_REASON_BACKUP_NOT_SUPPORTED;
import static android.content.pm.ShortcutInfo.DISABLED_REASON_NOT_DISABLED;
import static android.content.pm.ShortcutInfo.DISABLED_REASON_SIGNATURE_MISMATCH;
import static android.content.pm.ShortcutInfo.DISABLED_REASON_VERSION_LOWER;

import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.anyOrNull;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.anyStringOrNull;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertAllDisabled;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertAllDynamic;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertAllDynamicOrPinned;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertAllEnabled;
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
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertForLauncherCallback;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertShortcutIds;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertWith;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.filterByActivity;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.findShortcut;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.hashSet;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.list;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.makeBundle;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.pfdToBitmap;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.resetAll;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.set;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.waitOnMainThread;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.Manifest.permission;
import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherApps;
import android.content.pm.LauncherApps.PinItemRequest;
import android.content.pm.LauncherApps.ShortcutQuery;
import android.content.pm.PackageInfo;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.BitmapFactory;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;
import android.util.Log;
import android.util.SparseArray;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;
import android.util.Xml;

import androidx.test.filters.SmallTest;

import com.android.frameworks.servicestests.R;
import com.android.server.pm.ShortcutService.ConfigConstants;
import com.android.server.pm.ShortcutService.FileOutputStreamWithPath;
import com.android.server.pm.ShortcutUser.PackageWithUser;

import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;

/**
 * Tests for ShortcutService and ShortcutManager.
 *
 m FrameworksServicesTests &&
 adb install \
 -r -g ${ANDROID_PRODUCT_OUT}/data/app/FrameworksServicesTests/FrameworksServicesTests.apk &&
 adb shell am instrument -e class com.android.server.pm.ShortcutManagerTest1 \
 -w com.android.frameworks.servicestests/androidx.test.runner.AndroidJUnitRunner
 */
@Presubmit
@SmallTest
public class ShortcutManagerTest1 extends BaseShortcutManagerTest {

    private static final int CACHE_OWNER_0 = LauncherApps.FLAG_CACHE_NOTIFICATION_SHORTCUTS;
    private static final int CACHE_OWNER_1 = LauncherApps.FLAG_CACHE_BUBBLE_SHORTCUTS;
    private static final int CACHE_OWNER_2 = LauncherApps.FLAG_CACHE_PEOPLE_TILE_SHORTCUTS;

    @Override
    protected void tearDown() throws Exception {
        deleteUriFile("file32x32.jpg");
        deleteUriFile("file64x64.jpg");
        deleteUriFile("file512x512.jpg");

        super.tearDown();
    }

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
        assertEquals(4, mService.getMaxShortcutsForTest());
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

        assertEquals(ShortcutService.DEFAULT_MAX_SHORTCUTS_PER_ACTIVITY,
                mService.getMaxShortcutsForTest());

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
        final Icon icon3 = Icon.createWithAdaptiveBitmap(BitmapFactory.decodeResource(
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
        final ShortcutInfo si3 = makeShortcut(
                "shortcut3",
                "Title 3",
                /* activity */ null,
                icon3,
                makeIntent(Intent.ACTION_ASSIST, ShortcutActivity3.class),
                /* weight */ 13);

        assertTrue(mManager.setDynamicShortcuts(list(si1, si2, si3)));
        assertShortcutIds(assertAllNotKeyFieldsOnly(
                mManager.getDynamicShortcuts()),
                "shortcut1", "shortcut2", "shortcut3");
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

        mRunningUsers.put(USER_10, true);

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

        mRunningUsers.put(USER_10, true);

        runWithCaller(CALLING_PACKAGE_2, USER_10, () -> {
            assertTrue(mManager.addDynamicShortcuts(list(makeShortcut("s1"))));
        });
    }

    public void testPushDynamicShortcut() {
        // Change the max number of shortcuts.
        mService.updateConfigurationLocked(ConfigConstants.KEY_MAX_SHORTCUTS + "=5,"
                + ShortcutService.ConfigConstants.KEY_SAVE_DELAY_MILLIS + "=1");
        setCaller(CALLING_PACKAGE_1, USER_0);

        final ShortcutInfo s1 = makeShortcut("s1");
        final ShortcutInfo s2 = makeShortcut("s2");
        final ShortcutInfo s3 = makeShortcut("s3");
        final ShortcutInfo s4 = makeShortcut("s4");
        final ShortcutInfo s5 = makeShortcut("s5");
        final ShortcutInfo s6 = makeShortcut("s6");
        final ShortcutInfo s7 = makeShortcut("s7");
        final ShortcutInfo s8 = makeShortcut("s8");
        final ShortcutInfo s9 = makeShortcut("s9");

        // Test push as first shortcut
        mManager.pushDynamicShortcut(s1);
        assertShortcutIds(assertAllNotKeyFieldsOnly(mManager.getDynamicShortcuts()), "s1");
        assertEquals(0, getCallerShortcut("s1").getRank());
        verify(mMockUsageStatsManagerInternal, times(1)).reportShortcutUsage(
                eq(CALLING_PACKAGE_1), eq("s1"), eq(USER_0));

        // Test push when other shortcuts exist
        Mockito.reset(mMockUsageStatsManagerInternal);
        assertTrue(mManager.setDynamicShortcuts(list(s1, s2)));
        assertShortcutIds(assertAllNotKeyFieldsOnly(mManager.getDynamicShortcuts()), "s1", "s2");
        mManager.pushDynamicShortcut(s3);
        assertShortcutIds(assertAllNotKeyFieldsOnly(mManager.getDynamicShortcuts()),
                "s1", "s2", "s3");
        assertEquals(0, getCallerShortcut("s3").getRank());
        assertEquals(1, getCallerShortcut("s1").getRank());
        assertEquals(2, getCallerShortcut("s2").getRank());
        verify(mMockUsageStatsManagerInternal, times(0)).reportShortcutUsage(
                eq(CALLING_PACKAGE_1), eq("s1"), eq(USER_0));
        verify(mMockUsageStatsManagerInternal, times(0)).reportShortcutUsage(
                eq(CALLING_PACKAGE_1), eq("s2"), eq(USER_0));
        verify(mMockUsageStatsManagerInternal, times(1)).reportShortcutUsage(
                eq(CALLING_PACKAGE_1), eq("s3"), eq(USER_0));

        mInjectedCurrentTimeMillis += INTERVAL; // reset

        // Push with set rank
        Mockito.reset(mMockUsageStatsManagerInternal);
        s4.setRank(2);
        mManager.pushDynamicShortcut(s4);
        assertEquals(2, getCallerShortcut("s4").getRank());
        assertEquals(3, getCallerShortcut("s2").getRank());
        verify(mMockUsageStatsManagerInternal, times(1)).reportShortcutUsage(
                eq(CALLING_PACKAGE_1), eq("s4"), eq(USER_0));

        // Push existing shortcut with set rank
        Mockito.reset(mMockUsageStatsManagerInternal);
        final ShortcutInfo s4_2 = makeShortcut("s4");
        s4_2.setRank(4);
        mManager.pushDynamicShortcut(s4_2);
        assertEquals(2, getCallerShortcut("s2").getRank());
        assertEquals(3, getCallerShortcut("s4").getRank());
        verify(mMockUsageStatsManagerInternal, times(1)).reportShortcutUsage(
                eq(CALLING_PACKAGE_1), eq("s4"), eq(USER_0));

        mInjectedCurrentTimeMillis += INTERVAL; // reset

        // Test push as last
        Mockito.reset(mMockUsageStatsManagerInternal);
        mManager.pushDynamicShortcut(s5);
        assertShortcutIds(assertAllNotKeyFieldsOnly(mManager.getDynamicShortcuts()),
                "s1", "s2", "s3", "s4", "s5");
        assertEquals(0, getCallerShortcut("s5").getRank());
        assertEquals(1, getCallerShortcut("s3").getRank());
        assertEquals(2, getCallerShortcut("s1").getRank());
        assertEquals(3, getCallerShortcut("s2").getRank());
        assertEquals(4, getCallerShortcut("s4").getRank());
        verify(mMockUsageStatsManagerInternal, times(1)).reportShortcutUsage(
                eq(CALLING_PACKAGE_1), eq("s5"), eq(USER_0));

        // Push when max has already reached
        Mockito.reset(mMockUsageStatsManagerInternal);
        mManager.pushDynamicShortcut(s6);
        assertShortcutIds(assertAllNotKeyFieldsOnly(mManager.getDynamicShortcuts()),
                "s1", "s2", "s3", "s5", "s6");
        assertEquals(0, getCallerShortcut("s6").getRank());
        assertEquals(1, getCallerShortcut("s5").getRank());
        assertEquals(4, getCallerShortcut("s2").getRank());
        verify(mMockUsageStatsManagerInternal, times(1)).reportShortcutUsage(
                eq(CALLING_PACKAGE_1), eq("s6"), eq(USER_0));

        mInjectedCurrentTimeMillis += INTERVAL; // reset

        // Push with different activity
        Mockito.reset(mMockUsageStatsManagerInternal);
        s7.setActivity(makeComponent(ShortcutActivity2.class));
        mManager.pushDynamicShortcut(s7);
        assertEquals(makeComponent(ShortcutActivity2.class),
                getCallerShortcut("s7").getActivity());
        assertEquals(0, getCallerShortcut("s7").getRank());
        verify(mMockUsageStatsManagerInternal, times(1)).reportShortcutUsage(
                eq(CALLING_PACKAGE_1), eq("s7"), eq(USER_0));

        // Push to update shortcut with different activity
        Mockito.reset(mMockUsageStatsManagerInternal);
        final ShortcutInfo s1_2 = makeShortcut("s1");
        s1_2.setActivity(makeComponent(ShortcutActivity2.class));
        s1_2.setRank(1);
        mManager.pushDynamicShortcut(s1_2);
        assertEquals(0, getCallerShortcut("s7").getRank());
        assertEquals(1, getCallerShortcut("s1").getRank());
        assertEquals(0, getCallerShortcut("s6").getRank());
        assertEquals(1, getCallerShortcut("s5").getRank());
        assertEquals(2, getCallerShortcut("s3").getRank());
        assertEquals(3, getCallerShortcut("s2").getRank());
        verify(mMockUsageStatsManagerInternal, times(1)).reportShortcutUsage(
                eq(CALLING_PACKAGE_1), eq("s1"), eq(USER_0));

        mInjectedCurrentTimeMillis += INTERVAL; // reset

        // Test push when dropped shortcut is cached
        Mockito.reset(mMockUsageStatsManagerInternal);
        s8.setLongLived();
        s8.setRank(100);
        mManager.pushDynamicShortcut(s8);
        assertEquals(4, getCallerShortcut("s8").getRank());
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mInjectCheckAccessShortcutsPermission = true;
            mLauncherApps.cacheShortcuts(CALLING_PACKAGE_1, list("s8"), HANDLE_USER_0,
                    CACHE_OWNER_0);
            verify(mMockUsageStatsManagerInternal, times(1)).reportShortcutUsage(
                    eq(CALLING_PACKAGE_1), eq("s8"), eq(USER_0));
        });

        Mockito.reset(mMockUsageStatsManagerInternal);
        mManager.pushDynamicShortcut(s9);
        assertShortcutIds(assertAllNotKeyFieldsOnly(mManager.getDynamicShortcuts()),
                "s1", "s2", "s3", "s5", "s6", "s7", "s9");
        // Verify s13 stayed as cached
        assertShortcutIds(mManager.getShortcuts(ShortcutManager.FLAG_MATCH_CACHED),
                "s8");
        verify(mMockUsageStatsManagerInternal, times(1)).reportShortcutUsage(
                eq(CALLING_PACKAGE_1), eq("s9"), eq(USER_0));
    }

    public void testPushDynamicShortcut_CallsToUsageStatsManagerAreThrottled()
            throws InterruptedException {
        mService.updateConfigurationLocked(
                ShortcutService.ConfigConstants.KEY_SAVE_DELAY_MILLIS + "=500");

        // Verify calls to UsageStatsManagerInternal#reportShortcutUsage are throttled.
        setCaller(CALLING_PACKAGE_1, USER_0);
        for (int i = 0; i < ShortcutPackage.REPORT_USAGE_BUFFER_SIZE; i++) {
            final ShortcutInfo si = makeShortcut("s" + i);
            mManager.pushDynamicShortcut(si);
        }
        verify(mMockUsageStatsManagerInternal, times(1)).reportShortcutUsage(
                eq(CALLING_PACKAGE_1), eq("s1"), eq(USER_0));
        Mockito.reset(mMockUsageStatsManagerInternal);
        for (int i = ShortcutPackage.REPORT_USAGE_BUFFER_SIZE; i <= 10; i++) {
            final ShortcutInfo si = makeShortcut("s" + i);
            mManager.pushDynamicShortcut(si);
        }
        verify(mMockUsageStatsManagerInternal, times(0)).reportShortcutUsage(
                any(), any(), anyInt());

        // Verify pkg2 isn't blocked by pkg1, but consecutive calls from pkg2 are throttled as well.
        setCaller(CALLING_PACKAGE_2, USER_0);
        for (int i = 0; i < ShortcutPackage.REPORT_USAGE_BUFFER_SIZE; i++) {
            final ShortcutInfo si = makeShortcut("s" + i);
            mManager.pushDynamicShortcut(si);
        }
        verify(mMockUsageStatsManagerInternal, times(1)).reportShortcutUsage(
                eq(CALLING_PACKAGE_2), eq("s1"), eq(USER_0));
        Mockito.reset(mMockUsageStatsManagerInternal);
        for (int i = ShortcutPackage.REPORT_USAGE_BUFFER_SIZE; i <= 10; i++) {
            final ShortcutInfo si = makeShortcut("s" + i);
            mManager.pushDynamicShortcut(si);
        }
        verify(mMockUsageStatsManagerInternal, times(0)).reportShortcutUsage(
                any(), any(), anyInt());

        Mockito.reset(mMockUsageStatsManagerInternal);
        // Let time passes which resets the throttle
        Thread.sleep(505);
        // Verify UsageStatsManagerInternal#reportShortcutUsed can be called again
        setCaller(CALLING_PACKAGE_1, USER_0);
        mManager.pushDynamicShortcut(makeShortcut("s10"));
        setCaller(CALLING_PACKAGE_2, USER_0);
        mManager.pushDynamicShortcut(makeShortcut("s10"));
        verify(mMockUsageStatsManagerInternal, times(1)).reportShortcutUsage(
                eq(CALLING_PACKAGE_1), any(), eq(USER_0));
        verify(mMockUsageStatsManagerInternal, times(1)).reportShortcutUsage(
                eq(CALLING_PACKAGE_2), any(), eq(USER_0));
    }

    public void testUnlimitedCalls() {
        setCaller(CALLING_PACKAGE_1, USER_0);

        final ShortcutInfo si1 = makeShortcut("shortcut1");

        assertEquals(3, mManager.getRemainingCallCount());

        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(2, mManager.getRemainingCallCount());

        assertTrue(mManager.addDynamicShortcuts(list(si1)));
        assertEquals(1, mManager.getRemainingCallCount());

        assertTrue(mManager.updateShortcuts(list(si1)));
        assertEquals(0, mManager.getRemainingCallCount());

        // Unlimited now.
        mInjectHasUnlimitedShortcutsApiCallsPermission = true;

        assertEquals(3, mManager.getRemainingCallCount());

        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(3, mManager.getRemainingCallCount());

        assertTrue(mManager.addDynamicShortcuts(list(si1)));
        assertEquals(3, mManager.getRemainingCallCount());

        assertTrue(mManager.updateShortcuts(list(si1)));
        assertEquals(3, mManager.getRemainingCallCount());
    }

    public void testPublishWithNoActivity() {
        // If activity is not explicitly set, use the default one.

        mRunningUsers.put(USER_10, true);

        runWithCaller(CALLING_PACKAGE_2, USER_10, () -> {
            // s1 and s3 has no activities.
            final ShortcutInfo si1 = new ShortcutInfo.Builder(mClientContext, "si1")
                    .setShortLabel("label1")
                    .setIntent(new Intent("action1"))
                    .build();
            final ShortcutInfo si2 = new ShortcutInfo.Builder(mClientContext, "si2")
                    .setShortLabel("label2")
                    .setActivity(new ComponentName(getCallingPackage(), "abc"))
                    .setIntent(new Intent("action2"))
                    .build();
            final ShortcutInfo si3 = new ShortcutInfo.Builder(mClientContext, "si3")
                    .setShortLabel("label3")
                    .setIntent(new Intent("action3"))
                    .build();

            // Set test 1
            assertTrue(mManager.setDynamicShortcuts(list(si1)));

            assertWith(getCallerShortcuts())
                    .haveIds("si1")
                    .forShortcutWithId("si1", si -> {
                        assertEquals(new ComponentName(getCallingPackage(),
                                MAIN_ACTIVITY_CLASS), si.getActivity());
                    });

            // Set test 2
            assertTrue(mManager.setDynamicShortcuts(list(si2, si1)));

            assertWith(getCallerShortcuts())
                    .haveIds("si1", "si2")
                    .forShortcutWithId("si1", si -> {
                        assertEquals(new ComponentName(getCallingPackage(),
                                MAIN_ACTIVITY_CLASS), si.getActivity());
                    })
                    .forShortcutWithId("si2", si -> {
                        assertEquals(new ComponentName(getCallingPackage(),
                                "abc"), si.getActivity());
                    });


            // Set test 3
            assertTrue(mManager.setDynamicShortcuts(list(si3, si1)));

            assertWith(getCallerShortcuts())
                    .haveIds("si1", "si3")
                    .forShortcutWithId("si1", si -> {
                        assertEquals(new ComponentName(getCallingPackage(),
                                MAIN_ACTIVITY_CLASS), si.getActivity());
                    })
                    .forShortcutWithId("si3", si -> {
                        assertEquals(new ComponentName(getCallingPackage(),
                                MAIN_ACTIVITY_CLASS), si.getActivity());
                    });

            mInjectedCurrentTimeMillis += INTERVAL; // reset throttling

            // Add test 1
            mManager.removeAllDynamicShortcuts();
            assertTrue(mManager.addDynamicShortcuts(list(si1)));

            assertWith(getCallerShortcuts())
                    .haveIds("si1")
                    .forShortcutWithId("si1", si -> {
                        assertEquals(new ComponentName(getCallingPackage(),
                                MAIN_ACTIVITY_CLASS), si.getActivity());
                    });

            // Add test 2
            mManager.removeAllDynamicShortcuts();
            assertTrue(mManager.addDynamicShortcuts(list(si2, si1)));

            assertWith(getCallerShortcuts())
                    .haveIds("si1", "si2")
                    .forShortcutWithId("si1", si -> {
                        assertEquals(new ComponentName(getCallingPackage(),
                                MAIN_ACTIVITY_CLASS), si.getActivity());
                    })
                    .forShortcutWithId("si2", si -> {
                        assertEquals(new ComponentName(getCallingPackage(),
                                "abc"), si.getActivity());
                    });


            // Add test 3
            mManager.removeAllDynamicShortcuts();
            assertTrue(mManager.addDynamicShortcuts(list(si3, si1)));

            assertWith(getCallerShortcuts())
                    .haveIds("si1", "si3")
                    .forShortcutWithId("si1", si -> {
                        assertEquals(new ComponentName(getCallingPackage(),
                                MAIN_ACTIVITY_CLASS), si.getActivity());
                    })
                    .forShortcutWithId("si3", si -> {
                        assertEquals(new ComponentName(getCallingPackage(),
                                MAIN_ACTIVITY_CLASS), si.getActivity());
                    });
        });
    }

    public void testPublishWithNoActivity_noMainActivityInPackage() {
        mRunningUsers.put(USER_10, true);

        runWithCaller(CALLING_PACKAGE_2, USER_10, () -> {
            final ShortcutInfo si1 = new ShortcutInfo.Builder(mClientContext, "si1")
                    .setShortLabel("label1")
                    .setIntent(new Intent("action1"))
                    .build();

            // Returning null means there's no main activity in this package.
            mMainActivityFetcher = (packageName, userId) -> null;

            assertExpectException(
                    RuntimeException.class, "Launcher activity not found for", () -> {
                        assertTrue(mManager.setDynamicShortcuts(list(si1)));
                    });
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
        final Icon bmp64x64_maskable = Icon.createWithAdaptiveBitmap(BitmapFactory.decodeResource(
                getTestContext().getResources(), R.drawable.black_64x64));
        final Icon bmp512x512 = Icon.createWithBitmap(BitmapFactory.decodeResource(
                getTestContext().getResources(), R.drawable.black_512x512));

        // The corresponding files will be deleted in tearDown()
        final Icon uri32x32 = Icon.createWithContentUri(
                getFileUriFromResource("file32x32.jpg", R.drawable.black_32x32));
        final Icon uri64x64_maskable = Icon.createWithAdaptiveBitmapContentUri(
                getFileUriFromResource("file64x64.jpg", R.drawable.black_64x64));
        final Icon uri512x512 = Icon.createWithContentUri(
                getFileUriFromResource("file512x512.jpg", R.drawable.black_512x512));

        doReturn(mUriPermissionOwner.getExternalToken())
                .when(mMockUriGrantsManagerInternal).newUriPermissionOwner(anyString());

        // Set from package 1
        setCaller(CALLING_PACKAGE_1);
        assertTrue(mManager.setDynamicShortcuts(list(
                makeShortcutWithIcon("res32x32", res32x32),
                makeShortcutWithIcon("res64x64", res64x64),
                makeShortcutWithIcon("bmp32x32", bmp32x32),
                makeShortcutWithIcon("bmp64x64", bmp64x64_maskable),
                makeShortcutWithIcon("bmp512x512", bmp512x512),
                makeShortcutWithIcon("uri32x32", uri32x32),
                makeShortcutWithIcon("uri64x64", uri64x64_maskable),
                makeShortcutWithIcon("uri512x512", uri512x512),
                makeShortcut("none")
        )));

        // getDynamicShortcuts() shouldn't return icons, thus assertAllNotHaveIcon().
        assertShortcutIds(assertAllNotHaveIcon(mManager.getDynamicShortcuts()),
                "res32x32",
                "res64x64",
                "bmp32x32",
                "bmp64x64",
                "bmp512x512",
                "uri32x32",
                "uri64x64",
                "uri512x512",
                "none");

        // Call from another caller with the same ID, just to make sure storage is per-package.
        setCaller(CALLING_PACKAGE_2);
        assertTrue(mManager.setDynamicShortcuts(list(
                makeShortcutWithIcon("res32x32", res512x512),
                makeShortcutWithIcon("res64x64", res512x512),
                makeShortcutWithIcon("uri32x32", uri512x512),
                makeShortcutWithIcon("uri64x64", uri512x512),
                makeShortcutWithIcon("none", res512x512)
        )));
        assertShortcutIds(assertAllNotHaveIcon(mManager.getDynamicShortcuts()),
                "res32x32",
                "res64x64",
                "uri32x32",
                "uri64x64",
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

        assertShortcutIds(assertAllHaveIconUri(
                list(getShortcutInfoAsLauncher(CALLING_PACKAGE_1, "uri32x32", USER_0))),
                "uri32x32");

        assertShortcutIds(assertAllHaveIconUri(
                list(getShortcutInfoAsLauncher(CALLING_PACKAGE_1, "uri64x64", USER_0))),
                "uri64x64");

        assertShortcutIds(assertAllHaveIconUri(
                list(getShortcutInfoAsLauncher(CALLING_PACKAGE_1, "uri512x512", USER_0))),
                "uri512x512");

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
/*
        bmp = pfdToBitmap(mLauncherApps.getUriShortcutIconFd(
                getShortcutInfoAsLauncher(CALLING_PACKAGE_1, "uri32x32", USER_0)));
        assertBitmapSize(32, 32, bmp);

        bmp = pfdToBitmap(mLauncherApps.getUriShortcutIconFd(
                getShortcutInfoAsLauncher(CALLING_PACKAGE_1, "uri64x64", USER_0)));
        assertBitmapSize(64, 64, bmp);

        bmp = pfdToBitmap(mLauncherApps.getUriShortcutIconFd(
                getShortcutInfoAsLauncher(CALLING_PACKAGE_1, "uri512x512", USER_0)));
        assertBitmapSize(512, 512, bmp);
*/

        Drawable dr_bmp = mLauncherApps.getShortcutIconDrawable(
                makeShortcutWithIcon("bmp64x64", bmp64x64_maskable), 0);
        assertTrue(dr_bmp instanceof AdaptiveIconDrawable);
        float viewportPercentage = 1 / (1 + 2 * AdaptiveIconDrawable.getExtraInsetFraction());
        assertEquals((int) (bmp64x64_maskable.getBitmap().getWidth() * viewportPercentage),
                dr_bmp.getIntrinsicWidth());
        assertEquals((int) (bmp64x64_maskable.getBitmap().getHeight() * viewportPercentage),
                dr_bmp.getIntrinsicHeight());
/*
        Drawable dr_uri = mLauncherApps.getShortcutIconDrawable(
                makeShortcutWithIcon("uri64x64", uri64x64_maskable), 0);
        assertTrue(dr_uri instanceof AdaptiveIconDrawable);
        assertEquals((int) (bmp64x64_maskable.getBitmap().getWidth() * viewportPercentage),
                dr_uri.getIntrinsicWidth());
        assertEquals((int) (bmp64x64_maskable.getBitmap().getHeight() * viewportPercentage),
                dr_uri.getIntrinsicHeight());
*/
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
        mRunningUsers.put(USER_10, true);

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

        mService.waitForBitmapSavesForTest();
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

        mService.waitForBitmapSavesForTest();
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

        mService.waitForBitmapSavesForTest();
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
            assertEquals("string/r" + R.drawable.black_32x32, s.getIconResName());
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

        mRunningUsers.put(USER_11, true);

        runWithCaller(CALLING_PACKAGE_2, USER_11, () -> {
            mManager.updateShortcuts(list());
        });
    }

    public void testUpdateShortcuts_icons() {
        runWithCaller(CALLING_PACKAGE_1, UserHandle.USER_SYSTEM, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1")
            )));

            // Set uri icon
            assertTrue(mManager.updateShortcuts(list(
                    new ShortcutInfo.Builder(mClientContext, "s1")
                            .setIcon(Icon.createWithContentUri("test_uri"))
                            .build()
            )));
            mService.waitForBitmapSavesForTest();
            assertWith(getCallerShortcuts())
                    .forShortcutWithId("s1", si -> {
                        assertTrue(si.hasIconUri());
                        assertEquals("test_uri", si.getIconUri());
                    });
            // Set resource icon
            assertTrue(mManager.updateShortcuts(list(
                    new ShortcutInfo.Builder(mClientContext, "s1")
                            .setIcon(Icon.createWithResource(getTestContext(), R.drawable.black_32x32))
                            .build()
            )));
            mService.waitForBitmapSavesForTest();
            assertWith(getCallerShortcuts())
                    .forShortcutWithId("s1", si -> {
                        assertTrue(si.hasIconResource());
                        assertEquals(R.drawable.black_32x32, si.getIconResourceId());
                    });
            mService.waitForBitmapSavesForTest();

            mInjectedCurrentTimeMillis += INTERVAL; // reset throttling

            // Set bitmap icon
            assertTrue(mManager.updateShortcuts(list(
                    new ShortcutInfo.Builder(mClientContext, "s1")
                            .setIcon(Icon.createWithBitmap(BitmapFactory.decodeResource(
                                    getTestContext().getResources(), R.drawable.black_64x64)))
                            .build()
            )));
            mService.waitForBitmapSavesForTest();
            assertWith(getCallerShortcuts())
                    .forShortcutWithId("s1", si -> {
                        assertTrue(si.hasIconFile());
                    });

            // Do it again, with the reverse order (bitmap -> resource -> uri)
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1")
            )));

            // Set bitmap icon
            assertTrue(mManager.updateShortcuts(list(
                    new ShortcutInfo.Builder(mClientContext, "s1")
                            .setIcon(Icon.createWithBitmap(BitmapFactory.decodeResource(
                                    getTestContext().getResources(), R.drawable.black_64x64)))
                            .build()
            )));
            mService.waitForBitmapSavesForTest();
            assertWith(getCallerShortcuts())
                    .forShortcutWithId("s1", si -> {
                        assertTrue(si.hasIconFile());
                    });

            mInjectedCurrentTimeMillis += INTERVAL; // reset throttling

            // Set resource icon
            assertTrue(mManager.updateShortcuts(list(
                    new ShortcutInfo.Builder(mClientContext, "s1")
                            .setIcon(Icon.createWithResource(getTestContext(), R.drawable.black_32x32))
                            .build()
            )));
            mService.waitForBitmapSavesForTest();
            assertWith(getCallerShortcuts())
                    .forShortcutWithId("s1", si -> {
                        assertTrue(si.hasIconResource());
                        assertEquals(R.drawable.black_32x32, si.getIconResourceId());
                    });
            // Set uri icon
            assertTrue(mManager.updateShortcuts(list(
                    new ShortcutInfo.Builder(mClientContext, "s1")
                            .setIcon(Icon.createWithContentUri("test_uri"))
                            .build()
            )));
            mService.waitForBitmapSavesForTest();
            assertWith(getCallerShortcuts())
                    .forShortcutWithId("s1", si -> {
                        assertTrue(si.hasIconUri());
                        assertEquals("test_uri", si.getIconUri());
                    });
        });
    }

    public void testShortcutManagerGetShortcuts_shortcutTypes() {

        // Create 3 manifest and 3 dynamic shortcuts
        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                R.xml.shortcut_3);
        updatePackageVersion(CALLING_PACKAGE_1, 1);
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageAddIntent(CALLING_PACKAGE_1, USER_0));

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeLongLivedShortcut("s1"), makeLongLivedShortcut("s2"), makeShortcut("s3"))));
        });

        // Pin 2 and 3
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("ms2", "ms3", "s2", "s3"),
                    HANDLE_USER_0);
        });

        // Cache 1 and 2
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mInjectCheckAccessShortcutsPermission = true;
            mLauncherApps.cacheShortcuts(CALLING_PACKAGE_1, list("s1"),
                    HANDLE_USER_0, CACHE_OWNER_0);
            mLauncherApps.cacheShortcuts(CALLING_PACKAGE_1, list("s2"),
                    HANDLE_USER_0, CACHE_OWNER_1);
        });

        setCaller(CALLING_PACKAGE_1);

        // Get manifest shortcuts
        assertShortcutIds(mManager.getShortcuts(ShortcutManager.FLAG_MATCH_MANIFEST),
                "ms1", "ms2", "ms3");

        // Get dynamic shortcuts
        assertShortcutIds(mManager.getShortcuts(ShortcutManager.FLAG_MATCH_DYNAMIC),
                "s1", "s2", "s3");

        // Get pinned shortcuts
        assertShortcutIds(mManager.getShortcuts(ShortcutManager.FLAG_MATCH_PINNED),
                "ms2", "ms3", "s2", "s3");

        // Get cached shortcuts
        assertShortcutIds(mManager.getShortcuts(ShortcutManager.FLAG_MATCH_CACHED),
                "s1", "s2");

        // Get manifest and dynamic shortcuts
        assertShortcutIds(mManager.getShortcuts(
                ShortcutManager.FLAG_MATCH_MANIFEST | ShortcutManager.FLAG_MATCH_DYNAMIC),
                "ms1", "ms2", "ms3", "s1", "s2", "s3");

        // Get manifest and pinned shortcuts
        assertShortcutIds(mManager.getShortcuts(
                ShortcutManager.FLAG_MATCH_MANIFEST | ShortcutManager.FLAG_MATCH_PINNED),
                "ms1", "ms2", "ms3", "s2", "s3");

        // Get manifest and cached shortcuts
        assertShortcutIds(mManager.getShortcuts(
                ShortcutManager.FLAG_MATCH_MANIFEST | ShortcutManager.FLAG_MATCH_CACHED),
                "ms1", "ms2", "ms3", "s1", "s2");

        // Get dynamic and pinned shortcuts
        assertShortcutIds(mManager.getShortcuts(
                ShortcutManager.FLAG_MATCH_DYNAMIC | ShortcutManager.FLAG_MATCH_PINNED),
                "ms2", "ms3", "s1", "s2", "s3");

        // Get dynamic and cached shortcuts
        assertShortcutIds(mManager.getShortcuts(
                ShortcutManager.FLAG_MATCH_DYNAMIC | ShortcutManager.FLAG_MATCH_CACHED),
                "s1", "s2", "s3");

        // Get pinned and cached shortcuts
        assertShortcutIds(mManager.getShortcuts(
                ShortcutManager.FLAG_MATCH_PINNED | ShortcutManager.FLAG_MATCH_CACHED),
                "ms2", "ms3", "s1", "s2", "s3");

        // Remove a dynamic cached shortcut
        mManager.removeDynamicShortcuts(list("s1"));
        assertShortcutIds(mManager.getShortcuts(ShortcutManager.FLAG_MATCH_DYNAMIC), "s2", "s3");
        assertShortcutIds(mManager.getShortcuts(ShortcutManager.FLAG_MATCH_CACHED), "s1", "s2");

        // Remove a dynamic cached and pinned shortcut
        mManager.removeDynamicShortcuts(list("s2"));
        assertShortcutIds(mManager.getShortcuts(ShortcutManager.FLAG_MATCH_DYNAMIC), "s3");
        assertShortcutIds(mManager.getShortcuts(ShortcutManager.FLAG_MATCH_PINNED),
                "ms2", "ms3", "s2", "s3");
        assertShortcutIds(mManager.getShortcuts(ShortcutManager.FLAG_MATCH_CACHED), "s1", "s2");
    }

    public void testCachedShortcuts() {
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(makeShortcut("s1"),
                    makeLongLivedShortcut("s2"), makeLongLivedShortcut("s3"),
                    makeLongLivedShortcut("s4"), makeLongLivedShortcut("s5"),
                    makeLongLivedShortcut("s6"))));
        });

        // Pin s2
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("s2"),
                    HANDLE_USER_0);
        });

        // Cache some, but non long lived shortcuts will be ignored.
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mInjectCheckAccessShortcutsPermission = true;
            mLauncherApps.cacheShortcuts(CALLING_PACKAGE_1, list("s1", "s2"),
                    HANDLE_USER_0, CACHE_OWNER_0);
            mLauncherApps.cacheShortcuts(CALLING_PACKAGE_1, list("s2", "s4", "s5"),
                    HANDLE_USER_0, CACHE_OWNER_1);
            mLauncherApps.cacheShortcuts(CALLING_PACKAGE_1, list("s5", "s6"),
                    HANDLE_USER_0, CACHE_OWNER_2);
        });

        setCaller(CALLING_PACKAGE_1);

        // Get dynamic shortcuts
        assertShortcutIds(mManager.getShortcuts(ShortcutManager.FLAG_MATCH_DYNAMIC),
                "s1", "s2", "s3", "s4", "s5", "s6");
        // Get pinned shortcuts
        assertShortcutIds(mManager.getShortcuts(ShortcutManager.FLAG_MATCH_PINNED),
                "s2");
        // Get cached shortcuts
        assertShortcutIds(mManager.getShortcuts(ShortcutManager.FLAG_MATCH_CACHED),
                "s2", "s4", "s5", "s6");

        // Remove a dynamic cached shortcut
        mManager.removeDynamicShortcuts(list("s4", "s5"));
        assertShortcutIds(mManager.getShortcuts(ShortcutManager.FLAG_MATCH_DYNAMIC),
                "s1", "s2", "s3", "s6");
        assertShortcutIds(mManager.getShortcuts(ShortcutManager.FLAG_MATCH_CACHED),
                "s2", "s4", "s5", "s6");

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.uncacheShortcuts(CALLING_PACKAGE_1, list("s2", "s4"),
                    HANDLE_USER_0, CACHE_OWNER_0);
        });
        // s2 still cached by owner1. s4 wasn't cached by owner0 so didn't get removed.
        assertShortcutIds(mManager.getShortcuts(ShortcutManager.FLAG_MATCH_CACHED),
                "s2", "s4", "s5", "s6");

        // uncache a non-dynamic shortcut. Should be removed.
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.uncacheShortcuts(CALLING_PACKAGE_1, list("s4"),
                    HANDLE_USER_0, CACHE_OWNER_1);
        });

        // uncache s6 by its only owner. s5 still cached by owner1
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.uncacheShortcuts(CALLING_PACKAGE_1, list("s5", "s6"),
                    HANDLE_USER_0, CACHE_OWNER_2);
        });
        assertShortcutIds(mManager.getShortcuts(ShortcutManager.FLAG_MATCH_CACHED),
                "s2", "s5");

        // Cache another shortcut
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.cacheShortcuts(CALLING_PACKAGE_1, list("s3"),
                    HANDLE_USER_0, CACHE_OWNER_0);
        });
        assertShortcutIds(mManager.getShortcuts(ShortcutManager.FLAG_MATCH_CACHED),
                "s2", "s3", "s5");

        // Remove a dynamic cached pinned long lived shortcut
        mManager.removeLongLivedShortcuts(list("s2"));
        assertShortcutIds(mManager.getShortcuts(ShortcutManager.FLAG_MATCH_DYNAMIC),
                "s1", "s3", "s6");
        assertShortcutIds(mManager.getShortcuts(ShortcutManager.FLAG_MATCH_CACHED),
                "s3", "s5");
        assertShortcutIds(mManager.getShortcuts(ShortcutManager.FLAG_MATCH_PINNED),
                "s2");
    }

    public void testCachedShortcuts_accessShortcutsPermission() {
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(makeShortcut("s1"),
                    makeLongLivedShortcut("s2"), makeLongLivedShortcut("s3"),
                    makeLongLivedShortcut("s4"))));
        });

        // s1 is not long lived and will be ignored.
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mInjectCheckAccessShortcutsPermission = false;
            assertExpectException(
                    SecurityException.class, "Caller can't access shortcut information", () -> {
                        mLauncherApps.cacheShortcuts(CALLING_PACKAGE_1, list("s1", "s2", "s3"),
                                HANDLE_USER_0, CACHE_OWNER_0);
                    });
            // Give ACCESS_SHORTCUTS permission to LAUNCHER_1
            mInjectCheckAccessShortcutsPermission = true;
            mLauncherApps.cacheShortcuts(CALLING_PACKAGE_1, list("s1", "s2", "s3"),
                    HANDLE_USER_0, CACHE_OWNER_0);
        });

        setCaller(CALLING_PACKAGE_1);

        // Get cached shortcuts
        assertShortcutIds(mManager.getShortcuts(ShortcutManager.FLAG_MATCH_CACHED), "s2", "s3");

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mInjectCheckAccessShortcutsPermission = false;
            assertExpectException(
                    SecurityException.class, "Caller can't access shortcut information", () -> {
                        mLauncherApps.uncacheShortcuts(CALLING_PACKAGE_1, list("s2", "s4"),
                                HANDLE_USER_0, CACHE_OWNER_0);
                    });
            // Give ACCESS_SHORTCUTS permission to LAUNCHER_1
            mInjectCheckAccessShortcutsPermission = true;
            mLauncherApps.uncacheShortcuts(CALLING_PACKAGE_1, list("s2", "s4"),
                    HANDLE_USER_0, CACHE_OWNER_0);
        });

        assertShortcutIds(mManager.getShortcuts(ShortcutManager.FLAG_MATCH_CACHED), "s3");
    }

    public void testCachedShortcuts_canPassShortcutLimit() {
        // Change the max number of shortcuts.
        mService.updateConfigurationLocked(ConfigConstants.KEY_MAX_SHORTCUTS + "=4");

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(makeLongLivedShortcut("s1"),
                    makeLongLivedShortcut("s2"), makeLongLivedShortcut("s3"),
                    makeLongLivedShortcut("s4"))));
        });

        // Cache All
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mInjectCheckAccessShortcutsPermission = true;
            mLauncherApps.cacheShortcuts(CALLING_PACKAGE_1, list("s1", "s2", "s3", "s4"),
                    HANDLE_USER_0, CACHE_OWNER_0);
        });

        setCaller(CALLING_PACKAGE_1);

        // Get dynamic shortcuts
        assertShortcutIds(mManager.getShortcuts(ShortcutManager.FLAG_MATCH_DYNAMIC),
                "s1", "s2", "s3", "s4");
        // Get cached shortcuts
        assertShortcutIds(mManager.getShortcuts(ShortcutManager.FLAG_MATCH_CACHED),
                "s1", "s2", "s3", "s4");

        assertTrue(mManager.setDynamicShortcuts(makeShortcuts("sx1", "sx2", "sx3", "sx4")));

        // Get dynamic shortcuts
        assertShortcutIds(mManager.getShortcuts(ShortcutManager.FLAG_MATCH_DYNAMIC),
                "sx1", "sx2", "sx3", "sx4");
        // Get cached shortcuts
        assertShortcutIds(mManager.getShortcuts(ShortcutManager.FLAG_MATCH_CACHED),
                "s1", "s2", "s3", "s4");
    }

    // === Test for launcher side APIs ===

    public void testGetShortcuts() {

        // Set up shortcuts.

        setCaller(CALLING_PACKAGE_1);
        final ShortcutInfo s1_1 = makeLongLivedShortcut("s1");
        final ShortcutInfo s1_2 = makeShortcutWithLocusId("s2", makeLocusId("l1"));

        assertTrue(mManager.setDynamicShortcuts(list(s1_1, s1_2)));

        // Because setDynamicShortcuts will update the timestamps when ranks are changing,
        // we explicitly set timestamps here.
        updateCallerShortcut("s1", si -> si.setTimestamp(5000));
        updateCallerShortcut("s2", si -> si.setTimestamp(1000));

        setCaller(CALLING_PACKAGE_2);
        final ShortcutInfo s2_2 = makeShortcut("s2");
        final ShortcutInfo s2_3 = makeShortcutWithActivity("s3",
                makeComponent(ShortcutActivity2.class));
        final ShortcutInfo s2_4 = makeShortcutWithActivity("s4",
                makeComponent(ShortcutActivity.class));
        assertTrue(mManager.setDynamicShortcuts(list(s2_2, s2_3, s2_4)));

        updateCallerShortcut("s2", si -> si.setTimestamp(1500));
        updateCallerShortcut("s3", si -> si.setTimestamp(3000));
        updateCallerShortcut("s4", si -> si.setTimestamp(500));

        setCaller(CALLING_PACKAGE_3);
        final ShortcutInfo s3_2 = makeShortcutWithLocusId("s3", makeLocusId("l2"));
        s3_2.setLongLived();

        assertTrue(mManager.setDynamicShortcuts(list(s3_2)));

        updateCallerShortcut("s3", si -> si.setTimestamp(START_TIME + 5000));

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
                        /* time =*/ 1000, CALLING_PACKAGE_2, list("s3"), /* locusIds =*/ null,
                        /* activity =*/ null,
                        ShortcutQuery.FLAG_GET_DYNAMIC | ShortcutQuery.FLAG_GET_KEY_FIELDS_ONLY),
                        getCallingUser())),
                "s3"))));
        assertAllDynamic(assertAllNotHaveTitle(assertAllNotHaveIntents(assertShortcutIds(
                assertAllKeyFieldsOnly(mLauncherApps.getShortcuts(buildQuery(
                        /* time =*/ 1000, CALLING_PACKAGE_2, list("s3", "s2", "ss"),
                        /* locusIds =*/ null, /* activity =*/ null,
                        ShortcutQuery.FLAG_GET_DYNAMIC | ShortcutQuery.FLAG_GET_KEY_FIELDS_ONLY),
                        getCallingUser())),
                "s2", "s3"))));
        assertAllDynamic(assertAllNotHaveTitle(assertAllNotHaveIntents(assertShortcutIds(
                assertAllKeyFieldsOnly(mLauncherApps.getShortcuts(buildQuery(
                        /* time =*/ 1000, CALLING_PACKAGE_2, list("s3x", "s2x"),
                        /* locusIds =*/ null, /* activity =*/ null,
                        ShortcutQuery.FLAG_GET_DYNAMIC | ShortcutQuery.FLAG_GET_KEY_FIELDS_ONLY),
                        getCallingUser()))
                /* empty */))));
        assertAllDynamic(assertAllNotHaveTitle(assertAllNotHaveIntents(assertShortcutIds(
                assertAllKeyFieldsOnly(mLauncherApps.getShortcuts(buildQuery(
                        /* time =*/ 1000, CALLING_PACKAGE_2, list(), /* locusIds =*/ null,
                        /* activity =*/ null,
                        ShortcutQuery.FLAG_GET_DYNAMIC | ShortcutQuery.FLAG_GET_KEY_FIELDS_ONLY),
                        getCallingUser()))
                /* empty */))));

        // With locus ID.
        assertAllDynamic(assertAllNotHaveTitle(assertAllNotHaveIntents(assertShortcutIds(
                assertAllKeyFieldsOnly(mLauncherApps.getShortcuts(buildQuery(
                        /* time =*/ 1000, CALLING_PACKAGE_3, /* shortcutIds =*/ null,
                        list(makeLocusId("l2")), /* activity =*/ null,
                        ShortcutQuery.FLAG_GET_DYNAMIC | ShortcutQuery.FLAG_GET_KEY_FIELDS_ONLY),
                        getCallingUser())),
                "s3"))));
        assertAllDynamic(assertAllNotHaveTitle(assertAllNotHaveIntents(assertShortcutIds(
                assertAllKeyFieldsOnly(mLauncherApps.getShortcuts(buildQuery(
                        /* time =*/ 1000, CALLING_PACKAGE_1, /* shortcutIds =*/ null,
                        list(makeLocusId("l1"), makeLocusId("l2"), makeLocusId("l3")),
                        /* activity =*/ null,
                        ShortcutQuery.FLAG_GET_DYNAMIC | ShortcutQuery.FLAG_GET_KEY_FIELDS_ONLY),
                        getCallingUser())),
                "s2"))));
        assertAllDynamic(assertAllNotHaveTitle(assertAllNotHaveIntents(assertShortcutIds(
                assertAllKeyFieldsOnly(mLauncherApps.getShortcuts(buildQuery(
                        /* time =*/ 1000, CALLING_PACKAGE_1, /* shortcutIds =*/ null,
                        list(makeLocusId("lx1"), makeLocusId("lx2")), /* activity =*/ null,
                        ShortcutQuery.FLAG_GET_DYNAMIC | ShortcutQuery.FLAG_GET_KEY_FIELDS_ONLY),
                        getCallingUser()))
                /* empty */))));
        assertAllDynamic(assertAllNotHaveTitle(assertAllNotHaveIntents(assertShortcutIds(
                assertAllKeyFieldsOnly(mLauncherApps.getShortcuts(buildQuery(
                        /* time =*/ 1000, CALLING_PACKAGE_3, /* shortcutIds =*/ null, list(),
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
                    /* time =*/ 0, /* package= */ null, list("id"), /* locusIds =*/ null,
                    /* activity =*/ null, /* flags */ 0), getCallingUser());
                });

        // TODO More tests: pinned but dynamic.

        setCaller(LAUNCHER_1);

        // Cache some shortcuts. Only long lived shortcuts can get cached.
        mInjectCheckAccessShortcutsPermission = true;
        mLauncherApps.cacheShortcuts(CALLING_PACKAGE_1, list("s1"), getCallingUser(),
                CACHE_OWNER_0);
        mLauncherApps.cacheShortcuts(CALLING_PACKAGE_3, list("s3"), getCallingUser(),
                CACHE_OWNER_0);

        // Cached ones only
        assertShortcutIds(assertAllNotKeyFieldsOnly(
                mLauncherApps.getShortcuts(buildQuery(
                        /* time =*/ 0, CALLING_PACKAGE_3,
                        /* activity =*/ null,
                        ShortcutQuery.FLAG_MATCH_CACHED),
                        getCallingUser())),
                "s3");

        // All packages.
        assertShortcutIds(assertAllNotKeyFieldsOnly(
                mLauncherApps.getShortcuts(buildQuery(
                        /* time =*/ 0, /* package= */ null,
                        /* activity =*/ null,
                        ShortcutQuery.FLAG_MATCH_CACHED),
                        getCallingUser())),
                "s1", "s3");

        assertExpectException(
                IllegalArgumentException.class, "package name must also be set", () -> {
                    mLauncherApps.getShortcuts(buildQuery(
                            /* time =*/ 0, /* package= */ null, list("id"), /* locusIds= */ null,
                            /* activity =*/ null, /* flags */ 0), getCallingUser());
                });

        // Change Launcher. Cached shortcuts are the same for all launchers.
        setCaller(LAUNCHER_2);
        // All packages.
        assertShortcutIds(assertAllNotKeyFieldsOnly(
                mLauncherApps.getShortcuts(buildQuery(
                        /* time =*/ 0, /* package= */ null,
                        /* activity =*/ null,
                        ShortcutQuery.FLAG_MATCH_CACHED),
                        getCallingUser())),
                "s1", "s3");
    }

    public void testGetShortcuts_shortcutKinds() throws Exception {
        // Create 3 manifest and 3 dynamic shortcuts
        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                R.xml.shortcut_3);
        updatePackageVersion(CALLING_PACKAGE_1, 1);
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageAddIntent(CALLING_PACKAGE_1, USER_0));

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"))));
        });

        // Pin 2 and 3
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("ms2", "ms3", "s2", "s3"),
                    HANDLE_USER_0);
        });

        // Remove ms3 and s3
        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                R.xml.shortcut_2);
        updatePackageVersion(CALLING_PACKAGE_1, 1);
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageAddIntent(CALLING_PACKAGE_1, USER_0));

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"))));
        });

        // Check their status.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertWith(getCallerShortcuts())
                    .haveIds("ms1", "ms2", "ms3", "s1", "s2", "s3")

                    .selectByIds("ms1", "ms2")
                    .areAllManifest()
                    .areAllImmutable()
                    .areAllNotDynamic()

                    .revertToOriginalList()
                    .selectByIds("ms3")
                    .areAllNotManifest()
                    .areAllImmutable()
                    .areAllDisabled()
                    .areAllNotDynamic()

                    .revertToOriginalList()
                    .selectByIds("s1", "s2")
                    .areAllNotManifest()
                    .areAllMutable()
                    .areAllDynamic()

                    .revertToOriginalList()
                    .selectByIds("s3")
                    .areAllNotManifest()
                    .areAllMutable()
                    .areAllEnabled()
                    .areAllNotDynamic()

                    .revertToOriginalList()
                    .selectByIds("s1", "ms1")
                    .areAllNotPinned()

                    .revertToOriginalList()
                    .selectByIds("s2", "s3", "ms2", "ms3")
                    .areAllPinned()
            ;
        });

        // Finally, actual tests.
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertWith(mLauncherApps.getShortcuts(
                    buildQueryWithFlags(ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0))
                    .haveIds("s1", "s2");
            assertWith(mLauncherApps.getShortcuts(
                    buildQueryWithFlags(ShortcutQuery.FLAG_GET_MANIFEST), HANDLE_USER_0))
                    .haveIds("ms1", "ms2");
            assertWith(mLauncherApps.getShortcuts(
                    buildQueryWithFlags(ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0))
                    .haveIds("s2", "s3", "ms2", "ms3");

            assertWith(mLauncherApps.getShortcuts(
                    buildQueryWithFlags(
                            ShortcutQuery.FLAG_GET_DYNAMIC | ShortcutQuery.FLAG_GET_PINNED
                    ), HANDLE_USER_0))
                    .haveIds("s1", "s2", "s3", "ms2", "ms3");

            assertWith(mLauncherApps.getShortcuts(
                    buildQueryWithFlags(
                            ShortcutQuery.FLAG_GET_MANIFEST | ShortcutQuery.FLAG_GET_PINNED
                    ), HANDLE_USER_0))
                    .haveIds("ms1", "s2", "s3", "ms2", "ms3");

            assertWith(mLauncherApps.getShortcuts(
                    buildQueryWithFlags(
                            ShortcutQuery.FLAG_GET_DYNAMIC | ShortcutQuery.FLAG_GET_MANIFEST
                    ), HANDLE_USER_0))
                    .haveIds("ms1", "ms2", "s1", "s2");

            assertWith(mLauncherApps.getShortcuts(
                    buildQueryWithFlags(
                            ShortcutQuery.FLAG_GET_ALL_KINDS
                    ), HANDLE_USER_0))
                    .haveIds("ms1", "ms2", "ms3", "s1", "s2", "s3");
        });
    }

    public void testGetShortcuts_resolveStrings() throws Exception {
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
            assertEquals("string-com.android.test.1-user:0-res:10/en", ret.get(0).getTitle());
            assertEquals("string-com.android.test.1-user:0-res:11/en", ret.get(0).getText());
            assertEquals("string-com.android.test.1-user:0-res:12/en",
                    ret.get(0).getDisabledMessage());

            // USER P0
            ret = assertShortcutIds(
                    assertAllStringsResolved(mLauncherApps.getShortcuts(q, HANDLE_USER_P0)),
                    "id");
            assertEquals("string-com.android.test.1-user:20-res:10/en", ret.get(0).getTitle());
            assertEquals("string-com.android.test.1-user:20-res:11/en", ret.get(0).getText());
            assertEquals("string-com.android.test.1-user:20-res:12/en",
                    ret.get(0).getDisabledMessage());
        });
    }

    public void testGetShortcuts_personsFlag() {
        ShortcutInfo s = new ShortcutInfo.Builder(mClientContext, "id")
                .setShortLabel("label")
                .setActivity(new ComponentName(mClientContext, ShortcutActivity2.class))
                .setPerson(makePerson("person", "personKey", "personUri"))
                .setIntent(makeIntent("action", ShortcutActivity.class, "key", "val"))
                .build();

        setCaller(CALLING_PACKAGE_1);
        assertTrue(mManager.setDynamicShortcuts(list(s)));

        setCaller(LAUNCHER_1);

        assertNull(mLauncherApps.getShortcuts(buildQuery(
                /* time =*/ 0, CALLING_PACKAGE_1, /* activity =*/ null,
                ShortcutQuery.FLAG_MATCH_DYNAMIC | ShortcutQuery.FLAG_GET_KEY_FIELDS_ONLY),
                getCallingUser()).get(0).getPersons());

        assertNull(mLauncherApps.getShortcuts(buildQuery(
                /* time =*/ 0, CALLING_PACKAGE_1, /* activity =*/ null,
                ShortcutQuery.FLAG_MATCH_DYNAMIC),
                getCallingUser()).get(0).getPersons());

        // Using FLAG_GET_PERSONS_DATA should fail without permission
        mInjectCheckAccessShortcutsPermission = false;
        assertExpectException(
                SecurityException.class, "Caller can't access shortcut information", () -> {
                    mLauncherApps.getShortcuts(buildQuery(
                            /* time =*/ 0, CALLING_PACKAGE_1, /* activity =*/ null,
                            ShortcutQuery.FLAG_MATCH_DYNAMIC
                                    | ShortcutQuery.FLAG_GET_PERSONS_DATA),
                            getCallingUser());
                });

        mInjectCheckAccessShortcutsPermission = true;
        assertEquals("person", mLauncherApps.getShortcuts(buildQuery(
                /* time =*/ 0, CALLING_PACKAGE_1, /* activity =*/ null,
                ShortcutQuery.FLAG_MATCH_DYNAMIC | ShortcutQuery.FLAG_GET_PERSONS_DATA),
                getCallingUser()).get(0).getPersons()[0].getName());

        assertNull(mLauncherApps.getShortcuts(buildQuery(
                /* time =*/ 0, CALLING_PACKAGE_1, /* activity =*/ null,
                ShortcutQuery.FLAG_MATCH_DYNAMIC | ShortcutQuery.FLAG_GET_PERSONS_DATA
                        | ShortcutQuery.FLAG_GET_KEY_FIELDS_ONLY),
                getCallingUser()).get(0).getPersons());
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

            mManager.updateShortcuts(list(
                    new ShortcutInfo.Builder(mClientContext, "s2").setDisabledMessage("xyz")
                            .build()));

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
            // CALLING_PACKAGE_1 deleted s2, but it's pinned, so it still exists, and disabled.
            assertWith(mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser()))
                    .haveIds("s2")
                    .areAllWithDisabledReason(ShortcutInfo.DISABLED_REASON_BY_APP)
                    .forAllShortcuts(si -> {
                        assertEquals("xyz", si.getDisabledMessage());
                    })
                    .areAllPinned()
                    .areAllNotWithKeyFieldsOnly()
                    .areAllDisabled();
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s2", USER_0,
                    ActivityNotFoundException.class);

            // Here, s4 is still enabled and launchable, but s3 is disabled.
            assertWith(mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser()))
                    .haveIds("s3", "s4")
                    .areAllPinned()
                    .areAllNotWithKeyFieldsOnly()

                    .selectByIds("s3")
                    .areAllDisabled()

                    .revertToOriginalList()
                    .selectByIds("s4")
                    .areAllEnabled();

            assertStartShortcutThrowsException(CALLING_PACKAGE_2, "s3", USER_0,
                    ActivityNotFoundException.class);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s4", USER_0);

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
            assertShortcutLaunchable(CALLING_PACKAGE_1, "s2", USER_0);
        });
    }

    public void testDisableShortcuts_thenRepublish() {
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"))));

            runWithCaller(LAUNCHER_1, USER_0, () -> {
                mLauncherApps.pinShortcuts(
                        CALLING_PACKAGE_1, list("s1", "s2", "s3"), HANDLE_USER_0);
            });

            mManager.disableShortcuts(list("s1", "s2", "s3"));

            assertWith(getCallerShortcuts())
                    .haveIds("s1", "s2", "s3")
                    .areAllNotDynamic()
                    .areAllPinned()
                    .areAllDisabled();

            // Make sure updateShortcuts() will not re-enable them.
            assertTrue(mManager.updateShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"))));

            assertWith(getCallerShortcuts())
                    .haveIds("s1", "s2", "s3")
                    .areAllNotDynamic()
                    .areAllPinned()
                    .areAllDisabled();

            // Re-publish s1 with setDynamicShortcuts.
            mInjectedCurrentTimeMillis += INTERVAL; // reset throttling

            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"))));

            assertWith(getCallerShortcuts())
                    .haveIds("s1", "s2", "s3")

                    .selectByIds("s1")
                    .areAllDynamic()
                    .areAllPinned()
                    .areAllEnabled()

                    .revertToOriginalList()
                    .selectByIds("s2", "s3")
                    .areAllNotDynamic()
                    .areAllPinned()
                    .areAllDisabled();

            // Re-publish s2 with addDynamicShortcuts.
            mInjectedCurrentTimeMillis += INTERVAL; // reset throttling

            assertTrue(mManager.addDynamicShortcuts(list(
                    makeShortcut("s2"))));

            assertWith(getCallerShortcuts())
                    .haveIds("s1", "s2", "s3")

                    .selectByIds("s1", "s2")
                    .areAllDynamic()
                    .areAllPinned()
                    .areAllEnabled()

                    .revertToOriginalList()
                    .selectByIds("s3")
                    .areAllNotDynamic()
                    .areAllPinned()
                    .areAllDisabled();
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

        dumpsysOnLogcat("Before launcher 2");

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

            // Make sure FLAG_MATCH_ALL_PINNED will be ignored.
            assertWith(mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_MATCH_PINNED
                            | ShortcutQuery.FLAG_MATCH_PINNED_BY_ANY_LAUNCHER), getCallingUser()))
                    .isEmpty();

            // Make sure the special permission works.
            mInjectCheckAccessShortcutsPermission = true;

            dumpsysOnLogcat("All-pinned");

            assertWith(mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_MATCH_PINNED
                            | ShortcutQuery.FLAG_MATCH_PINNED_BY_ANY_LAUNCHER), getCallingUser()))
                    .haveIds("s1", "s2");
            assertWith(mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_MATCH_PINNED), getCallingUser()))
                    .isEmpty();

            assertShortcutLaunchable(CALLING_PACKAGE_2, "s1", getCallingUser().getIdentifier());

            mInjectCheckAccessShortcutsPermission = false;

            assertShortcutNotLaunched(CALLING_PACKAGE_2, "s1", getCallingUser().getIdentifier());

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

    public void testPinShortcutAndGetPinnedShortcuts_assistant() {
        // Create some shortcuts.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"))));
        });

        // Pin some.
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                    list("s3", "s4"), getCallingUser());
        });

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"))));
        });

        runWithCaller(LAUNCHER_2, USER_0, () -> {
            final ShortcutQuery allPinned = new ShortcutQuery().setQueryFlags(
                    ShortcutQuery.FLAG_MATCH_PINNED_BY_ANY_LAUNCHER);

            assertWith(mLauncherApps.getShortcuts(allPinned, HANDLE_USER_0))
                    .isEmpty();

            assertShortcutLaunchable(CALLING_PACKAGE_1, "s1", USER_0);
            assertShortcutNotLaunched(CALLING_PACKAGE_1, "s3", USER_0);
            assertShortcutNotLaunched(CALLING_PACKAGE_1, "s4", USER_0);

            // Make it the assistant app.
            mInternal.setShortcutHostPackage("assistant", LAUNCHER_2, USER_0);
            assertWith(mLauncherApps.getShortcuts(allPinned, HANDLE_USER_0))
                    .haveIds("s3");

            assertShortcutLaunchable(CALLING_PACKAGE_1, "s1", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_1, "s3", USER_0);
            assertShortcutNotLaunched(CALLING_PACKAGE_1, "s4", USER_0);

            mInternal.setShortcutHostPackage("another-type", LAUNCHER_3, USER_0);
            assertWith(mLauncherApps.getShortcuts(allPinned, HANDLE_USER_0))
                    .haveIds("s3");

            mInternal.setShortcutHostPackage("assistant", null, USER_0);
            assertWith(mLauncherApps.getShortcuts(allPinned, HANDLE_USER_0))
                    .isEmpty();

            mInternal.setShortcutHostPackage("assistant", LAUNCHER_2, USER_0);
            assertWith(mLauncherApps.getShortcuts(allPinned, HANDLE_USER_0))
                    .haveIds("s3");

            mInternal.setShortcutHostPackage("assistant", LAUNCHER_1, USER_0);
            assertWith(mLauncherApps.getShortcuts(allPinned, HANDLE_USER_0))
                    .isEmpty();
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

        mRunningUsers.put(USER_10, true);

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

        // First, make sure managed profile can't see other profiles.
        runWithCaller(LAUNCHER_1, USER_P1, () -> {

            final ShortcutQuery q = new ShortcutQuery().setQueryFlags(
                    ShortcutQuery.FLAG_MATCH_DYNAMIC | ShortcutQuery.FLAG_MATCH_PINNED
                            | ShortcutQuery.FLAG_MATCH_MANIFEST);

            // No shortcuts are visible.
            assertWith(mLauncherApps.getShortcuts(q, HANDLE_USER_0)).isEmpty();

            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("s1"), HANDLE_USER_0);

            // Should have no effects.
            assertWith(mLauncherApps.getShortcuts(q, HANDLE_USER_0)).isEmpty();

            assertShortcutNotLaunched(CALLING_PACKAGE_1, "s1", USER_0);
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

            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s1", USER_10,
                    SecurityException.class);
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s2", USER_10,
                    SecurityException.class);
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s3", USER_10,
                    SecurityException.class);
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s4", USER_10,
                    SecurityException.class);
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s5", USER_10,
                    SecurityException.class);
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s6", USER_10,
                    SecurityException.class);
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

            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s1", USER_10,
                    SecurityException.class);
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s2", USER_10,
                    SecurityException.class);
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s3", USER_10,
                    SecurityException.class);
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s4", USER_10,
                    SecurityException.class);
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s5", USER_10,
                    SecurityException.class);
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s6", USER_10,
                    SecurityException.class);
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

            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s1", USER_10,
                    SecurityException.class);
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s2", USER_10,
                    SecurityException.class);
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s3", USER_10,
                    SecurityException.class);
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s4", USER_10,
                    SecurityException.class);
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s5", USER_10,
                    SecurityException.class);
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s6", USER_10,
                    SecurityException.class);
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
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s2", USER_0,
                    ActivityNotFoundException.class);
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s3", USER_0,
                    ActivityNotFoundException.class);

            assertShortcutLaunchable(CALLING_PACKAGE_2, "s1", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s2", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s3", USER_0);

            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s1", USER_10,
                    SecurityException.class);
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s2", USER_10,
                    SecurityException.class);
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s3", USER_10,
                    SecurityException.class);
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s4", USER_10,
                    SecurityException.class);
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s5", USER_10,
                    SecurityException.class);
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s6", USER_10,
                    SecurityException.class);
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
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s3", USER_0,
                    ActivityNotFoundException.class);

            assertShortcutLaunchable(CALLING_PACKAGE_2, "s1", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s2", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s3", USER_0);

            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s1", USER_10,
                    SecurityException.class);
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s2", USER_10,
                    SecurityException.class);
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s3", USER_10,
                    SecurityException.class);
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s4", USER_10,
                    SecurityException.class);
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s5", USER_10,
                    SecurityException.class);
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s6", USER_10,
                    SecurityException.class);
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
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s2", USER_0,
                    ActivityNotFoundException.class);
            assertShortcutLaunchable(CALLING_PACKAGE_1, "s3", USER_0);

            assertShortcutLaunchable(CALLING_PACKAGE_2, "s1", USER_0);
            assertStartShortcutThrowsException(CALLING_PACKAGE_2, "s2", USER_0,
                    ActivityNotFoundException.class);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s3", USER_0);

            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s1", USER_10,
                    SecurityException.class);
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s2", USER_10,
                    SecurityException.class);
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s3", USER_10,
                    SecurityException.class);
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s4", USER_10,
                    SecurityException.class);
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s5", USER_10,
                    SecurityException.class);
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s6", USER_10,
                    SecurityException.class);
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

            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s1", USER_0,
                    SecurityException.class);
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s2", USER_0,
                    SecurityException.class);
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s3", USER_0,
                    SecurityException.class);

            assertStartShortcutThrowsException(CALLING_PACKAGE_2, "s1", USER_0,
                    SecurityException.class);
            assertStartShortcutThrowsException(CALLING_PACKAGE_2, "s2", USER_0,
                    SecurityException.class);
            assertStartShortcutThrowsException(CALLING_PACKAGE_2, "s3", USER_0,
                    SecurityException.class);

            assertShortcutLaunchable(CALLING_PACKAGE_1, "s1", USER_10);
            assertShortcutLaunchable(CALLING_PACKAGE_1, "s2", USER_10);
            assertShortcutLaunchable(CALLING_PACKAGE_1, "s3", USER_10);
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s4", USER_10,
                    ActivityNotFoundException.class);
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s5", USER_10,
                    ActivityNotFoundException.class);
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s6", USER_10,
                    ActivityNotFoundException.class);
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
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s2", USER_0,
                    ActivityNotFoundException.class);
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s3", USER_0,
                    ActivityNotFoundException.class);

            assertShortcutLaunchable(CALLING_PACKAGE_2, "s1", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s2", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s3", USER_0);

            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s1", USER_10,
                    SecurityException.class);
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s2", USER_10,
                    SecurityException.class);
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s3", USER_10,
                    SecurityException.class);
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s4", USER_10,
                    SecurityException.class);
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s5", USER_10,
                    SecurityException.class);
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s6", USER_10,
                    SecurityException.class);
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
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s3", USER_0,
                    ActivityNotFoundException.class);

            assertShortcutLaunchable(CALLING_PACKAGE_2, "s1", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s2", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s3", USER_0);

            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s1", USER_10,
                    SecurityException.class);
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s2", USER_10,
                    SecurityException.class);
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s3", USER_10,
                    SecurityException.class);
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s4", USER_10,
                    SecurityException.class);
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s5", USER_10,
                    SecurityException.class);
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s6", USER_10,
                    SecurityException.class);
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
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s2", USER_0,
                    ActivityNotFoundException.class);
            assertShortcutLaunchable(CALLING_PACKAGE_1, "s3", USER_0);

            assertShortcutLaunchable(CALLING_PACKAGE_2, "s1", USER_0);
            assertStartShortcutThrowsException(CALLING_PACKAGE_2, "s2", USER_0,
                    ActivityNotFoundException.class);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s3", USER_0);

            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s1", USER_10,
                    SecurityException.class);
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s2", USER_10,
                    SecurityException.class);
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s3", USER_10,
                    SecurityException.class);
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s4", USER_10,
                    SecurityException.class);
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s5", USER_10,
                    SecurityException.class);
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s6", USER_10,
                    SecurityException.class);
        });
    }

    public void testStartShortcut() {
        // Create some shortcuts.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            final ShortcutInfo s1_1 = makeShortcut(
                    "s1",
                    "Title 1",
                    makeComponent(ShortcutActivity.class),
                    /* icon =*/ null,
                    new Intent[]{makeIntent(Intent.ACTION_ASSIST, ShortcutActivity2.class,
                            "key1", "val1", "nest", makeBundle("key", 123))
                            .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK),
                            new Intent("act2").setFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)},
                    /* rank */ 10);

            final ShortcutInfo s1_2 = makeShortcut(
                    "s2",
                    "Title 2",
            /* activity */ null,
            /* icon =*/ null,
                    makeIntent(Intent.ACTION_ASSIST, ShortcutActivity3.class),
            /* rank */ 12);

            final ShortcutInfo s1_3 = makeShortcut("s3");

            assertTrue(mManager.setDynamicShortcuts(list(s1_1, s1_2, s1_3)));
        });

        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            final ShortcutInfo s2_1 = makeShortcut(
                    "s1",
                    "ABC",
                    makeComponent(ShortcutActivity.class),
                    /* icon =*/ null,
                    makeIntent(Intent.ACTION_ANSWER, ShortcutActivity.class,
                            "key1", "val1", "nest", makeBundle("key", 123)),
                    /* weight */ 10);
            assertTrue(mManager.setDynamicShortcuts(list(s2_1)));
        });

        // Pin some.
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                    list("s1", "s2"), getCallingUser());

            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2,
                    list("s1"), getCallingUser());
        });

        // Just to make it complicated, delete some.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            mManager.removeDynamicShortcuts(list("s2"));
        });

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            final Intent[] intents = launchShortcutAndGetIntents(CALLING_PACKAGE_1, "s1", USER_0);
            assertEquals(ShortcutActivity2.class.getName(),
                    intents[0].getComponent().getClassName());
            assertEquals(Intent.ACTION_ASSIST,
                    intents[0].getAction());
            assertEquals(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK,
                    intents[0].getFlags());

            assertEquals("act2",
                    intents[1].getAction());
            assertEquals(Intent.FLAG_ACTIVITY_NO_ANIMATION,
                    intents[1].getFlags());

            assertEquals(
                    ShortcutActivity3.class.getName(),
                    launchShortcutAndGetIntent(CALLING_PACKAGE_1, "s2", USER_0)
                            .getComponent().getClassName());
            assertEquals(
                    ShortcutActivity.class.getName(),
                    launchShortcutAndGetIntent(CALLING_PACKAGE_2, "s1", USER_0)
                            .getComponent().getClassName());

            assertShortcutLaunchable(CALLING_PACKAGE_1, "s3", USER_0);

            assertShortcutNotLaunched("no-such-package", "s2", USER_0);
            assertShortcutNotLaunched(CALLING_PACKAGE_1, "xxxx", USER_0);
        });

        // LAUNCHER_1 is no longer the default launcher
        setDefaultLauncherChecker((pkg, userId) -> false);

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            // Not the default launcher, but pinned shortcuts are still lauchable.
            final Intent[] intents = launchShortcutAndGetIntents(CALLING_PACKAGE_1, "s1", USER_0);
            assertEquals(ShortcutActivity2.class.getName(),
                    intents[0].getComponent().getClassName());
            assertEquals(Intent.ACTION_ASSIST,
                    intents[0].getAction());
            assertEquals(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK,
                    intents[0].getFlags());

            assertEquals("act2",
                    intents[1].getAction());
            assertEquals(Intent.FLAG_ACTIVITY_NO_ANIMATION,
                    intents[1].getFlags());
            assertEquals(
                    ShortcutActivity3.class.getName(),
                    launchShortcutAndGetIntent(CALLING_PACKAGE_1, "s2", USER_0)
                            .getComponent().getClassName());
            assertEquals(
                    ShortcutActivity.class.getName(),
                    launchShortcutAndGetIntent(CALLING_PACKAGE_2, "s1", USER_0)
                            .getComponent().getClassName());

            // Not pinned, so not lauchable.
        });

        // Test inner errors.
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            // Not launchable.
            doReturn(ActivityManager.START_CLASS_NOT_FOUND)
                    .when(mMockActivityTaskManagerInternal).startActivitiesAsPackage(
                    anyStringOrNull(), anyStringOrNull(), anyInt(),
                    anyOrNull(Intent[].class), anyOrNull(Bundle.class));
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s1", USER_0,
                    ActivityNotFoundException.class);

            // Still not launchable.
            doReturn(ActivityManager.START_CLASS_NOT_FOUND)
                    .when(mMockActivityTaskManagerInternal)
                    .startActivitiesAsPackage(
                            anyStringOrNull(), anyStringOrNull(), anyInt(),
                            anyOrNull(Intent[].class), anyOrNull(Bundle.class));
            assertStartShortcutThrowsException(CALLING_PACKAGE_1, "s1", USER_0,
                    ActivityNotFoundException.class);
        });


        // TODO Check extra, etc
    }

    public void testLauncherCallback() throws Throwable {
        // Disable throttling for this test.
        mService.updateConfigurationLocked(
                ConfigConstants.KEY_MAX_UPDATES_PER_INTERVAL + "=99999999,"
                        + ConfigConstants.KEY_MAX_SHORTCUTS + "=99999999"
        );

        setCaller(LAUNCHER_1, USER_0);

        assertForLauncherCallback(mLauncherApps, () -> {
            runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
                assertTrue(mManager.setDynamicShortcuts(list(
                        makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"))));
            });
        }).assertCallbackCalledForPackageAndUser(CALLING_PACKAGE_1, HANDLE_USER_0)
                .haveIds("s1", "s2", "s3")
                .areAllWithKeyFieldsOnly()
                .areAllDynamic();

        // From different package.
        assertForLauncherCallback(mLauncherApps, () -> {
            runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
                assertTrue(mManager.setDynamicShortcuts(list(
                        makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"))));
            });
        }).assertCallbackCalledForPackageAndUser(CALLING_PACKAGE_2, HANDLE_USER_0)
                .haveIds("s1", "s2", "s3")
                .areAllWithKeyFieldsOnly()
                .areAllDynamic();

        mRunningUsers.put(USER_10, true);

        // Different user, callback shouldn't be called.
        assertForLauncherCallback(mLauncherApps, () -> {
            runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
                assertTrue(mManager.setDynamicShortcuts(list(
                        makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"))));
            });
        }).assertNoCallbackCalled();


        // Test for addDynamicShortcuts.
        assertForLauncherCallback(mLauncherApps, () -> {
            runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
                assertTrue(mManager.addDynamicShortcuts(list(makeShortcut("s4"))));
            });
        }).assertCallbackCalledForPackageAndUser(CALLING_PACKAGE_1, HANDLE_USER_0)
                .haveIds("s1", "s2", "s3", "s4")
                .areAllWithKeyFieldsOnly()
                .areAllDynamic();

        // Test for remove
        assertForLauncherCallback(mLauncherApps, () -> {
            runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
                mManager.removeDynamicShortcuts(list("s1"));
            });
        }).assertCallbackCalledForPackageAndUser(CALLING_PACKAGE_1, HANDLE_USER_0)
                .haveIds("s2", "s3", "s4")
                .areAllWithKeyFieldsOnly()
                .areAllDynamic();

        // Test for update
        assertForLauncherCallback(mLauncherApps, () -> {
            runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
                assertTrue(mManager.updateShortcuts(list(
                        makeShortcut("s1"), makeShortcut("s2"))));
            });
        }).assertCallbackCalledForPackageAndUser(CALLING_PACKAGE_1, HANDLE_USER_0)
                // All remaining shortcuts will be passed regardless of what's been updated.
                .haveIds("s2", "s3", "s4")
                .areAllWithKeyFieldsOnly()
                .areAllDynamic();

        // Test for deleteAll
        assertForLauncherCallback(mLauncherApps, () -> {
            runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
                mManager.removeAllDynamicShortcuts();
            });
        }).assertCallbackCalledForPackageAndUser(CALLING_PACKAGE_1, HANDLE_USER_0)
                .isEmpty();

        // Update package1 with manifest shortcuts
        assertForLauncherCallback(mLauncherApps, () -> {
            addManifestShortcutResource(
                    new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                    R.xml.shortcut_2);
            updatePackageVersion(CALLING_PACKAGE_1, 1);
            mService.mPackageMonitor.onReceive(getTestContext(),
                    genPackageAddIntent(CALLING_PACKAGE_1, USER_0));
        }).assertCallbackCalledForPackageAndUser(CALLING_PACKAGE_1, HANDLE_USER_0)
                .areAllManifest()
                .areAllWithKeyFieldsOnly()
                .haveIds("ms1", "ms2");

        // Make sure pinned shortcuts are passed too.
        // 1. Add dynamic shortcuts.
        runWithCaller(CALLING_PACKAGE_1, UserHandle.USER_SYSTEM, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"))));
        });

        // 2. Pin some.
        runWithCaller(LAUNCHER_1, UserHandle.USER_SYSTEM, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("ms2", "s2"), HANDLE_USER_0);
        });
        runWithCaller(CALLING_PACKAGE_1, UserHandle.USER_SYSTEM, () -> {
            assertWith(getCallerShortcuts())
                    .haveIds("ms1", "ms2", "s1", "s2")
                    .areAllEnabled()

                    .selectByIds("ms1", "ms2")
                    .areAllManifest()

                    .revertToOriginalList()
                    .selectByIds("s1", "s2")
                    .areAllDynamic()
            ;
        });

        // 3 Update the app with no manifest shortcuts.  (Pinned one will survive.)
        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                R.xml.shortcut_0);
        updatePackageVersion(CALLING_PACKAGE_1, 1);
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageAddIntent(CALLING_PACKAGE_1, USER_0));

        assertForLauncherCallback(mLauncherApps, () -> {
            runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
                mManager.removeDynamicShortcuts(list("s2"));

                assertWith(getCallerShortcuts())
                        .haveIds("ms2", "s1", "s2")

                        .selectByIds("ms2")
                        .areAllNotManifest()
                        .areAllPinned()
                        .areAllImmutable()
                        .areAllDisabled()

                        .revertToOriginalList()
                        .selectByIds("s1")
                        .areAllDynamic()
                        .areAllNotPinned()
                        .areAllEnabled()

                        .revertToOriginalList()
                        .selectByIds("s2")
                        .areAllNotDynamic()
                        .areAllPinned()
                        .areAllEnabled()
                ;
            });
        }).assertCallbackCalledForPackageAndUser(CALLING_PACKAGE_1, HANDLE_USER_0)
                .haveIds("ms2", "s1", "s2")
                .areAllWithKeyFieldsOnly();

        // Remove CALLING_PACKAGE_2
        assertForLauncherCallback(mLauncherApps, () -> {
            uninstallPackage(USER_0, CALLING_PACKAGE_2);
            mService.cleanUpPackageLocked(CALLING_PACKAGE_2, USER_0, USER_0,
                    /* appStillExists = */ false);
        }).assertCallbackCalledForPackageAndUser(CALLING_PACKAGE_2, HANDLE_USER_0)
                .isEmpty();
    }

    public void testLauncherCallback_crossProfile() throws Throwable {
        prepareCrossProfileDataSet();

        final Handler h = new Handler(Looper.getMainLooper());

        final LauncherApps.Callback c0_1 = mock(LauncherApps.Callback.class);
        final LauncherApps.Callback c0_2 = mock(LauncherApps.Callback.class);
        final LauncherApps.Callback c0_3 = mock(LauncherApps.Callback.class);
        final LauncherApps.Callback c0_4 = mock(LauncherApps.Callback.class);

        final LauncherApps.Callback cP0_1 = mock(LauncherApps.Callback.class);
        final LauncherApps.Callback cP1_1 = mock(LauncherApps.Callback.class);
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
                case USER_P1:
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
        runWithCaller(LAUNCHER_1, USER_P1, () -> mLauncherApps.registerCallback(cP1_1, h));
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
        assertCallbackNotReceived(cP1_1);

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
        assertCallbackNotReceived(cP1_1);

        // Work profile.
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
        assertCallbackNotReceived(cP1_1);

        // Normal secondary user.
        mRunningUsers.put(USER_10, true);

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
        assertCallbackNotReceived(cP1_1);
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

        mRunningUsers.put(USER_10, true);

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

        // Try stopping the user
        mService.handleStopUser(USER_10);

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

        mRunningUsers.put(USER_10, true);

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


        final SparseArray<ShortcutUser> users = mService.getShortcutsForTest();
        assertEquals(2, users.size());
        assertEquals(USER_0, users.keyAt(0));
        assertEquals(USER_10, users.keyAt(1));

        final ShortcutUser user0 = users.get(USER_0);
        final ShortcutUser user10 = users.get(USER_10);


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
        mService.cleanUpPackageLocked("abc", USER_0, USER_0, /* appStillExists = */ false);

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
        mService.cleanUpPackageLocked(CALLING_PACKAGE_1, USER_0, USER_0,
                /* appStillExists = */ false);

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
        mService.cleanUpPackageLocked(LAUNCHER_1, USER_10, USER_10, /* appStillExists = */ false);

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
        mService.cleanUpPackageLocked(CALLING_PACKAGE_2, USER_10, USER_10,
                /* appStillExists = */ false);

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
        mService.cleanUpPackageLocked(LAUNCHER_2, USER_10, USER_10,
                /* appStillExists = */ false);

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
        mService.cleanUpPackageLocked(CALLING_PACKAGE_1, USER_10, USER_10,
                /* appStillExists = */ false);

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

    public void testCleanupPackage_republishManifests() {
        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                R.xml.shortcut_2);
        updatePackageVersion(CALLING_PACKAGE_1, 1);
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageAddIntent(CALLING_PACKAGE_1, USER_0));

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"))));
        });
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                    list("s2", "s3", "ms1", "ms2"), HANDLE_USER_0);
        });

        // Remove ms2 from manifest.
        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                R.xml.shortcut_1);
        updatePackageVersion(CALLING_PACKAGE_1, 1);
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageAddIntent(CALLING_PACKAGE_1, USER_0));

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"))));

            // Make sure the shortcuts are in the intended state.
            assertWith(getCallerShortcuts())
                    .haveIds("ms1", "ms2", "s1", "s2", "s3")

                    .selectByIds("ms1")
                    .areAllManifest()
                    .areAllPinned()

                    .revertToOriginalList()
                    .selectByIds("ms2")
                    .areAllNotManifest()
                    .areAllPinned()

                    .revertToOriginalList()
                    .selectByIds("s1")
                    .areAllDynamic()
                    .areAllNotPinned()

                    .revertToOriginalList()
                    .selectByIds("s2")
                    .areAllDynamic()
                    .areAllPinned()

                    .revertToOriginalList()
                    .selectByIds("s3")
                    .areAllNotDynamic()
                    .areAllPinned();
        });

        // Clean up + re-publish manifests.
        mService.cleanUpPackageLocked(CALLING_PACKAGE_1, USER_0, USER_0,
                /* appStillExists = */ true);
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertWith(getCallerShortcuts())
                    .haveIds("ms1")
                    .areAllManifest();
        });
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

        mRunningUsers.put(USER_10, true);

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

    protected void checkCanRestoreTo(int expected, ShortcutPackageInfo spi,
            boolean anyVersionOk, int version, boolean nowBackupAllowed, String... signatures) {
        final PackageInfo pi = genPackage("dummy", /* uid */ 0, version, signatures);
        if (!nowBackupAllowed) {
            pi.applicationInfo.flags &= ~ApplicationInfo.FLAG_ALLOW_BACKUP;
        }

        doReturn(expected != DISABLED_REASON_SIGNATURE_MISMATCH).when(
                mMockPackageManagerInternal).isDataRestoreSafe(any(byte[].class), anyString());

        assertEquals(expected, spi.canRestoreTo(mService, pi, anyVersionOk));
    }

    public void testCanRestoreTo() {
        addPackage(CALLING_PACKAGE_1, CALLING_UID_1, 10, "sig1");
        addPackage(CALLING_PACKAGE_2, CALLING_UID_2, 10, "sig1", "sig2");
        addPackage(CALLING_PACKAGE_3, CALLING_UID_3, 10, "sig1");

        updatePackageInfo(CALLING_PACKAGE_3,
                pi -> pi.applicationInfo.flags &= ~ApplicationInfo.FLAG_ALLOW_BACKUP);

        final ShortcutPackageInfo spi1 = ShortcutPackageInfo.generateForInstalledPackageForTest(
                mService, CALLING_PACKAGE_1, USER_0);
        final ShortcutPackageInfo spi2 = ShortcutPackageInfo.generateForInstalledPackageForTest(
                mService, CALLING_PACKAGE_2, USER_0);
        final ShortcutPackageInfo spi3 = ShortcutPackageInfo.generateForInstalledPackageForTest(
                mService, CALLING_PACKAGE_3, USER_0);

        checkCanRestoreTo(DISABLED_REASON_NOT_DISABLED, spi1, false, 10, true, "sig1");
        checkCanRestoreTo(DISABLED_REASON_NOT_DISABLED, spi1, false, 10, true, "x", "sig1");
        checkCanRestoreTo(DISABLED_REASON_NOT_DISABLED, spi1, false, 10, true, "sig1", "y");
        checkCanRestoreTo(DISABLED_REASON_NOT_DISABLED, spi1, false, 10, true, "x", "sig1", "y");
        checkCanRestoreTo(DISABLED_REASON_NOT_DISABLED, spi1, false, 11, true, "sig1");

        checkCanRestoreTo(DISABLED_REASON_SIGNATURE_MISMATCH, spi1, false, 10, true/* empty */);
        checkCanRestoreTo(DISABLED_REASON_SIGNATURE_MISMATCH, spi1, false, 10, true, "x");
        checkCanRestoreTo(DISABLED_REASON_SIGNATURE_MISMATCH, spi1, false, 10, true, "x", "y");
        checkCanRestoreTo(DISABLED_REASON_SIGNATURE_MISMATCH, spi1, false, 10, true, "x");
        checkCanRestoreTo(DISABLED_REASON_VERSION_LOWER, spi1, false, 9, true, "sig1");

        // Any version okay.
        checkCanRestoreTo(DISABLED_REASON_NOT_DISABLED, spi1, true, 9, true, "sig1");
        checkCanRestoreTo(DISABLED_REASON_NOT_DISABLED, spi1, true, 9, true, "sig1");

        checkCanRestoreTo(DISABLED_REASON_NOT_DISABLED, spi2, false, 10, true, "sig1", "sig2");
        checkCanRestoreTo(DISABLED_REASON_NOT_DISABLED, spi2, false, 10, true, "sig2", "sig1");
        checkCanRestoreTo(DISABLED_REASON_NOT_DISABLED, spi2, false, 10, true, "x", "sig1", "sig2");
        checkCanRestoreTo(DISABLED_REASON_NOT_DISABLED, spi2, false, 10, true, "x", "sig2", "sig1");
        checkCanRestoreTo(DISABLED_REASON_NOT_DISABLED, spi2, false, 10, true, "sig1", "sig2", "y");
        checkCanRestoreTo(DISABLED_REASON_NOT_DISABLED, spi2, false, 10, true, "sig2", "sig1", "y");
        checkCanRestoreTo(DISABLED_REASON_NOT_DISABLED, spi2, false, 10, true, "x", "sig1", "sig2", "y");
        checkCanRestoreTo(DISABLED_REASON_NOT_DISABLED, spi2, false, 10, true, "x", "sig2", "sig1", "y");
        checkCanRestoreTo(DISABLED_REASON_NOT_DISABLED, spi2, false, 11, true, "x", "sig2", "sig1", "y");

        checkCanRestoreTo(DISABLED_REASON_SIGNATURE_MISMATCH,
                spi2, false, 10, true, "sig1", "sig2x");
        checkCanRestoreTo(DISABLED_REASON_SIGNATURE_MISMATCH,
                spi2, false, 10, true, "sig2", "sig1x");
        checkCanRestoreTo(DISABLED_REASON_SIGNATURE_MISMATCH,
                spi2, false, 10, true, "x", "sig1x", "sig2");
        checkCanRestoreTo(DISABLED_REASON_SIGNATURE_MISMATCH,
                spi2, false, 10, true, "x", "sig2x", "sig1");
        checkCanRestoreTo(DISABLED_REASON_SIGNATURE_MISMATCH,
                spi2, false, 10, true, "sig1", "sig2x", "y");
        checkCanRestoreTo(DISABLED_REASON_SIGNATURE_MISMATCH,
                spi2, false, 10, true, "sig2", "sig1x", "y");
        checkCanRestoreTo(DISABLED_REASON_SIGNATURE_MISMATCH,
                spi2, false, 10, true, "x", "sig1x", "sig2", "y");
        checkCanRestoreTo(DISABLED_REASON_SIGNATURE_MISMATCH,
                spi2, false, 10, true, "x", "sig2x", "sig1", "y");
        checkCanRestoreTo(DISABLED_REASON_SIGNATURE_MISMATCH,
                spi2, false, 11, true, "x", "sig2x", "sig1", "y");

        checkCanRestoreTo(DISABLED_REASON_BACKUP_NOT_SUPPORTED, spi1, true, 10, false, "sig1");
        checkCanRestoreTo(DISABLED_REASON_BACKUP_NOT_SUPPORTED, spi3, true, 10, true, "sig1");
    }

    public void testHandlePackageDelete() {
        checkHandlePackageDeleteInner((userId, packageName) -> {
            uninstallPackage(userId, packageName);
            mService.mPackageMonitor.onReceive(getTestContext(),
                    genPackageDeleteIntent(packageName, userId));
        });
    }

    public void testHandlePackageDisable() {
        checkHandlePackageDeleteInner((userId, packageName) -> {
            disablePackage(userId, packageName);
            mService.mPackageMonitor.onReceive(getTestContext(),
                    genPackageChangedIntent(packageName, userId));
        });
    }

    private void checkHandlePackageDeleteInner(BiConsumer<Integer, String> remover) {
        final Icon bmp32x32 = Icon.createWithBitmap(BitmapFactory.decodeResource(
                getTestContext().getResources(), R.drawable.black_32x32));
        setCaller(CALLING_PACKAGE_1, USER_0);
        assertTrue(mManager.addDynamicShortcuts(list(
                makeShortcutWithIcon("s1", bmp32x32), makeShortcutWithIcon("s2", bmp32x32)
        )));
        // Also add a manifest shortcut, which should be removed too.
        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                R.xml.shortcut_1);
        updatePackageVersion(CALLING_PACKAGE_1, 1);
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageAddIntent(CALLING_PACKAGE_1, USER_0));
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertWith(getCallerShortcuts())
                    .haveIds("s1", "s2", "ms1")

                    .selectManifest()
                    .haveIds("ms1");
        });

        setCaller(CALLING_PACKAGE_2, USER_0);
        assertTrue(mManager.addDynamicShortcuts(list(makeShortcutWithIcon("s1", bmp32x32))));

        setCaller(CALLING_PACKAGE_3, USER_0);
        assertTrue(mManager.addDynamicShortcuts(list(makeShortcutWithIcon("s1", bmp32x32))));

        mRunningUsers.put(USER_10, true);

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

        remover.accept(USER_0, CALLING_PACKAGE_1);

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

        mRunningUsers.put(USER_10, true);

        remover.accept(USER_10, CALLING_PACKAGE_2);

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

        mService.checkPackageChanges(USER_0);

        assertNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_2, "s1", USER_0));
        assertNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_3, "s1", USER_0));  // ---------------
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "s1", USER_10));
        assertNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_2, "s1", USER_10));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_3, "s1", USER_10));

        assertFalse(bitmapDirectoryExists(CALLING_PACKAGE_1, USER_0));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_2, USER_0));
        assertFalse(bitmapDirectoryExists(CALLING_PACKAGE_3, USER_0));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_1, USER_10));
        assertFalse(bitmapDirectoryExists(CALLING_PACKAGE_2, USER_10));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_3, USER_10));

        mService.checkPackageChanges(USER_10);

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

        mRunningUsers.put(USER_10, true);

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

        mRunningUsers.put(USER_10, true);

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

    public void testHandlePackageClearData_manifestRepublished() {

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

        // Clear data
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageDataClear(CALLING_PACKAGE_1, USER_10));

        // Only manifest shortcuts will remain, and are no longer pinned.
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertWith(getCallerShortcuts())
                    .haveIds("ms1", "ms2")
                    .areAllEnabled()
                    .areAllNotPinned();
        });
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

        mRunningUsers.put(USER_10, true);

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

        // Update the version info for package 1.
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

        // Next, send an unlock event on user-10.  Now we scan packages on this user and send a
        // notification to the launcher.
        mInjectedCurrentTimeMillis = START_TIME + 200;

        mRunningUsers.put(USER_10, true);
        mUnlockedUsers.put(USER_10, true);

        reset(c0);
        reset(c10);
        setPackageLastUpdateTime(CALLING_PACKAGE_1, mInjectedCurrentTimeMillis);
        mService.handleUnlockUser(USER_10);
        mService.checkPackageChanges(USER_10);

        waitOnMainThread();

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
        mService.checkPackageChanges(USER_10);

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

    /**
     * Test the case where an updated app has resource IDs changed.
     */
    public void testHandlePackageUpdate_resIdChanged() throws Exception {
        final Icon icon1 = Icon.createWithResource(getTestContext(), /* res ID */ 1000);
        final Icon icon2 = Icon.createWithResource(getTestContext(), /* res ID */ 1001);

        // Set up shortcuts.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            // Note resource strings are not officially supported (they're hidden), but
            // should work.

            final ShortcutInfo s1 = new ShortcutInfo.Builder(mClientContext)
                    .setId("s1")
                    .setActivity(makeComponent(ShortcutActivity.class))
                    .setIntent(new Intent(Intent.ACTION_VIEW))
                    .setIcon(icon1)
                    .setTitleResId(10000)
                    .setTextResId(10001)
                    .setDisabledMessageResId(10002)
                    .build();

            final ShortcutInfo s2 = new ShortcutInfo.Builder(mClientContext)
                    .setId("s2")
                    .setActivity(makeComponent(ShortcutActivity.class))
                    .setIntent(new Intent(Intent.ACTION_VIEW))
                    .setIcon(icon2)
                    .setTitleResId(20000)
                    .build();

            assertTrue(mManager.setDynamicShortcuts(list(s1, s2)));
        });

        // Verify.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            final ShortcutInfo s1 = getCallerShortcut("s1");
            final ShortcutInfo s2 = getCallerShortcut("s2");

            assertEquals(1000, s1.getIconResourceId());
            assertEquals(10000, s1.getTitleResId());
            assertEquals(10001, s1.getTextResId());
            assertEquals(10002, s1.getDisabledMessageResourceId());

            assertEquals(1001, s2.getIconResourceId());
            assertEquals(20000, s2.getTitleResId());
            assertEquals(0, s2.getTextResId());
            assertEquals(0, s2.getDisabledMessageResourceId());
        });

        mService.saveDirtyInfo();
        initService();

        // Set up the mock resources again, with an "adjustment".
        // When the package is updated, the service will fetch the updated res-IDs with res-names,
        // and the new IDs will have this offset.
        setUpAppResources(10);

        // Update the package.
        updatePackageVersion(CALLING_PACKAGE_1, 1);
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageUpdateIntent(CALLING_PACKAGE_1, USER_0));

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            final ShortcutInfo s1 = getCallerShortcut("s1");
            final ShortcutInfo s2 = getCallerShortcut("s2");

            assertEquals(1010, s1.getIconResourceId());
            assertEquals(10010, s1.getTitleResId());
            assertEquals(10011, s1.getTextResId());
            assertEquals(10012, s1.getDisabledMessageResourceId());

            assertEquals(1011, s2.getIconResourceId());
            assertEquals(20010, s2.getTitleResId());
            assertEquals(0, s2.getTextResId());
            assertEquals(0, s2.getDisabledMessageResourceId());
        });
    }

    public void testHandlePackageUpdate_systemAppUpdate() {

        // Package1 is a system app.  Package 2 is not a system app, so it's not scanned
        // in this test at all.
        mSystemPackages.add(CALLING_PACKAGE_1);

        // Initial state: no shortcuts.
        mService.checkPackageChanges(USER_0);

        assertEquals(mInjectedCurrentTimeMillis,
                mService.getUserShortcutsLocked(USER_0).getLastAppScanTime());
        assertEquals(mInjectedBuildFingerprint,
                mService.getUserShortcutsLocked(USER_0).getLastAppScanOsFingerprint());

        // They have no shortcuts.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertWith(getCallerShortcuts())
                    .isEmpty();
        });

        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertWith(getCallerShortcuts())
                    .isEmpty();
        });

        // Next.
        // Update the packages -- now they have 1 manifest shortcut.
        // But checkPackageChanges() don't notice it, since their version code / timestamp haven't
        // changed.
        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                R.xml.shortcut_1);
        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_2, ShortcutActivity.class.getName()),
                R.xml.shortcut_1);
        mInjectedCurrentTimeMillis += 1000;
        mService.checkPackageChanges(USER_0);

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertWith(getCallerShortcuts())
                    .isEmpty();
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertWith(getCallerShortcuts())
                    .isEmpty();
        });

        // Next.
        // Update the build finger print.  All apps will be scanned now.
        mInjectedBuildFingerprint = "update1";
        mInjectedCurrentTimeMillis += 1000;
        mService.checkPackageChanges(USER_0);

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertWith(getCallerShortcuts())
                    .haveIds("ms1");
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertWith(getCallerShortcuts())
                    .haveIds("ms1");
        });

        // Next.
        // Update manifest shortcuts.
        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                R.xml.shortcut_2);
        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_2, ShortcutActivity.class.getName()),
                R.xml.shortcut_2);
        mInjectedCurrentTimeMillis += 1000;
        mService.checkPackageChanges(USER_0);

        // Fingerprint hasn't changed, so there packages weren't scanned.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertWith(getCallerShortcuts())
                    .haveIds("ms1");
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertWith(getCallerShortcuts())
                    .haveIds("ms1");
        });

        // Update the fingerprint.  CALLING_PACKAGE_1's version code hasn't changed, but we scan
        // all apps anyway.
        mInjectedBuildFingerprint = "update2";
        mInjectedCurrentTimeMillis += 1000;
        mService.checkPackageChanges(USER_0);

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertWith(getCallerShortcuts())
                    .haveIds("ms1", "ms2");
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertWith(getCallerShortcuts())
                    .haveIds("ms1", "ms2");
        });

        // Make sure getLastAppScanTime / getLastAppScanOsFingerprint are persisted.
        initService();
        assertEquals(mInjectedCurrentTimeMillis,
                mService.getUserShortcutsLocked(USER_0).getLastAppScanTime());
        assertEquals(mInjectedBuildFingerprint,
                mService.getUserShortcutsLocked(USER_0).getLastAppScanOsFingerprint());
    }

    public void testHandlePackageChanged() {
        final ComponentName ACTIVITY1 = new ComponentName(CALLING_PACKAGE_1, "act1");
        final ComponentName ACTIVITY2 = new ComponentName(CALLING_PACKAGE_1, "act2");

        addManifestShortcutResource(ACTIVITY1, R.xml.shortcut_1);
        addManifestShortcutResource(ACTIVITY2, R.xml.shortcut_1_alt);

        mRunningUsers.put(USER_10, true);

        updatePackageVersion(CALLING_PACKAGE_1, 1);
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageAddIntent(CALLING_PACKAGE_1, USER_10));

        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertTrue(mManager.addDynamicShortcuts(list(
                    makeShortcutWithActivity("s1", ACTIVITY1),
                    makeShortcutWithActivity("s2", ACTIVITY2)
            )));
        });
        runWithCaller(LAUNCHER_1, USER_10, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("ms1-alt", "s2"), HANDLE_USER_10);
        });

        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertWith(getCallerShortcuts())
                    .haveIds("ms1", "ms1-alt", "s1", "s2")
                    .areAllEnabled()

                    .selectPinned()
                    .haveIds("ms1-alt", "s2")

                    .revertToOriginalList()
                    .selectByIds("ms1", "s1")
                    .areAllWithActivity(ACTIVITY1)

                    .revertToOriginalList()
                    .selectByIds("ms1-alt", "s2")
                    .areAllWithActivity(ACTIVITY2)
            ;
        });

        // First, no changes.
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageChangedIntent(CALLING_PACKAGE_1, USER_10));

        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertWith(getCallerShortcuts())
                    .haveIds("ms1", "ms1-alt", "s1", "s2")
                    .areAllEnabled()

                    .selectPinned()
                    .haveIds("ms1-alt", "s2")

                    .revertToOriginalList()
                    .selectByIds("ms1", "s1")
                    .areAllWithActivity(ACTIVITY1)

                    .revertToOriginalList()
                    .selectByIds("ms1-alt", "s2")
                    .areAllWithActivity(ACTIVITY2)
            ;
        });

        // Disable activity 1
        mEnabledActivityChecker = (activity, userId) -> !ACTIVITY1.equals(activity);
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageChangedIntent(CALLING_PACKAGE_1, USER_10));

        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertWith(getCallerShortcuts())
                    .haveIds("ms1-alt", "s2")
                    .areAllEnabled()

                    .selectPinned()
                    .haveIds("ms1-alt", "s2")

                    .revertToOriginalList()
                    .selectByIds("ms1-alt", "s2")
                    .areAllWithActivity(ACTIVITY2)
            ;
        });

        // Re-enable activity 1.
        // Manifest shortcuts will be re-published, but dynamic ones are not.
        mEnabledActivityChecker = (activity, userId) -> true;
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageChangedIntent(CALLING_PACKAGE_1, USER_10));

        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertWith(getCallerShortcuts())
                    .haveIds("ms1", "ms1-alt", "s2")
                    .areAllEnabled()

                    .selectPinned()
                    .haveIds("ms1-alt", "s2")

                    .revertToOriginalList()
                    .selectByIds("ms1")
                    .areAllWithActivity(ACTIVITY1)

                    .revertToOriginalList()
                    .selectByIds("ms1-alt", "s2")
                    .areAllWithActivity(ACTIVITY2)
            ;
        });

        // Disable activity 2
        // Because "ms1-alt" and "s2" are both pinned, they will remain, but disabled.
        mEnabledActivityChecker = (activity, userId) -> !ACTIVITY2.equals(activity);
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageChangedIntent(CALLING_PACKAGE_1, USER_10));

        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertWith(getCallerShortcuts())
                    .haveIds("ms1", "ms1-alt", "s2")

                    .selectDynamic().isEmpty().revertToOriginalList() // no dynamics.

                    .selectPinned()
                    .haveIds("ms1-alt", "s2")
                    .areAllDisabled()

                    .revertToOriginalList()
                    .selectByIds("ms1")
                    .areAllWithActivity(ACTIVITY1)
                    .areAllEnabled()
            ;
        });
    }

    public void testHandlePackageUpdate_activityNoLongerMain() throws Throwable {
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcutWithActivity("s1a",
                            new ComponentName(getCallingPackage(), "act1")),
                    makeShortcutWithActivity("s1b",
                            new ComponentName(getCallingPackage(), "act1")),
                    makeShortcutWithActivity("s2a",
                            new ComponentName(getCallingPackage(), "act2")),
                    makeShortcutWithActivity("s2b",
                            new ComponentName(getCallingPackage(), "act2")),
                    makeShortcutWithActivity("s3a",
                            new ComponentName(getCallingPackage(), "act3")),
                    makeShortcutWithActivity("s3b",
                            new ComponentName(getCallingPackage(), "act3"))
            )));
            assertWith(getCallerShortcuts())
                    .haveIds("s1a", "s1b", "s2a", "s2b", "s3a", "s3b")
                    .areAllDynamic();
        });
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                    list("s1b", "s2b", "s3b"),
                    HANDLE_USER_0);
        });
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertWith(getCallerShortcuts())
                    .haveIds("s1a", "s1b", "s2a", "s2b", "s3a", "s3b")
                    .areAllDynamic()

                    .selectByIds("s1b", "s2b", "s3b")
                    .areAllPinned();
        });

        // Update the app and act2 and act3 are no longer main.
        mMainActivityChecker = (activity, userId) -> {
            return activity.getClassName().equals("act1");
        };

        setCaller(LAUNCHER_1, USER_0);
        assertForLauncherCallback(mLauncherApps, () -> {
            updatePackageVersion(CALLING_PACKAGE_1, 1);
            mService.mPackageMonitor.onReceive(getTestContext(),
                    genPackageUpdateIntent(CALLING_PACKAGE_1, USER_0));
        }).assertCallbackCalledForPackageAndUser(CALLING_PACKAGE_1, HANDLE_USER_0)
                // Make sure the launcher gets callbacks.
                .haveIds("s1a", "s1b", "s2b", "s3b")
                .areAllWithKeyFieldsOnly();

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            // s2a and s3a are gone, but s2b and s3b will remain because they're pinned, and
            // disabled.
            assertWith(getCallerShortcuts())
                    .haveIds("s1a", "s1b", "s2b", "s3b")

                    .selectByIds("s1a", "s1b")
                    .areAllDynamic()
                    .areAllEnabled()

                    .revertToOriginalList()
                    .selectByIds("s2b", "s3b")
                    .areAllNotDynamic()
                    .areAllDisabled()
                    .areAllPinned()
            ;
        });
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

        assertFileNotExists("user-0/shortcut_dump/restore-0-start.txt");
        assertFileNotExists("user-0/shortcut_dump/restore-1-payload.xml");
        assertFileNotExists("user-0/shortcut_dump/restore-2.txt");
        assertFileNotExists("user-0/shortcut_dump/restore-3.txt");
        assertFileNotExists("user-0/shortcut_dump/restore-4.txt");
        assertFileNotExists("user-0/shortcut_dump/restore-5-finish.txt");

        prepareForBackupTest();

        assertFileExistsWithContent("user-0/shortcut_dump/restore-0-start.txt");
        assertFileExistsWithContent("user-0/shortcut_dump/restore-1-payload.xml");
        assertFileExistsWithContent("user-0/shortcut_dump/restore-2.txt");
        assertFileExistsWithContent("user-0/shortcut_dump/restore-3.txt");
        assertFileExistsWithContent("user-0/shortcut_dump/restore-4.txt");
        assertFileExistsWithContent("user-0/shortcut_dump/restore-5-finish.txt");

        checkBackupAndRestore_success(/*firstRestore=*/ true);
    }

    public void testBackupAndRestore_backupRestoreTwice() {
        prepareForBackupTest();

        checkBackupAndRestore_success(/*firstRestore=*/ true);

        // Run a backup&restore again. Note the shortcuts that weren't restored in the previous
        // restore are disabled, so they won't be restored again.
        dumpsysOnLogcat("Before second backup&restore");

        backupAndRestore();

        dumpsysOnLogcat("After second backup&restore");

        checkBackupAndRestore_success(/*firstRestore=*/ false);
    }

    public void testBackupAndRestore_restoreToNewVersion() {
        prepareForBackupTest();

        addPackage(CALLING_PACKAGE_1, CALLING_UID_1, 2);
        addPackage(LAUNCHER_1, LAUNCHER_UID_1, 5);

        checkBackupAndRestore_success(/*firstRestore=*/ true);
    }

    public void testBackupAndRestore_restoreToSuperSetSignatures() {
        prepareForBackupTest();

        // Change package signatures.
        addPackage(CALLING_PACKAGE_1, CALLING_UID_1, 1, "sigx", CALLING_PACKAGE_1);
        addPackage(LAUNCHER_1, LAUNCHER_UID_1, 4, LAUNCHER_1, "sigy");

        checkBackupAndRestore_success(/*firstRestore=*/ true);
    }

    protected void checkBackupAndRestore_success(boolean firstRestore) {
        // Make sure non-system user is not restored.
        final ShortcutUser userP0 = mService.getUserShortcutsLocked(USER_P0);
        assertEquals(0, userP0.getAllPackagesForTest().size());
        assertEquals(0, userP0.getAllLaunchersForTest().size());

        // Make sure only "allowBackup" apps are restored, and are shadow.
        final ShortcutUser user0 = mService.getUserShortcutsLocked(USER_0);
        assertExistsAndShadow(user0.getAllPackagesForTest().get(CALLING_PACKAGE_1));
        assertExistsAndShadow(user0.getAllPackagesForTest().get(CALLING_PACKAGE_2));

        assertExistsAndShadow(user0.getAllPackagesForTest().get(CALLING_PACKAGE_3));
        assertExistsAndShadow(user0.getAllLaunchersForTest().get(
                PackageWithUser.of(USER_0, LAUNCHER_1)));
        assertExistsAndShadow(user0.getAllLaunchersForTest().get(
                PackageWithUser.of(USER_0, LAUNCHER_2)));

        assertNull(user0.getAllLaunchersForTest().get(PackageWithUser.of(USER_0, LAUNCHER_3)));
        assertNull(user0.getAllLaunchersForTest().get(PackageWithUser.of(USER_P0, LAUNCHER_1)));

        doReturn(true).when(mMockPackageManagerInternal).isDataRestoreSafe(any(byte[].class),
                anyString());

        installPackage(USER_0, CALLING_PACKAGE_1);
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertWith(getCallerVisibleShortcuts())
                    .selectDynamic()
                    .isEmpty()

                    .revertToOriginalList()
                    .selectPinned()
                    .haveIds("s1", "s2")
                    .areAllEnabled();
        });

        installPackage(USER_0, LAUNCHER_1);
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertWith(mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_0))
                    .areAllPinned()
                    .haveIds("s1")
                    .areAllEnabled();

            assertWith(mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_2), HANDLE_USER_0))
                    .isEmpty();

            assertWith(mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_3), HANDLE_USER_0))
                    .isEmpty();

            assertWith(mLauncherApps.getShortcuts(QUERY_ALL, HANDLE_USER_P0))
                    .isEmpty();
        });

        installPackage(USER_0, CALLING_PACKAGE_2);
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertWith(getCallerVisibleShortcuts())
                    .selectDynamic()
                    .isEmpty()

                    .revertToOriginalList()
                    .selectPinned()
                    .haveIds("s1", "s2", "s3")
                    .areAllEnabled();
        });

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertWith(mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_0))
                    .areAllPinned()
                    .haveIds("s1")
                    .areAllEnabled();

            assertWith(mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_2), HANDLE_USER_0))
                    .areAllPinned()
                    .haveIds("s1", "s2")
                    .areAllEnabled();

            assertWith(mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_3), HANDLE_USER_0))
                    .isEmpty();

            assertWith(mLauncherApps.getShortcuts(QUERY_ALL, HANDLE_USER_P0))
                    .isEmpty();
        });

        // 3 shouldn't be backed up, so no pinned shortcuts.
        installPackage(USER_0, CALLING_PACKAGE_3);
        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            assertWith(getCallerVisibleShortcuts())
                    .isEmpty();
        });

        // Launcher on a different profile shouldn't be restored.
        runWithCaller(LAUNCHER_1, USER_P0, () -> {
            assertWith(mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_0))
                    .isEmpty();
            assertWith(mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_2), HANDLE_USER_0))
                    .isEmpty();
            assertWith(mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_3), HANDLE_USER_0))
                    .isEmpty();
        });

        // Package on a different profile, no restore.
        installPackage(USER_P0, CALLING_PACKAGE_1);
        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            assertWith(getCallerVisibleShortcuts())
                    .isEmpty();
        });

        // Restore launcher 2 on user 0.
        installPackage(USER_0, LAUNCHER_2);
        runWithCaller(LAUNCHER_2, USER_0, () -> {
            assertWith(mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_0))
                    .areAllPinned()
                    .haveIds("s2")
                    .areAllEnabled();

            assertWith(mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_2), HANDLE_USER_0))
                    .areAllPinned()
                    .haveIds("s2", "s3")
                    .areAllEnabled();

            if (firstRestore) {
                assertWith(
                        mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_3), HANDLE_USER_0))
                        .haveIds("s2", "s3", "s4")
                        .areAllDisabled()
                        .areAllPinned()
                        .areAllNotDynamic()
                        .areAllWithDisabledReason(
                                ShortcutInfo.DISABLED_REASON_BACKUP_NOT_SUPPORTED);
            } else {
                assertWith(
                        mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_3), HANDLE_USER_0))
                        .isEmpty();
            }

            assertWith(mLauncherApps.getShortcuts(QUERY_ALL, HANDLE_USER_P0))
                    .isEmpty();
        });


        // Restoration of launcher2 shouldn't affect other packages; so do the same checks and
        // make sure they still have the same result.
        installPackage(USER_0, CALLING_PACKAGE_1);
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertWith(getCallerVisibleShortcuts())
                    .areAllPinned()
                    .haveIds("s1", "s2");
        });

        installPackage(USER_0, LAUNCHER_1);
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertWith(mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_0))
                    .areAllPinned()
                    .haveIds("s1")
                    .areAllEnabled();

            assertWith(mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_2), HANDLE_USER_0))
                    .areAllPinned()
                    .haveIds("s1", "s2")
                    .areAllEnabled();

            if (firstRestore) {
                assertWith(
                        mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_3), HANDLE_USER_0))
                        .haveIds("s1", "s2", "s3")
                        .areAllDisabled()
                        .areAllPinned()
                        .areAllNotDynamic()
                        .areAllWithDisabledReason(ShortcutInfo.DISABLED_REASON_BACKUP_NOT_SUPPORTED);
            } else {
                assertWith(
                        mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_3), HANDLE_USER_0))
                        .isEmpty();
            }

            assertWith(mLauncherApps.getShortcuts(QUERY_ALL, HANDLE_USER_P0))
                    .isEmpty();
        });

        installPackage(USER_0, CALLING_PACKAGE_2);
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertWith(getCallerVisibleShortcuts())
                    .areAllPinned()
                    .haveIds("s1", "s2", "s3")
                    .areAllEnabled();
        });
    }

    public void testBackupAndRestore_publisherLowerVersion() {
        prepareForBackupTest();

        addPackage(CALLING_PACKAGE_1, CALLING_UID_1, 0); // Lower version

        checkBackupAndRestore_publisherNotRestored(ShortcutInfo.DISABLED_REASON_VERSION_LOWER);
    }

    public void testBackupAndRestore_publisherWrongSignature() {
        prepareForBackupTest();

        addPackage(CALLING_PACKAGE_1, CALLING_UID_1, 10, "sigx"); // different signature

        checkBackupAndRestore_publisherNotRestored(ShortcutInfo.DISABLED_REASON_SIGNATURE_MISMATCH);
    }

    public void testBackupAndRestore_publisherNoLongerBackupTarget() {
        prepareForBackupTest();

        updatePackageInfo(CALLING_PACKAGE_1,
                pi -> pi.applicationInfo.flags &= ~ApplicationInfo.FLAG_ALLOW_BACKUP);

        checkBackupAndRestore_publisherNotRestored(
                ShortcutInfo.DISABLED_REASON_BACKUP_NOT_SUPPORTED);
    }

    protected void checkBackupAndRestore_publisherNotRestored(
            int package1DisabledReason) {
        doReturn(package1DisabledReason != ShortcutInfo.DISABLED_REASON_SIGNATURE_MISMATCH).when(
                mMockPackageManagerInternal).isDataRestoreSafe(any(byte[].class),
                eq(CALLING_PACKAGE_1));

        installPackage(USER_0, CALLING_PACKAGE_1);
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertEquals(0, mManager.getDynamicShortcuts().size());
            assertEquals(0, mManager.getPinnedShortcuts().size());
        });
        assertFalse(mService.getPackageShortcutForTest(CALLING_PACKAGE_1, USER_0)
                .getPackageInfo().isShadow());

        doReturn(true).when(mMockPackageManagerInternal).isDataRestoreSafe(
                any(byte[].class), anyString());

        installPackage(USER_0, CALLING_PACKAGE_2);
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertEquals(0, mManager.getDynamicShortcuts().size());
            assertShortcutIds(assertAllPinned(
                    mManager.getPinnedShortcuts()),
                    "s1", "s2", "s3");
        });
        assertFalse(mService.getPackageShortcutForTest(CALLING_PACKAGE_2, USER_0)
                .getPackageInfo().isShadow());

        installPackage(USER_0, LAUNCHER_1);
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertWith(mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_0))
                    .haveIds("s1")
                    .areAllPinned()
                    .areAllDisabled()
                    .areAllWithDisabledReason(package1DisabledReason)
                    .forAllShortcuts(si -> {
                        switch (package1DisabledReason) {
                            case ShortcutInfo.DISABLED_REASON_VERSION_LOWER:
                                assertEquals("App version downgraded, or isn’t compatible"
                                        + " with this shortcut",
                                        si.getDisabledMessage());
                                break;
                            case ShortcutInfo.DISABLED_REASON_SIGNATURE_MISMATCH:
                                assertEquals(
                                        "Couldn\u2019t restore shortcut because of app"
                                        + " signature mismatch",
                                        si.getDisabledMessage());
                                break;
                            case ShortcutInfo.DISABLED_REASON_BACKUP_NOT_SUPPORTED:
                                assertEquals(
                                        "Couldn\u2019t restore shortcut because app"
                                        + " doesn\u2019t support backup and restore",
                                        si.getDisabledMessage());
                                break;
                            default:
                                fail("Unhandled disabled reason: " + package1DisabledReason);
                        }
                    });
            assertWith(mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_2), HANDLE_USER_0))
                    .haveIds("s1", "s2")
                    .areAllPinned()
                    .areAllEnabled();
            assertWith(mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_3), HANDLE_USER_0))
                    .isEmpty();
        });
        installPackage(USER_0, LAUNCHER_2);
        runWithCaller(LAUNCHER_2, USER_0, () -> {
            assertWith(mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_0))
                    .haveIds("s2")
                    .areAllPinned()
                    .areAllDisabled()
                    .areAllWithDisabledReason(package1DisabledReason);
            assertWith(mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_2), HANDLE_USER_0))
                    .haveIds("s2", "s3")
                    .areAllPinned()
                    .areAllEnabled();
            assertWith(mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_3), HANDLE_USER_0))
                    .isEmpty();
        });

        installPackage(USER_0, CALLING_PACKAGE_3);
        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            assertEquals(0, mManager.getDynamicShortcuts().size());
            assertEquals(0, mManager.getPinnedShortcuts().size());
        });

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertWith(mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_0))
                    .haveIds("s1")
                    .areAllPinned()
                    .areAllDisabled()
                    .areAllWithDisabledReason(package1DisabledReason);
            assertWith(mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_2), HANDLE_USER_0))
                    .haveIds("s1", "s2")
                    .areAllPinned()
                    .areAllEnabled();
            assertWith(mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_3), HANDLE_USER_0))
                    .haveIds("s1", "s2", "s3")
                    .areAllPinned()
                    .areAllDisabled()
                    .areAllWithDisabledReason(ShortcutInfo.DISABLED_REASON_BACKUP_NOT_SUPPORTED);
        });
        runWithCaller(LAUNCHER_2, USER_0, () -> {
            assertWith(mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_0))
                    .haveIds("s2")
                    .areAllPinned()
                    .areAllDisabled()
                    .areAllWithDisabledReason(package1DisabledReason);
            assertWith(mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_2), HANDLE_USER_0))
                    .haveIds("s2", "s3")
                    .areAllPinned()
                    .areAllEnabled();
            assertWith(mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_3), HANDLE_USER_0))
                    .haveIds("s2", "s3", "s4")
                    .areAllPinned()
                    .areAllDisabled()
                    .areAllWithDisabledReason(ShortcutInfo.DISABLED_REASON_BACKUP_NOT_SUPPORTED);
        });
    }

    public void testBackupAndRestore_launcherLowerVersion() {
        prepareForBackupTest();

        addPackage(LAUNCHER_1, LAUNCHER_UID_1, 0); // Lower version

        // Note, we restore pinned shortcuts even if the launcher is of a lower version.
        checkBackupAndRestore_success(/*firstRestore=*/ true);
    }

    public void testBackupAndRestore_launcherWrongSignature() {
        prepareForBackupTest();

        addPackage(LAUNCHER_1, LAUNCHER_UID_1, 10, "sigx"); // different signature

        checkBackupAndRestore_launcherNotRestored(true);
    }

    public void testBackupAndRestore_launcherNoLongerBackupTarget() {
        prepareForBackupTest();

        updatePackageInfo(LAUNCHER_1,
                pi -> pi.applicationInfo.flags &= ~ApplicationInfo.FLAG_ALLOW_BACKUP);

        checkBackupAndRestore_launcherNotRestored(false);
    }

    protected void checkBackupAndRestore_launcherNotRestored(boolean differentSignatures) {
        doReturn(true).when(mMockPackageManagerInternal).isDataRestoreSafe(
                any(byte[].class), anyString());

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

        doReturn(!differentSignatures).when(mMockPackageManagerInternal).isDataRestoreSafe(
                any(byte[].class), eq(LAUNCHER_1));

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
        assertFalse(mService.getLauncherShortcutForTest(LAUNCHER_1, USER_0)
                .getPackageInfo().isShadow());

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertEquals(0, mManager.getDynamicShortcuts().size());

            // Now CALLING_PACKAGE_1 realizes "s1" is no longer pinned.
            assertShortcutIds(assertAllPinned(
                    mManager.getPinnedShortcuts()),
                    "s2");
        });

        doReturn(true).when(mMockPackageManagerInternal).isDataRestoreSafe(
                any(byte[].class), anyString());

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
        assertFalse(mService.getLauncherShortcutForTest(LAUNCHER_2, USER_0)
                .getPackageInfo().isShadow());

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
        });
    }

    public void testBackupAndRestore_launcherAndPackageNoLongerBackupTarget() {
        prepareForBackupTest();

        updatePackageInfo(CALLING_PACKAGE_1,
                pi -> pi.applicationInfo.flags &= ~ApplicationInfo.FLAG_ALLOW_BACKUP);

        updatePackageInfo(LAUNCHER_1,
                pi -> pi.applicationInfo.flags &= ~ApplicationInfo.FLAG_ALLOW_BACKUP);

        checkBackupAndRestore_publisherAndLauncherNotRestored();
    }

    protected void checkBackupAndRestore_publisherAndLauncherNotRestored() {
        doReturn(true).when(mMockPackageManagerInternal).isDataRestoreSafe(any(byte[].class),
                anyString());
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
            assertWith(mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_0))
                    .areAllPinned()
                    .haveIds("s2")
                    .areAllDisabled();
            assertWith(mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_2), HANDLE_USER_0))
                    .areAllPinned()
                    .haveIds("s2", "s3");
            assertWith(mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_3), HANDLE_USER_0))
                    .isEmpty();
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
            assertWith(mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_0))
                    .areAllPinned()
                    .haveIds("s2")
                    .areAllDisabled();
            assertWith(mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_2), HANDLE_USER_0))
                    .areAllPinned()
                    .haveIds("s2", "s3");
            assertWith(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_3), HANDLE_USER_0))
                    .haveIds("s2", "s3", "s4")
                    .areAllDisabled()
                    .areAllPinned()
                    .areAllNotDynamic()
                    .areAllWithDisabledReason(
                            ShortcutInfo.DISABLED_REASON_BACKUP_NOT_SUPPORTED);
        });
    }

    public void testBackupAndRestore_disabled() {
        prepareCrossProfileDataSet();

        // Before doing backup & restore, disable s1.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            mManager.disableShortcuts(list("s1"));
        });

        backupAndRestore();

        // Below is copied from checkBackupAndRestore_success.

        // Make sure non-system user is not restored.
        final ShortcutUser userP0 = mService.getUserShortcutsLocked(USER_P0);
        assertEquals(0, userP0.getAllPackagesForTest().size());
        assertEquals(0, userP0.getAllLaunchersForTest().size());

        // Make sure only "allowBackup" apps are restored, and are shadow.
        final ShortcutUser user0 = mService.getUserShortcutsLocked(USER_0);
        assertExistsAndShadow(user0.getAllPackagesForTest().get(CALLING_PACKAGE_1));
        assertExistsAndShadow(user0.getAllPackagesForTest().get(CALLING_PACKAGE_2));
        assertExistsAndShadow(user0.getAllPackagesForTest().get(CALLING_PACKAGE_3));
        assertExistsAndShadow(user0.getAllLaunchersForTest().get(
                PackageWithUser.of(USER_0, LAUNCHER_1)));
        assertExistsAndShadow(user0.getAllLaunchersForTest().get(
                PackageWithUser.of(USER_0, LAUNCHER_2)));

        assertNull(user0.getAllLaunchersForTest().get(PackageWithUser.of(USER_0, LAUNCHER_3)));
        assertNull(user0.getAllLaunchersForTest().get(PackageWithUser.of(USER_P0, LAUNCHER_1)));

        doReturn(true).when(mMockPackageManagerInternal).isDataRestoreSafe(any(byte[].class),
                anyString());

        installPackage(USER_0, CALLING_PACKAGE_1);
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertWith(getCallerVisibleShortcuts())
                    .areAllEnabled() // disabled shortcuts shouldn't be restored.

                    .selectDynamic()
                    .isEmpty()

                    .revertToOriginalList()
                    .selectPinned()
                    // s1 is not restored.
                    .haveIds("s2");
        });

        installPackage(USER_0, LAUNCHER_1);
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            // Note, s1 was pinned by launcher 1, but was disabled, so isn't restored.
            assertWith(mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_0))
                    .isEmpty();

            assertWith(mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_2), HANDLE_USER_0))
                    .isEmpty();

            assertWith(mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_3), HANDLE_USER_0))
                    .isEmpty();

            assertWith(mLauncherApps.getShortcuts(QUERY_ALL, HANDLE_USER_P0))
                    .isEmpty();
        });
    }


    public void testBackupAndRestore_manifestRePublished() {
        // Publish two manifest shortcuts.
        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                R.xml.shortcut_2);
        updatePackageVersion(CALLING_PACKAGE_1, 1);
        mService.mPackageMonitor.onReceive(mServiceContext,
                genPackageAddIntent(CALLING_PACKAGE_1, USER_0));

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"))));
        });

        // Pin from launcher 1.
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                    list("ms1", "ms2", "s1", "s2"), HANDLE_USER_0);
        });

        // Update and now ms2 is gone -> disabled.
        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                R.xml.shortcut_1);
        updatePackageVersion(CALLING_PACKAGE_1, 1);
        mService.mPackageMonitor.onReceive(mServiceContext,
                genPackageAddIntent(CALLING_PACKAGE_1, USER_0));

        // Make sure the manifest shortcuts have been published.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertWith(getCallerShortcuts())
                    .selectManifest()
                    .haveIds("ms1")

                    .revertToOriginalList()
                    .selectDynamic()
                    .haveIds("s1", "s2", "s3")

                    .revertToOriginalList()
                    .selectPinned()
                    .haveIds("ms1", "ms2", "s1", "s2")

                    .revertToOriginalList()
                    .selectByIds("ms1")
                    .areAllManifest()
                    .areAllEnabled()

                    .revertToOriginalList()
                    .selectByIds("ms2")
                    .areAllNotManifest()
                    .areAllDisabled();
        });

        doReturn(true).when(mMockPackageManagerInternal).isDataRestoreSafe(
                any(byte[].class), anyString());
        backupAndRestore();

        // When re-installing the app, the manifest shortcut should be re-published.
        mService.mPackageMonitor.onReceive(mServiceContext,
                genPackageAddIntent(CALLING_PACKAGE_1, USER_0));
        mService.mPackageMonitor.onReceive(mServiceContext,
                genPackageAddIntent(LAUNCHER_1, USER_0));

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertWith(getCallerVisibleShortcuts())
                    .selectPinned()
                    // ms2 was disabled, so not restored.
                    .haveIds("ms1", "s1", "s2")
                    .areAllEnabled()

                    .revertToOriginalList()
                    .selectByIds("ms1")
                    .areAllManifest()

                    .revertToOriginalList()
                    .selectByIds("s1", "s2")
                    .areAllNotDynamic()
            ;
        });
    }

    /**
     * It's the case with preintalled apps -- when applyRestore() is called, the system
     * apps are already installed, so manifest shortcuts need to be re-published.
     *
     * Also, when a restore target app is already installed, and
     * - if it has allowBackup=true, we'll restore normally, so all existing shortcuts will be
     * replaced. (but manifest shortcuts will be re-published anyway.)  We log a warning on
     * logcat.
     * - if it has allowBackup=false, we don't touch any of the existing shortcuts.
     */
    public void testBackupAndRestore_appAlreadyInstalledWhenRestored() {
        // Pre-backup.  Same as testBackupAndRestore_manifestRePublished().

        // Publish two manifest shortcuts.
        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                R.xml.shortcut_2);
        updatePackageVersion(CALLING_PACKAGE_1, 1);
        mService.mPackageMonitor.onReceive(mServiceContext,
                genPackageAddIntent(CALLING_PACKAGE_1, USER_0));

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"))));
        });

        // Pin from launcher 1.
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                    list("ms1", "ms2", "s1", "s2"), HANDLE_USER_0);
        });

        // Update and now ms2 is gone -> disabled.
        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                R.xml.shortcut_1);
        updatePackageVersion(CALLING_PACKAGE_1, 1);
        mService.mPackageMonitor.onReceive(mServiceContext,
                genPackageAddIntent(CALLING_PACKAGE_1, USER_0));

        // Set up shortcuts for package 3, which won't be backed up / restored.
        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_3, ShortcutActivity.class.getName()),
                R.xml.shortcut_1);
        updatePackageVersion(CALLING_PACKAGE_3, 1);
        mService.mPackageMonitor.onReceive(mServiceContext,
                genPackageAddIntent(CALLING_PACKAGE_3, USER_0));

        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            assertTrue(getManager().setDynamicShortcuts(list(
                    makeShortcut("s1"))));
        });

        // Make sure the manifest shortcuts have been published.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertWith(getCallerShortcuts())
                    .selectManifest()
                    .haveIds("ms1")

                    .revertToOriginalList()
                    .selectDynamic()
                    .haveIds("s1", "s2", "s3")

                    .revertToOriginalList()
                    .selectPinned()
                    .haveIds("ms1", "ms2", "s1", "s2")

                    .revertToOriginalList()
                    .selectByIds("ms1")
                    .areAllManifest()
                    .areAllEnabled()

                    .revertToOriginalList()
                    .selectByIds("ms2")
                    .areAllNotManifest()
                    .areAllDisabled();
        });

        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            assertWith(getCallerShortcuts())
                    .haveIds("s1", "ms1");
        });

        doReturn(true).when(mMockPackageManagerInternal).isDataRestoreSafe(any(byte[].class),
                anyString());
        // Backup and *without restarting the service, just call applyRestore()*.
        {
            int prevUid = mInjectedCallingUid;
            mInjectedCallingUid = Process.SYSTEM_UID; // Only system can call it.

            dumpsysOnLogcat("Before backup");

            final byte[] payload = mService.getBackupPayload(USER_0);
            if (ENABLE_DUMP) {
                final String xml = new String(payload);
                Log.v(TAG, "Backup payload:");
                for (String line : xml.split("\n")) {
                    Log.v(TAG, line);
                }
            }
            mService.applyRestore(payload, USER_0);

            dumpsysOnLogcat("After restore");

            mInjectedCallingUid = prevUid;
        }

        // The check is also the same as testBackupAndRestore_manifestRePublished().
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertWith(getCallerVisibleShortcuts())
                    .selectPinned()
                    // ms2 was disabled, so not restored.
                    .haveIds("ms1", "s1", "s2")
                    .areAllEnabled()

                    .revertToOriginalList()
                    .selectByIds("ms1")
                    .areAllManifest()

                    .revertToOriginalList()
                    .selectByIds("s1", "s2")
                    .areAllNotDynamic()
            ;
        });

        // Package 3 still has the same shortcuts.
        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            assertWith(getCallerShortcuts())
                    .haveIds("s1", "ms1");
        });
    }


    /**
     * Restored to a lower version with no manifest shortcuts. All shortcuts are now invisible,
     * and all calls from the publisher should ignore them.
     */
    public void testBackupAndRestore_disabledShortcutsAreIgnored() {
        // Publish two manifest shortcuts.
        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                R.xml.shortcut_5_altalt);
        updatePackageVersion(CALLING_PACKAGE_1, 1);
        mService.mPackageMonitor.onReceive(mServiceContext,
                genPackageAddIntent(CALLING_PACKAGE_1, USER_0));

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcutWithShortLabel("s1", "original-title"),
                    makeShortcut("s2"), makeShortcut("s3"))));
        });

        // Pin from launcher 1.
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                    list("ms1", "ms2", "ms3", "ms4", "s1", "s2"), HANDLE_USER_0);
        });

        doReturn(true).when(mMockPackageManagerInternal).isDataRestoreSafe(
                any(byte[].class), anyString());

        backupAndRestore();

        // Lower the version and remove the manifest shortcuts.
        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                R.xml.shortcut_0);
        addPackage(CALLING_PACKAGE_1, CALLING_UID_1, 0); // Lower version

        // When re-installing the app, the manifest shortcut should be re-published.
        mService.mPackageMonitor.onReceive(mServiceContext,
                genPackageAddIntent(CALLING_PACKAGE_1, USER_0));
        mService.mPackageMonitor.onReceive(mServiceContext,
                genPackageAddIntent(LAUNCHER_1, USER_0));

        // No shortcuts should be visible to the publisher.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertWith(getCallerVisibleShortcuts())
                    .isEmpty();
        });

        final Runnable checkAllDisabledForLauncher = () -> {
            runWithCaller(LAUNCHER_1, USER_0, () -> {
                assertWith(getShortcutAsLauncher(USER_0))
                        .areAllPinned()
                        .haveIds("ms1", "ms2", "ms3", "ms4", "s1", "s2")
                        .areAllDisabled()
                        .areAllWithDisabledReason(ShortcutInfo.DISABLED_REASON_VERSION_LOWER)

                        .forShortcutWithId("s1", si -> {
                            assertEquals("original-title", si.getShortLabel());
                        })
                        .forShortcutWithId("ms1", si -> {
                            assertEquals("string-com.android.test.1-user:0-res:"
                                            + R.string.shortcut_title1 + "/en"
                                    , si.getShortLabel());
                        })
                        .forShortcutWithId("ms2", si -> {
                            assertEquals("string-com.android.test.1-user:0-res:"
                                            + R.string.shortcut_title2 + "/en"
                                    , si.getShortLabel());
                        })
                        .forShortcutWithId("ms3", si -> {
                            assertEquals("string-com.android.test.1-user:0-res:"
                                            + R.string.shortcut_title1 + "/en"
                                    , si.getShortLabel());
                            assertEquals("string-com.android.test.1-user:0-res:"
                                            + R.string.shortcut_title2 + "/en"
                                    , si.getLongLabel());
                        })
                        .forShortcutWithId("ms4", si -> {
                            assertEquals("string-com.android.test.1-user:0-res:"
                                            + R.string.shortcut_title2 + "/en"
                                    , si.getShortLabel());
                            assertEquals("string-com.android.test.1-user:0-res:"
                                            + R.string.shortcut_title2 + "/en"
                                    , si.getLongLabel());
                        });
            });
        };

        checkAllDisabledForLauncher.run();

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {

            makeCallerForeground(); // CALLING_PACKAGE_1 is now in the foreground.

            // All changing API calls should be ignored.

            getManager().enableShortcuts(list("ms1", "ms2", "ms3", "ms4", "s1", "s2"));
            checkAllDisabledForLauncher.run();

            getManager().enableShortcuts(list("ms1", "ms2", "ms3", "ms4", "s1", "s2"));
            checkAllDisabledForLauncher.run();

            getManager().enableShortcuts(list("ms1", "ms2", "ms3", "ms4", "s1", "s2"));
            checkAllDisabledForLauncher.run();

            getManager().removeAllDynamicShortcuts();
            getManager().removeDynamicShortcuts(list("ms1", "ms2", "ms3", "ms4", "s1", "s2"));
            checkAllDisabledForLauncher.run();

            getManager().updateShortcuts(list(makeShortcutWithShortLabel("s1", "new-title")));
            checkAllDisabledForLauncher.run();


            // Add a shortcut -- even though ms1 was immutable, it will succeed.
            assertTrue(getManager().addDynamicShortcuts(list(
                    makeShortcutWithShortLabel("ms1", "original-title"))));

            runWithCaller(LAUNCHER_1, USER_0, () -> {
                assertWith(getShortcutAsLauncher(USER_0))
                        .haveIds("ms1", "ms2", "ms3", "ms4", "s1", "s2")

                        .selectByIds("ms1")
                        .areAllEnabled()
                        .areAllDynamic()
                        .areAllPinned()
                        .forAllShortcuts(si -> {
                            assertEquals("original-title", si.getShortLabel());
                        })

                        // The rest still exist and disabled.
                        .revertToOriginalList()
                        .selectByIds("ms2", "ms3", "ms4", "s1", "s2")
                        .areAllDisabled()
                        .areAllPinned()
                ;
            });

            assertTrue(getManager().setDynamicShortcuts(list(
                    makeShortcutWithShortLabel("ms2", "new-title-2"))));

            runWithCaller(LAUNCHER_1, USER_0, () -> {
                assertWith(getShortcutAsLauncher(USER_0))
                        .haveIds("ms1", "ms2", "ms3", "ms4", "s1", "s2")

                        .selectByIds("ms1")
                        .areAllEnabled()
                        .areAllNotDynamic() // ms1 was not in the list, so no longer dynamic.
                        .areAllPinned()
                        .areAllMutable()
                        .forAllShortcuts(si -> {
                            assertEquals("original-title", si.getShortLabel());
                        })

                        .revertToOriginalList()
                        .selectByIds("ms2")
                        .areAllEnabled()
                        .areAllDynamic()
                        .areAllPinned()
                        .areAllMutable()
                        .forAllShortcuts(si -> {
                            assertEquals("new-title-2", si.getShortLabel());
                        })

                        // The rest still exist and disabled.
                        .revertToOriginalList()
                        .selectByIds("ms3", "ms4", "s1", "s2")
                        .areAllDisabled()
                        .areAllPinned()
                ;
            });

            // Prepare for requestPinShortcut().
            setDefaultLauncher(USER_0, LAUNCHER_1);
            mPinConfirmActivityFetcher = (packageName, userId) ->
                    new ComponentName(packageName, PIN_CONFIRM_ACTIVITY_CLASS);

            mManager.requestPinShortcut(
                    makeShortcutWithShortLabel("ms3", "new-title-3"),
                    /*PendingIntent=*/ null);

            // Note this was pinned, so it'll be accepted right away.
            runWithCaller(LAUNCHER_1, USER_0, () -> {
                assertWith(getShortcutAsLauncher(USER_0))
                        .selectByIds("ms3")
                        .areAllEnabled()
                        .areAllNotDynamic()
                        .areAllPinned()
                        .areAllMutable()
                        .forAllShortcuts(si -> {
                            assertEquals("new-title-3", si.getShortLabel());
                            // The new one replaces the old manifest shortcut, so the long label
                            // should be gone now.
                            assertNull(si.getLongLabel());
                        });
            });

            // Now, change the launcher to launcher2, and request pin again.
            setDefaultLauncher(USER_0, LAUNCHER_2);

            reset(mServiceContext);

            assertTrue(mManager.isRequestPinShortcutSupported());
            mManager.requestPinShortcut(
                    makeShortcutWithShortLabel("ms4", "new-title-4"),
                    /*PendingIntent=*/ null);

            // Initially there should be no pinned shortcuts for L2.
            runWithCaller(LAUNCHER_2, USER_0, () -> {
                assertWith(getShortcutAsLauncher(USER_0))
                        .selectPinned()
                        .isEmpty();

                final ArgumentCaptor<Intent> intent = ArgumentCaptor.forClass(Intent.class);

                verify(mServiceContext).startActivityAsUser(intent.capture(), eq(HANDLE_USER_0));

                assertEquals(LauncherApps.ACTION_CONFIRM_PIN_SHORTCUT,
                        intent.getValue().getAction());
                assertEquals(LAUNCHER_2, intent.getValue().getComponent().getPackageName());

                // Check the request object.
                final PinItemRequest request = mLauncherApps.getPinItemRequest(intent.getValue());

                assertNotNull(request);
                assertEquals(PinItemRequest.REQUEST_TYPE_SHORTCUT, request.getRequestType());

                assertWith(request.getShortcutInfo())
                        .haveIds("ms4")
                        .areAllOrphan()
                        .forAllShortcuts(si -> {
                            assertEquals("new-title-4", si.getShortLabel());
                            // The new one replaces the old manifest shortcut, so the long label
                            // should be gone now.
                            assertNull(si.getLongLabel());
                        });
                assertTrue(request.accept());

                assertWith(getShortcutAsLauncher(USER_0))
                        .selectPinned()
                        .haveIds("ms4")
                        .areAllEnabled();
            });
        });
    }

    /**
     * Test for restoring the pre-P backup format.
     */
    public void testBackupAndRestore_api27format() throws Exception {
        final byte[] payload = readTestAsset("shortcut/shortcut_api27_backup.xml").getBytes();

        addPackage(CALLING_PACKAGE_1, CALLING_UID_1, 10, "22222");
        addPackage(LAUNCHER_1, LAUNCHER_UID_1, 10, "11111");

        doReturn(true).when(mMockPackageManagerInternal).isDataRestoreSafe(
                any(byte[].class), anyString());

        runWithSystemUid(() -> mService.applyRestore(payload, USER_0));

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertWith(getCallerShortcuts())
                    .areAllPinned()
                    .haveIds("s1")
                    .areAllEnabled();
        });

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertWith(getShortcutAsLauncher(USER_0))
                    .areAllPinned()
                    .haveIds("s1")
                    .areAllEnabled();
        });
        // Make sure getBackupSourceVersionCode and isBackupSourceBackupAllowed
        // are correct. We didn't have them in the old format.
        assertEquals(8, mService.getPackageShortcutForTest(CALLING_PACKAGE_1, USER_0)
                .getPackageInfo().getBackupSourceVersionCode());
        assertTrue(mService.getPackageShortcutForTest(CALLING_PACKAGE_1, USER_0)
                .getPackageInfo().isBackupSourceBackupAllowed());

        assertEquals(9, mService.getLauncherShortcutForTest(LAUNCHER_1, USER_0)
                .getPackageInfo().getBackupSourceVersionCode());
        assertTrue(mService.getLauncherShortcutForTest(LAUNCHER_1, USER_0)
                .getPackageInfo().isBackupSourceBackupAllowed());

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
        // Check the user-IDs.
        assertEquals(USER_0,
                mService.getUserShortcutsLocked(USER_0).getPackageShortcuts(CALLING_PACKAGE_1)
                        .getOwnerUserId());
        assertEquals(USER_0,
                mService.getUserShortcutsLocked(USER_0).getPackageShortcuts(CALLING_PACKAGE_1)
                        .getPackageUserId());
        assertEquals(USER_P0,
                mService.getUserShortcutsLocked(USER_P0).getPackageShortcuts(CALLING_PACKAGE_1)
                        .getOwnerUserId());
        assertEquals(USER_P0,
                mService.getUserShortcutsLocked(USER_P0).getPackageShortcuts(CALLING_PACKAGE_1)
                        .getPackageUserId());

        assertEquals(USER_0,
                mService.getUserShortcutsLocked(USER_0).getLauncherShortcuts(LAUNCHER_1, USER_0)
                        .getOwnerUserId());
        assertEquals(USER_0,
                mService.getUserShortcutsLocked(USER_0).getLauncherShortcuts(LAUNCHER_1, USER_0)
                        .getPackageUserId());
        assertEquals(USER_P0,
                mService.getUserShortcutsLocked(USER_P0).getLauncherShortcuts(LAUNCHER_1, USER_0)
                        .getOwnerUserId());
        assertEquals(USER_0,
                mService.getUserShortcutsLocked(USER_P0).getLauncherShortcuts(LAUNCHER_1, USER_0)
                        .getPackageUserId());
    }

    public void testOnApplicationActive_permission() {
        assertExpectException(SecurityException.class, "Missing permission", () ->
                mManager.onApplicationActive(CALLING_PACKAGE_1, USER_0));

        // Has permission, now it should pass.
        mCallerPermissions.add(permission.RESET_SHORTCUT_MANAGER_THROTTLING);
        mManager.onApplicationActive(CALLING_PACKAGE_1, USER_0);
    }

    public void testGetShareTargets_permission() {
        addPackage(CHOOSER_ACTIVITY_PACKAGE, CHOOSER_ACTIVITY_UID, 10, "sig1");
        mInjectedChooserActivity =
                ComponentName.createRelative(CHOOSER_ACTIVITY_PACKAGE, ".ChooserActivity");
        IntentFilter filter = new IntentFilter();

        assertExpectException(SecurityException.class, "Missing permission", () ->
                mManager.getShareTargets(filter));

        // Has permission, now it should pass.
        mCallerPermissions.add(permission.MANAGE_APP_PREDICTIONS);
        mManager.getShareTargets(filter);

        runWithCaller(CHOOSER_ACTIVITY_PACKAGE, USER_0, () -> {
            // Access is allowed when called from the configured system ChooserActivity
            mManager.getShareTargets(filter);
        });
    }

    public void testHasShareTargets_permission() {
        assertExpectException(SecurityException.class, "Missing permission", () ->
                mManager.hasShareTargets(CALLING_PACKAGE_1));

        // Has permission, now it should pass.
        mCallerPermissions.add(permission.MANAGE_APP_PREDICTIONS);
        mManager.hasShareTargets(CALLING_PACKAGE_1);
    }

    public void testisSharingShortcut_permission() throws IntentFilter.MalformedMimeTypeException {
        setCaller(LAUNCHER_1, USER_0);

        IntentFilter filter_any = new IntentFilter();
        filter_any.addDataType("*/*");

        assertExpectException(SecurityException.class, "Missing permission", () ->
                mInternal.isSharingShortcut(USER_0, LAUNCHER_1, CALLING_PACKAGE_1, "s1", USER_0,
                        filter_any));

        // Has permission, now it should pass.
        mCallerPermissions.add(permission.MANAGE_APP_PREDICTIONS);
        mManager.hasShareTargets(CALLING_PACKAGE_1);
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
        mInjectedCurrentTimeMillis += 100;
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
        mRunningUsers.put(USER_10, true);

        uninstallPackage(USER_10, CALLING_PACKAGE_1);
        uninstallPackage(USER_10, CALLING_PACKAGE_3);

        mInjectedCurrentTimeMillis += 100;
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

        mInjectedCurrentTimeMillis += 100;

        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                R.xml.shortcut_5);
        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_3, ShortcutActivity.class.getName()),
                R.xml.shortcut_1);

        initService();
        mService.handleUnlockUser(USER_0);

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertShortcutIds(assertAllManifest(assertAllImmutable(assertAllEnabled( // FAIL
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

        mInjectedCurrentTimeMillis += 100;

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

        mInjectedCurrentTimeMillis += 100;

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

        mRunningUsers.put(USER_10, true);
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
                R.xml.shortcut_5_altalt);
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
            assertWith(getCallerShortcuts()).selectManifest()
                    .selectByActivity(
                            new ComponentName(CALLING_PACKAGE_2, ShortcutActivity.class.getName()))
                    .haveRanksInOrder("ms1", "ms2", "ms3", "ms4", "ms5");
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

        dumpsysOnLogcat("After updating package 2");

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
            assertWith(getCallerShortcuts()).selectManifest()
                    .selectByActivity(
                            new ComponentName(CALLING_PACKAGE_2, ShortcutActivity.class.getName()))
                    .haveRanksInOrder("ms1", "ms2");
            assertShortcutIds(assertAllImmutable(assertAllPinned(
                    mManager.getPinnedShortcuts())),
                    "ms2", "ms3");
            // ms3 is no longer in manifest, so should be disabled.
            // but ms1 and ms2 should be enabled.
            assertWith(getCallerShortcuts())
                    .selectByIds("ms1", "ms2")
                    .areAllEnabled()

                    .revertToOriginalList()
                    .selectByIds("ms3")
                    .areAllDisabled()
                    .areAllWithDisabledReason(ShortcutInfo.DISABLED_REASON_APP_CHANGED);
        });

        // Make sure the launcher see the correct disabled reason.
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertWith(getShortcutAsLauncher(USER_0))
                    .forShortcutWithId("ms3", si -> {
                        assertEquals("string-com.android.test.2-user:0-res:"
                                        + R.string.shortcut_disabled_message3 + "/en",
                                si.getDisabledMessage());
                    });
        });


        // Package 2 on user 10 has no shortcuts yet.
        runWithCaller(CALLING_PACKAGE_2, USER_10, () -> {
            assertEmpty(mManager.getManifestShortcuts());
            assertEmpty(mManager.getPinnedShortcuts());
        });
        // Send add broadcast, but the user is not running, so should be ignored.
        mService.handleStopUser(USER_10);
        mRunningUsers.put(USER_10, false);
        mUnlockedUsers.put(USER_10, false);

        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageAddIntent(CALLING_PACKAGE_2, USER_10));
        runWithCaller(CALLING_PACKAGE_2, USER_10, () -> {
            // Don't use the mManager APIs to get shortcuts, because they'll trigger the package
            // update check.
            // So look the internal data directly using getCallerShortcuts().
            assertEmpty(getCallerShortcuts());
        });

        // Try again, but the user is locked, so still ignored.
        mRunningUsers.put(USER_10, true);
                mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageAddIntent(CALLING_PACKAGE_2, USER_10));
        runWithCaller(CALLING_PACKAGE_2, USER_10, () -> {
            // Don't use the mManager APIs to get shortcuts, because they'll trigger the package
            // update check.
            // So look the internal data directly using getCallerShortcuts().
            assertEmpty(getCallerShortcuts());
        });

        // Unlock the user, now it should work.
        mUnlockedUsers.put(USER_10, true);

        // Send PACKAGE_ADD broadcast to have Package 2 on user-10 publish manifest shortcuts.
                mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageAddIntent(CALLING_PACKAGE_2, USER_10));

        runWithCaller(CALLING_PACKAGE_2, USER_10, () -> {
            assertShortcutIds(assertAllManifest(assertAllImmutable(assertAllEnabled(
                    mManager.getManifestShortcuts()))),
                    "ms1", "ms2");
            assertWith(getCallerShortcuts()).selectManifest()
                    .selectByActivity(
                            new ComponentName(CALLING_PACKAGE_2, ShortcutActivity.class.getName()))
                    .haveRanksInOrder("ms1", "ms2");
            assertEmpty(mManager.getPinnedShortcuts());
        });

        // But it shouldn't affect user-0.
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertShortcutIds(assertAllManifest(assertAllImmutable(assertAllEnabled(
                    mManager.getManifestShortcuts()))),
                    "ms1", "ms2");
            assertWith(getCallerShortcuts()).selectManifest()
                    .selectByActivity(
                            new ComponentName(CALLING_PACKAGE_2, ShortcutActivity.class.getName()))
                    .haveRanksInOrder("ms1", "ms2");
            assertShortcutIds(assertAllImmutable(assertAllPinned(
                    mManager.getPinnedShortcuts())),
                    "ms2", "ms3");
            assertAllEnabled(list(getCallerShortcut("ms1")));
            assertAllEnabled(list(getCallerShortcut("ms2")));
            assertAllDisabled(list(getCallerShortcut("ms3")));
        });

        // Multiple activities.
        // Add shortcuts on activity 2 for package 2.
        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_2, ShortcutActivity.class.getName()),
                R.xml.shortcut_5_alt);
        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_2, ShortcutActivity2.class.getName()),
                R.xml.shortcut_5_reverse);

        updatePackageLastUpdateTime(CALLING_PACKAGE_2, 1);
                mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageAddIntent(CALLING_PACKAGE_2, USER_0));

        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertShortcutIds(assertAllManifest(assertAllImmutable(assertAllEnabled(
                    mManager.getManifestShortcuts()))),
                    "ms1", "ms2", "ms3", "ms4", "ms5",
                    "ms1_alt", "ms2_alt", "ms3_alt", "ms4_alt", "ms5_alt");

            // Make sure they have the correct ranks, regardless of their ID's alphabetical order.
            assertWith(getCallerShortcuts()).selectManifest()
                    .selectByActivity(
                            new ComponentName(CALLING_PACKAGE_2, ShortcutActivity.class.getName()))
                    .haveRanksInOrder("ms1_alt", "ms2_alt", "ms3_alt", "ms4_alt", "ms5_alt");
            assertWith(getCallerShortcuts()).selectManifest()
                    .selectByActivity(
                            new ComponentName(CALLING_PACKAGE_2, ShortcutActivity2.class.getName()))
                    .haveRanksInOrder("ms5", "ms4", "ms3", "ms2", "ms1");
        });

        // Package 2 now has no manifest shortcuts.
        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_2, ShortcutActivity.class.getName()),
                R.xml.shortcut_0);
        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_2, ShortcutActivity2.class.getName()),
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
            assertWith(getCallerShortcuts())
                    .areAllManifest()
                    .areAllImmutable()
                    .areAllEnabled()
                    .haveIds("x1");
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
            assertWith(getCallerShortcuts())
                    .areAllManifest()
                    .areAllImmutable()
                    .areAllEnabled()
                    .haveIds("x2");
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
            assertWith(getCallerShortcuts())
                    .areAllManifest()
                    .areAllImmutable()
                    .areAllEnabled()
                    .haveIds("x3")
                    .forShortcutWithId("x3", si -> {
                        assertEquals(set("cat2"), si.getCategories());
                     });
        });
    }

    public void testManifestShortcuts_intentDefinitions() {
        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                R.xml.shortcut_error_4);
        updatePackageVersion(CALLING_PACKAGE_1, 1);
                mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageAddIntent(CALLING_PACKAGE_1, USER_0));

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            // Make sure invalid ones are not published.
            // Note that at this point disabled ones don't show up because they weren't pinned.
            assertWith(getCallerShortcuts())
                    .haveIds("ms1", "ms2")
                    .areAllManifest()
                    .areAllNotDynamic()
                    .areAllNotPinned()
                    .areAllImmutable()
                    .areAllEnabled()
                    .forShortcutWithId("ms1", si -> {
                        assertTrue(si.isEnabled());
                        assertEquals(1, si.getIntents().length);

                        assertEquals("action1", si.getIntent().getAction());
                        assertEquals("value1", si.getIntent().getStringExtra("key1"));
                        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK |
                                Intent.FLAG_ACTIVITY_CLEAR_TASK |
                                Intent.FLAG_ACTIVITY_TASK_ON_HOME, si.getIntent().getFlags());

                        assertEquals("action1", si.getIntents()[0].getAction());
                        assertEquals("value1", si.getIntents()[0].getStringExtra("key1"));
                        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK |
                                Intent.FLAG_ACTIVITY_CLEAR_TASK |
                                Intent.FLAG_ACTIVITY_TASK_ON_HOME, si.getIntents()[0].getFlags());
                    })
                    .forShortcutWithId("ms2", si -> {
                        assertTrue(si.isEnabled());
                        assertEquals(2, si.getIntents().length);

                        // getIntent will return the last one.
                        assertEquals("action2_2", si.getIntent().getAction());
                        assertEquals("value2", si.getIntent().getStringExtra("key2"));
                        assertEquals(0, si.getIntent().getFlags());

                        final Intent i1 = si.getIntents()[0];
                        final Intent i2 = si.getIntents()[1];

                        assertEquals("action2_1", i1.getAction());
                        assertEquals("value1", i1.getStringExtra("key1"));
                        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK |
                                        Intent.FLAG_ACTIVITY_CLEAR_TASK |
                                        Intent.FLAG_ACTIVITY_TASK_ON_HOME, i1.getFlags());

                        assertEquals("action2_2", i2.getAction());
                        assertEquals("value2", i2.getStringExtra("key2"));
                        assertEquals(0, i2.getFlags());
                    });
        });

        // Publish 5 enabled to pin some, so we can later test disabled manfiest shortcuts..
        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                R.xml.shortcut_5);
        updatePackageVersion(CALLING_PACKAGE_1, 1);
                mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageAddIntent(CALLING_PACKAGE_1, USER_0));

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            // Make sure 5 manifest shortcuts are published.
            assertWith(getCallerShortcuts())
                    .haveIds("ms1", "ms2", "ms3", "ms4", "ms5")
                    .areAllManifest()
                    .areAllNotDynamic()
                    .areAllNotPinned()
                    .areAllImmutable()
                    .areAllEnabled();
        });

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                    list("ms3", "ms4", "ms5"), HANDLE_USER_0);
        });

        // Make sure they're pinned.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertWith(getCallerShortcuts())
                    .haveIds("ms1", "ms2", "ms3", "ms4", "ms5")
                    .selectByIds("ms1", "ms2")
                    .areAllNotPinned()
                    .areAllEnabled()

                    .revertToOriginalList()
                    .selectByIds("ms3", "ms4", "ms5")
                    .areAllPinned()
                    .areAllEnabled();
        });

        // Update the app.
        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                R.xml.shortcut_error_4);
        updatePackageVersion(CALLING_PACKAGE_1, 1);
                mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageAddIntent(CALLING_PACKAGE_1, USER_0));

        // Make sure 3, 4 and 5 still exist but disabled.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertWith(getCallerShortcuts())
                    .haveIds("ms1", "ms2", "ms3", "ms4", "ms5")
                    .areAllNotDynamic()
                    .areAllImmutable()

                    .selectByIds("ms1", "ms2")
                    .areAllManifest()
                    .areAllNotPinned()
                    .areAllEnabled()

                    .revertToOriginalList()
                    .selectByIds("ms3", "ms4", "ms5")
                    .areAllNotManifest()
                    .areAllPinned()
                    .areAllDisabled()

                    .revertToOriginalList()
                    .forShortcutWithId("ms1", si -> {
                        assertEquals(si.getId(), "action1", si.getIntent().getAction());
                    })
                    .forShortcutWithId("ms2", si -> {
                        // getIntent returns the last one.
                        assertEquals(si.getId(), "action2_2", si.getIntent().getAction());
                    })
                    .forShortcutWithId("ms3", si -> {
                        assertEquals(si.getId(), Intent.ACTION_VIEW, si.getIntent().getAction());
                    })
                    .forShortcutWithId("ms4", si -> {
                        assertEquals(si.getId(), Intent.ACTION_VIEW, si.getIntent().getAction());
                    })
                    .forShortcutWithId("ms5", si -> {
                        assertEquals(si.getId(), "action", si.getIntent().getAction());
                    });
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
            assertWith(getCallerShortcuts())
                    .haveIds("ms1", "ms2", "ms3", "ms4", "ms5")
                    .areAllManifest()
                    .areAllImmutable()
                    .areAllEnabled()
                    .areAllNotPinned()
                    .areAllNotDynamic()

                    .forShortcutWithId("ms1", si -> {
                        assertEquals(R.drawable.icon1, si.getIconResourceId());
                        assertEquals(new ComponentName(CALLING_PACKAGE_1,
                                ShortcutActivity.class.getName()),
                                si.getActivity());

                        assertEquals(R.string.shortcut_title1, si.getTitleResId());
                        assertEquals("r" + R.string.shortcut_title1, si.getTitleResName());
                        assertEquals(R.string.shortcut_text1, si.getTextResId());
                        assertEquals("r" + R.string.shortcut_text1, si.getTextResName());
                        assertEquals(R.string.shortcut_disabled_message1,
                                si.getDisabledMessageResourceId());
                        assertEquals("r" + R.string.shortcut_disabled_message1,
                                si.getDisabledMessageResName());

                        assertEquals(set("android.shortcut.conversation", "android.shortcut.media"),
                                si.getCategories());
                        assertEquals("action1", si.getIntent().getAction());
                        assertEquals(Uri.parse("http://a.b.c/1"), si.getIntent().getData());
                    })

                    .forShortcutWithId("ms2", si -> {
                        assertEquals("ms2", si.getId());
                        assertEquals(R.drawable.icon2, si.getIconResourceId());

                        assertEquals(R.string.shortcut_title2, si.getTitleResId());
                        assertEquals("r" + R.string.shortcut_title2, si.getTitleResName());
                        assertEquals(R.string.shortcut_text2, si.getTextResId());
                        assertEquals("r" + R.string.shortcut_text2, si.getTextResName());
                        assertEquals(R.string.shortcut_disabled_message2,
                                si.getDisabledMessageResourceId());
                        assertEquals("r" + R.string.shortcut_disabled_message2,
                                si.getDisabledMessageResName());

                        assertEquals(set("android.shortcut.conversation"), si.getCategories());
                        assertEquals("action2", si.getIntent().getAction());
                        assertEquals(null, si.getIntent().getData());
                    })

                    .forShortcutWithId("ms3", si -> {
                        assertEquals(0, si.getIconResourceId());
                        assertEquals(R.string.shortcut_title1, si.getTitleResId());
                        assertEquals("r" + R.string.shortcut_title1, si.getTitleResName());

                        assertEquals(0, si.getTextResId());
                        assertEquals(null, si.getTextResName());
                        assertEquals(0, si.getDisabledMessageResourceId());
                        assertEquals(null, si.getDisabledMessageResName());

                        assertEmpty(si.getCategories());
                        assertEquals("android.intent.action.VIEW", si.getIntent().getAction());
                        assertEquals(null, si.getIntent().getData());
                    })

                    .forShortcutWithId("ms4", si -> {
                        assertEquals(0, si.getIconResourceId());
                        assertEquals(R.string.shortcut_title2, si.getTitleResId());
                        assertEquals("r" + R.string.shortcut_title2, si.getTitleResName());

                        assertEquals(0, si.getTextResId());
                        assertEquals(null, si.getTextResName());
                        assertEquals(0, si.getDisabledMessageResourceId());
                        assertEquals(null, si.getDisabledMessageResName());

                        assertEquals(set("cat"), si.getCategories());
                        assertEquals("android.intent.action.VIEW2", si.getIntent().getAction());
                        assertEquals(null, si.getIntent().getData());
                    })

                    .forShortcutWithId("ms5", si -> {
                        si = getCallerShortcut("ms5");
                        assertEquals("action", si.getIntent().getAction());
                        assertEquals("http://www/", si.getIntent().getData().toString());
                        assertEquals("foo/bar", si.getIntent().getType());
                        assertEquals(
                                new ComponentName("abc", "abc.xyz"), si.getIntent().getComponent());

                        assertEquals(set("cat1", "cat2"), si.getIntent().getCategories());
                        assertEquals("value1", si.getIntent().getStringExtra("key1"));
                        assertEquals("value2", si.getIntent().getStringExtra("key2"));
                    });
        });
    }

    public void testManifestShortcuts_localeChange() throws InterruptedException {
        mService.handleUnlockUser(USER_0);

        // Package 1 updated, which has one valid manifest shortcut and one invalid.
        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                R.xml.shortcut_2);
        updatePackageVersion(CALLING_PACKAGE_1, 1);
                mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageAddIntent(CALLING_PACKAGE_1, USER_0));

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            mManager.setDynamicShortcuts(list(makeShortcutWithTitle("s1", "title")));

            assertShortcutIds(assertAllManifest(assertAllImmutable(assertAllEnabled(
                    mManager.getManifestShortcuts()))),
                    "ms1", "ms2");

            // check first shortcut.
            ShortcutInfo si = getCallerShortcut("ms1");

            assertEquals("ms1", si.getId());
            assertEquals("string-com.android.test.1-user:0-res:" + R.string.shortcut_title1 + "/en",
                    si.getTitle());
            assertEquals("string-com.android.test.1-user:0-res:" + R.string.shortcut_text1 + "/en",
                    si.getText());
            assertEquals("string-com.android.test.1-user:0-res:"
                            + R.string.shortcut_disabled_message1 + "/en",
                    si.getDisabledMessage());
            assertEquals(START_TIME, si.getLastChangedTimestamp());

            // check another
            si = getCallerShortcut("ms2");

            assertEquals("ms2", si.getId());
            assertEquals("string-com.android.test.1-user:0-res:" + R.string.shortcut_title2 + "/en",
                    si.getTitle());
            assertEquals("string-com.android.test.1-user:0-res:" + R.string.shortcut_text2 + "/en",
                    si.getText());
            assertEquals("string-com.android.test.1-user:0-res:"
                            + R.string.shortcut_disabled_message2 + "/en",
                    si.getDisabledMessage());
            assertEquals(START_TIME, si.getLastChangedTimestamp());

            // Check the dynamic one.
            si = getCallerShortcut("s1");

            assertEquals("s1", si.getId());
            assertEquals("title", si.getTitle());
            assertEquals(null, si.getText());
            assertEquals(null, si.getDisabledMessage());
            assertEquals(START_TIME, si.getLastChangedTimestamp());
        });

        mInjectedCurrentTimeMillis++;

        // Change the locale and send the broadcast, make sure the launcher gets a callback too.
        mInjectedLocale = Locale.JAPANESE;

        setCaller(LAUNCHER_1, USER_0);

        assertForLauncherCallback(mLauncherApps, () -> {
            mService.mReceiver.onReceive(mServiceContext, new Intent(Intent.ACTION_LOCALE_CHANGED));
        }).assertCallbackCalledForPackageAndUser(CALLING_PACKAGE_1, HANDLE_USER_0)
                .haveIds("ms1", "ms2", "s1");

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            // check first shortcut.
            ShortcutInfo si = getCallerShortcut("ms1");

            assertEquals("ms1", si.getId());
            assertEquals("string-com.android.test.1-user:0-res:" + R.string.shortcut_title1 + "/ja",
                    si.getTitle());
            assertEquals("string-com.android.test.1-user:0-res:" + R.string.shortcut_text1 + "/ja",
                    si.getText());
            assertEquals("string-com.android.test.1-user:0-res:"
                            + R.string.shortcut_disabled_message1 + "/ja",
                    si.getDisabledMessage());
            assertEquals(START_TIME + 1, si.getLastChangedTimestamp());

            // check another
            si = getCallerShortcut("ms2");

            assertEquals("ms2", si.getId());
            assertEquals("string-com.android.test.1-user:0-res:" + R.string.shortcut_title2 + "/ja",
                    si.getTitle());
            assertEquals("string-com.android.test.1-user:0-res:" + R.string.shortcut_text2 + "/ja",
                    si.getText());
            assertEquals("string-com.android.test.1-user:0-res:"
                            + R.string.shortcut_disabled_message2 + "/ja",
                    si.getDisabledMessage());
            assertEquals(START_TIME + 1, si.getLastChangedTimestamp());

            // Check the dynamic one.  (locale change shouldn't affect.)
            si = getCallerShortcut("s1");

            assertEquals("s1", si.getId());
            assertEquals("title", si.getTitle());
            assertEquals(null, si.getText());
            assertEquals(null, si.getDisabledMessage());
            assertEquals(START_TIME, si.getLastChangedTimestamp()); // Not changed.
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
                R.xml.shortcut_5);
        updatePackageVersion(CALLING_PACKAGE_1, 1);
                mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageAddIntent(CALLING_PACKAGE_1, USER_0));

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertShortcutIds(assertAllManifest(assertAllImmutable(assertAllEnabled(
                    mManager.getManifestShortcuts()))),
                    "ms1", "ms2", "ms3", "ms4", "ms5");

            // ms1 should belong to ShortcutActivity.
            ShortcutInfo si = getCallerShortcut("ms1");
            assertEquals(R.string.shortcut_title1, si.getTitleResId());
            assertEquals(new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                    si.getActivity());
            assertEquals(0, si.getRank());

            // ms2 should belong to ShortcutActivity*2*.
            si = getCallerShortcut("ms2");
            assertEquals(R.string.shortcut_title2, si.getTitleResId());
            assertEquals(new ComponentName(CALLING_PACKAGE_1, ShortcutActivity2.class.getName()),
                    si.getActivity());

            // Also check the ranks
            assertWith(getCallerShortcuts()).selectManifest()
                    .selectByActivity(
                            new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()))
                    .haveRanksInOrder("ms1");
            assertWith(getCallerShortcuts()).selectManifest()
                    .selectByActivity(
                            new ComponentName(CALLING_PACKAGE_1, ShortcutActivity2.class.getName()))
                    .haveRanksInOrder("ms2", "ms3", "ms4", "ms5");

            // Make sure there's no other dangling shortcuts.
            assertShortcutIds(getCallerShortcuts(), "ms1", "ms2", "ms3", "ms4", "ms5");
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

    public void testReturnedByServer() {
        // Package 1 updated, with manifest shortcuts.
        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                R.xml.shortcut_1);
        updatePackageVersion(CALLING_PACKAGE_1, 1);
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageAddIntent(CALLING_PACKAGE_1, USER_0));

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertWith(mManager.getManifestShortcuts())
                    .haveIds("ms1")
                    .forAllShortcuts(si -> assertTrue(si.isReturnedByServer()));

            assertTrue(mManager.setDynamicShortcuts(list(makeShortcut("s1"))));

            assertWith(mManager.getDynamicShortcuts())
                    .haveIds("s1")
                    .forAllShortcuts(si -> assertTrue(si.isReturnedByServer()));
        });

        // Pin them.
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                    list("ms1", "s1"), getCallingUser());
            assertWith(getShortcutAsLauncher(USER_0))
                    .haveIds("ms1", "s1")
                    .forAllShortcuts(si -> assertTrue(si.isReturnedByServer()));
        });

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertWith(mManager.getPinnedShortcuts())
                    .haveIds("ms1", "s1")
                    .forAllShortcuts(si -> assertTrue(si.isReturnedByServer()));
        });

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            // This shows a warning log, but should still work.
            assertTrue(mManager.setDynamicShortcuts(mManager.getDynamicShortcuts()));

            assertWith(mManager.getDynamicShortcuts())
                    .haveIds("s1")
                    .forAllShortcuts(si -> assertTrue(si.isReturnedByServer()));
        });
    }

    public void testIsForegroundDefaultLauncher_true() {
        final int uid = 1024;

        setDefaultLauncher(UserHandle.USER_SYSTEM, "default");
        makeUidForeground(uid);

        assertTrue(mInternal.isForegroundDefaultLauncher("default", uid));
    }


    public void testIsForegroundDefaultLauncher_defaultButNotForeground() {
        final int uid = 1024;

        setDefaultLauncher(UserHandle.USER_SYSTEM, "default");
        makeUidBackground(uid);

        assertFalse(mInternal.isForegroundDefaultLauncher("default", uid));
    }

    public void testIsForegroundDefaultLauncher_foregroundButNotDefault() {
        final int uid = 1024;

        setDefaultLauncher(UserHandle.USER_SYSTEM, "default");
        makeUidForeground(uid);

        assertFalse(mInternal.isForegroundDefaultLauncher("another", uid));
    }

    public void testParseShareTargetsFromManifest() {
        // These values must exactly match the content of shortcuts_share_targets.xml resource
        List<ShareTargetInfo> expectedValues = new ArrayList<>();
        expectedValues.add(new ShareTargetInfo(
                new ShareTargetInfo.TargetData[]{new ShareTargetInfo.TargetData(
                        "http", "www.google.com", "1234", "somePath", "somePathPattern",
                        "somePathPrefix", "text/plain")}, "com.test.directshare.TestActivity1",
                new String[]{"com.test.category.CATEGORY1", "com.test.category.CATEGORY2"}));
        expectedValues.add(new ShareTargetInfo(new ShareTargetInfo.TargetData[]{
                new ShareTargetInfo.TargetData(null, null, null, null, null, null, "video/mp4"),
                new ShareTargetInfo.TargetData("content", null, null, null, null, null, "video/*")},
                "com.test.directshare.TestActivity5",
                new String[]{"com.test.category.CATEGORY5", "com.test.category.CATEGORY6"}));

        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                R.xml.shortcut_share_targets);
        updatePackageVersion(CALLING_PACKAGE_1, 1);
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageAddIntent(CALLING_PACKAGE_1, USER_0));

        List<ShareTargetInfo> shareTargets = getCallerShareTargets();

        assertNotNull(shareTargets);
        assertEquals(expectedValues.size(), shareTargets.size());

        for (int i = 0; i < expectedValues.size(); i++) {
            ShareTargetInfo expected = expectedValues.get(i);
            ShareTargetInfo actual = shareTargets.get(i);

            assertEquals(expected.mTargetData.length, actual.mTargetData.length);
            for (int j = 0; j < expected.mTargetData.length; j++) {
                assertEquals(expected.mTargetData[j].mScheme, actual.mTargetData[j].mScheme);
                assertEquals(expected.mTargetData[j].mHost, actual.mTargetData[j].mHost);
                assertEquals(expected.mTargetData[j].mPort, actual.mTargetData[j].mPort);
                assertEquals(expected.mTargetData[j].mPath, actual.mTargetData[j].mPath);
                assertEquals(expected.mTargetData[j].mPathPrefix,
                        actual.mTargetData[j].mPathPrefix);
                assertEquals(expected.mTargetData[j].mPathPattern,
                        actual.mTargetData[j].mPathPattern);
                assertEquals(expected.mTargetData[j].mMimeType, actual.mTargetData[j].mMimeType);
            }

            assertEquals(expected.mTargetClass, actual.mTargetClass);

            assertEquals(expected.mCategories.length, actual.mCategories.length);
            for (int j = 0; j < expected.mCategories.length; j++) {
                assertEquals(expected.mCategories[j], actual.mCategories[j]);
            }
        }
    }

    public void testShareTargetInfo_saveToXml() throws IOException, XmlPullParserException {
        List<ShareTargetInfo> expectedValues = new ArrayList<>();
        expectedValues.add(new ShareTargetInfo(
                new ShareTargetInfo.TargetData[]{new ShareTargetInfo.TargetData(
                        "http", "www.google.com", "1234", "somePath", "somePathPattern",
                        "somePathPrefix", "text/plain")}, "com.test.directshare.TestActivity1",
                new String[]{"com.test.category.CATEGORY1", "com.test.category.CATEGORY2"}));
        expectedValues.add(new ShareTargetInfo(new ShareTargetInfo.TargetData[]{
                new ShareTargetInfo.TargetData(null, null, null, null, null, null, "video/mp4"),
                new ShareTargetInfo.TargetData("content", null, null, null, null, null, "video/*")},
                "com.test.directshare.TestActivity5",
                new String[]{"com.test.category.CATEGORY5", "com.test.category.CATEGORY6"}));

        // Write ShareTargets to Xml
        ByteArrayOutputStream outStream = new ByteArrayOutputStream();
        final TypedXmlSerializer outXml = Xml.newFastSerializer();
        outXml.setOutput(outStream, StandardCharsets.UTF_8.name());
        outXml.startDocument(null, true);
        for (int i = 0; i < expectedValues.size(); i++) {
            expectedValues.get(i).saveToXml(outXml);
        }
        outXml.endDocument();
        outXml.flush();

        // Read ShareTargets from Xml
        ByteArrayInputStream inStream = new ByteArrayInputStream(outStream.toByteArray());
        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new InputStreamReader(inStream));
        List<ShareTargetInfo> shareTargets = new ArrayList<>();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
            if (type == XmlPullParser.START_TAG && parser.getName().equals("share-target")) {
                shareTargets.add(ShareTargetInfo.loadFromXml(parser));
            }
        }

        // Assert two lists are equal
        assertNotNull(shareTargets);
        assertEquals(expectedValues.size(), shareTargets.size());

        for (int i = 0; i < expectedValues.size(); i++) {
            ShareTargetInfo expected = expectedValues.get(i);
            ShareTargetInfo actual = shareTargets.get(i);

            assertEquals(expected.mTargetData.length, actual.mTargetData.length);
            for (int j = 0; j < expected.mTargetData.length; j++) {
                assertEquals(expected.mTargetData[j].mScheme, actual.mTargetData[j].mScheme);
                assertEquals(expected.mTargetData[j].mHost, actual.mTargetData[j].mHost);
                assertEquals(expected.mTargetData[j].mPort, actual.mTargetData[j].mPort);
                assertEquals(expected.mTargetData[j].mPath, actual.mTargetData[j].mPath);
                assertEquals(expected.mTargetData[j].mPathPrefix,
                        actual.mTargetData[j].mPathPrefix);
                assertEquals(expected.mTargetData[j].mPathPattern,
                        actual.mTargetData[j].mPathPattern);
                assertEquals(expected.mTargetData[j].mMimeType, actual.mTargetData[j].mMimeType);
            }

            assertEquals(expected.mTargetClass, actual.mTargetClass);

            assertEquals(expected.mCategories.length, actual.mCategories.length);
            for (int j = 0; j < expected.mCategories.length; j++) {
                assertEquals(expected.mCategories[j], actual.mCategories[j]);
            }
        }
    }

    public void testIsSharingShortcut() throws IntentFilter.MalformedMimeTypeException {
        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                R.xml.shortcut_share_targets);
        updatePackageVersion(CALLING_PACKAGE_1, 1);
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageAddIntent(CALLING_PACKAGE_1, USER_0));

        setCaller(CALLING_PACKAGE_1, USER_0);

        final ShortcutInfo s1 = makeShortcutWithCategory("s1",
                set("com.test.category.CATEGORY1", "com.test.category.CATEGORY2"));
        final ShortcutInfo s2 = makeShortcutWithCategory("s2",
                set("com.test.category.CATEGORY5", "com.test.category.CATEGORY6"));
        final ShortcutInfo s3 = makeShortcut("s3");

        assertTrue(mManager.setDynamicShortcuts(list(s1, s2, s3)));
        assertShortcutIds(assertAllNotKeyFieldsOnly(mManager.getDynamicShortcuts()),
                "s1", "s2", "s3");

        IntentFilter filter_cat1 = new IntentFilter();
        filter_cat1.addDataType("text/plain");
        IntentFilter filter_cat5 = new IntentFilter();
        filter_cat5.addDataType("video/*");
        IntentFilter filter_any = new IntentFilter();
        filter_any.addDataType("*/*");

        setCaller(LAUNCHER_1, USER_0);
        mCallerPermissions.add(permission.MANAGE_APP_PREDICTIONS);

        assertTrue(mInternal.isSharingShortcut(USER_0, LAUNCHER_1, CALLING_PACKAGE_1, "s1", USER_0,
                filter_cat1));
        assertFalse(mInternal.isSharingShortcut(USER_0, LAUNCHER_1, CALLING_PACKAGE_1, "s1", USER_0,
                filter_cat5));
        assertTrue(mInternal.isSharingShortcut(USER_0, LAUNCHER_1, CALLING_PACKAGE_1, "s1", USER_0,
                filter_any));

        assertFalse(mInternal.isSharingShortcut(USER_0, LAUNCHER_1, CALLING_PACKAGE_1, "s2", USER_0,
                filter_cat1));
        assertTrue(mInternal.isSharingShortcut(USER_0, LAUNCHER_1, CALLING_PACKAGE_1, "s2", USER_0,
                filter_cat5));
        assertTrue(mInternal.isSharingShortcut(USER_0, LAUNCHER_1, CALLING_PACKAGE_1, "s2", USER_0,
                filter_any));

        assertFalse(mInternal.isSharingShortcut(USER_0, LAUNCHER_1, CALLING_PACKAGE_1, "s3", USER_0,
                filter_any));
        assertFalse(mInternal.isSharingShortcut(USER_0, LAUNCHER_1, CALLING_PACKAGE_1, "s4", USER_0,
                filter_any));
    }

    public void testIsSharingShortcut_PinnedAndCachedOnlyShortcuts()
            throws IntentFilter.MalformedMimeTypeException {
        addManifestShortcutResource(
                new ComponentName(CALLING_PACKAGE_1, ShortcutActivity.class.getName()),
                R.xml.shortcut_share_targets);
        updatePackageVersion(CALLING_PACKAGE_1, 1);
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageAddIntent(CALLING_PACKAGE_1, USER_0));

        final ShortcutInfo s1 = makeShortcutWithCategory("s1",
                set("com.test.category.CATEGORY1", "com.test.category.CATEGORY2"));
        final ShortcutInfo s2 = makeShortcutWithCategory("s2",
                set("com.test.category.CATEGORY5", "com.test.category.CATEGORY6"));
        final ShortcutInfo s3 = makeShortcutWithCategory("s3",
                set("com.test.category.CATEGORY5", "com.test.category.CATEGORY6"));
        s1.setLongLived();
        s2.setLongLived();

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(s1, s2, s3)));
            assertShortcutIds(assertAllNotKeyFieldsOnly(mManager.getDynamicShortcuts()),
                    "s1", "s2", "s3");
        });

        IntentFilter filter_any = new IntentFilter();
        filter_any.addDataType("*/*");

        setCaller(LAUNCHER_1, USER_0);
        mCallerPermissions.add(permission.MANAGE_APP_PREDICTIONS);

        // Assert all are sharing shortcuts
        assertTrue(mInternal.isSharingShortcut(USER_0, LAUNCHER_1, CALLING_PACKAGE_1, "s1", USER_0,
                filter_any));
        assertTrue(mInternal.isSharingShortcut(USER_0, LAUNCHER_1, CALLING_PACKAGE_1, "s2", USER_0,
                filter_any));
        assertTrue(mInternal.isSharingShortcut(USER_0, LAUNCHER_1, CALLING_PACKAGE_1, "s3", USER_0,
                filter_any));

        mInjectCheckAccessShortcutsPermission = true;
        mLauncherApps.cacheShortcuts(CALLING_PACKAGE_1, list("s1", "s2"), HANDLE_USER_0,
                CACHE_OWNER_0);
        mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("s3"), HANDLE_USER_0);

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            // Remove one cached shortcut, and leave one cached-only and pinned-only shortcuts.
            mManager.removeLongLivedShortcuts(list("s1"));
            mManager.removeDynamicShortcuts(list("s2, s3"));
        });

        assertFalse(mInternal.isSharingShortcut(USER_0, LAUNCHER_1, CALLING_PACKAGE_1, "s1", USER_0,
                filter_any));
        assertTrue(mInternal.isSharingShortcut(USER_0, LAUNCHER_1, CALLING_PACKAGE_1, "s2", USER_0,
                filter_any));
        assertTrue(mInternal.isSharingShortcut(USER_0, LAUNCHER_1, CALLING_PACKAGE_1, "s3", USER_0,
                filter_any));
    }

    public void testAddingShortcuts_ExcludesHiddenFromLauncherShortcuts() {
        final ShortcutInfo s1 = makeShortcutExcludedFromLauncher("s1");
        final ShortcutInfo s2 = makeShortcutExcludedFromLauncher("s2");
        final ShortcutInfo s3 = makeShortcutExcludedFromLauncher("s3");

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(s1, s2, s3)));
            assertEmpty(mManager.getDynamicShortcuts());
        });

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.addDynamicShortcuts(list(s1, s2, s3)));
            assertEmpty(mManager.getDynamicShortcuts());
        });

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            mManager.pushDynamicShortcut(s1);
            assertEmpty(mManager.getDynamicShortcuts());
        });
    }

    public void testUpdateShortcuts_ExcludesHiddenFromLauncherShortcuts() {
        final ShortcutInfo s1 = makeShortcut("s1");
        final ShortcutInfo s2 = makeShortcut("s2");
        final ShortcutInfo s3 = makeShortcut("s3");

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(s1, s2, s3)));
            assertThrown(IllegalArgumentException.class, () -> {
                mManager.updateShortcuts(list(makeShortcutExcludedFromLauncher("s1")));
            });
        });
    }

    public void testPinHiddenShortcuts_ThrowsException() {
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertThrown(IllegalArgumentException.class, () -> {
                mManager.requestPinShortcut(makeShortcutExcludedFromLauncher("s1"), null);
            });
        });
    }

    private Uri getFileUriFromResource(String fileName, int resId) throws IOException {
        File file = new File(getTestContext().getFilesDir(), fileName);
        // Make sure we are not leaving phantom files behind.
        assertFalse(file.exists());
        try (InputStream source = getTestContext().getResources().openRawResource(resId);
             OutputStream target = new FileOutputStream(file)) {
            byte[] buffer = new byte[1024];
            for (int len = source.read(buffer); len >= 0; len = source.read(buffer)) {
                target.write(buffer, 0, len);
            }
        }
        assertTrue(file.exists());
        return Uri.fromFile(file);
    }

    private void deleteUriFile(String fileName) {
        File file = new File(getTestContext().getFilesDir(), fileName);
        if (file.exists()) {
            file.delete();
        }
    }
}
