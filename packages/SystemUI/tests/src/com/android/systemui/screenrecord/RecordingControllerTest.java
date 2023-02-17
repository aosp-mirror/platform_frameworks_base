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

package com.android.systemui.screenrecord;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Looper;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.settings.UserContextProvider;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
/**
 * Tests for exception handling and  bitmap configuration in adding smart actions to Screenshot
 * Notification.
 */
public class RecordingControllerTest extends SysuiTestCase {

    private FakeSystemClock mFakeSystemClock = new FakeSystemClock();
    private FakeExecutor mMainExecutor = new FakeExecutor(mFakeSystemClock);
    @Mock
    private RecordingController.RecordingStateChangeCallback mCallback;
    @Mock
    private BroadcastDispatcher mBroadcastDispatcher;
    @Mock
    private UserContextProvider mUserContextProvider;
    @Mock
    private UserTracker mUserTracker;

    private RecordingController mController;

    private static final int USER_ID = 10;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mController = new RecordingController(mMainExecutor, mBroadcastDispatcher,
                mUserContextProvider, mUserTracker);
        mController.addCallback(mCallback);
    }

    // Test that when a countdown in progress is cancelled, the controller goes from starting to not
    // starting, and notifies listeners.
    @Test
    public void testCancelCountdown() {
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }

        mController.startCountdown(100, 10, null, null);

        assertTrue(mController.isStarting());
        assertFalse(mController.isRecording());

        mController.cancelCountdown();

        assertFalse(mController.isStarting());
        assertFalse(mController.isRecording());

        verify(mCallback).onCountdownEnd();
    }

    // Test that when recording is started, the start intent is sent and listeners are notified.
    @Test
    public void testStartRecording() throws PendingIntent.CanceledException {
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }

        PendingIntent startIntent = Mockito.mock(PendingIntent.class);
        mController.startCountdown(0, 0, startIntent, null);

        verify(mCallback).onCountdownEnd();
        verify(startIntent).send();
    }

    // Test that when recording is stopped, the stop intent is sent and listeners are notified.
    @Test
    public void testStopRecording() throws PendingIntent.CanceledException {
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }

        PendingIntent startIntent = Mockito.mock(PendingIntent.class);
        PendingIntent stopIntent = Mockito.mock(PendingIntent.class);

        mController.startCountdown(0, 0, startIntent, stopIntent);
        mController.stopRecording();

        assertFalse(mController.isStarting());
        assertFalse(mController.isRecording());
        verify(stopIntent).send();
        verify(mCallback).onRecordingEnd();
    }

    // Test that updating the controller state works and notifies listeners.
    @Test
    public void testUpdateState() {
        mController.updateState(true);
        assertTrue(mController.isRecording());
        verify(mCallback).onRecordingStart();

        mController.updateState(false);
        assertFalse(mController.isRecording());
        verify(mCallback).onRecordingEnd();
    }

    // Test that broadcast will update state
    @Test
    public void testUpdateStateBroadcast() {
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }

        // When a recording has started
        PendingIntent startIntent = Mockito.mock(PendingIntent.class);
        mController.startCountdown(0, 0, startIntent, null);
        verify(mCallback).onCountdownEnd();

        // then the receiver was registered
        verify(mBroadcastDispatcher).registerReceiver(eq(mController.mStateChangeReceiver),
                any(), any(), any());

        // When the receiver gets an update
        Intent intent = new Intent(RecordingController.INTENT_UPDATE_STATE);
        intent.putExtra(RecordingController.EXTRA_STATE, false);
        mController.mStateChangeReceiver.onReceive(mContext, intent);

        // then the state is updated
        assertFalse(mController.isRecording());
        verify(mCallback).onRecordingEnd();

        // and the receiver is unregistered
        verify(mBroadcastDispatcher).unregisterReceiver(eq(mController.mStateChangeReceiver));
    }

    // Test that switching users will stop an ongoing recording
    @Test
    public void testUserChange() {
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }

        // If we are recording
        PendingIntent startIntent = Mockito.mock(PendingIntent.class);
        PendingIntent stopIntent = Mockito.mock(PendingIntent.class);
        mController.startCountdown(0, 0, startIntent, stopIntent);
        mController.updateState(true);

        // and user is changed
        mController.mUserChangedCallback.onUserChanged(USER_ID, mContext);

        // Ensure that the recording was stopped
        verify(mCallback).onRecordingEnd();
        assertFalse(mController.isRecording());
    }
}
