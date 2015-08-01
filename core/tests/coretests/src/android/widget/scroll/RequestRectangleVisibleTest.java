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

package android.widget.scroll;

import android.test.suitebuilder.annotation.Suppress;
import android.widget.scroll.RequestRectangleVisible;
import com.android.frameworks.coretests.R;

import android.test.ActivityInstrumentationTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.ViewAsserts;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.view.View;
import android.view.KeyEvent;

/**
 * {@link RequestRectangleVisible} is set up to exercise the cases of moving a
 * rectangle that is either off screen or not entirely on the screen onto the screen.
 */
@Suppress // Flaky.
public class RequestRectangleVisibleTest extends ActivityInstrumentationTestCase<RequestRectangleVisible> {

    private ScrollView mScrollView;

    private Button mClickToScrollFromAbove;
    private Button mClickToScrollToUpperBlob;
    private TextView mTopBlob;

    private View mChildToScrollTo;

    private TextView mBottomBlob;
    private Button mClickToScrollToBlobLowerBlob;
    private Button mClickToScrollFromBelow;

    public RequestRectangleVisibleTest() {
        super("com.android.frameworks.coretests", RequestRectangleVisible.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        RequestRectangleVisible a = getActivity();

        mScrollView = (ScrollView) a.findViewById(R.id.scrollView);
        mClickToScrollFromAbove = (Button) a.findViewById(R.id.scrollToRectFromTop);
        mClickToScrollToUpperBlob = (Button) a.findViewById(R.id.scrollToRectFromTop2);
        mTopBlob = (TextView) a.findViewById(R.id.topBlob);
        mChildToScrollTo = a.findViewById(R.id.childToMakeVisible);
        mBottomBlob = (TextView) a.findViewById(R.id.bottomBlob);
        mClickToScrollToBlobLowerBlob = (Button) a.findViewById(R.id.scrollToRectFromBottom2);
        mClickToScrollFromBelow = (Button) a.findViewById(R.id.scrollToRectFromBottom);

        
    }

    
    @MediumTest
    public void testPreconditions() {
        assertNotNull(mScrollView);
        assertNotNull(mClickToScrollFromAbove);
        assertNotNull(mClickToScrollToUpperBlob);
        assertNotNull(mTopBlob);
        assertNotNull(mChildToScrollTo);
        assertNotNull(mBottomBlob);
        assertNotNull(mClickToScrollToBlobLowerBlob);
        assertNotNull(mClickToScrollFromBelow);

        assertTrue("top blob needs to be taller than the screen for many of the "
                + "tests below to work.",
                mTopBlob.getHeight() > mScrollView.getHeight());

        assertTrue("bottom blob needs to be taller than the screen for many of the "
                + "tests below to work.",
                mBottomBlob.getHeight() > mScrollView.getHeight());

        assertTrue("top blob needs to be lower than the fading edge region",
                mTopBlob.getTop() > mScrollView.getVerticalFadingEdgeLength());
    }

    @MediumTest
    public void testScrollToOffScreenRectangleFromTop() {
        // view is off screen
        assertTrue(mClickToScrollFromAbove.hasFocus());
        ViewAsserts.assertOffScreenBelow(mScrollView, mChildToScrollTo);

        // click
        sendKeys(KeyEvent.KEYCODE_DPAD_CENTER);
        getInstrumentation().waitForIdleSync();  // wait for scrolling to finish

        // should be on screen, positioned at the bottom (with room for
        // fading edge)
        ViewAsserts.assertOnScreen(mScrollView, mChildToScrollTo);
        ViewAsserts.assertHasScreenCoordinates(
                mScrollView, mChildToScrollTo,
                0,
                mScrollView.getHeight()
                        - mChildToScrollTo.getHeight()
                        - mScrollView.getVerticalFadingEdgeLength());
    }

    @MediumTest
    public void testScrollToPartiallyOffScreenRectFromTop() {
        pressDownUntilViewInFocus(mClickToScrollToUpperBlob, 4);

        // make sure the blob is indeed partially on screen below
        assertOnBottomEdgeOfScreen(mScrollView, mTopBlob);

        // click
        sendKeys(KeyEvent.KEYCODE_DPAD_CENTER);
        getInstrumentation().waitForIdleSync();  // wait for scrolling to finish

        // blob should have moved so top of it is at top of screen (with
        // room for the vertical fading edge
        ViewAsserts.assertHasScreenCoordinates(
                mScrollView, mTopBlob, 0, mScrollView.getVerticalFadingEdgeLength());
    }

    @LargeTest
    public void testScrollToOffScreenRectangleFromBottom() {
        // go to bottom button
        pressDownUntilViewInFocus(mClickToScrollFromBelow, 10);

        // view is off screen above
        assertTrue(mClickToScrollFromBelow.hasFocus());
        ViewAsserts.assertOffScreenAbove(mScrollView, mChildToScrollTo);

        // click
        sendKeys(KeyEvent.KEYCODE_DPAD_CENTER);
        getInstrumentation().waitForIdleSync();  // wait for scrolling to finish

        // on screen, positioned at top (with room for fading edge)
        ViewAsserts.assertOnScreen(mScrollView, mChildToScrollTo);
        ViewAsserts.assertHasScreenCoordinates(
                mScrollView, mChildToScrollTo, 0, mScrollView.getVerticalFadingEdgeLength());
    }


    @LargeTest
    public void testScrollToPartiallyOffScreenRectFromBottom() {
        pressDownUntilViewInFocus(mClickToScrollToBlobLowerBlob, 10);

        // make sure the blob is indeed partially on screen above
        assertOnTopEdgeOfScreen(mScrollView, mBottomBlob);

        // click
        sendKeys(KeyEvent.KEYCODE_DPAD_CENTER);
        getInstrumentation().waitForIdleSync();  // wait for scrolling to finish

        // blob should have moved so bottom of it is at bottom of screen
        // with room for vertical fading edge
        ViewAsserts.assertHasScreenCoordinates(
                mScrollView, mBottomBlob,
                0,
                mScrollView.getHeight() - mBottomBlob.getHeight()
                    - mScrollView.getVerticalFadingEdgeLength());
    }


    /**
     * Press the down key until a particular view is in focus
     * @param view The view to get in focus.
     * @param maxKeyPress The maximum times to press down before failing.
     */
    private void pressDownUntilViewInFocus(View view, int maxKeyPress) {
        int count = 0;
        while(!view.hasFocus()) {
            sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);
            getInstrumentation().waitForIdleSync();

            // just in case...
            if (++count > maxKeyPress) {
                fail("couldn't move down to bottom button within "
                        + maxKeyPress + " key presses.");
            }
        }
    }

    /**
     * Assert that view overlaps the bottom edge of the screen
     * @param origin The root view of the screen.
     * @param view The view
     */
    static public void assertOnBottomEdgeOfScreen(View origin, View view) {
        int[] xy = new int[2];
        view.getLocationOnScreen(xy);

        int[] xyRoot = new int[2];
        origin.getLocationOnScreen(xyRoot);

        int bottom = xy[1] + view.getHeight();
        int bottomOfRoot = xyRoot[1] + origin.getHeight();

        assertTrue(bottom > bottomOfRoot);

        assertTrue(xy[1] < bottomOfRoot);
        assertTrue(bottom > bottomOfRoot);
    }

    /**
     * Assert that view overlaps the bottom edge of the screen
     * @param origin The root view of the screen.
     * @param view The view
     */
    static public void assertOnTopEdgeOfScreen(View origin, View view) {
        int[] xy = new int[2];
        view.getLocationOnScreen(xy);

        int[] xyRoot = new int[2];
        origin.getLocationOnScreen(xyRoot);

        int bottom = xy[1] + view.getHeight();
        int bottomOfRoot = xyRoot[1] + origin.getHeight();

        assertTrue(bottom < bottomOfRoot);
        assertTrue(bottom > xyRoot[1]);

        assertTrue(xy[1] < xyRoot[1]);
    }


}
