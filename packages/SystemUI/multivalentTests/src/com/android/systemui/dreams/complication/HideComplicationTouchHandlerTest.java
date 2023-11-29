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

package com.android.systemui.dreams.complication;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.Handler;
import android.view.MotionEvent;
import android.view.View;

import androidx.concurrent.futures.CallbackToFutureAdapter;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.complication.Complication;
import com.android.systemui.dreams.DreamOverlayStateController;
import com.android.systemui.dreams.touch.DreamTouchHandler;
import com.android.systemui.shared.system.InputChannelCompat;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.touch.TouchInsetManager;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class HideComplicationTouchHandlerTest extends SysuiTestCase {
    private static final int RESTORE_TIMEOUT = 1000;
    private static final int HIDE_DELAY = 500;

    @Mock
    Complication.VisibilityController mVisibilityController;

    @Mock
    TouchInsetManager mTouchInsetManager;

    @Mock
    StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;

    @Mock
    Handler mHandler;

    @Mock
    MotionEvent mMotionEvent;

    @Mock
    DreamTouchHandler.TouchSession mSession;

    @Mock
    DreamOverlayStateController mStateController;

    FakeSystemClock mClock;

    FakeExecutor mFakeExecutor;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mClock = new FakeSystemClock();
        mFakeExecutor = new FakeExecutor(mClock);
    }

    /**
     * Ensures no actions are taken when there multiple sessions.
     */
    @Test
    public void testSessionEndOnMultipleSessions() {
        final HideComplicationTouchHandler touchHandler = new HideComplicationTouchHandler(
                mVisibilityController,
                RESTORE_TIMEOUT,
                HIDE_DELAY,
                mTouchInsetManager,
                mStatusBarKeyguardViewManager,
                mFakeExecutor,
                mStateController);

        // Report multiple active sessions.
        when(mSession.getActiveSessionCount()).thenReturn(2);

        // Bouncer hidden.
        when(mStatusBarKeyguardViewManager.isBouncerShowing()).thenReturn(false);

        // Start session.
        touchHandler.onSessionStart(mSession);

        // Verify session end.
        verify(mSession).pop();

        mClock.advanceTime(HIDE_DELAY);

        // Verify no interaction with visibility controller.
        verify(mVisibilityController, never()).setVisibility(anyInt());
    }

    /**
     * Ensures no actions are taken when the bouncer is showing.
     */
    @Test
    public void testSessionEndWhenBouncerShowing() {
        final HideComplicationTouchHandler touchHandler = new HideComplicationTouchHandler(
                mVisibilityController,
                RESTORE_TIMEOUT,
                HIDE_DELAY,
                mTouchInsetManager,
                mStatusBarKeyguardViewManager,
                mFakeExecutor,
                mStateController);

        // Report one session.
        when(mSession.getActiveSessionCount()).thenReturn(1);

        // Bouncer is showing.
        when(mStatusBarKeyguardViewManager.isBouncerShowing()).thenReturn(true);

        // Start session.
        touchHandler.onSessionStart(mSession);

        // Verify session end.
        verify(mSession).pop();

        mClock.advanceTime(HIDE_DELAY);

        // Verify no interaction with visibility controller.
        verify(mVisibilityController, never()).setVisibility(anyInt());
    }

    /**
     * Ensures no actions are taken when there multiple sessions.
     */
    @Test
    public void testSessionEndWithTouchInInset() {
        final HideComplicationTouchHandler touchHandler = new HideComplicationTouchHandler(
                mVisibilityController,
                RESTORE_TIMEOUT,
                HIDE_DELAY,
                mTouchInsetManager,
                mStatusBarKeyguardViewManager,
                mFakeExecutor,
                mStateController);

        // Report one session
        when(mSession.getActiveSessionCount()).thenReturn(1);

        // Bouncer hidden.
        when(mStatusBarKeyguardViewManager.isBouncerShowing()).thenReturn(false);

        // Start session
        touchHandler.onSessionStart(mSession);

        // Capture input listener
        final ArgumentCaptor<InputChannelCompat.InputEventListener> inputEventListenerCaptor =
                ArgumentCaptor.forClass(InputChannelCompat.InputEventListener.class);
        verify(mSession).registerInputListener(inputEventListenerCaptor.capture());

        // Report touch within the inset
        when(mTouchInsetManager.checkWithinTouchRegion(anyInt(), anyInt())).thenReturn(
                CallbackToFutureAdapter.getFuture(completer -> {
                    completer.set(true);
                    return "testSessionEndWithTouchInInset";
                })
        );

        inputEventListenerCaptor.getValue().onInputEvent(mMotionEvent);
        mFakeExecutor.runAllReady();

        // Verify session ended.
        verify(mSession).pop();

        mClock.advanceTime(HIDE_DELAY);

        // Verify no interaction with visibility controller.
        verify(mVisibilityController, never()).setVisibility(anyInt());
    }

    /**
     * Make sure visibility changes are triggered from session events.
     */
    @Test
    public void testSessionLifecycle() {
        final HideComplicationTouchHandler touchHandler = new HideComplicationTouchHandler(
                mVisibilityController,
                RESTORE_TIMEOUT,
                HIDE_DELAY,
                mTouchInsetManager,
                mStatusBarKeyguardViewManager,
                mFakeExecutor,
                mStateController);

        // Report one session
        when(mSession.getActiveSessionCount()).thenReturn(1);

        // Bouncer hidden.
        when(mStatusBarKeyguardViewManager.isBouncerShowing()).thenReturn(false);

        // Start session
        touchHandler.onSessionStart(mSession);

        // Capture input listener
        final ArgumentCaptor<InputChannelCompat.InputEventListener> inputEventListenerCaptor =
                ArgumentCaptor.forClass(InputChannelCompat.InputEventListener.class);
        verify(mSession).registerInputListener(inputEventListenerCaptor.capture());

        // Report touch outside the inset
        when(mTouchInsetManager.checkWithinTouchRegion(anyInt(), anyInt())).thenReturn(
                CallbackToFutureAdapter.getFuture(completer -> {
                    completer.set(false);
                    return "testSessionEndWithTouchInInset";
                })
        );

        // Send down event.
        when(mMotionEvent.getAction()).thenReturn(MotionEvent.ACTION_DOWN);
        inputEventListenerCaptor.getValue().onInputEvent(mMotionEvent);
        mFakeExecutor.runAllReady();

        // Verify visibility controller doesn't hide until after timeout
        verify(mVisibilityController, never()).setVisibility(eq(View.INVISIBLE));
        mClock.advanceTime(HIDE_DELAY);
        // Verify visibility controller told to hide complications.
        verify(mVisibilityController).setVisibility(eq(View.INVISIBLE));

        Mockito.clearInvocations(mVisibilityController, mHandler);

        // Send up event.
        when(mMotionEvent.getAction()).thenReturn(MotionEvent.ACTION_UP);
        inputEventListenerCaptor.getValue().onInputEvent(mMotionEvent);
        mFakeExecutor.runAllReady();

        // Verify visibility controller told to show complications.
        mClock.advanceTime(RESTORE_TIMEOUT);
        verify(mVisibilityController).setVisibility(eq(View.VISIBLE));

        // Verify session ended.
        verify(mSession).pop();
    }
}
