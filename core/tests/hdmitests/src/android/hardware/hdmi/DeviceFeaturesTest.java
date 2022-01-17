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

package android.hardware.hdmi;

import static android.hardware.hdmi.DeviceFeatures.FEATURE_NOT_SUPPORTED;
import static android.hardware.hdmi.DeviceFeatures.FEATURE_SUPPORTED;
import static android.hardware.hdmi.DeviceFeatures.FEATURE_SUPPORT_UNKNOWN;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.filters.SmallTest;

import com.google.common.testing.EqualsTester;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link DeviceFeatures} */
@RunWith(JUnit4.class)
@SmallTest
public class DeviceFeaturesTest {

    @Test
    public void testEquals() {
        new EqualsTester()
                .addEqualityGroup(DeviceFeatures.ALL_FEATURES_SUPPORT_UNKNOWN)
                .addEqualityGroup(DeviceFeatures.NO_FEATURES_SUPPORTED)
                .addEqualityGroup(
                        DeviceFeatures.fromOperand(
                                new byte[]{(byte) 0b0111_0000}),
                        DeviceFeatures.fromOperand(
                                new byte[]{(byte) 0b1111_0000}),
                        DeviceFeatures.fromOperand(
                                new byte[]{(byte) 0b1111_0000, (byte) 0b0101_0101}),
                        DeviceFeatures.ALL_FEATURES_SUPPORT_UNKNOWN.toBuilder()
                                .setRecordTvScreenSupport(FEATURE_SUPPORTED)
                                .setSetOsdStringSupport(FEATURE_SUPPORTED)
                                .setDeckControlSupport(FEATURE_SUPPORTED)
                                .setSetAudioRateSupport(FEATURE_NOT_SUPPORTED)
                                .setArcTxSupport(FEATURE_NOT_SUPPORTED)
                                .setArcRxSupport(FEATURE_NOT_SUPPORTED)
                                .setSetAudioVolumeLevelSupport(FEATURE_NOT_SUPPORTED)
                                .build()
                )
                .testEquals();
    }

    @Test
    public void testDeviceFeaturesOperandConversion() {
        DeviceFeatures info = DeviceFeatures.fromOperand(
                new byte[]{(byte) 0b0111_0000});

        assertThat(info.getRecordTvScreenSupport()).isEqualTo(FEATURE_SUPPORTED);
        assertThat(info.getSetOsdStringSupport()).isEqualTo(FEATURE_SUPPORTED);
        assertThat(info.getDeckControlSupport()).isEqualTo(FEATURE_SUPPORTED);
        assertThat(info.getSetAudioRateSupport()).isEqualTo(FEATURE_NOT_SUPPORTED);
        assertThat(info.getArcTxSupport()).isEqualTo(FEATURE_NOT_SUPPORTED);
        assertThat(info.getArcRxSupport()).isEqualTo(FEATURE_NOT_SUPPORTED);
        assertThat(info.getSetAudioVolumeLevelSupport()).isEqualTo(FEATURE_NOT_SUPPORTED);

        assertThat(info.toOperand()).isEqualTo(new byte[]{(byte) 0b0111_0000});
    }

    @Test
    public void testUpdate() {
        DeviceFeatures oldFeatures = DeviceFeatures.ALL_FEATURES_SUPPORT_UNKNOWN.toBuilder()
                .setRecordTvScreenSupport(FEATURE_SUPPORTED)
                .setSetOsdStringSupport(FEATURE_SUPPORTED)
                .setDeckControlSupport(FEATURE_NOT_SUPPORTED)
                .setSetAudioRateSupport(FEATURE_NOT_SUPPORTED)
                .setArcTxSupport(FEATURE_SUPPORT_UNKNOWN)
                .setArcRxSupport(FEATURE_SUPPORT_UNKNOWN)
                .setSetAudioVolumeLevelSupport(FEATURE_SUPPORT_UNKNOWN)
                .build();

        DeviceFeatures newFeatures = DeviceFeatures.ALL_FEATURES_SUPPORT_UNKNOWN.toBuilder()
                .setRecordTvScreenSupport(FEATURE_NOT_SUPPORTED)
                .setSetOsdStringSupport(FEATURE_SUPPORT_UNKNOWN)
                .setDeckControlSupport(FEATURE_SUPPORTED)
                .setSetAudioRateSupport(FEATURE_SUPPORT_UNKNOWN)
                .setArcTxSupport(FEATURE_SUPPORTED)
                .setArcRxSupport(FEATURE_NOT_SUPPORTED)
                .setSetAudioVolumeLevelSupport(FEATURE_SUPPORT_UNKNOWN)
                .build();

        // Always take the field from newFeatures, unless it's FEATURE_SUPPORT_UNKNOWN
        DeviceFeatures updatedFeatures = DeviceFeatures.ALL_FEATURES_SUPPORT_UNKNOWN.toBuilder()
                .setRecordTvScreenSupport(FEATURE_NOT_SUPPORTED)
                .setSetOsdStringSupport(FEATURE_SUPPORTED)
                .setDeckControlSupport(FEATURE_SUPPORTED)
                .setSetAudioRateSupport(FEATURE_NOT_SUPPORTED)
                .setArcTxSupport(FEATURE_SUPPORTED)
                .setArcRxSupport(FEATURE_NOT_SUPPORTED)
                .setSetAudioVolumeLevelSupport(FEATURE_SUPPORT_UNKNOWN)
                .build();

        assertThat(oldFeatures.toBuilder().update(newFeatures).build()).isEqualTo(updatedFeatures);
    }
}
