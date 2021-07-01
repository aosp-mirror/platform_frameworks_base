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

import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_APP_TRANSITION;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.os.Parcel;
import android.platform.test.annotations.Presubmit;
import android.view.Display.Mode;
import android.view.DisplayInfo;
import android.view.WindowManager.LayoutParams;

import androidx.test.filters.FlakyTest;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Build/Install/Run:
 *  atest WmTests:RefreshRatePolicyTest
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
@FlakyTest
public class RefreshRatePolicyTest extends WindowTestsBase {
    private static final float FLOAT_TOLERANCE = 0.01f;
    private static final int LOW_MODE_ID = 3;

    private RefreshRatePolicy mPolicy;
    private HighRefreshRateDenylist mDenylist = mock(HighRefreshRateDenylist.class);

    // Parcel and Unparcel the LayoutParams in the window state to test the path the object
    // travels from the app's process to system server
    void parcelLayoutParams(WindowState window) {
        Parcel parcel = Parcel.obtain();
        window.mAttrs.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        window.mAttrs.copyFrom(new LayoutParams(parcel));
        parcel.recycle();
    }

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
        mPolicy = new RefreshRatePolicy(mWm, di, mDenylist);
    }

    @Test
    public void testCamera() {
        final WindowState cameraUsingWindow = createWindow(null, TYPE_BASE_APPLICATION,
                "cameraUsingWindow");
        cameraUsingWindow.mAttrs.packageName = "com.android.test";
        parcelLayoutParams(cameraUsingWindow);
        assertEquals(0, mPolicy.getPreferredModeId(cameraUsingWindow));
        assertEquals(0, mPolicy.getPreferredRefreshRate(cameraUsingWindow), FLOAT_TOLERANCE);
        assertEquals(0, mPolicy.getPreferredMinRefreshRate(cameraUsingWindow), FLOAT_TOLERANCE);
        assertEquals(0, mPolicy.getPreferredMaxRefreshRate(cameraUsingWindow), FLOAT_TOLERANCE);
        mPolicy.addNonHighRefreshRatePackage("com.android.test");
        assertEquals(0, mPolicy.getPreferredModeId(cameraUsingWindow));
        assertEquals(0, mPolicy.getPreferredRefreshRate(cameraUsingWindow), FLOAT_TOLERANCE);
        assertEquals(0, mPolicy.getPreferredMinRefreshRate(cameraUsingWindow), FLOAT_TOLERANCE);
        assertEquals(60, mPolicy.getPreferredMaxRefreshRate(cameraUsingWindow), FLOAT_TOLERANCE);
        mPolicy.removeNonHighRefreshRatePackage("com.android.test");
        assertEquals(0, mPolicy.getPreferredModeId(cameraUsingWindow));
        assertEquals(0, mPolicy.getPreferredRefreshRate(cameraUsingWindow), FLOAT_TOLERANCE);
    }

    @Test
    public void testDenyList() {
        final WindowState denylistedWindow = createWindow(null, TYPE_BASE_APPLICATION,
                "denylistedWindow");
        denylistedWindow.mAttrs.packageName = "com.android.test";
        parcelLayoutParams(denylistedWindow);
        when(mDenylist.isDenylisted("com.android.test")).thenReturn(true);
        assertEquals(0, mPolicy.getPreferredModeId(denylistedWindow));
        assertEquals(60, mPolicy.getPreferredRefreshRate(denylistedWindow), FLOAT_TOLERANCE);
    }

    @Test
    public void testAppOverride_blacklist() {
        final WindowState overrideWindow = createWindow(null, TYPE_BASE_APPLICATION,
                "overrideWindow");
        overrideWindow.mAttrs.packageName = "com.android.test";
        overrideWindow.mAttrs.preferredDisplayModeId = LOW_MODE_ID;
        parcelLayoutParams(overrideWindow);
        when(mDenylist.isDenylisted("com.android.test")).thenReturn(true);
        assertEquals(LOW_MODE_ID, mPolicy.getPreferredModeId(overrideWindow));
        assertEquals(60, mPolicy.getPreferredRefreshRate(overrideWindow), FLOAT_TOLERANCE);
    }

    @Test
    public void testAppOverride_camera() {
        final WindowState overrideWindow = createWindow(null, TYPE_BASE_APPLICATION,
                "overrideWindow");
        overrideWindow.mAttrs.packageName = "com.android.test";
        overrideWindow.mAttrs.preferredDisplayModeId = LOW_MODE_ID;
        parcelLayoutParams(overrideWindow);
        mPolicy.addNonHighRefreshRatePackage("com.android.test");
        assertEquals(LOW_MODE_ID, mPolicy.getPreferredModeId(overrideWindow));
        assertEquals(0, mPolicy.getPreferredRefreshRate(overrideWindow), FLOAT_TOLERANCE);
        assertEquals(0, mPolicy.getPreferredMinRefreshRate(overrideWindow), FLOAT_TOLERANCE);
        assertEquals(0, mPolicy.getPreferredMaxRefreshRate(overrideWindow), FLOAT_TOLERANCE);
    }

    @Test
    public void testAnimatingAppOverride() {
        final WindowState overrideWindow = createWindow(null, TYPE_BASE_APPLICATION,
                "overrideWindow");
        overrideWindow.mAttrs.packageName = "com.android.test";
        overrideWindow.mAttrs.preferredDisplayModeId = LOW_MODE_ID;
        parcelLayoutParams(overrideWindow);
        overrideWindow.mActivityRecord.mSurfaceAnimator.startAnimation(
                overrideWindow.getPendingTransaction(), mock(AnimationAdapter.class),
                false /* hidden */, ANIMATION_TYPE_APP_TRANSITION);
        mPolicy.addNonHighRefreshRatePackage("com.android.test");
        assertEquals(0, mPolicy.getPreferredModeId(overrideWindow));
        assertEquals(0, mPolicy.getPreferredRefreshRate(overrideWindow), FLOAT_TOLERANCE);
        assertEquals(0, mPolicy.getPreferredMinRefreshRate(overrideWindow), FLOAT_TOLERANCE);
        assertEquals(0, mPolicy.getPreferredMaxRefreshRate(overrideWindow), FLOAT_TOLERANCE);
    }

    @Test
    public void testAnimatingCamera() {
        final WindowState cameraUsingWindow = createWindow(null, TYPE_BASE_APPLICATION,
                "cameraUsingWindow");
        cameraUsingWindow.mAttrs.packageName = "com.android.test";
        parcelLayoutParams(cameraUsingWindow);

        mPolicy.addNonHighRefreshRatePackage("com.android.test");
        assertEquals(0, mPolicy.getPreferredModeId(cameraUsingWindow));
        assertEquals(0, mPolicy.getPreferredRefreshRate(cameraUsingWindow), FLOAT_TOLERANCE);
        assertEquals(0, mPolicy.getPreferredMinRefreshRate(cameraUsingWindow), FLOAT_TOLERANCE);
        assertEquals(60, mPolicy.getPreferredMaxRefreshRate(cameraUsingWindow), FLOAT_TOLERANCE);

        cameraUsingWindow.mActivityRecord.mSurfaceAnimator.startAnimation(
                cameraUsingWindow.getPendingTransaction(), mock(AnimationAdapter.class),
                false /* hidden */, ANIMATION_TYPE_APP_TRANSITION);
        assertEquals(0, mPolicy.getPreferredModeId(cameraUsingWindow));
        assertEquals(0, mPolicy.getPreferredRefreshRate(cameraUsingWindow), FLOAT_TOLERANCE);
        assertEquals(0, mPolicy.getPreferredMinRefreshRate(cameraUsingWindow), FLOAT_TOLERANCE);
        assertEquals(0, mPolicy.getPreferredMaxRefreshRate(cameraUsingWindow), FLOAT_TOLERANCE);
    }

    @Test
    public void testAppMaxRefreshRate() {
        final WindowState window = createWindow(null, TYPE_BASE_APPLICATION, "window");
        window.mAttrs.preferredMaxDisplayRefreshRate = 60f;
        parcelLayoutParams(window);
        assertEquals(0, mPolicy.getPreferredModeId(window));
        assertEquals(0, mPolicy.getPreferredRefreshRate(window), FLOAT_TOLERANCE);
        assertEquals(0, mPolicy.getPreferredMinRefreshRate(window), FLOAT_TOLERANCE);
        assertEquals(60, mPolicy.getPreferredMaxRefreshRate(window), FLOAT_TOLERANCE);

        window.mActivityRecord.mSurfaceAnimator.startAnimation(
                window.getPendingTransaction(), mock(AnimationAdapter.class),
                false /* hidden */, ANIMATION_TYPE_APP_TRANSITION);
        assertEquals(0, mPolicy.getPreferredModeId(window));
        assertEquals(0, mPolicy.getPreferredRefreshRate(window), FLOAT_TOLERANCE);
        assertEquals(0, mPolicy.getPreferredMinRefreshRate(window), FLOAT_TOLERANCE);
        assertEquals(0, mPolicy.getPreferredMaxRefreshRate(window), FLOAT_TOLERANCE);
    }

    @Test
    public void testAppMinRefreshRate() {
        final WindowState window = createWindow(null, TYPE_BASE_APPLICATION, "window");
        window.mAttrs.preferredMinDisplayRefreshRate = 60f;
        parcelLayoutParams(window);
        assertEquals(0, mPolicy.getPreferredModeId(window));
        assertEquals(0, mPolicy.getPreferredRefreshRate(window), FLOAT_TOLERANCE);
        assertEquals(60, mPolicy.getPreferredMinRefreshRate(window), FLOAT_TOLERANCE);
        assertEquals(0, mPolicy.getPreferredMaxRefreshRate(window), FLOAT_TOLERANCE);

        window.mActivityRecord.mSurfaceAnimator.startAnimation(
                window.getPendingTransaction(), mock(AnimationAdapter.class),
                false /* hidden */, ANIMATION_TYPE_APP_TRANSITION);
        assertEquals(0, mPolicy.getPreferredModeId(window));
        assertEquals(0, mPolicy.getPreferredRefreshRate(window), FLOAT_TOLERANCE);
        assertEquals(0, mPolicy.getPreferredMaxRefreshRate(window), FLOAT_TOLERANCE);
    }

    @Test
    public void testAppPreferredRefreshRate() {
        final WindowState window = createWindow(null, TYPE_BASE_APPLICATION, "window");
        window.mAttrs.preferredRefreshRate = 60f;
        parcelLayoutParams(window);
        assertEquals(0, mPolicy.getPreferredModeId(window));
        assertEquals(60, mPolicy.getPreferredRefreshRate(window), FLOAT_TOLERANCE);
        assertEquals(0, mPolicy.getPreferredMinRefreshRate(window), FLOAT_TOLERANCE);
        assertEquals(0, mPolicy.getPreferredMaxRefreshRate(window), FLOAT_TOLERANCE);
    }
}
