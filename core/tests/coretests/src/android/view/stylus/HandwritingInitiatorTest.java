/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.view.stylus;

import static android.provider.Settings.Global.STYLUS_HANDWRITING_ENABLED;
import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_MOVE;
import static android.view.MotionEvent.ACTION_UP;
import static android.view.stylus.HandwritingTestUtil.createView;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.provider.Settings;
import android.view.HandwritingInitiator;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.inputmethod.InputMethodManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.compatibility.common.util.PollingCheck;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.TimeUnit;

/**
 * Tests for {@link HandwritingInitiator}
 *
 * Build/Install/Run:
 *  atest FrameworksCoreTests:android.view.stylus.HandwritingInitiatorTest
 */
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class HandwritingInitiatorTest {
    private static final long TIMEOUT = ViewConfiguration.getLongPressTimeout();
    private static final int HW_BOUNDS_OFFSETS_LEFT_PX = 10;
    private static final int HW_BOUNDS_OFFSETS_TOP_PX = 20;
    private static final int HW_BOUNDS_OFFSETS_RIGHT_PX = 30;
    private static final int HW_BOUNDS_OFFSETS_BOTTOM_PX = 40;
    private static final int SETTING_VALUE_ON = 1;
    private static final int SETTING_VALUE_OFF = 0;
    private int mHandwritingSlop = 4;

    private static final Rect sHwArea = new Rect(100, 200, 500, 500);

    private HandwritingInitiator mHandwritingInitiator;
    private View mTestView;
    private Context mContext;
    private int mHwInitialState;
    private boolean mShouldRestoreInitialHwState;

    @Before
    public void setup() throws Exception {
        final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        mContext = instrumentation.getTargetContext();

        mHwInitialState = Settings.Global.getInt(mContext.getContentResolver(),
                STYLUS_HANDWRITING_ENABLED, SETTING_VALUE_OFF);
        if (mHwInitialState != SETTING_VALUE_ON) {
            Settings.Global.putInt(mContext.getContentResolver(),
                    STYLUS_HANDWRITING_ENABLED, SETTING_VALUE_ON);
            mShouldRestoreInitialHwState = true;
        }
        String imeId = HandwritingImeService.getImeId();
        instrumentation.getUiAutomation().executeShellCommand("ime enable " + imeId);
        instrumentation.getUiAutomation().executeShellCommand("ime set " + imeId);
        PollingCheck.check("Check that stylus handwriting is available",
                TimeUnit.SECONDS.toMillis(10),
                () -> mContext.getSystemService(InputMethodManager.class)
                        .isStylusHandwritingAvailable());

        final ViewConfiguration viewConfiguration = ViewConfiguration.get(mContext);
        mHandwritingSlop = viewConfiguration.getScaledHandwritingSlop();

        InputMethodManager inputMethodManager = mContext.getSystemService(InputMethodManager.class);
        mHandwritingInitiator =
                spy(new HandwritingInitiator(viewConfiguration, inputMethodManager));

        mTestView = createView(sHwArea, true,
                HW_BOUNDS_OFFSETS_LEFT_PX,
                HW_BOUNDS_OFFSETS_TOP_PX,
                HW_BOUNDS_OFFSETS_RIGHT_PX,
                HW_BOUNDS_OFFSETS_BOTTOM_PX);
        mHandwritingInitiator.updateHandwritingAreasForView(mTestView);
    }

    @After
    public void tearDown() throws Exception {
        if (mShouldRestoreInitialHwState) {
            mShouldRestoreInitialHwState = false;
            Settings.Global.putInt(mContext.getContentResolver(),
                    STYLUS_HANDWRITING_ENABLED, mHwInitialState);
        }
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommand("ime reset");
    }

    @Test
    public void onTouchEvent_startHandwriting_when_stylusMoveOnce_withinHWArea() {
        mHandwritingInitiator.onInputConnectionCreated(mTestView);
        final int x1 = (sHwArea.left + sHwArea.right) / 2;
        final int y1 = (sHwArea.top + sHwArea.bottom) / 2;
        MotionEvent stylusEvent1 = createStylusEvent(ACTION_DOWN, x1, y1, 0);
        boolean onTouchEventResult1 = mHandwritingInitiator.onTouchEvent(stylusEvent1);

        final int x2 = x1 + mHandwritingSlop * 2;
        final int y2 = y1;

        MotionEvent stylusEvent2 = createStylusEvent(ACTION_MOVE, x2, y2, 0);
        boolean onTouchEventResult2 = mHandwritingInitiator.onTouchEvent(stylusEvent2);

        // Stylus movement within HandwritingArea should trigger IMM.startHandwriting once.
        verify(mHandwritingInitiator, times(1)).startHandwriting(mTestView);
        assertThat(onTouchEventResult1).isFalse();
        // After IMM.startHandwriting is triggered, onTouchEvent should return true for ACTION_MOVE
        // events so that the events are not dispatched to the view tree.
        assertThat(onTouchEventResult2).isTrue();
    }

    @Test
    public void onTouchEvent_startHandwritingOnce_when_stylusMoveMultiTimes_withinHWArea() {
        mHandwritingInitiator.onInputConnectionCreated(mTestView);
        final int x1 = (sHwArea.left + sHwArea.right) / 2;
        final int y1 = (sHwArea.top + sHwArea.bottom) / 2;
        MotionEvent stylusEvent1 = createStylusEvent(ACTION_DOWN, x1, y1, 0);
        boolean onTouchEventResult1 = mHandwritingInitiator.onTouchEvent(stylusEvent1);

        final int x2 = x1 + mHandwritingSlop / 2;
        final int y2 = y1;
        MotionEvent stylusEvent2 = createStylusEvent(ACTION_MOVE, x2, y2, 0);
        boolean onTouchEventResult2 = mHandwritingInitiator.onTouchEvent(stylusEvent2);

        final int x3 = x2 + mHandwritingSlop * 2;
        final int y3 = y1;
        MotionEvent stylusEvent3 = createStylusEvent(ACTION_MOVE, x3, y3, 0);
        boolean onTouchEventResult3 = mHandwritingInitiator.onTouchEvent(stylusEvent3);

        final int x4 = x3 + mHandwritingSlop * 2;
        final int y4 = y1;
        MotionEvent stylusEvent4 = createStylusEvent(ACTION_MOVE, x4, y4, 0);
        boolean onTouchEventResult4 = mHandwritingInitiator.onTouchEvent(stylusEvent4);

        MotionEvent stylusEvent5 = createStylusEvent(ACTION_UP, x4, y4, 0);
        boolean onTouchEventResult5 = mHandwritingInitiator.onTouchEvent(stylusEvent5);

        // It only calls startHandwriting once for each ACTION_DOWN.
        verify(mHandwritingInitiator, times(1)).startHandwriting(mTestView);
        assertThat(onTouchEventResult1).isFalse();
        // stylusEvent2 does not trigger IMM.startHandwriting since the touch slop distance has not
        // been exceeded. onTouchEvent should return false so that the event is dispatched to the
        // view tree.
        assertThat(onTouchEventResult2).isFalse();
        // After IMM.startHandwriting is triggered by stylusEvent3, onTouchEvent should return true
        // for ACTION_MOVE events so that the events are not dispatched to the view tree.
        assertThat(onTouchEventResult3).isTrue();
        assertThat(onTouchEventResult4).isTrue();
        assertThat(onTouchEventResult5).isFalse();
    }

    @Test
    public void onTouchEvent_startHandwriting_when_stylusMove_withinExtendedHWArea() {
        mHandwritingInitiator.onInputConnectionCreated(mTestView);
        final int x1 = sHwArea.left - HW_BOUNDS_OFFSETS_LEFT_PX / 2;
        final int y1 = sHwArea.top - HW_BOUNDS_OFFSETS_TOP_PX / 2;
        MotionEvent stylusEvent1 = createStylusEvent(ACTION_DOWN, x1, y1, 0);
        mHandwritingInitiator.onTouchEvent(stylusEvent1);

        final int x2 = x1 + mHandwritingSlop * 2;
        final int y2 = y1;

        MotionEvent stylusEvent2 = createStylusEvent(ACTION_MOVE, x2, y2, 0);
        mHandwritingInitiator.onTouchEvent(stylusEvent2);

        // Stylus movement within extended HandwritingArea should trigger IMM.startHandwriting once.
        verify(mHandwritingInitiator, times(1)).startHandwriting(mTestView);
    }

    @Test
    public void onTouchEvent_startHandwriting_inputConnectionBuiltAfterStylusMove() {
        final int x1 = (sHwArea.left + sHwArea.right) / 2;
        final int y1 = (sHwArea.top + sHwArea.bottom) / 2;
        MotionEvent stylusEvent1 = createStylusEvent(ACTION_DOWN, x1, y1, 0);
        mHandwritingInitiator.onTouchEvent(stylusEvent1);

        final int x2 = x1 + mHandwritingSlop * 2;
        final int y2 = y1;
        MotionEvent stylusEvent2 = createStylusEvent(ACTION_MOVE, x2, y2, 0);
        mHandwritingInitiator.onTouchEvent(stylusEvent2);

        // InputConnection is created after stylus movement.
        mHandwritingInitiator.onInputConnectionCreated(mTestView);

        verify(mHandwritingInitiator, times(1)).startHandwriting(mTestView);
    }

    @Test
    public void onTouchEvent_startHandwriting_inputConnectionBuilt_stylusMoveInExtendedHWArea() {
        final int x1 = sHwArea.right + HW_BOUNDS_OFFSETS_RIGHT_PX / 2;
        final int y1 = sHwArea.bottom + HW_BOUNDS_OFFSETS_BOTTOM_PX / 2;
        MotionEvent stylusEvent1 = createStylusEvent(ACTION_DOWN, x1, y1, 0);
        mHandwritingInitiator.onTouchEvent(stylusEvent1);

        final int x2 = x1 + mHandwritingSlop * 2;
        final int y2 = y1;
        MotionEvent stylusEvent2 = createStylusEvent(ACTION_MOVE, x2, y2, 0);
        mHandwritingInitiator.onTouchEvent(stylusEvent2);

        // InputConnection is created after stylus movement.
        mHandwritingInitiator.onInputConnectionCreated(mTestView);

        verify(mHandwritingInitiator, times(1)).startHandwriting(mTestView);
    }

    @Test
    public void onTouchEvent_notStartHandwriting_whenHandwritingNotAvailable() throws Exception {
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommand("ime reset");
        PollingCheck.check("Check that stylus handwriting is unavailable",
                TimeUnit.SECONDS.toMillis(10),
                () -> !mContext.getSystemService(InputMethodManager.class)
                        .isStylusHandwritingAvailable());

        mHandwritingInitiator.onInputConnectionCreated(mTestView);
        final int x1 = (sHwArea.left + sHwArea.right) / 2;
        final int y1 = (sHwArea.top + sHwArea.bottom) / 2;
        MotionEvent stylusEvent1 = createStylusEvent(ACTION_DOWN, x1, y1, 0);
        mHandwritingInitiator.onTouchEvent(stylusEvent1);

        final int x2 = x1 + mHandwritingSlop * 2;
        final int y2 = y1;

        MotionEvent stylusEvent2 = createStylusEvent(ACTION_MOVE, x2, y2, 0);
        mHandwritingInitiator.onTouchEvent(stylusEvent2);

        // Stylus movement within HandwritingArea should not trigger IMM.startHandwriting since
        // the current IME doesn't support handwriting.
        verify(mHandwritingInitiator, never()).startHandwriting(mTestView);
    }

    @Test
    public void onTouchEvent_notStartHandwriting_when_stylusTap_withinHWArea() {
        mHandwritingInitiator.onInputConnectionCreated(mTestView);
        final int x1 = 200;
        final int y1 = 200;
        MotionEvent stylusEvent1 = createStylusEvent(ACTION_DOWN, x1, y1, 0);
        mHandwritingInitiator.onTouchEvent(stylusEvent1);

        final int x2 = x1 + mHandwritingSlop / 2;
        final int y2 = y1;
        MotionEvent stylusEvent2 = createStylusEvent(ACTION_UP, x2, y2, 0);
        mHandwritingInitiator.onTouchEvent(stylusEvent2);

        verify(mHandwritingInitiator, never()).startHandwriting(mTestView);
    }

    @Test
    public void onTouchEvent_notStartHandwriting_when_stylusMove_outOfHWArea() {
        mHandwritingInitiator.onInputConnectionCreated(mTestView);
        final int x1 = 10;
        final int y1 = 10;
        MotionEvent stylusEvent1 = createStylusEvent(ACTION_DOWN, x1, y1, 0);
        mHandwritingInitiator.onTouchEvent(stylusEvent1);

        final int x2 = x1 + mHandwritingSlop * 2;
        final int y2 = y1;
        MotionEvent stylusEvent2 = createStylusEvent(ACTION_MOVE, x2, y2, 0);
        mHandwritingInitiator.onTouchEvent(stylusEvent2);

        verify(mHandwritingInitiator, never()).startHandwriting(mTestView);
    }

    @Test
    public void onTouchEvent_notStartHandwriting_when_stylusMove_afterTimeOut() {
        mHandwritingInitiator.onInputConnectionCreated(mTestView);
        final int x1 = 10;
        final int y1 = 10;
        final long time1 = 10L;
        MotionEvent stylusEvent1 = createStylusEvent(ACTION_DOWN, x1, y1, 0);
        mHandwritingInitiator.onTouchEvent(stylusEvent1);

        final int x2 = x1 + mHandwritingSlop * 2;
        final int y2 = y1;
        final long time2 = time1 + TIMEOUT + 10L;
        MotionEvent stylusEvent2 = createStylusEvent(ACTION_MOVE, x2, y2, time2);
        mHandwritingInitiator.onTouchEvent(stylusEvent2);

        // stylus movement is after TAP_TIMEOUT it shouldn't call startHandwriting.
        verify(mHandwritingInitiator, never()).startHandwriting(mTestView);
    }

    @Test
    public void onTouchEvent_focusView_stylusMoveOnce_withinHWArea() {
        final int x1 = (sHwArea.left + sHwArea.right) / 2;
        final int y1 = (sHwArea.top + sHwArea.bottom) / 2;
        MotionEvent stylusEvent1 = createStylusEvent(ACTION_DOWN, x1, y1, 0);
        mHandwritingInitiator.onTouchEvent(stylusEvent1);

        final int x2 = x1 + mHandwritingSlop * 2;
        final int y2 = y1;

        MotionEvent stylusEvent2 = createStylusEvent(ACTION_MOVE, x2, y2, 0);
        mHandwritingInitiator.onTouchEvent(stylusEvent2);

        // HandwritingInitiator will request focus for the registered view.
        verify(mTestView, times(1)).requestFocus();
    }

    @Test
    public void onTouchEvent_focusView_stylusMoveOnce_withinExtendedHWArea() {
        final int x1 = sHwArea.left - HW_BOUNDS_OFFSETS_LEFT_PX / 2;
        final int y1 = sHwArea.top - HW_BOUNDS_OFFSETS_TOP_PX / 2;
        MotionEvent stylusEvent1 = createStylusEvent(ACTION_DOWN, x1, y1, 0);
        mHandwritingInitiator.onTouchEvent(stylusEvent1);

        final int x2 = x1 + mHandwritingSlop * 2;
        final int y2 = y1;

        MotionEvent stylusEvent2 = createStylusEvent(ACTION_MOVE, x2, y2, 0);
        mHandwritingInitiator.onTouchEvent(stylusEvent2);

        // HandwritingInitiator will request focus for the registered view.
        verify(mTestView, times(1)).requestFocus();
    }

    @Test
    public void autoHandwriting_whenDisabled_wontStartHW() {
        View mockView = createView(sHwArea, false);
        mHandwritingInitiator.onInputConnectionCreated(mockView);
        final int x1 = (sHwArea.left + sHwArea.right) / 2;
        final int y1 = (sHwArea.top + sHwArea.bottom) / 2;
        MotionEvent stylusEvent1 = createStylusEvent(ACTION_DOWN, x1, y1, 0);
        mHandwritingInitiator.onTouchEvent(stylusEvent1);

        final int x2 = x1 + mHandwritingSlop * 2;
        final int y2 = y1;

        MotionEvent stylusEvent2 = createStylusEvent(ACTION_MOVE, x2, y2, 0);
        mHandwritingInitiator.onTouchEvent(stylusEvent2);

        verify(mHandwritingInitiator, never()).startHandwriting(mTestView);
    }

    @Test
    public void onInputConnectionCreated() {
        mHandwritingInitiator.onInputConnectionCreated(mTestView);
        assertThat(mHandwritingInitiator.mConnectedView).isNotNull();
        assertThat(mHandwritingInitiator.mConnectedView.get()).isEqualTo(mTestView);
    }

    @Test
    public void onInputConnectionCreated_whenAutoHandwritingIsDisabled() {
        View view = new View(mContext);
        view.setAutoHandwritingEnabled(false);
        assertThat(view.isAutoHandwritingEnabled()).isFalse();
        mHandwritingInitiator.onInputConnectionCreated(view);

        assertThat(mHandwritingInitiator.mConnectedView).isNull();
    }

    @Test
    public void onInputConnectionClosed() {
        mHandwritingInitiator.onInputConnectionCreated(mTestView);
        mHandwritingInitiator.onInputConnectionClosed(mTestView);

        assertThat(mHandwritingInitiator.mConnectedView).isNull();
    }

    @Test
    public void onInputConnectionClosed_whenAutoHandwritingIsDisabled() {
        View view = new View(mContext);
        view.setAutoHandwritingEnabled(false);
        mHandwritingInitiator.onInputConnectionCreated(view);
        mHandwritingInitiator.onInputConnectionClosed(view);

        assertThat(mHandwritingInitiator.mConnectedView).isNull();
    }

    @Test
    public void onInputConnectionCreated_inputConnectionRestarted() {
        // When IMM restarts input connection, View#onInputConnectionCreatedInternal might be
        // called before View#onInputConnectionClosedInternal. As a result, we need to handle the
        // case where "one view "2 InputConnections".
        mHandwritingInitiator.onInputConnectionCreated(mTestView);
        mHandwritingInitiator.onInputConnectionCreated(mTestView);
        mHandwritingInitiator.onInputConnectionClosed(mTestView);

        assertThat(mHandwritingInitiator.mConnectedView).isNotNull();
        assertThat(mHandwritingInitiator.mConnectedView.get()).isEqualTo(mTestView);
    }

    private MotionEvent createStylusEvent(int action, int x, int y, long eventTime) {
        MotionEvent.PointerProperties[] properties = MotionEvent.PointerProperties.createArray(1);
        properties[0].toolType = MotionEvent.TOOL_TYPE_STYLUS;

        MotionEvent.PointerCoords[] coords = MotionEvent.PointerCoords.createArray(1);
        coords[0].x = x;
        coords[0].y = y;

        return MotionEvent.obtain(0 /* downTime */, eventTime /* eventTime */, action, 1,
                properties, coords, 0 /* metaState */, 0 /* buttonState */, 1 /* xPrecision */,
                1 /* yPrecision */, 0 /* deviceId */, 0 /* edgeFlags */,
                InputDevice.SOURCE_TOUCHSCREEN, 0 /* flags */);
    }
}
