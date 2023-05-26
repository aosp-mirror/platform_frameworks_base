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

import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_HOVER_MOVE;
import static android.view.MotionEvent.ACTION_MOVE;
import static android.view.MotionEvent.ACTION_UP;
import static android.view.stylus.HandwritingTestUtil.createView;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.view.HandwritingInitiator;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

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
    private int mHandwritingSlop = 4;

    private static final Rect sHwArea1;
    private static final Rect sHwArea2;

    static {
        sHwArea1 = new Rect(100, 200, 500, 500);
        // The extended handwriting area bounds of the two views are overlapping.
        int hwArea2Top = sHwArea1.bottom + HW_BOUNDS_OFFSETS_TOP_PX / 2;
        sHwArea2 = new Rect(sHwArea1.left, hwArea2Top, sHwArea1.right, hwArea2Top + 300);
    }

    private HandwritingInitiator mHandwritingInitiator;
    private View mTestView1;
    private View mTestView2;
    private Context mContext;

    @Before
    public void setup() throws Exception {
        final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        mContext = instrumentation.getTargetContext();

        final ViewConfiguration viewConfiguration = ViewConfiguration.get(mContext);
        mHandwritingSlop = viewConfiguration.getScaledHandwritingSlop();

        InputMethodManager inputMethodManager = mContext.getSystemService(InputMethodManager.class);
        mHandwritingInitiator =
                spy(new HandwritingInitiator(viewConfiguration, inputMethodManager));

        mTestView1 = createView(sHwArea1, /* autoHandwritingEnabled= */ true,
                /* isStylusHandwritingAvailable= */ true,
                HW_BOUNDS_OFFSETS_LEFT_PX,
                HW_BOUNDS_OFFSETS_TOP_PX,
                HW_BOUNDS_OFFSETS_RIGHT_PX,
                HW_BOUNDS_OFFSETS_BOTTOM_PX);
        mTestView2 = createView(sHwArea2, /* autoHandwritingEnabled= */ true,
                /* isStylusHandwritingAvailable= */ true,
                HW_BOUNDS_OFFSETS_LEFT_PX,
                HW_BOUNDS_OFFSETS_TOP_PX,
                HW_BOUNDS_OFFSETS_RIGHT_PX,
                HW_BOUNDS_OFFSETS_BOTTOM_PX);
        mHandwritingInitiator.updateHandwritingAreasForView(mTestView1);
        mHandwritingInitiator.updateHandwritingAreasForView(mTestView2);
        doReturn(true).when(mHandwritingInitiator).tryAcceptStylusHandwritingDelegation(any());
    }

    @Test
    public void onTouchEvent_startHandwriting_when_stylusMoveOnce_withinHWArea() {
        mHandwritingInitiator.onInputConnectionCreated(mTestView1);
        final int x1 = (sHwArea1.left + sHwArea1.right) / 2;
        final int y1 = (sHwArea1.top + sHwArea1.bottom) / 2;
        MotionEvent stylusEvent1 = createStylusEvent(ACTION_DOWN, x1, y1, 0);
        boolean onTouchEventResult1 = mHandwritingInitiator.onTouchEvent(stylusEvent1);

        final int x2 = x1 + mHandwritingSlop * 2;
        final int y2 = y1;

        MotionEvent stylusEvent2 = createStylusEvent(ACTION_MOVE, x2, y2, 0);
        boolean onTouchEventResult2 = mHandwritingInitiator.onTouchEvent(stylusEvent2);

        // Stylus movement within HandwritingArea should trigger IMM.startHandwriting once.
        verify(mHandwritingInitiator, times(1)).startHandwriting(mTestView1);
        assertThat(onTouchEventResult1).isFalse();
        // After IMM.startHandwriting is triggered, onTouchEvent should return true for ACTION_MOVE
        // events so that the events are not dispatched to the view tree.
        assertThat(onTouchEventResult2).isTrue();
    }

    @Test
    public void onTouchEvent_startHandwritingOnce_when_stylusMoveMultiTimes_withinHWArea() {
        mHandwritingInitiator.onInputConnectionCreated(mTestView1);
        final int x1 = (sHwArea1.left + sHwArea1.right) / 2;
        final int y1 = (sHwArea1.top + sHwArea1.bottom) / 2;
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
        verify(mHandwritingInitiator, times(1)).startHandwriting(mTestView1);
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
        mHandwritingInitiator.onInputConnectionCreated(mTestView1);
        final int x1 = sHwArea1.left - HW_BOUNDS_OFFSETS_LEFT_PX / 2;
        final int y1 = sHwArea1.top - HW_BOUNDS_OFFSETS_TOP_PX / 2;
        MotionEvent stylusEvent1 = createStylusEvent(ACTION_DOWN, x1, y1, 0);
        mHandwritingInitiator.onTouchEvent(stylusEvent1);

        final int x2 = x1 + mHandwritingSlop * 2;
        final int y2 = y1;

        MotionEvent stylusEvent2 = createStylusEvent(ACTION_MOVE, x2, y2, 0);
        mHandwritingInitiator.onTouchEvent(stylusEvent2);

        // Stylus movement within extended HandwritingArea should trigger IMM.startHandwriting once.
        verify(mHandwritingInitiator, times(1)).startHandwriting(mTestView1);
    }

    @Test
    public void onTouchEvent_startHandwriting_inputConnectionBuiltAfterStylusMove() {
        final int x1 = (sHwArea1.left + sHwArea1.right) / 2;
        final int y1 = (sHwArea1.top + sHwArea1.bottom) / 2;
        MotionEvent stylusEvent1 = createStylusEvent(ACTION_DOWN, x1, y1, 0);
        mHandwritingInitiator.onTouchEvent(stylusEvent1);

        final int x2 = x1 + mHandwritingSlop * 2;
        final int y2 = y1;
        MotionEvent stylusEvent2 = createStylusEvent(ACTION_MOVE, x2, y2, 0);
        mHandwritingInitiator.onTouchEvent(stylusEvent2);

        // InputConnection is created after stylus movement.
        mHandwritingInitiator.onInputConnectionCreated(mTestView1);

        verify(mHandwritingInitiator, times(1)).startHandwriting(mTestView1);
    }

    @Test
    public void onTouchEvent_startHandwriting_inputConnectionBuilt_stylusMoveInExtendedHWArea() {
        final int x1 = sHwArea1.right + HW_BOUNDS_OFFSETS_RIGHT_PX / 2;
        final int y1 = sHwArea1.bottom + HW_BOUNDS_OFFSETS_BOTTOM_PX / 2;
        MotionEvent stylusEvent1 = createStylusEvent(ACTION_DOWN, x1, y1, 0);
        mHandwritingInitiator.onTouchEvent(stylusEvent1);

        final int x2 = x1 + mHandwritingSlop * 2;
        final int y2 = y1;
        MotionEvent stylusEvent2 = createStylusEvent(ACTION_MOVE, x2, y2, 0);
        mHandwritingInitiator.onTouchEvent(stylusEvent2);

        // InputConnection is created after stylus movement.
        mHandwritingInitiator.onInputConnectionCreated(mTestView1);

        verify(mHandwritingInitiator, times(1)).startHandwriting(mTestView1);
    }

    @Test
    public void onTouchEvent_tryAcceptDelegation_delegatorCallbackCreatesInputConnection() {
        View delegateView = new View(mContext);
        delegateView.setIsHandwritingDelegate(true);

        mTestView1.setHandwritingDelegatorCallback(
                () -> mHandwritingInitiator.onInputConnectionCreated(delegateView));

        final int x1 = (sHwArea1.left + sHwArea1.right) / 2;
        final int y1 = (sHwArea1.top + sHwArea1.bottom) / 2;
        MotionEvent stylusEvent1 = createStylusEvent(ACTION_DOWN, x1, y1, 0);
        mHandwritingInitiator.onTouchEvent(stylusEvent1);

        final int x2 = x1 + mHandwritingSlop * 2;
        final int y2 = y1;
        MotionEvent stylusEvent2 = createStylusEvent(ACTION_MOVE, x2, y2, 0);
        mHandwritingInitiator.onTouchEvent(stylusEvent2);

        verify(mHandwritingInitiator, times(1)).tryAcceptStylusHandwritingDelegation(delegateView);
    }

    @Test
    public void onTouchEvent_tryAcceptDelegation_delegatorCallbackFocusesDelegate() {
        View delegateView = new View(mContext);
        delegateView.setIsHandwritingDelegate(true);
        mHandwritingInitiator.onInputConnectionCreated(delegateView);
        reset(mHandwritingInitiator);

        mTestView1.setHandwritingDelegatorCallback(
                () -> mHandwritingInitiator.onDelegateViewFocused(delegateView));

        final int x1 = (sHwArea1.left + sHwArea1.right) / 2;
        final int y1 = (sHwArea1.top + sHwArea1.bottom) / 2;
        MotionEvent stylusEvent1 = createStylusEvent(ACTION_DOWN, x1, y1, 0);
        mHandwritingInitiator.onTouchEvent(stylusEvent1);

        final int x2 = x1 + mHandwritingSlop * 2;
        final int y2 = y1;
        MotionEvent stylusEvent2 = createStylusEvent(ACTION_MOVE, x2, y2, 0);
        mHandwritingInitiator.onTouchEvent(stylusEvent2);

        verify(mHandwritingInitiator, times(1)).tryAcceptStylusHandwritingDelegation(delegateView);
    }

    @Test
    public void onTouchEvent_startHandwriting_delegate_touchEventsHandled() {
        // There is no delegator view and the delegate callback does nothing so handwriting will not
        // be started. This is so we can test how touch events are handled before handwriting is
        // started.
        mTestView1.setHandwritingDelegatorCallback(() -> {});

        final int x1 = (sHwArea1.left + sHwArea1.right) / 2;
        final int y = (sHwArea1.top + sHwArea1.bottom) / 2;
        MotionEvent stylusEvent1 = createStylusEvent(ACTION_DOWN, x1, y, 0);
        boolean onTouchEventResult1 = mHandwritingInitiator.onTouchEvent(stylusEvent1);

        final int x2 = x1 + mHandwritingSlop / 2;
        MotionEvent stylusEvent2 = createStylusEvent(ACTION_MOVE, x2, y, 0);
        boolean onTouchEventResult2 = mHandwritingInitiator.onTouchEvent(stylusEvent2);

        final int x3 = x2 + mHandwritingSlop * 2;
        MotionEvent stylusEvent3 = createStylusEvent(ACTION_MOVE, x3, y, 0);
        boolean onTouchEventResult3 = mHandwritingInitiator.onTouchEvent(stylusEvent3);

        final int x4 = x3 + mHandwritingSlop * 2;
        MotionEvent stylusEvent4 = createStylusEvent(ACTION_MOVE, x4, y, 0);
        boolean onTouchEventResult4 = mHandwritingInitiator.onTouchEvent(stylusEvent4);

        assertThat(onTouchEventResult1).isFalse();
        // stylusEvent2 does not trigger delegation since the touch slop distance has not been
        // exceeded. onTouchEvent should return false so that the event is dispatched to the view
        // tree.
        assertThat(onTouchEventResult2).isFalse();
        // After delegation is triggered by stylusEvent3, onTouchEvent should return true for
        // ACTION_MOVE events so that the events are not dispatched to the view tree.
        assertThat(onTouchEventResult3).isTrue();
        assertThat(onTouchEventResult4).isTrue();
    }

    @Test
    public void onTouchEvent_notStartHandwriting_whenHandwritingNotAvailable() {
        final Rect rect = new Rect(600, 600, 900, 900);
        final View testView = createView(rect, true /* autoHandwritingEnabled */,
                false /* isStylusHandwritingAvailable */);
        mHandwritingInitiator.updateHandwritingAreasForView(testView);

        mHandwritingInitiator.onInputConnectionCreated(testView);
        final int x1 = (rect.left + rect.right) / 2;
        final int y1 = (rect.top + rect.bottom) / 2;
        MotionEvent stylusEvent1 = createStylusEvent(ACTION_DOWN, x1, y1, 0);
        mHandwritingInitiator.onTouchEvent(stylusEvent1);

        final int x2 = x1 + mHandwritingSlop * 2;
        final int y2 = y1;

        MotionEvent stylusEvent2 = createStylusEvent(ACTION_MOVE, x2, y2, 0);
        mHandwritingInitiator.onTouchEvent(stylusEvent2);

        // Stylus movement within HandwritingArea should not trigger IMM.startHandwriting since
        // the current IME doesn't support handwriting.
        verify(mHandwritingInitiator, never()).startHandwriting(mTestView1);
    }

    @Test
    public void onTouchEvent_notStartHandwriting_when_stylusTap_withinHWArea() {
        mHandwritingInitiator.onInputConnectionCreated(mTestView1);
        final int x1 = 200;
        final int y1 = 200;
        MotionEvent stylusEvent1 = createStylusEvent(ACTION_DOWN, x1, y1, 0);
        mHandwritingInitiator.onTouchEvent(stylusEvent1);

        final int x2 = x1 + mHandwritingSlop / 2;
        final int y2 = y1;
        MotionEvent stylusEvent2 = createStylusEvent(ACTION_UP, x2, y2, 0);
        mHandwritingInitiator.onTouchEvent(stylusEvent2);

        verify(mHandwritingInitiator, never()).startHandwriting(mTestView1);
    }

    @Test
    public void onTouchEvent_notStartHandwriting_when_stylusMove_outOfHWArea() {
        mHandwritingInitiator.onInputConnectionCreated(mTestView1);
        final int x1 = 10;
        final int y1 = 10;
        MotionEvent stylusEvent1 = createStylusEvent(ACTION_DOWN, x1, y1, 0);
        mHandwritingInitiator.onTouchEvent(stylusEvent1);

        final int x2 = x1 + mHandwritingSlop * 2;
        final int y2 = y1;
        MotionEvent stylusEvent2 = createStylusEvent(ACTION_MOVE, x2, y2, 0);
        mHandwritingInitiator.onTouchEvent(stylusEvent2);

        verify(mHandwritingInitiator, never()).startHandwriting(mTestView1);
    }

    @Test
    public void onTouchEvent_notStartHandwriting_when_stylusMove_afterTimeOut() {
        mHandwritingInitiator.onInputConnectionCreated(mTestView1);
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
        verify(mHandwritingInitiator, never()).startHandwriting(mTestView1);
    }

    @Test
    public void onTouchEvent_focusView_stylusMoveOnce_withinHWArea() {
        final int x1 = (sHwArea1.left + sHwArea1.right) / 2;
        final int y1 = (sHwArea1.top + sHwArea1.bottom) / 2;
        MotionEvent stylusEvent1 = createStylusEvent(ACTION_DOWN, x1, y1, 0);
        mHandwritingInitiator.onTouchEvent(stylusEvent1);

        final int x2 = x1 + mHandwritingSlop * 2;
        final int y2 = y1;

        MotionEvent stylusEvent2 = createStylusEvent(ACTION_MOVE, x2, y2, 0);
        mHandwritingInitiator.onTouchEvent(stylusEvent2);

        // HandwritingInitiator will request focus for the registered view.
        verify(mTestView1, times(1)).requestFocus();
    }

    @Test
    public void onTouchEvent_focusView_inputConnectionAlreadyBuilt_stylusMoveOnce_withinHWArea() {
        mHandwritingInitiator.onInputConnectionCreated(mTestView1);

        final int x1 = (sHwArea1.left + sHwArea1.right) / 2;
        final int y1 = (sHwArea1.top + sHwArea1.bottom) / 2;
        MotionEvent stylusEvent1 = createStylusEvent(ACTION_DOWN, x1, y1, 0);
        mHandwritingInitiator.onTouchEvent(stylusEvent1);

        final int x2 = x1 + mHandwritingSlop * 2;
        final int y2 = y1;

        MotionEvent stylusEvent2 = createStylusEvent(ACTION_MOVE, x2, y2, 0);
        mHandwritingInitiator.onTouchEvent(stylusEvent2);

        // View has input connection but not focus, so HandwritingInitiator will request focus
        // before starting handwriting.
        verify(mTestView1).requestFocus();
        verify(mHandwritingInitiator).startHandwriting(mTestView1);
    }

    @Test
    public void onTouchEvent_focusView_stylusMoveOnce_withinExtendedHWArea() {
        final int x1 = sHwArea1.left - HW_BOUNDS_OFFSETS_LEFT_PX / 2;
        final int y1 = sHwArea1.top - HW_BOUNDS_OFFSETS_TOP_PX / 2;
        MotionEvent stylusEvent1 = createStylusEvent(ACTION_DOWN, x1, y1, 0);
        mHandwritingInitiator.onTouchEvent(stylusEvent1);

        final int x2 = x1 + mHandwritingSlop * 2;
        final int y2 = y1;

        MotionEvent stylusEvent2 = createStylusEvent(ACTION_MOVE, x2, y2, 0);
        mHandwritingInitiator.onTouchEvent(stylusEvent2);

        // HandwritingInitiator will request focus for the registered view.
        verify(mTestView1, times(1)).requestFocus();
    }

    @Test
    public void onTouchEvent_handwritingAreaOverlapped_initiateForCloserView() {
        // The ACTION_DOWN location is within the handwriting bounds of both mTestView1 and
        // mTestView2. Because it's closer to mTestView2's handwriting bounds, handwriting is
        // initiated for mTestView2.
        final int x1 = (sHwArea1.left + sHwArea1.right) / 2;
        final int y1 = sHwArea1.bottom + HW_BOUNDS_OFFSETS_BOTTOM_PX - 1;
        MotionEvent stylusEvent1 = createStylusEvent(ACTION_DOWN, x1, y1, 0);
        mHandwritingInitiator.onTouchEvent(stylusEvent1);

        final int x2 = x1 + mHandwritingSlop * 2;
        final int y2 = y1;

        MotionEvent stylusEvent2 = createStylusEvent(ACTION_MOVE, x2, y2, 0);
        mHandwritingInitiator.onTouchEvent(stylusEvent2);

        verify(mTestView2, times(1)).requestFocus();

        mHandwritingInitiator.onInputConnectionCreated(mTestView2);
        verify(mHandwritingInitiator, times(1)).startHandwriting(mTestView2);
    }

    @Test
    public void onTouchEvent_handwritingAreaOverlapped_focusedViewHasPriority() {
        // Simulate the case where mTestView1 is focused.
        mHandwritingInitiator.onInputConnectionCreated(mTestView1);
        // The ACTION_DOWN location is within the handwriting bounds of both mTestView1 and
        // mTestView2. Although it's closer to mTestView2's handwriting bounds, handwriting is
        // initiated for mTestView1 because it's focused.
        final int x1 = (sHwArea1.left + sHwArea1.right) / 2;
        final int y1 = sHwArea1.bottom + HW_BOUNDS_OFFSETS_BOTTOM_PX - 1;
        MotionEvent stylusEvent1 = createStylusEvent(ACTION_DOWN, x1, y1, 0);
        mHandwritingInitiator.onTouchEvent(stylusEvent1);

        final int x2 = x1 + mHandwritingSlop * 2;
        final int y2 = y1;

        MotionEvent stylusEvent2 = createStylusEvent(ACTION_MOVE, x2, y2, 0);
        mHandwritingInitiator.onTouchEvent(stylusEvent2);

        verify(mHandwritingInitiator, times(1)).startHandwriting(mTestView1);
    }

    @Test
    public void onResolvePointerIcon_withinHWArea_showPointerIcon() {
        MotionEvent hoverEvent = createStylusHoverEvent(sHwArea1.centerX(), sHwArea1.centerY());
        PointerIcon icon = mHandwritingInitiator.onResolvePointerIcon(mContext, hoverEvent);
        assertThat(icon.getType()).isEqualTo(PointerIcon.TYPE_HANDWRITING);
    }

    @Test
    public void onResolvePointerIcon_withinExtendedHWArea_showPointerIcon() {
        int x = sHwArea1.left - HW_BOUNDS_OFFSETS_LEFT_PX / 2;
        int y = sHwArea1.top - HW_BOUNDS_OFFSETS_TOP_PX / 2;
        MotionEvent hoverEvent = createStylusHoverEvent(x, y);

        PointerIcon icon = mHandwritingInitiator.onResolvePointerIcon(mContext, hoverEvent);
        assertThat(icon.getType()).isEqualTo(PointerIcon.TYPE_HANDWRITING);
    }

    @Test
    public void onResolvePointerIcon_afterHandwriting_hidePointerIconForConnectedView() {
        // simulate the case where sTestView1 is focused.
        mHandwritingInitiator.onInputConnectionCreated(mTestView1);
        injectStylusEvent(mHandwritingInitiator, sHwArea1.centerX(), sHwArea1.centerY(),
                /* exceedsHWSlop */ true);
        // Verify that handwriting started for sTestView1.
        verify(mHandwritingInitiator, times(1)).startHandwriting(mTestView1);

        MotionEvent hoverEvent1 = createStylusHoverEvent(sHwArea1.centerX(), sHwArea1.centerY());
        PointerIcon icon1 = mHandwritingInitiator.onResolvePointerIcon(mContext, hoverEvent1);
        // After handwriting is initiated for the connected view, hide the hover icon.
        assertThat(icon1).isNull();

        MotionEvent hoverEvent2 = createStylusHoverEvent(sHwArea2.centerX(), sHwArea2.centerY());
        PointerIcon icon2 = mHandwritingInitiator.onResolvePointerIcon(mContext, hoverEvent2);
        // Now stylus is hovering on another editor, show the hover icon.
        assertThat(icon2.getType()).isEqualTo(PointerIcon.TYPE_HANDWRITING);

        // After the hover icon is displayed again, it will show hover icon for the connected view
        // again.
        PointerIcon icon3 = mHandwritingInitiator.onResolvePointerIcon(mContext, hoverEvent1);
        assertThat(icon3.getType()).isEqualTo(PointerIcon.TYPE_HANDWRITING);
    }

    @Test
    public void onResolvePointerIcon_afterHandwriting_hidePointerIconForDelegatorView() {
        // Set mTextView2 to be the delegate of mTestView1.
        mTestView2.setIsHandwritingDelegate(true);

        mTestView1.setHandwritingDelegatorCallback(
                () -> mHandwritingInitiator.onInputConnectionCreated(mTestView2));

        injectStylusEvent(mHandwritingInitiator, sHwArea1.centerX(), sHwArea1.centerY(),
                /* exceedsHWSlop */ true);
        // Prerequisite check, verify that handwriting started for delegateView.
        verify(mHandwritingInitiator, times(1)).tryAcceptStylusHandwritingDelegation(mTestView2);

        MotionEvent hoverEvent = createStylusHoverEvent(sHwArea2.centerX(), sHwArea2.centerY());
        PointerIcon icon = mHandwritingInitiator.onResolvePointerIcon(mContext, hoverEvent);
        // After handwriting is initiated for the connected view, hide the hover icon.
        assertThat(icon).isNull();
    }

    @Test
    public void onResolvePointerIcon_showHoverIconAfterTap() {
        // Simulate the case where sTestView1 is focused.
        mHandwritingInitiator.onInputConnectionCreated(mTestView1);
        injectStylusEvent(mHandwritingInitiator, sHwArea1.centerX(), sHwArea1.centerY(),
                /* exceedsHWSlop */ true);
        // Verify that handwriting started for sTestView1.
        verify(mHandwritingInitiator, times(1)).startHandwriting(mTestView1);

        MotionEvent hoverEvent1 = createStylusHoverEvent(sHwArea1.centerX(), sHwArea1.centerY());
        PointerIcon icon1 = mHandwritingInitiator.onResolvePointerIcon(mContext, hoverEvent1);
        // After handwriting is initiated for the connected view, hide the hover icon.
        assertThat(icon1).isNull();

        // When exceedsHwSlop is false, it simulates a tap.
        injectStylusEvent(mHandwritingInitiator, sHwArea1.centerX(), sHwArea1.centerY(),
                /* exceedsHWSlop */ false);

        PointerIcon icon2 = mHandwritingInitiator.onResolvePointerIcon(mContext, hoverEvent1);
        assertThat(icon2.getType()).isEqualTo(PointerIcon.TYPE_HANDWRITING);
    }

    @Test
    public void onResolvePointerIcon_showHoverIconAfterFocusChange() {
        // Simulate the case where sTestView1 is focused.
        mHandwritingInitiator.onInputConnectionCreated(mTestView1);
        injectStylusEvent(mHandwritingInitiator, sHwArea1.centerX(), sHwArea1.centerY(),
                /* exceedsHWSlop */ true);
        // Verify that handwriting started for sTestView1.
        verify(mHandwritingInitiator, times(1)).startHandwriting(mTestView1);

        MotionEvent hoverEvent1 = createStylusHoverEvent(sHwArea1.centerX(), sHwArea1.centerY());
        PointerIcon icon1 = mHandwritingInitiator.onResolvePointerIcon(mContext, hoverEvent1);
        // After handwriting is initiated for the connected view, hide the hover icon.
        assertThat(icon1).isNull();

        // Simulate that focus is switched to mTestView2 first and then switched back.
        mHandwritingInitiator.onInputConnectionCreated(mTestView2);
        mHandwritingInitiator.onInputConnectionCreated(mTestView1);

        PointerIcon icon2 = mHandwritingInitiator.onResolvePointerIcon(mContext, hoverEvent1);
        // After the change of focus, hover icon shows again.
        assertThat(icon2.getType()).isEqualTo(PointerIcon.TYPE_HANDWRITING);
    }

    @Test
    public void autoHandwriting_whenDisabled_wontStartHW() {
        View mockView = createView(sHwArea1, false /* autoHandwritingEnabled */,
                true /* isStylusHandwritingAvailable */);
        mHandwritingInitiator.onInputConnectionCreated(mockView);
        final int x1 = (sHwArea1.left + sHwArea1.right) / 2;
        final int y1 = (sHwArea1.top + sHwArea1.bottom) / 2;
        MotionEvent stylusEvent1 = createStylusEvent(ACTION_DOWN, x1, y1, 0);
        mHandwritingInitiator.onTouchEvent(stylusEvent1);

        final int x2 = x1 + mHandwritingSlop * 2;
        final int y2 = y1;

        MotionEvent stylusEvent2 = createStylusEvent(ACTION_MOVE, x2, y2, 0);
        mHandwritingInitiator.onTouchEvent(stylusEvent2);

        verify(mHandwritingInitiator, never()).startHandwriting(mTestView1);
    }

    @Test
    public void onInputConnectionCreated() {
        mHandwritingInitiator.onInputConnectionCreated(mTestView1);
        assertThat(mHandwritingInitiator.mConnectedView).isNotNull();
        assertThat(mHandwritingInitiator.mConnectedView.get()).isEqualTo(mTestView1);
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
        mHandwritingInitiator.onInputConnectionCreated(mTestView1);
        mHandwritingInitiator.onInputConnectionClosed(mTestView1);

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
        mHandwritingInitiator.onInputConnectionCreated(mTestView1);
        mHandwritingInitiator.onInputConnectionCreated(mTestView1);
        mHandwritingInitiator.onInputConnectionClosed(mTestView1);

        assertThat(mHandwritingInitiator.mConnectedView).isNotNull();
        assertThat(mHandwritingInitiator.mConnectedView.get()).isEqualTo(mTestView1);
    }

    @Test
    public void startHandwriting_hidesHint() {
        EditText editText =
                new EditText(InstrumentationRegistry.getInstrumentation().getTargetContext());
        editText.setHint("hint");
        editText.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        verifyEditTextDrawsText(editText, "hint");

        mHandwritingInitiator.onTouchEvent(createStylusEvent(ACTION_DOWN, 0, 0, 0));
        mHandwritingInitiator.startHandwriting(editText);

        verifyEditTextDrawsText(editText, null);
    }

    @Test
    public void startHandwriting_clearFocus_restoresHint() {
        EditText editText =
                new EditText(InstrumentationRegistry.getInstrumentation().getTargetContext());
        editText.setHint("hint");
        editText.setLayoutParams(new ViewGroup.LayoutParams(1024, 1024));
        editText.requestFocus();

        verifyEditTextDrawsText(editText, "hint");

        mHandwritingInitiator.onTouchEvent(createStylusEvent(ACTION_DOWN, 0, 0, 0));
        mHandwritingInitiator.startHandwriting(editText);

        verifyEditTextDrawsText(editText, null);

        editText.clearFocus();

        verifyEditTextDrawsText(editText, "hint");
    }

    @Test
    public void startHandwriting_setHint_restoresHint() {
        EditText editText =
                new EditText(InstrumentationRegistry.getInstrumentation().getTargetContext());
        editText.setHint("hint");
        editText.setLayoutParams(new ViewGroup.LayoutParams(1024, 1024));

        verifyEditTextDrawsText(editText, "hint");

        mHandwritingInitiator.onTouchEvent(createStylusEvent(ACTION_DOWN, 0, 0, 0));
        mHandwritingInitiator.startHandwriting(editText);

        verifyEditTextDrawsText(editText, null);

        editText.setHint("new hint");

        verifyEditTextDrawsText(editText, "new hint");
    }

    @Test
    public void startHandwriting_setText_restoresHint() {
        EditText editText =
                new EditText(InstrumentationRegistry.getInstrumentation().getTargetContext());
        editText.setHint("hint");
        editText.setLayoutParams(new ViewGroup.LayoutParams(1024, 1024));

        verifyEditTextDrawsText(editText, "hint");

        mHandwritingInitiator.onTouchEvent(createStylusEvent(ACTION_DOWN, 0, 0, 0));
        mHandwritingInitiator.startHandwriting(editText);

        verifyEditTextDrawsText(editText, null);

        editText.setText("a");
        editText.setText("");

        verifyEditTextDrawsText(editText, "hint");
    }

    private void verifyEditTextDrawsText(EditText editText, String text) {
        editText.measure(
                View.MeasureSpec.makeMeasureSpec(1024, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(1024, View.MeasureSpec.AT_MOST));
        Canvas canvas = prepareMockCanvas(editText);
        editText.draw(canvas);
        if (text != null) {
            ArgumentCaptor<CharSequence> textCaptor = ArgumentCaptor.forClass(CharSequence.class);
            verify(canvas).drawText(
                    textCaptor.capture(), anyInt(), anyInt(), anyFloat(), anyFloat(), any());
            assertThat(textCaptor.getValue().toString()).isEqualTo(text);
        } else {
            verify(canvas, never()).drawText(
                    any(CharSequence.class), anyInt(), anyInt(), anyFloat(), anyFloat(), any());
        }
    }

    private Canvas prepareMockCanvas(View view) {
        Canvas canvas = mock(Canvas.class);
        when(canvas.getClipBounds(any())).thenAnswer(invocation -> {
            Rect outRect = invocation.getArgument(0);
            outRect.top = 0;
            outRect.left = 0;
            outRect.right = view.getMeasuredWidth();
            outRect.bottom = view.getMeasuredHeight();
            return true;
        });
        return canvas;
    }

    /**
     * Inject {@link MotionEvent}s to the {@link HandwritingInitiator}.
     * @param x the x coordinate of the first {@link MotionEvent}.
     * @param y the y coordinate of the first {@link MotionEvent}.
     * @param exceedsHWSlop whether the injected {@link MotionEvent} movements exceed the
     *                     handwriting slop. If true, it simulates handwriting. Otherwise, it
     *                     simulates a tap/click,
     */
    private void injectStylusEvent(HandwritingInitiator handwritingInitiator, int x, int y,
            boolean exceedsHWSlop) {
        MotionEvent event1 = createStylusEvent(ACTION_DOWN, x, y, 0);

        if (exceedsHWSlop) {
            x += mHandwritingSlop * 2;
        } else {
            x += mHandwritingSlop / 2;
        }
        MotionEvent event2 = createStylusEvent(ACTION_MOVE, x, y, 0);
        MotionEvent event3 = createStylusEvent(ACTION_UP, x, y, 0);

        handwritingInitiator.onTouchEvent(event1);
        handwritingInitiator.onTouchEvent(event2);
        handwritingInitiator.onTouchEvent(event3);
    }

    private MotionEvent createStylusHoverEvent(int x, int y) {
        return createStylusEvent(ACTION_HOVER_MOVE, x, y, /* eventTime */ 0);
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
                InputDevice.SOURCE_STYLUS, 0 /* flags */);
    }
}
