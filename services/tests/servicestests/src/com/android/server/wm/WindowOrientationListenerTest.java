/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.wm;


import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.doReturn;

import android.annotation.DurationMillisLong;
import android.annotation.NonNull;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;
import android.hardware.input.InputSensorInfo;
import android.os.CancellationSignal;
import android.os.Handler;
import android.rotationresolver.RotationResolverInternal;
import android.service.rotationresolver.RotationResolverService;
import android.view.Surface;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link com.android.server.wm.WindowOrientationListener}
 */
public class WindowOrientationListenerTest {
    private static final int DEFAULT_SENSOR_ROTATION = Surface.ROTATION_90;

    @Mock
    private Context mMockContext;
    @Mock
    private InputSensorInfo mMockInputSensorInfo;
    @Mock
    private SensorManager mMockSensorManager;
    private TestableRotationResolver mFakeRotationResolverInternal;
    private TestableWindowOrientationListener mWindowOrientationListener;
    private int mFinalizedRotation;
    private boolean mRotationResolverEnabled;
    private SensorEvent mFakeSensorEvent;
    private Sensor mFakeSensor;
    private Handler mHandler;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mRotationResolverEnabled = true;
        mHandler = Handler.getMain();

        mFakeRotationResolverInternal = new TestableRotationResolver();
        doReturn(mMockSensorManager).when(mMockContext).getSystemService(Context.SENSOR_SERVICE);
        mWindowOrientationListener = new TestableWindowOrientationListener(mMockContext,
                mHandler);
        mWindowOrientationListener.mRotationResolverService = mFakeRotationResolverInternal;
        mWindowOrientationListener.mIsScreenLocked = false;

        mFakeSensor = new Sensor(mMockInputSensorInfo);
        mFakeSensorEvent = new SensorEvent(mFakeSensor, /* accuracy */ 1, /* timestamp */ 1L,
                new float[]{(float) DEFAULT_SENSOR_ROTATION});
    }

    @Test
    public void testOnSensorChanged_rotationResolverDisabled_useSensorResult() {
        mRotationResolverEnabled = false;

        mWindowOrientationListener.mOrientationJudge.onSensorChanged(mFakeSensorEvent);

        assertThat(mFinalizedRotation).isEqualTo(DEFAULT_SENSOR_ROTATION);
    }

    @Test
    public void testOnSensorChanged_callbackNotTheLatest_IgnoreResult() {
        mWindowOrientationListener.mOrientationJudge.onSensorChanged(mFakeSensorEvent);
        final RotationResolverInternal.RotationResolverCallbackInternal callback1 =
                mFakeRotationResolverInternal.getCallback();

        mWindowOrientationListener.mOrientationJudge.onSensorChanged(mFakeSensorEvent);
        final RotationResolverInternal.RotationResolverCallbackInternal callback2 =
                mFakeRotationResolverInternal.getCallback();

        callback1.onSuccess(Surface.ROTATION_180);
        assertThat(mWindowOrientationListener.mIsOnProposedRotationChangedCalled).isFalse();

        callback2.onSuccess(Surface.ROTATION_270);
        assertThat(mWindowOrientationListener.mIsOnProposedRotationChangedCalled).isTrue();
        assertThat(mFinalizedRotation).isEqualTo(Surface.ROTATION_270);
    }

    @Test
    public void testOnSensorChanged_normalCase1() {
        mWindowOrientationListener.mOrientationJudge.onSensorChanged(mFakeSensorEvent);

        mFakeRotationResolverInternal.callbackWithSuccessResult(Surface.ROTATION_180);

        assertThat(mFinalizedRotation).isEqualTo(Surface.ROTATION_180);
    }

    @Test
    public void testOnSensorChanged_normalCase2() {
        mWindowOrientationListener.mOrientationJudge.onSensorChanged(mFakeSensorEvent);

        mFakeRotationResolverInternal.callbackWithFailureResult(
                RotationResolverService.ROTATION_RESULT_FAILURE_CANCELLED);

        assertThat(mFinalizedRotation).isEqualTo(DEFAULT_SENSOR_ROTATION);
    }

    @Test
    public void testOnSensorChanged_rotationResolverServiceIsNull_useSensorResult() {
        mWindowOrientationListener.mRotationResolverService = null;

        mWindowOrientationListener.mOrientationJudge.onSensorChanged(mFakeSensorEvent);

        assertThat(mFinalizedRotation).isEqualTo(DEFAULT_SENSOR_ROTATION);
    }

    static final class TestableRotationResolver extends RotationResolverInternal {
        @Surface.Rotation
        RotationResolverCallbackInternal mCallback;

        @Override
        public boolean isRotationResolverSupported() {
            return true;
        }

        @Override
        public void resolveRotation(@NonNull RotationResolverCallbackInternal callback,
                String packageName, @Surface.Rotation int proposedRotation,
                @Surface.Rotation int currentRotation, @DurationMillisLong long timeoutMillis,
                @NonNull CancellationSignal cancellationSignal) {
            mCallback = callback;
        }

        public RotationResolverCallbackInternal getCallback() {
            return mCallback;
        }

        public void callbackWithSuccessResult(int result) {
            if (mCallback != null) {
                mCallback.onSuccess(result);
            }
        }

        public void callbackWithFailureResult(int error) {
            if (mCallback != null) {
                mCallback.onFailure(error);
            }
        }
    }

    @Test
    public void testOnSensorChanged_inLockScreen_doNotCallRotationResolver() {
        mWindowOrientationListener.mIsScreenLocked = true;

        mWindowOrientationListener.mOrientationJudge.onSensorChanged(mFakeSensorEvent);

        assertThat(mWindowOrientationListener.mIsOnProposedRotationChangedCalled).isFalse();
    }

    final class TestableWindowOrientationListener extends WindowOrientationListener {
        private boolean mIsOnProposedRotationChangedCalled = false;
        private boolean mIsScreenLocked;

        TestableWindowOrientationListener(Context context, Handler handler) {
            super(context, handler);
            this.mOrientationJudge = new OrientationSensorJudge();
        }

        @Override
        public boolean isKeyguardShowingAndNotOccluded() {
            return mIsScreenLocked;
        }

        @Override
        public void onProposedRotationChanged(int rotation) {
            mFinalizedRotation = rotation;
            mIsOnProposedRotationChangedCalled = true;
        }

        @Override
        public boolean isRotationResolverEnabled() {
            return mRotationResolverEnabled;
        }
    }
}
