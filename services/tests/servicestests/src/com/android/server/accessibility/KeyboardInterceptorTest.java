/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.accessibility;

import static junit.framework.Assert.assertTrue;

import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.hamcrest.MockitoHamcrest.argThat;

import android.os.Looper;
import android.view.KeyEvent;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.policy.WindowManagerPolicy;
import com.android.server.policy.WindowManagerPolicy.WindowState;

import org.hamcrest.Description;
import org.hamcrest.TypeSafeMatcher;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

/**
 * Tests for KeyboardInterceptor
 */
@RunWith(AndroidJUnit4.class)
public class KeyboardInterceptorTest {
    private KeyboardInterceptor mInterceptor;
    private MessageCapturingHandler mHandler = new MessageCapturingHandler(
            msg -> mInterceptor.handleMessage(msg));
    @Mock AccessibilityManagerService mMockAms;
    @Mock WindowManagerPolicy mMockPolicy;

    @BeforeClass
    public static void oneTimeInitialization() {
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mInterceptor = new KeyboardInterceptor(mMockAms, mMockPolicy, mHandler);
    }

    @Test
    public void whenNonspecialKeyArrives_withNothingInQueue_eventGoesToAms() {
        KeyEvent event = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_0);
        mInterceptor.onKeyEvent(event, 0);
        verify(mMockAms).notifyKeyEvent(argThat(matchesKeyEvent(event)), eq(0));
    }

    @Test
    public void whenVolumeKeyArrives_andPolicySaysUseIt_eventGoesToAms() {
        KeyEvent event = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN);
        when(mMockPolicy.interceptKeyBeforeDispatching((WindowState) argThat(nullValue()),
                argThat(matchesKeyEvent(event)), eq(0))).thenReturn(0L);
        mInterceptor.onKeyEvent(event, 0);
        verify(mMockAms).notifyKeyEvent(argThat(matchesKeyEvent(event)), eq(0));
    }

    @Test
    public void whenVolumeKeyArrives_andPolicySaysDropIt_eventDropped() {
        KeyEvent event = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP);
        when(mMockPolicy.interceptKeyBeforeDispatching((WindowState) argThat(nullValue()),
                argThat(matchesKeyEvent(event)), eq(0))).thenReturn(-1L);
        mInterceptor.onKeyEvent(event, 0);
        verify(mMockAms, times(0)).notifyKeyEvent(anyObject(), anyInt());
        assertFalse(mHandler.hasMessages());
    }

    @Test
    public void whenVolumeKeyArrives_andPolicySaysDelayThenUse_eventQueuedThenSentToAms() {
        KeyEvent event = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP);
        when(mMockPolicy.interceptKeyBeforeDispatching((WindowState) argThat(nullValue()),
                argThat(matchesKeyEvent(event)), eq(0))).thenReturn(150L);
        mInterceptor.onKeyEvent(event, 0);

        assertTrue(mHandler.hasMessages());
        verify(mMockAms, times(0)).notifyKeyEvent(anyObject(), anyInt());

        when(mMockPolicy.interceptKeyBeforeDispatching((WindowState) argThat(nullValue()),
                argThat(matchesKeyEvent(event)), eq(0))).thenReturn(0L);
        mHandler.sendAllMessages();

        verify(mMockAms).notifyKeyEvent(argThat(matchesKeyEvent(event)), eq(0));
    }

    @Test
    public void whenVolumeKeyArrives_andPolicySaysDelayThenDrop_eventQueuedThenDropped() {
        KeyEvent event = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN);
        when(mMockPolicy.interceptKeyBeforeDispatching((WindowState) argThat(nullValue()),
                argThat(matchesKeyEvent(event)), eq(0))).thenReturn(150L);
        mInterceptor.onKeyEvent(event, 0);

        assertTrue(mHandler.hasMessages());
        verify(mMockAms, times(0)).notifyKeyEvent(anyObject(), anyInt());

        when(mMockPolicy.interceptKeyBeforeDispatching((WindowState) argThat(nullValue()),
                argThat(matchesKeyEvent(event)), eq(0))).thenReturn(-1L);
        mHandler.sendAllMessages();

        verify(mMockAms, times(0)).notifyKeyEvent(anyObject(), anyInt());
    }

    @Test
    public void whenSomeEventsGetDelayed_allEventsStillInOrder() {
        KeyEvent[] events = {new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_0),
                new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP),
                new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A),
                new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_UP),
                new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_0)};

        when(mMockPolicy.interceptKeyBeforeDispatching((WindowState) argThat(nullValue()),
                argThat(matchesKeyEvent(events[1])), eq(0))).thenReturn(150L);
        when(mMockPolicy.interceptKeyBeforeDispatching((WindowState) argThat(nullValue()),
                argThat(matchesKeyEvent(events[3])), eq(0))).thenReturn(75L);

        for (KeyEvent event : events) {
            mInterceptor.onKeyEvent(event, 0);
        }

        when(mMockPolicy.interceptKeyBeforeDispatching((WindowState) argThat(nullValue()),
                argThat(matchesKeyEvent(events[1])), eq(0))).thenReturn(0L);
        when(mMockPolicy.interceptKeyBeforeDispatching((WindowState) argThat(nullValue()),
                argThat(matchesKeyEvent(events[3])), eq(0))).thenReturn(0L);

        mHandler.sendAllMessages();

        InOrder inOrder = Mockito.inOrder(mMockAms);
        for (KeyEvent event : events) {
            inOrder.verify(mMockAms).notifyKeyEvent(argThat(matchesKeyEvent(event)), eq(0));
        }
    }

    @Test
    public void whenSomeEventsGetDropped_otherEventsStillInOrder() {
        KeyEvent[] events = {new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_0),
                new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP),
                new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A),
                new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_UP),
                new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_0)};

        when(mMockPolicy.interceptKeyBeforeDispatching((WindowState) argThat(nullValue()),
                argThat(matchesKeyEvent(events[1])), eq(0))).thenReturn(150L);
        when(mMockPolicy.interceptKeyBeforeDispatching((WindowState) argThat(nullValue()),
                argThat(matchesKeyEvent(events[3])), eq(0))).thenReturn(75L);

        for (KeyEvent event : events) {
            mInterceptor.onKeyEvent(event, 0);
        }

        when(mMockPolicy.interceptKeyBeforeDispatching((WindowState) argThat(nullValue()),
                argThat(matchesKeyEvent(events[1])), eq(0))).thenReturn(-1L);
        when(mMockPolicy.interceptKeyBeforeDispatching((WindowState) argThat(nullValue()),
                argThat(matchesKeyEvent(events[3])), eq(0))).thenReturn(-1L);

        mHandler.sendAllMessages();

        InOrder inOrder = Mockito.inOrder(mMockAms);
        for (KeyEvent event : events) {
            if ((event == events[1]) || (event == events[3])) continue;
            inOrder.verify(mMockAms).notifyKeyEvent(argThat(matchesKeyEvent(event)), eq(0));
        }
    }

    private static KeyEventMatcher matchesKeyEvent(KeyEvent event) {
        return new KeyEventMatcher(event);
    }

    private static class KeyEventMatcher extends TypeSafeMatcher<KeyEvent> {
        private KeyEvent mEventToMatch;

        public KeyEventMatcher(KeyEvent eventToMatch) {
            mEventToMatch = eventToMatch;
        }

        @Override
        protected boolean matchesSafely(KeyEvent item) {
            return (mEventToMatch.getKeyCode() == item.getKeyCode())
                    && (mEventToMatch.getAction() == item.getAction());
        }

        @Override
        public void describeTo(Description description) {
            description.appendText("Matches key event");
        }
    }
}