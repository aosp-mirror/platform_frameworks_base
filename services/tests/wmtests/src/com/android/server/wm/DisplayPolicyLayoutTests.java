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
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;
import static android.view.ViewRootImpl.INSETS_LAYOUT_GENERALIZATION;
import static android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_INSET_PARENT_FRAME_BY_IME;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL;
import static android.view.WindowManagerPolicyConstants.ALT_BAR_BOTTOM;
import static android.view.WindowManagerPolicyConstants.ALT_BAR_LEFT;
import static android.view.WindowManagerPolicyConstants.ALT_BAR_RIGHT;
import static android.view.WindowManagerPolicyConstants.ALT_BAR_TOP;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.spy;
import static org.testng.Assert.expectThrows;

import android.graphics.Insets;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.util.Pair;
import android.util.SparseArray;
import android.view.DisplayCutout;
import android.view.DisplayInfo;
import android.view.Gravity;
import android.view.InsetsState;
import android.view.PrivacyIndicatorBounds;
import android.view.RoundedCorners;
import android.view.WindowInsets.Side;
import android.view.WindowInsets.Type;

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
        // We only test window frames set by DisplayPolicy, so here prevents computeFrameLw from
        // changing those frames.
        doNothing().when(mWindow).computeFrame(any());

        spyOn(mStatusBarWindow);
        spyOn(mNavBarWindow);

        // Disabling this call for most tests since it can override the systemUiFlags when called.
        doReturn(false).when(mDisplayPolicy).updateSystemUiVisibilityLw();

        updateDisplayFrames();
    }

    void addWindowWithRawInsetsState(WindowState win) {
        addWindow(win);
        // Without mPerformLayout in display content, the window cannot see any insets. Override the
        // insets state with the global one.
        final InsetsState insetsState =
                win.getDisplayContent().getInsetsStateController().getRawInsetsState();
        win.mAboveInsetsState.set(insetsState);
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
        mDisplayContent.getInsetsStateController().onPostLayout();

        InsetsSourceProvider provider =
                mDisplayContent.getInsetsStateController().getSourceProvider(ITYPE_STATUS_BAR);
        if (INSETS_LAYOUT_GENERALIZATION) {
            // In the new flexible insets setup, the insets frame should always respect the window
            // layout result.
            assertEquals(new Rect(0, 0, 500, 100), provider.getSource().getFrame());
        } else {
            assertNotEquals(new Rect(0, 0, 500, 100), provider.getSource().getFrame());
        }
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

    @Test
    public void addingWindow_variousGravities_alternateBarPosUpdated() {
        mDisplayPolicy.removeWindowLw(mNavBarWindow);  // Removes the existing one.

        WindowState win1 = createWindow(null, TYPE_NAVIGATION_BAR_PANEL, "NavBarPanel1");
        win1.mAttrs.providesInsetsTypes = new int[]{ITYPE_NAVIGATION_BAR};
        win1.mAttrs.gravity = Gravity.TOP;
        win1.getFrame().set(0, 0, 200, 500);
        addWindow(win1);

        assertEquals(mDisplayPolicy.getAlternateNavBarPosition(), ALT_BAR_TOP);
        mDisplayPolicy.removeWindowLw(win1);

        WindowState win2 = createWindow(null, TYPE_NAVIGATION_BAR_PANEL, "NavBarPanel2");
        win2.mAttrs.providesInsetsTypes = new int[]{ITYPE_NAVIGATION_BAR};
        win2.mAttrs.gravity = Gravity.BOTTOM;
        win2.getFrame().set(0, 0, 200, 500);
        addWindow(win2);

        assertEquals(mDisplayPolicy.getAlternateNavBarPosition(), ALT_BAR_BOTTOM);
        mDisplayPolicy.removeWindowLw(win2);

        WindowState win3 = createWindow(null, TYPE_NAVIGATION_BAR_PANEL, "NavBarPanel3");
        win3.mAttrs.providesInsetsTypes = new int[]{ITYPE_NAVIGATION_BAR};
        win3.mAttrs.gravity = Gravity.LEFT;
        win3.getFrame().set(0, 0, 200, 500);
        addWindow(win3);

        assertEquals(mDisplayPolicy.getAlternateNavBarPosition(), ALT_BAR_LEFT);
        mDisplayPolicy.removeWindowLw(win3);

        WindowState win4 = createWindow(null, TYPE_NAVIGATION_BAR_PANEL, "NavBarPanel4");
        win4.mAttrs.providesInsetsTypes = new int[]{ITYPE_NAVIGATION_BAR};
        win4.mAttrs.gravity = Gravity.RIGHT;
        win4.getFrame().set(0, 0, 200, 500);
        addWindow(win4);

        assertEquals(mDisplayPolicy.getAlternateNavBarPosition(), ALT_BAR_RIGHT);
        mDisplayPolicy.removeWindowLw(win4);
    }

    @Test
    public void layoutWindowLw_fitStatusBars() {
        mWindow.mAttrs.setFitInsetsTypes(Type.statusBars());
        addWindowWithRawInsetsState(mWindow);

        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

        assertInsetByTopBottom(mWindow.getDisplayFrame(), STATUS_BAR_HEIGHT, 0);
        assertInsetByTopBottom(mWindow.getParentFrame(), STATUS_BAR_HEIGHT, 0);
    }

    @Test
    public void layoutWindowLw_fitNavigationBars() {
        mWindow.mAttrs.setFitInsetsTypes(Type.navigationBars());
        addWindowWithRawInsetsState(mWindow);

        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

        assertInsetByTopBottom(mWindow.getDisplayFrame(), 0, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getParentFrame(), 0, NAV_BAR_HEIGHT);
    }

    @Test
    public void layoutWindowLw_fitAllSides() {
        mWindow.mAttrs.setFitInsetsSides(Side.all());
        addWindowWithRawInsetsState(mWindow);

        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

        assertInsetByTopBottom(mWindow.getDisplayFrame(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getParentFrame(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
    }

    @Test
    public void layoutWindowLw_fitTopOnly() {
        mWindow.mAttrs.setFitInsetsSides(Side.TOP);
        addWindowWithRawInsetsState(mWindow);

        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

        assertInsetByTopBottom(mWindow.getDisplayFrame(), STATUS_BAR_HEIGHT, 0);
        assertInsetByTopBottom(mWindow.getParentFrame(), STATUS_BAR_HEIGHT, 0);
    }

    @Test
    public void layoutWindowLw_fitInsetsIgnoringVisibility() {
        final InsetsState state =
                mDisplayContent.getInsetsStateController().getRawInsetsState();
        state.getSource(InsetsState.ITYPE_STATUS_BAR).setVisible(false);
        state.getSource(InsetsState.ITYPE_NAVIGATION_BAR).setVisible(false);
        mWindow.mAttrs.setFitInsetsIgnoringVisibility(true);
        addWindowWithRawInsetsState(mWindow);

        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

        assertInsetByTopBottom(mWindow.getDisplayFrame(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getParentFrame(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
    }

    @Test
    public void layoutWindowLw_fitInsetsNotIgnoringVisibility() {
        final InsetsState state =
                mDisplayContent.getInsetsStateController().getRawInsetsState();
        state.getSource(InsetsState.ITYPE_STATUS_BAR).setVisible(false);
        state.getSource(InsetsState.ITYPE_NAVIGATION_BAR).setVisible(false);
        mWindow.mAttrs.setFitInsetsIgnoringVisibility(false);
        addWindowWithRawInsetsState(mWindow);

        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

        assertInsetByTopBottom(mWindow.getDisplayFrame(), 0, 0);
        assertInsetByTopBottom(mWindow.getParentFrame(), 0, 0);
    }

    @Test
    public void layoutWindowLw_insetParentFrameByIme() {
        final InsetsState state =
                mDisplayContent.getInsetsStateController().getRawInsetsState();
        state.getSource(InsetsState.ITYPE_IME).setVisible(true);
        state.getSource(InsetsState.ITYPE_IME).setFrame(
                0, DISPLAY_HEIGHT - IME_HEIGHT, DISPLAY_WIDTH, DISPLAY_HEIGHT);
        mWindow.mAttrs.privateFlags |= PRIVATE_FLAG_INSET_PARENT_FRAME_BY_IME;
        addWindowWithRawInsetsState(mWindow);

        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

        assertInsetByTopBottom(mWindow.getDisplayFrame(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getParentFrame(), STATUS_BAR_HEIGHT, IME_HEIGHT);
    }

    @Test
    public void layoutWindowLw_fitDisplayCutout() {
        addDisplayCutout();

        mWindow.mAttrs.setFitInsetsTypes(Type.displayCutout());
        mWindow.mAttrs.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        addWindowWithRawInsetsState(mWindow);

        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

        assertInsetByTopBottom(mWindow.getDisplayFrame(), DISPLAY_CUTOUT_HEIGHT, 0);
        assertInsetByTopBottom(mWindow.getParentFrame(), DISPLAY_CUTOUT_HEIGHT, 0);
    }

    @Test
    public void layoutWindowLw_withDisplayCutout() {
        addDisplayCutout();

        mWindow.mAttrs.flags =
                FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR | FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        mWindow.mAttrs.setFitInsetsTypes(0 /* types */);
        addWindowWithRawInsetsState(mWindow);

        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

        assertInsetByTopBottom(mWindow.getParentFrame(), 0, 0);
        assertInsetByTopBottom(mWindow.getDisplayFrame(), 0, 0);
    }

    @Test
    public void layoutWindowLw_withDisplayCutout_never() {
        addDisplayCutout();

        mWindow.mAttrs.flags =
                FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR | FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        mWindow.mAttrs.setFitInsetsTypes(0 /* types */);
        mWindow.mAttrs.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
        addWindowWithRawInsetsState(mWindow);

        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

        assertInsetByTopBottom(mWindow.getParentFrame(), STATUS_BAR_HEIGHT, 0);
        assertInsetByTopBottom(mWindow.getDisplayFrame(), STATUS_BAR_HEIGHT, 0);
    }

    @Test
    public void layoutWindowLw_withDisplayCutout_shortEdges() {
        addDisplayCutout();

        mWindow.mAttrs.flags =
                FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR | FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        mWindow.mAttrs.setFitInsetsTypes(0 /* types */);
        mWindow.mAttrs.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        addWindowWithRawInsetsState(mWindow);

        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

        assertInsetBy(mWindow.getParentFrame(), 0, 0, 0, 0);
        assertInsetBy(mWindow.getDisplayFrame(), 0, 0, 0, 0);
    }

    @Test
    public void layoutWindowLw_withDisplayCutout_always() {
        addDisplayCutout();

        mWindow.mAttrs.flags =
                FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR | FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        mWindow.mAttrs.setFitInsetsTypes(0 /* types */);
        mWindow.mAttrs.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        addWindowWithRawInsetsState(mWindow);

        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

        assertInsetBy(mWindow.getParentFrame(), 0, 0, 0, 0);
        assertInsetBy(mWindow.getDisplayFrame(), 0, 0, 0, 0);
    }

    @Test
    public void layoutWindowLw_withDisplayCutout_layoutFullscreen() {
        addDisplayCutout();

        mWindow.mAttrs.flags =
                FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR | FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        mWindow.mAttrs.setFitInsetsTypes(0 /* types */);
        addWindowWithRawInsetsState(mWindow);

        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

        assertInsetByTopBottom(mWindow.getParentFrame(), 0, 0);
        assertInsetByTopBottom(mWindow.getDisplayFrame(), 0, 0);
    }

    @Test
    public void layoutWindowLw_withDisplayCutout_fullscreen() {
        addDisplayCutout();

        mWindow.mAttrs.flags =
                FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR | FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        mWindow.mAttrs.setFitInsetsTypes(0 /* types */);
        mDisplayContent.getInsetsStateController().getRawInsetsState()
                .getSource(InsetsState.ITYPE_STATUS_BAR).setVisible(false);
        final InsetsState requestedState = new InsetsState();
        requestedState.getSource(ITYPE_STATUS_BAR).setVisible(false);
        mWindow.updateRequestedVisibility(requestedState);
        addWindowWithRawInsetsState(mWindow);

        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

        assertInsetByTopBottom(mWindow.getParentFrame(), STATUS_BAR_HEIGHT, 0);
        assertInsetByTopBottom(mWindow.getDisplayFrame(), STATUS_BAR_HEIGHT, 0);
    }

    @Test
    public void layoutWindowLw_withDisplayCutout_fullscreenInCutout() {
        addDisplayCutout();

        mWindow.mAttrs.flags =
                FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR | FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        mWindow.mAttrs.setFitInsetsTypes(0 /* types */);
        mDisplayContent.getInsetsStateController().getRawInsetsState()
                .getSource(InsetsState.ITYPE_STATUS_BAR).setVisible(false);
        final InsetsState requestedState = new InsetsState();
        requestedState.getSource(ITYPE_STATUS_BAR).setVisible(false);
        mWindow.updateRequestedVisibility(requestedState);
        mWindow.mAttrs.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        addWindowWithRawInsetsState(mWindow);

        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

        assertInsetByTopBottom(mWindow.getParentFrame(), 0, 0);
        assertInsetByTopBottom(mWindow.getDisplayFrame(), 0, 0);
    }


    @Test
    public void layoutWindowLw_withDisplayCutout_landscape() {
        addDisplayCutout();
        setRotation(ROTATION_90, true /* includingWindows */);
        mWindow.mAttrs.flags =
                FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR | FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        mWindow.mAttrs.setFitInsetsTypes(0 /* types */);
        addWindowWithRawInsetsState(mWindow);

        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

        assertInsetBy(mWindow.getParentFrame(), DISPLAY_CUTOUT_HEIGHT, 0, 0, 0);
        assertInsetBy(mWindow.getDisplayFrame(), DISPLAY_CUTOUT_HEIGHT, 0, 0, 0);
    }

    @Test
    public void layoutWindowLw_withDisplayCutout_seascape() {
        addDisplayCutout();
        setRotation(ROTATION_270, true /* includingWindows */);

        mWindow.mAttrs.flags =
                FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR | FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        mWindow.mAttrs.setFitInsetsTypes(0 /* types */);
        addWindowWithRawInsetsState(mWindow);

        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

        assertInsetBy(mWindow.getParentFrame(), 0, 0, DISPLAY_CUTOUT_HEIGHT, 0);
        assertInsetBy(mWindow.getDisplayFrame(), 0, 0, DISPLAY_CUTOUT_HEIGHT, 0);
    }

    @Test
    public void layoutWindowLw_withDisplayCutout_fullscreen_landscape() {
        addDisplayCutout();
        setRotation(ROTATION_90, true /* includingWindows */);

        mWindow.mAttrs.flags =
                FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR | FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        mWindow.mAttrs.setFitInsetsTypes(0 /* types */);
        addWindowWithRawInsetsState(mWindow);

        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

        assertInsetBy(mWindow.getParentFrame(), DISPLAY_CUTOUT_HEIGHT, 0, 0, 0);
    }

    @Test
    public void layoutWindowLw_withDisplayCutout_floatingInScreen() {
        addDisplayCutout();

        mWindow.mAttrs.flags = FLAG_LAYOUT_IN_SCREEN;
        mWindow.mAttrs.setFitInsetsTypes(Type.systemBars() & ~Type.statusBars());
        mWindow.mAttrs.type = TYPE_APPLICATION_OVERLAY;
        mWindow.mAttrs.width = DISPLAY_WIDTH;
        mWindow.mAttrs.height = DISPLAY_HEIGHT;
        addWindowWithRawInsetsState(mWindow);

        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

        assertInsetByTopBottom(mWindow.getParentFrame(), 0, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getDisplayFrame(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
    }

    @Test
    public void layoutWindowLw_withDisplayCutout_fullscreenInCutout_landscape() {
        addDisplayCutout();
        setRotation(ROTATION_90, true /* includingWindows */);

        mWindow.mAttrs.flags =
                FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR | FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        mWindow.mAttrs.setFitInsetsTypes(0 /* types */);
        mWindow.mAttrs.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        addWindowWithRawInsetsState(mWindow);

        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

        assertInsetBy(mWindow.getParentFrame(), 0, 0, 0, 0);
    }

    @Test
    public void layoutWindowLw_withLongEdgeDisplayCutout() {
        addLongEdgeDisplayCutout();

        mWindow.mAttrs.flags =
                FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR | FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        mWindow.mAttrs.setFitInsetsTypes(0 /* types */);
        addWindowWithRawInsetsState(mWindow);

        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

        assertInsetBy(mWindow.getParentFrame(), DISPLAY_CUTOUT_HEIGHT, 0, 0, 0);
        assertInsetBy(mWindow.getDisplayFrame(), DISPLAY_CUTOUT_HEIGHT, 0, 0, 0);
    }

    @Test
    public void layoutWindowLw_withLongEdgeDisplayCutout_never() {
        addLongEdgeDisplayCutout();

        mWindow.mAttrs.flags =
                FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR | FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        mWindow.mAttrs.setFitInsetsTypes(0 /* types */);
        mWindow.mAttrs.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
        addWindowWithRawInsetsState(mWindow);

        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

        assertInsetBy(mWindow.getParentFrame(), DISPLAY_CUTOUT_HEIGHT, 0, 0, 0);
        assertInsetBy(mWindow.getDisplayFrame(), DISPLAY_CUTOUT_HEIGHT, 0, 0, 0);
    }

    @Test
    public void layoutWindowLw_withLongEdgeDisplayCutout_shortEdges() {
        addLongEdgeDisplayCutout();

        mWindow.mAttrs.flags =
                FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR | FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        mWindow.mAttrs.setFitInsetsTypes(0 /* types */);
        mWindow.mAttrs.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        addWindowWithRawInsetsState(mWindow);

        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

        assertInsetBy(mWindow.getParentFrame(), DISPLAY_CUTOUT_HEIGHT, 0, 0, 0);
        assertInsetBy(mWindow.getDisplayFrame(), DISPLAY_CUTOUT_HEIGHT, 0, 0, 0);
    }

    @Test
    public void layoutWindowLw_withLongEdgeDisplayCutout_always() {
        addLongEdgeDisplayCutout();

        mWindow.mAttrs.flags =
                FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR | FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        mWindow.mAttrs.setFitInsetsTypes(0 /* types */);
        mWindow.mAttrs.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        addWindowWithRawInsetsState(mWindow);

        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

        assertInsetBy(mWindow.getParentFrame(), 0, 0, 0, 0);
        assertInsetBy(mWindow.getDisplayFrame(), 0, 0, 0, 0);
    }

    @Test
    public void layoutWindowLw_withForwardInset_SoftInputAdjustNothing() {
        mWindow.mAttrs.flags =
                FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR | FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        mWindow.mAttrs.setFitInsetsTypes(0 /* types */);
        mWindow.mAttrs.softInputMode = SOFT_INPUT_ADJUST_NOTHING;
        addWindowWithRawInsetsState(mWindow);

        final int forwardedInsetBottom = 50;
        mDisplayPolicy.setForwardedInsets(Insets.of(0, 0, 0, forwardedInsetBottom));
        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

        assertInsetBy(mWindow.getParentFrame(), 0, 0, 0, 0);
        assertInsetBy(mWindow.getDisplayFrame(), 0, 0, 0, 0);
    }

    @Test
    public void layoutHint_appWindow() {
        mWindow.mAttrs.setFitInsetsTypes(0);

        final DisplayCutout.ParcelableWrapper outDisplayCutout =
                new DisplayCutout.ParcelableWrapper();
        final InsetsState outState = new InsetsState();

        mDisplayPolicy.getLayoutHint(mWindow.mAttrs, null /* windowToken */, outState,
                true /* localClient */);

        assertThat(outDisplayCutout, is(new DisplayCutout.ParcelableWrapper()));
        assertThat(outState.getSource(ITYPE_STATUS_BAR).getFrame(),
                is(new Rect(0, 0, DISPLAY_WIDTH, STATUS_BAR_HEIGHT)));
        assertThat(outState.getSource(ITYPE_NAVIGATION_BAR).getFrame(),
                is(new Rect(0, DISPLAY_HEIGHT - NAV_BAR_HEIGHT, DISPLAY_WIDTH, DISPLAY_HEIGHT)));
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
        mDisplayPolicy.simulateLayoutDisplay(simulatedDisplayFrames,
                new SparseArray<>() /* barContentFrames */);

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
        final Rect frame = mDisplayPolicy.getInsetsPolicy().getInsetsForWindow(mWindow)
                .getSource(ITYPE_STATUS_BAR).getFrame();
        mDisplayContent.rotateInDifferentOrientationIfNeeded(mWindow.mActivityRecord);
        final Rect rotatedFrame = mDisplayPolicy.getInsetsPolicy().getInsetsForWindow(mWindow)
                .getSource(ITYPE_STATUS_BAR).getFrame();

        assertEquals(DISPLAY_WIDTH, frame.width());
        assertEquals(DISPLAY_HEIGHT, rotatedFrame.width());
    }

    /**
     * Asserts that {@code actual} is inset by the given amounts from the full display rect.
     *
     * Convenience wrapper for when only the top and bottom inset are non-zero.
     */
    private void assertInsetByTopBottom(Rect actual, int expectedInsetTop,
            int expectedInsetBottom) {
        assertInsetBy(actual, 0, expectedInsetTop, 0, expectedInsetBottom);
    }

    /** Asserts that {@code actual} is inset by the given amounts from the full display rect. */
    private void assertInsetBy(Rect actual, int expectedInsetLeft, int expectedInsetTop,
            int expectedInsetRight, int expectedInsetBottom) {
        assertEquals(new Rect(expectedInsetLeft, expectedInsetTop,
                mFrames.mDisplayWidth - expectedInsetRight,
                mFrames.mDisplayHeight - expectedInsetBottom), actual);
    }
}
