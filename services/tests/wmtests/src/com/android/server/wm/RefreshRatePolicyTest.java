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
    private static final int HI_MODE_ID = 1;
    private static final float HI_REFRESH_RATE = 90;

    private static final int MID_MODE_ID = 2;
    private static final float MID_REFRESH_RATE = 70;

    private static final int LOW_MODE_ID = 3;
    private static final float LOW_REFRESH_RATE = 60;

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
        Mode defaultMode = mDisplayInfo.getDefaultMode();
        mDisplayInfo.supportedModes = new Mode[] {
                new Mode(HI_MODE_ID,
                        defaultMode.getPhysicalWidth(), defaultMode.getPhysicalHeight(),
                        HI_REFRESH_RATE),
                new Mode(MID_MODE_ID,
                        defaultMode.getPhysicalWidth(), defaultMode.getPhysicalHeight(),
                        MID_REFRESH_RATE),
                new Mode(LOW_MODE_ID,
                        defaultMode.getPhysicalWidth(), defaultMode.getPhysicalHeight(),
                        LOW_REFRESH_RATE),
        };
        mDisplayInfo.defaultModeId = HI_MODE_ID;
        mPolicy = new RefreshRatePolicy(mWm, mDisplayInfo, mDenylist);
    }

    WindowState createWindow(String name) {
        WindowState window = createWindow(null, TYPE_BASE_APPLICATION, name);
        when(window.getDisplayInfo()).thenReturn(mDisplayInfo);
        return window;
    }

    @Test
    public void testCamera() {
        final WindowState cameraUsingWindow = createWindow("cameraUsingWindow");
        cameraUsingWindow.mAttrs.packageName = "com.android.test";
        parcelLayoutParams(cameraUsingWindow);
        assertEquals(0, mPolicy.getPreferredModeId(cameraUsingWindow));
        assertEquals(0, mPolicy.getPreferredRefreshRate(cameraUsingWindow), FLOAT_TOLERANCE);
        assertEquals(0, mPolicy.getPreferredMinRefreshRate(cameraUsingWindow), FLOAT_TOLERANCE);
        assertEquals(0, mPolicy.getPreferredMaxRefreshRate(cameraUsingWindow), FLOAT_TOLERANCE);
        mPolicy.addRefreshRateRangeForPackage("com.android.test",
                LOW_REFRESH_RATE, LOW_REFRESH_RATE);
        assertEquals(0, mPolicy.getPreferredModeId(cameraUsingWindow));
        assertEquals(0, mPolicy.getPreferredRefreshRate(cameraUsingWindow), FLOAT_TOLERANCE);
        assertEquals(LOW_REFRESH_RATE,
                mPolicy.getPreferredMinRefreshRate(cameraUsingWindow), FLOAT_TOLERANCE);
        assertEquals(LOW_REFRESH_RATE,
                mPolicy.getPreferredMaxRefreshRate(cameraUsingWindow), FLOAT_TOLERANCE);
        mPolicy.removeRefreshRateRangeForPackage("com.android.test");
        assertEquals(0, mPolicy.getPreferredModeId(cameraUsingWindow));
        assertEquals(0, mPolicy.getPreferredRefreshRate(cameraUsingWindow), FLOAT_TOLERANCE);
        assertEquals(0, mPolicy.getPreferredMinRefreshRate(cameraUsingWindow), FLOAT_TOLERANCE);
        assertEquals(0, mPolicy.getPreferredMaxRefreshRate(cameraUsingWindow), FLOAT_TOLERANCE);
    }

    @Test
    public void testCameraRange() {
        final WindowState cameraUsingWindow = createWindow("cameraUsingWindow");
        cameraUsingWindow.mAttrs.packageName = "com.android.test";
        parcelLayoutParams(cameraUsingWindow);
        assertEquals(0, mPolicy.getPreferredModeId(cameraUsingWindow));
        assertEquals(0, mPolicy.getPreferredRefreshRate(cameraUsingWindow), FLOAT_TOLERANCE);
        assertEquals(0, mPolicy.getPreferredMinRefreshRate(cameraUsingWindow), FLOAT_TOLERANCE);
        assertEquals(0, mPolicy.getPreferredMaxRefreshRate(cameraUsingWindow), FLOAT_TOLERANCE);
        mPolicy.addRefreshRateRangeForPackage("com.android.test",
                LOW_REFRESH_RATE, MID_REFRESH_RATE);
        assertEquals(0, mPolicy.getPreferredModeId(cameraUsingWindow));
        assertEquals(0, mPolicy.getPreferredRefreshRate(cameraUsingWindow), FLOAT_TOLERANCE);
        assertEquals(LOW_REFRESH_RATE,
                mPolicy.getPreferredMinRefreshRate(cameraUsingWindow), FLOAT_TOLERANCE);
        assertEquals(MID_REFRESH_RATE,
                mPolicy.getPreferredMaxRefreshRate(cameraUsingWindow), FLOAT_TOLERANCE);
        mPolicy.removeRefreshRateRangeForPackage("com.android.test");
        assertEquals(0, mPolicy.getPreferredModeId(cameraUsingWindow));
        assertEquals(0, mPolicy.getPreferredRefreshRate(cameraUsingWindow), FLOAT_TOLERANCE);
        assertEquals(0, mPolicy.getPreferredMinRefreshRate(cameraUsingWindow), FLOAT_TOLERANCE);
        assertEquals(0, mPolicy.getPreferredMaxRefreshRate(cameraUsingWindow), FLOAT_TOLERANCE);
    }

    @Test
    public void testCameraRange_OutOfRange() {
        final WindowState cameraUsingWindow = createWindow("cameraUsingWindow");
        cameraUsingWindow.mAttrs.packageName = "com.android.test";
        parcelLayoutParams(cameraUsingWindow);
        assertEquals(0, mPolicy.getPreferredModeId(cameraUsingWindow));
        assertEquals(0, mPolicy.getPreferredRefreshRate(cameraUsingWindow), FLOAT_TOLERANCE);
        assertEquals(0, mPolicy.getPreferredMinRefreshRate(cameraUsingWindow), FLOAT_TOLERANCE);
        assertEquals(0, mPolicy.getPreferredMaxRefreshRate(cameraUsingWindow), FLOAT_TOLERANCE);
        mPolicy.addRefreshRateRangeForPackage("com.android.test",
                LOW_REFRESH_RATE - 10, HI_REFRESH_RATE + 10);
        assertEquals(0, mPolicy.getPreferredModeId(cameraUsingWindow));
        assertEquals(0, mPolicy.getPreferredRefreshRate(cameraUsingWindow), FLOAT_TOLERANCE);
        assertEquals(LOW_REFRESH_RATE,
                mPolicy.getPreferredMinRefreshRate(cameraUsingWindow), FLOAT_TOLERANCE);
        assertEquals(HI_REFRESH_RATE,
                mPolicy.getPreferredMaxRefreshRate(cameraUsingWindow), FLOAT_TOLERANCE);
        mPolicy.removeRefreshRateRangeForPackage("com.android.test");
        assertEquals(0, mPolicy.getPreferredModeId(cameraUsingWindow));
        assertEquals(0, mPolicy.getPreferredRefreshRate(cameraUsingWindow), FLOAT_TOLERANCE);
        assertEquals(0, mPolicy.getPreferredMinRefreshRate(cameraUsingWindow), FLOAT_TOLERANCE);
        assertEquals(0, mPolicy.getPreferredMaxRefreshRate(cameraUsingWindow), FLOAT_TOLERANCE);
    }

    @Test
    public void testDenyList() {
        final WindowState denylistedWindow = createWindow("denylistedWindow");
        denylistedWindow.mAttrs.packageName = "com.android.test";
        parcelLayoutParams(denylistedWindow);
        when(mDenylist.isDenylisted("com.android.test")).thenReturn(true);
        assertEquals(0, mPolicy.getPreferredModeId(denylistedWindow));
        assertEquals(LOW_REFRESH_RATE,
                mPolicy.getPreferredRefreshRate(denylistedWindow), FLOAT_TOLERANCE);
        assertEquals(0, mPolicy.getPreferredMinRefreshRate(denylistedWindow), FLOAT_TOLERANCE);
        assertEquals(0, mPolicy.getPreferredMaxRefreshRate(denylistedWindow), FLOAT_TOLERANCE);
    }

    @Test
    public void testAppOverridePreferredModeId_denylist() {
        final WindowState overrideWindow = createWindow("overrideWindow");
        overrideWindow.mAttrs.packageName = "com.android.test";
        overrideWindow.mAttrs.preferredDisplayModeId = HI_MODE_ID;
        parcelLayoutParams(overrideWindow);
        when(mDenylist.isDenylisted("com.android.test")).thenReturn(true);
        assertEquals(HI_MODE_ID, mPolicy.getPreferredModeId(overrideWindow));
        assertEquals(HI_REFRESH_RATE,
                mPolicy.getPreferredRefreshRate(overrideWindow), FLOAT_TOLERANCE);
        assertEquals(0, mPolicy.getPreferredMinRefreshRate(overrideWindow), FLOAT_TOLERANCE);
        assertEquals(0, mPolicy.getPreferredMaxRefreshRate(overrideWindow), FLOAT_TOLERANCE);
    }

    @Test
    public void testAppOverridePreferredRefreshRate_denylist() {
        final WindowState overrideWindow = createWindow("overrideWindow");
        overrideWindow.mAttrs.packageName = "com.android.test";
        overrideWindow.mAttrs.preferredRefreshRate = HI_REFRESH_RATE;
        parcelLayoutParams(overrideWindow);
        when(mDenylist.isDenylisted("com.android.test")).thenReturn(true);
        assertEquals(0, mPolicy.getPreferredModeId(overrideWindow));
        assertEquals(HI_REFRESH_RATE,
                mPolicy.getPreferredRefreshRate(overrideWindow), FLOAT_TOLERANCE);
        assertEquals(0, mPolicy.getPreferredMinRefreshRate(overrideWindow), FLOAT_TOLERANCE);
        assertEquals(0, mPolicy.getPreferredMaxRefreshRate(overrideWindow), FLOAT_TOLERANCE);
    }

    @Test
    public void testAppOverridePreferredModeId_camera() {
        final WindowState overrideWindow = createWindow("overrideWindow");
        overrideWindow.mAttrs.packageName = "com.android.test";
        overrideWindow.mAttrs.preferredDisplayModeId = HI_MODE_ID;
        parcelLayoutParams(overrideWindow);
        mPolicy.addRefreshRateRangeForPackage("com.android.test",
                LOW_REFRESH_RATE, LOW_REFRESH_RATE);
        assertEquals(HI_MODE_ID, mPolicy.getPreferredModeId(overrideWindow));
        assertEquals(HI_REFRESH_RATE,
                mPolicy.getPreferredRefreshRate(overrideWindow), FLOAT_TOLERANCE);
        assertEquals(LOW_REFRESH_RATE,
                mPolicy.getPreferredMinRefreshRate(overrideWindow), FLOAT_TOLERANCE);
        assertEquals(LOW_REFRESH_RATE,
                mPolicy.getPreferredMaxRefreshRate(overrideWindow), FLOAT_TOLERANCE);
    }

    @Test
    public void testAppOverridePreferredRefreshRate_camera() {
        final WindowState overrideWindow = createWindow("overrideWindow");
        overrideWindow.mAttrs.packageName = "com.android.test";
        overrideWindow.mAttrs.preferredRefreshRate = HI_REFRESH_RATE;
        parcelLayoutParams(overrideWindow);
        mPolicy.addRefreshRateRangeForPackage("com.android.test",
                LOW_REFRESH_RATE, LOW_REFRESH_RATE);
        assertEquals(0, mPolicy.getPreferredModeId(overrideWindow));
        assertEquals(HI_REFRESH_RATE,
                mPolicy.getPreferredRefreshRate(overrideWindow), FLOAT_TOLERANCE);
        assertEquals(LOW_REFRESH_RATE,
                mPolicy.getPreferredMinRefreshRate(overrideWindow), FLOAT_TOLERANCE);
        assertEquals(LOW_REFRESH_RATE,
                mPolicy.getPreferredMaxRefreshRate(overrideWindow), FLOAT_TOLERANCE);
    }

    @Test
    public void testAnimatingAppOverridePreferredModeId() {
        final WindowState overrideWindow = createWindow("overrideWindow");
        overrideWindow.mAttrs.packageName = "com.android.test";
        overrideWindow.mAttrs.preferredDisplayModeId = LOW_MODE_ID;
        parcelLayoutParams(overrideWindow);
        assertEquals(LOW_MODE_ID, mPolicy.getPreferredModeId(overrideWindow));
        assertEquals(LOW_REFRESH_RATE,
                mPolicy.getPreferredRefreshRate(overrideWindow), FLOAT_TOLERANCE);
        assertEquals(0, mPolicy.getPreferredMinRefreshRate(overrideWindow), FLOAT_TOLERANCE);
        assertEquals(0, mPolicy.getPreferredMaxRefreshRate(overrideWindow), FLOAT_TOLERANCE);

        overrideWindow.mActivityRecord.mSurfaceAnimator.startAnimation(
                overrideWindow.getPendingTransaction(), mock(AnimationAdapter.class),
                false /* hidden */, ANIMATION_TYPE_APP_TRANSITION);
        assertEquals(0, mPolicy.getPreferredModeId(overrideWindow));
        assertEquals(0, mPolicy.getPreferredRefreshRate(overrideWindow), FLOAT_TOLERANCE);
        assertEquals(0, mPolicy.getPreferredMinRefreshRate(overrideWindow), FLOAT_TOLERANCE);
        assertEquals(0, mPolicy.getPreferredMaxRefreshRate(overrideWindow), FLOAT_TOLERANCE);
    }

    @Test
    public void testAnimatingAppOverridePreferredRefreshRate() {
        final WindowState overrideWindow = createWindow("overrideWindow");
        overrideWindow.mAttrs.packageName = "com.android.test";
        overrideWindow.mAttrs.preferredRefreshRate = LOW_REFRESH_RATE;
        parcelLayoutParams(overrideWindow);
        assertEquals(0, mPolicy.getPreferredModeId(overrideWindow));
        assertEquals(LOW_REFRESH_RATE,
                mPolicy.getPreferredRefreshRate(overrideWindow), FLOAT_TOLERANCE);
        assertEquals(0, mPolicy.getPreferredMinRefreshRate(overrideWindow), FLOAT_TOLERANCE);
        assertEquals(0, mPolicy.getPreferredMaxRefreshRate(overrideWindow), FLOAT_TOLERANCE);

        overrideWindow.mActivityRecord.mSurfaceAnimator.startAnimation(
                overrideWindow.getPendingTransaction(), mock(AnimationAdapter.class),
                false /* hidden */, ANIMATION_TYPE_APP_TRANSITION);
        assertEquals(0, mPolicy.getPreferredModeId(overrideWindow));
        assertEquals(0, mPolicy.getPreferredRefreshRate(overrideWindow), FLOAT_TOLERANCE);
        assertEquals(0, mPolicy.getPreferredMinRefreshRate(overrideWindow), FLOAT_TOLERANCE);
        assertEquals(0, mPolicy.getPreferredMaxRefreshRate(overrideWindow), FLOAT_TOLERANCE);
    }

    @Test
    public void testAnimatingDenylist() {
        final WindowState window = createWindow("overrideWindow");
        window.mAttrs.packageName = "com.android.test";
        parcelLayoutParams(window);
        when(mDenylist.isDenylisted("com.android.test")).thenReturn(true);
        assertEquals(0, mPolicy.getPreferredModeId(window));
        assertEquals(LOW_REFRESH_RATE,
                mPolicy.getPreferredRefreshRate(window), FLOAT_TOLERANCE);
        assertEquals(0, mPolicy.getPreferredMinRefreshRate(window), FLOAT_TOLERANCE);
        assertEquals(0, mPolicy.getPreferredMaxRefreshRate(window), FLOAT_TOLERANCE);

        window.mActivityRecord.mSurfaceAnimator.startAnimation(
                window.getPendingTransaction(), mock(AnimationAdapter.class),
                false /* hidden */, ANIMATION_TYPE_APP_TRANSITION);
        assertEquals(0, mPolicy.getPreferredModeId(window));
        assertEquals(0, mPolicy.getPreferredRefreshRate(window), FLOAT_TOLERANCE);
        assertEquals(0, mPolicy.getPreferredMinRefreshRate(window), FLOAT_TOLERANCE);
        assertEquals(0, mPolicy.getPreferredMaxRefreshRate(window), FLOAT_TOLERANCE);
    }

    @Test
    public void testAnimatingCamera() {
        final WindowState cameraUsingWindow = createWindow("cameraUsingWindow");
        cameraUsingWindow.mAttrs.packageName = "com.android.test";
        parcelLayoutParams(cameraUsingWindow);

        mPolicy.addRefreshRateRangeForPackage("com.android.test",
                LOW_REFRESH_RATE, LOW_REFRESH_RATE);
        assertEquals(0, mPolicy.getPreferredModeId(cameraUsingWindow));
        assertEquals(0, mPolicy.getPreferredRefreshRate(cameraUsingWindow), FLOAT_TOLERANCE);
        assertEquals(LOW_REFRESH_RATE,
                mPolicy.getPreferredMinRefreshRate(cameraUsingWindow), FLOAT_TOLERANCE);
        assertEquals(LOW_REFRESH_RATE,
                mPolicy.getPreferredMaxRefreshRate(cameraUsingWindow), FLOAT_TOLERANCE);

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
        final WindowState window = createWindow("window");
        window.mAttrs.preferredMaxDisplayRefreshRate = LOW_REFRESH_RATE;
        parcelLayoutParams(window);
        assertEquals(0, mPolicy.getPreferredModeId(window));
        assertEquals(0, mPolicy.getPreferredRefreshRate(window), FLOAT_TOLERANCE);
        assertEquals(0, mPolicy.getPreferredMinRefreshRate(window), FLOAT_TOLERANCE);
        assertEquals(LOW_REFRESH_RATE, mPolicy.getPreferredMaxRefreshRate(window), FLOAT_TOLERANCE);

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
        final WindowState window = createWindow("window");
        window.mAttrs.preferredMinDisplayRefreshRate = LOW_REFRESH_RATE;
        parcelLayoutParams(window);
        assertEquals(0, mPolicy.getPreferredModeId(window));
        assertEquals(0, mPolicy.getPreferredRefreshRate(window), FLOAT_TOLERANCE);
        assertEquals(LOW_REFRESH_RATE, mPolicy.getPreferredMinRefreshRate(window), FLOAT_TOLERANCE);
        assertEquals(0, mPolicy.getPreferredMaxRefreshRate(window), FLOAT_TOLERANCE);

        window.mActivityRecord.mSurfaceAnimator.startAnimation(
                window.getPendingTransaction(), mock(AnimationAdapter.class),
                false /* hidden */, ANIMATION_TYPE_APP_TRANSITION);
        assertEquals(0, mPolicy.getPreferredModeId(window));
        assertEquals(0, mPolicy.getPreferredRefreshRate(window), FLOAT_TOLERANCE);
        assertEquals(0, mPolicy.getPreferredMinRefreshRate(window), FLOAT_TOLERANCE);
        assertEquals(0, mPolicy.getPreferredMaxRefreshRate(window), FLOAT_TOLERANCE);
    }

    @Test
    public void testAppPreferredRefreshRate() {
        final WindowState window = createWindow("window");
        window.mAttrs.preferredRefreshRate = LOW_REFRESH_RATE;
        parcelLayoutParams(window);
        assertEquals(0, mPolicy.getPreferredModeId(window));
        assertEquals(LOW_REFRESH_RATE, mPolicy.getPreferredRefreshRate(window), FLOAT_TOLERANCE);
        assertEquals(0, mPolicy.getPreferredMinRefreshRate(window), FLOAT_TOLERANCE);
        assertEquals(0, mPolicy.getPreferredMaxRefreshRate(window), FLOAT_TOLERANCE);
    }
}
