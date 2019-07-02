/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.platform.test.annotations.Presubmit;
import android.view.Display.Mode;
import android.view.DisplayInfo;

import androidx.test.filters.FlakyTest;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;

/**
 * Build/Install/Run:
 *  atest WmTests:RefreshRatePolicyTest
 */
@SmallTest
@Presubmit
@FlakyTest
public class RefreshRatePolicyTest extends WindowTestsBase {

    private static final int LOW_MODE_ID = 3;

    private RefreshRatePolicy mPolicy;
    private HighRefreshRateBlacklist mBlacklist = mock(HighRefreshRateBlacklist.class);

    @Before
    public void setUp() {
        DisplayInfo di = new DisplayInfo(mDisplayInfo);
        Mode defaultMode = di.getDefaultMode();
        di.supportedModes = new Mode[] {
                new Mode(1, defaultMode.getPhysicalWidth(), defaultMode.getPhysicalHeight(), 90),
                new Mode(2, defaultMode.getPhysicalWidth(), defaultMode.getPhysicalHeight(), 70),
                new Mode(LOW_MODE_ID,
                        defaultMode.getPhysicalWidth(), defaultMode.getPhysicalHeight(), 60),
        };
        di.defaultModeId = 1;
        mPolicy = new RefreshRatePolicy(mWm, di, mBlacklist);
    }

    @Test
    public void testCamera() {
        final WindowState cameraUsingWindow = createWindow(null, TYPE_BASE_APPLICATION,
                "cameraUsingWindow");
        cameraUsingWindow.mAttrs.packageName = "com.android.test";
        assertEquals(0, mPolicy.getPreferredModeId(cameraUsingWindow));
        mPolicy.addNonHighRefreshRatePackage("com.android.test");
        assertEquals(LOW_MODE_ID, mPolicy.getPreferredModeId(cameraUsingWindow));
        mPolicy.removeNonHighRefreshRatePackage("com.android.test");
        assertEquals(0, mPolicy.getPreferredModeId(cameraUsingWindow));
    }

    @Test
    public void testBlacklist() {
        final WindowState blacklistedWindow = createWindow(null, TYPE_BASE_APPLICATION,
                "blacklistedWindow");
        blacklistedWindow.mAttrs.packageName = "com.android.test";
        when(mBlacklist.isBlacklisted("com.android.test")).thenReturn(true);
        assertEquals(LOW_MODE_ID, mPolicy.getPreferredModeId(blacklistedWindow));
    }

    @Test
    public void testAppOverride_blacklist() {
        final WindowState overrideWindow = createWindow(null, TYPE_BASE_APPLICATION,
                "overrideWindow");
        overrideWindow.mAttrs.preferredDisplayModeId = LOW_MODE_ID;
        when(mBlacklist.isBlacklisted("com.android.test")).thenReturn(true);
        assertEquals(LOW_MODE_ID, mPolicy.getPreferredModeId(overrideWindow));
    }

    @Test
    public void testAppOverride_camera() {
        final WindowState overrideWindow = createWindow(null, TYPE_BASE_APPLICATION,
                "overrideWindow");
        overrideWindow.mAttrs.packageName = "com.android.test";
        overrideWindow.mAttrs.preferredDisplayModeId = LOW_MODE_ID;
        mPolicy.addNonHighRefreshRatePackage("com.android.test");
        assertEquals(LOW_MODE_ID, mPolicy.getPreferredModeId(overrideWindow));
    }

    @Test
    public void testAnimatingAppOverride() {
        final WindowState overrideWindow = createWindow(null, TYPE_BASE_APPLICATION,
                "overrideWindow");
        overrideWindow.mAttrs.packageName = "com.android.test";
        overrideWindow.mAttrs.preferredDisplayModeId = LOW_MODE_ID;
        overrideWindow.mAppToken.mSurfaceAnimator.startAnimation(
                overrideWindow.getPendingTransaction(), mock(AnimationAdapter.class),
                false /* hidden */);
        mPolicy.addNonHighRefreshRatePackage("com.android.test");
        assertEquals(0, mPolicy.getPreferredModeId(overrideWindow));
    }

    @Test
    public void testAnimatingCamera() {
        final WindowState cameraUsingWindow = createWindow(null, TYPE_BASE_APPLICATION,
                "cameraUsingWindow");
        cameraUsingWindow.mAttrs.packageName = "com.android.test";

        mPolicy.addNonHighRefreshRatePackage("com.android.test");
        assertEquals(LOW_MODE_ID, mPolicy.getPreferredModeId(cameraUsingWindow));

        cameraUsingWindow.mAppToken.mSurfaceAnimator.startAnimation(
                cameraUsingWindow.getPendingTransaction(), mock(AnimationAdapter.class),
                false /* hidden */);
        assertEquals(0, mPolicy.getPreferredModeId(cameraUsingWindow));
    }
}
