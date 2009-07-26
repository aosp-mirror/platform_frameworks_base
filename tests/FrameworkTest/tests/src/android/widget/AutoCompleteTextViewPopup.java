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
import android.test.suitebuilder.annotation.MediumTest;

/**
 * A collection of tests on aspects of the AutoCompleteTextView's popup
 */
public class AutoCompleteTextViewPopup
        extends ActivityInstrumentationTestCase2<AutoCompleteTextViewSimple> {

    public AutoCompleteTextViewPopup() {
        super("com.android.frameworktest", AutoCompleteTextViewSimple.class);
    }
    
    /** Test that we can move the selection and it responds as expected */
    @MediumTest
    public void testPopupSetListSelection() throws Throwable {
        AutoCompleteTextViewSimple theActivity = getActivity();
        final AutoCompleteTextView textView = theActivity.getTextView();
        final Instrumentation instrumentation = getInstrumentation();
        
        // focus and type
        textView.requestFocus();
        instrumentation.waitForIdleSync();
        sendKeys("A");
        
        // No initial selection
        assertEquals("getListSelection(-1)", 
                ListView.INVALID_POSITION, textView.getListSelection());
        
        // set and check
        runTestOnUiThread(new Runnable() {
            public void run() {
                textView.setListSelection(0);
            }
        });
        instrumentation.waitForIdleSync();
        assertEquals("set selection to (0)", 0, textView.getListSelection());
        
        // Use movement to cross-check the movement
        sendKeys("DPAD_DOWN");
        assertEquals("move selection to (1)", 1, textView.getListSelection());
    }
    
    /** Test that we can look at the selection as we move around */
    @MediumTest
    public void testPopupGetListSelection() {
        AutoCompleteTextViewSimple theActivity = getActivity();
        AutoCompleteTextView textView = theActivity.getTextView();
        final Instrumentation instrumentation = getInstrumentation();
        
        // focus and type
        textView.requestFocus();
        instrumentation.waitForIdleSync();
        sendKeys("A");
        
        // No initial selection
        assertEquals("getListSelection(-1)", 
                ListView.INVALID_POSITION, textView.getListSelection());
        
        // check for selection position as expected
        sendKeys("DPAD_DOWN");
        assertEquals("move selection to (0)", 0, textView.getListSelection());
        
        // Repeat for one more movement
        sendKeys("DPAD_DOWN");
        assertEquals("move selection to (1)", 1, textView.getListSelection());
    }
    
    /** Test that we can clear the selection */
    @MediumTest
    public void testPopupClearListSelection() throws Throwable {
        AutoCompleteTextViewSimple theActivity = getActivity();
        final AutoCompleteTextView textView = theActivity.getTextView();
        final Instrumentation instrumentation = getInstrumentation();
        
        // focus and type
        textView.requestFocus();
        instrumentation.waitForIdleSync();
        sendKeys("A");
        
        // No initial selection
        assertEquals("getListSelection(-1)", 
                ListView.INVALID_POSITION, textView.getListSelection());
        
        // check for selection position as expected
        sendKeys("DPAD_DOWN");
        assertEquals("getListSelection(0)", 0, textView.getListSelection());
        
        // clear it
        runTestOnUiThread(new Runnable() {
            public void run() {
                textView.clearListSelection();
            }
        });
        instrumentation.waitForIdleSync();
        assertEquals("setListSelection(ListView.INVALID_POSITION)", 
                ListView.INVALID_POSITION, textView.getListSelection());
    }

    /** Make sure we handle an empty adapter properly */
    @MediumTest
    public void testPopupNavigateNoAdapter() throws Throwable {
        AutoCompleteTextViewSimple theActivity = getActivity();
        final AutoCompleteTextView textView = theActivity.getTextView();
        final Instrumentation instrumentation = getInstrumentation();

        // focus and type
        textView.requestFocus();
        instrumentation.waitForIdleSync();
        sendKeys("A");

        // No initial selection
        assertEquals("getListSelection(-1)",
                ListView.INVALID_POSITION, textView.getListSelection());

        // check for selection position as expected
        sendKeys("DPAD_DOWN");
        assertEquals("getListSelection(0)", 0, textView.getListSelection());

        // Now get rid of the adapter
        runTestOnUiThread(new Runnable() {
            public void run() {
                textView.setAdapter((ArrayAdapter<?>) null);
            }
        });
        instrumentation.waitForIdleSync();

        // now try moving "down" - nothing should happen since there's no longer an adapter
        sendKeys("DPAD_DOWN");
    }
    
    /** Test the show/hide behavior of the drop-down. */
    @FlakyTest(tolerance=5)
    @MediumTest
    public void testPopupShow() throws Throwable {
        AutoCompleteTextViewSimple theActivity = getActivity();
        final AutoCompleteTextView textView = theActivity.getTextView();
        final Instrumentation instrumentation = getInstrumentation();
        
        // Drop-down should not be showing when no text has been entered
        assertFalse("isPopupShowing() on start", textView.isPopupShowing());
        
        // focus and type
        textView.requestFocus();
        instrumentation.waitForIdleSync();
        sendKeys("A");
        
        // Drop-down should now be visible
        assertTrue("isPopupShowing() after typing", textView.isPopupShowing());
        
        // Clear the text
        runTestOnUiThread(new Runnable() {
            public void run() {
                textView.setText("");
            }
        });
        instrumentation.waitForIdleSync();
        
        // Drop-down should be hidden when text is cleared
        assertFalse("isPopupShowing() after text cleared", textView.isPopupShowing());
        
        // Set the text, without filtering
        runTestOnUiThread(new Runnable() {
            public void run() {
                textView.setText("a", false);
            }
        });
        instrumentation.waitForIdleSync();
        
        // Drop-down should still be hidden
        assertFalse("isPopupShowing() after setText(\"a\", false)", textView.isPopupShowing());
        
        // Set the text, now with filtering
        runTestOnUiThread(new Runnable() {
            public void run() {
                textView.setText("a");
            }
        });
        instrumentation.waitForIdleSync();
        
        // Drop-down should show up after setText() with filtering 
        assertTrue("isPopupShowing() after text set", textView.isPopupShowing());
    }
}
