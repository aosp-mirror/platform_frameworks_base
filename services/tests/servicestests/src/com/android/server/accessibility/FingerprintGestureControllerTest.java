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

import static android.accessibilityservice.FingerprintGestureController
        .FINGERPRINT_GESTURE_SWIPE_DOWN;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.accessibilityservice.FingerprintGestureController;
import android.accessibilityservice.FingerprintGestureController.FingerprintGestureCallback;
import android.accessibilityservice.IAccessibilityServiceConnection;

import com.android.server.accessibility.test.MessageCapturingHandler;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for FingerprintGestureController.
 * TODO: These tests aren't really for server code, so this isn't their ideal home.
 */
public class FingerprintGestureControllerTest {
    @Mock IAccessibilityServiceConnection mMockAccessibilityServiceConnection;
    @Mock FingerprintGestureCallback mMockFingerprintGestureCallback;
    FingerprintGestureController mFingerprintGestureController;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mFingerprintGestureController =
                new FingerprintGestureController(mMockAccessibilityServiceConnection);
    }

    @Test
    public void testIsGestureDetectionActive_returnsValueFromServer() throws Exception {
        when(mMockAccessibilityServiceConnection.isFingerprintGestureDetectionAvailable())
                .thenReturn(true);
        assertTrue(mFingerprintGestureController.isGestureDetectionAvailable());
        when(mMockAccessibilityServiceConnection.isFingerprintGestureDetectionAvailable())
                .thenReturn(false);
        assertFalse(mFingerprintGestureController.isGestureDetectionAvailable());
    }

    @Test
    public void testCallbacks_withNoListeners_shouldNotCrash() {
        mFingerprintGestureController.onGestureDetectionActiveChanged(true);
        mFingerprintGestureController.onGesture(FINGERPRINT_GESTURE_SWIPE_DOWN);
    }

    @Test
    public void testDetectionActiveCallback_noHandler_shouldCallback() {
        mFingerprintGestureController.registerFingerprintGestureCallback(
                mMockFingerprintGestureCallback, null);
        mFingerprintGestureController.onGestureDetectionActiveChanged(true);
        verify(mMockFingerprintGestureCallback, times(1))
                .onGestureDetectionAvailabilityChanged(true);
        mFingerprintGestureController.onGestureDetectionActiveChanged(false);
        verify(mMockFingerprintGestureCallback, times(1))
                .onGestureDetectionAvailabilityChanged(false);

        reset(mMockFingerprintGestureCallback);
        mFingerprintGestureController.unregisterFingerprintGestureCallback(
                mMockFingerprintGestureCallback);
        mFingerprintGestureController.onGestureDetectionActiveChanged(true);
        mFingerprintGestureController.onGestureDetectionActiveChanged(false);
        verifyZeroInteractions(mMockFingerprintGestureCallback);
    }

    @Test
    public void testDetectionActiveCallback_withHandler_shouldPostRunnableToHandler() {
        MessageCapturingHandler messageCapturingHandler = new MessageCapturingHandler((message) -> {
            message.getCallback().run();
            return true;
        });

        mFingerprintGestureController.registerFingerprintGestureCallback(
                mMockFingerprintGestureCallback, messageCapturingHandler);
        mFingerprintGestureController.onGestureDetectionActiveChanged(true);
        verify(mMockFingerprintGestureCallback, times(0))
                .onGestureDetectionAvailabilityChanged(true);
        messageCapturingHandler.sendLastMessage();
        verify(mMockFingerprintGestureCallback, times(1))
                .onGestureDetectionAvailabilityChanged(true);

        mFingerprintGestureController.onGestureDetectionActiveChanged(false);
        verify(mMockFingerprintGestureCallback, times(0))
                .onGestureDetectionAvailabilityChanged(false);
        messageCapturingHandler.sendLastMessage();
        verify(mMockFingerprintGestureCallback, times(1))
                .onGestureDetectionAvailabilityChanged(false);

        reset(mMockFingerprintGestureCallback);
        mFingerprintGestureController.unregisterFingerprintGestureCallback(
                mMockFingerprintGestureCallback);
        mFingerprintGestureController.onGestureDetectionActiveChanged(true);
        mFingerprintGestureController.onGestureDetectionActiveChanged(false);
        assertFalse(messageCapturingHandler.hasMessages());
        verifyZeroInteractions(mMockFingerprintGestureCallback);

        messageCapturingHandler.removeAllMessages();
    }

    @Test
    public void testGestureCallback_noHandler_shouldCallListener() {
        mFingerprintGestureController.registerFingerprintGestureCallback(
                mMockFingerprintGestureCallback, null);
        mFingerprintGestureController.onGesture(FINGERPRINT_GESTURE_SWIPE_DOWN);
        verify(mMockFingerprintGestureCallback, times(1))
                .onGestureDetected(FINGERPRINT_GESTURE_SWIPE_DOWN);

        reset(mMockFingerprintGestureCallback);
        mFingerprintGestureController.unregisterFingerprintGestureCallback(
                mMockFingerprintGestureCallback);
        mFingerprintGestureController.onGesture(FINGERPRINT_GESTURE_SWIPE_DOWN);
        verifyZeroInteractions(mMockFingerprintGestureCallback);
    }

    @Test
    public void testGestureCallback_withHandler_shouldPostRunnableToHandler() {
        MessageCapturingHandler messageCapturingHandler = new MessageCapturingHandler((message) -> {
            message.getCallback().run();
            return true;
        });

        mFingerprintGestureController.registerFingerprintGestureCallback(
                mMockFingerprintGestureCallback, messageCapturingHandler);
        mFingerprintGestureController.onGesture(FINGERPRINT_GESTURE_SWIPE_DOWN);
        verify(mMockFingerprintGestureCallback, times(0))
                .onGestureDetected(FINGERPRINT_GESTURE_SWIPE_DOWN);
        messageCapturingHandler.sendLastMessage();
        verify(mMockFingerprintGestureCallback, times(1))
                .onGestureDetected(FINGERPRINT_GESTURE_SWIPE_DOWN);

        reset(mMockFingerprintGestureCallback);
        mFingerprintGestureController.unregisterFingerprintGestureCallback(
                mMockFingerprintGestureCallback);
        mFingerprintGestureController.onGesture(FINGERPRINT_GESTURE_SWIPE_DOWN);
        assertFalse(messageCapturingHandler.hasMessages());
        verifyZeroInteractions(mMockFingerprintGestureCallback);

        messageCapturingHandler.removeAllMessages();
    }
}
