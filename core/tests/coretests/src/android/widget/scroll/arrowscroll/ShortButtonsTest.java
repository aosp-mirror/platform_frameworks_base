/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.widget.scroll.arrowscroll;

import android.graphics.Rect;
import android.test.ActivityInstrumentationTestCase;
import android.view.KeyEvent;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.scroll.ShortButtons;

import androidx.test.filters.LargeTest;
import androidx.test.filters.MediumTest;

public class ShortButtonsTest extends ActivityInstrumentationTestCase<ShortButtons> {

    private ScrollView mScrollView;

    public ShortButtonsTest() {
        super("com.android.frameworks.coretests", ShortButtons.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mScrollView = getActivity().getScrollView();
    }

    @MediumTest
    public void testPreconditions() {
        assertTrue("buttons should be shorter than screen",
                getActivity().getButtonAt(0).getHeight()
                        < mScrollView.getHeight());

        assertTrue("should be enough buttons to have some scrolled off screen",
                getActivity().getLinearLayout().getHeight()
                        > getActivity().getScrollView().getHeight());
    }

    @LargeTest
    public void testScrollDownToBottomThroughButtons() throws Exception {
        final int numButtons = getActivity().getNumButtons();

        for (int i = 0; i < numButtons; i++) {
            String prefix = "after " + i + " downs expected button " + i;
            final Button button = getActivity().getButtonAt(i);
            assertTrue(prefix + "  to have focus", button.isFocused());
            assertTrue(prefix + " to be on screen", isButtonOnScreen(button));
            sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);
        }

        assertEquals("should be fully scrolled to bottom",
                getActivity().getLinearLayout().getHeight() - mScrollView.getHeight(),
                mScrollView.getScrollY());        
    }

    @LargeTest
    public void testScrollFromBottomToTopThroughButtons() throws Exception {
        final int numButtons = getActivity().getNumButtons();

        final Button lastButton = getActivity().getButtonAt(numButtons - 1);

        lastButton.post(new Runnable() {
            public void run() {
                lastButton.requestFocus();
            }
        });

        getInstrumentation().waitForIdleSync();

        assertTrue("lastButton.isFocused()", lastButton.isFocused());

        for (int i = numButtons - 1; i >= 0; i--) {
            String prefix = "after " + i + " ups expected button " + i;
            final Button button = getActivity().getButtonAt(i);
            assertTrue(prefix + "  to have focus", button.isFocused());
            assertTrue(prefix + " to be on screen", isButtonOnScreen(button));
            sendKeys(KeyEvent.KEYCODE_DPAD_UP);
        }

        assertEquals("should be fully scrolled to top",
                0,
                mScrollView.getScrollY());        
    }

    private Rect mTempRect = new Rect();
    protected boolean isButtonOnScreen(Button b) {
        b.getDrawingRect(mTempRect);
        mScrollView.offsetDescendantRectToMyCoords(b, mTempRect);
        return mTempRect.bottom >= mScrollView.getScrollY()
                && mTempRect.top <= (mScrollView.getScrollY() + mScrollView.getHeight());
    }

}
