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

import android.app.Activity;
import android.app.Instrumentation;
import android.os.SystemClock;
import android.test.ActivityInstrumentationTestCase2;
import android.view.KeyEvent;
import android.view.View;

import androidx.test.filters.LargeTest;

import com.android.frameworks.coretests.R;

/**
 * Test {@link DatePicker} focus changes.
 */
@LargeTest
public class DatePickerFocusTest extends ActivityInstrumentationTestCase2<DatePickerActivity> {

    private Activity mActivity;
    private Instrumentation mInstrumentation;
    private DatePicker mDatePicker;

    public DatePickerFocusTest() {
        super(DatePickerActivity.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mActivity = getActivity();
        mInstrumentation = getInstrumentation();

        mDatePicker = (DatePicker) mActivity.findViewById(R.id.datePicker);
    }

    /**
     * Tabs (forward and backward) through the DatePicker to ensure the correct
     * Views gain focus.
     */
    public void testFocusTravel() throws Throwable {
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertTrue(mDatePicker.requestFocus());
            }
        });
        assertViewHasFocus(com.android.internal.R.id.date_picker_header_year);
        sendKey(KeyEvent.KEYCODE_TAB);
        assertViewHasFocus(com.android.internal.R.id.prev);
        sendKey(KeyEvent.KEYCODE_TAB);
        assertViewHasFocus(com.android.internal.R.id.next);
        sendKey(KeyEvent.KEYCODE_TAB);
        assertViewHasFocus(com.android.internal.R.id.day_picker_view_pager);
        sendKey(KeyEvent.KEYCODE_TAB);
        assertViewHasFocus(R.id.belowPicker);
        sendShiftKey(KeyEvent.KEYCODE_TAB);
        assertViewHasFocus(com.android.internal.R.id.day_picker_view_pager);
        sendShiftKey(KeyEvent.KEYCODE_TAB);
        assertViewHasFocus(com.android.internal.R.id.next);
        sendShiftKey(KeyEvent.KEYCODE_TAB);
        assertViewHasFocus(com.android.internal.R.id.prev);
        sendShiftKey(KeyEvent.KEYCODE_TAB);
        assertViewHasFocus(com.android.internal.R.id.date_picker_header_year);
    }

    private void sendKey(int keycode) {
        mInstrumentation.sendKeyDownUpSync(keycode);
        mInstrumentation.waitForIdleSync();
    }

    private void assertViewHasFocus(final int id) throws Throwable {
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                View view = mActivity.findViewById(id);
                assertTrue(view.hasFocus());
            }
        });
    }

    private void sendShiftKey(int keycode) {
        final KeyEvent shiftDown = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT);
        mInstrumentation.sendKeySync(shiftDown);

        final KeyEvent keyDown = new KeyEvent(SystemClock.uptimeMillis(),
                SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, keycode, 0,
                KeyEvent.META_SHIFT_ON);
        mInstrumentation.sendKeySync(keyDown);

        final KeyEvent keyUp = new KeyEvent(SystemClock.uptimeMillis(),
                SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keycode, 0,
                KeyEvent.META_SHIFT_ON);
        mInstrumentation.sendKeySync(keyUp);

        final KeyEvent shiftUp = new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT);
        mInstrumentation.sendKeySync(shiftUp);

        mInstrumentation.waitForIdleSync();
    }

    /**
     * Tests to ensure the keyboard can select the current year.
     */
    public void testYearChoice() throws Throwable {
        setKnownDate();
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                View year = mDatePicker.
                        findViewById(com.android.internal.R.id.date_picker_header_year);
                assertTrue(year.requestFocus());
            }
        });
        sendKey(KeyEvent.KEYCODE_ENTER);
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                View yearSelect = mDatePicker.
                        findViewById(com.android.internal.R.id.date_picker_year_picker);
                assertEquals(yearSelect, mDatePicker.findFocus());
            }
        });
        sendKey(KeyEvent.KEYCODE_DPAD_UP);
        sendKey(KeyEvent.KEYCODE_ENTER);
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                View yearSelect = mDatePicker.
                        findViewById(com.android.internal.R.id.date_picker_year_picker);
                assertNotSame(View.VISIBLE, yearSelect.getVisibility());
                View year = mDatePicker.
                        findViewById(com.android.internal.R.id.date_picker_header_year);
                assertTrue(year.hasFocus());
                assertEquals(2014, mDatePicker.getYear());
            }
        });
    }

    public void testArrowThroughDays() throws Throwable {
        setKnownDate();
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                View prev = mDatePicker.findViewById(com.android.internal.R.id.next);
                prev.requestFocus();
            }
        });
        sendKey(KeyEvent.KEYCODE_TAB);
        // Should select the current date and the date shouldn't change
        sendKey(KeyEvent.KEYCODE_ENTER);
        assertDateIs(12, 31, 2015);
        // Move right to January 24, 2016
        sendKey(KeyEvent.KEYCODE_DPAD_RIGHT);
        sendKey(KeyEvent.KEYCODE_ENTER);
        assertDateIs(1, 24, 2016);
        // Move down to January 31, 2016
        sendKey(KeyEvent.KEYCODE_DPAD_DOWN);
        sendKey(KeyEvent.KEYCODE_ENTER);
        assertDateIs(1, 31, 2016);
        // Move up to January 5, 2016
        sendKey(KeyEvent.KEYCODE_DPAD_UP);
        sendKey(KeyEvent.KEYCODE_DPAD_UP);
        sendKey(KeyEvent.KEYCODE_DPAD_UP);
        sendKey(KeyEvent.KEYCODE_DPAD_UP);
        sendKey(KeyEvent.KEYCODE_DPAD_RIGHT);
        sendKey(KeyEvent.KEYCODE_DPAD_RIGHT);
        sendKey(KeyEvent.KEYCODE_ENTER);
        assertDateIs(1, 5, 2016);
        // Move up to prev arrow key
        sendKey(KeyEvent.KEYCODE_DPAD_UP);
        assertViewHasFocus(com.android.internal.R.id.prev);
        // tab back into the day-selection pager
        sendKey(KeyEvent.KEYCODE_TAB);
        sendKey(KeyEvent.KEYCODE_TAB);
        sendKey(KeyEvent.KEYCODE_ENTER);
        assertViewHasFocus(com.android.internal.R.id.day_picker_view_pager);
        assertDateIs(1, 5, 2016);

        // Move up out again, then down back into the day-selection pager.
        // It should land right below the prev button (1/3/2016)
        sendKey(KeyEvent.KEYCODE_DPAD_UP);
        sendKey(KeyEvent.KEYCODE_DPAD_DOWN);
        sendKey(KeyEvent.KEYCODE_ENTER);
        assertViewHasFocus(com.android.internal.R.id.day_picker_view_pager);
        assertDateIs(1, 3, 2016);

        // Move left to previous month (12/12/2015)
        sendKey(KeyEvent.KEYCODE_DPAD_LEFT);
        sendKey(KeyEvent.KEYCODE_ENTER);
        assertDateIs(12, 12, 2015);
        // Now make sure the start of the month works
        // Move up to 12/5/2015 and right to 1/1/2016
        sendKey(KeyEvent.KEYCODE_DPAD_UP);
        sendKey(KeyEvent.KEYCODE_DPAD_RIGHT);
        sendKey(KeyEvent.KEYCODE_ENTER);
        assertDateIs(1, 1, 2016);
        // Now make sure the left key goes back to previous month (12/5/2015)
        sendKey(KeyEvent.KEYCODE_DPAD_LEFT);
        sendKey(KeyEvent.KEYCODE_ENTER);
        assertDateIs(12, 5, 2015);
        // Now go to a mismatched row (no such row on previous month)
        // This moves over to 1/31/2016 and then left to 12/31/2015
        sendKey(KeyEvent.KEYCODE_DPAD_DOWN);
        sendKey(KeyEvent.KEYCODE_DPAD_DOWN);
        sendKey(KeyEvent.KEYCODE_DPAD_DOWN);
        sendKey(KeyEvent.KEYCODE_DPAD_RIGHT);
        sendKey(KeyEvent.KEYCODE_DPAD_DOWN);
        sendKey(KeyEvent.KEYCODE_DPAD_DOWN);
        sendKey(KeyEvent.KEYCODE_DPAD_LEFT);
        sendKey(KeyEvent.KEYCODE_ENTER);
        assertDateIs(12, 31, 2015);
    }

    private void assertDateIs(int month, final int day, final int year) throws Throwable {
        final int monthInt = month - 1; // months are 0-based
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertEquals(day, mDatePicker.getDayOfMonth());
                assertEquals(year, mDatePicker.getYear());
                assertEquals(monthInt, mDatePicker.getMonth());
            }
        });
    }

    private void setKnownDate() throws Throwable {
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                mDatePicker.updateDate(2015, 11, 31); // December 31, 2015
            }
        });
        mInstrumentation.waitForIdleSync();
    }
}
