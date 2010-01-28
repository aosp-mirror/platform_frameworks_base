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
 *
 * TODO: tests fail intermittently. Add back MediumTest annotation when fixed
 */
public class AutoCompleteTextViewPopup
        extends ActivityInstrumentationTestCase2<AutoCompleteTextViewSimple> {

    // ms to sleep when checking for intermittent UI state
    private static final int SLEEP_TIME = 50;
    // number of times to poll when checking expected UI state
    // total wait time will be LOOP_AMOUNT * SLEEP_TIME
    private static final int LOOP_AMOUNT = 10;


    public AutoCompleteTextViewPopup() {
        super("com.android.frameworks.coretests", AutoCompleteTextViewSimple.class);
    }

    /** Test that we can move the selection and it responds as expected */
    @FlakyTest(tolerance=3)
    public void testPopupSetListSelection() throws Throwable {
        AutoCompleteTextViewSimple theActivity = getActivity();
        final AutoCompleteTextView textView = theActivity.getTextView();
        final Instrumentation instrumentation = getInstrumentation();

        // focus and type
        textView.requestFocus();
        instrumentation.waitForIdleSync();
        sendKeys("A");

        // No initial selection
        waitAssertListSelection(textView, ListView.INVALID_POSITION);

        // set and check
        runTestOnUiThread(new Runnable() {
            public void run() {
                textView.setListSelection(0);
            }
        });
        instrumentation.waitForIdleSync();
        waitAssertListSelection("set selection to (0)", textView, 0);

        // Use movement to cross-check the movement
        sendKeys("DPAD_DOWN");
        waitAssertListSelection("move selection to (1)", textView, 1);

        // TODO: FlakyTest repeat runs will not currently call setUp, clear state
        clearText(textView);
    }

    /** Test that we can look at the selection as we move around */
    @FlakyTest(tolerance=3)
    public void testPopupGetListSelection() throws Throwable {
        AutoCompleteTextViewSimple theActivity = getActivity();
        final AutoCompleteTextView textView = theActivity.getTextView();
        final Instrumentation instrumentation = getInstrumentation();

        // focus and type
        textView.requestFocus();
        instrumentation.waitForIdleSync();
        sendKeys("A");

        // No initial selection
        waitAssertListSelection(textView, ListView.INVALID_POSITION);

        // check for selection position as expected
        sendKeys("DPAD_DOWN");
        waitAssertListSelection("move selection to (0)", textView, 0);

        // Repeat for one more movement
        sendKeys("DPAD_DOWN");
        waitAssertListSelection("move selection to (1)", textView, 1);

        // TODO: FlakyTest repeat runs will not currently call setUp, clear state
        clearText(textView);
    }

    /** Test that we can clear the selection */
    @FlakyTest(tolerance=3)
    public void testPopupClearListSelection() throws Throwable {
        AutoCompleteTextViewSimple theActivity = getActivity();
        final AutoCompleteTextView textView = theActivity.getTextView();
        final Instrumentation instrumentation = getInstrumentation();

        // focus and type
        textView.requestFocus();
        instrumentation.waitForIdleSync();
        sendKeys("A");

        // No initial selection
        waitAssertListSelection(textView, ListView.INVALID_POSITION);

        // check for selection position as expected
        sendKeys("DPAD_DOWN");
        waitAssertListSelection(textView, 0);

        // clear it
        runTestOnUiThread(new Runnable() {
            public void run() {
                textView.clearListSelection();
            }
        });
        instrumentation.waitForIdleSync();
        waitAssertListSelection("setListSelection(ListView.INVALID_POSITION)", textView,
                ListView.INVALID_POSITION);

        // TODO: FlakyTest repeat runs will not currently call setUp, clear state
        clearText(textView);
    }

    /** Make sure we handle an empty adapter properly */
    @FlakyTest(tolerance=3)
    public void testPopupNavigateNoAdapter() throws Throwable {
        AutoCompleteTextViewSimple theActivity = getActivity();
        final AutoCompleteTextView textView = theActivity.getTextView();
        final Instrumentation instrumentation = getInstrumentation();

        // focus and type
        textView.requestFocus();
        instrumentation.waitForIdleSync();
        sendKeys("A");

        // No initial selection
         waitAssertListSelection(textView, ListView.INVALID_POSITION);

        // check for selection position as expected
        sendKeys("DPAD_DOWN");
        waitAssertListSelection(textView, 0);

        // Now get rid of the adapter
        runTestOnUiThread(new Runnable() {
            public void run() {
                textView.setAdapter((ArrayAdapter<?>) null);
            }
        });
        instrumentation.waitForIdleSync();

        // now try moving "down" - nothing should happen since there's no longer an adapter
        sendKeys("DPAD_DOWN");

        // TODO: FlakyTest repeat runs will not currently call setUp, clear state
        clearText(textView);
    }

    /** Test the show/hide behavior of the drop-down. */
    @FlakyTest(tolerance=3)
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
        waitAssertPopupShowState("isPopupShowing() after typing", textView, true);

        // Clear the text
        runTestOnUiThread(new Runnable() {
            public void run() {
                textView.setText("");
            }
        });
        instrumentation.waitForIdleSync();

        // Drop-down should be hidden when text is cleared
        waitAssertPopupShowState("isPopupShowing() after text cleared", textView, false);

        // Set the text, without filtering
        runTestOnUiThread(new Runnable() {
            public void run() {
                textView.setText("a", false);
            }
        });
        instrumentation.waitForIdleSync();

        // Drop-down should still be hidden
        waitAssertPopupShowState("isPopupShowing() after setText(\"a\", false)", textView, false);

        // Set the text, now with filtering
        runTestOnUiThread(new Runnable() {
            public void run() {
                textView.setText("a");
            }
        });
        instrumentation.waitForIdleSync();

        // Drop-down should show up after setText() with filtering
        waitAssertPopupShowState("isPopupShowing() after text set", textView, true);

        // TODO: FlakyTest repeat runs will not currently call setUp, clear state
        clearText(textView);
    }

    private void waitAssertPopupShowState(String message, AutoCompleteTextView textView,
            boolean expected) throws InterruptedException {
        for (int i = 0; i < LOOP_AMOUNT; i++) {
            if (textView.isPopupShowing() == expected) {
                return;
            }
            Thread.sleep(SLEEP_TIME);
        }
        assertEquals(message, expected, textView.isPopupShowing());
    }

    private void waitAssertListSelection(AutoCompleteTextView textView, int expected)
            throws Exception {
        waitAssertListSelection("getListSelection()", textView, expected);
    }

    private void waitAssertListSelection(String message, AutoCompleteTextView textView,
            int expected) throws Exception {
        int currentSelection = ListView.INVALID_POSITION;
        for (int i = 0; i < LOOP_AMOUNT; i++) {
            currentSelection = textView.getListSelection();
            if (expected == currentSelection) {
                return;
            }
            Thread.sleep(SLEEP_TIME);
        }
        assertEquals(message, expected, textView.getListSelection());
    }

    private void clearText(final AutoCompleteTextView textView) throws Throwable {
        runTestOnUiThread(new Runnable() {
            public void run() {
                textView.setText("");
            }
        });
    }
}
