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

import static android.view.InsetsState.INSET_SIDE_BOTTOM;
import static android.view.InsetsState.INSET_SIDE_TOP;
import static android.view.InsetsState.TYPE_IME;
import static android.view.InsetsState.TYPE_NAVIGATION_BAR;
import static android.view.InsetsState.TYPE_SIDE_BAR_1;
import static android.view.InsetsState.TYPE_SIDE_BAR_2;
import static android.view.InsetsState.TYPE_SIDE_BAR_3;
import static android.view.InsetsState.TYPE_TOP_BAR;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.junit.Assert.assertNotEquals;

import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Parcel;
import android.platform.test.annotations.Presubmit;
import android.util.SparseIntArray;
import android.view.WindowInsets.Type;

import androidx.test.filters.FlakyTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@Presubmit
@FlakyTest(detail = "Promote once confirmed non-flaky")
@RunWith(AndroidJUnit4.class)
public class InsetsStateTest {

    private InsetsState mState = new InsetsState();
    private InsetsState mState2 = new InsetsState();

    @Test
    public void testCalculateInsets() {
        mState.getSource(TYPE_TOP_BAR).setFrame(new Rect(0, 0, 100, 100));
        mState.getSource(TYPE_TOP_BAR).setVisible(true);
        mState.getSource(TYPE_IME).setFrame(new Rect(0, 200, 100, 300));
        mState.getSource(TYPE_IME).setVisible(true);
        SparseIntArray typeSideMap = new SparseIntArray();
        WindowInsets insets = mState.calculateInsets(new Rect(0, 0, 100, 300), false, false,
                DisplayCutout.NO_CUTOUT, null, null, typeSideMap);
        assertEquals(Insets.of(0, 100, 0, 100), insets.getSystemWindowInsets());
        assertEquals(Insets.of(0, 100, 0, 100), insets.getInsets(Type.all()));
        assertEquals(INSET_SIDE_TOP, typeSideMap.get(TYPE_TOP_BAR));
        assertEquals(INSET_SIDE_BOTTOM, typeSideMap.get(TYPE_IME));
        assertEquals(Insets.of(0, 100, 0, 0), insets.getInsets(Type.topBar()));
        assertEquals(Insets.of(0, 0, 0, 100), insets.getInsets(Type.ime()));
    }

    @Test
    public void testCalculateInsets_imeAndNav() {
        mState.getSource(TYPE_NAVIGATION_BAR).setFrame(new Rect(0, 200, 100, 300));
        mState.getSource(TYPE_NAVIGATION_BAR).setVisible(true);
        mState.getSource(TYPE_IME).setFrame(new Rect(0, 100, 100, 300));
        mState.getSource(TYPE_IME).setVisible(true);
        WindowInsets insets = mState.calculateInsets(new Rect(0, 0, 100, 300), false, false,
                DisplayCutout.NO_CUTOUT, null, null, null);
        assertEquals(100, insets.getStableInsetBottom());
        assertEquals(Insets.of(0, 0, 0, 100), insets.getMaxInsets(Type.all()));
        assertEquals(Insets.of(0, 0, 0, 200), insets.getSystemWindowInsets());
        assertEquals(Insets.of(0, 0, 0, 200), insets.getInsets(Type.all()));
        assertEquals(Insets.of(0, 0, 0, 100), insets.getInsets(Type.sideBars()));
        assertEquals(Insets.of(0, 0, 0, 200), insets.getInsets(Type.ime()));
    }

    @Test
    public void testCalculateInsets_navRightStatusTop() {
        mState.getSource(TYPE_TOP_BAR).setFrame(new Rect(0, 0, 100, 100));
        mState.getSource(TYPE_TOP_BAR).setVisible(true);
        mState.getSource(TYPE_NAVIGATION_BAR).setFrame(new Rect(80, 0, 100, 300));
        mState.getSource(TYPE_NAVIGATION_BAR).setVisible(true);
        WindowInsets insets = mState.calculateInsets(new Rect(0, 0, 100, 300), false, false,
                DisplayCutout.NO_CUTOUT, null, null, null);
        assertEquals(Insets.of(0, 100, 20, 0), insets.getSystemWindowInsets());
        assertEquals(Insets.of(0, 100, 0, 0), insets.getInsets(Type.topBar()));
        assertEquals(Insets.of(0, 0, 20, 0), insets.getInsets(Type.sideBars()));
    }

    @Test
    public void testStripForDispatch() {
        mState.getSource(TYPE_TOP_BAR).setFrame(new Rect(0, 0, 100, 100));
        mState.getSource(TYPE_TOP_BAR).setVisible(true);
        mState.getSource(TYPE_IME).setFrame(new Rect(0, 200, 100, 300));
        mState.getSource(TYPE_IME).setVisible(true);
        mState.removeSource(TYPE_IME);
        WindowInsets insets = mState.calculateInsets(new Rect(0, 0, 100, 300), false, false,
                DisplayCutout.NO_CUTOUT, null, null, null);
        assertEquals(0, insets.getSystemWindowInsetBottom());
    }

    @Test
    public void testEquals_differentRect() {
        mState.getSource(TYPE_TOP_BAR).setFrame(new Rect(0, 0, 100, 100));
        mState2.getSource(TYPE_TOP_BAR).setFrame(new Rect(0, 0, 10, 10));
        assertNotEquals(mState, mState2);
    }

    @Test
    public void testEquals_differentSource() {
        mState.getSource(TYPE_TOP_BAR).setFrame(new Rect(0, 0, 100, 100));
        mState2.getSource(TYPE_IME).setFrame(new Rect(0, 0, 100, 100));
        assertNotEquals(mState, mState2);
    }

    @Test
    public void testEquals_sameButDifferentInsertOrder() {
        mState.getSource(TYPE_TOP_BAR).setFrame(new Rect(0, 0, 100, 100));
        mState.getSource(TYPE_IME).setFrame(new Rect(0, 0, 100, 100));
        mState2.getSource(TYPE_IME).setFrame(new Rect(0, 0, 100, 100));
        mState2.getSource(TYPE_TOP_BAR).setFrame(new Rect(0, 0, 100, 100));
        assertEquals(mState, mState2);
    }

    @Test
    public void testEquals_visibility() {
        mState.getSource(TYPE_IME).setFrame(new Rect(0, 0, 100, 100));
        mState.getSource(TYPE_IME).setVisible(true);
        mState2.getSource(TYPE_IME).setFrame(new Rect(0, 0, 100, 100));
        assertNotEquals(mState, mState2);
    }

    @Test
    public void testParcelUnparcel() {
        mState.getSource(TYPE_IME).setFrame(new Rect(0, 0, 100, 100));
        mState.getSource(TYPE_IME).setVisible(true);
        mState.getSource(TYPE_TOP_BAR).setFrame(new Rect(0, 0, 100, 100));
        Parcel p = Parcel.obtain();
        mState.writeToParcel(p, 0 /* flags */);
        mState2.readFromParcel(p);
        p.recycle();
        assertEquals(mState, mState2);
    }

    @Test
    public void testGetDefaultVisibility() {
        assertTrue(InsetsState.getDefaultVisibility(TYPE_TOP_BAR));
        assertTrue(InsetsState.getDefaultVisibility(TYPE_SIDE_BAR_1));
        assertTrue(InsetsState.getDefaultVisibility(TYPE_SIDE_BAR_2));
        assertTrue(InsetsState.getDefaultVisibility(TYPE_SIDE_BAR_3));
        assertFalse(InsetsState.getDefaultVisibility(TYPE_IME));
    }
}
