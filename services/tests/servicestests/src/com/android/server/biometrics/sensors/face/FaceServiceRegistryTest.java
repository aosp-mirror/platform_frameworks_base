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

package com.android.server.biometrics.sensors.face;

import static android.hardware.biometrics.BiometricAuthenticator.TYPE_FACE;
import static android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG;
import static android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK;
import static android.hardware.biometrics.SensorProperties.STRENGTH_STRONG;
import static android.hardware.biometrics.SensorProperties.STRENGTH_WEAK;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.biometrics.IBiometricService;
import android.hardware.face.FaceSensorProperties;
import android.hardware.face.FaceSensorPropertiesInternal;
import android.hardware.face.IFaceAuthenticatorsRegisteredCallback;
import android.hardware.face.IFaceService;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.List;

@Presubmit
@SmallTest
public class FaceServiceRegistryTest {

    private static final int SENSOR_ID_1 = 1;
    private static final int SENSOR_ID_2 = 2;

    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();

    @Mock
    private IBiometricService mBiometricService;
    @Mock
    private IFaceService mFaceService;
    @Mock
    private ServiceProvider mProvider1;
    @Mock
    private ServiceProvider mProvider2;
    @Captor
    private ArgumentCaptor<Integer> mIdCaptor;
    @Captor
    private ArgumentCaptor<Integer> mStrengthCaptor;

    private FaceSensorPropertiesInternal mProvider1Props;
    private FaceSensorPropertiesInternal mProvider2Props;
    private FaceServiceRegistry mRegistry;

    @Before
    public void setup() {
        mProvider1Props = new FaceSensorPropertiesInternal(SENSOR_ID_1,
                STRENGTH_WEAK, 5 /* maxEnrollmentsPerUser */,
                List.of(), FaceSensorProperties.TYPE_RGB,
                true /* supportsFace Detection */,
                true /* supportsSelfIllumination */,
                false /* resetLockoutRequiresHardwareAuthToken */);
        mProvider2Props = new FaceSensorPropertiesInternal(SENSOR_ID_2,
                STRENGTH_STRONG, 5 /* maxEnrollmentsPerUser */,
                List.of(), FaceSensorProperties.TYPE_IR,
                true /* supportsFace Detection */,
                true /* supportsSelfIllumination */,
                false /* resetLockoutRequiresHardwareAuthToken */);

        when(mProvider1.getSensorProperties()).thenReturn(List.of(mProvider1Props));
        when(mProvider1.containsSensor(eq(SENSOR_ID_1))).thenReturn(true);
        when(mProvider2.getSensorProperties()).thenReturn(List.of(mProvider2Props));
        when(mProvider2.containsSensor(eq(SENSOR_ID_2))).thenReturn(true);
        mRegistry = new FaceServiceRegistry(mFaceService, () -> mBiometricService);
    }

    @Test
    public void registersAllProviders() throws Exception {
        mRegistry.registerAllInBackground(() -> List.of(mProvider1, mProvider2));

        assertThat(mRegistry.getProviders()).containsExactly(mProvider1, mProvider2);
        assertThat(mRegistry.getAllProperties()).containsExactly(mProvider1Props, mProvider2Props);
        verify(mBiometricService, times(2)).registerAuthenticator(
                mIdCaptor.capture(), eq(TYPE_FACE), mStrengthCaptor.capture(), any());
        assertThat(mIdCaptor.getAllValues()).containsExactly(SENSOR_ID_1, SENSOR_ID_2);
        assertThat(mStrengthCaptor.getAllValues())
                .containsExactly(BIOMETRIC_WEAK, BIOMETRIC_STRONG);
    }

    @Test
    public void getsProviderById() {
        mRegistry.registerAllInBackground(() -> List.of(mProvider1, mProvider2));

        assertThat(mRegistry.getProviderForSensor(SENSOR_ID_1)).isSameInstanceAs(mProvider1);
        assertThat(mRegistry.getProviderForSensor(SENSOR_ID_2)).isSameInstanceAs(mProvider2);
        assertThat(mRegistry.getProviderForSensor(500)).isNull();
    }

    @Test
    public void getsSingleProvider() {
        mRegistry.registerAllInBackground(() -> List.of(mProvider1));

        assertThat(mRegistry.getSingleProvider().second).isSameInstanceAs(mProvider1);
        assertThat(mRegistry.getProviders()).containsExactly(mProvider1);
        assertThat(mRegistry.getProviderForSensor(SENSOR_ID_1)).isSameInstanceAs(mProvider1);
    }

    @Test
    public void getSingleProviderFindsFirstWhenMultiple() {
        mRegistry.registerAllInBackground(() -> List.of(mProvider1, mProvider2));

        assertThat(mRegistry.getSingleProvider().second).isSameInstanceAs(mProvider1);
    }

    @Test
    public void registersListenerBeforeAllRegistered() {
        final List<FaceSensorPropertiesInternal> all = new ArrayList<>();
        mRegistry.addAllRegisteredCallback(new IFaceAuthenticatorsRegisteredCallback.Stub() {
            @Override
            public void onAllAuthenticatorsRegistered(
                    List<FaceSensorPropertiesInternal> sensors) {
                all.addAll(sensors);
            }
        });

        mRegistry.registerAllInBackground(() -> List.of(mProvider1, mProvider2));

        assertThat(all).containsExactly(mProvider1Props, mProvider2Props);
    }

    @Test
    public void registersListenerAfterAllRegistered() {
        mRegistry.registerAllInBackground(() -> List.of(mProvider1, mProvider2));

        final List<FaceSensorPropertiesInternal> all = new ArrayList<>();
        mRegistry.addAllRegisteredCallback(new IFaceAuthenticatorsRegisteredCallback.Stub() {
            @Override
            public void onAllAuthenticatorsRegistered(
                    List<FaceSensorPropertiesInternal> sensors) {
                all.addAll(sensors);
            }
        });

        assertThat(all).containsExactly(mProvider1Props, mProvider2Props);
    }
}
