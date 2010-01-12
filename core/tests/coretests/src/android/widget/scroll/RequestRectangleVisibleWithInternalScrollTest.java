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

import com.android.frameworks.coretests.R;

import android.test.ActivityInstrumentationTestCase;
import android.test.ViewAsserts;
import android.test.suitebuilder.annotation.Suppress;
import android.view.KeyEvent;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * This is suppressed because {@link TextView#scrollBy} isn't working.
 */
@Suppress
public class RequestRectangleVisibleWithInternalScrollTest
        extends ActivityInstrumentationTestCase<RequestRectangleVisibleWithInternalScroll> {

    private TextView mTextBlob;
    private Button mScrollToBlob;

    private ScrollView mScrollView;


    public RequestRectangleVisibleWithInternalScrollTest() {
        super("com.android.frameworks.coretests",
                RequestRectangleVisibleWithInternalScroll.class);
    }

    
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mTextBlob = getActivity().getTextBlob();
        mScrollToBlob = getActivity().getScrollToBlob();

        mScrollView = (ScrollView) getActivity().findViewById(R.id.scrollView);
    }

    public void testPreconditions() {
        assertNotNull(mTextBlob);
        assertNotNull(mScrollToBlob);
        assertEquals(getActivity().getScrollYofBlob(), mTextBlob.getScrollY());
    }

    public void testMoveToChildWithScrollYBelow() {
        assertTrue(mScrollToBlob.hasFocus());

        ViewAsserts.assertOffScreenBelow(mScrollView, mTextBlob);

        // click
        sendKeys(KeyEvent.KEYCODE_DPAD_CENTER);
        getInstrumentation().waitForIdleSync();  // wait for scrolling to finish

        // should be on screen, positioned at the bottom (with enough room for
        // fading edge)
        ViewAsserts.assertOnScreen(mScrollView, mTextBlob);
        ViewAsserts.assertHasScreenCoordinates(
                mScrollView, mTextBlob,
                0,
                mScrollView.getHeight()
                        - mTextBlob.getHeight()
                        - mScrollView.getVerticalFadingEdgeLength());

    }


}
