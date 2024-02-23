/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.wm;

import static android.permission.flags.Flags.FLAG_SENSITIVE_NOTIFICATION_APP_PROTECTION;
import static android.view.flags.Flags.FLAG_SENSITIVE_CONTENT_APP_PROTECTION;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Binder;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.util.ArraySet;

import androidx.test.filters.SmallTest;

import com.android.server.wm.SensitiveContentPackages.PackageInfo;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

import java.util.Set;

/**
 * Build/Install/Run:
 *  atest WmTests:SensitiveContentPackagesTest
 */
@SmallTest
@Presubmit
public class SensitiveContentPackagesTest {
    private static final String APP_PKG_1 = "com.android.server.wm.one";
    private static final String APP_PKG_2 = "com.android.server.wm.two";
    private static final String APP_PKG_3 = "com.android.server.wm.three";

    private static final int APP_UID_1 = 5;
    private static final int APP_UID_2 = 6;

    private static final IBinder WINDOW_TOKEN_1 = new Binder();
    private static final IBinder WINDOW_TOKEN_2 = new Binder();

    private final SensitiveContentPackages mSensitiveContentPackages =
            new SensitiveContentPackages();

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @After
    public void tearDown() {
        mSensitiveContentPackages.clearBlockedApps();
    }

    private boolean shouldBlockScreenCaptureForApp(String pkg, int uid, IBinder windowToken) {
        return mSensitiveContentPackages.shouldBlockScreenCaptureForApp(pkg, uid, windowToken);
    }

    @Test
    @RequiresFlagsEnabled(FLAG_SENSITIVE_NOTIFICATION_APP_PROTECTION)
    public void shouldBlockScreenCaptureForNotificationApps() {
        ArraySet<PackageInfo> blockedApps = new ArraySet(
                Set.of(new PackageInfo(APP_PKG_1, APP_UID_1),
                        new PackageInfo(APP_PKG_2, APP_UID_2)
                ));

        boolean modified = mSensitiveContentPackages.addBlockScreenCaptureForApps(blockedApps);

        assertTrue(modified);

        assertTrue(shouldBlockScreenCaptureForApp(APP_PKG_1, APP_UID_1, WINDOW_TOKEN_1));
        assertTrue(shouldBlockScreenCaptureForApp(APP_PKG_1, APP_UID_1, WINDOW_TOKEN_2));
        assertFalse(shouldBlockScreenCaptureForApp(APP_PKG_1, APP_UID_2, WINDOW_TOKEN_2));
        assertTrue(shouldBlockScreenCaptureForApp(APP_PKG_2, APP_UID_2, WINDOW_TOKEN_1));
        assertTrue(shouldBlockScreenCaptureForApp(APP_PKG_2, APP_UID_2, WINDOW_TOKEN_2));
        assertFalse(shouldBlockScreenCaptureForApp(APP_PKG_2, APP_UID_1, WINDOW_TOKEN_2));
    }

    @Test
    @RequiresFlagsEnabled(FLAG_SENSITIVE_CONTENT_APP_PROTECTION)
    public void shouldBlockScreenCaptureForSensitiveContentOnScreenApps() {
        ArraySet<PackageInfo> blockedApps = new ArraySet(
                Set.of(new PackageInfo(APP_PKG_1, APP_UID_1, WINDOW_TOKEN_1),
                        new PackageInfo(APP_PKG_2, APP_UID_2, WINDOW_TOKEN_2)
                ));

        boolean modified = mSensitiveContentPackages.addBlockScreenCaptureForApps(blockedApps);
        assertTrue(modified);

        assertTrue(shouldBlockScreenCaptureForApp(APP_PKG_1, APP_UID_1, WINDOW_TOKEN_1));
        assertFalse(shouldBlockScreenCaptureForApp(APP_PKG_1, APP_UID_1, WINDOW_TOKEN_2));

        assertTrue(shouldBlockScreenCaptureForApp(APP_PKG_2, APP_UID_2, WINDOW_TOKEN_2));
        assertFalse(shouldBlockScreenCaptureForApp(APP_PKG_2, APP_UID_2, WINDOW_TOKEN_1));
    }

    @Test
    @RequiresFlagsEnabled(
            {FLAG_SENSITIVE_CONTENT_APP_PROTECTION, FLAG_SENSITIVE_NOTIFICATION_APP_PROTECTION})
    public void shouldBlockScreenCaptureForApps() {
        ArraySet<PackageInfo> blockedApps = new ArraySet(
                Set.of(new PackageInfo(APP_PKG_1, APP_UID_1, WINDOW_TOKEN_1),
                        new PackageInfo(APP_PKG_1, APP_UID_1),
                        new PackageInfo(APP_PKG_2, APP_UID_2, WINDOW_TOKEN_2)
                ));

        boolean modified = mSensitiveContentPackages.addBlockScreenCaptureForApps(blockedApps);
        assertTrue(modified);

        assertTrue(shouldBlockScreenCaptureForApp(APP_PKG_1, APP_UID_1, WINDOW_TOKEN_1));
        assertTrue(shouldBlockScreenCaptureForApp(APP_PKG_1, APP_UID_1, WINDOW_TOKEN_2));

        assertTrue(shouldBlockScreenCaptureForApp(APP_PKG_2, APP_UID_2, WINDOW_TOKEN_2));
        assertFalse(shouldBlockScreenCaptureForApp(APP_PKG_2, APP_UID_2, WINDOW_TOKEN_1));
    }

    @Test
    public void addBlockScreenCaptureForApps_addedTwice() {
        ArraySet<PackageInfo> blockedApps = new ArraySet(
                Set.of(new PackageInfo(APP_PKG_1, APP_UID_1),
                        new PackageInfo(APP_PKG_1, APP_UID_2),
                        new PackageInfo(APP_PKG_2, APP_UID_1),
                        new PackageInfo(APP_PKG_2, APP_UID_2)
                ));

        mSensitiveContentPackages.addBlockScreenCaptureForApps(blockedApps);
        boolean modified = mSensitiveContentPackages.addBlockScreenCaptureForApps(blockedApps);
        assertFalse(modified);
        assertTrue(mSensitiveContentPackages.size() == 4);
    }

    @Test
    public void addBlockScreenCaptureForApps_withPartialPreviousPackages() {
        ArraySet<PackageInfo> blockedApps = new ArraySet(
                Set.of(new PackageInfo(APP_PKG_1, APP_UID_1),
                        new PackageInfo(APP_PKG_1, APP_UID_2),
                        new PackageInfo(APP_PKG_2, APP_UID_1),
                        new PackageInfo(APP_PKG_2, APP_UID_2)
                ));

        mSensitiveContentPackages.addBlockScreenCaptureForApps(blockedApps);
        boolean modified = mSensitiveContentPackages
                .addBlockScreenCaptureForApps(
                        new ArraySet(Set.of(new PackageInfo(APP_PKG_3, APP_UID_1))));
        assertTrue(modified);
        assertTrue(mSensitiveContentPackages.size() == 5);
    }

    @Test
    public void clearBlockedApps() {
        ArraySet<PackageInfo> blockedApps = new ArraySet(
                Set.of(new PackageInfo(APP_PKG_1, APP_UID_1),
                        new PackageInfo(APP_PKG_2, APP_UID_2, WINDOW_TOKEN_2)
                ));

        mSensitiveContentPackages.addBlockScreenCaptureForApps(blockedApps);
        boolean modified = mSensitiveContentPackages.clearBlockedApps();

        assertTrue(modified);
        assertTrue(mSensitiveContentPackages.size() == 0);
    }

    @Test
    public void clearBlockedApps_alreadyEmpty() {
        boolean modified = mSensitiveContentPackages.clearBlockedApps();
        assertFalse(modified);
        assertTrue(mSensitiveContentPackages.size() == 0);
    }
}
