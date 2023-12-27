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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.platform.test.annotations.Presubmit;
import android.util.ArraySet;

import androidx.test.filters.SmallTest;

import com.android.server.wm.SensitiveContentPackages.PackageInfo;

import org.junit.After;
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
    private static final int APP_UID_3 = 7;


    private final SensitiveContentPackages mSensitiveContentPackages =
            new SensitiveContentPackages();

    @After
    public void tearDown() {
        mSensitiveContentPackages.clearBlockedApps();
    }

    @Test
    public void addBlockScreenCaptureForApps() {
        ArraySet<PackageInfo> blockedApps = new ArraySet(
                Set.of(new PackageInfo(APP_PKG_1, APP_UID_1),
                        new PackageInfo(APP_PKG_1, APP_UID_2),
                        new PackageInfo(APP_PKG_2, APP_UID_1),
                        new PackageInfo(APP_PKG_2, APP_UID_2)
                ));

        boolean modified = mSensitiveContentPackages.addBlockScreenCaptureForApps(blockedApps);

        assertTrue(modified);

        assertTrue(mSensitiveContentPackages.shouldBlockScreenCaptureForApp(APP_PKG_1, APP_UID_1));
        assertTrue(mSensitiveContentPackages.shouldBlockScreenCaptureForApp(APP_PKG_1, APP_UID_2));
        assertFalse(mSensitiveContentPackages.shouldBlockScreenCaptureForApp(APP_PKG_1, APP_UID_3));

        assertTrue(mSensitiveContentPackages.shouldBlockScreenCaptureForApp(APP_PKG_2, APP_UID_1));
        assertTrue(mSensitiveContentPackages.shouldBlockScreenCaptureForApp(APP_PKG_2, APP_UID_2));
        assertFalse(mSensitiveContentPackages.shouldBlockScreenCaptureForApp(APP_PKG_2, APP_UID_3));

        assertFalse(mSensitiveContentPackages.shouldBlockScreenCaptureForApp(APP_PKG_3, APP_UID_1));
        assertFalse(mSensitiveContentPackages.shouldBlockScreenCaptureForApp(APP_PKG_3, APP_UID_2));
        assertFalse(mSensitiveContentPackages.shouldBlockScreenCaptureForApp(APP_PKG_3, APP_UID_3));
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

        assertTrue(mSensitiveContentPackages.shouldBlockScreenCaptureForApp(APP_PKG_1, APP_UID_1));
        assertTrue(mSensitiveContentPackages.shouldBlockScreenCaptureForApp(APP_PKG_1, APP_UID_2));
        assertFalse(mSensitiveContentPackages.shouldBlockScreenCaptureForApp(APP_PKG_1, APP_UID_3));

        assertTrue(mSensitiveContentPackages.shouldBlockScreenCaptureForApp(APP_PKG_2, APP_UID_1));
        assertTrue(mSensitiveContentPackages.shouldBlockScreenCaptureForApp(APP_PKG_2, APP_UID_2));
        assertFalse(mSensitiveContentPackages.shouldBlockScreenCaptureForApp(APP_PKG_2, APP_UID_3));

        assertFalse(mSensitiveContentPackages.shouldBlockScreenCaptureForApp(APP_PKG_3, APP_UID_1));
        assertFalse(mSensitiveContentPackages.shouldBlockScreenCaptureForApp(APP_PKG_3, APP_UID_2));
        assertFalse(mSensitiveContentPackages.shouldBlockScreenCaptureForApp(APP_PKG_3, APP_UID_3));
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

        assertTrue(mSensitiveContentPackages.shouldBlockScreenCaptureForApp(APP_PKG_1, APP_UID_1));
        assertTrue(mSensitiveContentPackages.shouldBlockScreenCaptureForApp(APP_PKG_1, APP_UID_2));
        assertFalse(mSensitiveContentPackages.shouldBlockScreenCaptureForApp(APP_PKG_1, APP_UID_3));

        assertTrue(mSensitiveContentPackages.shouldBlockScreenCaptureForApp(APP_PKG_2, APP_UID_1));
        assertTrue(mSensitiveContentPackages.shouldBlockScreenCaptureForApp(APP_PKG_2, APP_UID_2));
        assertFalse(mSensitiveContentPackages.shouldBlockScreenCaptureForApp(APP_PKG_2, APP_UID_3));

        assertTrue(mSensitiveContentPackages.shouldBlockScreenCaptureForApp(APP_PKG_3, APP_UID_1));
        assertFalse(mSensitiveContentPackages.shouldBlockScreenCaptureForApp(APP_PKG_3, APP_UID_2));
        assertFalse(mSensitiveContentPackages.shouldBlockScreenCaptureForApp(APP_PKG_3, APP_UID_3));
    }

    @Test
    public void clearBlockedApps() {
        ArraySet<PackageInfo> blockedApps = new ArraySet(
                Set.of(new PackageInfo(APP_PKG_1, APP_UID_1),
                        new PackageInfo(APP_PKG_1, APP_UID_2),
                        new PackageInfo(APP_PKG_2, APP_UID_1),
                        new PackageInfo(APP_PKG_2, APP_UID_2)
                ));

        mSensitiveContentPackages.addBlockScreenCaptureForApps(blockedApps);
        boolean modified = mSensitiveContentPackages.clearBlockedApps();

        assertTrue(modified);

        assertFalse(mSensitiveContentPackages.shouldBlockScreenCaptureForApp(APP_PKG_1, APP_UID_1));
        assertFalse(mSensitiveContentPackages.shouldBlockScreenCaptureForApp(APP_PKG_1, APP_UID_2));
        assertFalse(mSensitiveContentPackages.shouldBlockScreenCaptureForApp(APP_PKG_1, APP_UID_3));

        assertFalse(mSensitiveContentPackages.shouldBlockScreenCaptureForApp(APP_PKG_2, APP_UID_1));
        assertFalse(mSensitiveContentPackages.shouldBlockScreenCaptureForApp(APP_PKG_2, APP_UID_2));
        assertFalse(mSensitiveContentPackages.shouldBlockScreenCaptureForApp(APP_PKG_2, APP_UID_3));

        assertFalse(mSensitiveContentPackages.shouldBlockScreenCaptureForApp(APP_PKG_3, APP_UID_1));
        assertFalse(mSensitiveContentPackages.shouldBlockScreenCaptureForApp(APP_PKG_3, APP_UID_2));
        assertFalse(mSensitiveContentPackages.shouldBlockScreenCaptureForApp(APP_PKG_3, APP_UID_3));
    }

    @Test
    public void clearBlockedApps_alreadyEmpty() {
        boolean modified = mSensitiveContentPackages.clearBlockedApps();

        assertFalse(modified);

        assertFalse(mSensitiveContentPackages.shouldBlockScreenCaptureForApp(APP_PKG_1, APP_UID_1));
        assertFalse(mSensitiveContentPackages.shouldBlockScreenCaptureForApp(APP_PKG_1, APP_UID_2));
        assertFalse(mSensitiveContentPackages.shouldBlockScreenCaptureForApp(APP_PKG_1, APP_UID_3));

        assertFalse(mSensitiveContentPackages.shouldBlockScreenCaptureForApp(APP_PKG_2, APP_UID_1));
        assertFalse(mSensitiveContentPackages.shouldBlockScreenCaptureForApp(APP_PKG_2, APP_UID_2));
        assertFalse(mSensitiveContentPackages.shouldBlockScreenCaptureForApp(APP_PKG_2, APP_UID_3));

        assertFalse(mSensitiveContentPackages.shouldBlockScreenCaptureForApp(APP_PKG_3, APP_UID_1));
        assertFalse(mSensitiveContentPackages.shouldBlockScreenCaptureForApp(APP_PKG_3, APP_UID_2));
        assertFalse(mSensitiveContentPackages.shouldBlockScreenCaptureForApp(APP_PKG_3, APP_UID_3));
    }
}
