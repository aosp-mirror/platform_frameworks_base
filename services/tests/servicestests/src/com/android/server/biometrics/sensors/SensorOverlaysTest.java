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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.biometrics.BiometricOverlayConstants;
import android.hardware.fingerprint.ISidefpsController;
import android.hardware.fingerprint.IUdfpsOverlayController;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.List;

@Presubmit
@SmallTest
public class SensorOverlaysTest {

    private static final int SENSOR_ID = 11;
    private static final long REQUEST_ID = 8;

    @Rule public final MockitoRule mockito = MockitoJUnit.rule();

    @Mock private IUdfpsOverlayController mUdfpsOverlayController;
    @Mock private ISidefpsController mSidefpsController;
    @Mock private AcquisitionClient<?> mAcquisitionClient;

    @Before
    public void setup() {
        when(mAcquisitionClient.getRequestId()).thenReturn(REQUEST_ID);
        when(mAcquisitionClient.hasRequestId()).thenReturn(true);
    }

    @Test
    public void noopWhenBothNull() {
        final SensorOverlays useless = new SensorOverlays(null, null);
        useless.show(SENSOR_ID, 2, null);
        useless.hide(SENSOR_ID);
    }

    @Test
    public void testProvidesUdfps() {
        final List<IUdfpsOverlayController> udfps = new ArrayList<>();
        SensorOverlays sensorOverlays = new SensorOverlays(null, mSidefpsController);

        sensorOverlays.ifUdfps(udfps::add);
        assertThat(udfps).isEmpty();

        sensorOverlays = new SensorOverlays(mUdfpsOverlayController, mSidefpsController);
        sensorOverlays.ifUdfps(udfps::add);
        assertThat(udfps).containsExactly(mUdfpsOverlayController);
    }

    @Test
    public void testShow() throws Exception {
        testShow(mUdfpsOverlayController, mSidefpsController);
    }

    @Test
    public void testShowUdfps() throws Exception {
        testShow(mUdfpsOverlayController, null);
    }

    @Test
    public void testShowSidefps() throws Exception {
        testShow(null, mSidefpsController);
    }

    private void testShow(IUdfpsOverlayController udfps, ISidefpsController sidefps)
            throws Exception {
        final SensorOverlays sensorOverlays = new SensorOverlays(udfps, sidefps);
        final int reason = BiometricOverlayConstants.REASON_UNKNOWN;
        sensorOverlays.show(SENSOR_ID, reason, mAcquisitionClient);

        if (udfps != null) {
            verify(mUdfpsOverlayController).showUdfpsOverlay(
                    eq(REQUEST_ID), eq(SENSOR_ID), eq(reason), any());
        }
        if (sidefps != null) {
            verify(mSidefpsController).show(eq(SENSOR_ID), eq(reason));
        }
    }

    @Test
    public void testHide() throws Exception {
        testHide(mUdfpsOverlayController, mSidefpsController);
    }

    @Test
    public void testHideUdfps() throws Exception {
        testHide(mUdfpsOverlayController, null);
    }

    @Test
    public void testHideSidefps() throws Exception {
        testHide(null, mSidefpsController);
    }

    private void testHide(IUdfpsOverlayController udfps, ISidefpsController sidefps)
            throws Exception {
        final SensorOverlays sensorOverlays = new SensorOverlays(udfps, sidefps);
        sensorOverlays.hide(SENSOR_ID);

        if (udfps != null) {
            verify(mUdfpsOverlayController).hideUdfpsOverlay(eq(SENSOR_ID));
        }
        if (sidefps != null) {
            verify(mSidefpsController).hide(eq(SENSOR_ID));
        }
    }
}
