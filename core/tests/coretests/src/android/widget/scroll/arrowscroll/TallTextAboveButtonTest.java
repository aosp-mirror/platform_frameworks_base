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

import android.test.ActivityInstrumentationTestCase;
import android.view.KeyEvent;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.scroll.TallTextAboveButton;

import androidx.test.filters.MediumTest;

public class TallTextAboveButtonTest extends ActivityInstrumentationTestCase<TallTextAboveButton> {
    private ScrollView mScrollView;
    private TextView mTopText;
    private TextView mBottomButton;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mScrollView = getActivity().getScrollView();
        mTopText = getActivity().getContentChildAt(0);
        mBottomButton = getActivity().getContentChildAt(1);
    }

    public TallTextAboveButtonTest() {
        super("com.android.frameworks.coretests", TallTextAboveButton.class);
    }

    @MediumTest
    public void testPreconditions() {
        assertTrue("top text should be larger than screen",
                mTopText.getHeight() > mScrollView.getHeight());
        assertTrue("scroll view should have focus (because nothing else focusable "
                + "is on screen), but " + getActivity().getScrollView().findFocus() + " does instead",
                getActivity().getScrollView().isFocused());
    }

    @MediumTest
    public void testGainFocusAsScrolledOntoScreen() {
        sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);

        assertTrue("button should have scrolled onto screen",
                mBottomButton.getBottom() >= mScrollView.getBottom());
        assertTrue("button should have gained focus as it was scrolled completely "
                + "into view", mBottomButton.isFocused());

        sendKeys(KeyEvent.KEYCODE_DPAD_UP);
        assertTrue("scroll view should have focus, but " + getActivity().getScrollView().findFocus() + " does instead",
                getActivity().getScrollView().isFocused());
    }

    @MediumTest
    public void testScrollingButtonOffScreenLosesFocus() {
        sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);
        assertTrue("button should have focus", mBottomButton.isFocused());
        sendKeys(KeyEvent.KEYCODE_DPAD_UP);
        assertTrue("scroll view should have focus, but " + getActivity().getScrollView().findFocus() + " does instead",
                getActivity().getScrollView().isFocused());
    }
    

}
