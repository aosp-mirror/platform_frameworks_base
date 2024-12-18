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

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.graphics.Region;
import android.platform.test.annotations.Presubmit;
import android.view.autofill.AutofillId;

import androidx.test.filters.SmallTest;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


/**
 * Test basic functions of ViewGroup.
 *
 * Build/Install/Run:
 *     atest FrameworksCoreTests:ViewGroupTest
 */
@Presubmit
@SmallTest
public class ViewGroupTest {

    @Test
    public void testDispatchMouseEventsUnderCursor() {
        final Context context = getInstrumentation().getContext();
        final TestView viewGroup = new TestView(context, 0 /* left */, 0 /* top */,
                200 /* right */, 100 /* bottom */);
        final TestView viewA = spy(new TestView(context, 0 /* left */, 0 /* top */,
                100 /* right */, 100 /* bottom */));
        final TestView viewB = spy(new TestView(context, 100 /* left */, 0 /* top */,
                200 /* right */, 100 /* bottom */));

        viewGroup.addView(viewA);
        viewGroup.addView(viewB);

        // Make sure all of them handle touch events dispatched to them.
        doReturn(true).when(viewA).dispatchTouchEvent(any());
        doReturn(true).when(viewB).dispatchTouchEvent(any());

        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2];
        properties[0] = new MotionEvent.PointerProperties();
        properties[0].id = 0;
        properties[0].toolType = MotionEvent.TOOL_TYPE_FINGER;
        properties[1] = new MotionEvent.PointerProperties();
        properties[1].id = 1;
        properties[1].toolType = MotionEvent.TOOL_TYPE_FINGER;

        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[2];
        coords[0] = new MotionEvent.PointerCoords();
        coords[0].x = 80;
        coords[0].y = 50;
        coords[1] = new MotionEvent.PointerCoords();
        coords[1].x = 240;
        coords[1].y = 50;

        MotionEvent event;
        // Make sure the down event is active with a pointer which coordinate is different from the
        // cursor position, which is the midpoint of all 2 pointers above.
        event = MotionEvent.obtain(0 /* downTime */, 0 /* eventTime */, MotionEvent.ACTION_DOWN,
                2 /* pointerCount */, properties, coords, 0 /* metaState */, 0 /* buttonState */,
                0 /* xPrecision */, 0 /* yPrecision */, 0 /* deviceId */, 0 /* edgeFlags */,
                InputDevice.SOURCE_MOUSE, 0 /* flags */);
        viewGroup.dispatchTouchEvent(event);
        verify(viewB).dispatchTouchEvent(event);

        viewGroup.onResolvePointerIcon(event, 0 /* pointerIndex */);
        verify(viewB).onResolvePointerIcon(event, 0);

        event.setAction(MotionEvent.ACTION_SCROLL);
        viewGroup.dispatchGenericMotionEvent(event);
        verify(viewB).dispatchGenericMotionEvent(event);

        event = MotionEvent.obtain(0 /* downTime */, 0 /* eventTime */,
                MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                2 /* pointerCount */, properties, coords, 0 /* metaState */, 0 /* buttonState */,
                0 /* xPrecision */, 0 /* yPrecision */, 0 /* deviceId */, 0 /* edgeFlags */,
                InputDevice.SOURCE_MOUSE, 0 /* flags */);
        viewGroup.dispatchTouchEvent(event);
        verify(viewB).dispatchTouchEvent(event);

        viewGroup.onResolvePointerIcon(event, 1 /* pointerIndex */);
        verify(viewB).onResolvePointerIcon(event, 1);

        event.setAction(MotionEvent.ACTION_SCROLL);
        viewGroup.dispatchGenericMotionEvent(event);
        verify(viewB).dispatchGenericMotionEvent(event);

        verify(viewA, never()).dispatchTouchEvent(any());
        verify(viewA, never()).onResolvePointerIcon(any(), anyInt());
        verify(viewA, never()).dispatchGenericMotionEvent(any());
    }

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
        final Context context = getInstrumentation().getContext();
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

    @Test
    public void testfindAutofillableViewsByTraversal() {
        final Context context = getInstrumentation().getContext();
        final TestView viewGroup = new TestView(context, 200 /* right */);

        // viewA and viewC are autofillable. ViewB isn't.
        final TestView viewA = spy(new AutofillableTestView(context, 100 /* right */));
        final TestView viewB = spy(new NonAutofillableTestView(context, 200 /* right */));
        final TestView viewC = spy(new AutofillableTestView(context, 300 /* right */));

        viewGroup.addView(viewA);
        viewGroup.addView(viewB);
        viewGroup.addView(viewC);

        List<View> autofillableViews = new ArrayList<>();
        viewGroup.findAutofillableViewsByTraversal(autofillableViews);

        verify(viewA).findAutofillableViewsByTraversal(autofillableViews);
        verify(viewB).findAutofillableViewsByTraversal(autofillableViews);
        verify(viewC).findAutofillableViewsByTraversal(autofillableViews);

        assertEquals("Size of autofillable views", 2, autofillableViews.size());
        assertTrue(autofillableViews.containsAll(Arrays.asList(viewA, viewC)));
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

    public static class TestView extends ViewGroup {
        TestView(Context context, int right) {
            this(context, 0 /* left */, 0 /* top */, right, 1 /* bottom */);
        }

        TestView(Context context, int left, int top, int right, int bottom) {
            super(context);
            setFrame(left, top, right, bottom);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            // We don't layout this view.
        }
    }

    public static class AutofillableTestView extends TestView {
        AutofillableTestView(Context context, int right) {
            super(context, right);
            setImportantForAutofill(IMPORTANT_FOR_AUTOFILL_YES);
            // Need to set autofill id in such a way that the view is considered part of activity.
            setAutofillId(new AutofillId(LAST_APP_AUTOFILL_ID + 5));
        }

        @Override
        public @AutofillType int getAutofillType() {
            return AUTOFILL_TYPE_TEXT;
        }
    }

    public static class NonAutofillableTestView extends TestView {
        NonAutofillableTestView(Context context, int right) {
            super(context, right);
        }

        @Override
        public @AutofillType int getAutofillType() {
            return AUTOFILL_TYPE_NONE;
        }
    }
}
