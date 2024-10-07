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

package com.android.server.biometrics.sensors;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.content.Context;
import android.hardware.biometrics.BiometricConstants;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;

import androidx.annotation.NonNull;
import androidx.test.filters.SmallTest;

import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.function.Supplier;

@Presubmit
@SmallTest
public class AcquisitionClientTest {

    private static final int TEST_SENSOR_ID = 100;

    @Mock
    private Context mContext;
    @Mock
    private IBinder mToken;
    @Mock
    private ClientMonitorCallbackConverter mClientCallback;
    @Mock
    private ClientMonitorCallback mSchedulerCallback;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testUserCanceled() throws Exception {
        // Start an AcquisitionClient
        final TestAcquisitionClient client = new TestAcquisitionClient(mContext, Object::new,
                mToken, mClientCallback);
        client.start(mSchedulerCallback);
        assertTrue(client.mHalOperationRunning);
        verify(mClientCallback).getModality();
        verify(mSchedulerCallback).onClientStarted(eq(client));

        // Pretend that it got canceled by the user.
        client.onUserCanceled();
        verify(mSchedulerCallback, never()).onClientFinished(any(), anyBoolean());
        verify(mClientCallback).onError(eq(TEST_SENSOR_ID),
                anyInt(),
                eq(BiometricConstants.BIOMETRIC_ERROR_USER_CANCELED),
                eq(0) /* vendorCode */);
        assertFalse(client.mHalOperationRunning);

        // Pretend that the HAL responded with ERROR_CANCELED
        client.onError(BiometricConstants.BIOMETRIC_ERROR_CANCELED, 0 /* vendorCode */);
        verifyNoMoreInteractions(mClientCallback);
        verify(mSchedulerCallback).onClientFinished(eq(client), anyBoolean());
    }

    private static class TestAcquisitionClient extends AcquisitionClient<Object> {
        boolean mHalOperationRunning;

        public TestAcquisitionClient(@NonNull Context context,
                @NonNull Supplier<Object> lazyDaemon, @NonNull IBinder token,
                @NonNull ClientMonitorCallbackConverter callback) {
            super(context, lazyDaemon, token, callback, 0 /* userId */, "Test", 0 /* cookie */,
                    TEST_SENSOR_ID /* sensorId */, true /* shouldVibrate */,
                    mock(BiometricLogger.class), mock(BiometricContext.class),
                    false /* isMandatoryBiometrics */);
        }

        @Override
        public void start(@NonNull ClientMonitorCallback callback) {
            super.start(callback);
            startHalOperation();
        }

        @Override
        protected void stopHalOperation() {
            mHalOperationRunning = false;
        }

        @Override
        protected void startHalOperation() {
            mHalOperationRunning = true;
        }

        @Override
        public int getProtoEnum() {
            return 0;
        }
    }
}
