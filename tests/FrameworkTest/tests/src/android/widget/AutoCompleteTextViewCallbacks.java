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
import android.test.suitebuilder.annotation.MediumTest;

public class AutoCompleteTextViewCallbacks 
        extends ActivityInstrumentationTestCase2<AutoCompleteTextViewSimple> {

    public AutoCompleteTextViewCallbacks() {
        super("com.android.frameworktest", AutoCompleteTextViewSimple.class);
    }

    /** Test that the initial popup of the suggestions does not select anything.
     *
     * TODO: test currently fails. Add back MediumTest annotation when fixed.
     */
    public void testPopupNoSelection() {
        AutoCompleteTextViewSimple theActivity = getActivity();
        AutoCompleteTextView textView = theActivity.getTextView();
        final Instrumentation instrumentation = getInstrumentation();
        
        // focus and type
        textView.requestFocus();
        instrumentation.waitForIdleSync();
        sendKeys("A");
        
        // now check for selection callbacks.  Nothing should be clicked or selected.
        assertFalse("onItemClick should not be called", theActivity.mItemClickCalled);
        assertFalse("onItemSelected should not be called", theActivity.mItemSelectedCalled);
        
        // arguably, this should be "false", because we aren't deselecting - we shouldn't
        // really be calling it.  But it's not the end of the world, and we might wind up
        // breaking something if we change this.
        assertTrue("onNothingSelected should be called", theActivity.mNothingSelectedCalled);
    }

    /** Test that arrow-down into the popup calls the onSelected callback */
    @MediumTest
    public void testPopupEnterSelection() {
        AutoCompleteTextViewSimple theActivity = getActivity();
        AutoCompleteTextView textView = theActivity.getTextView();
        final Instrumentation instrumentation = getInstrumentation();
        
        // focus and type
        textView.requestFocus();
        instrumentation.waitForIdleSync();
        sendKeys("A");
        
        // prepare to move down into the popup
        theActivity.resetItemListeners();
        sendKeys("DPAD_DOWN");
        
        // now check for selection callbacks.
        assertFalse("onItemClick should not be called", theActivity.mItemClickCalled);
        assertTrue("onItemSelected should be called", theActivity.mItemSelectedCalled);
        assertEquals("onItemSelected position", 0, theActivity.mItemSelectedPosition);
        assertFalse("onNothingSelected should not be called", theActivity.mNothingSelectedCalled);
        
        // try one more time - should move from 0 to 1
        theActivity.resetItemListeners();
        sendKeys("DPAD_DOWN");
        
        // now check for selection callbacks.
        assertFalse("onItemClick should not be called", theActivity.mItemClickCalled);
        assertTrue("onItemSelected should be called", theActivity.mItemSelectedCalled);
        assertEquals("onItemSelected position", 1, theActivity.mItemSelectedPosition);
        assertFalse("onNothingSelected should not be called", theActivity.mNothingSelectedCalled);
    }

    /** Test that arrow-up out of the popup calls the onNothingSelected callback */
    @MediumTest
    public void testPopupLeaveSelection() {
        AutoCompleteTextViewSimple theActivity = getActivity();
        AutoCompleteTextView textView = theActivity.getTextView();
        final Instrumentation instrumentation = getInstrumentation();
        
        // focus and type
        textView.requestFocus();
        instrumentation.waitForIdleSync();
        sendKeys("A");
        
        // move down into the popup
        sendKeys("DPAD_DOWN");
        
        // now move back up out of the popup
        theActivity.resetItemListeners();
        sendKeys("DPAD_UP");

        // now check for selection callbacks.
        assertFalse("onItemClick should not be called", theActivity.mItemClickCalled);
        assertFalse("onItemSelected should not be called", theActivity.mItemSelectedCalled);
        assertTrue("onNothingSelected should be called", theActivity.mNothingSelectedCalled);
    }

}
