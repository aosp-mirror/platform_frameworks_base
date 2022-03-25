/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static android.view.InsetsState.ITYPE_CLIMATE_BAR;
import static android.view.InsetsState.ITYPE_EXTRA_NAVIGATION_BAR;
import static android.view.InsetsState.ITYPE_NAVIGATION_BAR;
import static android.view.InsetsState.ITYPE_STATUS_BAR;
import static android.view.InsetsState.ITYPE_TOP_GESTURES;
import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_90;
import static android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.spy;
import static org.testng.Assert.expectThrows;

import android.graphics.Insets;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.util.Pair;
import android.view.DisplayInfo;
import android.view.InsetsState;
import android.view.PrivacyIndicatorBounds;
import android.view.RoundedCorners;

import androidx.test.filters.SmallTest;

import com.android.server.wm.utils.WmDisplayCutout;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Tests for the {@link DisplayPolicy} class.
 *
 * Build/Install/Run:
 *  atest WmTests:DisplayPolicyLayoutTests
 */
@SmallTest
@Presubmit
@WindowTestsBase.UseTestDisplay(
        addWindows = { WindowTestsBase.W_STATUS_BAR, WindowTestsBase.W_NAVIGATION_BAR })
@RunWith(WindowTestRunner.class)
public class DisplayPolicyLayoutTests extends DisplayPolicyTestsBase {

    private DisplayFrames mFrames;
    private WindowState mWindow;
    private int mRotation = ROTATION_0;
    private boolean mHasDisplayCutout;
    private boolean mIsLongEdgeDisplayCutout;
    private boolean mHasRoundedCorners;

    private final Rect mDisplayBounds = new Rect();

    @Before
    public void setUp() throws Exception {
        mWindow = spy(createWindow(null, TYPE_APPLICATION, "window"));

        spyOn(mStatusBarWindow);
        spyOn(mNavBarWindow);

        // Disabling this call for most tests since it can override the systemUiFlags when called.
        doNothing().when(mDisplayPolicy).updateSystemBarAttributes();

        updateDisplayFrames();
    }

    public void setRotation(int rotation, boolean includingWindows) {
        mRotation = rotation;
        updateDisplayFrames();
        if (includingWindows) {
            mNavBarWindow.getWindowConfiguration().setRotation(rotation);
            mStatusBarWindow.getWindowConfiguration().setRotation(rotation);
            mWindow.getWindowConfiguration().setRotation(rotation);
        }
    }

    public void addDisplayCutout() {
        mHasDisplayCutout = true;
        updateDisplayFrames();
    }

    public void addLongEdgeDisplayCutout() {
        mHasDisplayCutout = true;
        mIsLongEdgeDisplayCutout = true;
        updateDisplayFrames();
    }

    public void addRoundedCorners() {
        mHasRoundedCorners = true;
        updateDisplayFrames();
    }

    private void updateDisplayFrames() {
        mFrames = createDisplayFrames(
                mDisplayContent.getInsetsStateController().getRawInsetsState());
        mDisplayBounds.set(0, 0, mFrames.mDisplayWidth, mFrames.mDisplayHeight);
        mDisplayContent.mDisplayFrames = mFrames;
        mDisplayContent.setBounds(mDisplayBounds);
        mDisplayPolicy.layoutWindowLw(mNavBarWindow, null, mFrames);
        mDisplayPolicy.layoutWindowLw(mStatusBarWindow, null, mFrames);
    }

    private DisplayFrames createDisplayFrames(InsetsState insetsState) {
        final Pair<DisplayInfo, WmDisplayCutout> info = displayInfoAndCutoutForRotation(mRotation,
                mHasDisplayCutout, mIsLongEdgeDisplayCutout);
        final RoundedCorners roundedCorners = mHasRoundedCorners
                ? mDisplayContent.calculateRoundedCornersForRotation(mRotation)
                : RoundedCorners.NO_ROUNDED_CORNERS;
        return new DisplayFrames(mDisplayContent.getDisplayId(),
                insetsState, info.first, info.second, roundedCorners, new PrivacyIndicatorBounds());
    }

    @Test
    public void addingWindow_doesNotTamperWithSysuiFlags() {
        mWindow.mAttrs.flags |= FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        addWindow(mWindow);

        assertEquals(0, mWindow.mAttrs.systemUiVisibility);
        assertEquals(0, mWindow.mAttrs.subtreeSystemUiVisibility);
    }

    @Test
    public void addingWindow_withInsetsTypes() {
        mDisplayPolicy.removeWindowLw(mStatusBarWindow);  // Removes the existing one.

        WindowState win = createWindow(null, TYPE_STATUS_BAR_SUB_PANEL, "StatusBarSubPanel");
        win.mAttrs.providesInsetsTypes = new int[]{ITYPE_STATUS_BAR, ITYPE_TOP_GESTURES};
        win.getFrame().set(0, 0, 500, 100);

        addWindow(win);
        win.updateSourceFrame(win.getFrame());
        InsetsStateController controller = mDisplayContent.getInsetsStateController();
        controller.onPostLayout();

        InsetsSourceProvider statusBarProvider = controller.getSourceProvider(ITYPE_STATUS_BAR);
        assertEquals(new Rect(0, 0, 500, 100), statusBarProvider.getSource().getFrame());
        assertEquals(Insets.of(0, 100, 0, 0),
                statusBarProvider.getSource().calculateInsets(new Rect(0, 0, 500, 500),
                        false /* ignoreVisibility */));

        InsetsSourceProvider topGesturesProvider = controller.getSourceProvider(ITYPE_TOP_GESTURES);
        assertEquals(new Rect(0, 0, 500, 100), topGesturesProvider.getSource().getFrame());
        assertEquals(Insets.of(0, 100, 0, 0),
                topGesturesProvider.getSource().calculateInsets(new Rect(0, 0, 500, 500),
                        false /* ignoreVisibility */));

        InsetsSourceProvider navigationBarProvider = controller.getSourceProvider(
                ITYPE_NAVIGATION_BAR);
        assertNotEquals(new Rect(0, 0, 500, 100), navigationBarProvider.getSource().getFrame());
    }

    @Test
    public void addingWindow_InWindowTypeWithPredefinedInsets() {
        mDisplayPolicy.removeWindowLw(mStatusBarWindow);  // Removes the existing one.
        WindowState win = createWindow(null, TYPE_STATUS_BAR, "StatusBar");
        win.mAttrs.providesInsetsTypes = new int[]{ITYPE_STATUS_BAR};
        win.getFrame().set(0, 0, 500, 100);

        addWindow(win);
        win.updateSourceFrame(win.getFrame());
        mDisplayContent.getInsetsStateController().onPostLayout();

        InsetsSourceProvider provider =
                mDisplayContent.getInsetsStateController().getSourceProvider(ITYPE_STATUS_BAR);
        // In the new flexible insets setup, the insets frame should always respect the window
        // layout result.
        assertEquals(new Rect(0, 0, 500, 100), provider.getSource().getFrame());
    }

    @Test
    public void addingWindow_throwsException_WithMultipleInsetTypes() {
        WindowState win1 = createWindow(null, TYPE_STATUS_BAR_SUB_PANEL, "StatusBarSubPanel");
        win1.mAttrs.providesInsetsTypes = new int[]{ITYPE_STATUS_BAR, ITYPE_NAVIGATION_BAR};

        expectThrows(IllegalArgumentException.class, () -> addWindow(win1));

        WindowState win2 = createWindow(null, TYPE_STATUS_BAR_SUB_PANEL, "StatusBarSubPanel");
        win2.mAttrs.providesInsetsTypes = new int[]{ITYPE_CLIMATE_BAR, ITYPE_EXTRA_NAVIGATION_BAR};

        expectThrows(IllegalArgumentException.class, () -> addWindow(win2));
    }

    /**
     * Verify that {@link DisplayPolicy#simulateLayoutDisplay} outputs the same display frames as
     * the real one.
     */
    @Test
    public void testSimulateLayoutDisplay() {
        assertSimulateLayoutSameDisplayFrames();
        setRotation(ROTATION_90, false /* includingWindows */);
        assertSimulateLayoutSameDisplayFrames();
        addDisplayCutout();
        assertSimulateLayoutSameDisplayFrames();
        addRoundedCorners();
        assertSimulateLayoutSameDisplayFrames();
    }

    private void assertSimulateLayoutSameDisplayFrames() {
        final String prefix = "";
        final InsetsState simulatedInsetsState = new InsetsState();
        final DisplayFrames simulatedDisplayFrames = createDisplayFrames(simulatedInsetsState);
        // Force the display bounds because it is not synced with display frames in policy test.
        mDisplayContent.getWindowConfiguration().setBounds(mFrames.mUnrestricted);
        mDisplayContent.getInsetsStateController().onPostLayout();
        mDisplayPolicy.simulateLayoutDisplay(simulatedDisplayFrames);

        final StringWriter realFramesDump = new StringWriter();
        mFrames.dump(prefix, new PrintWriter(realFramesDump));
        final StringWriter simulatedFramesDump = new StringWriter();
        simulatedDisplayFrames.dump(prefix, new PrintWriter(simulatedFramesDump));

        assertEquals(new ToStringComparatorWrapper<>(realFramesDump),
                new ToStringComparatorWrapper<>(simulatedFramesDump));

        final InsetsState realInsetsState = new InsetsState(
                mDisplayContent.getInsetsStateController().getRawInsetsState());
        // Exclude comparing IME insets because currently the simulated layout only focuses on the
        // insets from status bar and navigation bar.
        realInsetsState.removeSource(InsetsState.ITYPE_IME);
        realInsetsState.removeSource(InsetsState.ITYPE_CAPTION_BAR);

        assertEquals(new ToStringComparatorWrapper<>(realInsetsState),
                new ToStringComparatorWrapper<>(simulatedInsetsState));
    }

    @Test
    public void testFixedRotationInsetsSourceFrame() {
        mDisplayContent.mBaseDisplayHeight = DISPLAY_HEIGHT;
        mDisplayContent.mBaseDisplayWidth = DISPLAY_WIDTH;
        doReturn((mDisplayContent.getRotation() + 1) % 4).when(mDisplayContent)
                .rotationForActivityInDifferentOrientation(eq(mWindow.mActivityRecord));
        mWindow.mAboveInsetsState.set(
                mDisplayContent.getInsetsStateController().getRawInsetsState());
        final Rect frame = mWindow.getInsetsState().getSource(ITYPE_STATUS_BAR).getFrame();
        mDisplayContent.rotateInDifferentOrientationIfNeeded(mWindow.mActivityRecord);
        final Rect rotatedFrame = mWindow.getInsetsState().getSource(ITYPE_STATUS_BAR).getFrame();

        assertEquals(DISPLAY_WIDTH, frame.width());
        assertEquals(DISPLAY_HEIGHT, rotatedFrame.width());
    }
}
