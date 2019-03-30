/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static androidx.test.InstrumentationRegistry.getContext;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.graphics.Region;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Test;


/**
 * Test basic functions of ViewGroup.
 *
 * Build/Install/Run:
 *     atest FrameworksCoreTests:ViewGroupTest
 */
@Presubmit
@SmallTest
public class ViewGroupTest {

    /**
     * Test if {@link ViewGroup#subtractObscuredTouchableRegion} works as expected.
     *
     * The view hierarchy:
     *   A---B---C
     *    \   \
     *     \   --D
     *      \
     *       E---F
     *
     * The layer and bounds of each view:
     *   F -- (invisible)
     *   E --
     *   D ----
     *   C ----------
     *   B ------
     *   A --------
     */
    @Test
    public void testSubtractObscuredTouchableRegion() {
        final Context context = getContext();
        final TestView viewA = new TestView(context, 8 /* right */);
        final TestView viewB = new TestView(context, 6 /* right */);
        final TestView viewC = new TestView(context, 10 /* right */);
        final TestView viewD = new TestView(context, 4 /* right */);
        final TestView viewE = new TestView(context, 2 /* right */);
        final TestView viewF = new TestView(context, 2 /* right */);

        viewA.addView(viewB);
        viewA.addView(viewE);
        viewB.addView(viewC);
        viewB.addView(viewD);
        viewE.addView(viewF);

        viewF.setVisibility(View.INVISIBLE);

        final Region r = new Region();

        getUnobscuredTouchableRegion(r, viewA);
        assertRegionContainPoint(1 /* x */, r, true /* contain */);
        assertRegionContainPoint(3 /* x */, r, true /* contain */);
        assertRegionContainPoint(5 /* x */, r, true /* contain */);
        assertRegionContainPoint(7 /* x */, r, true /* contain */);
        assertRegionContainPoint(9 /* x */, r, false /* contain */); // Outside of bounds

        getUnobscuredTouchableRegion(r, viewB);
        assertRegionContainPoint(1 /* x */, r, false /* contain */); // Obscured by E
        assertRegionContainPoint(3 /* x */, r, true /* contain */);
        assertRegionContainPoint(5 /* x */, r, true /* contain */);
        assertRegionContainPoint(7 /* x */, r, false /* contain */); // Outside of bounds

        getUnobscuredTouchableRegion(r, viewC);
        assertRegionContainPoint(1 /* x */, r, false /* contain */); // Obscured by D and E
        assertRegionContainPoint(3 /* x */, r, false /* contain */); // Obscured by D
        assertRegionContainPoint(5 /* x */, r, true /* contain */);
        assertRegionContainPoint(7 /* x */, r, false /* contain */); // Outside of parent bounds

        getUnobscuredTouchableRegion(r, viewD);
        assertRegionContainPoint(1 /* x */, r, false /* contain */); // Obscured by E
        assertRegionContainPoint(3 /* x */, r, true /* contain */);
        assertRegionContainPoint(5 /* x */, r, false /* contain */); // Outside of bounds

        getUnobscuredTouchableRegion(r, viewE);
        assertRegionContainPoint(1 /* x */, r, true /* contain */);
        assertRegionContainPoint(3 /* x */, r, false /* contain */); // Outside of bounds
    }

    private static void getUnobscuredTouchableRegion(Region outRegion, View view) {
        outRegion.set(view.getLeft(), view.getTop(), view.getRight(), view.getBottom());
        final ViewParent parent = view.getParent();
        if (parent != null) {
            parent.subtractObscuredTouchableRegion(outRegion, view);
        }
    }

    private static void assertRegionContainPoint(int x, Region region, boolean contain) {
        assertEquals(String.format("Touchable region must%s contain (%s, 0).",
                (contain ? "" : " not"), x), contain, region.contains(x, 0 /* y */));
    }

    private static class TestView extends ViewGroup {
        TestView(Context context, int right) {
            super(context);
            setFrame(0 /* left */, 0 /* top */, right, 1 /* bottom */);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            // We don't layout this view.
        }
    }
}
