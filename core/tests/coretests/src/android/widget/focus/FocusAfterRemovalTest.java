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

package android.widget.focus;

import android.test.ActivityInstrumentationTestCase;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.test.filters.MediumTest;

import com.android.frameworks.coretests.R;

/**
 * {@link FocusAfterRemoval} is set up to exercise cases where the views that
 * have focus become invisible or GONE.
 */
public class FocusAfterRemovalTest extends ActivityInstrumentationTestCase<FocusAfterRemoval> {

    private LinearLayout mLeftLayout;
    private Button mTopLeftButton;
    private Button mBottomLeftButton;
    private Button mTopRightButton;
    private Button mBottomRightButton;

    public FocusAfterRemovalTest() {
        super("com.android.frameworks.coretests", FocusAfterRemoval.class);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();

        final FocusAfterRemoval a = getActivity();
        mLeftLayout = (LinearLayout) a.findViewById(R.id.leftLayout);
        mTopLeftButton = (Button) a.findViewById(R.id.topLeftButton);
        mBottomLeftButton = (Button) a.findViewById(R.id.bottomLeftButton);
        mTopRightButton = (Button) a.findViewById(R.id.topRightButton);
        mBottomRightButton = (Button) a.findViewById(R.id.bottomRightButton);
    }

    // Test that setUp did what we expect it to do.  These asserts
    // can't go in SetUp, or the test will hang.
    @MediumTest
    public void testSetUpConditions() throws Exception {
        assertNotNull(mLeftLayout);
        assertNotNull(mTopLeftButton);
        assertNotNull(mTopRightButton);
        assertNotNull(mBottomLeftButton);
        assertNotNull(mBottomRightButton);

        assertTrue(mTopLeftButton.hasFocus());
    }

    // if a parent layout becomes GONE when one of its children has focus,
    // make sure the focus moves to something visible (bug 827087)
    @MediumTest
    public void testFocusLeavesWhenParentLayoutIsGone() throws Exception {

        // clicking on this button makes its parent linear layout GONE
        sendKeys(KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals(View.GONE, mLeftLayout.getVisibility());

        assertTrue("focus should jump to visible button",
                mTopRightButton.hasFocus());

    }

    @MediumTest
    public void testFocusLeavesWhenParentLayoutInvisible() throws Exception {

        // move down to bottom left button
        sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);
        assertTrue(mBottomLeftButton.hasFocus());

        // clicking on this button makes its parent linear layout INVISIBLE
        sendKeys(KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals(View.INVISIBLE,
                getActivity().findViewById(R.id.leftLayout).getVisibility());

        assertTrue("focus should jump to visible button",
                mTopRightButton.hasFocus());
    }

    @MediumTest
    public void testFocusLeavesWhenFocusedViewBecomesGone() throws Exception {

        // move to top right
        sendKeys(KeyEvent.KEYCODE_DPAD_RIGHT);
        assertTrue(mTopRightButton.hasFocus());

        // click making it GONE
        sendKeys(KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals(View.GONE, mTopRightButton.getVisibility());

        assertTrue("focus should jump to visible button",
                mTopLeftButton.hasFocus());
    }

    @MediumTest
    public void testFocusLeavesWhenFocusedViewBecomesInvisible() throws Exception {

        // move to bottom right
        sendKeys(KeyEvent.KEYCODE_DPAD_RIGHT);
        sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);
        assertTrue(mBottomRightButton.hasFocus());

        // click making it INVISIBLE
        sendKeys(KeyEvent.KEYCODE_DPAD_CENTER);
        assertEquals(View.INVISIBLE, mBottomRightButton.getVisibility());

        assertTrue("focus should jump to visible button",
                mTopLeftButton.hasFocus());
    }

}
