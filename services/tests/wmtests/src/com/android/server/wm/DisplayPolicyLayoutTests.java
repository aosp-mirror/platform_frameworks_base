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

import static android.view.Gravity.BOTTOM;
import static android.view.Gravity.LEFT;
import static android.view.Gravity.RIGHT;
import static android.view.Gravity.TOP;
import static android.view.InsetsState.ITYPE_NAVIGATION_BAR;
import static android.view.InsetsState.ITYPE_STATUS_BAR;
import static android.view.InsetsState.ITYPE_TOP_GESTURES;
import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;
import static android.view.View.SYSTEM_UI_FLAG_FULLSCREEN;
import static android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
import static android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
import static android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_FORCE_DRAW_BAR_BACKGROUNDS;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_IS_SCREEN_DECOR;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.spy;
import static org.testng.Assert.expectThrows;

import android.app.WindowConfiguration;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.util.Pair;
import android.view.DisplayCutout;
import android.view.DisplayInfo;
import android.view.InsetsState;
import android.view.ViewRootImpl;
import android.view.WindowInsets.Side;
import android.view.WindowInsets.Type;
import android.view.WindowManager;

import androidx.test.filters.FlakyTest;
import androidx.test.filters.SmallTest;

import com.android.server.policy.WindowManagerPolicy;
import com.android.server.wm.utils.WmDisplayCutout;

import org.junit.After;
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
@RunWith(WindowTestRunner.class)
public class DisplayPolicyLayoutTests extends DisplayPolicyTestsBase {

    private DisplayFrames mFrames;
    private WindowState mWindow;
    private int mRotation = ROTATION_0;
    private boolean mHasDisplayCutout;
    private boolean mIsLongEdgeDisplayCutout;
    private static final int DECOR_WINDOW_INSET = 50;

    @Before
    public void setUp() throws Exception {
        mWindow = spy(createWindow(null, TYPE_APPLICATION, "window"));
        // We only test window frames set by DisplayPolicy, so here prevents computeFrameLw from
        // changing those frames.
        doNothing().when(mWindow).computeFrameLw();

        final WindowManager.LayoutParams attrs = mWindow.mAttrs;
        attrs.width = MATCH_PARENT;
        attrs.height = MATCH_PARENT;
        attrs.format = PixelFormat.TRANSLUCENT;

        updateDisplayFrames();
    }

    @After
    public void tearDown() {
        PolicyControl.setFilters("");
        mWindow.getDisplayContent().mInputMethodTarget = null;
    }

    public void setRotation(int rotation) {
        mRotation = rotation;
        updateDisplayFrames();
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

    private void updateDisplayFrames() {
        mFrames = createDisplayFrames();
        mDisplayContent.mDisplayFrames = mFrames;
    }

    private DisplayFrames createDisplayFrames() {
        final Pair<DisplayInfo, WmDisplayCutout> info = displayInfoAndCutoutForRotation(mRotation,
                mHasDisplayCutout, mIsLongEdgeDisplayCutout);
        return new DisplayFrames(mDisplayContent.getDisplayId(), info.first, info.second);
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
        WindowState win = createWindow(null, TYPE_STATUS_BAR_SUB_PANEL, "StatusBarSubPanel");
        win.mAttrs.providesInsetsTypes = new int[]{ITYPE_STATUS_BAR, ITYPE_TOP_GESTURES};
        win.getFrameLw().set(0, 0, 500, 100);

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
    public void addingWindow_ignoresInsetsTypes_InWindowTypeWithPredefinedInsets() {
        mDisplayPolicy.removeWindowLw(mStatusBarWindow);  // Removes the existing one.
        WindowState win = createWindow(null, TYPE_STATUS_BAR, "StatusBar");
        win.mAttrs.providesInsetsTypes = new int[]{ITYPE_STATUS_BAR};
        win.getFrameLw().set(0, 0, 500, 100);

        addWindow(win);
        mDisplayContent.getInsetsStateController().onPostLayout();

        InsetsSourceProvider provider =
                mDisplayContent.getInsetsStateController().getSourceProvider(ITYPE_STATUS_BAR);
        assertNotEquals(new Rect(0, 0, 500, 100), provider.getSource().getFrame());
    }

    @Test
    public void addingWindow_throwsException_WithMultipleInsetTypes() {
        WindowState win = createWindow(null, TYPE_STATUS_BAR_SUB_PANEL, "StatusBarSubPanel");
        win.mAttrs.providesInsetsTypes = new int[]{ITYPE_STATUS_BAR, ITYPE_NAVIGATION_BAR};

        expectThrows(IllegalArgumentException.class, () -> addWindow(win));
    }

    @Test
    public void layoutWindowLw_fitStatusBars() {
        assumeTrue(ViewRootImpl.sNewInsetsMode == ViewRootImpl.NEW_INSETS_MODE_FULL);

        mWindow.mAttrs.setFitInsetsTypes(Type.statusBars());
        addWindow(mWindow);

        mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

        assertInsetByTopBottom(mWindow.getStableFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getDisplayFrameLw(), STATUS_BAR_HEIGHT, 0);
        assertInsetByTopBottom(mWindow.getParentFrame(), STATUS_BAR_HEIGHT, 0);
        assertInsetByTopBottom(mWindow.getVisibleFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getContentFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getDecorFrame(), 0, 0);
    }

    @Test
    public void layoutWindowLw_fitNavigationBars() {
        assumeTrue(ViewRootImpl.sNewInsetsMode == ViewRootImpl.NEW_INSETS_MODE_FULL);

        mWindow.mAttrs.setFitInsetsTypes(Type.navigationBars());
        addWindow(mWindow);

        mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

        assertInsetByTopBottom(mWindow.getStableFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getDisplayFrameLw(), 0, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getParentFrame(), 0, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getVisibleFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getContentFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getDecorFrame(), 0, 0);
    }

    @Test
    public void layoutWindowLw_fitAllSides() {
        assumeTrue(ViewRootImpl.sNewInsetsMode == ViewRootImpl.NEW_INSETS_MODE_FULL);

        mWindow.mAttrs.setFitInsetsSides(Side.all());
        addWindow(mWindow);

        mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

        assertInsetByTopBottom(mWindow.getStableFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getDisplayFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getParentFrame(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getVisibleFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getContentFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getDecorFrame(), 0, 0);
    }

    @Test
    public void layoutWindowLw_fitTopOnly() {
        assumeTrue(ViewRootImpl.sNewInsetsMode == ViewRootImpl.NEW_INSETS_MODE_FULL);

        mWindow.mAttrs.setFitInsetsSides(Side.TOP);
        addWindow(mWindow);

        mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

        assertInsetByTopBottom(mWindow.getStableFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getDisplayFrameLw(), STATUS_BAR_HEIGHT, 0);
        assertInsetByTopBottom(mWindow.getParentFrame(), STATUS_BAR_HEIGHT, 0);
        assertInsetByTopBottom(mWindow.getVisibleFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getContentFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getDecorFrame(), 0, 0);
    }

    @Test
    public void layoutWindowLw_fitInsetsIgnoringVisibility() {
        assumeTrue(ViewRootImpl.sNewInsetsMode == ViewRootImpl.NEW_INSETS_MODE_FULL);

        final InsetsState state =
                mDisplayContent.getInsetsPolicy().getInsetsForDispatch(mWindow);
        state.getSource(InsetsState.ITYPE_STATUS_BAR).setVisible(false);
        state.getSource(InsetsState.ITYPE_NAVIGATION_BAR).setVisible(false);
        mWindow.mAttrs.setFitInsetsIgnoringVisibility(true);
        addWindow(mWindow);

        mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

        assertInsetByTopBottom(mWindow.getStableFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getDisplayFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getParentFrame(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getVisibleFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getContentFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getDecorFrame(), 0, 0);
    }

    @Test
    public void layoutWindowLw_fitInsetsNotIgnoringVisibility() {
        assumeTrue(ViewRootImpl.sNewInsetsMode == ViewRootImpl.NEW_INSETS_MODE_FULL);

        final InsetsState state =
                mDisplayContent.getInsetsPolicy().getInsetsForDispatch(mWindow);
        state.getSource(InsetsState.ITYPE_STATUS_BAR).setVisible(false);
        state.getSource(InsetsState.ITYPE_NAVIGATION_BAR).setVisible(false);
        mWindow.mAttrs.setFitInsetsIgnoringVisibility(false);
        addWindow(mWindow);

        mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

        assertInsetByTopBottom(mWindow.getStableFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getDisplayFrameLw(), 0, 0);
        assertInsetByTopBottom(mWindow.getParentFrame(), 0, 0);
        assertInsetByTopBottom(mWindow.getVisibleFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getContentFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getDecorFrame(), 0, 0);
    }

    @Test
    public void layoutWindowLw_fitDisplayCutout() {
        assumeTrue(ViewRootImpl.sNewInsetsMode == ViewRootImpl.NEW_INSETS_MODE_FULL);
        addDisplayCutout();

        mWindow.mAttrs.setFitInsetsTypes(Type.displayCutout());
        mWindow.mAttrs.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        addWindow(mWindow);

        mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

        assertInsetByTopBottom(mWindow.getStableFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getDisplayFrameLw(), DISPLAY_CUTOUT_HEIGHT, 0);
        assertInsetByTopBottom(mWindow.getParentFrame(), DISPLAY_CUTOUT_HEIGHT, 0);
        assertInsetByTopBottom(mWindow.getVisibleFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getContentFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getDecorFrame(), 0, 0);
    }

    // TODO(b/118118435): remove after migration
    @Test
    public void layoutWindowLw_appDrawsBarsLegacy() {
        assumeTrue(ViewRootImpl.sNewInsetsMode != ViewRootImpl.NEW_INSETS_MODE_FULL);

        mWindow.mAttrs.flags =
                FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR | FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        addWindow(mWindow);

        mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

        assertInsetByTopBottom(mWindow.getParentFrame(), 0, 0);
        assertInsetByTopBottom(mWindow.getStableFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getContentFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getDecorFrame(), 0, 0);
        assertInsetBy(mWindow.getDisplayFrameLw(), 0, 0, 0, 0);
    }

    // TODO(b/118118435): remove after migration
    @Test
    public void layoutWindowLw_appWontDrawBars() {
        assumeTrue(ViewRootImpl.sNewInsetsMode != ViewRootImpl.NEW_INSETS_MODE_FULL);

        mWindow.mAttrs.flags = FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR;
        addWindow(mWindow);

        mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

        assertInsetByTopBottom(mWindow.getParentFrame(), 0, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getStableFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getContentFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getDecorFrame(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getDisplayFrameLw(), 0, NAV_BAR_HEIGHT);
    }

    // TODO(b/118118435): remove after migration
    @Test
    public void layoutWindowLw_appWontDrawBars_forceStatusAndNav() throws Exception {
        assumeTrue(ViewRootImpl.sNewInsetsMode != ViewRootImpl.NEW_INSETS_MODE_FULL);

        mWindow.mAttrs.flags = FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR;
        mWindow.mAttrs.privateFlags = PRIVATE_FLAG_FORCE_DRAW_BAR_BACKGROUNDS;
        addWindow(mWindow);

        mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

        assertInsetByTopBottom(mWindow.getParentFrame(), 0, 0);
        assertInsetByTopBottom(mWindow.getStableFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getContentFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getDecorFrame(), 0, 0);
        assertInsetByTopBottom(mWindow.getDisplayFrameLw(), 0, 0);
    }

    // TODO(b/118118435): remove after migration (keyguard dialog is not special with the new logic)
    @Test
    public void layoutWindowLw_keyguardDialog_hideNav() {
        mWindow.mAttrs.type = TYPE_KEYGUARD_DIALOG;
        mWindow.mAttrs.flags =
                FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR | FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        mWindow.mAttrs.systemUiVisibility = SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        mWindow.mAttrs.setFitInsetsTypes(0 /* types */);
        addWindow(mWindow);

        mDisplayPolicy.beginLayoutLw(mFrames, 0 /* uiMode */);
        mDisplayPolicy.layoutWindowLw(mWindow, null /* attached */, mFrames);

        assertInsetByTopBottom(mWindow.getParentFrame(), 0, 0);
        assertInsetByTopBottom(mWindow.getStableFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getContentFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getDecorFrame(), 0, 0);
        assertInsetByTopBottom(mWindow.getDisplayFrameLw(), 0, 0);
    }

    @Test
    public void layoutWindowLw_withDisplayCutout() {
        addDisplayCutout();

        mWindow.mAttrs.flags =
                FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR | FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        mWindow.mAttrs.setFitInsetsTypes(0 /* types */);
        addWindow(mWindow);

        mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

        assertInsetByTopBottom(mWindow.getParentFrame(), 0, 0);
        assertInsetByTopBottom(mWindow.getStableFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getContentFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getDecorFrame(), 0, 0);
        assertInsetByTopBottom(mWindow.getDisplayFrameLw(), 0, 0);
    }

    @Test
    public void layoutWindowLw_withDisplayCutout_never() {
        addDisplayCutout();

        mWindow.mAttrs.flags =
                FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR | FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        mWindow.mAttrs.setFitInsetsTypes(0 /* types */);
        mWindow.mAttrs.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
        addWindow(mWindow);

        mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

        assertInsetByTopBottom(mWindow.getParentFrame(), STATUS_BAR_HEIGHT, 0);
        assertInsetByTopBottom(mWindow.getStableFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getContentFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getDecorFrame(), 0, 0);
        assertInsetByTopBottom(mWindow.getDisplayFrameLw(), STATUS_BAR_HEIGHT, 0);
    }

    @Test
    public void layoutWindowLw_withDisplayCutout_shortEdges() {
        addDisplayCutout();

        mWindow.mAttrs.flags =
                FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR | FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        mWindow.mAttrs.setFitInsetsTypes(0 /* types */);
        mWindow.mAttrs.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        addWindow(mWindow);

        mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

        assertInsetBy(mWindow.getParentFrame(), 0, 0, 0, 0);
        assertInsetBy(mWindow.getStableFrameLw(), 0, STATUS_BAR_HEIGHT, 0, NAV_BAR_HEIGHT);
        assertInsetBy(mWindow.getContentFrameLw(), 0, STATUS_BAR_HEIGHT, 0, NAV_BAR_HEIGHT);
        assertInsetBy(mWindow.getDecorFrame(), 0, 0, 0, 0);
        assertInsetBy(mWindow.getDisplayFrameLw(), 0, 0, 0, 0);
    }

    @Test
    public void layoutWindowLw_withDisplayCutout_always() {
        addDisplayCutout();

        mWindow.mAttrs.flags =
                FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR | FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        mWindow.mAttrs.setFitInsetsTypes(0 /* types */);
        mWindow.mAttrs.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        addWindow(mWindow);

        mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

        assertInsetBy(mWindow.getParentFrame(), 0, 0, 0, 0);
        assertInsetBy(mWindow.getStableFrameLw(), 0, STATUS_BAR_HEIGHT, 0, NAV_BAR_HEIGHT);
        assertInsetBy(mWindow.getContentFrameLw(), 0, STATUS_BAR_HEIGHT, 0, NAV_BAR_HEIGHT);
        assertInsetBy(mWindow.getDecorFrame(), 0, 0, 0, 0);
        assertInsetBy(mWindow.getDisplayFrameLw(), 0, 0, 0, 0);
    }

    @Test
    public void layoutWindowLw_withDisplayCutout_layoutFullscreen() {
        addDisplayCutout();

        mWindow.mAttrs.flags =
                FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR | FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        mWindow.mAttrs.setFitInsetsTypes(0 /* types */);
        mWindow.mAttrs.subtreeSystemUiVisibility |= SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        mWindow.mAttrs.setFitInsetsTypes(
                mWindow.mAttrs.getFitInsetsTypes() & ~Type.statusBars());
        addWindow(mWindow);

        mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

        assertInsetByTopBottom(mWindow.getParentFrame(), 0, 0);
        assertInsetByTopBottom(mWindow.getStableFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getContentFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getDecorFrame(), 0, 0);
        assertInsetByTopBottom(mWindow.getDisplayFrameLw(), 0, 0);
    }

    @Test
    public void layoutWindowLw_withDisplayCutout_fullscreen() {
        addDisplayCutout();

        mWindow.mAttrs.flags =
                FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR | FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        mWindow.mAttrs.setFitInsetsTypes(0 /* types */);
        mWindow.mAttrs.subtreeSystemUiVisibility |= SYSTEM_UI_FLAG_FULLSCREEN;
        mDisplayContent.getInsetsPolicy().getInsetsForDispatch(mWindow)
                .getSource(InsetsState.ITYPE_STATUS_BAR).setVisible(false);
        addWindow(mWindow);

        mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

        assertInsetByTopBottom(mWindow.getParentFrame(), STATUS_BAR_HEIGHT, 0);
        assertInsetByTopBottom(mWindow.getStableFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getContentFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getDecorFrame(), 0, 0);
        assertInsetByTopBottom(mWindow.getDisplayFrameLw(), STATUS_BAR_HEIGHT, 0);
    }

    @Test
    public void layoutWindowLw_withDisplayCutout_fullscreenInCutout() {
        addDisplayCutout();

        mWindow.mAttrs.flags =
                FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR | FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        mWindow.mAttrs.setFitInsetsTypes(0 /* types */);
        mWindow.mAttrs.subtreeSystemUiVisibility |= SYSTEM_UI_FLAG_FULLSCREEN;
        mDisplayContent.getInsetsPolicy().getInsetsForDispatch(mWindow)
                .getSource(InsetsState.ITYPE_STATUS_BAR).setVisible(false);
        mWindow.mAttrs.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        addWindow(mWindow);

        mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

        assertInsetByTopBottom(mWindow.getParentFrame(), 0, 0);
        assertInsetByTopBottom(mWindow.getStableFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getContentFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getDecorFrame(), 0, 0);
        assertInsetByTopBottom(mWindow.getDisplayFrameLw(), 0, 0);
    }


    @Test
    public void layoutWindowLw_withDisplayCutout_landscape() {
        addDisplayCutout();
        setRotation(ROTATION_90);

        mWindow.mAttrs.flags =
                FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR | FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        mWindow.mAttrs.setFitInsetsTypes(0 /* types */);
        addWindow(mWindow);

        mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

        assertInsetBy(mWindow.getParentFrame(), DISPLAY_CUTOUT_HEIGHT, 0, 0, 0);
        assertInsetBy(mWindow.getStableFrameLw(), 0, STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT, 0);
        assertInsetBy(mWindow.getContentFrameLw(),
                DISPLAY_CUTOUT_HEIGHT, STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT, 0);
        assertInsetBy(mWindow.getDecorFrame(), 0, 0, 0, 0);
        assertInsetBy(mWindow.getDisplayFrameLw(), DISPLAY_CUTOUT_HEIGHT, 0, 0, 0);
    }

    @Test
    public void layoutWindowLw_withDisplayCutout_seascape() {
        addDisplayCutout();
        setRotation(ROTATION_270);

        mWindow.mAttrs.flags =
                FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR | FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        mWindow.mAttrs.setFitInsetsTypes(0 /* types */);
        addWindow(mWindow);

        mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

        assertInsetBy(mWindow.getParentFrame(), 0, 0, DISPLAY_CUTOUT_HEIGHT, 0);
        assertInsetBy(mWindow.getStableFrameLw(), NAV_BAR_HEIGHT, STATUS_BAR_HEIGHT, 0, 0);
        assertInsetBy(mWindow.getContentFrameLw(),
                NAV_BAR_HEIGHT, STATUS_BAR_HEIGHT, DISPLAY_CUTOUT_HEIGHT, 0);
        assertInsetBy(mWindow.getDecorFrame(), 0, 0, 0, 0);
        assertInsetBy(mWindow.getDisplayFrameLw(), 0, 0, DISPLAY_CUTOUT_HEIGHT, 0);
    }

    @Test
    public void layoutWindowLw_withDisplayCutout_fullscreen_landscape() {
        addDisplayCutout();
        setRotation(ROTATION_90);

        mWindow.mAttrs.flags =
                FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR | FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        mWindow.mAttrs.setFitInsetsTypes(0 /* types */);
        mWindow.mAttrs.subtreeSystemUiVisibility |= SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        mWindow.mAttrs.setFitInsetsTypes(
                mWindow.mAttrs.getFitInsetsTypes() & ~Type.statusBars());
        addWindow(mWindow);

        mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

        assertInsetBy(mWindow.getParentFrame(), DISPLAY_CUTOUT_HEIGHT, 0, 0, 0);
        assertInsetBy(mWindow.getStableFrameLw(), 0, STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT, 0);
        assertInsetBy(mWindow.getContentFrameLw(),
                DISPLAY_CUTOUT_HEIGHT, STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT, 0);
        assertInsetBy(mWindow.getDecorFrame(), 0, 0, 0, 0);
    }

    @Test
    public void layoutWindowLw_withDisplayCutout_floatingInScreen() {
        addDisplayCutout();

        mWindow.mAttrs.flags = FLAG_LAYOUT_IN_SCREEN;
        mWindow.mAttrs.setFitInsetsTypes(Type.systemBars() & ~Type.statusBars());
        mWindow.mAttrs.type = TYPE_APPLICATION_OVERLAY;
        mWindow.mAttrs.width = DISPLAY_WIDTH;
        mWindow.mAttrs.height = DISPLAY_HEIGHT;
        addWindow(mWindow);

        mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

        assertInsetByTopBottom(mWindow.getParentFrame(), 0, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getDisplayFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
    }

    @Test
    public void layoutWindowLw_withDisplayCutout_fullscreenInCutout_landscape() {
        addDisplayCutout();
        setRotation(ROTATION_90);

        mWindow.mAttrs.flags =
                FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR | FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        mWindow.mAttrs.setFitInsetsTypes(0 /* types */);
        mWindow.mAttrs.subtreeSystemUiVisibility |= SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        mWindow.mAttrs.setFitInsetsTypes(
                mWindow.mAttrs.getFitInsetsTypes() & ~Type.statusBars());
        mWindow.mAttrs.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        addWindow(mWindow);

        mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

        assertInsetBy(mWindow.getParentFrame(), 0, 0, 0, 0);
        assertInsetBy(mWindow.getStableFrameLw(), 0, STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT, 0);
        assertInsetBy(mWindow.getContentFrameLw(),
                DISPLAY_CUTOUT_HEIGHT, STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT, 0);
        assertInsetBy(mWindow.getDecorFrame(), 0, 0, 0, 0);
    }

    @Test
    public void layoutWindowLw_withLongEdgeDisplayCutout() {
        addLongEdgeDisplayCutout();

        mWindow.mAttrs.flags =
                FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR | FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        mWindow.mAttrs.setFitInsetsTypes(0 /* types */);
        addWindow(mWindow);

        mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

        assertInsetBy(mWindow.getParentFrame(), DISPLAY_CUTOUT_HEIGHT, 0, 0, 0);
        assertInsetBy(mWindow.getStableFrameLw(), 0, STATUS_BAR_HEIGHT, 0, NAV_BAR_HEIGHT);
        assertInsetBy(mWindow.getContentFrameLw(), DISPLAY_CUTOUT_HEIGHT, STATUS_BAR_HEIGHT, 0,
                NAV_BAR_HEIGHT);
        assertInsetBy(mWindow.getDecorFrame(), 0, 0, 0, 0);
        assertInsetBy(mWindow.getDisplayFrameLw(), DISPLAY_CUTOUT_HEIGHT, 0, 0, 0);
    }

    @Test
    public void layoutWindowLw_withLongEdgeDisplayCutout_never() {
        addLongEdgeDisplayCutout();

        mWindow.mAttrs.flags =
                FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR | FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        mWindow.mAttrs.setFitInsetsTypes(0 /* types */);
        mWindow.mAttrs.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
        addWindow(mWindow);

        mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

        assertInsetBy(mWindow.getParentFrame(), DISPLAY_CUTOUT_HEIGHT, 0, 0, 0);
        assertInsetBy(mWindow.getStableFrameLw(), 0, STATUS_BAR_HEIGHT, 0,
                NAV_BAR_HEIGHT);
        assertInsetBy(mWindow.getContentFrameLw(), DISPLAY_CUTOUT_HEIGHT, STATUS_BAR_HEIGHT, 0,
                NAV_BAR_HEIGHT);
        assertInsetBy(mWindow.getDecorFrame(), 0, 0, 0, 0);
        assertInsetBy(mWindow.getDisplayFrameLw(), DISPLAY_CUTOUT_HEIGHT, 0, 0, 0);
    }

    @Test
    public void layoutWindowLw_withLongEdgeDisplayCutout_shortEdges() {
        addLongEdgeDisplayCutout();

        mWindow.mAttrs.flags =
                FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR | FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        mWindow.mAttrs.setFitInsetsTypes(0 /* types */);
        mWindow.mAttrs.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        addWindow(mWindow);

        mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

        assertInsetBy(mWindow.getParentFrame(), DISPLAY_CUTOUT_HEIGHT, 0, 0, 0);
        assertInsetBy(mWindow.getStableFrameLw(), 0, STATUS_BAR_HEIGHT, 0, NAV_BAR_HEIGHT);
        assertInsetBy(mWindow.getContentFrameLw(), DISPLAY_CUTOUT_HEIGHT, STATUS_BAR_HEIGHT, 0,
                NAV_BAR_HEIGHT);
        assertInsetBy(mWindow.getDecorFrame(), 0, 0, 0, 0);
        assertInsetBy(mWindow.getDisplayFrameLw(), DISPLAY_CUTOUT_HEIGHT, 0, 0, 0);
    }

    @Test
    public void layoutWindowLw_withLongEdgeDisplayCutout_always() {
        addLongEdgeDisplayCutout();

        mWindow.mAttrs.flags =
                FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR | FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        mWindow.mAttrs.setFitInsetsTypes(0 /* types */);
        mWindow.mAttrs.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        addWindow(mWindow);

        mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

        assertInsetBy(mWindow.getParentFrame(), 0, 0, 0, 0);
        assertInsetBy(mWindow.getStableFrameLw(), 0, STATUS_BAR_HEIGHT, 0, NAV_BAR_HEIGHT);
        assertInsetBy(mWindow.getContentFrameLw(), DISPLAY_CUTOUT_HEIGHT, STATUS_BAR_HEIGHT, 0,
                NAV_BAR_HEIGHT);
        assertInsetBy(mWindow.getDecorFrame(), 0, 0, 0, 0);
        assertInsetBy(mWindow.getDisplayFrameLw(), 0, 0, 0, 0);
    }

    @Test
    public void layoutWindowLw_withForwardInset_SoftInputAdjustResize() {
        assumeTrue(ViewRootImpl.sNewInsetsMode == ViewRootImpl.NEW_INSETS_MODE_NONE);

        mWindow.mAttrs.flags =
                FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR | FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        mWindow.mAttrs.setFitInsetsTypes(0 /* types */);
        mWindow.mAttrs.softInputMode = SOFT_INPUT_ADJUST_RESIZE;
        addWindow(mWindow);

        final int forwardedInsetBottom = 50;
        mDisplayPolicy.setForwardedInsets(Insets.of(0, 0, 0, forwardedInsetBottom));
        mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

        assertInsetBy(mWindow.getParentFrame(), 0, 0, 0, 0);
        assertInsetByTopBottom(mWindow.getStableFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getContentFrameLw(),
                STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT + forwardedInsetBottom);
        assertInsetByTopBottom(mWindow.getVisibleFrameLw(),
                STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT + forwardedInsetBottom);
        assertInsetBy(mWindow.getDecorFrame(), 0, 0, 0, 0);
        assertInsetBy(mWindow.getDisplayFrameLw(), 0, 0, 0, 0);
    }

    @Test
    public void layoutWindowLw_withForwardInset_SoftInputAdjustNothing() {
        mWindow.mAttrs.flags =
                FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR | FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        mWindow.mAttrs.setFitInsetsTypes(0 /* types */);
        mWindow.mAttrs.softInputMode = SOFT_INPUT_ADJUST_NOTHING;
        addWindow(mWindow);

        final int forwardedInsetBottom = 50;
        mDisplayPolicy.setForwardedInsets(Insets.of(0, 0, 0, forwardedInsetBottom));
        mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

        assertInsetBy(mWindow.getParentFrame(), 0, 0, 0, 0);
        assertInsetByTopBottom(mWindow.getStableFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getContentFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mWindow.getVisibleFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetBy(mWindow.getDecorFrame(), 0, 0, 0, 0);
        assertInsetBy(mWindow.getDisplayFrameLw(), 0, 0, 0, 0);
    }

    // TODO(b/118118435): remove after removing PolicyControl
    @FlakyTest(bugId = 129711077)
    @Test
    public void layoutWindowLw_withImmersive_SoftInputAdjustResize() {
        assumeTrue(ViewRootImpl.sNewInsetsMode != ViewRootImpl.NEW_INSETS_MODE_FULL);

        synchronized (mWm.mGlobalLock) {
            mWindow.mAttrs.softInputMode = SOFT_INPUT_ADJUST_RESIZE;
            mWindow.mAttrs.flags = 0;
            mWindow.mAttrs.systemUiVisibility =
                    SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | SYSTEM_UI_FLAG_FULLSCREEN | SYSTEM_UI_FLAG_HIDE_NAVIGATION;

            addWindow(mWindow);

            mWindow.getDisplayContent().mInputMethodTarget = mWindow;
            mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
            mFrames.mContent.bottom = mFrames.mVoiceContent.bottom = INPUT_METHOD_WINDOW_TOP;
            mFrames.mCurrent.bottom = INPUT_METHOD_WINDOW_TOP;
            mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

            int bottomInset = mFrames.mDisplayHeight - INPUT_METHOD_WINDOW_TOP;
            assertInsetByTopBottom(mWindow.getParentFrame(), 0, 0);
            assertInsetByTopBottom(mWindow.getContentFrameLw(), 0, 0);
            assertInsetByTopBottom(mWindow.getVisibleFrameLw(), STATUS_BAR_HEIGHT, bottomInset);
        }
    }

    // TODO(b/118118435): remove after removing PolicyControl
    @FlakyTest(bugId = 129711077)
    @Test
    public void layoutWindowLw_withImmersive_SoftInputAdjustNothing() {
        assumeTrue(ViewRootImpl.sNewInsetsMode != ViewRootImpl.NEW_INSETS_MODE_FULL);

        synchronized (mWm.mGlobalLock) {
            mWindow.mAttrs.softInputMode = SOFT_INPUT_ADJUST_NOTHING;
            mWindow.mAttrs.flags = 0;
            mWindow.mAttrs.systemUiVisibility =
                    SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | SYSTEM_UI_FLAG_FULLSCREEN | SYSTEM_UI_FLAG_HIDE_NAVIGATION;

            addWindow(mWindow);

            mWindow.getDisplayContent().mInputMethodTarget = mWindow;
            mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
            mFrames.mContent.bottom = mFrames.mVoiceContent.bottom = INPUT_METHOD_WINDOW_TOP;
            mFrames.mCurrent.bottom = INPUT_METHOD_WINDOW_TOP;
            mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

            assertInsetByTopBottom(mWindow.getParentFrame(), 0, 0);
            assertInsetByTopBottom(mWindow.getContentFrameLw(), 0, 0);
            assertInsetByTopBottom(mWindow.getVisibleFrameLw(), 0, 0);
        }
    }

    // TODO(b/118118435): remove after removing PolicyControl
    @FlakyTest(bugId = 129711077)
    @Test
    public void layoutWindowLw_withForceImmersive_fullscreen() {
        assumeTrue(ViewRootImpl.sNewInsetsMode != ViewRootImpl.NEW_INSETS_MODE_FULL);

        synchronized (mWm.mGlobalLock) {
            mWindow.mAttrs.softInputMode = SOFT_INPUT_ADJUST_RESIZE;
            mWindow.mAttrs.flags = 0;
            mWindow.mAttrs.systemUiVisibility = 0;
            PolicyControl.setFilters(PolicyControl.NAME_IMMERSIVE_FULL + "=*");

            addWindow(mWindow);

            mWindow.getDisplayContent().mInputMethodTarget = mWindow;
            mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
            mFrames.mContent.bottom = mFrames.mVoiceContent.bottom = INPUT_METHOD_WINDOW_TOP;
            mFrames.mCurrent.bottom = INPUT_METHOD_WINDOW_TOP;
            mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

            int bottomInset = mFrames.mDisplayHeight - INPUT_METHOD_WINDOW_TOP;
            assertInsetByTopBottom(mWindow.getParentFrame(), 0, 0);
            assertInsetByTopBottom(mWindow.getContentFrameLw(), 0, 0);
            assertInsetByTopBottom(mWindow.getVisibleFrameLw(), STATUS_BAR_HEIGHT, bottomInset);
        }
    }

    // TODO(b/118118435): remove after removing PolicyControl
    @FlakyTest(bugId = 129711077)
    @Test
    public void layoutWindowLw_withForceImmersive_nonFullscreen() {
        assumeTrue(ViewRootImpl.sNewInsetsMode != ViewRootImpl.NEW_INSETS_MODE_FULL);

        synchronized (mWm.mGlobalLock) {
            mWindow.mAttrs.softInputMode = SOFT_INPUT_ADJUST_RESIZE;
            mWindow.mAttrs.flags = 0;
            mWindow.mAttrs.systemUiVisibility = 0;
            mWindow.mAttrs.width = DISPLAY_WIDTH / 2;
            mWindow.mAttrs.height = DISPLAY_HEIGHT / 2;
            PolicyControl.setFilters(PolicyControl.NAME_IMMERSIVE_FULL + "=*");

            addWindow(mWindow);

            mWindow.getDisplayContent().mInputMethodTarget = mWindow;
            mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
            mFrames.mContent.bottom = mFrames.mVoiceContent.bottom = INPUT_METHOD_WINDOW_TOP;
            mFrames.mCurrent.bottom = INPUT_METHOD_WINDOW_TOP;
            mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

            int bottomInset = mFrames.mDisplayHeight - INPUT_METHOD_WINDOW_TOP;
            assertInsetByTopBottom(mWindow.getParentFrame(), STATUS_BAR_HEIGHT, bottomInset);
            assertInsetByTopBottom(mWindow.getContentFrameLw(), STATUS_BAR_HEIGHT, bottomInset);
            assertInsetByTopBottom(mWindow.getVisibleFrameLw(), STATUS_BAR_HEIGHT, bottomInset);
        }
    }

    @Test
    public void layoutHint_appWindow() {
        mWindow.mAttrs.flags =
                FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR | FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;

        // Initialize DisplayFrames
        mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);

        final Rect outFrame = new Rect();
        final Rect outContentInsets = new Rect();
        final Rect outStableInsets = new Rect();
        final DisplayCutout.ParcelableWrapper outDisplayCutout =
                new DisplayCutout.ParcelableWrapper();

        mDisplayPolicy.getLayoutHint(mWindow.mAttrs, null /* windowToken */, outFrame,
                outContentInsets, outStableInsets, outDisplayCutout);

        assertThat(outFrame, is(mFrames.mUnrestricted));
        assertThat(outContentInsets, is(new Rect(0, STATUS_BAR_HEIGHT, 0, NAV_BAR_HEIGHT)));
        assertThat(outStableInsets, is(new Rect(0, STATUS_BAR_HEIGHT, 0, NAV_BAR_HEIGHT)));
        assertThat(outDisplayCutout, is(new DisplayCutout.ParcelableWrapper()));
    }

    @Test
    public void layoutHint_appWindowInTask() {
        mWindow.mAttrs.flags =
                FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR | FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;

        // Initialize DisplayFrames
        mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);

        final Rect taskBounds = new Rect(100, 100, 200, 200);
        final Task task = mWindow.getTask();
        // Force the bounds because the task may resolve different bounds from Task#setBounds.
        task.getWindowConfiguration().setBounds(taskBounds);

        final Rect outFrame = new Rect();
        final Rect outContentInsets = new Rect();
        final Rect outStableInsets = new Rect();
        final DisplayCutout.ParcelableWrapper outDisplayCutout =
                new DisplayCutout.ParcelableWrapper();

        mDisplayPolicy.getLayoutHint(mWindow.mAttrs, mWindow.mToken, outFrame,
                outContentInsets, outStableInsets, outDisplayCutout);

        assertThat(outFrame, is(taskBounds));
        assertThat(outContentInsets, is(new Rect()));
        assertThat(outStableInsets, is(new Rect()));
        assertThat(outDisplayCutout, is(new DisplayCutout.ParcelableWrapper()));
    }

    @Test
    public void layoutHint_appWindowInTask_outsideContentFrame() {
        mWindow.mAttrs.flags =
                FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR | FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;

        // Initialize DisplayFrames
        mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);

        // Task is in the nav bar area (usually does not happen, but this is similar enough to
        // the possible overlap with the IME)
        final Rect taskBounds = new Rect(100, mFrames.mContent.bottom + 1,
                200, mFrames.mContent.bottom + 10);

        final Task task = mWindow.getTask();
        // Make the task floating.
        task.setWindowingMode(WindowConfiguration.WINDOWING_MODE_FREEFORM);
        // Force the bounds because the task may resolve different bounds from Task#setBounds.
        task.getWindowConfiguration().setBounds(taskBounds);

        final Rect outFrame = new Rect();
        final Rect outContentInsets = new Rect();
        final Rect outStableInsets = new Rect();
        final DisplayCutout.ParcelableWrapper outDisplayCutout =
                new DisplayCutout.ParcelableWrapper();

        mDisplayPolicy.getLayoutHint(mWindow.mAttrs, mWindow.mToken, outFrame, outContentInsets,
                outStableInsets, outDisplayCutout);

        assertThat(outFrame, is(taskBounds));
        assertThat(outContentInsets, is(new Rect()));
        assertThat(outStableInsets, is(new Rect()));
        assertThat(outDisplayCutout, is(new DisplayCutout.ParcelableWrapper()));
    }

    /**
     * Verify that {@link DisplayPolicy#simulateLayoutDisplay} outputs the same display frames as
     * {@link DisplayPolicy#beginLayoutLw}.
     */
    @Test
    public void testSimulateLayoutDisplay() {
        assertSimulateLayoutSameDisplayFrames();
        setRotation(ROTATION_90);
        assertSimulateLayoutSameDisplayFrames();
        addDisplayCutout();
        assertSimulateLayoutSameDisplayFrames();
    }

    private void assertSimulateLayoutSameDisplayFrames() {
        final int uiMode = 0;
        final String prefix = "";
        final InsetsState simulatedInsetsState = new InsetsState();
        final DisplayFrames simulatedDisplayFrames = createDisplayFrames();
        mDisplayPolicy.beginLayoutLw(mFrames, uiMode);
        // Force the display bounds because it is not synced with display frames in policy test.
        mDisplayContent.getWindowConfiguration().setBounds(mFrames.mUnrestricted);
        mDisplayContent.getInsetsStateController().onPostLayout();
        mDisplayPolicy.simulateLayoutDisplay(simulatedDisplayFrames, simulatedInsetsState, uiMode);

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
    public void forceShowSystemBars_clearsSystemUIFlags() {
        mDisplayPolicy.mLastSystemUiFlags |= SYSTEM_UI_FLAG_FULLSCREEN;
        mWindow.mAttrs.subtreeSystemUiVisibility |= SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        mWindow.mAttrs.flags =
                FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR | FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        mWindow.mSystemUiVisibility = SYSTEM_UI_FLAG_FULLSCREEN;
        mDisplayPolicy.setForceShowSystemBars(true);
        addWindow(mWindow);

        mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);
        // triggers updateSystemUiVisibilityLw which will reset the flags as needed
        int finishPostLayoutPolicyLw = mDisplayPolicy.focusChangedLw(mWindow, mWindow);

        assertEquals(WindowManagerPolicy.FINISH_LAYOUT_REDO_LAYOUT, finishPostLayoutPolicyLw);
        assertEquals(0, mDisplayPolicy.mLastSystemUiFlags);
        assertEquals(0, mWindow.mAttrs.systemUiVisibility);
        assertInsetByTopBottom(mWindow.getContentFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
    }

    @Test
    public void testScreenDecorWindows() {
        final WindowState decorWindow = createWindow(null, TYPE_APPLICATION_OVERLAY, "decorWindow");
        mWindow.mAttrs.flags = FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR
                | FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        decorWindow.mAttrs.privateFlags |= PRIVATE_FLAG_IS_SCREEN_DECOR;
        addWindow(decorWindow);
        addWindow(mWindow);

        // Decor on top
        updateDecorWindow(decorWindow, MATCH_PARENT, DECOR_WINDOW_INSET, TOP);
        mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);
        assertInsetByTopBottom(mWindow.getContentFrameLw(), DECOR_WINDOW_INSET, NAV_BAR_HEIGHT);

        // Decor on bottom
        updateDecorWindow(decorWindow, MATCH_PARENT, DECOR_WINDOW_INSET, BOTTOM);
        mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);
        assertInsetByTopBottom(mWindow.getContentFrameLw(), STATUS_BAR_HEIGHT,
                DECOR_WINDOW_INSET);

        // Decor on the left
        updateDecorWindow(decorWindow, DECOR_WINDOW_INSET, MATCH_PARENT, LEFT);
        mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);
        assertInsetBy(mWindow.getContentFrameLw(), DECOR_WINDOW_INSET, STATUS_BAR_HEIGHT, 0,
                NAV_BAR_HEIGHT);

        // Decor on the right
        updateDecorWindow(decorWindow, DECOR_WINDOW_INSET, MATCH_PARENT, RIGHT);
        mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);
        assertInsetBy(mWindow.getContentFrameLw(), 0, STATUS_BAR_HEIGHT, DECOR_WINDOW_INSET,
                NAV_BAR_HEIGHT);

        // Decor not allowed as inset
        updateDecorWindow(decorWindow, DECOR_WINDOW_INSET, DECOR_WINDOW_INSET, TOP);
        mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
        mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);
        assertInsetByTopBottom(mWindow.getContentFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
    }

    private void updateDecorWindow(WindowState decorWindow, int width, int height, int gravity) {
        decorWindow.mAttrs.width = width;
        decorWindow.mAttrs.height = height;
        decorWindow.mAttrs.gravity = gravity;
        decorWindow.setRequestedSize(width, height);
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
