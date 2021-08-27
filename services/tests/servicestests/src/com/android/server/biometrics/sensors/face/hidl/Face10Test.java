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

package com.android.server.biometrics.sensors.face.hidl;

import static junit.framework.Assert.assertEquals;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.hardware.biometrics.ComponentInfoInternal;
import android.hardware.biometrics.SensorProperties;
import android.hardware.face.FaceSensorProperties;
import android.hardware.face.FaceSensorPropertiesInternal;
import android.hardware.face.IFaceServiceReceiver;
import android.os.Binder;
import android.os.IBinder;
import android.os.UserManager;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.server.biometrics.sensors.BiometricScheduler;
import com.android.server.biometrics.sensors.LockoutResetDispatcher;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

@Presubmit
@SmallTest
public class Face10Test {

    private static final String TAG = "Face10Test";
    private static final int SENSOR_ID = 1;
    private static final int USER_ID = 20;

    @Mock
    private Context mContext;
    @Mock
    private UserManager mUserManager;
    @Mock
    private BiometricScheduler mScheduler;

    private LockoutResetDispatcher mLockoutResetDispatcher;
    private com.android.server.biometrics.sensors.face.hidl.Face10 mFace10;
    private IBinder mBinder;

    private static void waitForIdle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mContext.getSystemService(Context.USER_SERVICE)).thenReturn(mUserManager);
        when(mUserManager.getAliveUsers()).thenReturn(new ArrayList<>());

        mLockoutResetDispatcher = new LockoutResetDispatcher(mContext);

        final int maxEnrollmentsPerUser = 1;
        final List<ComponentInfoInternal> componentInfo = new ArrayList<>();
        final boolean supportsFaceDetection = false;
        final boolean supportsSelfIllumination = false;
        final boolean resetLockoutRequiresChallenge = false;
        final FaceSensorPropertiesInternal sensorProps = new FaceSensorPropertiesInternal(SENSOR_ID,
                SensorProperties.STRENGTH_STRONG, maxEnrollmentsPerUser, componentInfo,
                FaceSensorProperties.TYPE_UNKNOWN, supportsFaceDetection, supportsSelfIllumination,
                resetLockoutRequiresChallenge);

        Face10.sSystemClock = Clock.fixed(Instant.ofEpochMilli(100), ZoneId.of("PST"));
        mFace10 = new Face10(mContext, sensorProps, mLockoutResetDispatcher, mScheduler);
        mBinder = new Binder();
    }

    private void tick(long seconds) {
        waitForIdle();
        Face10.sSystemClock = Clock.fixed(Instant.ofEpochSecond(
                Face10.sSystemClock.instant().getEpochSecond() + seconds),
                ZoneId.of("PST"));
    }

    @Test
    public void getAuthenticatorId_doesNotCrashWhenIdNotFound() {
        assertEquals(0, mFace10.getAuthenticatorId(0 /* sensorId */, 111 /* userId */));
        waitForIdle();
    }

    @Test
    public void scheduleRevokeChallenge_doesNotCrash() {
        mFace10.scheduleRevokeChallenge(0 /* sensorId */, 0 /* userId */, mBinder, TAG,
                0 /* challenge */);
        waitForIdle();
    }

    @Test
    public void scheduleGenerateChallenge_cachesResult() {
        final IFaceServiceReceiver[] mocks = IntStream.range(0, 3)
                .mapToObj(i -> mock(IFaceServiceReceiver.class))
                .toArray(IFaceServiceReceiver[]::new);
        for (IFaceServiceReceiver mock : mocks) {
            mFace10.scheduleGenerateChallenge(SENSOR_ID, USER_ID, mBinder, mock, TAG);
            tick(10);
        }
        tick(120);
        mFace10.scheduleGenerateChallenge(
                SENSOR_ID, USER_ID, mBinder, mock(IFaceServiceReceiver.class), TAG);
        waitForIdle();

        verify(mScheduler, times(2))
                .scheduleClientMonitor(isA(FaceGenerateChallengeClient.class), any());
    }

    @Test
    public void scheduleRevokeChallenge_waitsUntilEmpty() {
        final long challenge = 22;
        final IFaceServiceReceiver[] mocks = IntStream.range(0, 3)
                .mapToObj(i -> mock(IFaceServiceReceiver.class))
                .toArray(IFaceServiceReceiver[]::new);
        for (IFaceServiceReceiver mock : mocks) {
            mFace10.scheduleGenerateChallenge(SENSOR_ID, USER_ID, mBinder, mock, TAG);
            tick(10);
        }
        for (IFaceServiceReceiver mock : mocks) {
            mFace10.scheduleRevokeChallenge(SENSOR_ID, USER_ID, mBinder, TAG, challenge);
            tick(10);
        }
        waitForIdle();

        verify(mScheduler).scheduleClientMonitor(isA(FaceRevokeChallengeClient.class), any());
    }

    @Test
    public void scheduleRevokeChallenge_doesNotWaitForever() {
        mFace10.scheduleGenerateChallenge(
                SENSOR_ID, USER_ID, mBinder, mock(IFaceServiceReceiver.class), TAG);
        mFace10.scheduleGenerateChallenge(
                SENSOR_ID, USER_ID, mBinder, mock(IFaceServiceReceiver.class), TAG);
        tick(10000);
        mFace10.scheduleGenerateChallenge(
                SENSOR_ID, USER_ID, mBinder, mock(IFaceServiceReceiver.class), TAG);
        mFace10.scheduleRevokeChallenge(
                SENSOR_ID, USER_ID, mBinder, TAG, 8 /* challenge */);
        waitForIdle();

        verify(mScheduler).scheduleClientMonitor(isA(FaceRevokeChallengeClient.class), any());
    }

    @Test
    public void halServiceDied_resetsScheduler() {
        // It's difficult to test the linkToDeath --> serviceDied path, so let's just invoke
        // serviceDied directly.
        mFace10.serviceDied(0 /* cookie */);
        waitForIdle();
        verify(mScheduler).reset();
    }
}
