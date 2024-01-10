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

import androidx.test.filters.SmallTest;

import com.android.server.wm.SensitiveContentPackages.PackageInfo;

import org.junit.After;
import org.junit.Test;

import java.util.Collections;
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
        mSensitiveContentPackages.setShouldBlockScreenCaptureForApp(Collections.emptySet());
    }

    @Test
    public void setShouldBlockScreenCaptureForApp() {
        Set<PackageInfo> blockedApps =
                Set.of(new PackageInfo(APP_PKG_1, APP_UID_1),
                        new PackageInfo(APP_PKG_1, APP_UID_2),
                        new PackageInfo(APP_PKG_2, APP_UID_1),
                        new PackageInfo(APP_PKG_2, APP_UID_2));

        mSensitiveContentPackages.setShouldBlockScreenCaptureForApp(blockedApps);

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
    public void setShouldBlockScreenCaptureForApp_empty() {
        Set<PackageInfo> blockedApps =
                Set.of(new PackageInfo(APP_PKG_1, APP_UID_1),
                        new PackageInfo(APP_PKG_1, APP_UID_2),
                        new PackageInfo(APP_PKG_2, APP_UID_1),
                        new PackageInfo(APP_PKG_2, APP_UID_2));

        mSensitiveContentPackages.setShouldBlockScreenCaptureForApp(blockedApps);
        mSensitiveContentPackages.setShouldBlockScreenCaptureForApp(Collections.emptySet());

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
