/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static android.view.DisplayCutout.NO_CUTOUT;
import static android.view.DisplayCutout.extractBoundsFromList;
import static android.view.DisplayCutout.fromSpec;
import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_180;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import android.graphics.Insets;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Parcel;
import android.platform.test.annotations.Presubmit;
import android.view.DisplayCutout.ParcelableWrapper;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Collections;

/**
 * Tests for {@link DisplayCutout}.
 *
 * <p>Build/Install/Run:
 *  atest FrameworksCoreTests:DisplayCutoutTest
 *
 * <p>This test class is a part of Window Manager Service tests and specified in
 * {@link com.android.server.wm.test.filters.FrameworksTestsFilter}.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class DisplayCutoutTest {

    private static final Rect ZERO_RECT = new Rect();

    /** This is not a consistent cutout. Useful for verifying insets in one go though. */
    final DisplayCutout mCutoutNumbers = new DisplayCutout(
            Insets.of(5, 6, 7, 8) /* safeInsets */,
            null /* boundLeft */,
            new Rect(9, 0, 10, 1) /* boundTop */,
            null /* boundRight */,
            null /* boundBottom */);

    final DisplayCutout mCutoutTop = createCutoutTop();
    final DisplayCutout mCutoutWithWaterfall = createCutoutWithWaterfall();
    final DisplayCutout mWaterfallOnly = createCutoutWaterfallOnly();

    @Test
    public void testExtractBoundsFromList_left() {
        Rect safeInsets = new Rect(10, 0, 0, 0);
        Rect bound = new Rect(0, 80, 10, 120);
        assertThat(extractBoundsFromList(safeInsets, Collections.singletonList(bound)),
                equalTo(new Rect[]{bound, ZERO_RECT, ZERO_RECT, ZERO_RECT}));
    }

    @Test
    public void testExtractBoundsFromList_top() {
        Rect safeInsets = new Rect(0, 10, 0, 0);
        Rect bound = new Rect(80, 0, 120, 10);
        assertThat(extractBoundsFromList(safeInsets, Collections.singletonList(bound)),
                equalTo(new Rect[]{ZERO_RECT, bound, ZERO_RECT, ZERO_RECT}));
    }

    @Test
    public void testExtractBoundsFromList_right() {
        Rect safeInsets = new Rect(0, 0, 10, 0);
        Rect bound = new Rect(190, 80, 200, 120);
        assertThat(extractBoundsFromList(safeInsets, Collections.singletonList(bound)),
                equalTo(new Rect[]{ZERO_RECT, ZERO_RECT, bound, ZERO_RECT}));
    }

    @Test
    public void testExtractBoundsFromList_bottom() {
        Rect safeInsets = new Rect(0, 0, 0, 10);
        Rect bound = new Rect(80, 190, 120, 200);
        assertThat(extractBoundsFromList(safeInsets, Collections.singletonList(bound)),
                equalTo(new Rect[]{ZERO_RECT, ZERO_RECT, ZERO_RECT, bound}));
    }

    @Test
    public void testExtractBoundsFromList_top_and_bottom() {
        Rect safeInsets = new Rect(0, 10, 0, 10);
        Rect boundTop = new Rect(0, 0, 120, 10);
        Rect boundBottom = new Rect(80, 190, 120, 200);
        assertThat(extractBoundsFromList(safeInsets,
                Arrays.asList(new Rect[]{boundTop, boundBottom})),
                equalTo(new Rect[]{ZERO_RECT, boundTop, ZERO_RECT, boundBottom}));
    }

    @Test
    public void testExtractBoundsFromList_nullBoundingRects() {
        Rect safeInsets = new Rect(0, 0, 0, 0);
        assertThat(extractBoundsFromList(safeInsets, null /* boundingRects */),
                equalTo(new Rect[]{ZERO_RECT, ZERO_RECT, ZERO_RECT, ZERO_RECT}));
    }

    @Test
    public void testExtractBoundsFromList_nullSafeInsets() {
        assertThat(extractBoundsFromList(null /* safeInsets */, Collections.emptyList()),
                equalTo(new Rect[]{ZERO_RECT, ZERO_RECT, ZERO_RECT, ZERO_RECT}));
    }

    @Test
    public void testHasCutout_noCutout() throws Exception {
        assertTrue(NO_CUTOUT.isBoundsEmpty());
        assertThat(NO_CUTOUT.getWaterfallInsets(), equalTo(Insets.NONE));
        assertThat(NO_CUTOUT.getCutoutPath(), nullValue());
    }

    @Test
    public void testHasCutout_cutoutOnly() {
        assertFalse(mCutoutTop.isBoundsEmpty());
    }

    @Test
    public void testHasCutout_cutoutWithWaterfall() {
        assertFalse(mCutoutWithWaterfall.isBoundsEmpty());
    }

    @Test
    public void testHasCutout_waterfallOnly() {
        assertTrue(mWaterfallOnly.isBoundsEmpty());
    }

    @Test
    public void testGetSafeInsets() throws Exception {
        assertEquals(5, mCutoutNumbers.getSafeInsetLeft());
        assertEquals(6, mCutoutNumbers.getSafeInsetTop());
        assertEquals(7, mCutoutNumbers.getSafeInsetRight());
        assertEquals(8, mCutoutNumbers.getSafeInsetBottom());

        assertEquals(new Rect(5, 6, 7, 8), mCutoutNumbers.getSafeInsets());
    }

    @Test
    public void testGetWaterfallInsets() throws Exception {
        DisplayCutout cutout =
                createCutoutWaterfallOnly(Insets.of(5, 6, 7, 8));
        assertEquals(Insets.of(5, 6, 7, 8), cutout.getWaterfallInsets());
    }

    @Test
    public void testGetCutoutPath() throws Exception {
        final String cutoutSpecString = "L1,0 L1,1 L0,1 z";
        final int displayWidth = 200;
        final int displayHeight = 400;
        final float density = 1f;
        final DisplayCutout cutout = fromSpec(cutoutSpecString, displayWidth, displayHeight,
                density, Insets.NONE);
        assertThat(cutout.getCutoutPath(), notNullValue());
    }

    @Test
    public void testGetCutoutPath_caches() throws Exception {
        final String cutoutSpecString = "L1,0 L1,1 L0,1 z";
        final int displayWidth = 200;
        final int displayHeight = 400;
        final float density = 1f;
        final Path first = fromSpec(cutoutSpecString, displayWidth, displayHeight,
                density, Insets.NONE).getCutoutPath();
        final Path second = fromSpec(cutoutSpecString, displayWidth, displayHeight,
                density, Insets.NONE).getCutoutPath();
        assertThat(first, equalTo(second));
    }

    @Test
    public void testGetCutoutPath_wontCacheIfCutoutPathParerInfoChanged() throws Exception {
        final int displayWidth = 200;
        final int displayHeight = 400;
        final float density = 1f;
        final Path first = fromSpec("L1,0 L1,1 L0,1 z", displayWidth, displayHeight,
                density, Insets.NONE).getCutoutPath();
        final Path second = fromSpec("L2,0 L2,2 L0,2 z", displayWidth, displayHeight,
                density, Insets.NONE).getCutoutPath();
        assertThat(first, not(equalTo(second)));
    }

    @Test
    public void testGetCutoutPathParserInfo() throws Exception {
        final String cutoutSpecString = "L1,0 L1,1 L0,1 z";
        final int displayWidth = 200;
        final int displayHeight = 400;
        final float density = 1f;
        final DisplayCutout cutout = fromSpec(cutoutSpecString, displayWidth, displayHeight,
                density, Insets.NONE);
        assertThat(displayWidth, equalTo(cutout.getCutoutPathParserInfo().getDisplayWidth()));
        assertThat(displayHeight, equalTo(cutout.getCutoutPathParserInfo().getDisplayHeight()));
        assertThat(density, equalTo(cutout.getCutoutPathParserInfo().getDensity()));
        assertThat(cutoutSpecString.trim(),
                equalTo(cutout.getCutoutPathParserInfo().getCutoutSpec()));
        assertThat(0, equalTo(cutout.getCutoutPathParserInfo().getRotation()));
        assertThat(1f, equalTo(cutout.getCutoutPathParserInfo().getScale()));
    }

    @Test
    public void testHashCode() throws Exception {
        assertEquals(mCutoutWithWaterfall.hashCode(), createCutoutWithWaterfall().hashCode());
        assertNotEquals(mCutoutWithWaterfall.hashCode(), mCutoutNumbers.hashCode());
    }

    @Test
    public void testEquals() throws Exception {
        assertEquals(mCutoutWithWaterfall, createCutoutWithWaterfall());
        assertNotEquals(mCutoutWithWaterfall, mCutoutNumbers);
    }

    @Test
    public void testToString() throws Exception {
        assertFalse(mCutoutWithWaterfall.toString().isEmpty());
        assertFalse(mCutoutNumbers.toString().isEmpty());
    }

    @Test
    public void inset_immutable() throws Exception {
        DisplayCutout cutout = mCutoutTop.inset(1, 2, 3, 4);

        assertEquals("original instance must not be mutated", createCutoutTop(), mCutoutTop);
    }

    @Test
    public void inset_insets_withLeftCutout() throws Exception {
        DisplayCutout cutout = createCutoutWithInsets(100, 0, 0, 0).inset(1, 2, 3, 4);

        assertEquals(99, cutout.getSafeInsetLeft());
        assertEquals(0, cutout.getSafeInsetTop());
        assertEquals(0, cutout.getSafeInsetRight());
        assertEquals(0, cutout.getSafeInsetBottom());
    }

    @Test
    public void inset_insets_withTopCutout() throws Exception {
        DisplayCutout cutout = mCutoutTop.inset(1, 2, 3, 4);

        assertEquals(0, cutout.getSafeInsetLeft());
        assertEquals(98, cutout.getSafeInsetTop());
        assertEquals(0, cutout.getSafeInsetRight());
        assertEquals(0, cutout.getSafeInsetBottom());
    }

    @Test
    public void inset_insets_withRightCutout() throws Exception {
        DisplayCutout cutout = createCutoutWithInsets(0, 0, 100, 0).inset(1, 2, 3, 4);

        assertEquals(0, cutout.getSafeInsetLeft());
        assertEquals(0, cutout.getSafeInsetTop());
        assertEquals(97, cutout.getSafeInsetRight());
        assertEquals(0, cutout.getSafeInsetBottom());
    }

    @Test
    public void inset_insets_withBottomCutout() throws Exception {
        DisplayCutout cutout = createCutoutWithInsets(0, 0, 0, 100).inset(1, 2, 3, 4);

        assertEquals(cutout.getSafeInsetLeft(), 0);
        assertEquals(cutout.getSafeInsetTop(), 0);
        assertEquals(cutout.getSafeInsetRight(), 0);
        assertEquals(cutout.getSafeInsetBottom(), 96);
    }

    @Test
    public void inset_insets_withWaterfallCutout() throws Exception {
        DisplayCutout cutout = createCutoutWaterfallOnly(Insets.of(0, 10, 0, 10)).inset(1, 2, 3, 4);

        assertEquals(cutout.getSafeInsetLeft(), 0);
        assertEquals(cutout.getSafeInsetTop(), 8);
        assertEquals(cutout.getSafeInsetRight(), 0);
        assertEquals(cutout.getSafeInsetBottom(), 6);
    }

    @Test
    public void inset_insets_consumeInset() throws Exception {
        DisplayCutout cutout = mCutoutTop.inset(0, 1000, 0, 0);

        assertEquals(cutout.getSafeInsetLeft(), 0);
        assertEquals(cutout.getSafeInsetTop(), 0);
        assertEquals(cutout.getSafeInsetRight(), 0);
        assertEquals(cutout.getSafeInsetBottom(), 0);

        assertTrue(cutout.isEmpty());
    }

    @Test
    public void inset_bounds() throws Exception {
        DisplayCutout cutout = mCutoutTop.inset(1, 2, 3, 4);
        assertThat(cutout.getBoundingRectsAll(), equalTo(
                new Rect[]{ ZERO_RECT, new Rect(49, -2, 74, 98), ZERO_RECT, ZERO_RECT }));
    }


    // TODO: Deprecate fromBoundingRect.
    /*
    @Test
    public void fromBoundingPolygon() throws Exception {
        assertEquals(
                new Rect(50, 0, 75, 100),
                DisplayCutout.fromBoundingRect(50, 0, 75, 100).getBounds().getBounds());
    }
    */

    @Test
    public void parcel_unparcel_regular() {
        Parcel p = Parcel.obtain();

        new ParcelableWrapper(mCutoutWithWaterfall).writeToParcel(p, 0);
        int posAfterWrite = p.dataPosition();

        p.setDataPosition(0);

        assertEquals(mCutoutWithWaterfall, ParcelableWrapper.CREATOR.createFromParcel(p).get());
        assertEquals(posAfterWrite, p.dataPosition());
    }

    @Test
    public void parcel_unparcel_withFrame() {
        Parcel p = Parcel.obtain();

        new ParcelableWrapper(mCutoutNumbers).writeToParcel(p, 0);
        int posAfterWrite = p.dataPosition();

        p.setDataPosition(0);

        assertEquals(mCutoutNumbers, ParcelableWrapper.CREATOR.createFromParcel(p).get());
        assertEquals(posAfterWrite, p.dataPosition());
    }

    @Test
    public void fromSpec_caches() {
        Insets waterfallInsets = Insets.of(0, 20, 0, 20);
        DisplayCutout cached = fromSpec("L1,0 L1,1 L0,1 z", 200, 400, 1f, waterfallInsets);
        assertThat(
                fromSpec("L1,0 L1,1 L0,1 z", 200, 400, 1f, waterfallInsets),
                sameInstance(cached));
    }

    @Test
    public void fromSpec_wontCacheIfSpecChanges() {
        DisplayCutout cached = fromSpec("L1,0 L1000,1000 L0,1 z", 200, 400, 1f, Insets.NONE);
        assertThat(
                fromSpec("L1,0 L1,1 L0,1 z", 200, 400, 1f, Insets.NONE),
                not(sameInstance(cached)));
    }

    @Test
    public void fromSpec_wontCacheIfScreenWidthChanges() {
        DisplayCutout cached = fromSpec("L1,0 L1,1 L0,1 z", 2000, 400, 1f, Insets.NONE);
        assertThat(
                fromSpec("L1,0 L1,1 L0,1 z", 200, 400, 1f, Insets.NONE),
                not(sameInstance(cached)));
    }

    @Test
    public void fromSpec_wontCacheIfScreenHeightChanges() {
        DisplayCutout cached = fromSpec("L1,0 L1,1 L0,1 z", 200, 4000, 1f, Insets.NONE);
        assertThat(
                fromSpec("L1,0 L1,1 L0,1 z", 200, 400, 1f, Insets.NONE),
                not(sameInstance(cached)));
    }

    @Test
    public void fromSpec_wontCacheIfDensityChanges() {
        DisplayCutout cached = fromSpec("L1,0 L1,1 L0,1 z", 200, 400, 2f, Insets.NONE);
        assertThat(
                fromSpec("L1,0 L1,1 L0,1 z", 200, 400, 1f, Insets.NONE),
                not(sameInstance(cached)));
    }

    @Test
    public void fromSpec_wontCacheIfWaterfallInsetsChange() {
        Insets waterfallInsets = Insets.of(0, 20, 0, 20);
        DisplayCutout cached = fromSpec("L1,0 L1,1 L0,1 z", 200, 400, 2f, Insets.NONE);
        assertThat(
                fromSpec("L1,0 L1,1 L0,1 z", 200, 400, 2f, waterfallInsets),
                not(sameInstance(cached)));
    }

    @Test
    public void fromSpec_setsSafeInsets_top() {
        DisplayCutout cutout = fromSpec("M -50,0 v 20 h 100 v -20 z", 200, 400, 2f, Insets.NONE);
        assertThat(cutout.getSafeInsets(), equalTo(new Rect(0, 20, 0, 0)));
    }

    @Test
    public void fromSpec_setsSafeInsets_top_and_bottom() {
        DisplayCutout cutout = fromSpec("M -50,0 v 20 h 100 v -20 z"
                + "@bottom M -50,0 v -10,0 h 100 v 20 z", 200, 400, 2f, Insets.NONE);
        assertThat(cutout.getSafeInsets(), equalTo(new Rect(0, 20, 0, 10)));
        assertThat(cutout.getBoundingRectsAll(), equalTo(new Rect[]{
                ZERO_RECT, new Rect(50, 0, 150, 20),
                ZERO_RECT, new Rect(50, 390, 150, 410)
        }));
    }

    @Test
    public void fromSpec_setsSafeInsets_waterfallTopBottom() {
        DisplayCutout cutout = fromSpec("", 200, 400, 2f, Insets.of(0, 30, 0, 30));
        assertThat(cutout.getSafeInsets(), equalTo(new Rect(0, 30, 0, 30)));
    }

    @Test
    public void fromSpec_setsSafeInsets_waterfallLeftRight() {
        DisplayCutout cutout = fromSpec("", 200, 400, 2f, Insets.of(30, 0, 30, 0));
        assertThat(cutout.getSafeInsets(), equalTo(new Rect(30, 0, 30, 0)));
    }

    @Test
    public void fromSpec_setsSafeInsets_waterfall_allEdges() {
        DisplayCutout cutout = fromSpec("", 200, 400, 2f, Insets.of(30, 30, 30, 30));
        assertThat(cutout.getSafeInsets(), equalTo(new Rect(30, 30, 30, 30)));
    }

    @Test
    public void fromSpec_setsSafeInsets_cutoutTopBottom_waterfallTopBottom() {
        DisplayCutout cutout = fromSpec("M -50,0 v 20 h 100 v -20 z"
                + "@bottom M -50,0 v -20,0 h 100 v 20 z", 200, 400, 2f, Insets.of(0, 30, 0, 30));
        assertThat(cutout.getSafeInsets(), equalTo(new Rect(0, 30, 0, 30)));
    }

    @Test
    public void fromSpec_setsSafeInsets_cutoutTopBottom_waterfallLeftRight() {
        DisplayCutout cutout = fromSpec("M -50,0 v 20 h 100 v -20 z"
                + "@bottom M -50,0 v -20,0 h 100 v 20 z", 200, 400, 2f, Insets.of(30, 0, 30, 0));
        assertThat(cutout.getSafeInsets(), equalTo(new Rect(30, 20, 30, 20)));
    }

    @Test
    public void parcel_unparcel_nocutout() {
        Parcel p = Parcel.obtain();

        new ParcelableWrapper(NO_CUTOUT).writeToParcel(p, 0);
        int posAfterWrite = p.dataPosition();

        p.setDataPosition(0);

        assertEquals(NO_CUTOUT, ParcelableWrapper.CREATOR.createFromParcel(p).get());
        assertEquals(posAfterWrite, p.dataPosition());
    }

    @Test
    public void parcel_unparcel_inplace() {
        Parcel p = Parcel.obtain();

        new ParcelableWrapper(mCutoutWithWaterfall).writeToParcel(p, 0);
        int posAfterWrite = p.dataPosition();

        p.setDataPosition(0);

        ParcelableWrapper wrapper = new ParcelableWrapper();
        wrapper.readFromParcel(p);

        assertEquals(mCutoutWithWaterfall, wrapper.get());
        assertEquals(posAfterWrite, p.dataPosition());
    }

    @Test
    public void wrapper_hashcode() throws Exception {
        assertEquals(new ParcelableWrapper(mCutoutWithWaterfall).hashCode(),
                new ParcelableWrapper(createCutoutWithWaterfall()).hashCode());
        assertNotEquals(new ParcelableWrapper(mCutoutWithWaterfall).hashCode(),
                new ParcelableWrapper(mCutoutNumbers).hashCode());
    }

    @Test
    public void wrapper_equals() throws Exception {
        assertEquals(new ParcelableWrapper(mCutoutWithWaterfall),
                new ParcelableWrapper(createCutoutWithWaterfall()));
        assertNotEquals(new ParcelableWrapper(mCutoutWithWaterfall),
                new ParcelableWrapper(mCutoutNumbers));
    }

    @Test
    public void testGetRotatedBounds_top_rot0() {
        int displayW = 500, displayH = 1000;
        DisplayCutout expected = new DisplayCutout(Insets.of(20, 100, 20, 0),
                ZERO_RECT, new Rect(50, 0, 75, 100), ZERO_RECT, ZERO_RECT,
                Insets.of(20, 0, 20, 0));
        DisplayCutout cutout = new DisplayCutout(Insets.of(20, 100, 20, 0),
                ZERO_RECT, new Rect(50, 0, 75, 100), ZERO_RECT, ZERO_RECT,
                Insets.of(20, 0, 20, 0));
        DisplayCutout rotated = cutout.getRotated(displayW, displayH, ROTATION_0, ROTATION_0);
        assertEquals(expected, rotated);
    }

    @Test
    public void testGetRotatedBounds_top_rot90() {
        int displayW = 500, displayH = 1000;
        DisplayCutout expected = new DisplayCutout(Insets.of(100, 20, 0, 20),
                new Rect(0, displayW - 75, 100, displayW - 50), ZERO_RECT, ZERO_RECT, ZERO_RECT,
                Insets.of(0, 20, 0, 20), createParserInfo(ROTATION_90));
        DisplayCutout cutout = new DisplayCutout(Insets.of(20, 100, 20, 0),
                ZERO_RECT, new Rect(50, 0, 75, 100), ZERO_RECT, ZERO_RECT,
                Insets.of(20, 0, 20, 0));
        DisplayCutout rotated = cutout.getRotated(displayW, displayH, ROTATION_0, ROTATION_90);
        assertEquals(expected, rotated);
    }

    @Test
    public void testGetRotatedBounds_top_rot180() {
        int displayW = 500, displayH = 1000;
        DisplayCutout expected = new DisplayCutout(Insets.of(20, 0, 20, 100),
                ZERO_RECT, ZERO_RECT, ZERO_RECT,
                new Rect(displayW - 75, displayH - 100, displayW - 50, displayH - 0),
                Insets.of(20, 0, 20, 0), createParserInfo(ROTATION_180));
        DisplayCutout cutout = new DisplayCutout(Insets.of(20, 100, 20, 0),
                ZERO_RECT, new Rect(50, 0, 75, 100), ZERO_RECT, ZERO_RECT,
                Insets.of(20, 0, 20, 0));
        DisplayCutout rotated = cutout.getRotated(displayW, displayH, ROTATION_0, ROTATION_180);
        assertEquals(expected, rotated);
    }

    @Test
    public void testGetRotatedBounds_top_rot270() {
        int displayW = 500, displayH = 1000;
        DisplayCutout expected = new DisplayCutout(Insets.of(0, 20, 100, 20),
                ZERO_RECT, ZERO_RECT, new Rect(displayH - 100, 50, displayH - 0, 75), ZERO_RECT,
                Insets.of(0, 20, 0, 20), createParserInfo(ROTATION_270));
        DisplayCutout cutout = new DisplayCutout(Insets.of(20, 100, 20, 0),
                ZERO_RECT, new Rect(50, 0, 75, 100), ZERO_RECT, ZERO_RECT,
                Insets.of(20, 0, 20, 0));
        DisplayCutout rotated = cutout.getRotated(displayW, displayH, ROTATION_0, ROTATION_270);
        assertEquals(expected, rotated);
    }

    @Test
    public void testGetRotatedBounds_top_rot90to180() {
        int displayW = 500, displayH = 1000;
        DisplayCutout expected = new DisplayCutout(Insets.of(20, 0, 20, 100),
                ZERO_RECT, ZERO_RECT, ZERO_RECT,
                new Rect(displayW - 75, displayH - 100, displayW - 50, displayH - 0),
                Insets.of(20, 0, 20, 0), createParserInfo(ROTATION_180));
        DisplayCutout cutout = new DisplayCutout(Insets.of(100, 20, 0, 20),
                new Rect(0, displayW - 75, 100, displayW - 50), ZERO_RECT, ZERO_RECT, ZERO_RECT,
                Insets.of(0, 20, 0, 20));
        // starting from 90, so the start displayW/H are swapped:
        DisplayCutout rotated = cutout.getRotated(displayH, displayW, ROTATION_90, ROTATION_180);
        assertEquals(expected, rotated);
    }

    private static DisplayCutout createCutoutTop() {
        return createCutoutWithInsets(0, 100, 0, 0);
    }

    private static DisplayCutout createCutoutWithInsets(int left, int top, int right, int bottom) {
        Insets safeInset = Insets.of(left, top, right, bottom);
        Rect boundTop = new Rect(50, 0, 75, 100);
        return new DisplayCutout(
                safeInset, null /* boundLeft */, boundTop, null /* boundRight */,
                null /* boundBottom */);
    }

    private static DisplayCutout createCutoutWithWaterfall() {
        return new DisplayCutout(
                Insets.of(20, 100, 20, 0),
                ZERO_RECT,
                new Rect(50, 0, 75, 100),
                ZERO_RECT,
                ZERO_RECT,
                Insets.of(20, 0, 20, 0));
    }

    private static DisplayCutout createCutoutWaterfallOnly() {
        return createCutoutWaterfallOnly(Insets.of(20, 0, 20, 0));
    }

    private static DisplayCutout createCutoutWaterfallOnly(Insets waterfallInsets) {
        return new DisplayCutout(
                Insets.of(waterfallInsets.left, waterfallInsets.top, waterfallInsets.right,
                        waterfallInsets.bottom),
                ZERO_RECT,
                ZERO_RECT,
                ZERO_RECT,
                ZERO_RECT,
                waterfallInsets);
    }

    private static DisplayCutout.CutoutPathParserInfo createParserInfo(
            @Surface.Rotation int rotation) {
        return new DisplayCutout.CutoutPathParserInfo(
                0 /* displayWidth */, 0 /* displayHeight */, 0 /* displayWidth */,
                0 /* displayHeight */, 0f /* density */, "" /* cutoutSpec */,
                rotation, 0f /* scale */, 0f /* displaySizeRatio */);
    }
}
