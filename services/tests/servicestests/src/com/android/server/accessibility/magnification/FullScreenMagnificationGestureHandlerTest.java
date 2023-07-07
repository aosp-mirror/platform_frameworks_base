/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.accessibility.magnification;

import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_MOVE;
import static android.view.MotionEvent.ACTION_POINTER_DOWN;
import static android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT;
import static android.view.MotionEvent.ACTION_POINTER_UP;
import static android.view.MotionEvent.ACTION_UP;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.server.testutils.TestUtils.strictMock;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.graphics.PointF;
import android.os.Handler;
import android.os.Message;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.testing.TestableContext;
import android.util.DebugUtils;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.ConcurrentUtils;
import com.android.server.accessibility.AccessibilityManagerService;
import com.android.server.accessibility.AccessibilityTraceManager;
import com.android.server.accessibility.EventStreamTransformation;
import com.android.server.accessibility.magnification.FullScreenMagnificationController.MagnificationInfoChangedCallback;
import com.android.server.testutils.OffsettableClock;
import com.android.server.testutils.TestHandler;
import com.android.server.wm.WindowManagerInternal;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Tests the state transitions of {@link FullScreenMagnificationGestureHandler}
 *
 * Here's a dot graph describing the transitions being tested:
 * {@code
 *      digraph {
 *          IDLE -> SHORTCUT_TRIGGERED [label="a11y\nbtn"]
 *          IDLE -> DOUBLE_TAP [label="2tap"]
 *          DOUBLE_TAP -> IDLE [label="timeout"]
 *          DOUBLE_TAP -> ACTIVATED [label="tap"]
 *          DOUBLE_TAP -> NON_ACTIVATED_ZOOMED_TMP [label="hold"]
 *          NON_ACTIVATED_ZOOMED_TMP -> IDLE [label="release"]
 *          SHORTCUT_TRIGGERED -> IDLE [label="a11y\nbtn"]
 *          SHORTCUT_TRIGGERED -> ACTIVATED [label="tap"]
 *          SHORTCUT_TRIGGERED -> SHORTCUT_TRIGGERED_ZOOMED_TMP [label="hold"]
 *          SHORTCUT_TRIGGERED -> PANNING [label="2hold]
 *          if always-on enabled:
 *              SHORTCUT_TRIGGERED_ZOOMED_TMP -> ACTIVATED [label="release"]
 *          else:
 *              SHORTCUT_TRIGGERED_ZOOMED_TMP -> IDLE [label="release"]
 *          ACTIVATED -> ACTIVATED_DOUBLE_TAP [label="2tap"]
 *          ACTIVATED -> IDLE [label="a11y\nbtn"]
 *          ACTIVATED -> PANNING [label="2hold"]
 *          ACTIVATED_DOUBLE_TAP -> ACTIVATED [label="timeout"]
 *          ACTIVATED_DOUBLE_TAP -> ACTIVATED_ZOOMED_TMP [label="hold"]
 *          ACTIVATED_DOUBLE_TAP -> IDLE [label="tap"]
 *          ACTIVATED_ZOOMED_TMP -> ACTIVATED [label="release"]
 *          PANNING -> ACTIVATED [label="release"]
 *          PANNING -> PANNING_SCALING [label="pinch"]
 *          PANNING_SCALING -> ACTIVATED [label="release"]
 *      }
 * }
 */
@RunWith(AndroidJUnit4.class)
public class FullScreenMagnificationGestureHandlerTest {

    public static final int STATE_IDLE = 1;
    public static final int STATE_ACTIVATED = 2;
    public static final int STATE_2TAPS = 3;
    public static final int STATE_ACTIVATED_2TAPS = 4;
    public static final int STATE_SHORTCUT_TRIGGERED = 5;
    public static final int STATE_NON_ACTIVATED_ZOOMED_TMP = 6;
    public static final int STATE_ACTIVATED_ZOOMED_TMP = 7;
    public static final int STATE_SHORTCUT_TRIGGERED_ZOOMED_TMP = 8;
    public static final int STATE_PANNING = 9;
    public static final int STATE_SCALING_AND_PANNING = 10;

    public static final int FIRST_STATE = STATE_IDLE;
    public static final int LAST_STATE = STATE_SCALING_AND_PANNING;

    // Co-prime x and y, to potentially catch x-y-swapped errors
    public static final float DEFAULT_X = 301;
    public static final float DEFAULT_Y = 299;
    public static final PointF DEFAULT_POINT = new PointF(DEFAULT_X, DEFAULT_Y);

    private static final int DISPLAY_0 = 0;

    FullScreenMagnificationController mFullScreenMagnificationController;
    @Mock
    MagnificationGestureHandler.Callback mMockCallback;
    @Mock
    MagnificationInfoChangedCallback mMagnificationInfoChangedCallback;
    @Mock
    WindowMagnificationPromptController mWindowMagnificationPromptController;
    @Mock
    AccessibilityManagerService mMockAccessibilityManagerService;
    @Mock
    AccessibilityTraceManager mMockTraceManager;

    @Rule
    public final TestableContext mContext = new TestableContext(getInstrumentation().getContext());

    private OffsettableClock mClock;
    private FullScreenMagnificationGestureHandler mMgh;
    private TestHandler mHandler;

    private long mLastDownTime = Integer.MIN_VALUE;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        final FullScreenMagnificationController.ControllerContext mockController =
                mock(FullScreenMagnificationController.ControllerContext.class);
        final WindowManagerInternal mockWindowManager = mock(WindowManagerInternal.class);
        when(mockController.getContext()).thenReturn(mContext);
        when(mockController.getTraceManager()).thenReturn(mMockTraceManager);
        when(mockController.getWindowManager()).thenReturn(mockWindowManager);
        when(mockController.getHandler()).thenReturn(new Handler(mContext.getMainLooper()));
        when(mockController.newValueAnimator()).thenReturn(new ValueAnimator());
        when(mockController.getAnimationDuration()).thenReturn(1000L);
        when(mockWindowManager.setMagnificationCallbacks(eq(DISPLAY_0), any())).thenReturn(true);
        mFullScreenMagnificationController = new FullScreenMagnificationController(
                mockController,
                new Object(),
                mMagnificationInfoChangedCallback,
                new MagnificationScaleProvider(mContext),
                () -> null,
                ConcurrentUtils.DIRECT_EXECUTOR) {
            @Override
            public boolean magnificationRegionContains(int displayId, float x, float y) {
                return true;
            }
        };
        mFullScreenMagnificationController.register(DISPLAY_0);
        mFullScreenMagnificationController.setAlwaysOnMagnificationEnabled(true);
        mClock = new OffsettableClock.Stopped();

        boolean detectTripleTap = true;
        boolean detectShortcutTrigger = true;
        mMgh = newInstance(detectTripleTap, detectShortcutTrigger);
    }

    @After
    public void tearDown() {
        mMgh.onDestroy();
        mFullScreenMagnificationController.unregister(DISPLAY_0);
        verify(mWindowMagnificationPromptController).onDestroy();
    }

    @NonNull
    private FullScreenMagnificationGestureHandler newInstance(boolean detectTripleTap,
            boolean detectShortcutTrigger) {
        FullScreenMagnificationGestureHandler h = new FullScreenMagnificationGestureHandler(
                mContext, mFullScreenMagnificationController, mMockTraceManager, mMockCallback,
                detectTripleTap, detectShortcutTrigger,
                mWindowMagnificationPromptController, DISPLAY_0);
        mHandler = new TestHandler(h.mDetectingState, mClock) {
            @Override
            protected String messageToString(Message m) {
                return DebugUtils.valueToString(
                        FullScreenMagnificationGestureHandler.DetectingState.class, "MESSAGE_",
                        m.what);
            }
        };
        h.mDetectingState.mHandler = mHandler;
        h.setNext(strictMock(EventStreamTransformation.class));
        return h;
    }

    @Test
    public void testInitialState_isIdle() {
        assertIn(STATE_IDLE);
    }

    /**
     * Covers paths to get to and back between each state and {@link #STATE_IDLE}
     * This navigates between states using "canonical" paths, specified in
     * {@link #goFromStateIdleTo} (for traversing away from {@link #STATE_IDLE}) and
     * {@link #returnToNormalFrom} (for navigating back to {@link #STATE_IDLE})
     */
    @Test
    public void testEachState_isReachableAndRecoverable() {
        forEachState(state -> {
            goFromStateIdleTo(state);
            assertIn(state);

            returnToNormalFrom(state);
            try {
                assertIn(STATE_IDLE);
            } catch (AssertionError e) {
                throw new AssertionError("Failed while testing state " + stateToString(state), e);
            }
        });
    }

    @Test
    public void testStates_areMutuallyExclusive() {
        forEachState(state1 -> {
            forEachState(state2 -> {
                if (state1 < state2) {
                    goFromStateIdleTo(state1);
                    try {
                        assertIn(state2);
                        fail("State " + stateToString(state1) + " also implies state "
                                + stateToString(state2) + stateDump());
                    } catch (AssertionError e) {
                        // expected
                        returnToNormalFrom(state1);
                    }
                }
            });
        });
    }

    @Test
    public void testTransitionToDelegatingStateAndClear_preservesShortcutTriggeredState() {
        mMgh.mDetectingState.transitionToDelegatingStateAndClear();
        assertFalse(mMgh.mDetectingState.mShortcutTriggered);

        goFromStateIdleTo(STATE_SHORTCUT_TRIGGERED);
        mMgh.mDetectingState.transitionToDelegatingStateAndClear();
        assertTrue(mMgh.mDetectingState.mShortcutTriggered);
    }

    /**
     * Covers edges of the graph not covered by "canonical" transitions specified in
     * {@link #goFromStateIdleTo} and {@link #returnToNormalFrom}
     */
    @SuppressWarnings("Convert2MethodRef")
    @Test
    public void testAlternativeTransitions_areWorking() {
        // A11y button followed by a tap&hold turns temporary "viewport dragging" zoom in
        assertTransition(STATE_SHORTCUT_TRIGGERED, () -> {
            send(downEvent());
            fastForward1sec();
        }, STATE_SHORTCUT_TRIGGERED_ZOOMED_TMP);

        // A11y button followed by a tap turns magnifier on
        assertTransition(STATE_SHORTCUT_TRIGGERED, () -> tap(), STATE_ACTIVATED);

        // A11y button pressed second time negates the 1st press
        assertTransition(STATE_SHORTCUT_TRIGGERED, () -> triggerShortcut(), STATE_IDLE);

        // A11y button turns magnifier off
        assertTransition(STATE_ACTIVATED, () -> triggerShortcut(), STATE_IDLE);

        // Double tap times out while activated
        assertTransition(STATE_ACTIVATED_2TAPS, () -> {
            allowEventDelegation();
            fastForward1sec();
        }, STATE_ACTIVATED);

        // tap+tap+swipe doesn't get delegated
        assertTransition(STATE_2TAPS, () -> swipe(), STATE_IDLE);

        // tap+tap+swipe&hold initiates temporary viewport dragging zoom in immediately
        assertTransition(STATE_2TAPS, () -> swipeAndHold(), STATE_NON_ACTIVATED_ZOOMED_TMP);

        // release when activated temporary zoom in back to activated
        assertTransition(STATE_ACTIVATED_ZOOMED_TMP, () -> send(upEvent()), STATE_ACTIVATED);
    }

    @Test
    public void testRelease_shortcutTriggeredZoomedTmp_alwaysOnNotEnabled_shouldInIdle() {
        mFullScreenMagnificationController.setAlwaysOnMagnificationEnabled(false);
        goFromStateIdleTo(STATE_SHORTCUT_TRIGGERED_ZOOMED_TMP);
        send(upEvent());

        assertIn(STATE_IDLE);
    }

    @Test
    public void testRelease_shortcutTriggeredZoomedTmp_alwaysOnEnabled_shouldInActivated() {
        mFullScreenMagnificationController.setAlwaysOnMagnificationEnabled(true);
        goFromStateIdleTo(STATE_SHORTCUT_TRIGGERED_ZOOMED_TMP);
        send(upEvent());

        assertIn(STATE_ACTIVATED);
        assertTrue(!isZoomed());

        returnToNormalFrom(STATE_ACTIVATED);
    }

    @Test
    public void testNonTransitions_dontChangeState() {
        // ACTION_POINTER_DOWN triggers event delegation if not magnifying
        assertStaysIn(STATE_IDLE, () -> {
            allowEventDelegation();
            send(downEvent());
            send(pointerEvent(ACTION_POINTER_DOWN, DEFAULT_X * 2, DEFAULT_Y));
        });

        // Long tap breaks the triple-tap detection sequence
        Runnable tapAndLongTap = () -> {
            allowEventDelegation();
            tap();
            longTap();
        };
        assertStaysIn(STATE_IDLE, tapAndLongTap);
        assertStaysIn(STATE_ACTIVATED, tapAndLongTap);

        // Triple tap with delays in between doesn't count
        Runnable slow3tap = () -> {
            tap();
            fastForward1sec();
            tap();
            fastForward1sec();
            tap();
        };
        assertStaysIn(STATE_IDLE, slow3tap);
        assertStaysIn(STATE_ACTIVATED, slow3tap);
    }

    @Test
    public void testDisablingTripleTap_removesInputLag() {
        mMgh = newInstance(/* detect3tap */ false, /* detectShortcut */ true);
        goFromStateIdleTo(STATE_IDLE);
        allowEventDelegation();
        tap();
        // no fast forward
        verify(mMgh.getNext(), times(2)).onMotionEvent(any(), any(), anyInt());
    }

    @Test
    public void testTripleTapAndHold_zoomsImmediately() {
        assertZoomsImmediatelyOnSwipeFrom(STATE_2TAPS, STATE_NON_ACTIVATED_ZOOMED_TMP);
        assertZoomsImmediatelyOnSwipeFrom(STATE_SHORTCUT_TRIGGERED,
                STATE_SHORTCUT_TRIGGERED_ZOOMED_TMP);
        assertZoomsImmediatelyOnSwipeFrom(STATE_ACTIVATED_2TAPS, STATE_ACTIVATED_ZOOMED_TMP);
    }

    @Test
    public void testMultiTap_outOfDistanceSlop_shouldInIdle() {
        // All delay motion events should be sent, if multi-tap with out of distance slop.
        // STATE_IDLE will check if tapCount() < 2.
        allowEventDelegation();
        assertStaysIn(STATE_IDLE, () -> {
            tap();
            tap(DEFAULT_X * 2, DEFAULT_Y * 2);
        });
        assertStaysIn(STATE_IDLE, () -> {
            tap();
            tap(DEFAULT_X * 2, DEFAULT_Y * 2);
            tap();
            tap(DEFAULT_X * 2, DEFAULT_Y * 2);
            tap();
        });
    }

    @Test
    public void testTwoFingersOneTap_activatedState_dispatchMotionEvents() {
        goFromStateIdleTo(STATE_ACTIVATED);
        final EventCaptor eventCaptor = new EventCaptor();
        mMgh.setNext(eventCaptor);

        send(downEvent());
        send(pointerEvent(ACTION_POINTER_DOWN, DEFAULT_X * 2, DEFAULT_Y));
        send(pointerEvent(ACTION_POINTER_UP, DEFAULT_X * 2, DEFAULT_Y));
        send(upEvent());

        assertIn(STATE_ACTIVATED);
        final List<Integer> expectedActions = new ArrayList();
        expectedActions.add(Integer.valueOf(ACTION_DOWN));
        expectedActions.add(Integer.valueOf(ACTION_POINTER_DOWN));
        expectedActions.add(Integer.valueOf(ACTION_POINTER_UP));
        expectedActions.add(Integer.valueOf(ACTION_UP));
        assertActionsInOrder(eventCaptor.mEvents, expectedActions);

        returnToNormalFrom(STATE_ACTIVATED);
    }

    @Test
    public void testMagnifierDeactivates_shortcutTriggeredState_returnToIdleState() {
        goFromStateIdleTo(STATE_SHORTCUT_TRIGGERED);

        mFullScreenMagnificationController.reset(DISPLAY_0, /* animate= */ false);

        assertIn(STATE_IDLE);
    }

    @Test
    public void testThreeFingersOneTap_activatedState_dispatchMotionEvents() {
        goFromStateIdleTo(STATE_ACTIVATED);
        final EventCaptor eventCaptor = new EventCaptor();
        mMgh.setNext(eventCaptor);
        PointF pointer1 = DEFAULT_POINT;
        PointF pointer2 = new PointF(DEFAULT_X * 1.5f, DEFAULT_Y);
        PointF pointer3 = new PointF(DEFAULT_X * 2, DEFAULT_Y);

        send(downEvent());
        send(pointerEvent(ACTION_POINTER_DOWN, new PointF[] {pointer1, pointer2}, 1));
        send(pointerEvent(ACTION_POINTER_DOWN, new PointF[] {pointer1, pointer2, pointer3}, 2));
        send(pointerEvent(ACTION_POINTER_UP, new PointF[] {pointer1, pointer2, pointer3}, 2));
        send(pointerEvent(ACTION_POINTER_UP, new PointF[] {pointer1, pointer2, pointer3}, 2));
        send(upEvent());

        assertIn(STATE_ACTIVATED);
        final List<Integer> expectedActions = new ArrayList();
        expectedActions.add(Integer.valueOf(ACTION_DOWN));
        expectedActions.add(Integer.valueOf(ACTION_POINTER_DOWN));
        expectedActions.add(Integer.valueOf(ACTION_POINTER_DOWN));
        expectedActions.add(Integer.valueOf(ACTION_POINTER_UP));
        expectedActions.add(Integer.valueOf(ACTION_POINTER_UP));
        expectedActions.add(Integer.valueOf(ACTION_UP));
        assertActionsInOrder(eventCaptor.mEvents, expectedActions);

        returnToNormalFrom(STATE_ACTIVATED);
    }

    @Test
    public void testFirstFingerSwipe_twoPointerDownAndActivatedState_panningState() {
        goFromStateIdleTo(STATE_ACTIVATED);
        PointF pointer1 = DEFAULT_POINT;
        PointF pointer2 = new PointF(DEFAULT_X * 1.5f, DEFAULT_Y);

        send(downEvent());
        send(pointerEvent(ACTION_POINTER_DOWN, new PointF[] {pointer1, pointer2}, 1));
        //The minimum movement to transit to panningState.
        final float sWipeMinDistance = ViewConfiguration.get(mContext).getScaledTouchSlop();
        pointer1.offset(sWipeMinDistance + 1, 0);
        send(pointerEvent(ACTION_MOVE, new PointF[] {pointer1, pointer2}, 0));
        assertIn(STATE_PANNING);

        returnToNormalFrom(STATE_PANNING);
    }

    @Test
    public void testSecondFingerSwipe_twoPointerDownAndActivatedState_panningState() {
        goFromStateIdleTo(STATE_ACTIVATED);
        PointF pointer1 = DEFAULT_POINT;
        PointF pointer2 = new PointF(DEFAULT_X * 1.5f, DEFAULT_Y);

        send(downEvent());
        send(pointerEvent(ACTION_POINTER_DOWN, new PointF[] {pointer1, pointer2}, 1));
        //The minimum movement to transit to panningState.
        final float sWipeMinDistance = ViewConfiguration.get(mContext).getScaledTouchSlop();
        pointer2.offset(sWipeMinDistance + 1, 0);
        send(pointerEvent(ACTION_MOVE, new PointF[] {pointer1, pointer2}, 1));
        assertIn(STATE_PANNING);

        returnToNormalFrom(STATE_PANNING);
    }

    @Test
    public void testSecondFingerSwipe_twoPointerDownAndShortcutTriggeredState_panningState() {
        goFromStateIdleTo(STATE_SHORTCUT_TRIGGERED);
        PointF pointer1 = DEFAULT_POINT;
        PointF pointer2 = new PointF(DEFAULT_X * 1.5f, DEFAULT_Y);

        send(downEvent());
        send(pointerEvent(ACTION_POINTER_DOWN, new PointF[] {pointer1, pointer2}, 1));
        //The minimum movement to transit to panningState.
        final float sWipeMinDistance = ViewConfiguration.get(mContext).getScaledTouchSlop();
        pointer2.offset(sWipeMinDistance + 1, 0);
        send(pointerEvent(ACTION_MOVE, new PointF[] {pointer1, pointer2}, 1));
        assertIn(STATE_PANNING);

        returnToNormalFrom(STATE_PANNING);
    }

    @Test
    public void testActivatedWithTripleTap_invokeShowWindowPromptAction() {
        goFromStateIdleTo(STATE_ACTIVATED);

        verify(mWindowMagnificationPromptController).showNotificationIfNeeded();
    }

    @Test
    public void testShortcutTriggered_invokeShowWindowPromptAction() {
        goFromStateIdleTo(STATE_SHORTCUT_TRIGGERED);

        verify(mWindowMagnificationPromptController).showNotificationIfNeeded();
    }

    @Test
    public void testTransitToPanningState_scaleDifferenceOverThreshold_startDetecting() {
        final float scale = 2.0f;
        final float threshold = FullScreenMagnificationGestureHandler.PanningScalingState
                .CHECK_DETECTING_PASS_PERSISTED_SCALE_THRESHOLD;
        final float persistedScale = (1.0f + threshold) * scale + 1.0f;
        mFullScreenMagnificationController.setScale(DISPLAY_0, persistedScale, DEFAULT_X,
                DEFAULT_Y, /* animate= */ false,
                AccessibilityManagerService.MAGNIFICATION_GESTURE_HANDLER_ID);
        mFullScreenMagnificationController.persistScale(DISPLAY_0);
        mFullScreenMagnificationController.setScale(DISPLAY_0, scale, DEFAULT_X,
                DEFAULT_Y, /* animate= */ false,
                AccessibilityManagerService.MAGNIFICATION_GESTURE_HANDLER_ID);

        mMgh.transitionTo(mMgh.mPanningScalingState);

        assertTrue(mMgh.mPanningScalingState.mDetectingPassPersistedScale);

        mMgh.clearAndTransitionToStateDetecting();
        mFullScreenMagnificationController.reset(DISPLAY_0, /* animate= */ false);
    }

    @Test
    public void testTransitToPanningState_scaleDifferenceLessThanThreshold_doNotDetect() {
        final float scale = 2.0f;
        final float threshold = FullScreenMagnificationGestureHandler.PanningScalingState
                .CHECK_DETECTING_PASS_PERSISTED_SCALE_THRESHOLD;
        final float persistedScale = (1.0f + threshold) * scale - 0.1f;
        mFullScreenMagnificationController.setScale(DISPLAY_0, persistedScale, DEFAULT_X,
                DEFAULT_Y, /* animate= */ false,
                AccessibilityManagerService.MAGNIFICATION_GESTURE_HANDLER_ID);
        mFullScreenMagnificationController.persistScale(DISPLAY_0);
        mFullScreenMagnificationController.setScale(DISPLAY_0, scale, DEFAULT_X,
                DEFAULT_Y, /* animate= */ false,
                AccessibilityManagerService.MAGNIFICATION_GESTURE_HANDLER_ID);

        mMgh.transitionTo(mMgh.mPanningScalingState);

        assertFalse(mMgh.mPanningScalingState.mDetectingPassPersistedScale);

        mMgh.clearAndTransitionToStateDetecting();
        mFullScreenMagnificationController.reset(DISPLAY_0, /* animate= */ false);
    }

    @Test
    public void testPanningScaleToPersistedScale_detecting_vibrateAndClear() {
        Vibrator vibrator = mock(Vibrator.class);
        mContext.addMockSystemService(Vibrator.class, vibrator);

        mMgh.mPanningScalingState.mDetectingPassPersistedScale = true;

        final float persistedScale =
                mFullScreenMagnificationController.getPersistedScale(DISPLAY_0);

        mMgh.transitionTo(mMgh.mPanningScalingState);
        mMgh.mPanningScalingState.setScaleAndClearIfNeeded(persistedScale, DEFAULT_X, DEFAULT_Y);

        verify(vibrator).vibrate(any(VibrationEffect.class));
        assertFalse(mMgh.mPanningScalingState.mScaling);

        mMgh.clearAndTransitionToStateDetecting();
        mFullScreenMagnificationController.reset(DISPLAY_0, /* animate= */ false);
    }

    @Test
    public void testPanningScaleOverThreshold_notDetecting_startDetecting() {
        final float persistedScale =
                mFullScreenMagnificationController.getPersistedScale(DISPLAY_0);

        mFullScreenMagnificationController.setScale(DISPLAY_0, persistedScale, DEFAULT_X,
                DEFAULT_Y, /* animate= */ false,
                AccessibilityManagerService.MAGNIFICATION_GESTURE_HANDLER_ID);
        mMgh.transitionTo(mMgh.mPanningScalingState);

        final float threshold = FullScreenMagnificationGestureHandler.PanningScalingState
                .CHECK_DETECTING_PASS_PERSISTED_SCALE_THRESHOLD;
        final float scale = (1.0f + threshold) * persistedScale + 1.0f;
        mMgh.mPanningScalingState.setScaleAndClearIfNeeded(scale, DEFAULT_X, DEFAULT_Y);

        assertTrue(mMgh.mPanningScalingState.mDetectingPassPersistedScale);

        mMgh.clearAndTransitionToStateDetecting();
        mFullScreenMagnificationController.reset(DISPLAY_0, /* animate= */ false);
    }

    private void assertActionsInOrder(List<MotionEvent> actualEvents,
            List<Integer> expectedActions) {
        assertTrue(actualEvents.size() == expectedActions.size());
        final int size = actualEvents.size();
        for (int i = 0; i < size; i++) {
            final int expectedAction = expectedActions.get(i);
            final int actualAction = actualEvents.get(i).getActionMasked();
            assertTrue(String.format(
                    "%dth action %s is not matched, actual events : %s, ", i,
                    MotionEvent.actionToString(expectedAction), actualEvents),
                    actualAction == expectedAction);
        }
    }

    private void assertZoomsImmediatelyOnSwipeFrom(int fromState, int toState) {
        goFromStateIdleTo(fromState);
        swipeAndHold();
        assertIn(toState);
        returnToNormalFrom(toState);
    }

    private void assertTransition(int fromState, Runnable transitionAction, int toState) {
        goFromStateIdleTo(fromState);
        transitionAction.run();
        assertIn(toState);
        returnToNormalFrom(toState);
    }

    private void assertStaysIn(int state, Runnable action) {
        assertTransition(state, action, state);
    }

    private void forEachState(IntConsumer action) {
        for (int state = FIRST_STATE; state <= LAST_STATE; state++) {
            action.accept(state);
        }
    }

    private void allowEventDelegation() {
        doNothing().when(mMgh.getNext()).onMotionEvent(any(), any(), anyInt());
    }

    private void fastForward1sec() {
        fastForward(1000);
    }

    private void fastForward(int ms) {
        mClock.fastForward(ms);
        mHandler.timeAdvance();
    }

    /**
     * Asserts that {@link #mMgh the handler} is in the given {@code state}
     */
    private void assertIn(int state) {
        switch (state) {

            // Asserts on separate lines for accurate stack traces

            case STATE_IDLE: {
                check(tapCount() < 2, state);
                check(!mMgh.mDetectingState.mShortcutTriggered, state);
                check(!isActivated(), state);
                check(!isZoomed(), state);
            } break;
            case STATE_ACTIVATED: {
                check(isActivated(), state);
                check(tapCount() < 2, state);
            } break;
            case STATE_2TAPS: {
                check(!isActivated(), state);
                check(!isZoomed(), state);
                check(tapCount() == 2, state);
            } break;
            case STATE_ACTIVATED_2TAPS: {
                check(isActivated(), state);
                check(isZoomed(), state);
                check(tapCount() == 2, state);
            } break;
            case STATE_NON_ACTIVATED_ZOOMED_TMP: {
                check(isActivated(), state);
                check(isZoomed(), state);
                check(mMgh.mCurrentState == mMgh.mViewportDraggingState,
                        state);
                check(Float.isNaN(mMgh.mViewportDraggingState.mScaleToRecoverAfterDraggingEnd),
                        state);
            } break;
            case STATE_ACTIVATED_ZOOMED_TMP: {
                check(isActivated(), state);
                check(isZoomed(), state);
                check(mMgh.mCurrentState == mMgh.mViewportDraggingState,
                        state);
                check(mMgh.mViewportDraggingState.mScaleToRecoverAfterDraggingEnd >= 1.0f,
                        state);
            } break;
            case STATE_SHORTCUT_TRIGGERED_ZOOMED_TMP: {
                check(isActivated(), state);
                check(isZoomed(), state);
                check(mMgh.mCurrentState == mMgh.mViewportDraggingState,
                        state);
                if (mFullScreenMagnificationController.isAlwaysOnMagnificationEnabled()) {
                    check(mMgh.mViewportDraggingState.mScaleToRecoverAfterDraggingEnd >= 1.0f,
                            state);
                } else {
                    check(Float.isNaN(mMgh.mViewportDraggingState.mScaleToRecoverAfterDraggingEnd),
                            state);
                }
            } break;
            case STATE_SHORTCUT_TRIGGERED: {
                check(mMgh.mDetectingState.mShortcutTriggered, state);
                check(isActivated(), state);
                check(!isZoomed(), state);
            } break;
            case STATE_PANNING: {
                check(isActivated(), state);
                check(mMgh.mCurrentState == mMgh.mPanningScalingState,
                        state);
                check(!mMgh.mPanningScalingState.mScaling, state);
            } break;
            case STATE_SCALING_AND_PANNING: {
                check(isActivated(), state);
                check(mMgh.mCurrentState == mMgh.mPanningScalingState,
                        state);
                check(mMgh.mPanningScalingState.mScaling, state);
            } break;
            default: throw new IllegalArgumentException("Illegal state: " + state);
        }
    }

    /**
     * Defines a "canonical" path from {@link #STATE_IDLE} to {@code state}
     */
    private void goFromStateIdleTo(int state) {
        try {
            switch (state) {
                case STATE_IDLE: {
                    mMgh.clearAndTransitionToStateDetecting();
                } break;
                case STATE_2TAPS: {
                    goFromStateIdleTo(STATE_IDLE);
                    tap();
                    tap();
                } break;
                case STATE_ACTIVATED: {
                    if (mMgh.mDetectTripleTap) {
                        goFromStateIdleTo(STATE_2TAPS);
                        tap();
                    } else {
                        goFromStateIdleTo(STATE_SHORTCUT_TRIGGERED);
                        tap();
                    }
                } break;
                case STATE_ACTIVATED_2TAPS: {
                    goFromStateIdleTo(STATE_ACTIVATED);
                    tap();
                    tap();
                } break;
                case STATE_NON_ACTIVATED_ZOOMED_TMP: {
                    goFromStateIdleTo(STATE_2TAPS);
                    send(downEvent());
                    fastForward1sec();
                } break;
                case STATE_ACTIVATED_ZOOMED_TMP: {
                    goFromStateIdleTo(STATE_ACTIVATED_2TAPS);
                    send(downEvent());
                    fastForward1sec();
                } break;
                case STATE_SHORTCUT_TRIGGERED_ZOOMED_TMP: {
                    goFromStateIdleTo(STATE_SHORTCUT_TRIGGERED);
                    send(downEvent());
                    fastForward1sec();
                } break;
                case STATE_SHORTCUT_TRIGGERED: {
                    goFromStateIdleTo(STATE_IDLE);
                    triggerShortcut();
                } break;
                case STATE_PANNING: {
                    goFromStateIdleTo(STATE_ACTIVATED);
                    send(downEvent());
                    send(pointerEvent(ACTION_POINTER_DOWN, DEFAULT_X * 2, DEFAULT_Y));
                    fastForward(ViewConfiguration.getTapTimeout());
                } break;
                case STATE_SCALING_AND_PANNING: {
                    goFromStateIdleTo(STATE_PANNING);
                    send(pointerEvent(ACTION_MOVE, DEFAULT_X * 2, DEFAULT_Y * 3));
                    send(pointerEvent(ACTION_MOVE, DEFAULT_X * 2, DEFAULT_Y * 4));
                    send(pointerEvent(ACTION_MOVE, DEFAULT_X * 2, DEFAULT_Y * 5));
                } break;
                default:
                    throw new IllegalArgumentException("Illegal state: " + state);
            }
        } catch (Throwable t) {
            throw new RuntimeException("Failed to go to state " + stateToString(state), t);
        }
    }

    /**
     * Defines a "canonical" path from {@code state} to {@link #STATE_IDLE}
     */
    private void returnToNormalFrom(int state) {
        switch (state) {
            case STATE_IDLE: {
                // no op
            } break;
            case STATE_2TAPS: {
                allowEventDelegation();
                fastForward1sec();
            } break;
            case STATE_ACTIVATED: {
                if (mMgh.mDetectTripleTap) {
                    tap();
                    tap();
                    returnToNormalFrom(STATE_ACTIVATED_2TAPS);
                } else {
                    triggerShortcut();
                }
            } break;
            case STATE_ACTIVATED_2TAPS: {
                tap();
            } break;
            case STATE_NON_ACTIVATED_ZOOMED_TMP: {
                send(upEvent());
            } break;
            case STATE_ACTIVATED_ZOOMED_TMP: {
                send(upEvent());
                returnToNormalFrom(STATE_ACTIVATED);
            } break;
            case STATE_SHORTCUT_TRIGGERED_ZOOMED_TMP: {
                send(upEvent());
                if (mFullScreenMagnificationController.isAlwaysOnMagnificationEnabled()) {
                    returnToNormalFrom(STATE_ACTIVATED);
                }
            } break;
            case STATE_SHORTCUT_TRIGGERED: {
                triggerShortcut();
            } break;
            case STATE_PANNING: {
                send(pointerEvent(ACTION_POINTER_UP, DEFAULT_X * 2, DEFAULT_Y));
                send(upEvent());
                returnToNormalFrom(STATE_ACTIVATED);
            } break;
            case STATE_SCALING_AND_PANNING: {
                returnToNormalFrom(STATE_PANNING);
            } break;
            default: throw new IllegalArgumentException("Illegal state: " + state);
        }
    }

    private void check(boolean condition, int expectedState) {
        if (!condition) {
            fail("Expected to be in state " + stateToString(expectedState) + stateDump());
        }
    }

    private boolean isActivated() {
        return mMgh.mFullScreenMagnificationController.isActivated(DISPLAY_0);
    }

    private boolean isZoomed() {
        return mMgh.mFullScreenMagnificationController.getScale(DISPLAY_0) > 1.0f;
    }

    private int tapCount() {
        return mMgh.mDetectingState.tapCount();
    }

    private static String stateToString(int state) {
        return DebugUtils.valueToString(FullScreenMagnificationGestureHandlerTest.class, "STATE_",
                state);
    }

    private void tap() {
        send(downEvent());
        send(upEvent());
    }

    private void tap(float x, float y) {
        send(downEvent(x, y));
        send(upEvent(x, y));
    }

    private void swipe() {
        swipeAndHold();
        send(upEvent());
    }

    private void swipeAndHold() {
        send(downEvent());
        send(moveEvent(DEFAULT_X * 2, DEFAULT_Y * 2));
    }

    private void longTap() {
        send(downEvent());
        fastForward(2000);
        send(upEvent());
    }

    private void triggerShortcut() {
        mMgh.notifyShortcutTriggered();
    }

    private void send(MotionEvent event) {
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            mMgh.onMotionEvent(event, event, /* policyFlags */ 0);
        } catch (Throwable t) {
            throw new RuntimeException("Exception while handling " + event, t);
        }
        fastForward(1);
    }

    private static MotionEvent fromTouchscreen(MotionEvent ev) {
        ev.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        return ev;
    }

    private MotionEvent moveEvent(float x, float y) {
        return fromTouchscreen(
                MotionEvent.obtain(mLastDownTime, mClock.now(), ACTION_MOVE, x, y, 0));
    }

    private MotionEvent downEvent() {
        return downEvent(DEFAULT_X, DEFAULT_Y);
    }

    private MotionEvent downEvent(float x, float y) {
        mLastDownTime = mClock.now();
        return fromTouchscreen(MotionEvent.obtain(mLastDownTime, mLastDownTime,
                ACTION_DOWN, x, y, 0));
    }

    private MotionEvent upEvent() {
        return upEvent(DEFAULT_X, DEFAULT_Y, mLastDownTime);
    }

    private MotionEvent upEvent(float x, float y) {
        return upEvent(x, y, mLastDownTime);
    }

    private MotionEvent upEvent(float x, float y, long downTime) {
        return fromTouchscreen(MotionEvent.obtain(downTime, mClock.now(),
                MotionEvent.ACTION_UP, x, y, 0));
    }


    private MotionEvent pointerEvent(int action, float x, float y) {
        return pointerEvent(action, new PointF[] {DEFAULT_POINT, new PointF(x, y)}, 1);
    }

    private MotionEvent pointerEvent(int action, PointF[] pointersPosition, int changedIndex) {
        final MotionEvent.PointerProperties[] PointerPropertiesArray =
                new MotionEvent.PointerProperties[pointersPosition.length];
        for (int i = 0; i < pointersPosition.length; i++) {
            MotionEvent.PointerProperties pointerProperties = new MotionEvent.PointerProperties();
            pointerProperties.id = i;
            pointerProperties.toolType = MotionEvent.TOOL_TYPE_FINGER;
            PointerPropertiesArray[i] = pointerProperties;
        }

        final MotionEvent.PointerCoords[] pointerCoordsArray =
                new MotionEvent.PointerCoords[pointersPosition.length];
        for (int i = 0; i < pointersPosition.length; i++) {
            MotionEvent.PointerCoords pointerCoords = new MotionEvent.PointerCoords();
            pointerCoords.x = pointersPosition[i].x;
            pointerCoords.y = pointersPosition[i].y;
            pointerCoordsArray[i] = pointerCoords;
        }

        action += (changedIndex << ACTION_POINTER_INDEX_SHIFT);

        return MotionEvent.obtain(
                /* downTime */ mClock.now(),
                /* eventTime */ mClock.now(),
                /* action */ action,
                /* pointerCount */ pointersPosition.length,
                /* pointerProperties */ PointerPropertiesArray,
                /* pointerCoords */ pointerCoordsArray,
                /* metaState */ 0,
                /* buttonState */ 0,
                /* xPrecision */ 1.0f,
                /* yPrecision */ 1.0f,
                /* deviceId */ 0,
                /* edgeFlags */ 0,
                /* source */ InputDevice.SOURCE_TOUCHSCREEN,
                /* flags */ 0);
    }


    private String stateDump() {
        return "\nCurrent state dump:\n" + mMgh + "\n" + mHandler.getPendingMessages();
    }

    private class EventCaptor implements EventStreamTransformation {
        List<MotionEvent> mEvents = new ArrayList<>();

        @Override
        public void onMotionEvent(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
            mEvents.add(event.copy());
        }

        @Override
        public void setNext(EventStreamTransformation next) {
        }

        @Override
        public EventStreamTransformation getNext() {
            return null;
        }
    }
}
