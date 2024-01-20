/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.server.wm.CompatScaleProvider.COMPAT_SCALE_MODE_GAME;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;

import android.app.GameManagerInternal;
import android.content.pm.ApplicationInfo;
import android.content.res.CompatibilityInfo.CompatScale;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import com.android.server.LocalServices;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the {@link CompatModePackages} class.
 *
 * Build/Install/Run:
 * atest WmTests:CompatModePackagesTests
 */
@SmallTest
@Presubmit
public class CompatModePackagesTests extends SystemServiceTestsBase {
    ActivityTaskManagerService mAtm;
    GameManagerInternal mGm;
    static final String TEST_PACKAGE = "compat.mode.packages";
    static final int TEST_USER_ID = 1;

    @Before
    public void setUp() {
        mAtm = mSystemServicesTestRule.getActivityTaskManagerService();
        mGm = mock(GameManagerInternal.class);
        mAtm.registerCompatScaleProvider(COMPAT_SCALE_MODE_GAME, new CompatScaleProvider() {
            @Override
            public CompatScale getCompatScale(String packageName, int uid) {
                int userId = UserHandle.getUserHandleForUid(uid).getIdentifier();
                float scalingFactor = mGm.getResolutionScalingFactor(packageName, userId);
                if (scalingFactor > 0) {
                    return new CompatScale(1f / scalingFactor);
                }
                return null;
            }
        });
    }

    @After
    public void tearDown() {
        LocalServices.removeServiceForTest(GameManagerInternal.class);
    }

    @Test
    public void testGetCompatScale_gameManagerReturnsPositive() {
        LocalServices.addService(GameManagerInternal.class, mGm);
        float scale = 0.25f;
        doReturn(scale).when(mGm).getResolutionScalingFactor(anyString(), anyInt());
        assertEquals(1 / scale, mAtm.mCompatModePackages.getCompatScale(TEST_PACKAGE, TEST_USER_ID),
                0.01f);
    }

    @Test
    public void testGetCompatScale_gameManagerReturnsZero() {
        LocalServices.addService(GameManagerInternal.class, mGm);
        float scale = 0f;
        doReturn(scale).when(mGm).getResolutionScalingFactor(anyString(), anyInt());
        assertEquals(mAtm.mCompatModePackages.getCompatScale(TEST_PACKAGE, TEST_USER_ID), 1f,
                0.01f);
    }

    @Test
    public void testGetCompatScale_gameManagerReturnsNegative() {
        LocalServices.addService(GameManagerInternal.class, mGm);
        float scale = -1f;
        doReturn(scale).when(mGm).getResolutionScalingFactor(anyString(), anyInt());
        assertEquals(mAtm.mCompatModePackages.getCompatScale(TEST_PACKAGE, TEST_USER_ID), 1f,
                0.01f);
    }

    @Test
    public void testGetCompatScale_noGameManager() {
        assertEquals(mAtm.mCompatModePackages.getCompatScale(TEST_PACKAGE, TEST_USER_ID), 1f,
                0.01f);

        final ApplicationInfo info = new ApplicationInfo();
        // Any non-zero value without FLAG_SUPPORTS_*_SCREENS.
        info.flags = ApplicationInfo.FLAG_HAS_CODE;
        info.packageName = info.sourceDir = "legacy.app";
        mAtm.mCompatModePackages.compatibilityInfoForPackageLocked(info);
        assertTrue(mAtm.mCompatModePackages.useLegacyScreenCompatMode(info.packageName));
        mAtm.mCompatModePackages.handlePackageUninstalledLocked(info.packageName);
        assertFalse(mAtm.mCompatModePackages.useLegacyScreenCompatMode(info.packageName));
    }
}
