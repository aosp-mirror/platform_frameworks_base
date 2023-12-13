/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import android.annotation.UiThread;
import android.app.Instrumentation;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;

import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.frameworks.coretests.R;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Calendar;
import java.util.GregorianCalendar;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class PointerIconTest {
    private Instrumentation mInstrumentation;

    @Before
    public void setup() {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
    }

    @Rule
    public ActivityScenarioRule<PointerIconTestActivity> mActivityScenarioRule =
            new ActivityScenarioRule<>(PointerIconTestActivity.class);

    @Test
    @UiThread
    public void button_mouse_onResolvePointerIcon_returnsTypeHand() {
        assertOnResolvePointerIconForMouseEvent(R.id.button, PointerIcon.TYPE_HAND);
    }

    @Test
    @UiThread
    public void button_mouse_disabled_onResolvePointerIcon_returnsNull() {
        assertOnResolvePointerIconReturnNull(R.id.button, /* enabled */ false, /* clickable */ true,
                /* isMouse */ true);
    }

    @Test
    @UiThread
    public void button_mouse_unclickable_onResolvePointerIcon_returnsNull() {
        assertOnResolvePointerIconReturnNull(R.id.button, /* enabled */ true, /* clickable */ false,
                /* isMouse */ true);
    }

    @Test
    @UiThread
    public void button_stylus_onResolvePointerIcon_returnsNull() {
        assertOnResolvePointerIconReturnNull(R.id.button, /* enabled */ true, /* clickable */ true,
                /* isMouse */ false);
    }

    @Test
    @UiThread
    public void imageButton_mouse_onResolvePointerIconreturnsTypeHand() {
        assertOnResolvePointerIconForMouseEvent(R.id.imagebutton, PointerIcon.TYPE_HAND);
    }

    @Test
    @UiThread
    public void imageButton_mouse_diabled_onResolvePointerIcon_returnsNull() {
        assertOnResolvePointerIconReturnNull(R.id.imagebutton, /* enabled */ false,
                /* clickable */ true, /* isMouse */ true);
    }

    @Test
    @UiThread
    public void imageButton_mouse_unclickable_onResolvePointerIcon_returnsNull() {
        assertOnResolvePointerIconReturnNull(R.id.imagebutton, /* enabled */ true,
                /* clickable */ false, /* isMouse */ true);
    }

    @Test
    @UiThread
    public void imageButton_stylus_onResolvePointerIcon_returnsNull() {
        assertOnResolvePointerIconReturnNull(R.id.imagebutton, /* enabled */ true,
                /* clickable */ true, /* isMouse */ false);
    }

    @Test
    @UiThread
    public void textView_mouse_onResolvePointerIcon_returnsNull() {
        assertOnResolvePointerIconReturnNull(R.id.textview, /* enabled */ true,
                /* clickable */ true, /* isMouse */ true);
    }

    @Test
    @UiThread
    public void textView_stylus_onResolvePointerIcon_returnsNull() {
        assertOnResolvePointerIconReturnNull(R.id.textview, /* enabled */ true,
                /* clickable */ true, /* isMouse */ false);
    }

    @Test
    @UiThread
    public void editText_mouse_onResolvePointerIcon_returnsTypeText() {
        assertOnResolvePointerIconForMouseEvent(R.id.edittext, PointerIcon.TYPE_TEXT);
    }

    @Test
    @UiThread
    public void editText_stylus_onResolvePointerIcon_returnsNull() {
        assertOnResolvePointerIconReturnNull(R.id.edittext, /* enabled */ true,
                /* clickable */ true, /* isMouse */ false);
    }

    @Test
    @UiThread
    public void spinner_mouse_onResolvePointerIcon_returnsTypeHand() {
        assertOnResolvePointerIconForMouseEvent(R.id.spinner, PointerIcon.TYPE_HAND);
    }

    @Test
    @UiThread
    public void spinner_mouse_disabled_onResolvePointerIcon_returnsNull() {
        assertOnResolvePointerIconReturnNull(R.id.spinner, /* enabled */ false,
                /* clickable */ true, /* isMouse */ true);
    }

    @Test
    @UiThread
    public void spinner_mouse_unclickable_onResolvePointerIcon_returnsNull() {
        assertOnResolvePointerIconReturnNull(R.id.spinner, /* enabled */ true,
                /* clickable */ false, /* isMouse */ true);
    }

    @Test
    @UiThread
    public void spinner_stylus_onResolvePointerIcon_returnsNull() {
        assertOnResolvePointerIconReturnNull(R.id.spinner, /* enabled */ true, /* clickable */ true,
                /* isMouse */ false);
    }

    @Test
    @UiThread
    public void radialTimePickerView_mouse_onResolvePointerIcon_returnsTypeHand() {
        assertOnResolvePointerIconForMouseEvent(R.id.timepicker, PointerIcon.TYPE_HAND);

    }

    @Test
    @UiThread
    public void radialTimePickerView_mouse_disabled_onResolvePointerIcon_returnsNull() {
        assertOnResolvePointerIconReturnNull(R.id.timepicker, /* enabled */ false,
                /* clickable */ true, /* isMouse */ true);
    }

    @Test
    @UiThread
    public void radialTimePickerView_stylus_onResolvePointerIcon_returnsNull() {
        assertOnResolvePointerIconReturnNull(R.id.timepicker, /* enabled */ true,
                /* clickable */ true, /* isMouse */ false);
    }

    @Test
    @UiThread
    public void calendarView_mouse_onResolvePointerIcon_returnsTypeHand() {
        assertPointerIconForCalendarView(/* pointerType */ PointerIcon.TYPE_HAND,
                /* isMouse */ true);
    }

    @Test
    @UiThread
    public void calendarView_stylus_onResolvePointerIcon_returnsNull() {
        assertPointerIconForCalendarView(/* pointerType */ Integer.MIN_VALUE, /* isMouse */ false);
    }

    /**
     * Assert {@link View#onResolvePointerIcon} method for {@link CalendarView}.
     *
     * @param pointerType the expected type of the {@link PointerIcon}.
     *                    When {@link Integer#MIN_VALUE} is passed, it will verify that the
     *                    returned {@link PointerIcon} is null.
     * @param isMouse if true, mouse events are used to test the given view. Otherwise, it uses
     *               stylus events to test the view.
     */
    void assertPointerIconForCalendarView(int pointerType, boolean isMouse) {
        Calendar calendar = new GregorianCalendar();
        calendar.set(2023, 0, 1);
        long time = calendar.getTimeInMillis();
        mActivityScenarioRule.getScenario().onActivity(activity -> {
            CalendarView calendarView = activity.findViewById(R.id.calendar);
            calendarView.setDate(time, /* animate */ false, /* center */true);
        });

        // Wait for setDate to finish and then verify.
        mInstrumentation.waitForIdleSync();
        mActivityScenarioRule.getScenario().onActivity(activity -> {
            CalendarView calendarView = activity.findViewById(R.id.calendar);
            Rect bounds = new Rect();
            calendarView.getBoundsForDate(time, bounds);
            MotionEvent event = createHoverEvent(isMouse, bounds.centerX(), bounds.centerY());
            PointerIcon icon = calendarView.onResolvePointerIcon(event, /* pointerIndex */ 0);
            if (pointerType != Integer.MIN_VALUE) {
                assertThat(icon.getType()).isEqualTo(pointerType);
            } else {
                assertThat(icon).isNull();
            }
        });
    }

    /**
     * Assert that the given view's {@link View#onResolvePointerIcon(MotionEvent, int)} method
     * returns a {@link PointerIcon} with the specified pointer type. The passed {@link MotionEvent}
     * locates at the center of the view.
     *
     * @param resId the resource id of the view to be tested.
     * @param pointerType the expected pointer type. When {@link Integer#MIN_VALUE} is passed, it
     *                   will verify that the returned {@link PointerIcon} is null.
     */
    public void assertOnResolvePointerIconForMouseEvent(int resId, int pointerType) {
        mActivityScenarioRule.getScenario().onActivity(activity -> {
            View view = activity.findViewById(resId);
            MotionEvent event = createHoverEvent(/* isMouse */ true, /* x */ 0, /* y */ 0);
            PointerIcon icon = view.onResolvePointerIcon(event, /* pointerIndex */ 0);
            if (pointerType != Integer.MIN_VALUE) {
                assertThat(icon.getType()).isEqualTo(pointerType);
            } else {
                assertThat(icon).isNull();
            }
        });
    }

    /**
     * Assert that the given view's {@link View#onResolvePointerIcon(MotionEvent, int)} method
     * returns a {@link PointerIcon} with the specified pointer type. The passed {@link MotionEvent}
     * locates at the center of the view.
     *
     * @param resId the resource id of the view to be tested.
     * @param enabled whether the tested  view is enabled.
     * @param clickable whether the tested view is clickable.
     * @param isMouse if true, mouse events are used to test the given view. Otherwise, it uses
     *               stylus events to test the view.
     */
    public void assertOnResolvePointerIconReturnNull(int resId, boolean enabled, boolean clickable,
            boolean isMouse) {
        mActivityScenarioRule.getScenario().onActivity(activity -> {
            View view = activity.findViewById(resId);
            view.setEnabled(enabled);
            view.setClickable(clickable);
            MotionEvent event = createHoverEvent(isMouse, /* x */ 0, /* y */ 0);
            PointerIcon icon = view.onResolvePointerIcon(event, /* pointerIndex */ 0);
            assertThat(icon).isNull();
        });
    }


    /**
     * Create a hover {@link MotionEvent} for testing.
     *
     * @param isMouse if true, a {@link MotionEvent} from mouse is returned. Otherwise,
     *               a {@link MotionEvent} from stylus is returned.
     * @param x the x coordinate of the returned {@link MotionEvent}
     * @param y the y coordinate of the returned {@link MotionEvent}
     */
    private MotionEvent createHoverEvent(boolean isMouse, int x, int y) {
        MotionEvent.PointerProperties[] properties = MotionEvent.PointerProperties.createArray(1);
        properties[0].toolType =
                isMouse ? MotionEvent.TOOL_TYPE_MOUSE : MotionEvent.TOOL_TYPE_STYLUS;

        MotionEvent.PointerCoords[] coords = MotionEvent.PointerCoords.createArray(1);
        coords[0].x = x;
        coords[0].y = y;

        int source = isMouse ? InputDevice.SOURCE_MOUSE : InputDevice.SOURCE_STYLUS;
        long eventTime = SystemClock.uptimeMillis();
        return MotionEvent.obtain(/* downTime */ 0, eventTime, MotionEvent.ACTION_HOVER_MOVE,
                /* pointerCount */ 1, properties, coords, /* metaState */ 0, /* buttonState */ 0,
                /* xPrecision */ 1, /* yPrecision */ 1, /* deviceId */ 0, /* edgeFlags */ 0,
                source, /* flags */ 0);
    }

}
