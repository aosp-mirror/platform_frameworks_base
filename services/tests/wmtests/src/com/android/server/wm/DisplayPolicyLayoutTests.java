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

import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_90;
import static android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.spy;

import android.graphics.Rect;
import android.os.Binder;
import android.platform.test.annotations.Presubmit;
import android.util.SparseArray;
import android.view.DisplayInfo;
import android.view.InsetsFrameProvider;
import android.view.InsetsSource;
import android.view.InsetsState;
import android.view.PrivacyIndicatorBounds;
import android.view.RoundedCorners;
import android.view.WindowInsets;

import androidx.test.filters.SmallTest;

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
@WindowTestsBase.SetupWindows(
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

        makeWindowVisible(mStatusBarWindow, mNavBarWindow);
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
        mDisplayBounds.set(0, 0, mFrames.mWidth, mFrames.mHeight);
        mDisplayContent.mDisplayFrames = mFrames;
        mDisplayContent.setBounds(mDisplayBounds);
        mDisplayPolicy.layoutWindowLw(mNavBarWindow, null, mFrames);
        mDisplayPolicy.layoutWindowLw(mStatusBarWindow, null, mFrames);
    }

    private DisplayFrames createDisplayFrames(InsetsState insetsState) {
        final DisplayInfo info = displayInfoAndCutoutForRotation(mRotation,
                mHasDisplayCutout, mIsLongEdgeDisplayCutout);
        final RoundedCorners roundedCorners = mHasRoundedCorners
                ? mDisplayContent.calculateRoundedCornersForRotation(mRotation)
                : RoundedCorners.NO_ROUNDED_CORNERS;
        return new DisplayFrames(insetsState, info, info.displayCutout, roundedCorners,
                new PrivacyIndicatorBounds(), info.displayShape);
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

        final WindowState win = createWindow(null, TYPE_STATUS_BAR_SUB_PANEL, "statusBar");
        final Binder owner = new Binder();
        win.mAttrs.providedInsets = new InsetsFrameProvider[] {
                new InsetsFrameProvider(owner, 0, WindowInsets.Type.statusBars()),
                new InsetsFrameProvider(owner, 0, WindowInsets.Type.systemGestures())
        };
        addWindow(win);
        win.getFrame().set(0, 0, 500, 100);
        makeWindowVisible(win);
        win.updateSourceFrame(win.getFrame());
        mDisplayContent.getInsetsStateController().onPostLayout();

        assertTrue(win.hasInsetsSourceProvider());
        final SparseArray<InsetsSourceProvider> providers = win.getInsetsSourceProviders();
        for (int i = providers.size() - 1; i >= 0; i--) {
            final InsetsSourceProvider provider = providers.valueAt(i);
            assertEquals(new Rect(0, 0, 500, 100), provider.getSource().getFrame());
        }
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
        realInsetsState.removeSource(InsetsSource.ID_IME);

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
        final int statusBarId = mStatusBarWindow.getControllableInsetProvider().getSource().getId();
        final Rect frame = mWindow.getInsetsState().peekSource(statusBarId).getFrame();
        mDisplayContent.rotateInDifferentOrientationIfNeeded(mWindow.mActivityRecord);
        final Rect rotatedFrame = mWindow.getInsetsState().peekSource(statusBarId).getFrame();

        assertEquals(DISPLAY_WIDTH, frame.width());
        assertEquals(DISPLAY_HEIGHT, rotatedFrame.width());
    }
}
