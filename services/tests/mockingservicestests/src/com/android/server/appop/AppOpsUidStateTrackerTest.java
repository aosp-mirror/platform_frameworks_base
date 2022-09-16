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

package com.android.server.appop;

import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.MODE_FOREGROUND;
import static android.app.AppOpsManager.MODE_IGNORED;
import static android.app.AppOpsManager.OP_CAMERA;
import static android.app.AppOpsManager.OP_COARSE_LOCATION;
import static android.app.AppOpsManager.OP_FINE_LOCATION;
import static android.app.AppOpsManager.OP_RECORD_AUDIO;
import static android.app.AppOpsManager.OP_WIFI_SCAN;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AppOpsManager;
import android.os.Handler;
import android.os.Message;
import android.util.SparseArray;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.dx.mockito.inline.extended.StaticMockitoSession;
import com.android.internal.os.Clock;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.quality.Strictness;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class AppOpsUidStateTrackerTest {

    private static final int UID = 10001;

    // An op code that's not location/cam/mic so that we can test the code for evaluating mode
    // without a specific capability associated with it.
    public static final int OP_NO_CAPABILITIES = OP_WIFI_SCAN;

    @Mock
    ActivityManagerInternal mAmi;

    @Mock
    Handler mHandler;

    @Mock
    AppOpsService.Constants mConstants;

    AppOpsUidStateTrackerTestClock mClock = new AppOpsUidStateTrackerTestClock();

    AppOpsUidStateTracker mIntf;

    StaticMockitoSession mSession;

    @Before
    public void setUp() {
        mSession = ExtendedMockito.mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
                .startMocking();
        mConstants.TOP_STATE_SETTLE_TIME = 10 * 1000L;
        mConstants.FG_SERVICE_STATE_SETTLE_TIME = 5 * 1000L;
        mConstants.BG_STATE_SETTLE_TIME = 1 * 1000L;
        mIntf = new AppOpsUidStateTrackerImpl(mAmi, mHandler, mClock, mConstants);
    }

    @After
    public void tearDown() {
        mSession.finishMocking();
    }

    /**
     * This class makes the assumption that all ops are restricted at the same state, this is likely
     * to be the case forever or become obsolete with the capability mechanism. If this fails
     * something in {@link AppOpsUidStateTrackerImpl} might break when reporting if foreground mode
     * might change.
     */
    @Test
    public void testConstantFirstUnrestrictedUidState() {
        for (int i = 0; i < AppOpsManager.getNumOps(); i++) {
            assertEquals(AppOpsManager.UID_STATE_MAX_LAST_NON_RESTRICTED,
                    AppOpsManager.resolveFirstUnrestrictedUidState(i));
        }
    }

    @Test
    public void testNoCapability() {
        procStateBuilder(UID)
                .foregroundState()
                .update();

        assertEquals(MODE_IGNORED, mIntf.evalMode(UID, OP_RECORD_AUDIO, MODE_FOREGROUND));
        assertEquals(MODE_IGNORED, mIntf.evalMode(UID, OP_CAMERA, MODE_FOREGROUND));
        assertEquals(MODE_IGNORED, mIntf.evalMode(UID, OP_COARSE_LOCATION, MODE_FOREGROUND));
        assertEquals(MODE_IGNORED, mIntf.evalMode(UID, OP_FINE_LOCATION, MODE_FOREGROUND));
    }

    @Test
    public void testForegroundWithMicrophoneCapability() {
        procStateBuilder(UID)
                .foregroundState()
                .microphoneCapability()
                .update();

        assertEquals(MODE_ALLOWED, mIntf.evalMode(UID, OP_RECORD_AUDIO, MODE_FOREGROUND));

        assertEquals(MODE_IGNORED, mIntf.evalMode(UID, OP_CAMERA, MODE_FOREGROUND));
        assertEquals(MODE_IGNORED, mIntf.evalMode(UID, OP_COARSE_LOCATION, MODE_FOREGROUND));
        assertEquals(MODE_IGNORED, mIntf.evalMode(UID, OP_FINE_LOCATION, MODE_FOREGROUND));
    }

    @Test
    public void testBackgroundWithMicrophoneCapability() {
        procStateBuilder(UID)
                .backgroundState()
                .microphoneCapability()
                .update();

        assertEquals(MODE_ALLOWED, mIntf.evalMode(UID, OP_RECORD_AUDIO, MODE_FOREGROUND));

        assertEquals(MODE_IGNORED, mIntf.evalMode(UID, OP_CAMERA, MODE_FOREGROUND));
        assertEquals(MODE_IGNORED, mIntf.evalMode(UID, OP_COARSE_LOCATION, MODE_FOREGROUND));
        assertEquals(MODE_IGNORED, mIntf.evalMode(UID, OP_FINE_LOCATION, MODE_FOREGROUND));
    }

    @Test
    public void testForegroundWithCameraCapability() {
        procStateBuilder(UID)
                .foregroundState()
                .cameraCapability()
                .update();

        assertEquals(MODE_ALLOWED, mIntf.evalMode(UID, OP_CAMERA, MODE_FOREGROUND));

        assertEquals(MODE_IGNORED, mIntf.evalMode(UID, OP_RECORD_AUDIO, MODE_FOREGROUND));
        assertEquals(MODE_IGNORED, mIntf.evalMode(UID, OP_COARSE_LOCATION, MODE_FOREGROUND));
        assertEquals(MODE_IGNORED, mIntf.evalMode(UID, OP_FINE_LOCATION, MODE_FOREGROUND));
    }

    @Test
    public void testBackgroundWithCameraCapability() {
        procStateBuilder(UID)
                .backgroundState()
                .cameraCapability()
                .update();

        assertEquals(MODE_ALLOWED, mIntf.evalMode(UID, OP_CAMERA, MODE_FOREGROUND));

        assertEquals(MODE_IGNORED, mIntf.evalMode(UID, OP_RECORD_AUDIO, MODE_FOREGROUND));
        assertEquals(MODE_IGNORED, mIntf.evalMode(UID, OP_COARSE_LOCATION, MODE_FOREGROUND));
        assertEquals(MODE_IGNORED, mIntf.evalMode(UID, OP_FINE_LOCATION, MODE_FOREGROUND));
    }

    @Test
    public void testForegroundWithLocationCapability() {
        procStateBuilder(UID)
                .foregroundState()
                .locationCapability()
                .update();

        assertEquals(MODE_ALLOWED, mIntf.evalMode(UID, OP_COARSE_LOCATION, MODE_FOREGROUND));
        assertEquals(MODE_ALLOWED, mIntf.evalMode(UID, OP_FINE_LOCATION, MODE_FOREGROUND));

        assertEquals(MODE_IGNORED, mIntf.evalMode(UID, OP_RECORD_AUDIO, MODE_FOREGROUND));
        assertEquals(MODE_IGNORED, mIntf.evalMode(UID, OP_CAMERA, MODE_FOREGROUND));
    }

    @Test
    public void testBackgroundWithLocationCapability() {
        procStateBuilder(UID)
                .backgroundState()
                .locationCapability()
                .update();

        assertEquals(MODE_ALLOWED, mIntf.evalMode(UID, OP_COARSE_LOCATION, MODE_FOREGROUND));
        assertEquals(MODE_ALLOWED, mIntf.evalMode(UID, OP_FINE_LOCATION, MODE_FOREGROUND));

        assertEquals(MODE_IGNORED, mIntf.evalMode(UID, OP_RECORD_AUDIO, MODE_FOREGROUND));
        assertEquals(MODE_IGNORED, mIntf.evalMode(UID, OP_CAMERA, MODE_FOREGROUND));
    }

    @Test
    public void testForegroundNotCapabilitiesTracked() {
        procStateBuilder(UID)
                .foregroundState()
                .update();

        assertEquals(MODE_ALLOWED, mIntf.evalMode(UID, OP_NO_CAPABILITIES, MODE_FOREGROUND));
    }

    @Test
    public void testBackgroundNotCapabilitiesTracked() {
        procStateBuilder(UID)
                .backgroundState()
                .update();

        assertEquals(MODE_IGNORED, mIntf.evalMode(UID, OP_NO_CAPABILITIES, MODE_FOREGROUND));
    }

    @Test
    public void testBackgroundToForegroundTransition() {
        procStateBuilder(UID)
                .backgroundState()
                .update();
        assertBackground(UID);

        procStateBuilder(UID)
                .foregroundState()
                .update();
        assertForeground(UID);
    }

    @Test
    public void testForegroundToBackgroundTransition() {
        procStateBuilder(UID)
                .foregroundState()
                .update();
        assertForeground(UID);

        procStateBuilder(UID)
                .backgroundState()
                .update();
        // Still in foreground due to settle time
        assertForeground(UID);

        AtomicReference<Message> messageAtomicReference = new AtomicReference<>();
        AtomicLong delayAtomicReference = new AtomicLong();

        getPostDelayedMessageArguments(messageAtomicReference, delayAtomicReference);
        Message message = messageAtomicReference.get();
        long delay = delayAtomicReference.get();

        assertNotNull(message);
        assertEquals(mConstants.TOP_STATE_SETTLE_TIME + 1, delay);

        mClock.advanceTime(mConstants.TOP_STATE_SETTLE_TIME + 1);
        message.getCallback().run();
        assertBackground(UID);
    }

    @Test
    public void testForegroundServiceToBackgroundTransition() {
        procStateBuilder(UID)
                .foregroundServiceState()
                .update();
        assertForeground(UID);

        procStateBuilder(UID)
                .backgroundState()
                .update();
        // Still in foreground due to settle time
        assertForeground(UID);

        AtomicReference<Message> messageAtomicReference = new AtomicReference<>();
        AtomicLong delayAtomicReference = new AtomicLong();

        getPostDelayedMessageArguments(messageAtomicReference, delayAtomicReference);
        Message message = messageAtomicReference.get();
        long delay = delayAtomicReference.get();

        assertNotNull(message);
        assertEquals(mConstants.FG_SERVICE_STATE_SETTLE_TIME + 1, delay);

        mClock.advanceTime(mConstants.FG_SERVICE_STATE_SETTLE_TIME + 1);
        message.getCallback().run();
        assertBackground(UID);
    }

    @Test
    public void testEarlyUpdateDoesntCommit() {
        procStateBuilder(UID)
                .foregroundServiceState()
                .update();
        assertForeground(UID);

        procStateBuilder(UID)
                .backgroundState()
                .update();
        // Still in foreground due to settle time
        assertForeground(UID);

        AtomicReference<Message> messageAtomicReference = new AtomicReference<>();

        getPostDelayedMessageArguments(messageAtomicReference, null);
        Message message = messageAtomicReference.get();

        // 1 ms short of settle time
        mClock.advanceTime(mConstants.FG_SERVICE_STATE_SETTLE_TIME - 1);
        message.getCallback().run();
        assertForeground(UID);
    }

    @Test
    public void testMicrophoneCapabilityAdded() {
        procStateBuilder(UID)
                .backgroundState()
                .update();

        procStateBuilder(UID)
                .backgroundState()
                .microphoneCapability()
                .update();

        assertEquals(MODE_ALLOWED, mIntf.evalMode(UID, OP_RECORD_AUDIO, MODE_FOREGROUND));
    }

    @Test
    public void testMicrophoneCapabilityRemoved() {
        procStateBuilder(UID)
                .backgroundState()
                .microphoneCapability()
                .update();

        procStateBuilder(UID)
                .backgroundState()
                .update();

        assertEquals(MODE_IGNORED, mIntf.evalMode(UID, OP_RECORD_AUDIO, MODE_FOREGROUND));
    }

    @Test
    public void testCameraCapabilityAdded() {
        procStateBuilder(UID)
                .backgroundState()
                .update();

        procStateBuilder(UID)
                .backgroundState()
                .cameraCapability()
                .update();

        assertEquals(MODE_ALLOWED, mIntf.evalMode(UID, OP_CAMERA, MODE_FOREGROUND));
    }

    @Test
    public void testCameraCapabilityRemoved() {
        procStateBuilder(UID)
                .backgroundState()
                .cameraCapability()
                .update();

        procStateBuilder(UID)
                .backgroundState()
                .update();

        assertEquals(MODE_IGNORED, mIntf.evalMode(UID, OP_CAMERA, MODE_FOREGROUND));
    }

    @Test
    public void testLocationCapabilityAdded() {
        procStateBuilder(UID)
                .backgroundState()
                .update();

        procStateBuilder(UID)
                .backgroundState()
                .locationCapability()
                .update();

        assertEquals(MODE_ALLOWED, mIntf.evalMode(UID, OP_COARSE_LOCATION, MODE_FOREGROUND));
        assertEquals(MODE_ALLOWED, mIntf.evalMode(UID, OP_FINE_LOCATION, MODE_FOREGROUND));
    }

    @Test
    public void testLocationCapabilityRemoved() {
        procStateBuilder(UID)
                .backgroundState()
                .locationCapability()
                .update();

        procStateBuilder(UID)
                .backgroundState()
                .update();

        assertEquals(MODE_IGNORED, mIntf.evalMode(UID, OP_COARSE_LOCATION, MODE_FOREGROUND));
        assertEquals(MODE_IGNORED, mIntf.evalMode(UID, OP_FINE_LOCATION, MODE_FOREGROUND));
    }

    @Test
    public void testVisibleAppWidget() {
        procStateBuilder(UID)
                .backgroundState()
                .update();

        SparseArray<String> appPackageNames = new SparseArray<>();
        appPackageNames.put(UID, "");
        mIntf.updateAppWidgetVisibility(appPackageNames, true);

        assertForeground(UID);
        assertEquals(MODE_ALLOWED, mIntf.evalMode(UID, OP_RECORD_AUDIO, MODE_FOREGROUND));
        assertEquals(MODE_ALLOWED, mIntf.evalMode(UID, OP_CAMERA, MODE_FOREGROUND));
        assertEquals(MODE_ALLOWED, mIntf.evalMode(UID, OP_COARSE_LOCATION, MODE_FOREGROUND));
        assertEquals(MODE_ALLOWED, mIntf.evalMode(UID, OP_FINE_LOCATION, MODE_FOREGROUND));
    }

    @Test
    public void testPendingTop() {
        procStateBuilder(UID)
                .backgroundState()
                .update();

        doReturn(true).when(mAmi).isPendingTopUid(eq(UID));

        assertForeground(UID);
        assertEquals(MODE_ALLOWED, mIntf.evalMode(UID, OP_RECORD_AUDIO, MODE_FOREGROUND));
        assertEquals(MODE_ALLOWED, mIntf.evalMode(UID, OP_CAMERA, MODE_FOREGROUND));
        assertEquals(MODE_ALLOWED, mIntf.evalMode(UID, OP_COARSE_LOCATION, MODE_FOREGROUND));
        assertEquals(MODE_ALLOWED, mIntf.evalMode(UID, OP_FINE_LOCATION, MODE_FOREGROUND));
    }

    @Test
    public void testTempAllowlist() {
        procStateBuilder(UID)
                .backgroundState()
                .update();

        doReturn(true).when(mAmi).isTempAllowlistedForFgsWhileInUse(eq(UID));

        assertForeground(UID);
        assertEquals(MODE_ALLOWED, mIntf.evalMode(UID, OP_RECORD_AUDIO, MODE_FOREGROUND));
        assertEquals(MODE_ALLOWED, mIntf.evalMode(UID, OP_CAMERA, MODE_FOREGROUND));
        assertEquals(MODE_ALLOWED, mIntf.evalMode(UID, OP_COARSE_LOCATION, MODE_FOREGROUND));
        assertEquals(MODE_ALLOWED, mIntf.evalMode(UID, OP_FINE_LOCATION, MODE_FOREGROUND));
    }

    /* If testForegroundNotCapabilitiesTracked fails, this assertion is probably incorrect */
    private void assertForeground(int uid) {
        assertEquals(MODE_ALLOWED, mIntf.evalMode(uid, OP_NO_CAPABILITIES, MODE_FOREGROUND));
    }

    /* If testBackgroundNotCapabilitiesTracked fails, this assertion is probably incorrect */
    private void assertBackground(int uid) {
        assertEquals(MODE_IGNORED, mIntf.evalMode(uid, OP_NO_CAPABILITIES, MODE_FOREGROUND));
    }

    private void getPostDelayedMessageArguments(AtomicReference<Message> message,
            AtomicLong delay) {

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        ArgumentCaptor<Long> delayCaptor = ArgumentCaptor.forClass(Long.class);

        verify(mHandler).sendMessageDelayed(messageCaptor.capture(), delayCaptor.capture());

        if (message != null) {
            message.set(messageCaptor.getValue());
        }
        if (delay != null) {
            delay.set(delayCaptor.getValue());
        }
    }

    private UidProcStateUpdateBuilder procStateBuilder(int uid) {
        return new UidProcStateUpdateBuilder(mIntf, uid);
    }

    private static class UidProcStateUpdateBuilder {
        private AppOpsUidStateTracker mIntf;
        private int mUid;
        private int mProcState = ActivityManager.PROCESS_STATE_NONEXISTENT;
        private int mCapability = 0;

        private UidProcStateUpdateBuilder(AppOpsUidStateTracker intf, int uid) {
            mUid = uid;
            mIntf = intf;
        }

        public void update() {
            mIntf.updateUidProcState(mUid, mProcState, mCapability);
        }

        public UidProcStateUpdateBuilder persistentState() {
            mProcState = ActivityManager.PROCESS_STATE_PERSISTENT;
            return this;
        }

        public UidProcStateUpdateBuilder foregroundState() {
            mProcState = ActivityManager.PROCESS_STATE_TOP;
            return this;
        }

        public UidProcStateUpdateBuilder foregroundServiceState() {
            mProcState = ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE;
            return this;
        }

        public UidProcStateUpdateBuilder backgroundState() {
            mProcState = ActivityManager.PROCESS_STATE_SERVICE;
            return this;
        }

        public UidProcStateUpdateBuilder cachedState() {
            mProcState = ActivityManager.PROCESS_STATE_CACHED_ACTIVITY;
            return this;
        }

        public UidProcStateUpdateBuilder locationCapability() {
            mCapability |= ActivityManager.PROCESS_CAPABILITY_FOREGROUND_LOCATION;
            return this;
        }

        public UidProcStateUpdateBuilder cameraCapability() {
            mCapability |= ActivityManager.PROCESS_CAPABILITY_FOREGROUND_CAMERA;
            return this;
        }

        public UidProcStateUpdateBuilder microphoneCapability() {
            mCapability |= ActivityManager.PROCESS_CAPABILITY_FOREGROUND_MICROPHONE;
            return this;
        }
    }

    private static class AppOpsUidStateTrackerTestClock extends Clock {

        long mElapsedRealTime = 0x5f3759df;

        @Override
        public long elapsedRealtime() {
            return mElapsedRealTime;
        }

        void advanceTime(long time) {
            mElapsedRealTime += time;
        }
    }
}
