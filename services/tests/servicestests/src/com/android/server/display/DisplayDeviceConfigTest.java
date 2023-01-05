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

import com.android.internal.R;

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
    private static final int DEFAULT_PEAK_REFRESH_RATE = 75;
    private static final int DEFAULT_REFRESH_RATE = 120;
    private static final int DEFAULT_HIGH_BLOCKING_ZONE_REFRESH_RATE = 55;
    private static final int DEFAULT_LOW_BLOCKING_ZONE_REFRESH_RATE = 95;
    private static final int[] LOW_BRIGHTNESS_THRESHOLD_OF_PEAK_REFRESH_RATE = new int[]{10, 30};
    private static final int[] LOW_AMBIENT_THRESHOLD_OF_PEAK_REFRESH_RATE = new int[]{1, 21};
    private static final int[] HIGH_BRIGHTNESS_THRESHOLD_OF_PEAK_REFRESH_RATE = new int[]{160};
    private static final int[] HIGH_AMBIENT_THRESHOLD_OF_PEAK_REFRESH_RATE = new int[]{30000};

    private DisplayDeviceConfig mDisplayDeviceConfig;
    private static final float ZERO_DELTA = 0.0f;
    private static final float SMALL_DELTA = 0.0001f;

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
        assertEquals(mDisplayDeviceConfig.getBrightnessRampFastDecrease(), 0.01f, ZERO_DELTA);
        assertEquals(mDisplayDeviceConfig.getBrightnessRampFastIncrease(), 0.02f, ZERO_DELTA);
        assertEquals(mDisplayDeviceConfig.getBrightnessRampSlowIncrease(), 0.04f, ZERO_DELTA);
        assertEquals(mDisplayDeviceConfig.getBrightnessRampSlowDecrease(), 0.03f, ZERO_DELTA);
        assertEquals(mDisplayDeviceConfig.getBrightnessDefault(), 0.5f, ZERO_DELTA);
        assertArrayEquals(mDisplayDeviceConfig.getBrightness(), new float[]{0.0f, 0.62f, 1.0f},
                ZERO_DELTA);
        assertArrayEquals(mDisplayDeviceConfig.getNits(), new float[]{2.0f, 500.0f, 800.0f},
                ZERO_DELTA);
        assertArrayEquals(mDisplayDeviceConfig.getBacklight(), new float[]{0.0f, 0.62f, 1.0f},
                ZERO_DELTA);
        assertEquals(mDisplayDeviceConfig.getAutoBrightnessBrighteningLightDebounce(), 2000);
        assertEquals(mDisplayDeviceConfig.getAutoBrightnessDarkeningLightDebounce(), 1000);
        assertArrayEquals(mDisplayDeviceConfig.getAutoBrightnessBrighteningLevelsLux(), new
                float[]{0.0f, 50.0f, 80.0f}, ZERO_DELTA);
        assertArrayEquals(mDisplayDeviceConfig.getAutoBrightnessBrighteningLevelsNits(), new
                float[]{45.32f, 75.43f}, ZERO_DELTA);

        // Test thresholds
        assertEquals(10, mDisplayDeviceConfig.getAmbientLuxBrighteningMinThreshold(),
                ZERO_DELTA);
        assertEquals(20, mDisplayDeviceConfig.getAmbientLuxBrighteningMinThresholdIdle(),
                ZERO_DELTA);
        assertEquals(30, mDisplayDeviceConfig.getAmbientLuxDarkeningMinThreshold(), ZERO_DELTA);
        assertEquals(40, mDisplayDeviceConfig.getAmbientLuxDarkeningMinThresholdIdle(), ZERO_DELTA);

        assertEquals(0.1f, mDisplayDeviceConfig.getScreenBrighteningMinThreshold(), ZERO_DELTA);
        assertEquals(0.2f, mDisplayDeviceConfig.getScreenBrighteningMinThresholdIdle(), ZERO_DELTA);
        assertEquals(0.3f, mDisplayDeviceConfig.getScreenDarkeningMinThreshold(), ZERO_DELTA);
        assertEquals(0.4f, mDisplayDeviceConfig.getScreenDarkeningMinThresholdIdle(), ZERO_DELTA);

        assertArrayEquals(new float[]{0, 0.10f, 0.20f},
                mDisplayDeviceConfig.getScreenBrighteningLevels(), ZERO_DELTA);
        assertArrayEquals(new float[]{9, 10, 11},
                mDisplayDeviceConfig.getScreenBrighteningPercentages(), ZERO_DELTA);
        assertArrayEquals(new float[]{0, 0.11f, 0.21f},
                mDisplayDeviceConfig.getScreenDarkeningLevels(), ZERO_DELTA);
        assertArrayEquals(new float[]{11, 12, 13},
                mDisplayDeviceConfig.getScreenDarkeningPercentages(), ZERO_DELTA);

        assertArrayEquals(new float[]{0, 100, 200},
                mDisplayDeviceConfig.getAmbientBrighteningLevels(), ZERO_DELTA);
        assertArrayEquals(new float[]{13, 14, 15},
                mDisplayDeviceConfig.getAmbientBrighteningPercentages(), ZERO_DELTA);
        assertArrayEquals(new float[]{0, 300, 400},
                mDisplayDeviceConfig.getAmbientDarkeningLevels(), ZERO_DELTA);
        assertArrayEquals(new float[]{15, 16, 17},
                mDisplayDeviceConfig.getAmbientDarkeningPercentages(), ZERO_DELTA);

        assertArrayEquals(new float[]{0, 0.12f, 0.22f},
                mDisplayDeviceConfig.getScreenBrighteningLevelsIdle(), ZERO_DELTA);
        assertArrayEquals(new float[]{17, 18, 19},
                mDisplayDeviceConfig.getScreenBrighteningPercentagesIdle(), ZERO_DELTA);
        assertArrayEquals(new float[]{0, 0.13f, 0.23f},
                mDisplayDeviceConfig.getScreenDarkeningLevelsIdle(), ZERO_DELTA);
        assertArrayEquals(new float[]{19, 20, 21},
                mDisplayDeviceConfig.getScreenDarkeningPercentagesIdle(), ZERO_DELTA);

        assertArrayEquals(new float[]{0, 500, 600},
                mDisplayDeviceConfig.getAmbientBrighteningLevelsIdle(), ZERO_DELTA);
        assertArrayEquals(new float[]{21, 22, 23},
                mDisplayDeviceConfig.getAmbientBrighteningPercentagesIdle(), ZERO_DELTA);
        assertArrayEquals(new float[]{0, 700, 800},
                mDisplayDeviceConfig.getAmbientDarkeningLevelsIdle(), ZERO_DELTA);
        assertArrayEquals(new float[]{23, 24, 25},
                mDisplayDeviceConfig.getAmbientDarkeningPercentagesIdle(), ZERO_DELTA);

        assertEquals("ProximitySensor123", mDisplayDeviceConfig.getProximitySensor().name);
        assertEquals("prox_type_1", mDisplayDeviceConfig.getProximitySensor().type);
        assertEquals(75, mDisplayDeviceConfig.getDefaultLowBlockingZoneRefreshRate());
        assertEquals(90, mDisplayDeviceConfig.getDefaultHighBlockingZoneRefreshRate());
        assertEquals(85, mDisplayDeviceConfig.getDefaultPeakRefreshRate());
        assertEquals(45, mDisplayDeviceConfig.getDefaultRefreshRate());
        assertArrayEquals(new int[]{45, 55},
                mDisplayDeviceConfig.getLowDisplayBrightnessThresholds());
        assertArrayEquals(new int[]{50, 60},
                mDisplayDeviceConfig.getLowAmbientBrightnessThresholds());
        assertArrayEquals(new int[]{65, 75},
                mDisplayDeviceConfig.getHighDisplayBrightnessThresholds());
        assertArrayEquals(new int[]{70, 80},
                mDisplayDeviceConfig.getHighAmbientBrightnessThresholds());

        assertEquals("sensor_12345",
                mDisplayDeviceConfig.getScreenOffBrightnessSensor().type);
        assertEquals("Sensor 12345",
                mDisplayDeviceConfig.getScreenOffBrightnessSensor().name);

        assertArrayEquals(new int[]{-1, 10, 20, 30, 40},
                mDisplayDeviceConfig.getScreenOffBrightnessSensorValueToLux());

        // Todo(brup): Add asserts for BrightnessThrottlingData, DensityMapping,
        // HighBrightnessModeData AmbientLightSensor, RefreshRateLimitations and ProximitySensor.
    }

    @Test
    public void testConfigValuesFromConfigResource() {
        setupDisplayDeviceConfigFromConfigResourceFile();
        assertArrayEquals(mDisplayDeviceConfig.getAutoBrightnessBrighteningLevelsNits(), new
                float[]{2.0f, 200.0f, 600.0f}, ZERO_DELTA);
        assertArrayEquals(mDisplayDeviceConfig.getAutoBrightnessBrighteningLevelsLux(), new
                float[]{0.0f, 0.0f, 110.0f, 500.0f}, ZERO_DELTA);

        // Test thresholds
        assertEquals(0, mDisplayDeviceConfig.getAmbientLuxBrighteningMinThreshold(), ZERO_DELTA);
        assertEquals(0, mDisplayDeviceConfig.getAmbientLuxBrighteningMinThresholdIdle(),
                ZERO_DELTA);
        assertEquals(0, mDisplayDeviceConfig.getAmbientLuxDarkeningMinThreshold(), ZERO_DELTA);
        assertEquals(0, mDisplayDeviceConfig.getAmbientLuxDarkeningMinThresholdIdle(), ZERO_DELTA);

        assertEquals(0, mDisplayDeviceConfig.getScreenBrighteningMinThreshold(), ZERO_DELTA);
        assertEquals(0, mDisplayDeviceConfig.getScreenBrighteningMinThresholdIdle(), ZERO_DELTA);
        assertEquals(0, mDisplayDeviceConfig.getScreenDarkeningMinThreshold(), ZERO_DELTA);
        assertEquals(0, mDisplayDeviceConfig.getScreenDarkeningMinThresholdIdle(), ZERO_DELTA);

        // screen levels will be considered "old screen brightness scale"
        // and therefore will divide by 255
        assertArrayEquals(new float[]{0, 42 / 255f, 43 / 255f},
                mDisplayDeviceConfig.getScreenBrighteningLevels(), SMALL_DELTA);
        assertArrayEquals(new float[]{35, 36, 37},
                mDisplayDeviceConfig.getScreenBrighteningPercentages(), ZERO_DELTA);
        assertArrayEquals(new float[]{0, 42 / 255f, 43 / 255f},
                mDisplayDeviceConfig.getScreenDarkeningLevels(), SMALL_DELTA);
        assertArrayEquals(new float[]{37, 38, 39},
                mDisplayDeviceConfig.getScreenDarkeningPercentages(), ZERO_DELTA);

        assertArrayEquals(new float[]{0, 30, 31},
                mDisplayDeviceConfig.getAmbientBrighteningLevels(), ZERO_DELTA);
        assertArrayEquals(new float[]{27, 28, 29},
                mDisplayDeviceConfig.getAmbientBrighteningPercentages(), ZERO_DELTA);
        assertArrayEquals(new float[]{0, 30, 31},
                mDisplayDeviceConfig.getAmbientDarkeningLevels(), ZERO_DELTA);
        assertArrayEquals(new float[]{29, 30, 31},
                mDisplayDeviceConfig.getAmbientDarkeningPercentages(), ZERO_DELTA);

        assertArrayEquals(new float[]{0, 42 / 255f, 43 / 255f},
                mDisplayDeviceConfig.getScreenBrighteningLevelsIdle(), SMALL_DELTA);
        assertArrayEquals(new float[]{35, 36, 37},
                mDisplayDeviceConfig.getScreenBrighteningPercentagesIdle(), ZERO_DELTA);
        assertArrayEquals(new float[]{0, 42 / 255f, 43 / 255f},
                mDisplayDeviceConfig.getScreenDarkeningLevelsIdle(), SMALL_DELTA);
        assertArrayEquals(new float[]{37, 38, 39},
                mDisplayDeviceConfig.getScreenDarkeningPercentagesIdle(), ZERO_DELTA);

        assertArrayEquals(new float[]{0, 30, 31},
                mDisplayDeviceConfig.getAmbientBrighteningLevelsIdle(), ZERO_DELTA);
        assertArrayEquals(new float[]{27, 28, 29},
                mDisplayDeviceConfig.getAmbientBrighteningPercentagesIdle(), ZERO_DELTA);
        assertArrayEquals(new float[]{0, 30, 31},
                mDisplayDeviceConfig.getAmbientDarkeningLevelsIdle(), ZERO_DELTA);
        assertArrayEquals(new float[]{29, 30, 31},
                mDisplayDeviceConfig.getAmbientDarkeningPercentagesIdle(), ZERO_DELTA);
        assertEquals(mDisplayDeviceConfig.getDefaultLowBlockingZoneRefreshRate(),
                DEFAULT_LOW_BLOCKING_ZONE_REFRESH_RATE);
        assertEquals(mDisplayDeviceConfig.getDefaultHighBlockingZoneRefreshRate(),
                DEFAULT_HIGH_BLOCKING_ZONE_REFRESH_RATE);
        assertEquals(mDisplayDeviceConfig.getDefaultPeakRefreshRate(), DEFAULT_PEAK_REFRESH_RATE);
        assertEquals(mDisplayDeviceConfig.getDefaultRefreshRate(), DEFAULT_REFRESH_RATE);
        assertArrayEquals(mDisplayDeviceConfig.getLowDisplayBrightnessThresholds(),
                LOW_BRIGHTNESS_THRESHOLD_OF_PEAK_REFRESH_RATE);
        assertArrayEquals(mDisplayDeviceConfig.getLowAmbientBrightnessThresholds(),
                LOW_AMBIENT_THRESHOLD_OF_PEAK_REFRESH_RATE);
        assertArrayEquals(mDisplayDeviceConfig.getHighDisplayBrightnessThresholds(),
                HIGH_BRIGHTNESS_THRESHOLD_OF_PEAK_REFRESH_RATE);
        assertArrayEquals(mDisplayDeviceConfig.getHighAmbientBrightnessThresholds(),
                HIGH_AMBIENT_THRESHOLD_OF_PEAK_REFRESH_RATE);

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
                +   "<screenOffBrightnessSensor>\n"
                +       "<type>sensor_12345</type>\n"
                +       "<name>Sensor 12345</name>\n"
                +   "</screenOffBrightnessSensor>\n"
                +   "<ambientBrightnessChangeThresholds>\n"
                +       "<brighteningThresholds>\n"
                +           "<minimum>10</minimum>\n"
                +           "<brightnessThresholdPoints>\n"
                +               "<brightnessThresholdPoint>\n"
                +                   "<threshold>0</threshold><percentage>13</percentage>\n"
                +               "</brightnessThresholdPoint>\n"
                +               "<brightnessThresholdPoint>\n"
                +                   "<threshold>100</threshold><percentage>14</percentage>\n"
                +               "</brightnessThresholdPoint>\n"
                +               "<brightnessThresholdPoint>\n"
                +                   "<threshold>200</threshold><percentage>15</percentage>\n"
                +               "</brightnessThresholdPoint>\n"
                +           "</brightnessThresholdPoints>\n"
                +       "</brighteningThresholds>\n"
                +       "<darkeningThresholds>\n"
                +           "<minimum>30</minimum>\n"
                +           "<brightnessThresholdPoints>\n"
                +               "<brightnessThresholdPoint>\n"
                +                   "<threshold>0</threshold><percentage>15</percentage>\n"
                +               "</brightnessThresholdPoint>\n"
                +               "<brightnessThresholdPoint>\n"
                +                   "<threshold>300</threshold><percentage>16</percentage>\n"
                +               "</brightnessThresholdPoint>\n"
                +               "<brightnessThresholdPoint>\n"
                +                   "<threshold>400</threshold><percentage>17</percentage>\n"
                +               "</brightnessThresholdPoint>\n"
                +           "</brightnessThresholdPoints>\n"
                +       "</darkeningThresholds>\n"
                +   "</ambientBrightnessChangeThresholds>\n"
                +   "<displayBrightnessChangeThresholds>\n"
                +       "<brighteningThresholds>\n"
                +           "<minimum>0.1</minimum>\n"
                +           "<brightnessThresholdPoints>\n"
                +               "<brightnessThresholdPoint>\n"
                +                   "<threshold>0</threshold>\n"
                +                   "<percentage>9</percentage>\n"
                +               "</brightnessThresholdPoint>\n"
                +               "<brightnessThresholdPoint>\n"
                +                   "<threshold>0.10</threshold>\n"
                +                   "<percentage>10</percentage>\n"
                +               "</brightnessThresholdPoint>\n"
                +               "<brightnessThresholdPoint>\n"
                +                   "<threshold>0.20</threshold>\n"
                +                   "<percentage>11</percentage>\n"
                +               "</brightnessThresholdPoint>\n"
                +           "</brightnessThresholdPoints>\n"
                +       "</brighteningThresholds>\n"
                +       "<darkeningThresholds>\n"
                +           "<minimum>0.3</minimum>\n"
                +           "<brightnessThresholdPoints>\n"
                +               "<brightnessThresholdPoint>\n"
                +                   "<threshold>0</threshold><percentage>11</percentage>\n"
                +               "</brightnessThresholdPoint>\n"
                +               "<brightnessThresholdPoint>\n"
                +                   "<threshold>0.11</threshold><percentage>12</percentage>\n"
                +               "</brightnessThresholdPoint>\n"
                +               "<brightnessThresholdPoint>\n"
                +                   "<threshold>0.21</threshold><percentage>13</percentage>\n"
                +               "</brightnessThresholdPoint>\n"
                +           "</brightnessThresholdPoints>\n"
                +       "</darkeningThresholds>\n"
                +   "</displayBrightnessChangeThresholds>\n"
                +   "<ambientBrightnessChangeThresholdsIdle>\n"
                +       "<brighteningThresholds>\n"
                +           "<minimum>20</minimum>\n"
                +           "<brightnessThresholdPoints>\n"
                +               "<brightnessThresholdPoint>\n"
                +                   "<threshold>0</threshold><percentage>21</percentage>\n"
                +               "</brightnessThresholdPoint>\n"
                +               "<brightnessThresholdPoint>\n"
                +                   "<threshold>500</threshold><percentage>22</percentage>\n"
                +               "</brightnessThresholdPoint>\n"
                +               "<brightnessThresholdPoint>\n"
                +                   "<threshold>600</threshold><percentage>23</percentage>\n"
                +               "</brightnessThresholdPoint>\n"
                +           "</brightnessThresholdPoints>\n"
                +       "</brighteningThresholds>\n"
                +       "<darkeningThresholds>\n"
                +           "<minimum>40</minimum>\n"
                +           "<brightnessThresholdPoints>\n"
                +               "<brightnessThresholdPoint>\n"
                +                   "<threshold>0</threshold><percentage>23</percentage>\n"
                +               "</brightnessThresholdPoint>\n"
                +               "<brightnessThresholdPoint>\n"
                +                   "<threshold>700</threshold><percentage>24</percentage>\n"
                +               "</brightnessThresholdPoint>\n"
                +               "<brightnessThresholdPoint>\n"
                +                   "<threshold>800</threshold><percentage>25</percentage>\n"
                +               "</brightnessThresholdPoint>\n"
                +           "</brightnessThresholdPoints>\n"
                +       "</darkeningThresholds>\n"
                +   "</ambientBrightnessChangeThresholdsIdle>\n"
                +   "<displayBrightnessChangeThresholdsIdle>\n"
                +       "<brighteningThresholds>\n"
                +           "<minimum>0.2</minimum>\n"
                +           "<brightnessThresholdPoints>\n"
                +               "<brightnessThresholdPoint>\n"
                +                   "<threshold>0</threshold><percentage>17</percentage>\n"
                +               "</brightnessThresholdPoint>\n"
                +               "<brightnessThresholdPoint>\n"
                +                   "<threshold>0.12</threshold><percentage>18</percentage>\n"
                +               "</brightnessThresholdPoint>\n"
                +               "<brightnessThresholdPoint>\n"
                +                   "<threshold>0.22</threshold><percentage>19</percentage>\n"
                +               "</brightnessThresholdPoint>\n"
                +           "</brightnessThresholdPoints>\n"
                +       "</brighteningThresholds>\n"
                +       "<darkeningThresholds>\n"
                +           "<minimum>0.4</minimum>\n"
                +           "<brightnessThresholdPoints>\n"
                +               "<brightnessThresholdPoint>\n"
                +                   "<threshold>0</threshold><percentage>19</percentage>\n"
                +               "</brightnessThresholdPoint>\n"
                +               "<brightnessThresholdPoint>\n"
                +                   "<threshold>0.13</threshold><percentage>20</percentage>\n"
                +               "</brightnessThresholdPoint>\n"
                +               "<brightnessThresholdPoint>\n"
                +                   "<threshold>0.23</threshold><percentage>21</percentage>\n"
                +               "</brightnessThresholdPoint>\n"
                +           "</brightnessThresholdPoints>\n"
                +       "</darkeningThresholds>\n"
                +   "</displayBrightnessChangeThresholdsIdle>\n"
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
                +   "<proxSensor>\n"
                +       "<name>ProximitySensor123</name>\n"
                +       "<type>prox_type_1</type>\n"
                +   "</proxSensor>\n"
                +   "<refreshRate>\n"
                +       "<defaultRefreshRate>45</defaultRefreshRate>\n"
                +       "<defaultPeakRefreshRate>85</defaultPeakRefreshRate>\n"
                +       "<lowerBlockingZoneConfigs>\n"
                +           "<defaultRefreshRate>75</defaultRefreshRate>\n"
                +           "<blockingZoneThreshold>\n"
                +               "<displayBrightnessPoint>\n"
                +                   "<lux>50</lux>\n"
                // This number will be rounded to integer when read by the system
                +                   "<nits>45.3</nits>\n"
                +               "</displayBrightnessPoint>\n"
                +               "<displayBrightnessPoint>\n"
                +                   "<lux>60</lux>\n"
                // This number will be rounded to integer when read by the system
                +                   "<nits>55.2</nits>\n"
                +               "</displayBrightnessPoint>\n"
                +           "</blockingZoneThreshold>\n"
                +       "</lowerBlockingZoneConfigs>\n"
                +       "<higherBlockingZoneConfigs>\n"
                +           "<defaultRefreshRate>90</defaultRefreshRate>\n"
                +           "<blockingZoneThreshold>\n"
                +               "<displayBrightnessPoint>\n"
                +                   "<lux>70</lux>\n"
                // This number will be rounded to integer when read by the system
                +                   "<nits>65.6</nits>\n"
                +               "</displayBrightnessPoint>\n"
                +               "<displayBrightnessPoint>\n"
                +                   "<lux>80</lux>\n"
                // This number will be rounded to integer when read by the system
                +                   "<nits>75</nits>\n"
                +               "</displayBrightnessPoint>\n"
                +           "</blockingZoneThreshold>\n"
                +       "</higherBlockingZoneConfigs>\n"
                +   "</refreshRate>\n"
                +   "<screenOffBrightnessSensorValueToLux>\n"
                +       "<item>-1</item>\n"
                +       "<item>10</item>\n"
                +       "<item>20</item>\n"
                +       "<item>30</item>\n"
                +       "<item>40</item>\n"
                +   "</screenOffBrightnessSensorValueToLux>\n"
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

        // Thresholds
        // Config.xml requires the levels arrays to be of length N and the thresholds arrays to be
        // of length N+1
        when(mResources.getIntArray(com.android.internal.R.array.config_ambientThresholdLevels))
                .thenReturn(new int[]{30, 31});
        when(mResources.getIntArray(com.android.internal.R.array.config_screenThresholdLevels))
                .thenReturn(new int[]{42, 43});
        when(mResources.getIntArray(
                com.android.internal.R.array.config_ambientBrighteningThresholds))
                .thenReturn(new int[]{270, 280, 290});
        when(mResources.getIntArray(com.android.internal.R.array.config_ambientDarkeningThresholds))
                .thenReturn(new int[]{290, 300, 310});
        when(mResources.getIntArray(R.array.config_screenBrighteningThresholds))
                .thenReturn(new int[]{350, 360, 370});
        when(mResources.getIntArray(R.array.config_screenDarkeningThresholds))
                .thenReturn(new int[]{370, 380, 390});

        // Configs related to refresh rates and blocking zones
        when(mResources.getInteger(R.integer.config_defaultPeakRefreshRate))
                .thenReturn(DEFAULT_PEAK_REFRESH_RATE);
        when(mResources.getInteger(R.integer.config_defaultRefreshRate))
                .thenReturn(DEFAULT_REFRESH_RATE);
        when(mResources.getInteger(R.integer.config_fixedRefreshRateInHighZone))
            .thenReturn(DEFAULT_HIGH_BLOCKING_ZONE_REFRESH_RATE);
        when(mResources.getInteger(R.integer.config_defaultRefreshRateInZone))
            .thenReturn(DEFAULT_LOW_BLOCKING_ZONE_REFRESH_RATE);
        when(mResources.getIntArray(R.array.config_brightnessThresholdsOfPeakRefreshRate))
                .thenReturn(LOW_BRIGHTNESS_THRESHOLD_OF_PEAK_REFRESH_RATE);
        when(mResources.getIntArray(R.array.config_ambientThresholdsOfPeakRefreshRate))
                .thenReturn(LOW_AMBIENT_THRESHOLD_OF_PEAK_REFRESH_RATE);
        when(mResources.getIntArray(
                R.array.config_highDisplayBrightnessThresholdsOfFixedRefreshRate))
                .thenReturn(HIGH_BRIGHTNESS_THRESHOLD_OF_PEAK_REFRESH_RATE);
        when(mResources.getIntArray(
                R.array.config_highAmbientBrightnessThresholdsOfFixedRefreshRate))
                .thenReturn(HIGH_AMBIENT_THRESHOLD_OF_PEAK_REFRESH_RATE);

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
