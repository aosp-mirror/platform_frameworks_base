/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.os.vibrator;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.content.res.Resources;

import com.android.internal.R;

import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.HashMap;
import java.util.Map;

public class VibrationConfigTest {

    @Rule
    public MockitoRule rule = MockitoJUnit.rule();

    @Mock
    private Resources mResourcesMock;

    private final Map<String, String> mSystemProperties = new HashMap<>();

    @Test
    public void getDefaultVibrationAmplitude_returnsConfiguredAmplitude() {
        when(mResourcesMock.getInteger(R.integer.config_defaultVibrationAmplitude)).thenReturn(1);
        assertThat(createConfig().getDefaultVibrationAmplitude()).isEqualTo(1);

        when(mResourcesMock.getInteger(R.integer.config_defaultVibrationAmplitude)).thenReturn(123);
        assertThat(createConfig().getDefaultVibrationAmplitude()).isEqualTo(123);

        when(mResourcesMock.getInteger(R.integer.config_defaultVibrationAmplitude)).thenReturn(255);
        assertThat(createConfig().getDefaultVibrationAmplitude()).isEqualTo(255);
    }

    @Test
    public void getDefaultVibrationAmplitude_invalidValue_returnsMaxAmplitude() {
        when(mResourcesMock.getInteger(R.integer.config_defaultVibrationAmplitude)).thenReturn(-1);
        assertThat(createConfig().getDefaultVibrationAmplitude()).isEqualTo(255);

        when(mResourcesMock.getInteger(R.integer.config_defaultVibrationAmplitude)).thenReturn(0);
        assertThat(createConfig().getDefaultVibrationAmplitude()).isEqualTo(255);

        when(mResourcesMock.getInteger(R.integer.config_defaultVibrationAmplitude)).thenReturn(500);
        assertThat(createConfig().getDefaultVibrationAmplitude()).isEqualTo(255);
    }

    @Test
    public void getDefaultVibrationScaleLevelGain_returnsConfiguredGain() {
        mSystemProperties.put(VibrationConfig.SCALE_LEVEL_GAIN_SYSTEM_PROPERTY, "1.2");
        assertThat(createConfig().getDefaultVibrationScaleLevelGain()).isEqualTo(1.2f);

        mSystemProperties.put(VibrationConfig.SCALE_LEVEL_GAIN_SYSTEM_PROPERTY, "2");
        assertThat(createConfig().getDefaultVibrationScaleLevelGain()).isEqualTo(2f);
    }

    @Test
    public void getDefaultVibrationScaleLevelGain_invalidValue_returnsFixedScaleGain() {
        mSystemProperties.put(VibrationConfig.SCALE_LEVEL_GAIN_SYSTEM_PROPERTY, "");
        assertThat(createConfig().getDefaultVibrationScaleLevelGain()).isEqualTo(1.4f);

        mSystemProperties.put(VibrationConfig.SCALE_LEVEL_GAIN_SYSTEM_PROPERTY, "invalid");
        assertThat(createConfig().getDefaultVibrationScaleLevelGain()).isEqualTo(1.4f);

        mSystemProperties.put(VibrationConfig.SCALE_LEVEL_GAIN_SYSTEM_PROPERTY, "-1");
        assertThat(createConfig().getDefaultVibrationScaleLevelGain()).isEqualTo(1.4f);

        mSystemProperties.put(VibrationConfig.SCALE_LEVEL_GAIN_SYSTEM_PROPERTY, "0.5");
        assertThat(createConfig().getDefaultVibrationScaleLevelGain()).isEqualTo(1.4f);

        mSystemProperties.put(VibrationConfig.SCALE_LEVEL_GAIN_SYSTEM_PROPERTY, "1.0");
        assertThat(createConfig().getDefaultVibrationScaleLevelGain()).isEqualTo(1.4f);
    }

    private VibrationConfig createConfig() {
        return new VibrationConfig(mResourcesMock, mSystemProperties::get);
    }
}
