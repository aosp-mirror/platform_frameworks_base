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
import static android.app.AppOpsManager.OP_RECEIVE_EXPLICIT_USER_INTERACTION_AUDIO;
import static android.app.AppOpsManager.OP_RECORD_AUDIO;
import static android.app.AppOpsManager.OP_WIFI_SCAN;
import static android.app.AppOpsManager.UID_STATE_BACKGROUND;
import static android.app.AppOpsManager.UID_STATE_CACHED;
import static android.app.AppOpsManager.UID_STATE_FOREGROUND;
import static android.app.AppOpsManager.UID_STATE_FOREGROUND_SERVICE;
import static android.app.AppOpsManager.UID_STATE_MAX_LAST_NON_RESTRICTED;
import static android.app.AppOpsManager.UID_STATE_TOP;

import static com.android.server.appop.AppOpsUidStateTracker.processStateToUidState;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AppOpsManager;
import android.util.SparseArray;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.dx.mockito.inline.extended.StaticMockitoSession;
import com.android.internal.os.Clock;
import com.android.server.appop.AppOpsUidStateTracker.UidStateChangedCallback;
import com.android.server.appop.AppOpsUidStateTrackerImpl.DelayableExecutor;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.quality.Strictness;

import java.util.PriorityQueue;

public class AppOpsUidStateTrackerTest {

    private static final int UID = 10001;

    // An op code that's not location/cam/mic so that we can test the code for evaluating mode
    // without a specific capability associated with it.
    public static final int OP_NO_CAPABILITIES = OP_WIFI_SCAN;

    @Mock
    ActivityManagerInternal mAmi;

    @Mock
    AppOpsService.Constants mConstants;

    AppOpsUidStateTrackerTestExecutor mExecutor = new AppOpsUidStateTrackerTestExecutor();

    AppOpsUidStateTrackerTestClock mClock = new AppOpsUidStateTrackerTestClock(mExecutor);

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
        mIntf = new AppOpsUidStateTrackerImpl(mAmi, mExecutor, mClock, mConstants,
                Thread.currentThread());
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
            assertEquals(UID_STATE_MAX_LAST_NON_RESTRICTED,
                    AppOpsManager.resolveFirstUnrestrictedUidState(i));
        }
    }

    @Test
    public void testNoCapability() {
        procStateBuilder(UID)
                .topState()
                .update();

        assertEquals(MODE_IGNORED, mIntf.evalMode(UID, OP_RECORD_AUDIO, MODE_FOREGROUND));
        assertEquals(MODE_IGNORED, mIntf.evalMode(UID, OP_CAMERA, MODE_FOREGROUND));
        assertEquals(MODE_IGNORED, mIntf.evalMode(UID, OP_COARSE_LOCATION, MODE_FOREGROUND));
        assertEquals(MODE_IGNORED, mIntf.evalMode(UID, OP_FINE_LOCATION, MODE_FOREGROUND));
        assertEquals(MODE_IGNORED,
                mIntf.evalMode(UID, OP_RECEIVE_EXPLICIT_USER_INTERACTION_AUDIO, MODE_FOREGROUND));
    }

    @Test
    public void testForegroundWithMicrophoneCapability() {
        procStateBuilder(UID)
                .topState()
                .microphoneCapability()
                .update();

        assertEquals(MODE_ALLOWED, mIntf.evalMode(UID, OP_RECORD_AUDIO, MODE_FOREGROUND));
        assertEquals(MODE_ALLOWED,
                mIntf.evalMode(UID, OP_RECEIVE_EXPLICIT_USER_INTERACTION_AUDIO, MODE_FOREGROUND));

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
        assertEquals(MODE_ALLOWED,
                mIntf.evalMode(UID, OP_RECEIVE_EXPLICIT_USER_INTERACTION_AUDIO, MODE_FOREGROUND));

        assertEquals(MODE_IGNORED, mIntf.evalMode(UID, OP_CAMERA, MODE_FOREGROUND));
        assertEquals(MODE_IGNORED, mIntf.evalMode(UID, OP_COARSE_LOCATION, MODE_FOREGROUND));
        assertEquals(MODE_IGNORED, mIntf.evalMode(UID, OP_FINE_LOCATION, MODE_FOREGROUND));
    }

    @Test
    public void testForegroundWithCameraCapability() {
        procStateBuilder(UID)
                .topState()
                .cameraCapability()
                .update();

        assertEquals(MODE_ALLOWED, mIntf.evalMode(UID, OP_CAMERA, MODE_FOREGROUND));

        assertEquals(MODE_IGNORED, mIntf.evalMode(UID, OP_RECORD_AUDIO, MODE_FOREGROUND));
        assertEquals(MODE_IGNORED, mIntf.evalMode(UID, OP_COARSE_LOCATION, MODE_FOREGROUND));
        assertEquals(MODE_IGNORED, mIntf.evalMode(UID, OP_FINE_LOCATION, MODE_FOREGROUND));
        assertEquals(MODE_IGNORED,
                mIntf.evalMode(UID, OP_RECEIVE_EXPLICIT_USER_INTERACTION_AUDIO, MODE_FOREGROUND));
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
        assertEquals(MODE_IGNORED,
                mIntf.evalMode(UID, OP_RECEIVE_EXPLICIT_USER_INTERACTION_AUDIO, MODE_FOREGROUND));
    }

    @Test
    public void testForegroundWithLocationCapability() {
        procStateBuilder(UID)
                .topState()
                .locationCapability()
                .update();

        assertEquals(MODE_ALLOWED, mIntf.evalMode(UID, OP_COARSE_LOCATION, MODE_FOREGROUND));
        assertEquals(MODE_ALLOWED, mIntf.evalMode(UID, OP_FINE_LOCATION, MODE_FOREGROUND));

        assertEquals(MODE_IGNORED, mIntf.evalMode(UID, OP_RECORD_AUDIO, MODE_FOREGROUND));
        assertEquals(MODE_IGNORED, mIntf.evalMode(UID, OP_CAMERA, MODE_FOREGROUND));
        assertEquals(MODE_IGNORED,
                mIntf.evalMode(UID, OP_RECEIVE_EXPLICIT_USER_INTERACTION_AUDIO, MODE_FOREGROUND));
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
        assertEquals(MODE_IGNORED,
                mIntf.evalMode(UID, OP_RECEIVE_EXPLICIT_USER_INTERACTION_AUDIO, MODE_FOREGROUND));
    }

    @Test
    public void testForegroundNotCapabilitiesTracked() {
        procStateBuilder(UID)
                .topState()
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
                .topState()
                .update();
        assertForeground(UID);
    }

    @Test
    public void testForegroundToBackgroundTransition() {
        procStateBuilder(UID)
                .topState()
                .update();
        assertForeground(UID);

        procStateBuilder(UID)
                .backgroundState()
                .update();
        // Still in foreground due to settle time
        assertForeground(UID);

        mClock.advanceTime(mConstants.TOP_STATE_SETTLE_TIME - 1);
        assertForeground(UID);

        mClock.advanceTime(1);
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

        mClock.advanceTime(mConstants.FG_SERVICE_STATE_SETTLE_TIME - 1);
        assertForeground(UID);

        mClock.advanceTime(1);
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

        // 1 ms short of settle time
        mClock.advanceTime(mConstants.FG_SERVICE_STATE_SETTLE_TIME - 1);
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
        assertEquals(MODE_ALLOWED,
                mIntf.evalMode(UID, OP_RECEIVE_EXPLICIT_USER_INTERACTION_AUDIO, MODE_FOREGROUND));
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
        assertEquals(MODE_IGNORED,
                mIntf.evalMode(UID, OP_RECEIVE_EXPLICIT_USER_INTERACTION_AUDIO, MODE_FOREGROUND));
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
        assertEquals(MODE_ALLOWED,
                mIntf.evalMode(UID, OP_RECEIVE_EXPLICIT_USER_INTERACTION_AUDIO, MODE_FOREGROUND));
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
        assertEquals(MODE_ALLOWED,
                mIntf.evalMode(UID, OP_RECEIVE_EXPLICIT_USER_INTERACTION_AUDIO, MODE_FOREGROUND));
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
        assertEquals(MODE_ALLOWED,
                mIntf.evalMode(UID, OP_RECEIVE_EXPLICIT_USER_INTERACTION_AUDIO, MODE_FOREGROUND));
    }

    @Test
    public void testUidStateChangedCallbackNewProcessTop() {
        UidStateChangedCallback cb = addUidStateChangeCallback();

        procStateBuilder(UID)
                .topState()
                .update();

        verify(cb).onUidStateChanged(eq(UID), eq(UID_STATE_TOP), eq(true));
    }

    @Test
    public void testUidStateChangedCallbackNewProcessForegroundService() {
        UidStateChangedCallback cb = addUidStateChangeCallback();

        procStateBuilder(UID)
                .foregroundServiceState()
                .update();

        verify(cb).onUidStateChanged(eq(UID), eq(UID_STATE_FOREGROUND_SERVICE), eq(true));
    }

    @Test
    public void testUidStateChangedCallbackNewProcessForeground() {
        UidStateChangedCallback cb = addUidStateChangeCallback();

        procStateBuilder(UID)
                .foregroundState()
                .update();

        verify(cb).onUidStateChanged(eq(UID), eq(UID_STATE_FOREGROUND), eq(true));
    }

    @Test
    public void testUidStateChangedCallbackNewProcessBackground() {
        UidStateChangedCallback cb = addUidStateChangeCallback();

        procStateBuilder(UID)
                .backgroundState()
                .update();

        verify(cb).onUidStateChanged(eq(UID), eq(UID_STATE_BACKGROUND), eq(false));
    }

    @Test
    public void testUidStateChangedCallbackNewProcessCached() {
        UidStateChangedCallback cb = addUidStateChangeCallback();

        procStateBuilder(UID)
                .cachedState()
                .update();

        // Cached is the default, no change in uid state.
        verify(cb, times(0)).onUidStateChanged(anyInt(), anyInt(), anyBoolean());
    }

    @Test
    public void testUidStateChangedCallbackCachedToBackground() {
        testUidStateChangedCallback(
                ActivityManager.PROCESS_STATE_CACHED_ACTIVITY,
                ActivityManager.PROCESS_STATE_RECEIVER);
    }

    @Test
    public void testUidStateChangedCallbackCachedToForeground() {
        testUidStateChangedCallback(
                ActivityManager.PROCESS_STATE_CACHED_ACTIVITY,
                ActivityManager.PROCESS_STATE_BOUND_TOP);
    }

    @Test
    public void testUidStateChangedCallbackCachedToForegroundService() {
        testUidStateChangedCallback(
                ActivityManager.PROCESS_STATE_CACHED_ACTIVITY,
                ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE);
    }

    @Test
    public void testUidStateChangedCallbackCachedToTop() {
        testUidStateChangedCallback(
                ActivityManager.PROCESS_STATE_CACHED_ACTIVITY,
                ActivityManager.PROCESS_STATE_TOP);
    }

    @Test
    public void testUidStateChangedCallbackBackgroundToCached() {
        testUidStateChangedCallback(
                ActivityManager.PROCESS_STATE_RECEIVER,
                ActivityManager.PROCESS_STATE_CACHED_ACTIVITY);
    }

    @Test
    public void testUidStateChangedCallbackBackgroundToForeground() {
        testUidStateChangedCallback(
                ActivityManager.PROCESS_STATE_RECEIVER,
                ActivityManager.PROCESS_STATE_BOUND_TOP);
    }

    @Test
    public void testUidStateChangedCallbackBackgroundToForegroundService() {
        testUidStateChangedCallback(
                ActivityManager.PROCESS_STATE_RECEIVER,
                ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE);
    }

    @Test
    public void testUidStateChangedCallbackBackgroundToTop() {
        testUidStateChangedCallback(
                ActivityManager.PROCESS_STATE_RECEIVER,
                ActivityManager.PROCESS_STATE_TOP);
    }

    @Test
    public void testUidStateChangedCallbackForegroundToCached() {
        testUidStateChangedCallback(
                ActivityManager.PROCESS_STATE_BOUND_TOP,
                ActivityManager.PROCESS_STATE_CACHED_ACTIVITY);
    }

    @Test
    public void testUidStateChangedCallbackForegroundToBackground() {
        testUidStateChangedCallback(
                ActivityManager.PROCESS_STATE_BOUND_TOP,
                ActivityManager.PROCESS_STATE_RECEIVER);
    }

    @Test
    public void testUidStateChangedCallbackForegroundToForegroundService() {
        testUidStateChangedCallback(
                ActivityManager.PROCESS_STATE_BOUND_TOP,
                ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE);
    }

    @Test
    public void testUidStateChangedCallbackForegroundToTop() {
        testUidStateChangedCallback(
                ActivityManager.PROCESS_STATE_BOUND_TOP,
                ActivityManager.PROCESS_STATE_TOP);
    }

    @Test
    public void testUidStateChangedCallbackForegroundServiceToCached() {
        testUidStateChangedCallback(
                ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE,
                ActivityManager.PROCESS_STATE_CACHED_ACTIVITY);
    }

    @Test
    public void testUidStateChangedCallbackForegroundServiceToBackground() {
        testUidStateChangedCallback(
                ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE,
                ActivityManager.PROCESS_STATE_RECEIVER);
    }

    @Test
    public void testUidStateChangedCallbackForegroundServiceToForeground() {
        testUidStateChangedCallback(
                ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE,
                ActivityManager.PROCESS_STATE_BOUND_TOP);
    }

    @Test
    public void testUidStateChangedCallbackForegroundServiceToTop() {
        testUidStateChangedCallback(
                ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE,
                ActivityManager.PROCESS_STATE_TOP);
    }

    @Test
    public void testUidStateChangedCallbackTopToCached() {
        testUidStateChangedCallback(
                ActivityManager.PROCESS_STATE_TOP,
                ActivityManager.PROCESS_STATE_CACHED_ACTIVITY);
    }

    @Test
    public void testUidStateChangedCallbackTopToBackground() {
        testUidStateChangedCallback(
                ActivityManager.PROCESS_STATE_TOP,
                ActivityManager.PROCESS_STATE_RECEIVER);
    }

    @Test
    public void testUidStateChangedCallbackTopToForeground() {
        testUidStateChangedCallback(
                ActivityManager.PROCESS_STATE_TOP,
                ActivityManager.PROCESS_STATE_BOUND_TOP);
    }

    @Test
    public void testUidStateChangedCallbackTopToForegroundService() {
        testUidStateChangedCallback(
                ActivityManager.PROCESS_STATE_TOP,
                ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE);
    }

    @Test
    public void testUidStateChangedCallbackCachedToNonexistent() {
        UidStateChangedCallback cb = addUidStateChangeCallback();

        procStateBuilder(UID)
                .cachedState()
                .update();

        procStateBuilder(UID)
                .nonExistentState()
                .update();

        verify(cb, never()).onUidStateChanged(anyInt(), anyInt(), anyBoolean());
    }

    @Test
    public void testUidStateChangedCallbackBackgroundToNonexistent() {
        UidStateChangedCallback cb = addUidStateChangeCallback();

        procStateBuilder(UID)
                .backgroundState()
                .update();

        procStateBuilder(UID)
                .nonExistentState()
                .update();

        verify(cb, atLeastOnce()).onUidStateChanged(eq(UID), eq(UID_STATE_CACHED), eq(false));
    }

    @Test
    public void testUidStateChangedCallbackForegroundToNonexistent() {
        UidStateChangedCallback cb = addUidStateChangeCallback();

        procStateBuilder(UID)
                .foregroundState()
                .update();

        procStateBuilder(UID)
                .nonExistentState()
                .update();

        verify(cb, atLeastOnce()).onUidStateChanged(eq(UID), eq(UID_STATE_CACHED), eq(true));
    }

    @Test
    public void testUidStateChangedCallbackForegroundServiceToNonexistent() {
        UidStateChangedCallback cb = addUidStateChangeCallback();

        procStateBuilder(UID)
                .foregroundServiceState()
                .update();

        procStateBuilder(UID)
                .nonExistentState()
                .update();

        verify(cb, atLeastOnce()).onUidStateChanged(eq(UID), eq(UID_STATE_CACHED), eq(true));
    }

    @Test
    public void testUidStateChangedCallbackTopToNonexistent() {
        UidStateChangedCallback cb = addUidStateChangeCallback();

        procStateBuilder(UID)
                .topState()
                .update();

        procStateBuilder(UID)
                .nonExistentState()
                .update();

        verify(cb, atLeastOnce()).onUidStateChanged(eq(UID), eq(UID_STATE_CACHED), eq(true));
    }

    @Test
    public void testUidStateChangedBackgroundThenForegroundImmediately() {
        procStateBuilder(UID)
            .topState()
            .update();

        UidStateChangedCallback cb = addUidStateChangeCallback();

        procStateBuilder(UID)
            .backgroundState()
            .update();

        mClock.advanceTime(mConstants.TOP_STATE_SETTLE_TIME - 1);

        procStateBuilder(UID)
            .topState()
            .update();

        mClock.advanceTime(1);

        verify(cb, never()).onUidStateChanged(anyInt(), anyInt(), anyBoolean());
    }

    @Test
    public void testIsUidInForegroundForBackgroundState() {
        procStateBuilder(UID)
                .backgroundState()
                .update();
        assertFalse(mIntf.isUidInForeground(UID));

        procStateBuilder(UID)
                .nonExistentState()
                .update();
        assertFalse(mIntf.isUidInForeground(UID));
    }

    @Test
    public void testIsUidInForegroundForForegroundState() {
        procStateBuilder(UID)
                .topState()
                .update();
        assertTrue(mIntf.isUidInForeground(UID));

        procStateBuilder(UID)
                .foregroundServiceState()
                .update();
        assertTrue(mIntf.isUidInForeground(UID));
    }

    @Test
    public void testAppWidgetVisibleDoesntChangeUidState() {
        procStateBuilder(UID)
                .topState()
                .update();

        SparseArray<String> updatedAppWidgetVisibilities = new SparseArray<>();
        updatedAppWidgetVisibilities.put(UID, "");

        mIntf.updateAppWidgetVisibility(updatedAppWidgetVisibilities, true);

        assertEquals(UID_STATE_TOP, mIntf.getUidState(UID));
    }

    @Test
    public void testAppWidgetNotVisibleDoesntChangeUidState() {
        SparseArray<String> updatedAppWidgetVisibilities = new SparseArray<>();
        updatedAppWidgetVisibilities.put(UID, "");
        mIntf.updateAppWidgetVisibility(updatedAppWidgetVisibilities, true);
        procStateBuilder(UID)
                .topState()
                .update();

        mIntf.updateAppWidgetVisibility(updatedAppWidgetVisibilities, false);

        assertEquals(UID_STATE_TOP, mIntf.getUidState(UID));
    }

    public void testUidStateChangedCallback(int initialState, int finalState) {
        int initialUidState = processStateToUidState(initialState);
        int finalUidState = processStateToUidState(finalState);
        boolean foregroundChange = initialUidState <= UID_STATE_MAX_LAST_NON_RESTRICTED
                        != finalUidState <= UID_STATE_MAX_LAST_NON_RESTRICTED;
        boolean finalUidStateIsBackgroundAndLessImportant =
                finalUidState > UID_STATE_MAX_LAST_NON_RESTRICTED
                        && finalUidState > initialUidState;

        UidStateChangedCallback cb = addUidStateChangeCallback();

        procStateBuilder(UID)
                .setState(initialState)
                .update();

        procStateBuilder(UID)
                .setState(finalState)
                .update();

        if (finalUidStateIsBackgroundAndLessImportant) {
            mClock.advanceTime(mConstants.TOP_STATE_SETTLE_TIME + 1);
        }

        verify(cb, atLeastOnce())
                .onUidStateChanged(eq(UID), eq(finalUidState), eq(foregroundChange));
    }

    private UidStateChangedCallback addUidStateChangeCallback() {
        UidStateChangedCallback cb =
                Mockito.mock(UidStateChangedCallback.class);
        mIntf.addUidStateChangedCallback(r -> r.run(), cb);
        return cb;
    }

    /* If testForegroundNotCapabilitiesTracked fails, this assertion is probably incorrect */
    private void assertForeground(int uid) {
        assertEquals(MODE_ALLOWED, mIntf.evalMode(uid, OP_NO_CAPABILITIES, MODE_FOREGROUND));
    }

    /* If testBackgroundNotCapabilitiesTracked fails, this assertion is probably incorrect */
    private void assertBackground(int uid) {
        assertEquals(MODE_IGNORED, mIntf.evalMode(uid, OP_NO_CAPABILITIES, MODE_FOREGROUND));
    }

    private UidProcStateUpdateBuilder procStateBuilder(int uid) {
        return new UidProcStateUpdateBuilder(mIntf, uid);
    }

    private static class UidProcStateUpdateBuilder {
        private AppOpsUidStateTracker mIntf;
        private int mUid;
        private int mProcState = ActivityManager.PROCESS_STATE_NONEXISTENT;
        private int mCapability = ActivityManager.PROCESS_CAPABILITY_NONE;

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

        public UidProcStateUpdateBuilder setState(int procState) {
            mProcState = procState;
            return this;
        }

        public UidProcStateUpdateBuilder topState() {
            mProcState = ActivityManager.PROCESS_STATE_TOP;
            return this;
        }

        public UidProcStateUpdateBuilder foregroundServiceState() {
            mProcState = ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE;
            return this;
        }

        public UidProcStateUpdateBuilder foregroundState() {
            mProcState = ActivityManager.PROCESS_STATE_BOUND_TOP;
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

        public UidProcStateUpdateBuilder nonExistentState() {
            mProcState = ActivityManager.PROCESS_STATE_NONEXISTENT;
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

        private AppOpsUidStateTrackerTestExecutor mExecutor;
        long mElapsedRealTime = 0x5f3759df;

        AppOpsUidStateTrackerTestClock(AppOpsUidStateTrackerTestExecutor executor) {
            mExecutor = executor;
            executor.setUptime(mElapsedRealTime);
        }

        @Override
        public long elapsedRealtime() {
            return mElapsedRealTime;
        }

        void advanceTime(long time) {
            mElapsedRealTime += time;
            mExecutor.setUptime(mElapsedRealTime); // assume uptime == elapsedtime
        }
    }

    private static class AppOpsUidStateTrackerTestExecutor implements DelayableExecutor {

        private static class QueueElement implements Comparable<QueueElement> {

            private long mExecutionTime;
            private Runnable mRunnable;

            private QueueElement(long executionTime, Runnable runnable) {
                mExecutionTime = executionTime;
                mRunnable = runnable;
            }

            @Override
            public int compareTo(QueueElement queueElement) {
                return Long.compare(mExecutionTime, queueElement.mExecutionTime);
            }
        }

        private long mUptime = 0;

        private PriorityQueue<QueueElement> mDelayedMessages = new PriorityQueue();

        @Override
        public void execute(Runnable runnable) {
            runnable.run();
        }

        @Override
        public void executeDelayed(Runnable runnable, long delay) {
            if (delay <= 0) {
                execute(runnable);
            }

            mDelayedMessages.add(new QueueElement(mUptime + delay, runnable));
        }

        private void setUptime(long uptime) {
            while (!mDelayedMessages.isEmpty()
                    && mDelayedMessages.peek().mExecutionTime <= uptime) {
                mDelayedMessages.poll().mRunnable.run();
            }

            mUptime = uptime;
        }
    }
}
