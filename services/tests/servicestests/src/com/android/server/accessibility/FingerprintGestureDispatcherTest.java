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

import android.accessibilityservice.FingerprintGestureController;
import android.content.res.Resources;
import android.hardware.fingerprint.IFingerprintService;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.KeyEvent;

import com.android.server.accessibility.FingerprintGestureDispatcher.FingerprintGestureClient;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests for FingerprintGestureDispatcher
 */
public class FingerprintGestureDispatcherTest {

    private @Mock IFingerprintService mMockFingerprintService;
    private @Mock FingerprintGestureClient mNonGestureCapturingClient;
    private @Mock FingerprintGestureClient mGestureCapturingClient;
    private @Mock Resources mMockResources;

    private MessageCapturingHandler mMessageCapturingHandler;
    private FingerprintGestureDispatcher mFingerprintGestureDispatcher;

    @BeforeClass
    public static void oneTimeInitialization() {
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }
    }

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        // For most tests, we support fingerprint gestures
        when(mMockResources.getBoolean(anyInt())).thenReturn(true);
        mMessageCapturingHandler = new MessageCapturingHandler(
                msg -> mFingerprintGestureDispatcher.handleMessage(msg));
        mFingerprintGestureDispatcher = new FingerprintGestureDispatcher(mMockFingerprintService,
                mMockResources, new Object(), mMessageCapturingHandler);
        when(mNonGestureCapturingClient.isCapturingFingerprintGestures()).thenReturn(false);
        when(mGestureCapturingClient.isCapturingFingerprintGestures()).thenReturn(true);
    }

    @Test
    public void testNoServices_doesNotCrashOrConsumeGestures() {
        mFingerprintGestureDispatcher.onClientActiveChanged(true);
        mFingerprintGestureDispatcher.onClientActiveChanged(false);
        assertFalse(mFingerprintGestureDispatcher.onFingerprintGesture(
                KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP));
    }

    @Test
    public void testOneNonCapturingService_doesNotCrashOrConsumeGestures() {
        mFingerprintGestureDispatcher.updateClientList(
                Arrays.asList(mNonGestureCapturingClient));
        mFingerprintGestureDispatcher.onClientActiveChanged(true);
        mFingerprintGestureDispatcher.onClientActiveChanged(false);
        assertFalse(mFingerprintGestureDispatcher.onFingerprintGesture(
                KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP));
        verify(mNonGestureCapturingClient, times(0))
                .onFingerprintGestureDetectionActiveChanged(anyBoolean());
        verify(mNonGestureCapturingClient, times(0)).onFingerprintGesture(anyInt());
    }

    @Test
    public void testOneCapturingService_notifiesClientOfActivityChanges() {
        mFingerprintGestureDispatcher.updateClientList(
                Arrays.asList(mGestureCapturingClient));
        mFingerprintGestureDispatcher.onClientActiveChanged(true);
        // Client active means gesture detection isn't.
        verify(mGestureCapturingClient, times(1)).onFingerprintGestureDetectionActiveChanged(false);
        verify(mGestureCapturingClient, times(0)).onFingerprintGestureDetectionActiveChanged(true);
        mFingerprintGestureDispatcher.onClientActiveChanged(false);
        verify(mGestureCapturingClient, times(1)).onFingerprintGestureDetectionActiveChanged(false);
        verify(mGestureCapturingClient, times(1)).onFingerprintGestureDetectionActiveChanged(true);
    }

    @Test
    public void testOneCapturingService_consumesGesturesAndPassesThemAlong() {
        mFingerprintGestureDispatcher.updateClientList(
                Arrays.asList(mGestureCapturingClient));
        assertTrue(mFingerprintGestureDispatcher.onFingerprintGesture(
                KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP));
        verify(mGestureCapturingClient, times(1)).onFingerprintGesture(
                FingerprintGestureController.FINGERPRINT_GESTURE_SWIPE_UP);
        assertTrue(mFingerprintGestureDispatcher.onFingerprintGesture(
                KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN));
        verify(mGestureCapturingClient, times(1)).onFingerprintGesture(
                FingerprintGestureController.FINGERPRINT_GESTURE_SWIPE_DOWN);
        assertTrue(mFingerprintGestureDispatcher.onFingerprintGesture(
                KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT));
        verify(mGestureCapturingClient, times(1)).onFingerprintGesture(
                FingerprintGestureController.FINGERPRINT_GESTURE_SWIPE_LEFT);
        assertTrue(mFingerprintGestureDispatcher.onFingerprintGesture(
                KeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT));
        verify(mGestureCapturingClient, times(1)).onFingerprintGesture(
                FingerprintGestureController.FINGERPRINT_GESTURE_SWIPE_RIGHT);
    }

    @Test
    public void testInvalidKeyCodes_areNotCaptured() {
        mFingerprintGestureDispatcher.updateClientList(
                Arrays.asList(mGestureCapturingClient));
        assertFalse(mFingerprintGestureDispatcher.onFingerprintGesture(
                KeyEvent.KEYCODE_SPACE));
        verify(mGestureCapturingClient, times(0)).onFingerprintGesture(anyInt());
    }

    @Test
    public void testWithCapturingService_registersForFingerprintUpdates() throws Exception {
        verifyNoMoreInteractions(mMockFingerprintService);
        mFingerprintGestureDispatcher.updateClientList(
                Arrays.asList(mGestureCapturingClient));
        mMessageCapturingHandler.sendOneMessage();
        verify(mMockFingerprintService).addClientActiveCallback(mFingerprintGestureDispatcher);
    }

    @Test
    public void testWhenCapturingServiceStops_unregistersForFingerprintUpdates() throws Exception {
        verifyNoMoreInteractions(mMockFingerprintService);
        mFingerprintGestureDispatcher.updateClientList(
                Arrays.asList(mGestureCapturingClient));
        mMessageCapturingHandler.sendOneMessage();
        mFingerprintGestureDispatcher.updateClientList(Collections.emptyList());
        mMessageCapturingHandler.sendOneMessage();
        verify(mMockFingerprintService).removeClientActiveCallback(mFingerprintGestureDispatcher);
    }

    @Test
    public void testIsGestureDetectionAvailable_dependsOnFingerprintService() throws Exception {
        when(mMockFingerprintService.isClientActive()).thenReturn(true);
        assertFalse(mFingerprintGestureDispatcher.isFingerprintGestureDetectionAvailable());
        when(mMockFingerprintService.isClientActive()).thenReturn(false);
        assertTrue(mFingerprintGestureDispatcher.isFingerprintGestureDetectionAvailable());
    }

    @Test
    public void ifGestureDectionNotSupported_neverSaysAvailable() throws Exception {
        when(mMockResources.getBoolean(anyInt())).thenReturn(false);
        // Need to create a new dispatcher, since it picks up the resource value in its
        // constructor. This is fine since hardware config values don't change dynamically.
        FingerprintGestureDispatcher fingerprintGestureDispatcher =
                new FingerprintGestureDispatcher(mMockFingerprintService, mMockResources,
                        new Object(), mMessageCapturingHandler);

        when(mMockFingerprintService.isClientActive()).thenReturn(false);
        assertFalse(fingerprintGestureDispatcher.isFingerprintGestureDetectionAvailable());
    }
}
