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

package com.android.server.display;


import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public final class DisplayDeviceConfigTest {
    private DisplayDeviceConfig mDisplayDeviceConfig;
    @Mock
    private Context mContext;

    @Mock
    private Resources mResources;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mContext.getResources()).thenReturn(mResources);
        mockDeviceConfigs();
    }

    @Test
    public void testConfigValuesFromDisplayConfig() throws IOException {
        setupDisplayDeviceConfigFromDisplayConfigFile();

        assertEquals(mDisplayDeviceConfig.getAmbientHorizonLong(), 5000);
        assertEquals(mDisplayDeviceConfig.getAmbientHorizonShort(), 50);
        assertEquals(mDisplayDeviceConfig.getBrightnessRampDecreaseMaxMillis(), 3000);
        assertEquals(mDisplayDeviceConfig.getBrightnessRampIncreaseMaxMillis(), 2000);
        assertEquals(mDisplayDeviceConfig.getAmbientLuxBrighteningMinThreshold(), 10.0f, 0.0f);
        assertEquals(mDisplayDeviceConfig.getAmbientLuxDarkeningMinThreshold(), 2.0f, 0.0f);
        assertEquals(mDisplayDeviceConfig.getBrightnessRampFastDecrease(), 0.01f, 0.0f);
        assertEquals(mDisplayDeviceConfig.getBrightnessRampFastIncrease(), 0.02f, 0.0f);
        assertEquals(mDisplayDeviceConfig.getBrightnessRampSlowIncrease(), 0.04f, 0.0f);
        assertEquals(mDisplayDeviceConfig.getBrightnessRampSlowDecrease(), 0.03f, 0.0f);
        assertEquals(mDisplayDeviceConfig.getBrightnessDefault(), 0.5f, 0.0f);
        assertArrayEquals(mDisplayDeviceConfig.getBrightness(), new float[]{0.0f, 0.62f, 1.0f},
                0.0f);
        assertArrayEquals(mDisplayDeviceConfig.getNits(), new float[]{2.0f, 500.0f, 800.0f}, 0.0f);
        assertArrayEquals(mDisplayDeviceConfig.getBacklight(), new float[]{0.0f, 0.62f, 1.0f},
                0.0f);
        assertEquals(mDisplayDeviceConfig.getScreenBrighteningMinThreshold(), 0.001, 0.000001f);
        assertEquals(mDisplayDeviceConfig.getScreenDarkeningMinThreshold(), 0.002, 0.000001f);
        assertEquals(mDisplayDeviceConfig.getAutoBrightnessBrighteningLightDebounce(), 2000);
        assertEquals(mDisplayDeviceConfig.getAutoBrightnessDarkeningLightDebounce(), 1000);
        assertArrayEquals(mDisplayDeviceConfig.getAutoBrightnessBrighteningLevelsLux(), new
                float[]{0.0f, 50.0f, 80.0f}, 0.0f);
        assertArrayEquals(mDisplayDeviceConfig.getAutoBrightnessBrighteningLevelsNits(), new
                float[]{45.32f, 75.43f}, 0.0f);
        // Todo(brup): Add asserts for BrightnessThrottlingData, DensityMapping,
        // HighBrightnessModeData AmbientLightSensor, RefreshRateLimitations and ProximitySensor.
    }

    @Test
    public void testConfigValuesFromConfigResource() {
        setupDisplayDeviceConfigFromConfigResourceFile();
        assertArrayEquals(mDisplayDeviceConfig.getAutoBrightnessBrighteningLevelsNits(), new
                float[]{2.0f, 200.0f, 600.0f}, 0.0f);
        assertArrayEquals(mDisplayDeviceConfig.getAutoBrightnessBrighteningLevelsLux(), new
                float[]{0.0f, 0.0f, 110.0f, 500.0f}, 0.0f);
        // Todo(brup): Add asserts for BrightnessThrottlingData, DensityMapping,
        // HighBrightnessModeData AmbientLightSensor, RefreshRateLimitations and ProximitySensor.
    }

    private String getContent() {
        return "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                + "<displayConfiguration>\n"
                +   "<screenBrightnessMap>\n"
                +       "<point>\n"
                +           "<value>0.0</value>\n"
                +           "<nits>2.0</nits>\n"
                +       "</point>\n"
                +       "<point>\n"
                +           "<value>0.62</value>\n"
                +           "<nits>500.0</nits>\n"
                +       "</point>\n"
                +       "<point>\n"
                +           "<value>1.0</value>\n"
                +           "<nits>800.0</nits>\n"
                +       "</point>\n"
                +   "</screenBrightnessMap>\n"
                +   "<autoBrightness>\n"
                +       "<brighteningLightDebounceMillis>2000</brighteningLightDebounceMillis>\n"
                +       "<darkeningLightDebounceMillis>1000</darkeningLightDebounceMillis>\n"
                +       "<displayBrightnessMapping>\n"
                +            "<displayBrightnessPoint>\n"
                +                "<lux>50</lux>\n"
                +                "<nits>45.32</nits>\n"
                +            "</displayBrightnessPoint>\n"
                +            "<displayBrightnessPoint>\n"
                +                "<lux>80</lux>\n"
                +                "<nits>75.43</nits>\n"
                +            "</displayBrightnessPoint>\n"
                +       "</displayBrightnessMapping>\n"
                +   "</autoBrightness>\n"
                +   "<highBrightnessMode enabled=\"true\">\n"
                +       "<transitionPoint>0.62</transitionPoint>\n"
                +       "<minimumLux>10000</minimumLux>\n"
                +       "<timing>\n"
                +           "<!-- allow for 5 minutes out of every 30 minutes -->\n"
                +           "<timeWindowSecs>1800</timeWindowSecs>\n"
                +           "<timeMaxSecs>300</timeMaxSecs>\n"
                +           "<timeMinSecs>60</timeMinSecs>\n"
                +       "</timing>\n"
                +       "<refreshRate>\n"
                +           "<minimum>120</minimum>\n"
                +           "<maximum>120</maximum>\n"
                +       "</refreshRate>\n"
                +       "<thermalStatusLimit>light</thermalStatusLimit>\n"
                +       "<allowInLowPowerMode>false</allowInLowPowerMode>\n"
                +   "</highBrightnessMode>\n"
                +   "<ambientBrightnessChangeThresholds>\n"
                +       "<brighteningThresholds>\n"
                +           "<minimum>10</minimum>\n"
                +       "</brighteningThresholds>\n"
                +       "<darkeningThresholds>\n"
                +           "<minimum>2</minimum>\n"
                +       "</darkeningThresholds>\n"
                +   "</ambientBrightnessChangeThresholds>\n"
                +   "<screenBrightnessRampFastDecrease>0.01</screenBrightnessRampFastDecrease> "
                +   "<screenBrightnessRampFastIncrease>0.02</screenBrightnessRampFastIncrease>  "
                +   "<screenBrightnessRampSlowDecrease>0.03</screenBrightnessRampSlowDecrease>"
                +   "<screenBrightnessRampSlowIncrease>0.04</screenBrightnessRampSlowIncrease>"
                +   "<screenBrightnessRampIncreaseMaxMillis>"
                +       "2000"
                +   "</screenBrightnessRampIncreaseMaxMillis>"
                +   "<screenBrightnessRampDecreaseMaxMillis>"
                +       "3000"
                +   "</screenBrightnessRampDecreaseMaxMillis>"
                +   "<ambientLightHorizonLong>5000</ambientLightHorizonLong>\n"
                +   "<ambientLightHorizonShort>50</ambientLightHorizonShort>\n"
                +   "<displayBrightnessChangeThresholds>"
                +       "<brighteningThresholds>"
                +           "<minimum>"
                +               "0.001"
                +           "</minimum>"
                +       "</brighteningThresholds>"
                +       "<darkeningThresholds>"
                +           "<minimum>"
                +               "0.002"
                +           "</minimum>"
                +       "</darkeningThresholds>"
                +   "</displayBrightnessChangeThresholds>"
                +   "<screenBrightnessRampIncreaseMaxMillis>"
                +       "2000"
                +    "</screenBrightnessRampIncreaseMaxMillis>\n"
                +   "<thermalThrottling>\n"
                +       "<brightnessThrottlingMap>\n"
                +           "<brightnessThrottlingPoint>\n"
                +               "<thermalStatus>emergency</thermalStatus>\n"
                +               "<!-- Throttling to 250 nits: (250-2.0)/(500-2.0)*(0.62-0.0)+0"
                +               ".0 = 0.30875502 -->\n"
                +               "<brightness>0.30875502</brightness>\n"
                +           "</brightnessThrottlingPoint>\n"
                +       "</brightnessThrottlingMap>\n"
                +   "</thermalThrottling>\n"
                + "</displayConfiguration>\n";
    }

    private void mockDeviceConfigs() {
        when(mResources.getFloat(com.android.internal.R.dimen
                .config_screenBrightnessSettingDefaultFloat)).thenReturn(0.5f);
        when(mResources.getFloat(com.android.internal.R.dimen
                .config_screenBrightnessSettingMaximumFloat)).thenReturn(1.0f);
    }

    private void setupDisplayDeviceConfigFromDisplayConfigFile() throws IOException {
        Path tempFile = Files.createTempFile("display_config", ".tmp");
        Files.write(tempFile, getContent().getBytes(StandardCharsets.UTF_8));
        mDisplayDeviceConfig = new DisplayDeviceConfig(mContext);
        mDisplayDeviceConfig.initFromFile(tempFile.toFile());
    }

    private void setupDisplayDeviceConfigFromConfigResourceFile() {
        TypedArray screenBrightnessNits = createFloatTypedArray(new float[]{2.0f, 250.0f, 650.0f});
        when(mResources.obtainTypedArray(
                com.android.internal.R.array.config_screenBrightnessNits))
                .thenReturn(screenBrightnessNits);
        TypedArray screenBrightnessBacklight = createFloatTypedArray(new
                float[]{0.0f, 120.0f, 255.0f});
        when(mResources.obtainTypedArray(
                com.android.internal.R.array.config_screenBrightnessBacklight))
                .thenReturn(screenBrightnessBacklight);
        when(mResources.getIntArray(com.android.internal.R.array
                .config_screenBrightnessBacklight)).thenReturn(new int[]{0, 120, 255});

        when(mResources.getIntArray(com.android.internal.R.array
                .config_autoBrightnessLevels)).thenReturn(new int[]{30, 80});
        when(mResources.getIntArray(com.android.internal.R.array
                .config_autoBrightnessDisplayValuesNits)).thenReturn(new int[]{25, 55});

        TypedArray screenBrightnessLevelNits = createFloatTypedArray(new
                float[]{2.0f, 200.0f, 600.0f});
        when(mResources.obtainTypedArray(
                com.android.internal.R.array.config_autoBrightnessDisplayValuesNits))
                .thenReturn(screenBrightnessLevelNits);
        int[] screenBrightnessLevelLux = new int[]{0, 110, 500};
        when(mResources.getIntArray(
                com.android.internal.R.array.config_autoBrightnessLevels))
                .thenReturn(screenBrightnessLevelLux);

        mDisplayDeviceConfig = DisplayDeviceConfig.create(mContext, true);

    }

    private TypedArray createFloatTypedArray(float[] vals) {
        TypedArray mockArray = mock(TypedArray.class);
        when(mockArray.length()).thenAnswer(invocation -> {
            return vals.length;
        });
        when(mockArray.getFloat(anyInt(), anyFloat())).thenAnswer(invocation -> {
            final float def = (float) invocation.getArguments()[1];
            if (vals == null) {
                return def;
            }
            int idx = (int) invocation.getArguments()[0];
            if (idx >= 0 && idx < vals.length) {
                return vals[idx];
            } else {
                return def;
            }
        });
        return mockArray;
    }
}
