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

package android.widget.touchmode;

import static android.util.TouchModeFlexibleAsserts.assertInTouchModeAfterClick;
import static android.util.TouchModeFlexibleAsserts.assertInTouchModeAfterTap;
import static android.util.TouchModeFlexibleAsserts.assertNotInTouchModeAfterKey;

import android.test.ActivityInstrumentationTestCase;
import android.view.KeyEvent;
import android.widget.layout.linear.LLOfButtons1;
import android.widget.layout.linear.LLOfButtons2;

import androidx.test.filters.LargeTest;
import androidx.test.filters.MediumTest;

/**
 * Tests that the touch mode changes from various events, and that the state
 * persists across activities.
 */
public class ChangeTouchModeTest extends ActivityInstrumentationTestCase<LLOfButtons1> {

    public ChangeTouchModeTest() {
        super("com.android.frameworks.coretests", LLOfButtons1.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
    }

    @MediumTest
    public void testPreconditions() throws Exception {
        assertFalse("touch mode", getActivity().isInTouchMode());
    }

    @MediumTest
    public void testTouchingScreenEntersTouchMode() throws Exception {
        assertInTouchModeAfterTap(this, getActivity().getFirstButton());
        assertTrue("touch mode", getActivity().isInTouchMode());
    }

    // TODO: reenable when more reliable
    public void DISABLE_testDpadDirectionLeavesTouchMode() throws Exception {
        assertInTouchModeAfterClick(this, getActivity().getFirstButton());
        sendKeys(KeyEvent.KEYCODE_DPAD_RIGHT);
        assertNotInTouchModeAfterKey(this, KeyEvent.KEYCODE_DPAD_RIGHT, getActivity().getFirstButton());
        assertFalse("touch mode", getActivity().isInTouchMode());
    }

    public void TODO_touchTrackBallMovementLeavesTouchMode() throws Exception {

    }

    @MediumTest
    public void testTouchModeFalseAcrossActivites() throws Exception {

        getInstrumentation().waitForIdleSync();

        LLOfButtons2 otherActivity = null;
        try {
            otherActivity =
                    launchActivity("com.android.frameworks.coretests", LLOfButtons2.class, null);
            assertNotNull(otherActivity);
            assertFalse(otherActivity.isInTouchMode());
        } finally {
            if (otherActivity != null) {
                otherActivity.finish();
            }
        }
    }

    @LargeTest
    public void testTouchModeTrueAcrossActivites() throws Exception {
        assertInTouchModeAfterClick(this, getActivity().getFirstButton());
        LLOfButtons2 otherActivity = null;
        try {
            otherActivity =
                    launchActivity("com.android.frameworks.coretests", LLOfButtons2.class, null);
            assertNotNull(otherActivity);
            assertTrue(otherActivity.isInTouchMode());
        } finally {
            if (otherActivity != null) {
                otherActivity.finish();
            }
        }
    }

    @LargeTest
    public void testTouchModeChangedInOtherActivity() throws Exception {

        assertFalse("touch mode", getActivity().isInTouchMode());

        LLOfButtons2 otherActivity = null;
        try {
            otherActivity =
                    launchActivity("com.android.frameworks.coretests", LLOfButtons2.class, null);
            assertNotNull(otherActivity);
            assertFalse(otherActivity.isInTouchMode());
            assertInTouchModeAfterClick(this, otherActivity.getFirstButton());
            assertTrue(otherActivity.isInTouchMode());
        } finally {
            if (otherActivity != null) {
                otherActivity.finish();
            }
        }

        // need to wait for async update back to window to occur
        Thread.sleep(200);
        
        assertTrue("touch mode", getActivity().isInTouchMode());
    }

}
