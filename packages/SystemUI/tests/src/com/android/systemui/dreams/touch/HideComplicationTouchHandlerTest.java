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

package com.android.systemui.dreams.touch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.Handler;
import android.testing.AndroidTestingRunner;
import android.view.MotionEvent;
import android.view.View;

import androidx.concurrent.futures.CallbackToFutureAdapter;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.dreams.complication.Complication;
import com.android.systemui.shared.system.InputChannelCompat;
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
@RunWith(AndroidTestingRunner.class)
public class HideComplicationTouchHandlerTest extends SysuiTestCase {
    private static final int RESTORE_TIMEOUT = 1000;

    @Mock
    Complication.VisibilityController mVisibilityController;

    @Mock
    TouchInsetManager mTouchInsetManager;

    @Mock
    Handler mHandler;

    @Mock
    MotionEvent mMotionEvent;

    @Mock
    DreamTouchHandler.TouchSession mSession;

    FakeExecutor mFakeExecutor = new FakeExecutor(new FakeSystemClock());

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
    }

    /**
     * Ensures no actions are taken when there multiple sessions.
     */
    @Test
    public void testSessionEndOnMultipleSessions() {
        final HideComplicationTouchHandler touchHandler = new HideComplicationTouchHandler(
                mVisibilityController,
                RESTORE_TIMEOUT,
                mTouchInsetManager,
                mFakeExecutor,
                mHandler);

        // Report multiple active sessions.
        when(mSession.getActiveSessionCount()).thenReturn(2);

        // Start session.
        touchHandler.onSessionStart(mSession);

        // Verify session end.
        verify(mSession).pop();

        // Verify no interaction with visibility controller.
        verify(mVisibilityController, never()).setVisibility(anyInt(), anyBoolean());
    }

    /**
     * Ensures no actions are taken when there multiple sessions.
     */
    @Test
    public void testSessionEndWithTouchInInset() {
        final HideComplicationTouchHandler touchHandler = new HideComplicationTouchHandler(
                mVisibilityController,
                RESTORE_TIMEOUT,
                mTouchInsetManager,
                mFakeExecutor,
                mHandler);

        // Report one session
        when(mSession.getActiveSessionCount()).thenReturn(1);

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

        // Verify no interaction with visibility controller.
        verify(mVisibilityController, never()).setVisibility(anyInt(), anyBoolean());
    }

    /**
     * Make sure visibility changes are triggered from session events.
     */
    @Test
    public void testSessionLifecycle() {
        final HideComplicationTouchHandler touchHandler = new HideComplicationTouchHandler(
                mVisibilityController,
                RESTORE_TIMEOUT,
                mTouchInsetManager,
                mFakeExecutor,
                mHandler);

        // Report one session
        when(mSession.getActiveSessionCount()).thenReturn(1);

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

        // Verify callback to restore visibility cancelled.
        verify(mHandler).removeCallbacks(any());

        // Verify visibility controller told to hide complications.
        verify(mVisibilityController).setVisibility(eq(View.INVISIBLE), anyBoolean());

        Mockito.clearInvocations(mVisibilityController, mHandler);

        // Send up event.
        when(mMotionEvent.getAction()).thenReturn(MotionEvent.ACTION_UP);
        inputEventListenerCaptor.getValue().onInputEvent(mMotionEvent);
        mFakeExecutor.runAllReady();

        // Verify visibility controller told to show complications.
        ArgumentCaptor<Runnable> delayRunnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(mHandler).postDelayed(delayRunnableCaptor.capture(),
                eq(Long.valueOf(RESTORE_TIMEOUT)));
        delayRunnableCaptor.getValue().run();
        verify(mVisibilityController).setVisibility(eq(View.VISIBLE), anyBoolean());

        // Verify session ended.
        verify(mSession).pop();
    }
}
