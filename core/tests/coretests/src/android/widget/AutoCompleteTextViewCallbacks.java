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

package android.widget;

import android.app.Instrumentation;
import android.test.ActivityInstrumentationTestCase2;
import android.test.FlakyTest;

// TODO: tests fail intermittently. Add back MediumTest annotation when fixed
public class AutoCompleteTextViewCallbacks
        extends ActivityInstrumentationTestCase2<AutoCompleteTextViewSimple> {

    private static final int WAIT_TIME = 200;

    public AutoCompleteTextViewCallbacks() {
        super("com.android.frameworks.coretests", AutoCompleteTextViewSimple.class);
    }

    /** Test that the initial popup of the suggestions does not select anything.
     */
    @FlakyTest(tolerance=3)
    public void testPopupNoSelection() throws Exception {
        AutoCompleteTextViewSimple theActivity = getActivity();
        AutoCompleteTextView textView = theActivity.getTextView();
        final Instrumentation instrumentation = getInstrumentation();

        // focus and type
        textView.requestFocus();
        instrumentation.waitForIdleSync();
        sendKeys("A");
        instrumentation.waitForIdleSync();
        // give UI time to settle
        Thread.sleep(WAIT_TIME);

        // now check for selection callbacks.  Nothing should be clicked or selected.
        assertFalse("onItemClick should not be called", theActivity.mItemClickCalled);
        assertFalse("onItemSelected should not be called", theActivity.mItemSelectedCalled);

        // arguably, this should be "false", because we aren't deselecting - we shouldn't
        // really be calling it.  But it's not the end of the world, and we might wind up
        // breaking something if we change this.
        //assertTrue("onNothingSelected should be called", theActivity.mNothingSelectedCalled);
    }

    /** Test that arrow-down into the popup calls the onSelected callback. */
    @FlakyTest(tolerance=3)
    public void testPopupEnterSelection() throws Exception {
        final AutoCompleteTextViewSimple theActivity = getActivity();
        AutoCompleteTextView textView = theActivity.getTextView();
        final Instrumentation instrumentation = getInstrumentation();

        // focus and type
        textView.requestFocus();
        instrumentation.waitForIdleSync();
        sendKeys("A");

        textView.post(new Runnable() {
            public void run() {
                // prepare to move down into the popup
                theActivity.resetItemListeners();
            }
        });

        sendKeys("DPAD_DOWN");
        instrumentation.waitForIdleSync();
        // give UI time to settle
        Thread.sleep(WAIT_TIME);

        // now check for selection callbacks.
        assertFalse("onItemClick should not be called", theActivity.mItemClickCalled);
        assertTrue("onItemSelected should be called", theActivity.mItemSelectedCalled);
        assertEquals("onItemSelected position", 0, theActivity.mItemSelectedPosition);
        assertFalse("onNothingSelected should not be called", theActivity.mNothingSelectedCalled);

        textView.post(new Runnable() {
            public void run() {
                // try one more time - should move from 0 to 1
                theActivity.resetItemListeners();
            }
        });

        sendKeys("DPAD_DOWN");
        instrumentation.waitForIdleSync();        
        // give UI time to settle
        Thread.sleep(WAIT_TIME);

        // now check for selection callbacks.
        assertFalse("onItemClick should not be called", theActivity.mItemClickCalled);
        assertTrue("onItemSelected should be called", theActivity.mItemSelectedCalled);
        assertEquals("onItemSelected position", 1, theActivity.mItemSelectedPosition);
        assertFalse("onNothingSelected should not be called", theActivity.mNothingSelectedCalled);
    }

    /** Test that arrow-up out of the popup calls the onNothingSelected callback */
    @FlakyTest(tolerance=3)
    public void testPopupLeaveSelection() {
        final AutoCompleteTextViewSimple theActivity = getActivity();
        AutoCompleteTextView textView = theActivity.getTextView();
        final Instrumentation instrumentation = getInstrumentation();

        // focus and type
        textView.requestFocus();
        instrumentation.waitForIdleSync();
        sendKeys("A");
        instrumentation.waitForIdleSync();

        // move down into the popup
        sendKeys("DPAD_DOWN");
        instrumentation.waitForIdleSync();

        textView.post(new Runnable() {
            public void run() {
                // prepare to move down into the popup
                theActivity.resetItemListeners();
            }
        });

        sendKeys("DPAD_UP");
        instrumentation.waitForIdleSync();

        // now check for selection callbacks.
        assertFalse("onItemClick should not be called", theActivity.mItemClickCalled);
        assertFalse("onItemSelected should not be called", theActivity.mItemSelectedCalled);
        assertTrue("onNothingSelected should be called", theActivity.mNothingSelectedCalled);
    }

}
