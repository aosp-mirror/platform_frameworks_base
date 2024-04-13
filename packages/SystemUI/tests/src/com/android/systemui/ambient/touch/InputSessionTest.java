/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.ambient.touch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.platform.test.annotations.EnableFlags;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.Choreographer;
import android.view.GestureDetector;
import android.view.InputEvent;
import android.view.MotionEvent;

import androidx.test.filters.SmallTest;

import com.android.systemui.Flags;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.shared.system.InputChannelCompat;
import com.android.systemui.shared.system.InputMonitorCompat;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

/**
 * A test suite for exercising {@link InputSession}.
 */
@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper()
public class InputSessionTest extends SysuiTestCase {
    @Mock
    InputMonitorCompat mInputMonitor;

    @Mock
    GestureDetector mGestureDetector;

    @Mock
    InputChannelCompat.InputEventListener mInputEventListener;

    TestableLooper mLooper;

    @Mock
    Choreographer mChoreographer;

    @Mock
    InputChannelCompat.InputEventReceiver mInputEventReceiver;

    InputSession mSession;

    InputChannelCompat.InputEventListener mEventListener;


    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mLooper = TestableLooper.get(this);
    }

    private void createSession(boolean pilfer) {
        when(mInputMonitor.getInputReceiver(any(), any(), any()))
                .thenReturn(mInputEventReceiver);
        mSession = new InputSession(mInputMonitor, mGestureDetector,
                mInputEventListener, mChoreographer, mLooper.getLooper(), pilfer);
        final ArgumentCaptor<InputChannelCompat.InputEventListener> listenerCaptor =
                ArgumentCaptor.forClass(InputChannelCompat.InputEventListener.class);
        verify(mInputMonitor).getInputReceiver(any(), any(), listenerCaptor.capture());
        mEventListener = listenerCaptor.getValue();
    }

    /**
     * Ensures consumed motion events are pilfered when option is set.
     */
    @Test
    public void testPilferOnMotionEventGestureConsume() {
        createSession(true);
        final MotionEvent event = Mockito.mock(MotionEvent.class);
        when(mGestureDetector.onTouchEvent(event)).thenReturn(true);
        mEventListener.onInputEvent(event);
        verify(mInputEventListener).onInputEvent(eq(event));
        verify(mInputMonitor).pilferPointers();
    }

    /**
     * Ensures consumed motion events are not pilfered when option is not set.
     */
    @Test
    public void testNoPilferOnMotionEventGestureConsume() {
        createSession(false);
        final MotionEvent event = Mockito.mock(MotionEvent.class);
        when(mGestureDetector.onTouchEvent(event)).thenReturn(true);
        mEventListener.onInputEvent(event);
        verify(mInputEventListener).onInputEvent(eq(event));
        verify(mInputMonitor, never()).pilferPointers();
    }

    /**
     * Ensures input events are never pilfered.
     */
    @Test
    public void testNoPilferOnInputEvent() {
        createSession(true);
        final InputEvent event = Mockito.mock(InputEvent.class);
        mEventListener.onInputEvent(event);
        verify(mInputEventListener).onInputEvent(eq(event));
        verify(mInputMonitor, never()).pilferPointers();
    }

    @Test
    @EnableFlags(Flags.FLAG_DREAM_INPUT_SESSION_PILFER_ONCE)
    public void testPilferOnce() {
        createSession(true);
        final MotionEvent event = Mockito.mock(MotionEvent.class);
        when(mGestureDetector.onTouchEvent(event)).thenReturn(true);
        mEventListener.onInputEvent(event);
        mEventListener.onInputEvent(event);
        verify(mInputEventListener, times(2)).onInputEvent(eq(event));
        verify(mInputMonitor, times(1)).pilferPointers();
    }

    /**
     * Ensures components are properly disposed.
     */
    @Test
    public void testDispose() {
        createSession(true);
        mSession.dispose();
        verify(mInputMonitor).dispose();
        verify(mInputEventReceiver).dispose();
    }
}
