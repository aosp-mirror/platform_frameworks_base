/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.hardware.fingerprint;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.os.Looper;
import android.os.RemoteException;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.List;


@RunWith(MockitoJUnitRunner.class)
public class FingerprintManagerTest {
    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();

    @Mock
    Context mContext;
    @Mock
    IFingerprintService mService;

    @Captor
    ArgumentCaptor<IFingerprintAuthenticatorsRegisteredCallback> mCaptor;

    List<FingerprintSensorPropertiesInternal> mProps;
    FingerprintManager mFingerprintManager;

    @Before
    public void setUp() throws Exception {
        when(mContext.getMainLooper()).thenReturn(Looper.getMainLooper());
        mFingerprintManager = new FingerprintManager(mContext, mService);
        mProps = new ArrayList<>();
        mProps.add(new FingerprintSensorPropertiesInternal(
                0 /* sensorId */,
                FingerprintSensorProperties.STRENGTH_STRONG,
                1 /* maxEnrollmentsPerUser */,
                new ArrayList<>() /* componentInfo */,
                FingerprintSensorProperties.TYPE_UNKNOWN,
                true /* halControlsIllumination */,
                true /* resetLockoutRequiresHardwareAuthToken */,
                new ArrayList<>() /* sensorLocations */));
    }

    @Test
    public void getSensorPropertiesInternal_noBinderCalls() throws RemoteException {
        verify(mService).addAuthenticatorsRegisteredCallback(mCaptor.capture());

        mCaptor.getValue().onAllAuthenticatorsRegistered(mProps);
        List<FingerprintSensorPropertiesInternal> actual =
                mFingerprintManager.getSensorPropertiesInternal();

        assertThat(actual).isEqualTo(mProps);
        verify(mService, never()).getSensorPropertiesInternal(any());
    }
}
