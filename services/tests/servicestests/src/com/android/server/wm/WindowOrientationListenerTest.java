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
import android.view.Surface;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link com.android.server.wm.WindowOrientationListener}
 */
public class WindowOrientationListenerTest {

    @Mock
    private Context mMockContext;
    @Mock
    private Handler mMockHandler;
    @Mock
    private InputSensorInfo mMockInputSensorInfo;
    @Mock
    private SensorManager mMockSensorManager;
    @Mock
    private WindowManagerService mMockWindowManagerService;

    private TestableRotationResolver mFakeRotationResolverInternal;
    private com.android.server.wm.WindowOrientationListener mWindowOrientationListener;
    private int mFinalizedRotation;
    private boolean mRotationResolverEnabled;
    private SensorEvent mFakeSensorEvent;
    private Sensor mFakeSensor;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mRotationResolverEnabled = true;

        mFakeRotationResolverInternal = new TestableRotationResolver();
        doReturn(mMockSensorManager).when(mMockContext).getSystemService(Context.SENSOR_SERVICE);
        mWindowOrientationListener = new TestableWindowOrientationListener(mMockContext,
                mMockHandler, mMockWindowManagerService);
        mWindowOrientationListener.mRotationResolverService = mFakeRotationResolverInternal;

        mFakeSensor = new Sensor(mMockInputSensorInfo);
        mFakeSensorEvent = new SensorEvent(mFakeSensor, /* accuracy */ 1, /* timestamp */ 1L,
                new float[]{(float) Surface.ROTATION_90});
    }

    @Test
    public void testOnSensorChanged_rotationResolverDisabled_useSensorResult() {
        mRotationResolverEnabled = false;

        mWindowOrientationListener.mOrientationJudge.onSensorChanged(mFakeSensorEvent);

        assertThat(mFinalizedRotation).isEqualTo(Surface.ROTATION_90);
    }

    @Test
    public void testOnSensorChanged_normalCase() {
        mFakeRotationResolverInternal.mResult = Surface.ROTATION_180;

        mWindowOrientationListener.mOrientationJudge.onSensorChanged(mFakeSensorEvent);

        assertThat(mFinalizedRotation).isEqualTo(Surface.ROTATION_180);
    }

    final class TestableRotationResolver extends RotationResolverInternal {
        @Surface.Rotation
        int mResult;

        @Override
        public boolean isRotationResolverSupported() {
            return true;
        }

        @Override
        public void resolveRotation(@NonNull RotationResolverCallbackInternal callback,
                @Surface.Rotation int proposedRotation, @Surface.Rotation int currentRotation,
                @DurationMillisLong long timeoutMillis,
                @NonNull CancellationSignal cancellationSignal) {
            callback.onSuccess(mResult);
        }
    }

    final class TestableWindowOrientationListener extends WindowOrientationListener {

        TestableWindowOrientationListener(Context context, Handler handler,
                WindowManagerService service) {
            super(context, handler, service);
            this.mOrientationJudge = new OrientationSensorJudge();
        }

        @Override
        public void onProposedRotationChanged(int rotation) {
            mFinalizedRotation = rotation;
        }

        @Override
        public boolean isRotationResolverEnabled() {
            return mRotationResolverEnabled;
        }
    }
}
