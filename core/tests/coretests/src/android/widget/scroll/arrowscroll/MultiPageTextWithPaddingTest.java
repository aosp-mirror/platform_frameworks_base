/*
 * Copyright (C) 2011 Sony Ericsson Mobile Communications AB.
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

import android.widget.scroll.arrowscroll.MultiPageTextWithPadding;
import android.test.ActivityInstrumentationTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.MediumTest;
import android.view.KeyEvent;
import android.widget.TextView;
import android.widget.ScrollView;

public class MultiPageTextWithPaddingTest extends
        ActivityInstrumentationTestCase<MultiPageTextWithPadding> {

    private ScrollView mScrollView;

    private TextView mTextView;

    public MultiPageTextWithPaddingTest() {
        super("com.android.frameworks.coretests", MultiPageTextWithPadding.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mScrollView = getActivity().getScrollView();
        mTextView = getActivity().getContentChildAt(0);
    }

    @MediumTest
    public void testPreconditions() {
        assertTrue("text should not fit on screen",
                   mTextView.getHeight() > mScrollView.getHeight());
    }

    @LargeTest
    public void testScrollDownToBottom() throws Exception {
        // Calculate the number of arrow scrolls needed to reach the bottom
        int scrollsNeeded = (int)Math.ceil(Math.max(0.0f,
                (mTextView.getHeight() - mScrollView.getHeight()))
                / mScrollView.getMaxScrollAmount());
        for (int i = 0; i < scrollsNeeded; i++) {
            sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);
        }

        assertEquals(
                "should be fully scrolled to bottom",
                getActivity().getLinearLayout().getHeight()
                        - (mScrollView.getHeight() - mScrollView.getPaddingTop() - mScrollView
                                .getPaddingBottom()), mScrollView.getScrollY());
    }
}
