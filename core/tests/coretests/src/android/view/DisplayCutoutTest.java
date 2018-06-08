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
import static android.view.DisplayCutout.fromSpec;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;
import android.os.Parcel;
import android.platform.test.annotations.Presubmit;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.view.DisplayCutout.ParcelableWrapper;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class DisplayCutoutTest {

    /** This is not a consistent cutout. Useful for verifying insets in one go though. */
    final DisplayCutout mCutoutNumbers = new DisplayCutout(
            new Rect(1, 2, 3, 4),
            Arrays.asList(new Rect(5, 6, 7, 8)));

    final DisplayCutout mCutoutTop = createCutoutTop();

    @Test
    public void hasCutout() throws Exception {
        assertTrue(NO_CUTOUT.isEmpty());
        assertFalse(mCutoutTop.isEmpty());
    }

    @Test
    public void getSafeInsets() throws Exception {
        assertEquals(1, mCutoutNumbers.getSafeInsetLeft());
        assertEquals(2, mCutoutNumbers.getSafeInsetTop());
        assertEquals(3, mCutoutNumbers.getSafeInsetRight());
        assertEquals(4, mCutoutNumbers.getSafeInsetBottom());

        assertEquals(new Rect(1, 2, 3, 4), mCutoutNumbers.getSafeInsets());
    }

    @Test
    public void getBoundingRect() throws Exception {
        assertEquals(new Rect(50, 0, 75, 100), mCutoutTop.getBounds().getBounds());
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

        assertEquals(cutout.getSafeInsetLeft(), 99);
        assertEquals(cutout.getSafeInsetTop(), 0);
        assertEquals(cutout.getSafeInsetRight(), 0);
        assertEquals(cutout.getSafeInsetBottom(), 0);
    }

    @Test
    public void inset_insets_withTopCutout() throws Exception {
        DisplayCutout cutout = mCutoutTop.inset(1, 2, 3, 4);

        assertEquals(cutout.getSafeInsetLeft(), 0);
        assertEquals(cutout.getSafeInsetTop(), 98);
        assertEquals(cutout.getSafeInsetRight(), 0);
        assertEquals(cutout.getSafeInsetBottom(), 0);
    }

    @Test
    public void inset_insets_withRightCutout() throws Exception {
        DisplayCutout cutout = createCutoutWithInsets(0, 0, 100, 0).inset(1, 2, 3, 4);

        assertEquals(cutout.getSafeInsetLeft(), 0);
        assertEquals(cutout.getSafeInsetTop(), 0);
        assertEquals(cutout.getSafeInsetRight(), 97);
        assertEquals(cutout.getSafeInsetBottom(), 0);
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

        assertEquals(new Rect(49, -2, 74, 98), cutout.getBounds().getBounds());
    }

    @Test
    public void fromBoundingPolygon() throws Exception {
        assertEquals(
                new Rect(50, 0, 75, 100),
                DisplayCutout.fromBoundingRect(50, 0, 75, 100).getBounds().getBounds());
    }

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
        return new DisplayCutout(
                new Rect(left, top, right, bottom),
                Arrays.asList(new Rect(50, 0, 75, 100)));
    }
}
