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

package android.view;

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.view.InsetsState.ISIDE_BOTTOM;
import static android.view.InsetsState.ISIDE_TOP;
import static android.view.InsetsState.ITYPE_BOTTOM_GESTURES;
import static android.view.InsetsState.ITYPE_CAPTION_BAR;
import static android.view.InsetsState.ITYPE_CLIMATE_BAR;
import static android.view.InsetsState.ITYPE_EXTRA_NAVIGATION_BAR;
import static android.view.InsetsState.ITYPE_IME;
import static android.view.InsetsState.ITYPE_NAVIGATION_BAR;
import static android.view.InsetsState.ITYPE_STATUS_BAR;
import static android.view.RoundedCorner.POSITION_BOTTOM_LEFT;
import static android.view.RoundedCorner.POSITION_BOTTOM_RIGHT;
import static android.view.RoundedCorner.POSITION_TOP_LEFT;
import static android.view.RoundedCorner.POSITION_TOP_RIGHT;
import static android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
import static android.view.WindowInsets.Type.ime;
import static android.view.WindowInsets.Type.navigationBars;
import static android.view.WindowInsets.Type.statusBars;
import static android.view.WindowInsets.Type.systemBars;
import static android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ERROR;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Parcel;
import android.platform.test.annotations.Presubmit;
import android.util.SparseIntArray;
import android.view.WindowInsets.Type;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for {@link InsetsState}.
 *
 * <p>Build/Install/Run:
 *  atest FrameworksCoreTests:InsetsStateTest
 *
 * <p>This test class is a part of Window Manager Service tests and specified in
 * {@link com.android.server.wm.test.filters.FrameworksTestsFilter}.
 */
@Presubmit
@RunWith(AndroidJUnit4.class)
public class InsetsStateTest {

    private InsetsState mState = new InsetsState();
    private InsetsState mState2 = new InsetsState();

    @Test
    public void testCalculateInsets() {
        mState.getSource(ITYPE_STATUS_BAR).setFrame(new Rect(0, 0, 100, 100));
        mState.getSource(ITYPE_STATUS_BAR).setVisible(true);
        mState.getSource(ITYPE_IME).setFrame(new Rect(0, 200, 100, 300));
        mState.getSource(ITYPE_IME).setVisible(true);
        SparseIntArray typeSideMap = new SparseIntArray();
        WindowInsets insets = mState.calculateInsets(new Rect(0, 0, 100, 300), null, false,
                false, SOFT_INPUT_ADJUST_RESIZE, 0, 0, TYPE_APPLICATION, WINDOWING_MODE_UNDEFINED,
                typeSideMap);
        assertEquals(Insets.of(0, 100, 0, 100), insets.getSystemWindowInsets());
        assertEquals(Insets.of(0, 100, 0, 100), insets.getInsets(Type.all()));
        assertEquals(ISIDE_TOP, typeSideMap.get(ITYPE_STATUS_BAR));
        assertEquals(ISIDE_BOTTOM, typeSideMap.get(ITYPE_IME));
        assertEquals(Insets.of(0, 100, 0, 0), insets.getInsets(statusBars()));
        assertEquals(Insets.of(0, 0, 0, 100), insets.getInsets(ime()));
    }

    @Test
    public void testCalculateInsets_imeAndNav() {
        mState.getSource(ITYPE_NAVIGATION_BAR).setFrame(new Rect(0, 200, 100, 300));
        mState.getSource(ITYPE_NAVIGATION_BAR).setVisible(true);
        mState.getSource(ITYPE_IME).setFrame(new Rect(0, 100, 100, 300));
        mState.getSource(ITYPE_IME).setVisible(true);
        WindowInsets insets = mState.calculateInsets(new Rect(0, 0, 100, 300), null, false,
                false, SOFT_INPUT_ADJUST_RESIZE, 0, 0, TYPE_APPLICATION, WINDOWING_MODE_UNDEFINED,
                null);
        assertEquals(100, insets.getStableInsetBottom());
        assertEquals(Insets.of(0, 0, 0, 100), insets.getInsetsIgnoringVisibility(systemBars()));
        assertEquals(Insets.of(0, 0, 0, 200), insets.getSystemWindowInsets());
        assertEquals(Insets.of(0, 0, 0, 200), insets.getInsets(Type.all()));
        assertEquals(Insets.of(0, 0, 0, 100), insets.getInsets(navigationBars()));
        assertEquals(Insets.of(0, 0, 0, 200), insets.getInsets(ime()));
    }

    @Test
    public void testCalculateInsets_navRightStatusTop() {
        mState.getSource(ITYPE_STATUS_BAR).setFrame(new Rect(0, 0, 100, 100));
        mState.getSource(ITYPE_STATUS_BAR).setVisible(true);
        mState.getSource(ITYPE_NAVIGATION_BAR).setFrame(new Rect(80, 0, 100, 300));
        mState.getSource(ITYPE_NAVIGATION_BAR).setVisible(true);
        WindowInsets insets = mState.calculateInsets(new Rect(0, 0, 100, 300), null, false,
                false, 0, 0, 0, TYPE_APPLICATION, WINDOWING_MODE_UNDEFINED, null);
        assertEquals(Insets.of(0, 100, 20, 0), insets.getSystemWindowInsets());
        assertEquals(Insets.of(0, 100, 0, 0), insets.getInsets(statusBars()));
        assertEquals(Insets.of(0, 0, 20, 0), insets.getInsets(navigationBars()));
    }

    @Test
    public void testCalculateInsets_extraNavRightClimateTop() throws Exception {
        mState.getSource(ITYPE_CLIMATE_BAR).setFrame(new Rect(0, 0, 100, 100));
        mState.getSource(ITYPE_CLIMATE_BAR).setVisible(true);
        mState.getSource(ITYPE_EXTRA_NAVIGATION_BAR).setFrame(new Rect(80, 0, 100, 300));
        mState.getSource(ITYPE_EXTRA_NAVIGATION_BAR).setVisible(true);
        WindowInsets insets = mState.calculateInsets(new Rect(0, 0, 100, 300), null, false,
                false, 0, 0, 0, TYPE_APPLICATION, WINDOWING_MODE_UNDEFINED, null);
        // ITYPE_CLIMATE_BAR is a type of status bar and ITYPE_EXTRA_NAVIGATION_BAR is a type
        // of navigation bar.
        assertEquals(Insets.of(0, 100, 20, 0), insets.getSystemWindowInsets());
        assertEquals(Insets.of(0, 100, 0, 0), insets.getInsets(Type.statusBars()));
        assertEquals(Insets.of(0, 0, 20, 0), insets.getInsets(Type.navigationBars()));
    }

    @Test
    public void testCalculateInsets_imeIgnoredWithoutAdjustResize() {
        mState.getSource(ITYPE_STATUS_BAR).setFrame(new Rect(0, 0, 100, 100));
        mState.getSource(ITYPE_STATUS_BAR).setVisible(true);
        mState.getSource(ITYPE_IME).setFrame(new Rect(0, 200, 100, 300));
        mState.getSource(ITYPE_IME).setVisible(true);
        WindowInsets insets = mState.calculateInsets(new Rect(0, 0, 100, 300), null, false,
                false, SOFT_INPUT_ADJUST_NOTHING, 0, 0, TYPE_APPLICATION, WINDOWING_MODE_UNDEFINED,
                null);
        assertEquals(0, insets.getSystemWindowInsetBottom());
        assertEquals(100, insets.getInsets(ime()).bottom);
        assertTrue(insets.isVisible(ime()));
    }

    @Test
    public void testCalculateInsets_systemUiFlagLayoutStable() {
        mState.getSource(ITYPE_STATUS_BAR).setFrame(new Rect(0, 0, 100, 100));
        mState.getSource(ITYPE_STATUS_BAR).setVisible(false);
        mState.getSource(ITYPE_IME).setFrame(new Rect(0, 200, 100, 300));
        mState.getSource(ITYPE_IME).setVisible(true);
        WindowInsets insets = mState.calculateInsets(new Rect(0, 0, 100, 300), null, false,
                false, SOFT_INPUT_ADJUST_NOTHING, 0, SYSTEM_UI_FLAG_LAYOUT_STABLE, TYPE_APPLICATION,
                WINDOWING_MODE_UNDEFINED, null);
        assertEquals(100, insets.getSystemWindowInsetTop());
        insets = mState.calculateInsets(new Rect(0, 0, 100, 300), null, false, false,
                SOFT_INPUT_ADJUST_NOTHING, 0, 0 /* legacySystemUiFlags */, TYPE_APPLICATION,
                WINDOWING_MODE_UNDEFINED, null);
        assertEquals(0, insets.getSystemWindowInsetTop());
    }

    @Test
    public void testCalculateInsets_systemUiFlagLayoutStable_windowFlagFullscreen() {
        mState.getSource(ITYPE_STATUS_BAR).setFrame(new Rect(0, 0, 100, 100));
        mState.getSource(ITYPE_STATUS_BAR).setVisible(false);
        WindowInsets insets = mState.calculateInsets(new Rect(0, 0, 100, 300), null, false,
                false, SOFT_INPUT_ADJUST_NOTHING, FLAG_FULLSCREEN, SYSTEM_UI_FLAG_LAYOUT_STABLE,
                TYPE_APPLICATION, WINDOWING_MODE_UNDEFINED, null);
        assertEquals(0, insets.getSystemWindowInsetTop());
        insets = mState.calculateInsets(new Rect(0, 0, 100, 300), null, false, false,
                SOFT_INPUT_ADJUST_NOTHING, 0, 0 /* legacySystemUiFlags */, TYPE_APPLICATION,
                WINDOWING_MODE_UNDEFINED, null);
        assertEquals(0, insets.getSystemWindowInsetTop());
    }

    @Test
    public void testCalculateInsets_flagLayoutNoLimits() {
        mState.getSource(ITYPE_STATUS_BAR).setFrame(new Rect(0, 0, 100, 100));
        mState.getSource(ITYPE_STATUS_BAR).setVisible(true);
        WindowInsets insets = mState.calculateInsets(new Rect(0, 0, 100, 300), null, false,
                false, SOFT_INPUT_ADJUST_NOTHING, FLAG_LAYOUT_NO_LIMITS,
                0 /* legacySystemUiFlags */, TYPE_APPLICATION, WINDOWING_MODE_UNDEFINED, null);
        assertEquals(0, insets.getSystemWindowInsetTop());
        insets = mState.calculateInsets(new Rect(0, 0, 100, 300), null, false,
                false, SOFT_INPUT_ADJUST_NOTHING, FLAG_LAYOUT_NO_LIMITS,
                0 /* legacySystemUiFlags */, TYPE_SYSTEM_ERROR, WINDOWING_MODE_UNDEFINED, null);
        assertEquals(100, insets.getSystemWindowInsetTop());
        insets = mState.calculateInsets(new Rect(0, 0, 100, 300), null, false,
                false, SOFT_INPUT_ADJUST_NOTHING, FLAG_LAYOUT_NO_LIMITS,
                0 /* legacySystemUiFlags */, TYPE_WALLPAPER, WINDOWING_MODE_UNDEFINED, null);
        assertEquals(100, insets.getSystemWindowInsetTop());
        insets = mState.calculateInsets(new Rect(0, 0, 100, 300), null, false,
                false, SOFT_INPUT_ADJUST_NOTHING, FLAG_LAYOUT_NO_LIMITS,
                0 /* legacySystemUiFlags */, TYPE_APPLICATION, WINDOWING_MODE_FREEFORM, null);
        assertEquals(100, insets.getSystemWindowInsetTop());
    }


    @Test
    public void testCalculateInsets_captionStatusBarOverlap() {
        mState.getSource(ITYPE_STATUS_BAR).setFrame(new Rect(0, 0, 100, 100));
        mState.getSource(ITYPE_STATUS_BAR).setVisible(true);
        mState.getSource(ITYPE_CAPTION_BAR).setFrame(new Rect(0, 0, 100, 300));
        mState.getSource(ITYPE_CAPTION_BAR).setVisible(true);

        Insets visibleInsets = mState.calculateVisibleInsets(
                new Rect(0, 0, 100, 400), TYPE_APPLICATION, WINDOWING_MODE_UNDEFINED,
                SOFT_INPUT_ADJUST_NOTHING, 0 /* windowFlags */);
        assertEquals(Insets.of(0, 300, 0, 0), visibleInsets);
    }

    @Test
    public void testCalculateInsets_captionBarOffset() {
        mState.getSource(ITYPE_CAPTION_BAR).setFrame(new Rect(0, 0, 100, 300));
        mState.getSource(ITYPE_CAPTION_BAR).setVisible(true);

        Insets visibleInsets = mState.calculateVisibleInsets(
                new Rect(0, 0, 150, 400), TYPE_APPLICATION, WINDOWING_MODE_UNDEFINED,
                SOFT_INPUT_ADJUST_NOTHING, 0 /* windowFlags */);
        assertEquals(Insets.of(0, 300, 0, 0), visibleInsets);
    }

    @Test
    public void testCalculateInsets_extraNavRightStatusTop() {
        mState.getSource(ITYPE_STATUS_BAR).setFrame(new Rect(0, 0, 100, 100));
        mState.getSource(ITYPE_STATUS_BAR).setVisible(true);
        mState.getSource(ITYPE_EXTRA_NAVIGATION_BAR).setFrame(new Rect(80, 0, 100, 300));
        mState.getSource(ITYPE_EXTRA_NAVIGATION_BAR).setVisible(true);
        WindowInsets insets = mState.calculateInsets(new Rect(0, 0, 100, 300), null, false,
                false, 0, 0, 0, TYPE_APPLICATION, WINDOWING_MODE_UNDEFINED, null);
        assertEquals(Insets.of(0, 100, 20, 0), insets.getSystemWindowInsets());
        assertEquals(Insets.of(0, 100, 0, 0), insets.getInsets(statusBars()));
        assertEquals(Insets.of(0, 0, 20, 0), insets.getInsets(navigationBars()));
    }

    @Test
    public void testCalculateInsets_navigationRightClimateTop() {
        mState.getSource(ITYPE_CLIMATE_BAR).setFrame(new Rect(0, 0, 100, 100));
        mState.getSource(ITYPE_CLIMATE_BAR).setVisible(true);
        mState.getSource(ITYPE_NAVIGATION_BAR).setFrame(new Rect(80, 0, 100, 300));
        mState.getSource(ITYPE_NAVIGATION_BAR).setVisible(true);
        WindowInsets insets = mState.calculateInsets(new Rect(0, 0, 100, 300), null, false,
                false, 0, 0, 0, TYPE_APPLICATION, WINDOWING_MODE_UNDEFINED, null);
        assertEquals(Insets.of(0, 100, 20, 0), insets.getSystemWindowInsets());
        assertEquals(Insets.of(0, 100, 0, 0), insets.getInsets(statusBars()));
        assertEquals(Insets.of(0, 0, 20, 0), insets.getInsets(navigationBars()));
    }

    @Test
    public void testStripForDispatch() {
        mState.getSource(ITYPE_STATUS_BAR).setFrame(new Rect(0, 0, 100, 100));
        mState.getSource(ITYPE_STATUS_BAR).setVisible(true);
        mState.getSource(ITYPE_IME).setFrame(new Rect(0, 200, 100, 300));
        mState.getSource(ITYPE_IME).setVisible(true);
        mState.removeSource(ITYPE_IME);
        WindowInsets insets = mState.calculateInsets(new Rect(0, 0, 100, 300), null, false, false,
                SOFT_INPUT_ADJUST_RESIZE, 0, 0, TYPE_APPLICATION, WINDOWING_MODE_UNDEFINED, null);
        assertEquals(0, insets.getSystemWindowInsetBottom());
    }

    @Test
    public void testEquals_differentRect() {
        mState.getSource(ITYPE_STATUS_BAR).setFrame(new Rect(0, 0, 100, 100));
        mState2.getSource(ITYPE_STATUS_BAR).setFrame(new Rect(0, 0, 10, 10));
        assertNotEqualsAndHashCode();
    }

    @Test
    public void testEquals_differentSource() {
        mState.getSource(ITYPE_STATUS_BAR).setFrame(new Rect(0, 0, 100, 100));
        mState2.getSource(ITYPE_IME).setFrame(new Rect(0, 0, 100, 100));
        assertNotEqualsAndHashCode();
    }

    @Test
    public void testEquals_sameButDifferentInsertOrder() {
        mState.getSource(ITYPE_STATUS_BAR).setFrame(new Rect(0, 0, 100, 100));
        mState.getSource(ITYPE_IME).setFrame(new Rect(0, 0, 100, 100));
        mState2.getSource(ITYPE_IME).setFrame(new Rect(0, 0, 100, 100));
        mState2.getSource(ITYPE_STATUS_BAR).setFrame(new Rect(0, 0, 100, 100));
        assertEqualsAndHashCode();
    }

    @Test
    public void testEquals_visibility() {
        mState.getSource(ITYPE_IME).setFrame(new Rect(0, 0, 100, 100));
        mState.getSource(ITYPE_IME).setVisible(true);
        mState2.getSource(ITYPE_IME).setFrame(new Rect(0, 0, 100, 100));
        assertNotEqualsAndHashCode();
    }

    @Test
    public void testEquals_differentFrame() {
        mState.setDisplayFrame(new Rect(0, 1, 2, 3));
        mState.setDisplayFrame(new Rect(4, 5, 6, 7));
        assertNotEqualsAndHashCode();
    }

    @Test
    public void testEquals_sameFrame() {
        mState.setDisplayFrame(new Rect(0, 1, 2, 3));
        mState2.setDisplayFrame(new Rect(0, 1, 2, 3));
        assertEqualsAndHashCode();
    }

    @Test
    public void testEquals_samePrivacyIndicator() {
        Rect one = new Rect(0, 1, 2, 3);
        Rect two = new Rect(4, 5, 6, 7);
        Rect[] bounds = new Rect[] { one, two, one, two };
        mState.setPrivacyIndicatorBounds(new PrivacyIndicatorBounds(bounds, 0));
        mState2.setPrivacyIndicatorBounds(new PrivacyIndicatorBounds(bounds, 0));
        assertEqualsAndHashCode();
    }

    @Test
    public void testEquals_differentPrivacyIndicatorStaticBounds() {
        Rect one = new Rect(0, 1, 2, 3);
        Rect two = new Rect(4, 5, 6, 7);
        Rect three = new Rect(8, 9, 10, 11);
        Rect[] boundsOne = new Rect[] { one, two, one, two };
        Rect[] boundsDifferent = new Rect[] { one, two, three, one };
        Rect[] boundsDifferentOrder = new Rect[] { two, one, one, two };
        Rect[] boundsDifferentLength = new Rect[] { one, two };
        Rect[] boundsNull = new Rect[4];
        mState.setPrivacyIndicatorBounds(new PrivacyIndicatorBounds(boundsOne, 0));

        mState2.setPrivacyIndicatorBounds(new PrivacyIndicatorBounds(boundsDifferent, 0));
        assertNotEqualsAndHashCode();

        mState2.setPrivacyIndicatorBounds(new PrivacyIndicatorBounds(boundsDifferentOrder, 0));
        assertNotEqualsAndHashCode();

        mState2.setPrivacyIndicatorBounds(new PrivacyIndicatorBounds(boundsDifferentLength, 0));
        assertNotEqualsAndHashCode();

        mState2.setPrivacyIndicatorBounds(new PrivacyIndicatorBounds(boundsNull, 0));
        assertNotEqualsAndHashCode();
    }

    @Test
    public void testEquals_differentPrivacyIndicatorRotation() {
        Rect one = new Rect(0, 1, 2, 3);
        Rect two = new Rect(4, 5, 6, 7);
        Rect[] arr = new Rect[] { one, two, one, two};
        mState.setPrivacyIndicatorBounds(new PrivacyIndicatorBounds(arr, 0));
        mState2.setPrivacyIndicatorBounds(new PrivacyIndicatorBounds(arr, 1));
        assertNotEqualsAndHashCode();
    }

    @Test
    public void testEquals_excludeInvisibleIme() {
        mState.getSource(ITYPE_IME).setFrame(new Rect(0, 0, 100, 100));
        mState.getSource(ITYPE_IME).setVisible(false);
        mState2.getSource(ITYPE_IME).setFrame(new Rect(0, 0, 100, 200));
        mState2.getSource(ITYPE_IME).setVisible(false);
        assertTrue(mState2.equals(mState, true, true /* excludeInvisibleIme */));
    }

    @Test
    public void testParcelUnparcel() {
        mState.getSource(ITYPE_IME).setFrame(new Rect(0, 0, 100, 100));
        mState.getSource(ITYPE_IME).setVisibleFrame(new Rect(0, 0, 50, 10));
        mState.getSource(ITYPE_IME).setVisible(true);
        mState.getSource(ITYPE_STATUS_BAR).setFrame(new Rect(0, 0, 100, 100));
        Parcel p = Parcel.obtain();
        mState.writeToParcel(p, 0 /* flags */);
        p.setDataPosition(0);
        mState2.readFromParcel(p);
        p.recycle();
        assertEquals(mState, mState2);
    }

    @Test
    public void testCopy() {
        mState.getSource(ITYPE_IME).setFrame(new Rect(0, 0, 100, 100));
        mState.getSource(ITYPE_IME).setVisibleFrame(new Rect(0, 0, 50, 10));
        mState.getSource(ITYPE_IME).setVisible(true);
        mState.getSource(ITYPE_STATUS_BAR).setFrame(new Rect(0, 0, 100, 100));
        mState2.getSource(ITYPE_NAVIGATION_BAR).setFrame(new Rect(0, 0, 100, 100));
        mState2.set(mState, true);
        assertEquals(mState, mState2);
    }

    @Test
    public void testGetDefaultVisibility() {
        assertTrue(InsetsState.getDefaultVisibility(ITYPE_STATUS_BAR));
        assertTrue(InsetsState.getDefaultVisibility(ITYPE_NAVIGATION_BAR));
        assertTrue(InsetsState.getDefaultVisibility(ITYPE_CLIMATE_BAR));
        assertTrue(InsetsState.getDefaultVisibility(ITYPE_EXTRA_NAVIGATION_BAR));
        assertTrue(InsetsState.getDefaultVisibility(ITYPE_CAPTION_BAR));
        assertFalse(InsetsState.getDefaultVisibility(ITYPE_IME));
    }

    @Test
    public void testCalculateVisibleInsets() {
        mState.getSource(ITYPE_STATUS_BAR).setFrame(new Rect(0, 0, 100, 100));
        mState.getSource(ITYPE_STATUS_BAR).setVisible(true);
        mState.getSource(ITYPE_IME).setFrame(new Rect(0, 200, 100, 300));
        mState.getSource(ITYPE_IME).setVisible(true);

        // Make sure bottom gestures are ignored
        mState.getSource(ITYPE_BOTTOM_GESTURES).setFrame(new Rect(0, 100, 100, 300));
        mState.getSource(ITYPE_BOTTOM_GESTURES).setVisible(true);
        Insets visibleInsets = mState.calculateVisibleInsets(
                new Rect(0, 0, 100, 300), TYPE_APPLICATION, WINDOWING_MODE_UNDEFINED,
                SOFT_INPUT_ADJUST_PAN, 0 /* windowFlags */);
        assertEquals(Insets.of(0, 100, 0, 100), visibleInsets);
    }

    @Test
    public void testCalculateVisibleInsets_adjustNothing() {
        mState.getSource(ITYPE_STATUS_BAR).setFrame(new Rect(0, 0, 100, 100));
        mState.getSource(ITYPE_STATUS_BAR).setVisible(true);
        mState.getSource(ITYPE_IME).setFrame(new Rect(0, 200, 100, 300));
        mState.getSource(ITYPE_IME).setVisible(true);

        // Make sure bottom gestures are ignored
        mState.getSource(ITYPE_BOTTOM_GESTURES).setFrame(new Rect(0, 100, 100, 300));
        mState.getSource(ITYPE_BOTTOM_GESTURES).setVisible(true);
        Insets visibleInsets = mState.calculateVisibleInsets(
                new Rect(0, 0, 100, 300), TYPE_APPLICATION, WINDOWING_MODE_UNDEFINED,
                SOFT_INPUT_ADJUST_NOTHING, 0 /* windowFlags */);
        assertEquals(Insets.of(0, 100, 0, 0), visibleInsets);
    }

    @Test
    public void testCalculateVisibleInsets_layoutNoLimits() {
        mState.getSource(ITYPE_STATUS_BAR).setFrame(new Rect(0, 0, 100, 100));
        mState.getSource(ITYPE_STATUS_BAR).setVisible(true);
        mState.getSource(ITYPE_IME).setFrame(new Rect(0, 200, 100, 300));
        mState.getSource(ITYPE_IME).setVisible(true);

        // Make sure bottom gestures are ignored
        mState.getSource(ITYPE_BOTTOM_GESTURES).setFrame(new Rect(0, 100, 100, 300));
        mState.getSource(ITYPE_BOTTOM_GESTURES).setVisible(true);
        Insets visibleInsets = mState.calculateVisibleInsets(
                new Rect(0, 0, 100, 300), TYPE_APPLICATION, WINDOWING_MODE_UNDEFINED,
                SOFT_INPUT_ADJUST_PAN, FLAG_LAYOUT_NO_LIMITS);
        assertEquals(Insets.NONE, visibleInsets);
    }

    @Test
    public void testCalculateUncontrollableInsets() {
        mState.getSource(ITYPE_STATUS_BAR).setFrame(new Rect(0, 0, 200, 100));
        mState.getSource(ITYPE_STATUS_BAR).setVisible(true);
        mState.getSource(ITYPE_IME).setFrame(new Rect(0, 200, 200, 300));
        mState.getSource(ITYPE_IME).setVisible(true);
        mState.getSource(ITYPE_NAVIGATION_BAR).setFrame(new Rect(100, 0, 200, 300));
        mState.getSource(ITYPE_NAVIGATION_BAR).setVisible(true);

        mState.setDisplayFrame(new Rect(0, 0, 200, 300));
        assertEquals(0,
                mState.calculateUncontrollableInsetsFromFrame(new Rect(0, 0, 200, 300)));
        assertEquals(statusBars() | ime(),
                mState.calculateUncontrollableInsetsFromFrame(new Rect(0, 50, 200, 250)));
        assertEquals(navigationBars(),
                mState.calculateUncontrollableInsetsFromFrame(new Rect(50, 0, 150, 300)));
    }

    @Test
    public void testCalculateRelativeCutout() {
        mState.setDisplayFrame(new Rect(0, 0, 200, 300));
        mState.setDisplayCutout(new DisplayCutout(Insets.of(1, 2, 3, 4),
                new Rect(0, 0, 1, 2),
                new Rect(0, 0, 1, 2),
                new Rect(197, 296, 200, 300),
                new Rect(197, 296, 200, 300)));
        DisplayCutout cutout = mState.calculateInsets(new Rect(1, 1, 199, 300), null, false, false,
                SOFT_INPUT_ADJUST_UNSPECIFIED, 0, 0, TYPE_APPLICATION, WINDOWING_MODE_UNDEFINED,
                new SparseIntArray()).getDisplayCutout();
        assertEquals(0, cutout.getSafeInsetLeft());
        assertEquals(1, cutout.getSafeInsetTop());
        assertEquals(2, cutout.getSafeInsetRight());
        assertEquals(4, cutout.getSafeInsetBottom());
        assertEquals(new Rect(-1, -1, 0, 1),
                cutout.getBoundingRectLeft());
        assertEquals(new Rect(-1, -1, 0, 1),
                cutout.getBoundingRectTop());
        assertEquals(new Rect(196, 295, 199, 299),
                cutout.getBoundingRectRight());
        assertEquals(new Rect(196, 295, 199, 299),
                cutout.getBoundingRectBottom());
    }

    @Test
    public void testCalculateRelativeRoundedCorners() {
        mState.setDisplayFrame(new Rect(0, 0, 200, 400));
        mState.setRoundedCorners(new RoundedCorners(
                new RoundedCorner(POSITION_TOP_LEFT, 10, 10, 10),
                new RoundedCorner(POSITION_TOP_RIGHT, 10, 190, 10),
                new RoundedCorner(POSITION_BOTTOM_RIGHT, 20, 180, 380),
                new RoundedCorner(POSITION_BOTTOM_LEFT, 20, 20, 380)));
        WindowInsets windowInsets = mState.calculateInsets(new Rect(1, 2, 197, 396), null, false,
                false, SOFT_INPUT_ADJUST_UNSPECIFIED, 0, 0, TYPE_APPLICATION,
                WINDOWING_MODE_UNDEFINED, new SparseIntArray());
        assertEquals(new RoundedCorner(POSITION_TOP_LEFT, 10, 9, 8),
                windowInsets.getRoundedCorner(POSITION_TOP_LEFT));
        assertEquals(new RoundedCorner(POSITION_TOP_RIGHT, 10, 189, 8),
                windowInsets.getRoundedCorner(POSITION_TOP_RIGHT));
        assertEquals(new RoundedCorner(POSITION_BOTTOM_RIGHT, 20, 179, 378),
                windowInsets.getRoundedCorner(POSITION_BOTTOM_RIGHT));
        assertEquals(new RoundedCorner(POSITION_BOTTOM_LEFT, 20, 19, 378),
                windowInsets.getRoundedCorner(POSITION_BOTTOM_LEFT));
    }

    private void assertEqualsAndHashCode() {
        assertEquals(mState, mState2);
        assertEquals(mState.hashCode(), mState2.hashCode());
    }

    private void assertNotEqualsAndHashCode() {
        assertNotEquals(mState, mState2);
        assertNotEquals(mState.hashCode(), mState2.hashCode());
    }
}
