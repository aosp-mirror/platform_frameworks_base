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

import android.widget.scroll.ScrollViewButtonsAndLabels;

import android.test.ActivityInstrumentationTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.MediumTest;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.view.KeyEvent;


public class ScrollViewButtonsAndLabelsTest
        extends ActivityInstrumentationTestCase<ScrollViewButtonsAndLabels> {

    private ScrollView mScrollView;
    private LinearLayout mLinearLayout;
    private int mScreenBottom;
    private int mScreenTop;

    public ScrollViewButtonsAndLabelsTest() {
        super("com.android.frameworks.coretests",
              ScrollViewButtonsAndLabels.class);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mScrollView = getActivity().getScrollView();
        mLinearLayout = getActivity().getLinearLayout();

        int origin[] = {0, 0};
        mScrollView.getLocationOnScreen(origin);
        mScreenTop = origin[1];
        mScreenBottom = origin[1] + mScrollView.getHeight();
    }

    @MediumTest
    public void testPreconditions() {
        assertTrue("vertical fading edge width needs to be non-zero for this "
                + "test to be worth anything",
                mScrollView.getVerticalFadingEdgeLength() > 0);
    }

    // moving down to something off screen should move the element
    // onto the screen just above the vertical fading edge
    @LargeTest
    public void testArrowScrollDownOffScreenVerticalFadingEdge() {

        int offScreenIndex = findFirstButtonOffScreenTop2Bottom();
        Button firstButtonOffScreen = getActivity().getButton(offScreenIndex);

        for (int i = 0; i < offScreenIndex; i++) {
            sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);
        }
        getInstrumentation().waitForIdleSync();
        assertTrue(firstButtonOffScreen.hasFocus());

        assertTrue("the button we've moved to off screen must not be the last "
                + "button in the scroll view for this test to work (since we "
                + "are expecting the fading edge to be there).",
                offScreenIndex < getActivity().getNumButtons());

        // now we are at the first button off screen
        int buttonLoc[] = {0, 0};
        firstButtonOffScreen.getLocationOnScreen(buttonLoc);
        int buttonBottom = buttonLoc[1] + firstButtonOffScreen.getHeight();

        int verticalFadingEdgeLength = mScrollView
                .getVerticalFadingEdgeLength();
        assertEquals("bottom of button should be verticalFadingEdgeLength "
                + "above the bottom of the screen",
                buttonBottom, mScreenBottom - verticalFadingEdgeLength);
    }

    // there should be no offset for vertical fading edge
    // if the item is the last one on screen
    @LargeTest
    public void testArrowScrollDownToBottomElementOnScreen() {

        int numGroups = getActivity().getNumButtons();
        Button lastButton = getActivity().getButton(numGroups - 1);

        assertEquals("button needs to be at the very bottom of the layout for "
                + "this test to work",
                mLinearLayout.getHeight(), lastButton.getBottom());

        // move down to last button
        for (int i = 0; i < numGroups; i++) {
            sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);
        }
        getInstrumentation().waitForIdleSync();
        assertTrue("last button should have focus", lastButton.hasFocus());

        int buttonLoc[] = {0, 0};
        lastButton.getLocationOnScreen(buttonLoc);
        int buttonBottom = buttonLoc[1] + lastButton.getHeight();
        assertEquals("button should be at very bottom of screen",
                mScreenBottom, buttonBottom);
    }

    @LargeTest
    public void testArrowScrollUpOffScreenVerticalFadingEdge() {
        // get to bottom button
        int numGroups = goToBottomButton();

        // go up to first off screen button
        int offScreenIndex = findFirstButtonOffScreenBottom2Top();
        Button offScreenButton = getActivity().getButton(offScreenIndex);
        int clicksToOffScreenIndex = numGroups - offScreenIndex - 1;
        for (int i = 0; i < clicksToOffScreenIndex; i++) {
            sendKeys(KeyEvent.KEYCODE_DPAD_UP);
        }
        getInstrumentation().waitForIdleSync();
        assertTrue("we want to be at offScreenButton", offScreenButton.hasFocus());

        // top should take into account fading edge
        int buttonLoc[] = {0, 0};
        offScreenButton.getLocationOnScreen(buttonLoc);
        assertEquals("top should take into account fading edge",
            mScreenTop + mScrollView.getVerticalFadingEdgeLength(), buttonLoc[1]);
    }


    @LargeTest
    public void testArrowScrollUpToTopElementOnScreen() {
        // get to bottom button
        int numButtons = goToBottomButton();

        // go back to the top
        for (int i = 0; i < numButtons; i++) {
            sendKeys(KeyEvent.KEYCODE_DPAD_UP);
        }
        getInstrumentation().waitForIdleSync();

        Button topButton = getActivity().getButton(0);
        assertTrue("should be back at top button", topButton.hasFocus());


        int buttonLoc[] = {0, 0};
        topButton.getLocationOnScreen(buttonLoc);
        assertEquals("top of top button should be at top of screen; no need to take"
                + " into account vertical fading edge.",
                mScreenTop, buttonLoc[1]);
    }

    private int goToBottomButton() {
        int numButtons = getActivity().getNumButtons();
        Button lastButton = getActivity().getButton(numButtons - 1);

        for (int i = 0; i < numButtons; i++) {
          sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);
        }
        getInstrumentation().waitForIdleSync();
        assertTrue("we want to be at the last button", lastButton.hasFocus());
        return numButtons;
    }

    // search from top to bottom for the first button off screen
    private int findFirstButtonOffScreenTop2Bottom() {
        int origin[] = {0, 0};
        mScrollView.getLocationOnScreen(origin);
        int screenHeight = mScrollView.getHeight();

        for (int i = 0; i < getActivity().getNumButtons(); i++) {

            int buttonLoc[] = {0, 0};
            Button button = getActivity().getButton(i);
            button.getLocationOnScreen(buttonLoc);

            if (buttonLoc[1] - origin[1] > screenHeight) {
                return i;
            }
        }
        fail("couldn't find first button off screen");
        return -1; // this won't execute, but the compiler needs it
    }

    private int findFirstButtonOffScreenBottom2Top() {
        int origin[] = {0, 0};
        mScrollView.getLocationOnScreen(origin);

        for (int i = getActivity().getNumButtons() - 1; i >= 0; i--) {

            int buttonLoc[] = {0, 0};
            Button button = getActivity().getButton(i);
            button.getLocationOnScreen(buttonLoc);

            if (buttonLoc[1] < 0) {
                return i;
            }
        }
        fail("couldn't find first button off screen");
        return -1; // this won't execute, but the compiler needs it
    }

}
