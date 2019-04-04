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

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import android.graphics.Insets;
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
        Rect safeInsets = new Rect(0, 1, 0, 10);
        Rect boundTop = new Rect(80, 0, 120, 10);
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
    public void hasCutout() throws Exception {
        assertTrue(NO_CUTOUT.isEmpty());
        assertFalse(mCutoutTop.isEmpty());
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
    public void testHashCode() throws Exception {
        assertEquals(mCutoutTop.hashCode(), createCutoutTop().hashCode());
        assertNotEquals(mCutoutTop.hashCode(), mCutoutNumbers.hashCode());
    }

    @Test
    public void testEquals() throws Exception {
        assertEquals(mCutoutTop, createCutoutTop());
        assertNotEquals(mCutoutTop, mCutoutNumbers);
    }

    @Test
    public void testToString() throws Exception {
        assertFalse(mCutoutTop.toString().isEmpty());
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

        new ParcelableWrapper(mCutoutTop).writeToParcel(p, 0);
        int posAfterWrite = p.dataPosition();

        p.setDataPosition(0);

        assertEquals(mCutoutTop, ParcelableWrapper.CREATOR.createFromParcel(p).get());
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
        DisplayCutout cached = fromSpec("L1,0 L1,1 L0,1 z", 200, 400, 1f);
        assertThat(fromSpec("L1,0 L1,1 L0,1 z", 200, 400, 1f), sameInstance(cached));
    }

    @Test
    public void fromSpec_wontCacheIfSpecChanges() {
        DisplayCutout cached = fromSpec("L1,0 L1000,1000 L0,1 z", 200, 400, 1f);
        assertThat(fromSpec("L1,0 L1,1 L0,1 z", 200, 400, 1f), not(sameInstance(cached)));
    }

    @Test
    public void fromSpec_wontCacheIfScreenWidthChanges() {
        DisplayCutout cached = fromSpec("L1,0 L1,1 L0,1 z", 2000, 400, 1f);
        assertThat(fromSpec("L1,0 L1,1 L0,1 z", 200, 400, 1f), not(sameInstance(cached)));
    }

    @Test
    public void fromSpec_wontCacheIfScreenHeightChanges() {
        DisplayCutout cached = fromSpec("L1,0 L1,1 L0,1 z", 200, 4000, 1f);
        assertThat(fromSpec("L1,0 L1,1 L0,1 z", 200, 400, 1f), not(sameInstance(cached)));
    }

    @Test
    public void fromSpec_wontCacheIfDensityChanges() {
        DisplayCutout cached = fromSpec("L1,0 L1,1 L0,1 z", 200, 400, 2f);
        assertThat(fromSpec("L1,0 L1,1 L0,1 z", 200, 400, 1f), not(sameInstance(cached)));
    }

    @Test
    public void fromSpec_setsSafeInsets_top() {
        DisplayCutout cutout = fromSpec("M -50,0 v 20 h 100 v -20 z", 200, 400, 2f);
        assertThat(cutout.getSafeInsets(), equalTo(new Rect(0, 20, 0, 0)));
    }

    @Test
    public void fromSpec_setsSafeInsets_top_and_bottom() {
        DisplayCutout cutout = fromSpec("M -50,0 v 20 h 100 v -20 z"
                + "@bottom M -50,0 v -10,0 h 100 v 20 z", 200, 400, 2f);
        assertThat(cutout.getSafeInsets(), equalTo(new Rect(0, 20, 0, 10)));
        assertThat(cutout.getBoundingRectsAll(), equalTo(new Rect[]{
                ZERO_RECT, new Rect(50, 0, 150, 20),
                ZERO_RECT, new Rect(50, 390, 150, 410)
        }));
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

        new ParcelableWrapper(mCutoutTop).writeToParcel(p, 0);
        int posAfterWrite = p.dataPosition();

        p.setDataPosition(0);

        ParcelableWrapper wrapper = new ParcelableWrapper();
        wrapper.readFromParcel(p);

        assertEquals(mCutoutTop, wrapper.get());
        assertEquals(posAfterWrite, p.dataPosition());
    }

    @Test
    public void wrapper_hashcode() throws Exception {
        assertEquals(new ParcelableWrapper(mCutoutTop).hashCode(),
                new ParcelableWrapper(createCutoutTop()).hashCode());
        assertNotEquals(new ParcelableWrapper(mCutoutTop).hashCode(),
                new ParcelableWrapper(mCutoutNumbers).hashCode());
    }

    @Test
    public void wrapper_equals() throws Exception {
        assertEquals(new ParcelableWrapper(mCutoutTop), new ParcelableWrapper(createCutoutTop()));
        assertNotEquals(new ParcelableWrapper(mCutoutTop), new ParcelableWrapper(mCutoutNumbers));
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
}
