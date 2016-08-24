/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.test;

import static junit.framework.Assert.*;

import android.view.View;
import android.view.ViewGroup;

/**
 * Some useful assertions about views.
 *
 * @deprecated Use
 * <a href="{@docRoot}reference/android/support/test/espresso/matcher/ViewMatchers.html">Espresso
 * View Matchers</a> instead. New test should be written using the
 * <a href="{@docRoot}tools/testing-support-library/index.html">Android Testing Support Library</a>.
 * For more information about UI testing, take the
 * <a href="{@docRoot}tools/testing-support-library/index.html">Espresso UI testing</a> training.
 */
@Deprecated
public class ViewAsserts {

    private ViewAsserts() {}

    /**
     * Assert that view is on the screen.
     * @param origin The root view of the screen.
     * @param view The view.
     */
    static public void assertOnScreen(View origin, View view) {
        int[] xy = new int[2];
        view.getLocationOnScreen(xy);

        int[] xyRoot = new int[2];
        origin.getLocationOnScreen(xyRoot);

        int y = xy[1] - xyRoot[1];

        assertTrue("view should have positive y coordinate on screen",
                y  >= 0);

        assertTrue("view should have y location on screen less than drawing "
                + "height of root view",
                y <= view.getRootView().getHeight());
    }

    /**
     * Assert that view is below the visible screen.
     * @param origin The root view of the screen.
     * @param view The view
     */
    static public void assertOffScreenBelow(View origin, View view) {
        int[] xy = new int[2];
        view.getLocationOnScreen(xy);

        int[] xyRoot = new int[2];
        origin.getLocationOnScreen(xyRoot);

        int y = xy[1] - xyRoot[1];

        assertTrue("view should have y location on screen greater than drawing "
                + "height of origen view (" + y + " is not greater than "
                + origin.getHeight() + ")",
                y > origin.getHeight());
    }

    /**
     * Assert that view is above the visible screen.
     * @param origin Te root view of the screen.
     * @param view The view
     */
    static public void assertOffScreenAbove(View origin, View view) {
        int[] xy = new int[2];
        view.getLocationOnScreen(xy);

        int[] xyRoot = new int[2];
        origin.getLocationOnScreen(xyRoot);

        int y = xy[1] - xyRoot[1];

        assertTrue("view should have y location less than that of origin view",
                y < 0);
    }

    /**
     * Assert that a view has a particular x and y position on the visible screen.
     * @param origin The root view of the screen.
     * @param view The view.
     * @param x The expected x coordinate.
     * @param y The expected y coordinate.
     */
    static public void assertHasScreenCoordinates(View origin, View view, int x, int y) {
        int[] xy = new int[2];
        view.getLocationOnScreen(xy);

        int[] xyRoot = new int[2];
        origin.getLocationOnScreen(xyRoot);

        assertEquals("x coordinate", x, xy[0] - xyRoot[0]);
        assertEquals("y coordinate", y, xy[1] - xyRoot[1]);
    }

    /**
     * Assert that two views are aligned on their baseline, that is that their baselines
     * are on the same y location.
     *
     * @param first The first view
     * @param second The second view
     */
    static public void assertBaselineAligned(View first, View second) {
        int[] xy = new int[2];
        first.getLocationOnScreen(xy);
        int firstTop = xy[1] + first.getBaseline();

        second.getLocationOnScreen(xy);
        int secondTop = xy[1] + second.getBaseline();

        assertEquals("views are not baseline aligned", firstTop, secondTop);
    }

    /**
     * Assert that two views are right aligned, that is that their right edges
     * are on the same x location.
     *
     * @param first The first view
     * @param second The second view
     */
    static public void assertRightAligned(View first, View second) {
        int[] xy = new int[2];
        first.getLocationOnScreen(xy);
        int firstRight = xy[0] + first.getMeasuredWidth();

        second.getLocationOnScreen(xy);
        int secondRight = xy[0] + second.getMeasuredWidth();

        assertEquals("views are not right aligned", firstRight, secondRight);
    }

    /**
     * Assert that two views are right aligned, that is that their right edges
     * are on the same x location, with respect to the specified margin.
     *
     * @param first The first view
     * @param second The second view
     * @param margin The margin between the first view and the second view
     */
    static public void assertRightAligned(View first, View second, int margin) {
        int[] xy = new int[2];
        first.getLocationOnScreen(xy);
        int firstRight = xy[0] + first.getMeasuredWidth();

        second.getLocationOnScreen(xy);
        int secondRight = xy[0] + second.getMeasuredWidth();

        assertEquals("views are not right aligned", Math.abs(firstRight - secondRight), margin);
    }

    /**
     * Assert that two views are left aligned, that is that their left edges
     * are on the same x location.
     *
     * @param first The first view
     * @param second The second view
     */
    static public void assertLeftAligned(View first, View second) {
        int[] xy = new int[2];
        first.getLocationOnScreen(xy);
        int firstLeft = xy[0];

        second.getLocationOnScreen(xy);
        int secondLeft = xy[0];

        assertEquals("views are not left aligned", firstLeft, secondLeft);
    }

    /**
     * Assert that two views are left aligned, that is that their left edges
     * are on the same x location, with respect to the specified margin.
     *
     * @param first The first view
     * @param second The second view
     * @param margin The margin between the first view and the second view
     */
    static public void assertLeftAligned(View first, View second, int margin) {
        int[] xy = new int[2];
        first.getLocationOnScreen(xy);
        int firstLeft = xy[0];

        second.getLocationOnScreen(xy);
        int secondLeft = xy[0];

        assertEquals("views are not left aligned", Math.abs(firstLeft - secondLeft), margin);
    }

    /**
     * Assert that two views are bottom aligned, that is that their bottom edges
     * are on the same y location.
     *
     * @param first The first view
     * @param second The second view
     */
    static public void assertBottomAligned(View first, View second) {
        int[] xy = new int[2];
        first.getLocationOnScreen(xy);
        int firstBottom = xy[1] + first.getMeasuredHeight();

        second.getLocationOnScreen(xy);
        int secondBottom = xy[1] + second.getMeasuredHeight();

        assertEquals("views are not bottom aligned", firstBottom, secondBottom);
    }

    /**
     * Assert that two views are bottom aligned, that is that their bottom edges
     * are on the same y location, with respect to the specified margin.
     *
     * @param first The first view
     * @param second The second view
     * @param margin The margin between the first view and the second view
     */
    static public void assertBottomAligned(View first, View second, int margin) {
        int[] xy = new int[2];
        first.getLocationOnScreen(xy);
        int firstBottom = xy[1] + first.getMeasuredHeight();

        second.getLocationOnScreen(xy);
        int secondBottom = xy[1] + second.getMeasuredHeight();

        assertEquals("views are not bottom aligned", Math.abs(firstBottom - secondBottom), margin);
    }

    /**
     * Assert that two views are top aligned, that is that their top edges
     * are on the same y location.
     *
     * @param first The first view
     * @param second The second view
     */
    static public void assertTopAligned(View first, View second) {
        int[] xy = new int[2];
        first.getLocationOnScreen(xy);
        int firstTop = xy[1];

        second.getLocationOnScreen(xy);
        int secondTop = xy[1];

        assertEquals("views are not top aligned", firstTop, secondTop);
    }

    /**
     * Assert that two views are top aligned, that is that their top edges
     * are on the same y location, with respect to the specified margin.
     *
     * @param first The first view
     * @param second The second view
     * @param margin The margin between the first view and the second view
     */
    static public void assertTopAligned(View first, View second, int margin) {
        int[] xy = new int[2];
        first.getLocationOnScreen(xy);
        int firstTop = xy[1];

        second.getLocationOnScreen(xy);
        int secondTop = xy[1];

        assertEquals("views are not top aligned", Math.abs(firstTop - secondTop), margin);
    }

    /**
     * Assert that the <code>test</code> view is horizontally center aligned
     * with respect to the <code>reference</code> view.
     *
     * @param reference The reference view
     * @param test The view that should be center aligned with the reference view
     */
    static public void assertHorizontalCenterAligned(View reference, View test) {
        int[] xy = new int[2];
        reference.getLocationOnScreen(xy);
        int referenceLeft = xy[0];

        test.getLocationOnScreen(xy);
        int testLeft = xy[0];

        int center = (reference.getMeasuredWidth() - test.getMeasuredWidth()) / 2;
        int delta = testLeft - referenceLeft;

        assertEquals("views are not horizontally center aligned", center, delta);
    }

    /**
     * Assert that the <code>test</code> view is vertically center aligned
     * with respect to the <code>reference</code> view.
     *
     * @param reference The reference view
     * @param test The view that should be center aligned with the reference view
     */
    static public void assertVerticalCenterAligned(View reference, View test) {
        int[] xy = new int[2];
        reference.getLocationOnScreen(xy);
        int referenceTop = xy[1];

        test.getLocationOnScreen(xy);
        int testTop = xy[1];

        int center = (reference.getMeasuredHeight() - test.getMeasuredHeight()) / 2;
        int delta = testTop - referenceTop;

        assertEquals("views are not vertically center aligned", center, delta);
    }

    /**
     * Assert the specified group's integrity. The children count should be >= 0 and each
     * child should be non-null.
     *
     * @param parent The group whose integrity to check
     */
    static public void assertGroupIntegrity(ViewGroup parent) {
        final int count = parent.getChildCount();
        assertTrue("child count should be >= 0", count >= 0);

        for (int i = 0; i < count; i++) {
            assertNotNull("group should not contain null children", parent.getChildAt(i));
            assertSame(parent, parent.getChildAt(i).getParent());
        }
    }

    /**
     * Assert that the specified group contains a specific child once and only once.
     *
     * @param parent The group
     * @param child The child that should belong to group
     */
    static public void assertGroupContains(ViewGroup parent, View child) {
        final int count = parent.getChildCount();
        assertTrue("Child count should be >= 0", count >= 0);

        boolean found = false;
        for (int i = 0; i < count; i++) {
            if (parent.getChildAt(i) == child) {
                if (!found) {
                    found = true;
                } else {
                    assertTrue("child " + child + " is duplicated in parent", false);
                }
            }
        }

        assertTrue("group does not contain " + child, found);
    }

    /**
     * Assert that the specified group does not contain a specific child.
     *
     * @param parent The group
     * @param child The child that should not belong to group
     */
    static public void assertGroupNotContains(ViewGroup parent, View child) {
        final int count = parent.getChildCount();
        assertTrue("Child count should be >= 0", count >= 0);

        for (int i = 0; i < count; i++) {
            if (parent.getChildAt(i) == child) {
                assertTrue("child " + child + " is found in parent", false);
            }
        }
    }
}
