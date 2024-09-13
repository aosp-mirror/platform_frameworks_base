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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.view.InsetsSource.FLAG_FORCE_CONSUMING;
import static android.view.InsetsSource.FLAG_FORCE_CONSUMING_OPAQUE_CAPTION_BAR;
import static android.view.InsetsSource.ID_IME;
import static android.view.InsetsSource.SIDE_BOTTOM;
import static android.view.InsetsSource.SIDE_TOP;
import static android.view.RoundedCorner.POSITION_BOTTOM_LEFT;
import static android.view.RoundedCorner.POSITION_BOTTOM_RIGHT;
import static android.view.RoundedCorner.POSITION_TOP_LEFT;
import static android.view.RoundedCorner.POSITION_TOP_RIGHT;
import static android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
import static android.view.WindowInsets.Type.captionBar;
import static android.view.WindowInsets.Type.ime;
import static android.view.WindowInsets.Type.navigationBars;
import static android.view.WindowInsets.Type.statusBars;
import static android.view.WindowInsets.Type.systemBars;
import static android.view.WindowInsets.Type.systemGestures;
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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Parcel;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.SetFlagsRule;
import android.util.SparseIntArray;
import android.view.WindowInsets.Type;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.window.flags.Flags;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

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

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private static final int ID_STATUS_BAR = InsetsSource.createId(
            null /* owner */, 0 /* index */, statusBars());
    private static final int ID_NAVIGATION_BAR = InsetsSource.createId(
            null /* owner */, 0 /* index */, navigationBars());
    private static final int ID_CAPTION_BAR = InsetsSource.createId(
            null /* owner */, 0 /* index */, captionBar());
    private static final int ID_CLIMATE_BAR = InsetsSource.createId(
            null /* owner */, 1 /* index */, statusBars());
    private static final int ID_EXTRA_NAVIGATION_BAR = InsetsSource.createId(
            null /* owner */, 1 /* index */, navigationBars());
    private static final int ID_BOTTOM_GESTURES = InsetsSource.createId(
            null /* owner */, 0 /* index */, systemGestures());
    private static final int ID_EXTRA_CAPTION_BAR = InsetsSource.createId(
            null /* owner */, 2 /* index */, captionBar());

    private final InsetsState mState = new InsetsState();
    private final InsetsState mState2 = new InsetsState();

    @Test
    public void testCalculateInsets() {
        mState.getOrCreateSource(ID_STATUS_BAR, statusBars())
                .setFrame(new Rect(0, 0, 100, 100))
                .setVisible(true);
        mState.getOrCreateSource(ID_IME, ime())
                .setFrame(new Rect(0, 200, 100, 300))
                .setVisible(true);
        SparseIntArray typeSideMap = new SparseIntArray();
        WindowInsets insets = mState.calculateInsets(new Rect(0, 0, 100, 300), null, false,
                SOFT_INPUT_ADJUST_RESIZE, 0, 0, TYPE_APPLICATION, ACTIVITY_TYPE_UNDEFINED,
                typeSideMap);
        assertEquals(Insets.of(0, 100, 0, 100), insets.getSystemWindowInsets());
        assertEquals(Insets.of(0, 100, 0, 100), insets.getInsets(Type.all()));
        assertEquals(SIDE_TOP, typeSideMap.get(ID_STATUS_BAR));
        assertEquals(SIDE_BOTTOM, typeSideMap.get(ID_IME));
        assertEquals(Insets.of(0, 100, 0, 0), insets.getInsets(statusBars()));
        assertEquals(Insets.of(0, 0, 0, 100), insets.getInsets(ime()));
    }

    @Test
    public void testCalculateInsets_imeAndNav() {
        mState.getOrCreateSource(ID_NAVIGATION_BAR, navigationBars())
                .setFrame(new Rect(0, 200, 100, 300))
                .setVisible(true);
        mState.getOrCreateSource(ID_IME, ime())
                .setFrame(new Rect(0, 100, 100, 300))
                .setVisible(true);
        WindowInsets insets = mState.calculateInsets(new Rect(0, 0, 100, 300), null, false,
                SOFT_INPUT_ADJUST_RESIZE, 0, 0, TYPE_APPLICATION, ACTIVITY_TYPE_UNDEFINED,
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
        mState.getOrCreateSource(ID_STATUS_BAR, statusBars())
                .setFrame(new Rect(0, 0, 100, 100))
                .setVisible(true);
        mState.getOrCreateSource(ID_NAVIGATION_BAR, navigationBars())
                .setFrame(new Rect(80, 0, 100, 300))
                .setVisible(true);
        WindowInsets insets = mState.calculateInsets(new Rect(0, 0, 100, 300), null, false,
                0, 0, 0, TYPE_APPLICATION, ACTIVITY_TYPE_UNDEFINED, null);
        assertEquals(Insets.of(0, 100, 20, 0), insets.getSystemWindowInsets());
        assertEquals(Insets.of(0, 100, 0, 0), insets.getInsets(statusBars()));
        assertEquals(Insets.of(0, 0, 20, 0), insets.getInsets(navigationBars()));
    }

    @Test
    public void testCalculateInsets_extraNavRightClimateTop() throws Exception {
        mState.getOrCreateSource(ID_CLIMATE_BAR, statusBars())
                .setFrame(new Rect(0, 0, 100, 100))
                .setVisible(true);
        mState.getOrCreateSource(ID_EXTRA_NAVIGATION_BAR, navigationBars())
                .setFrame(new Rect(80, 0, 100, 300))
                .setVisible(true);
        WindowInsets insets = mState.calculateInsets(new Rect(0, 0, 100, 300), null, false,
                0, 0, 0, TYPE_APPLICATION, ACTIVITY_TYPE_UNDEFINED, null);
        assertEquals(Insets.of(0, 100, 20, 0), insets.getSystemWindowInsets());
        assertEquals(Insets.of(0, 100, 0, 0), insets.getInsets(Type.statusBars()));
        assertEquals(Insets.of(0, 0, 20, 0), insets.getInsets(Type.navigationBars()));
    }

    @Test
    public void testCalculateInsets_imeIgnoredWithoutAdjustResize() {
        mState.getOrCreateSource(ID_STATUS_BAR, statusBars())
                .setFrame(new Rect(0, 0, 100, 100))
                .setVisible(true);
        mState.getOrCreateSource(ID_IME, ime())
                .setFrame(new Rect(0, 200, 100, 300))
                .setVisible(true);
        WindowInsets insets = mState.calculateInsets(new Rect(0, 0, 100, 300), null, false,
                SOFT_INPUT_ADJUST_NOTHING, 0, 0, TYPE_APPLICATION, ACTIVITY_TYPE_UNDEFINED,
                null);
        assertEquals(0, insets.getSystemWindowInsetBottom());
        assertEquals(100, insets.getInsets(ime()).bottom);
        assertTrue(insets.isVisible(ime()));
    }

    @Test
    public void testCalculateInsets_systemUiFlagLayoutStable() {
        mState.getOrCreateSource(ID_STATUS_BAR, statusBars())
                .setFrame(new Rect(0, 0, 100, 100))
                .setVisible(false);
        mState.getOrCreateSource(ID_IME, ime())
                .setFrame(new Rect(0, 200, 100, 300))
                .setVisible(true);
        WindowInsets insets = mState.calculateInsets(new Rect(0, 0, 100, 300), null, false,
                SOFT_INPUT_ADJUST_NOTHING, 0, SYSTEM_UI_FLAG_LAYOUT_STABLE, TYPE_APPLICATION,
                ACTIVITY_TYPE_UNDEFINED, null);
        assertEquals(100, insets.getSystemWindowInsetTop());
        insets = mState.calculateInsets(new Rect(0, 0, 100, 300), null, false,
                SOFT_INPUT_ADJUST_NOTHING, 0, 0 /* legacySystemUiFlags */, TYPE_APPLICATION,
                ACTIVITY_TYPE_UNDEFINED, null);
        assertEquals(0, insets.getSystemWindowInsetTop());
    }

    @Test
    public void testCalculateInsets_systemUiFlagLayoutStable_windowFlagFullscreen() {
        mState.getOrCreateSource(ID_STATUS_BAR, statusBars())
                .setFrame(new Rect(0, 0, 100, 100))
                .setVisible(false);
        WindowInsets insets = mState.calculateInsets(new Rect(0, 0, 100, 300), null, false,
                SOFT_INPUT_ADJUST_NOTHING, FLAG_FULLSCREEN, SYSTEM_UI_FLAG_LAYOUT_STABLE,
                TYPE_APPLICATION, ACTIVITY_TYPE_UNDEFINED, null);
        assertEquals(0, insets.getSystemWindowInsetTop());
        insets = mState.calculateInsets(new Rect(0, 0, 100, 300), null, false,
                SOFT_INPUT_ADJUST_NOTHING, 0, 0 /* legacySystemUiFlags */, TYPE_APPLICATION,
                ACTIVITY_TYPE_UNDEFINED, null);
        assertEquals(0, insets.getSystemWindowInsetTop());
    }

    @Test
    public void testCalculateInsets_flagLayoutNoLimits() {
        mState.getOrCreateSource(ID_STATUS_BAR, statusBars())
                .setFrame(new Rect(0, 0, 100, 100))
                .setVisible(true)
                .setFlags(FLAG_FORCE_CONSUMING);
        WindowInsets insets = mState.calculateInsets(new Rect(0, 0, 100, 300), null, false,
                SOFT_INPUT_ADJUST_NOTHING, FLAG_LAYOUT_NO_LIMITS,
                0 /* legacySystemUiFlags */, TYPE_APPLICATION, ACTIVITY_TYPE_UNDEFINED, null);
        assertEquals(0, insets.getSystemWindowInsetTop());
        insets = mState.calculateInsets(new Rect(0, 0, 100, 300), null, false,
                SOFT_INPUT_ADJUST_NOTHING, FLAG_LAYOUT_NO_LIMITS,
                0 /* legacySystemUiFlags */, TYPE_SYSTEM_ERROR, ACTIVITY_TYPE_UNDEFINED, null);
        assertEquals(100, insets.getSystemWindowInsetTop());
        insets = mState.calculateInsets(new Rect(0, 0, 100, 300), null, false,
                SOFT_INPUT_ADJUST_NOTHING, FLAG_LAYOUT_NO_LIMITS,
                0 /* legacySystemUiFlags */, TYPE_WALLPAPER, ACTIVITY_TYPE_UNDEFINED, null);
        assertEquals(100, insets.getSystemWindowInsetTop());
        insets = mState.calculateInsets(new Rect(0, 0, 100, 300), null, false,
                SOFT_INPUT_ADJUST_NOTHING, FLAG_LAYOUT_NO_LIMITS,
                0 /* legacySystemUiFlags */, TYPE_APPLICATION, ACTIVITY_TYPE_STANDARD, null);
        assertEquals(100, insets.getSystemWindowInsetTop());
    }


    @Test
    public void testCalculateInsets_captionStatusBarOverlap() {
        mState.getOrCreateSource(ID_STATUS_BAR, statusBars())
                .setFrame(new Rect(0, 0, 100, 100))
                .setVisible(true);
        mState.getOrCreateSource(ID_CAPTION_BAR, captionBar())
                .setFrame(new Rect(0, 0, 100, 300))
                .setVisible(true);

        Insets visibleInsets = mState.calculateVisibleInsets(
                new Rect(0, 0, 100, 400), TYPE_APPLICATION, ACTIVITY_TYPE_UNDEFINED,
                SOFT_INPUT_ADJUST_NOTHING, 0 /* windowFlags */);
        assertEquals(Insets.of(0, 300, 0, 0), visibleInsets);
    }

    @Test
    public void testCalculateInsets_captionBarOffset() {
        mState.getOrCreateSource(ID_CAPTION_BAR, captionBar())
                .setFrame(new Rect(0, 0, 100, 300))
                .setVisible(true);

        Insets visibleInsets = mState.calculateVisibleInsets(
                new Rect(0, 0, 150, 400), TYPE_APPLICATION, ACTIVITY_TYPE_UNDEFINED,
                SOFT_INPUT_ADJUST_NOTHING, 0 /* windowFlags */);
        assertEquals(Insets.of(0, 300, 0, 0), visibleInsets);
    }

    @Test
    public void testCalculateInsets_extraNavRightStatusTop() {
        mState.getOrCreateSource(ID_STATUS_BAR, statusBars())
                .setFrame(new Rect(0, 0, 100, 100))
                .setVisible(true);
        mState.getOrCreateSource(ID_EXTRA_NAVIGATION_BAR, navigationBars())
                .setFrame(new Rect(80, 0, 100, 300))
                .setVisible(true);
        WindowInsets insets = mState.calculateInsets(new Rect(0, 0, 100, 300), null, false,
                0, 0, 0, TYPE_APPLICATION, ACTIVITY_TYPE_UNDEFINED, null);
        assertEquals(Insets.of(0, 100, 20, 0), insets.getSystemWindowInsets());
        assertEquals(Insets.of(0, 100, 0, 0), insets.getInsets(statusBars()));
        assertEquals(Insets.of(0, 0, 20, 0), insets.getInsets(navigationBars()));
    }

    @Test
    public void testCalculateInsets_navigationRightClimateTop() {
        mState.getOrCreateSource(ID_CLIMATE_BAR, statusBars())
                .setFrame(new Rect(0, 0, 100, 100))
                .setVisible(true);
        mState.getOrCreateSource(ID_NAVIGATION_BAR, navigationBars())
                .setFrame(new Rect(80, 0, 100, 300))
                .setVisible(true);
        WindowInsets insets = mState.calculateInsets(new Rect(0, 0, 100, 300), null, false,
                0, 0, 0, TYPE_APPLICATION, ACTIVITY_TYPE_UNDEFINED, null);
        assertEquals(Insets.of(0, 100, 20, 0), insets.getSystemWindowInsets());
        assertEquals(Insets.of(0, 100, 0, 0), insets.getInsets(statusBars()));
        assertEquals(Insets.of(0, 0, 20, 0), insets.getInsets(navigationBars()));
    }

    @Test
    public void testCalculateInsets_emptyIme() {
        WindowInsets insets1 = mState.calculateInsets(new Rect(), null, false,
                SOFT_INPUT_ADJUST_NOTHING, 0, 0, TYPE_APPLICATION, ACTIVITY_TYPE_UNDEFINED, null);
        mState.getOrCreateSource(ID_IME, ime());
        WindowInsets insets2 = mState.calculateInsets(new Rect(), null, false,
                SOFT_INPUT_ADJUST_NOTHING, 0, 0, TYPE_APPLICATION, ACTIVITY_TYPE_UNDEFINED, null);
        assertEquals(Insets.NONE, insets1.getInsets(ime()));
        assertEquals(Insets.NONE, insets2.getInsets(ime()));
        assertEquals(insets1, insets2);
    }

    @Test
    public void testStripForDispatch() {
        mState.getOrCreateSource(ID_STATUS_BAR, statusBars())
                .setFrame(new Rect(0, 0, 100, 100))
                .setVisible(true);
        mState.getOrCreateSource(ID_IME, ime())
                .setFrame(new Rect(0, 200, 100, 300))
                .setVisible(true);
        mState.removeSource(ID_IME);
        WindowInsets insets = mState.calculateInsets(new Rect(0, 0, 100, 300), null, false,
                SOFT_INPUT_ADJUST_RESIZE, 0, 0, TYPE_APPLICATION, ACTIVITY_TYPE_UNDEFINED, null);
        assertEquals(0, insets.getSystemWindowInsetBottom());
    }

    @Test
    public void testEquals() {
        final InsetsState state1 = new InsetsState();
        final InsetsState state2 = new InsetsState();
        assertTrue(state1.equals(state2));

        state1.addSource(new InsetsSource(ID_STATUS_BAR, statusBars()));
        assertFalse(state1.equals(state2));

        state2.addSource(new InsetsSource(ID_STATUS_BAR, statusBars()));
        assertTrue(state1.equals(state2));

        state2.addSource(new InsetsSource(ID_NAVIGATION_BAR, navigationBars()));
        assertFalse(state1.equals(state2));
    }

    @Test
    public void testEquals_excludesCaptionBar() {
        final InsetsState state1 = new InsetsState();
        final InsetsState state2 = new InsetsState();

        state1.addSource(new InsetsSource(ID_CAPTION_BAR, captionBar()).setFrame(0, 0, 0, 5));
        assertFalse(state1.equals(
                state2, false /* excludesCaptionBar */, false /* excludesInvisibleIme */));
        assertTrue(state1.equals(
                state2, true /* excludesCaptionBar */, false /* excludesInvisibleIme */));

        state2.addSource(new InsetsSource(ID_CAPTION_BAR, captionBar()).setFrame(0, 0, 0, 10));
        assertFalse(state1.equals(
                state2, false /* excludesCaptionBar */, false /* excludesInvisibleIme */));
        assertTrue(state1.equals(
                state2, true /* excludesCaptionBar */, false /* excludesInvisibleIme */));

        state1.addSource(new InsetsSource(ID_STATUS_BAR, statusBars()));
        state2.addSource(new InsetsSource(ID_STATUS_BAR, statusBars()));
        assertFalse(state1.equals(
                state2, false /* excludesCaptionBar */, false /* excludesInvisibleIme */));
        assertTrue(state1.equals(
                state2, true /* excludesCaptionBar */, false /* excludesInvisibleIme */));
    }

    @Test
    public void testEquals_excludesInvisibleIme() {
        final InsetsState state1 = new InsetsState();
        final InsetsState state2 = new InsetsState();

        final InsetsSource imeSource1 = new InsetsSource(ID_IME, ime()).setVisible(true);
        state1.addSource(imeSource1);
        assertFalse(state1.equals(
                state2, false /* excludesCaptionBar */, false /* excludesInvisibleIme */));
        assertFalse(state1.equals(
                state2, false /* excludesCaptionBar */, true /* excludesInvisibleIme */));

        imeSource1.setVisible(false);
        assertFalse(state1.equals(
                state2, false /* excludesCaptionBar */, false /* excludesInvisibleIme */));
        assertTrue(state1.equals(
                state2, false /* excludesCaptionBar */, true /* excludesInvisibleIme */));

        final InsetsSource imeSource2 = new InsetsSource(ID_IME, ime()).setFrame(0, 0, 0, 10);
        state2.addSource(imeSource2);
        assertFalse(state1.equals(
                state2, false /* excludesCaptionBar */, false /* excludesInvisibleIme */));
        assertTrue(state1.equals(
                state2, false /* excludesCaptionBar */, true /* excludesInvisibleIme */));
    }

    @Test
    public void testEquals_differentRect() {
        mState.getOrCreateSource(ID_STATUS_BAR, statusBars())
                .setFrame(new Rect(0, 0, 100, 100));
        mState2.getOrCreateSource(ID_STATUS_BAR, statusBars())
                .setFrame(new Rect(0, 0, 10, 10));
        assertNotEqualsAndHashCode();
    }

    @Test
    public void testEquals_differentSource() {
        mState.getOrCreateSource(ID_STATUS_BAR, statusBars())
                .setFrame(new Rect(0, 0, 100, 100));
        mState2.getOrCreateSource(ID_IME, ime())
                .setFrame(new Rect(0, 0, 100, 100));
        assertNotEqualsAndHashCode();
    }

    @Test
    public void testEquals_sameButDifferentInsertOrder() {
        mState.getOrCreateSource(ID_STATUS_BAR, statusBars())
                .setFrame(new Rect(0, 0, 100, 100));
        mState.getOrCreateSource(ID_IME, ime())
                .setFrame(new Rect(0, 0, 100, 100));
        mState2.getOrCreateSource(ID_IME, ime())
                .setFrame(new Rect(0, 0, 100, 100));
        mState2.getOrCreateSource(ID_STATUS_BAR, statusBars())
                .setFrame(new Rect(0, 0, 100, 100));
        assertEqualsAndHashCode();
    }

    @Test
    public void testEquals_visibility() {
        mState.getOrCreateSource(ID_IME, ime())
                .setFrame(new Rect(0, 0, 100, 100))
                .setBoundingRects(new Rect[]{ new Rect(0, 0, 10, 10) })
                .setVisible(true);
        mState2.getOrCreateSource(ID_IME, ime())
                .setFrame(new Rect(0, 0, 100, 100))
                .setBoundingRects(new Rect[]{ new Rect(0, 0, 10, 10) });
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
    public void testEquals_sameBoundingRects() {
        mState.getOrCreateSource(ID_CAPTION_BAR, captionBar())
                .setFrame(new Rect(0, 0, 100, 100))
                .setBoundingRects(new Rect[]{ new Rect(0, 0, 10, 10) })
                .setVisible(true);
        mState2.getOrCreateSource(ID_CAPTION_BAR, captionBar())
                .setFrame(new Rect(0, 0, 100, 100))
                .setBoundingRects(new Rect[]{ new Rect(0, 0, 10, 10) });
        assertEqualsAndHashCode();
    }

    @Test
    public void testEquals_differentBoundingRects() {
        mState.getOrCreateSource(ID_CAPTION_BAR, captionBar())
                .setFrame(new Rect(0, 0, 100, 100))
                .setBoundingRects(new Rect[]{ new Rect(0, 0, 10, 10) })
                .setVisible(true);
        mState2.getOrCreateSource(ID_CAPTION_BAR, captionBar())
                .setFrame(new Rect(0, 0, 100, 100))
                .setBoundingRects(new Rect[]{ new Rect(0, 0, 20, 20) });
        assertNotEqualsAndHashCode();
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
    public void testParcelUnparcel() {
        mState.getOrCreateSource(ID_IME, ime())
                .setFrame(new Rect(0, 0, 100, 100))
                .setVisibleFrame(new Rect(0, 0, 50, 10))
                .setVisible(true);
        mState.getOrCreateSource(ID_STATUS_BAR, statusBars())
                .setFrame(new Rect(0, 0, 100, 100));
        Parcel p = Parcel.obtain();
        mState.writeToParcel(p, 0 /* flags */);
        p.setDataPosition(0);
        mState2.readFromParcel(p);
        p.recycle();
        assertEquals(mState, mState2);
    }

    @Test
    public void testCopy() {
        mState.getOrCreateSource(ID_IME, ime())
                .setFrame(new Rect(0, 0, 100, 100))
                .setVisibleFrame(new Rect(0, 0, 50, 10))
                .setVisible(true);
        mState.getOrCreateSource(ID_STATUS_BAR, statusBars())
                .setFrame(new Rect(0, 0, 100, 100));
        mState2.getOrCreateSource(ID_NAVIGATION_BAR, navigationBars())
                .setFrame(new Rect(0, 0, 100, 100));
        mState2.set(mState, true);
        assertEquals(mState, mState2);
    }

    @Test
    public void testCalculateVisibleInsets() {
        mState.getOrCreateSource(ID_STATUS_BAR, statusBars())
                .setFrame(new Rect(0, 0, 100, 100))
                .setVisible(true);
        mState.getOrCreateSource(ID_IME, ime())
                .setFrame(new Rect(0, 200, 100, 300))
                .setVisible(true);

        // Make sure bottom gestures are ignored
        mState.getOrCreateSource(ID_BOTTOM_GESTURES, systemGestures())
                .setFrame(new Rect(0, 100, 100, 300))
                .setVisible(true);
        Insets visibleInsets = mState.calculateVisibleInsets(
                new Rect(0, 0, 100, 300), TYPE_APPLICATION, ACTIVITY_TYPE_UNDEFINED,
                SOFT_INPUT_ADJUST_PAN, 0 /* windowFlags */);
        assertEquals(Insets.of(0, 100, 0, 100), visibleInsets);
    }

    @Test
    public void testCalculateVisibleInsets_adjustNothing() {
        mState.getOrCreateSource(ID_STATUS_BAR, statusBars())
                .setFrame(new Rect(0, 0, 100, 100))
                .setVisible(true);
        mState.getOrCreateSource(ID_IME, ime())
                .setFrame(new Rect(0, 200, 100, 300))
                .setVisible(true);

        // Make sure bottom gestures are ignored
        mState.getOrCreateSource(ID_BOTTOM_GESTURES, systemGestures())
                .setFrame(new Rect(0, 100, 100, 300))
                .setVisible(true);
        Insets visibleInsets = mState.calculateVisibleInsets(
                new Rect(0, 0, 100, 300), TYPE_APPLICATION, ACTIVITY_TYPE_UNDEFINED,
                SOFT_INPUT_ADJUST_NOTHING, 0 /* windowFlags */);
        assertEquals(Insets.of(0, 100, 0, 0), visibleInsets);
    }

    @Test
    public void testCalculateVisibleInsets_layoutNoLimits() {
        mState.getOrCreateSource(ID_STATUS_BAR, statusBars())
                .setFrame(new Rect(0, 0, 100, 100))
                .setVisible(true);
        mState.getOrCreateSource(ID_IME, ime())
                .setFrame(new Rect(0, 200, 100, 300))
                .setVisible(true);

        // Make sure bottom gestures are ignored
        mState.getOrCreateSource(ID_BOTTOM_GESTURES, systemGestures())
                .setFrame(new Rect(0, 100, 100, 300))
                .setVisible(true);
        Insets visibleInsets = mState.calculateVisibleInsets(
                new Rect(0, 0, 100, 300), TYPE_APPLICATION, ACTIVITY_TYPE_UNDEFINED,
                SOFT_INPUT_ADJUST_PAN, FLAG_LAYOUT_NO_LIMITS);
        assertEquals(Insets.NONE, visibleInsets);
    }

    @Test
    public void testCalculateUncontrollableInsets() {
        mState.getOrCreateSource(ID_STATUS_BAR, statusBars())
                .setFrame(new Rect(0, 0, 200, 100))
                .setVisible(true);
        mState.getOrCreateSource(ID_IME, ime())
                .setFrame(new Rect(0, 200, 200, 300))
                .setVisible(true);
        mState.getOrCreateSource(ID_NAVIGATION_BAR, navigationBars())
                .setFrame(new Rect(100, 0, 200, 300))
                .setVisible(true);

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
        DisplayCutout cutout = mState.calculateInsets(new Rect(1, 1, 199, 300), null, false,
                SOFT_INPUT_ADJUST_UNSPECIFIED, 0, 0, TYPE_APPLICATION, ACTIVITY_TYPE_UNDEFINED,
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
                SOFT_INPUT_ADJUST_UNSPECIFIED, 0, 0, TYPE_APPLICATION,
                ACTIVITY_TYPE_UNDEFINED, new SparseIntArray());
        assertEquals(new RoundedCorner(POSITION_TOP_LEFT, 10, 9, 8),
                windowInsets.getRoundedCorner(POSITION_TOP_LEFT));
        assertEquals(new RoundedCorner(POSITION_TOP_RIGHT, 10, 189, 8),
                windowInsets.getRoundedCorner(POSITION_TOP_RIGHT));
        assertEquals(new RoundedCorner(POSITION_BOTTOM_RIGHT, 20, 179, 378),
                windowInsets.getRoundedCorner(POSITION_BOTTOM_RIGHT));
        assertEquals(new RoundedCorner(POSITION_BOTTOM_LEFT, 20, 19, 378),
                windowInsets.getRoundedCorner(POSITION_BOTTOM_LEFT));
    }

    @Test
    public void testCalculateRelativeDisplayShape() {
        mState.setDisplayFrame(new Rect(0, 0, 200, 400));
        mState.setDisplayShape(DisplayShape.createDefaultDisplayShape(200, 400, false));
        WindowInsets windowInsets = mState.calculateInsets(new Rect(10, 20, 200, 400), null, false,
                SOFT_INPUT_ADJUST_UNSPECIFIED, 0, 0, TYPE_APPLICATION,
                ACTIVITY_TYPE_UNDEFINED, new SparseIntArray());

        final DisplayShape expect =
                DisplayShape.createDefaultDisplayShape(200, 400, false).setOffset(-10, -20);
        assertEquals(expect, windowInsets.getDisplayShape());
    }

    private void assertEqualsAndHashCode() {
        assertEquals(mState, mState2);
        assertEquals(mState.hashCode(), mState2.hashCode());
    }

    private void assertNotEqualsAndHashCode() {
        assertNotEquals(mState, mState2);
        assertNotEquals(mState.hashCode(), mState2.hashCode());
    }

    @Test
    public void testTraverse() {
        // The type doesn't matter in this test.
        final int type = statusBars();

        final InsetsState insetsState1 = new InsetsState();
        insetsState1.getOrCreateSource(2000, type);
        insetsState1.getOrCreateSource(1000, type);
        insetsState1.getOrCreateSource(3000, type);

        final InsetsState insetsState2 = new InsetsState();
        insetsState2.getOrCreateSource(3000, type);
        insetsState2.getOrCreateSource(4000, type);
        insetsState2.getOrCreateSource(2000, type);
        insetsState2.getOrCreateSource(5000, type);

        final int[] onStartCalled = {0};
        final int[] onIdMatchCalled = {0};
        final int[] onIdNotFoundInState1Called = {0};
        final int[] onIdNotFoundInState2Called = {0};
        final int[] onFinishCalled = {0};

        InsetsState.traverse(insetsState1, insetsState2, new InsetsState.OnTraverseCallbacks() {
            @Override
            public void onStart(InsetsState state1, InsetsState state2) {
                assertSame("state1 must be the same as insetsState1", state1, insetsState1);
                assertSame("state2 must be the same as insetsState2", state2, insetsState2);
                onStartCalled[0]++;
            }

            @Override
            public void onIdMatch(InsetsSource source1, InsetsSource source2) {
                assertNotNull("source1 must not be null.", source1);
                assertNotNull("source2 must not be null.", source2);
                assertEquals("Source IDs must match.", source1.getId(), source2.getId());
                onIdMatchCalled[0]++;
            }

            @Override
            public void onIdNotFoundInState1(int index2, InsetsSource source2) {
                assertNotNull("source2 must not be null.", source2);
                assertSame(source2 + " must be placed at " + index2 + " of insetsState2",
                        source2, insetsState2.sourceAt(index2));
                assertNull("state1 must not have " + source2,
                        insetsState1.peekSource(source2.getId()));
                onIdNotFoundInState1Called[0]++;
            }

            @Override
            public void onIdNotFoundInState2(int index1, InsetsSource source1) {
                assertNotNull("source1 must not be null.", source1);
                assertSame(source1 + " must be placed at " + index1 + " of insetsState1",
                        source1, insetsState1.sourceAt(index1));
                assertNull("state2 must not have " + source1,
                        insetsState2.peekSource(source1.getId()));
                onIdNotFoundInState2Called[0]++;
            }

            @Override
            public void onFinish(InsetsState state1, InsetsState state2) {
                assertSame("state1 must be the same as insetsState1", state1, insetsState1);
                assertSame("state2 must be the same as insetsState2", state2, insetsState2);
                onFinishCalled[0]++;
            }
        });

        assertEquals(1, onStartCalled[0]);
        assertEquals(2, onIdMatchCalled[0]); // 2000 and 3000.
        assertEquals(2, onIdNotFoundInState1Called[0]); // 4000 and 5000.
        assertEquals(1, onIdNotFoundInState2Called[0]); // 1000.
        assertEquals(1, onFinishCalled[0]);
    }

    @Test
    public void testCalculateBoundingRects() {
        mState.getOrCreateSource(ID_STATUS_BAR, statusBars())
                .setFrame(new Rect(0, 0, 1000, 100))
                .setBoundingRects(null)
                .setVisible(true);
        mState.getOrCreateSource(ID_CAPTION_BAR, captionBar())
                .setFrame(new Rect(0, 0, 1000, 100))
                .setBoundingRects(new Rect[]{
                        new Rect(0, 0, 200, 100),
                        new Rect(800, 0, 1000, 100)
                })
                .setVisible(true);
        SparseIntArray typeSideMap = new SparseIntArray();

        WindowInsets insets = mState.calculateInsets(new Rect(0, 0, 1000, 1000), null, false,
                SOFT_INPUT_ADJUST_RESIZE, 0, 0, TYPE_APPLICATION, ACTIVITY_TYPE_UNDEFINED,
                typeSideMap);

        assertEquals(
                List.of(new Rect(0, 0, 1000, 100)),
                insets.getBoundingRects(Type.statusBars())
        );
        assertEquals(
                List.of(
                        new Rect(0, 0, 200, 100),
                        new Rect(800, 0, 1000, 100)
                ),
                insets.getBoundingRects(Type.captionBar())
        );
    }

    @Test
    public void testCalculateBoundingRects_multipleSourcesOfSameType_concatenated() {
        mState.getOrCreateSource(ID_CAPTION_BAR, captionBar())
                .setFrame(new Rect(0, 0, 1000, 100))
                .setBoundingRects(new Rect[]{new Rect(0, 0, 200, 100)})
                .setVisible(true);
        mState.getOrCreateSource(ID_EXTRA_CAPTION_BAR, captionBar())
                .setFrame(new Rect(0, 0, 1000, 100))
                .setBoundingRects(new Rect[]{new Rect(800, 0, 1000, 100)})
                .setVisible(true);
        SparseIntArray typeSideMap = new SparseIntArray();

        WindowInsets insets = mState.calculateInsets(new Rect(0, 0, 1000, 1000), null, false,
                SOFT_INPUT_ADJUST_RESIZE, 0, 0, TYPE_APPLICATION, ACTIVITY_TYPE_UNDEFINED,
                typeSideMap);

        final List<Rect> expected = List.of(
                new Rect(0, 0, 200, 100),
                new Rect(800, 0, 1000, 100)
        );
        final List<Rect> actual = insets.getBoundingRects(captionBar());
        assertEquals(expected.size(), actual.size());

        // Order does not matter.
        assertTrue(actual.containsAll(expected));
    }

    @Test
    public void testCalculateBoundingRects_captionBar_reportedAsSysGesturesAndTappableElement() {
        mState.getOrCreateSource(ID_CAPTION_BAR, captionBar())
                .setFrame(new Rect(0, 0, 1000, 100))
                .setBoundingRects(new Rect[]{new Rect(0, 0, 200, 100)})
                .setVisible(true);
        SparseIntArray typeSideMap = new SparseIntArray();

        WindowInsets insets = mState.calculateInsets(new Rect(0, 0, 1000, 1000), null, false,
                SOFT_INPUT_ADJUST_RESIZE, 0, 0, TYPE_APPLICATION, ACTIVITY_TYPE_UNDEFINED,
                typeSideMap);

        assertEquals(
                List.of(new Rect(0, 0, 200, 100)),
                insets.getBoundingRects(Type.captionBar())
        );
        assertEquals(
                List.of(new Rect(0, 0, 200, 100)),
                insets.getBoundingRects(Type.systemGestures())
        );
        assertEquals(
                List.of(new Rect(0, 0, 200, 100)),
                insets.getBoundingRects(Type.mandatorySystemGestures())
        );
        assertEquals(
                List.of(new Rect(0, 0, 200, 100)),
                insets.getBoundingRects(Type.tappableElement())
        );

    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_CAPTION_COMPAT_INSET_FORCE_CONSUMPTION_ALWAYS)
    public void testCalculateInsets_forceConsumingCaptionBar() {
        mState.getOrCreateSource(ID_CAPTION_BAR, captionBar())
                .setFrame(new Rect(0, 0, 100, 100))
                .setVisible(true)
                .setFlags(FLAG_FORCE_CONSUMING_OPAQUE_CAPTION_BAR);

        final WindowInsets insets = mState.calculateInsets(new Rect(0, 0, 1000, 1000), null, false,
                SOFT_INPUT_ADJUST_RESIZE, 0, 0, TYPE_APPLICATION, ACTIVITY_TYPE_UNDEFINED,
                new SparseIntArray());

        assertTrue(insets.isForceConsumingOpaqueCaptionBar());
    }
}
