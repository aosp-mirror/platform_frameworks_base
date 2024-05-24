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


import static com.android.internal.display.BrightnessSynchronizer.brightnessIntToFloat;
import static com.android.server.display.AutomaticBrightnessController.AUTO_BRIGHTNESS_MODE_DEFAULT;
import static com.android.server.display.AutomaticBrightnessController.AUTO_BRIGHTNESS_MODE_DOZE;
import static com.android.server.display.config.SensorData.TEMPERATURE_TYPE_SKIN;
import static com.android.server.display.config.SensorData.SupportedMode;
import static com.android.server.display.utils.DeviceConfigParsingUtils.ambientBrightnessThresholdsIntToFloat;
import static com.android.server.display.utils.DeviceConfigParsingUtils.displayBrightnessThresholdsIntToFloat;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.hardware.display.DisplayManagerInternal;
import android.os.PowerManager;
import android.os.Temperature;
import android.provider.Settings;
import android.util.SparseArray;
import android.util.Spline;
import android.view.SurfaceControl;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.R;
import com.android.server.display.config.HdrBrightnessData;
import com.android.server.display.config.ThermalStatus;
import com.android.server.display.feature.DisplayManagerFlags;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SmallTest
@RunWith(AndroidJUnit4.class)
public final class DisplayDeviceConfigTest {
    private static final int DEFAULT_PEAK_REFRESH_RATE = 75;
    private static final int DEFAULT_REFRESH_RATE = 120;
    private static final int DEFAULT_HIGH_BLOCKING_ZONE_REFRESH_RATE = 55;
    private static final int DEFAULT_LOW_BLOCKING_ZONE_REFRESH_RATE = 95;
    private static final int DEFAULT_REFRESH_RATE_IN_HBM_HDR = 90;
    private static final int DEFAULT_REFRESH_RATE_IN_HBM_SUNLIGHT = 100;
    private static final int[] LOW_BRIGHTNESS_THRESHOLD_OF_PEAK_REFRESH_RATE =
            new int[]{10, 30, -1};
    private static final int[] LOW_AMBIENT_THRESHOLD_OF_PEAK_REFRESH_RATE = new int[]{-1, 1, 21};
    private static final int[] HIGH_BRIGHTNESS_THRESHOLD_OF_PEAK_REFRESH_RATE = new int[]{160, -1};
    private static final int[] HIGH_AMBIENT_THRESHOLD_OF_PEAK_REFRESH_RATE = new int[]{-1, 30000};
    private static final float[] NITS = {2, 500, 800};
    private static final float[] BRIGHTNESS = {0, 0.62f, 1};
    private static final Spline NITS_TO_BRIGHTNESS_SPLINE = Spline.createSpline(NITS, BRIGHTNESS);

    private DisplayDeviceConfig mDisplayDeviceConfig;
    private static final float ZERO_DELTA = 0.0f;
    private static final float SMALL_DELTA = 0.0001f;
    @Mock
    private Context mContext;

    @Mock
    private Resources mResources;

    @Mock
    private DisplayManagerFlags mFlags;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mContext.getResources()).thenReturn(mResources);
        when(mFlags.areAutoBrightnessModesEnabled()).thenReturn(true);
        when(mFlags.isSensorBasedBrightnessThrottlingEnabled()).thenReturn(true);
        mockDeviceConfigs();
    }

    @Test
    public void testDefaultValues() {
        when(mResources.getString(com.android.internal.R.string.config_displayLightSensorType))
                .thenReturn("test_light_sensor");
        when(mResources.getBoolean(R.bool.config_automatic_brightness_available)).thenReturn(true);

        mDisplayDeviceConfig = DisplayDeviceConfig.create(mContext, /* useConfigXml= */ false,
                mFlags);

        assertEquals(DisplayDeviceConfig.BRIGHTNESS_DEFAULT,
                mDisplayDeviceConfig.getBrightnessDefault(), ZERO_DELTA);
        assertEquals(PowerManager.BRIGHTNESS_MAX,
                mDisplayDeviceConfig.getBrightnessRampFastDecrease(), ZERO_DELTA);
        assertEquals(PowerManager.BRIGHTNESS_MAX,
                mDisplayDeviceConfig.getBrightnessRampFastIncrease(), ZERO_DELTA);
        assertEquals(PowerManager.BRIGHTNESS_MAX,
                mDisplayDeviceConfig.getBrightnessRampSlowDecrease(), ZERO_DELTA);
        assertEquals(PowerManager.BRIGHTNESS_MAX,
                mDisplayDeviceConfig.getBrightnessRampSlowIncrease(), ZERO_DELTA);
        assertEquals(PowerManager.BRIGHTNESS_MAX,
                mDisplayDeviceConfig.getBrightnessRampSlowDecreaseIdle(), ZERO_DELTA);
        assertEquals(PowerManager.BRIGHTNESS_MAX,
                mDisplayDeviceConfig.getBrightnessRampSlowIncreaseIdle(), ZERO_DELTA);
        assertEquals(0, mDisplayDeviceConfig.getBrightnessRampDecreaseMaxMillis());
        assertEquals(0, mDisplayDeviceConfig.getBrightnessRampIncreaseMaxMillis());
        assertEquals(0, mDisplayDeviceConfig.getBrightnessRampDecreaseMaxIdleMillis());
        assertEquals(0, mDisplayDeviceConfig.getBrightnessRampIncreaseMaxIdleMillis());
        assertNull(mDisplayDeviceConfig.getNits());
        assertNull(mDisplayDeviceConfig.getBacklight());
        assertEquals(0.3f, mDisplayDeviceConfig.getBacklightFromBrightness(0.3f), ZERO_DELTA);
        assertEquals("test_light_sensor", mDisplayDeviceConfig.getAmbientLightSensor().type);
        assertEquals("", mDisplayDeviceConfig.getAmbientLightSensor().name);
        assertNull(mDisplayDeviceConfig.getProximitySensor().type);
        assertNull(mDisplayDeviceConfig.getProximitySensor().name);
        assertEquals(TEMPERATURE_TYPE_SKIN, mDisplayDeviceConfig.getTempSensor().type);
        assertNull(mDisplayDeviceConfig.getTempSensor().name);
        assertTrue(mDisplayDeviceConfig.isAutoBrightnessAvailable());
    }

    @Test
    public void testConfigValuesFromDisplayConfig() throws IOException {
        setupDisplayDeviceConfigFromDisplayConfigFile();

        assertEquals(mDisplayDeviceConfig.getName(), "Example Display");
        assertEquals(mDisplayDeviceConfig.getAmbientHorizonLong(), 5000);
        assertEquals(mDisplayDeviceConfig.getAmbientHorizonShort(), 50);
        assertEquals(mDisplayDeviceConfig.getBrightnessDefault(), 0.5f, ZERO_DELTA);
        assertArrayEquals(mDisplayDeviceConfig.getBrightness(), BRIGHTNESS, ZERO_DELTA);
        assertArrayEquals(mDisplayDeviceConfig.getNits(), NITS, ZERO_DELTA);
        assertArrayEquals(mDisplayDeviceConfig.getBacklight(), BRIGHTNESS, ZERO_DELTA);

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

        assertEquals(75, mDisplayDeviceConfig.getDefaultLowBlockingZoneRefreshRate());
        assertEquals(90, mDisplayDeviceConfig.getDefaultHighBlockingZoneRefreshRate());
        assertEquals(85, mDisplayDeviceConfig.getDefaultPeakRefreshRate());
        assertEquals(45, mDisplayDeviceConfig.getDefaultRefreshRate());
        assertEquals(2, mDisplayDeviceConfig.getRefreshRangeProfiles().size());
        assertEquals(60, mDisplayDeviceConfig.getRefreshRange("test1").min, SMALL_DELTA);
        assertEquals(60, mDisplayDeviceConfig.getRefreshRange("test1").max, SMALL_DELTA);
        assertEquals(80, mDisplayDeviceConfig.getRefreshRange("test2").min, SMALL_DELTA);
        assertEquals(90, mDisplayDeviceConfig.getRefreshRange("test2").max, SMALL_DELTA);
        assertEquals(82, mDisplayDeviceConfig.getDefaultRefreshRateInHbmHdr());
        assertEquals(83, mDisplayDeviceConfig.getDefaultRefreshRateInHbmSunlight());

        assertNotNull(mDisplayDeviceConfig.getHostUsiVersion());
        assertEquals(mDisplayDeviceConfig.getHostUsiVersion().getMajorVersion(), 2);
        assertEquals(mDisplayDeviceConfig.getHostUsiVersion().getMinorVersion(), 0);
    }

    @Test
    public void testPowerThrottlingConfigFromDisplayConfig() throws IOException {
        setupDisplayDeviceConfigFromDisplayConfigFile();

        DisplayDeviceConfig.PowerThrottlingConfigData powerThrottlingConfigData =
                mDisplayDeviceConfig.getPowerThrottlingConfigData();
        assertNotNull(powerThrottlingConfigData);
        assertEquals(0.1f, powerThrottlingConfigData.brightnessLowestCapAllowed, SMALL_DELTA);
        assertEquals(10, powerThrottlingConfigData.pollingWindowMillis);
    }

    @Test
    public void testPowerThrottlingDataFromDisplayConfig() throws IOException {
        setupDisplayDeviceConfigFromDisplayConfigFile();

        List<DisplayDeviceConfig.PowerThrottlingData.ThrottlingLevel>
                defaultThrottlingLevels = new ArrayList<>();
        defaultThrottlingLevels.add(
                new DisplayDeviceConfig.PowerThrottlingData.ThrottlingLevel(
                        DisplayDeviceConfig.convertThermalStatus(ThermalStatus.light), 800f
                ));
        defaultThrottlingLevels.add(
                new DisplayDeviceConfig.PowerThrottlingData.ThrottlingLevel(
                        DisplayDeviceConfig.convertThermalStatus(ThermalStatus.moderate), 600f
                ));
        defaultThrottlingLevels.add(
                new DisplayDeviceConfig.PowerThrottlingData.ThrottlingLevel(
                        DisplayDeviceConfig.convertThermalStatus(ThermalStatus.severe), 400f
                ));
        defaultThrottlingLevels.add(
                new DisplayDeviceConfig.PowerThrottlingData.ThrottlingLevel(
                        DisplayDeviceConfig.convertThermalStatus(ThermalStatus.critical), 200f
                ));
        defaultThrottlingLevels.add(
                new DisplayDeviceConfig.PowerThrottlingData.ThrottlingLevel(
                        DisplayDeviceConfig.convertThermalStatus(ThermalStatus.emergency), 100f
                ));
        defaultThrottlingLevels.add(
                new DisplayDeviceConfig.PowerThrottlingData.ThrottlingLevel(
                        DisplayDeviceConfig.convertThermalStatus(ThermalStatus.shutdown), 50f
                ));

        DisplayDeviceConfig.PowerThrottlingData defaultThrottlingData =
                new DisplayDeviceConfig.PowerThrottlingData(defaultThrottlingLevels);

        List<DisplayDeviceConfig.PowerThrottlingData.ThrottlingLevel>
                concurrentThrottlingLevels = new ArrayList<>();
        concurrentThrottlingLevels.add(
                new DisplayDeviceConfig.PowerThrottlingData.ThrottlingLevel(
                        DisplayDeviceConfig.convertThermalStatus(ThermalStatus.light), 800f
                ));
        concurrentThrottlingLevels.add(
                new DisplayDeviceConfig.PowerThrottlingData.ThrottlingLevel(
                        DisplayDeviceConfig.convertThermalStatus(ThermalStatus.moderate), 600f
                ));
        concurrentThrottlingLevels.add(
                new DisplayDeviceConfig.PowerThrottlingData.ThrottlingLevel(
                        DisplayDeviceConfig.convertThermalStatus(ThermalStatus.severe), 400f
                ));
        concurrentThrottlingLevels.add(
                new DisplayDeviceConfig.PowerThrottlingData.ThrottlingLevel(
                        DisplayDeviceConfig.convertThermalStatus(ThermalStatus.critical), 200f
                ));
        concurrentThrottlingLevels.add(
                new DisplayDeviceConfig.PowerThrottlingData.ThrottlingLevel(
                        DisplayDeviceConfig.convertThermalStatus(ThermalStatus.emergency), 100f
                ));
        concurrentThrottlingLevels.add(
                new DisplayDeviceConfig.PowerThrottlingData.ThrottlingLevel(
                        DisplayDeviceConfig.convertThermalStatus(ThermalStatus.shutdown), 50f
                ));
        DisplayDeviceConfig.PowerThrottlingData concurrentThrottlingData =
                new DisplayDeviceConfig.PowerThrottlingData(concurrentThrottlingLevels);

        HashMap<String, DisplayDeviceConfig.PowerThrottlingData> throttlingDataMap =
                new HashMap<>(2);
        throttlingDataMap.put("default", defaultThrottlingData);
        throttlingDataMap.put("concurrent", concurrentThrottlingData);

        assertEquals(throttlingDataMap,
                mDisplayDeviceConfig.getPowerThrottlingDataMapByThrottlingId());
    }

    @Test
    public void testConfigValuesFromConfigResource() {
        setupDisplayDeviceConfigFromConfigResourceFile();
        verifyConfigValuesFromConfigResource();
    }

    @Test
    public void testThermalRefreshRateThrottlingFromDisplayConfig() throws IOException {
        setupDisplayDeviceConfigFromDisplayConfigFile();

        SparseArray<SurfaceControl.RefreshRateRange> defaultMap =
                mDisplayDeviceConfig.getThermalRefreshRateThrottlingData(null);
        assertNotNull(defaultMap);
        assertEquals(2, defaultMap.size());
        assertEquals(30, defaultMap.get(Temperature.THROTTLING_CRITICAL).min, SMALL_DELTA);
        assertEquals(60, defaultMap.get(Temperature.THROTTLING_CRITICAL).max, SMALL_DELTA);
        assertEquals(0, defaultMap.get(Temperature.THROTTLING_SHUTDOWN).min, SMALL_DELTA);
        assertEquals(30, defaultMap.get(Temperature.THROTTLING_SHUTDOWN).max, SMALL_DELTA);

        SparseArray<SurfaceControl.RefreshRateRange> testMap =
                mDisplayDeviceConfig.getThermalRefreshRateThrottlingData("test");
        assertNotNull(testMap);
        assertEquals(1, testMap.size());
        assertEquals(60, testMap.get(Temperature.THROTTLING_EMERGENCY).min, SMALL_DELTA);
        assertEquals(90, testMap.get(Temperature.THROTTLING_EMERGENCY).max, SMALL_DELTA);
    }

    @Test
    public void testValidLuxThrottling() throws Exception {
        setupDisplayDeviceConfigFromDisplayConfigFile();

        Map<DisplayDeviceConfig.BrightnessLimitMapType, Map<Float, Float>> luxThrottlingData =
                mDisplayDeviceConfig.getLuxThrottlingData();
        assertEquals(2, luxThrottlingData.size());

        Map<Float, Float> adaptiveOnBrightnessPoints = luxThrottlingData.get(
                DisplayDeviceConfig.BrightnessLimitMapType.ADAPTIVE);
        assertEquals(2, adaptiveOnBrightnessPoints.size());
        assertEquals(0.3f, adaptiveOnBrightnessPoints.get(1000f), SMALL_DELTA);
        assertEquals(0.5f, adaptiveOnBrightnessPoints.get(5000f), SMALL_DELTA);

        Map<Float, Float> adaptiveOffBrightnessPoints = luxThrottlingData.get(
                DisplayDeviceConfig.BrightnessLimitMapType.DEFAULT);
        assertEquals(2, adaptiveOffBrightnessPoints.size());
        assertEquals(0.35f, adaptiveOffBrightnessPoints.get(1500f), SMALL_DELTA);
        assertEquals(0.55f, adaptiveOffBrightnessPoints.get(5500f), SMALL_DELTA);
    }

    @Test
    public void testInvalidLuxThrottling() throws Exception {
        setupDisplayDeviceConfigFromDisplayConfigFile(
                getContent(getInvalidLuxThrottling(), getValidProxSensor(),
                        /* includeIdleMode= */ true));

        Map<DisplayDeviceConfig.BrightnessLimitMapType, Map<Float, Float>> luxThrottlingData =
                mDisplayDeviceConfig.getLuxThrottlingData();
        assertEquals(1, luxThrottlingData.size());

        Map<Float, Float> adaptiveOnBrightnessPoints = luxThrottlingData.get(
                DisplayDeviceConfig.BrightnessLimitMapType.ADAPTIVE);
        assertEquals(1, adaptiveOnBrightnessPoints.size());
        assertEquals(0.3f, adaptiveOnBrightnessPoints.get(1000f), SMALL_DELTA);
    }

    @Test
    public void testFallbackToConfigResource() throws IOException {
        setupDisplayDeviceConfigFromConfigResourceFile();

        // Empty display config file
        setupDisplayDeviceConfigFromDisplayConfigFile(
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                + "<displayConfiguration />\n");

        // We should fall back to the config resource
        verifyConfigValuesFromConfigResource();
        assertEquals(3000, mDisplayDeviceConfig.getAutoBrightnessBrighteningLightDebounce());
        assertEquals(4000, mDisplayDeviceConfig.getAutoBrightnessDarkeningLightDebounce());
        assertEquals(3000, mDisplayDeviceConfig.getAutoBrightnessBrighteningLightDebounceIdle());
        assertEquals(4000, mDisplayDeviceConfig.getAutoBrightnessDarkeningLightDebounceIdle());
    }

    @Test
    public void testDensityMappingFromDisplayConfig() throws IOException {
        setupDisplayDeviceConfigFromDisplayConfigFile();

        assertEquals(120, mDisplayDeviceConfig.getDensityMapping()
                .getDensityForResolution(720, 480));
        assertEquals(213, mDisplayDeviceConfig.getDensityMapping()
                .getDensityForResolution(1280, 720));
        assertEquals(320, mDisplayDeviceConfig.getDensityMapping()
                .getDensityForResolution(1920, 1080));
        assertEquals(640, mDisplayDeviceConfig.getDensityMapping()
                .getDensityForResolution(3840, 2160));
    }

    @Test
    public void testHighBrightnessModeDataFromDisplayConfig() throws IOException {
        setupDisplayDeviceConfigFromDisplayConfigFile();

        DisplayDeviceConfig.HighBrightnessModeData hbmData =
                mDisplayDeviceConfig.getHighBrightnessModeData();
        assertNotNull(hbmData);
        assertEquals(BRIGHTNESS[1], hbmData.transitionPoint, ZERO_DELTA);
        assertEquals(10000, hbmData.minimumLux, ZERO_DELTA);
        assertEquals(1800 * 1000, hbmData.timeWindowMillis);
        assertEquals(300 * 1000, hbmData.timeMaxMillis);
        assertEquals(60 * 1000, hbmData.timeMinMillis);
        assertFalse(hbmData.allowInLowPowerMode);
        assertEquals(0.6f, hbmData.minimumHdrPercentOfScreen, ZERO_DELTA);

        List<DisplayManagerInternal.RefreshRateLimitation> refreshRateLimitations =
                mDisplayDeviceConfig.getRefreshRateLimitations();
        assertEquals(1, refreshRateLimitations.size());
        assertEquals(DisplayManagerInternal.REFRESH_RATE_LIMIT_HIGH_BRIGHTNESS_MODE,
                refreshRateLimitations.get(0).type);
        assertEquals(120, refreshRateLimitations.get(0).range.min, ZERO_DELTA);
        assertEquals(120, refreshRateLimitations.get(0).range.max, ZERO_DELTA);

        // Max desired Hdr/SDR ratio upper-bounds the HDR brightness.
        assertTrue(mDisplayDeviceConfig.hasSdrToHdrRatioSpline());
        assertEquals(NITS_TO_BRIGHTNESS_SPLINE.interpolate(500 * 1.6f),
                mDisplayDeviceConfig.getHdrBrightnessFromSdr(
                        NITS_TO_BRIGHTNESS_SPLINE.interpolate(500), Float.POSITIVE_INFINITY),
                ZERO_DELTA);
        assertEquals(NITS_TO_BRIGHTNESS_SPLINE.interpolate(500),
                mDisplayDeviceConfig.getHdrBrightnessFromSdr(
                        NITS_TO_BRIGHTNESS_SPLINE.interpolate(500), 1.0f),
                ZERO_DELTA);
        assertEquals(NITS_TO_BRIGHTNESS_SPLINE.interpolate(500 * 1.25f),
                mDisplayDeviceConfig.getHdrBrightnessFromSdr(
                        NITS_TO_BRIGHTNESS_SPLINE.interpolate(500), 1.25f),
                SMALL_DELTA);
        assertEquals(NITS_TO_BRIGHTNESS_SPLINE.interpolate(2 * 4),
                mDisplayDeviceConfig.getHdrBrightnessFromSdr(
                        NITS_TO_BRIGHTNESS_SPLINE.interpolate(2), Float.POSITIVE_INFINITY),
                SMALL_DELTA);
    }

    @Test
    public void testThermalBrightnessThrottlingDataFromDisplayConfig() throws IOException {
        setupDisplayDeviceConfigFromDisplayConfigFile();

        List<DisplayDeviceConfig.ThermalBrightnessThrottlingData.ThrottlingLevel>
                defaultThrottlingLevels = new ArrayList<>();
        defaultThrottlingLevels.add(
                new DisplayDeviceConfig.ThermalBrightnessThrottlingData.ThrottlingLevel(
                        DisplayDeviceConfig.convertThermalStatus(ThermalStatus.light), 0.4f
                ));
        defaultThrottlingLevels.add(
                new DisplayDeviceConfig.ThermalBrightnessThrottlingData.ThrottlingLevel(
                        DisplayDeviceConfig.convertThermalStatus(ThermalStatus.moderate), 0.3f
                ));
        defaultThrottlingLevels.add(
                new DisplayDeviceConfig.ThermalBrightnessThrottlingData.ThrottlingLevel(
                        DisplayDeviceConfig.convertThermalStatus(ThermalStatus.severe), 0.2f
                ));
        defaultThrottlingLevels.add(
                new DisplayDeviceConfig.ThermalBrightnessThrottlingData.ThrottlingLevel(
                        DisplayDeviceConfig.convertThermalStatus(ThermalStatus.critical), 0.1f
                ));
        defaultThrottlingLevels.add(
                new DisplayDeviceConfig.ThermalBrightnessThrottlingData.ThrottlingLevel(
                        DisplayDeviceConfig.convertThermalStatus(ThermalStatus.emergency), 0.05f
                ));
        defaultThrottlingLevels.add(
                new DisplayDeviceConfig.ThermalBrightnessThrottlingData.ThrottlingLevel(
                        DisplayDeviceConfig.convertThermalStatus(ThermalStatus.shutdown), 0.025f
                ));

        DisplayDeviceConfig.ThermalBrightnessThrottlingData defaultThrottlingData =
                new DisplayDeviceConfig.ThermalBrightnessThrottlingData(defaultThrottlingLevels);

        List<DisplayDeviceConfig.ThermalBrightnessThrottlingData.ThrottlingLevel>
                concurrentThrottlingLevels = new ArrayList<>();
        concurrentThrottlingLevels.add(
                new DisplayDeviceConfig.ThermalBrightnessThrottlingData.ThrottlingLevel(
                        DisplayDeviceConfig.convertThermalStatus(ThermalStatus.light), 0.2f
                ));
        concurrentThrottlingLevels.add(
                new DisplayDeviceConfig.ThermalBrightnessThrottlingData.ThrottlingLevel(
                        DisplayDeviceConfig.convertThermalStatus(ThermalStatus.moderate), 0.15f
                ));
        concurrentThrottlingLevels.add(
                new DisplayDeviceConfig.ThermalBrightnessThrottlingData.ThrottlingLevel(
                        DisplayDeviceConfig.convertThermalStatus(ThermalStatus.severe), 0.1f
                ));
        concurrentThrottlingLevels.add(
                new DisplayDeviceConfig.ThermalBrightnessThrottlingData.ThrottlingLevel(
                        DisplayDeviceConfig.convertThermalStatus(ThermalStatus.critical), 0.05f
                ));
        concurrentThrottlingLevels.add(
                new DisplayDeviceConfig.ThermalBrightnessThrottlingData.ThrottlingLevel(
                        DisplayDeviceConfig.convertThermalStatus(ThermalStatus.emergency), 0.025f
                ));
        concurrentThrottlingLevels.add(
                new DisplayDeviceConfig.ThermalBrightnessThrottlingData.ThrottlingLevel(
                        DisplayDeviceConfig.convertThermalStatus(ThermalStatus.shutdown), 0.0125f
                ));
        DisplayDeviceConfig.ThermalBrightnessThrottlingData concurrentThrottlingData =
                new DisplayDeviceConfig.ThermalBrightnessThrottlingData(concurrentThrottlingLevels);

        HashMap<String, DisplayDeviceConfig.ThermalBrightnessThrottlingData> throttlingDataMap =
                new HashMap<>(2);
        throttlingDataMap.put("default", defaultThrottlingData);
        throttlingDataMap.put("concurrent", concurrentThrottlingData);

        assertEquals(throttlingDataMap,
                mDisplayDeviceConfig.getThermalBrightnessThrottlingDataMapByThrottlingId());
    }

    @Test
    public void testAmbientLightSensorFromDisplayConfig() throws IOException {
        setupDisplayDeviceConfigFromDisplayConfigFile();

        assertEquals("test_light_sensor",
                mDisplayDeviceConfig.getAmbientLightSensor().type);
        assertEquals("Test Ambient Light Sensor",
                mDisplayDeviceConfig.getAmbientLightSensor().name);
        assertEquals(60, mDisplayDeviceConfig.getAmbientLightSensor().minRefreshRate, ZERO_DELTA);
        assertEquals(120, mDisplayDeviceConfig.getAmbientLightSensor().maxRefreshRate, ZERO_DELTA);
    }

    @Test
    public void testScreenOffBrightnessSensorFromDisplayConfig() throws IOException {
        setupDisplayDeviceConfigFromDisplayConfigFile();

        assertEquals("test_binned_brightness_sensor",
                mDisplayDeviceConfig.getScreenOffBrightnessSensor().type);
        assertEquals("Test Binned Brightness Sensor",
                mDisplayDeviceConfig.getScreenOffBrightnessSensor().name);

        assertArrayEquals(new int[]{ -1, 10, 20, 30, 40 },
                mDisplayDeviceConfig.getScreenOffBrightnessSensorValueToLux());

        // Low/High zone thermal maps
        assertEquals(new SurfaceControl.RefreshRateRange(30, 40),
                mDisplayDeviceConfig.getLowBlockingZoneThermalMap()
                .get(Temperature.THROTTLING_CRITICAL));
        assertEquals(new SurfaceControl.RefreshRateRange(40, 60),
                mDisplayDeviceConfig.getHighBlockingZoneThermalMap()
                .get(Temperature.THROTTLING_EMERGENCY));

        // Todo: Add asserts for DensityMapping,
        // HighBrightnessModeData AmbientLightSensor, RefreshRateLimitations and ProximitySensor.
    }

    @Test
    public void testProximitySensorFromDisplayConfig() throws IOException {
        setupDisplayDeviceConfigFromDisplayConfigFile();

        assertEquals("test_proximity_sensor",
                mDisplayDeviceConfig.getProximitySensor().type);
        assertEquals("Test Proximity Sensor",
                mDisplayDeviceConfig.getProximitySensor().name);
    }

    @Test
    public void testProximitySensorWithEmptyValuesFromDisplayConfig() throws IOException {
        setupDisplayDeviceConfigFromDisplayConfigFile(
                getContent(getValidLuxThrottling(), getProxSensorWithEmptyValues(),
                        /* includeIdleMode= */ true));
        assertNull(mDisplayDeviceConfig.getProximitySensor());
    }

    @Test
    public void testProximitySensorWithRefreshRatesFromDisplayConfig() throws IOException {
        setupDisplayDeviceConfigFromDisplayConfigFile(
                getContent(getValidLuxThrottling(), getValidProxSensorWithRefreshRateAndVsyncRate(),
                        /* includeIdleMode= */ true));
        assertEquals("test_proximity_sensor",
                mDisplayDeviceConfig.getProximitySensor().type);
        assertEquals("Test Proximity Sensor",
                mDisplayDeviceConfig.getProximitySensor().name);
        assertEquals(mDisplayDeviceConfig.getProximitySensor().minRefreshRate, 60, SMALL_DELTA);
        assertEquals(mDisplayDeviceConfig.getProximitySensor().maxRefreshRate, 90, SMALL_DELTA);
        assertThat(mDisplayDeviceConfig.getProximitySensor().supportedModes).hasSize(2);
        SupportedMode mode = mDisplayDeviceConfig.getProximitySensor().supportedModes.get(0);
        assertEquals(mode.refreshRate, 60, SMALL_DELTA);
        assertEquals(mode.vsyncRate, 65, SMALL_DELTA);
        mode = mDisplayDeviceConfig.getProximitySensor().supportedModes.get(1);
        assertEquals(mode.refreshRate, 120, SMALL_DELTA);
        assertEquals(mode.vsyncRate, 125, SMALL_DELTA);
    }

    @Test
    public void testTempSensorFromDisplayConfig() throws IOException {
        setupDisplayDeviceConfigFromDisplayConfigFile();
        assertEquals("DISPLAY", mDisplayDeviceConfig.getTempSensor().type);
        assertEquals("VIRTUAL-SKIN-DISPLAY", mDisplayDeviceConfig.getTempSensor().name);
    }

    @Test
    public void testBlockingZoneThresholdsFromDisplayConfig() throws IOException {
        setupDisplayDeviceConfigFromDisplayConfigFile();

        assertArrayEquals(new float[]{ NITS_TO_BRIGHTNESS_SPLINE.interpolate(50),
                        NITS_TO_BRIGHTNESS_SPLINE.interpolate(300),
                        NITS_TO_BRIGHTNESS_SPLINE.interpolate(300), -1},
                mDisplayDeviceConfig.getLowDisplayBrightnessThresholds(), SMALL_DELTA);
        assertArrayEquals(new float[]{50, 60, -1, 60},
                mDisplayDeviceConfig.getLowAmbientBrightnessThresholds(), ZERO_DELTA);
        assertArrayEquals(new float[]{ NITS_TO_BRIGHTNESS_SPLINE.interpolate(80),
                        NITS_TO_BRIGHTNESS_SPLINE.interpolate(100),
                        NITS_TO_BRIGHTNESS_SPLINE.interpolate(100), -1},
                mDisplayDeviceConfig.getHighDisplayBrightnessThresholds(), SMALL_DELTA);
        assertArrayEquals(new float[]{70, 80, -1, 80},
                mDisplayDeviceConfig.getHighAmbientBrightnessThresholds(), ZERO_DELTA);
    }

    @Test
    public void testBlockingZoneThresholdsFromConfigResource() {
        setupDisplayDeviceConfigFromConfigResourceFile();

        assertArrayEquals(displayBrightnessThresholdsIntToFloat(
                        LOW_BRIGHTNESS_THRESHOLD_OF_PEAK_REFRESH_RATE),
                mDisplayDeviceConfig.getLowDisplayBrightnessThresholds(), SMALL_DELTA);
        assertArrayEquals(ambientBrightnessThresholdsIntToFloat(
                        LOW_AMBIENT_THRESHOLD_OF_PEAK_REFRESH_RATE),
                mDisplayDeviceConfig.getLowAmbientBrightnessThresholds(), ZERO_DELTA);
        assertArrayEquals(displayBrightnessThresholdsIntToFloat(
                        HIGH_BRIGHTNESS_THRESHOLD_OF_PEAK_REFRESH_RATE),
                mDisplayDeviceConfig.getHighDisplayBrightnessThresholds(), SMALL_DELTA);
        assertArrayEquals(ambientBrightnessThresholdsIntToFloat(
                        HIGH_AMBIENT_THRESHOLD_OF_PEAK_REFRESH_RATE),
                mDisplayDeviceConfig.getHighAmbientBrightnessThresholds(), ZERO_DELTA);
    }

    @Test
    public void testHdrBrightnessDataFromDisplayConfig() throws IOException {
        setupDisplayDeviceConfigFromDisplayConfigFile();

        HdrBrightnessData data = mDisplayDeviceConfig.getHdrBrightnessData();

        assertNotNull(data);
        assertEquals(2, data.mMaxBrightnessLimits.size());
        assertEquals(13000, data.mBrightnessDecreaseDebounceMillis);
        assertEquals(0.1f, data.mScreenBrightnessRampDecrease, SMALL_DELTA);
        assertEquals(1000, data.mBrightnessIncreaseDebounceMillis);
        assertEquals(0.11f, data.mScreenBrightnessRampIncrease, SMALL_DELTA);

        assertEquals(0.3f, data.mMaxBrightnessLimits.get(500f), SMALL_DELTA);
        assertEquals(0.6f, data.mMaxBrightnessLimits.get(1200f), SMALL_DELTA);
    }

    private void verifyConfigValuesFromConfigResource() {
        assertNull(mDisplayDeviceConfig.getName());
        assertArrayEquals(mDisplayDeviceConfig.getAutoBrightnessBrighteningLevelsNits(),
                new float[]{2.0f, 200.0f, 600.0f}, ZERO_DELTA);
        assertArrayEquals(mDisplayDeviceConfig.getAutoBrightnessBrighteningLevelsLux(
                AUTO_BRIGHTNESS_MODE_DEFAULT, Settings.System.SCREEN_BRIGHTNESS_AUTOMATIC_NORMAL),
                new float[]{0.0f, 110.0f, 500.0f}, ZERO_DELTA);
        assertArrayEquals(mDisplayDeviceConfig.getAutoBrightnessBrighteningLevels(
                AUTO_BRIGHTNESS_MODE_DEFAULT, Settings.System.SCREEN_BRIGHTNESS_AUTOMATIC_NORMAL),
                new float[]{brightnessIntToFloat(50), brightnessIntToFloat(100),
                        brightnessIntToFloat(150)}, SMALL_DELTA);

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
        assertEquals(0, mDisplayDeviceConfig.getRefreshRangeProfiles().size());
        assertEquals(mDisplayDeviceConfig.getDefaultRefreshRateInHbmSunlight(),
                DEFAULT_REFRESH_RATE_IN_HBM_SUNLIGHT);
        assertEquals(mDisplayDeviceConfig.getDefaultRefreshRateInHbmHdr(),
                DEFAULT_REFRESH_RATE_IN_HBM_HDR);

        assertEquals("test_light_sensor", mDisplayDeviceConfig.getAmbientLightSensor().type);
        assertEquals("", mDisplayDeviceConfig.getAmbientLightSensor().name);
        assertTrue(mDisplayDeviceConfig.isAutoBrightnessAvailable());

        assertEquals(brightnessIntToFloat(35),
                mDisplayDeviceConfig.getBrightnessCapForWearBedtimeMode(), ZERO_DELTA);
    }

    @Test
    public void testLightDebounceFromDisplayConfig() throws IOException {
        setupDisplayDeviceConfigFromDisplayConfigFile();

        assertEquals(mDisplayDeviceConfig.getAutoBrightnessBrighteningLightDebounce(), 2000);
        assertEquals(mDisplayDeviceConfig.getAutoBrightnessDarkeningLightDebounce(), 1000);
        assertEquals(mDisplayDeviceConfig.getAutoBrightnessBrighteningLightDebounceIdle(), 2500);
        assertEquals(mDisplayDeviceConfig.getAutoBrightnessDarkeningLightDebounceIdle(), 1500);
    }

    @Test
    public void testBrightnessRamps() throws IOException {
        setupDisplayDeviceConfigFromDisplayConfigFile();

        assertEquals(mDisplayDeviceConfig.getBrightnessRampDecreaseMaxMillis(), 3000);
        assertEquals(mDisplayDeviceConfig.getBrightnessRampIncreaseMaxMillis(), 2000);
        assertEquals(mDisplayDeviceConfig.getBrightnessRampDecreaseMaxIdleMillis(), 5000);
        assertEquals(mDisplayDeviceConfig.getBrightnessRampIncreaseMaxIdleMillis(), 4000);
        assertEquals(mDisplayDeviceConfig.getBrightnessRampFastDecrease(), 0.01f, ZERO_DELTA);
        assertEquals(mDisplayDeviceConfig.getBrightnessRampFastIncrease(), 0.02f, ZERO_DELTA);
        assertEquals(mDisplayDeviceConfig.getBrightnessRampSlowDecrease(), 0.03f, ZERO_DELTA);
        assertEquals(mDisplayDeviceConfig.getBrightnessRampSlowIncrease(), 0.04f, ZERO_DELTA);
        assertEquals(mDisplayDeviceConfig.getBrightnessRampSlowDecreaseIdle(), 0.05f, ZERO_DELTA);
        assertEquals(mDisplayDeviceConfig.getBrightnessRampSlowIncreaseIdle(), 0.06f, ZERO_DELTA);
    }

    @Test
    public void testBrightnessRamps_IdleFallsBackToConfigInteractive() throws IOException {
        setupDisplayDeviceConfigFromDisplayConfigFile(getContent(getValidLuxThrottling(),
                getValidProxSensor(), /* includeIdleMode= */ false));

        assertEquals(mDisplayDeviceConfig.getBrightnessRampDecreaseMaxMillis(), 3000);
        assertEquals(mDisplayDeviceConfig.getBrightnessRampIncreaseMaxMillis(), 2000);
        assertEquals(mDisplayDeviceConfig.getBrightnessRampDecreaseMaxIdleMillis(), 3000);
        assertEquals(mDisplayDeviceConfig.getBrightnessRampIncreaseMaxIdleMillis(), 2000);
        assertEquals(mDisplayDeviceConfig.getBrightnessRampFastDecrease(), 0.01f, ZERO_DELTA);
        assertEquals(mDisplayDeviceConfig.getBrightnessRampFastIncrease(), 0.02f, ZERO_DELTA);
        assertEquals(mDisplayDeviceConfig.getBrightnessRampSlowDecrease(), 0.03f, ZERO_DELTA);
        assertEquals(mDisplayDeviceConfig.getBrightnessRampSlowIncrease(), 0.04f, ZERO_DELTA);
        assertEquals(mDisplayDeviceConfig.getBrightnessRampSlowDecreaseIdle(), 0.03f, ZERO_DELTA);
        assertEquals(mDisplayDeviceConfig.getBrightnessRampSlowIncreaseIdle(), 0.04f, ZERO_DELTA);
    }

    @Test
    public void testBrightnessCapForWearBedtimeMode() throws IOException {
        setupDisplayDeviceConfigFromDisplayConfigFile(getContent(getValidLuxThrottling(),
                getValidProxSensor(), /* includeIdleMode= */ false));
        assertEquals(0.1f, mDisplayDeviceConfig.getBrightnessCapForWearBedtimeMode(), ZERO_DELTA);
    }

    @Test
    public void testAutoBrightnessBrighteningLevels() throws IOException {
        setupDisplayDeviceConfigFromDisplayConfigFile(getContent(getValidLuxThrottling(),
                getValidProxSensor(), /* includeIdleMode= */ false));

        assertArrayEquals(new float[]{0.0f, 80},
                mDisplayDeviceConfig.getAutoBrightnessBrighteningLevelsLux(
                        AUTO_BRIGHTNESS_MODE_DEFAULT,
                        Settings.System.SCREEN_BRIGHTNESS_AUTOMATIC_NORMAL), ZERO_DELTA);
        assertArrayEquals(new float[]{0.2f, 0.3f},
                mDisplayDeviceConfig.getAutoBrightnessBrighteningLevels(
                        AUTO_BRIGHTNESS_MODE_DEFAULT,
                        Settings.System.SCREEN_BRIGHTNESS_AUTOMATIC_NORMAL), SMALL_DELTA);

        assertArrayEquals(new float[]{0.0f, 90},
                mDisplayDeviceConfig.getAutoBrightnessBrighteningLevelsLux(
                        AUTO_BRIGHTNESS_MODE_DEFAULT,
                        Settings.System.SCREEN_BRIGHTNESS_AUTOMATIC_DIM), ZERO_DELTA);
        assertArrayEquals(new float[]{0.3f, 0.4f},
                mDisplayDeviceConfig.getAutoBrightnessBrighteningLevels(
                        AUTO_BRIGHTNESS_MODE_DEFAULT,
                        Settings.System.SCREEN_BRIGHTNESS_AUTOMATIC_DIM), SMALL_DELTA);

        assertArrayEquals(new float[]{0.0f, 80},
                mDisplayDeviceConfig.getAutoBrightnessBrighteningLevelsLux(
                        AUTO_BRIGHTNESS_MODE_DEFAULT,
                        Settings.System.SCREEN_BRIGHTNESS_AUTOMATIC_BRIGHT), ZERO_DELTA);
        assertArrayEquals(new float[]{0.6f, 0.7f},
                mDisplayDeviceConfig.getAutoBrightnessBrighteningLevels(
                        AUTO_BRIGHTNESS_MODE_DEFAULT,
                        Settings.System.SCREEN_BRIGHTNESS_AUTOMATIC_BRIGHT), SMALL_DELTA);

        assertArrayEquals(new float[]{0.0f, 95},
                mDisplayDeviceConfig.getAutoBrightnessBrighteningLevelsLux(
                        AUTO_BRIGHTNESS_MODE_DOZE,
                        Settings.System.SCREEN_BRIGHTNESS_AUTOMATIC_NORMAL), ZERO_DELTA);
        assertArrayEquals(new float[]{0.35f, 0.45f},
                mDisplayDeviceConfig.getAutoBrightnessBrighteningLevels(
                        AUTO_BRIGHTNESS_MODE_DOZE,
                        Settings.System.SCREEN_BRIGHTNESS_AUTOMATIC_NORMAL), SMALL_DELTA);

        assertArrayEquals(new float[]{0.0f, 100},
                mDisplayDeviceConfig.getAutoBrightnessBrighteningLevelsLux(
                        AUTO_BRIGHTNESS_MODE_DOZE,
                        Settings.System.SCREEN_BRIGHTNESS_AUTOMATIC_BRIGHT), ZERO_DELTA);
        assertArrayEquals(new float[]{0.4f, 0.5f},
                mDisplayDeviceConfig.getAutoBrightnessBrighteningLevels(
                        AUTO_BRIGHTNESS_MODE_DOZE,
                        Settings.System.SCREEN_BRIGHTNESS_AUTOMATIC_BRIGHT), SMALL_DELTA);

        // Should fall back to the normal preset
        assertArrayEquals(new float[]{0.0f, 95},
                mDisplayDeviceConfig.getAutoBrightnessBrighteningLevelsLux(
                        AUTO_BRIGHTNESS_MODE_DOZE,
                        Settings.System.SCREEN_BRIGHTNESS_AUTOMATIC_DIM), ZERO_DELTA);
        assertArrayEquals(new float[]{0.35f, 0.45f},
                mDisplayDeviceConfig.getAutoBrightnessBrighteningLevels(
                        AUTO_BRIGHTNESS_MODE_DOZE,
                        Settings.System.SCREEN_BRIGHTNESS_AUTOMATIC_DIM), SMALL_DELTA);
    }

    @Test
    public void testAutoBrightnessBrighteningLevels_FeatureFlagOff() throws IOException {
        when(mFlags.areAutoBrightnessModesEnabled()).thenReturn(false);
        setupDisplayDeviceConfigFromConfigResourceFile();
        setupDisplayDeviceConfigFromDisplayConfigFile(getContent(getValidLuxThrottling(),
                getValidProxSensor(), /* includeIdleMode= */ false));

        assertArrayEquals(new float[]{brightnessIntToFloat(50), brightnessIntToFloat(100),
                        brightnessIntToFloat(150)},
                mDisplayDeviceConfig.getAutoBrightnessBrighteningLevels(
                        AUTO_BRIGHTNESS_MODE_DEFAULT,
                        Settings.System.SCREEN_BRIGHTNESS_AUTOMATIC_NORMAL), SMALL_DELTA);
        assertArrayEquals(new float[]{0, 110, 500},
                mDisplayDeviceConfig.getAutoBrightnessBrighteningLevelsLux(
                        AUTO_BRIGHTNESS_MODE_DEFAULT,
                        Settings.System.SCREEN_BRIGHTNESS_AUTOMATIC_NORMAL), ZERO_DELTA);
        assertArrayEquals(new float[]{2, 200, 600},
                mDisplayDeviceConfig.getAutoBrightnessBrighteningLevelsNits(), SMALL_DELTA);
    }

    @Test
    public void testIsAutoBrightnessAvailable_EnabledInConfigResource() throws IOException {
        when(mResources.getBoolean(R.bool.config_automatic_brightness_available)).thenReturn(true);

        setupDisplayDeviceConfigFromDisplayConfigFile();

        assertTrue(mDisplayDeviceConfig.isAutoBrightnessAvailable());
    }

    @Test
    public void testIsAutoBrightnessAvailable_DisabledInConfigResource() throws IOException {
        when(mResources.getBoolean(R.bool.config_automatic_brightness_available)).thenReturn(false);

        setupDisplayDeviceConfigFromDisplayConfigFile();

        assertFalse(mDisplayDeviceConfig.isAutoBrightnessAvailable());
    }

    private String getValidLuxThrottling() {
        return "<luxThrottling>\n"
                + "    <brightnessLimitMap>\n"
                + "        <type>adaptive</type>\n"
                + "        <map>\n"
                + "            <point>"
                + "                <first>1000</first>\n"
                + "                <second>0.3</second>\n"
                + "            </point>"
                + "            <point>"
                + "                <first>5000</first>\n"
                + "                <second>0.5</second>\n"
                + "            </point>"
                + "        </map>\n"
                + "    </brightnessLimitMap>\n"
                + "    <brightnessLimitMap>\n"
                + "        <type>default</type>\n"
                + "        <map>\n"
                + "            <point>"
                + "                <first>1500</first>\n"
                + "                <second>0.35</second>\n"
                + "            </point>"
                + "            <point>"
                + "                <first>5500</first>\n"
                + "                <second>0.55</second>\n"
                + "            </point>"
                + "        </map>\n"
                + "    </brightnessLimitMap>\n"
                + "</luxThrottling>";
    }

    private String getInvalidLuxThrottling() {
        return "<luxThrottling>\n"
                + "    <brightnessLimitMap>\n"
                + "        <type>adaptive</type>\n"
                + "        <map>\n"
                + "            <point>"
                + "                <first>1000</first>\n"
                + "                <second>0.3</second>\n"
                + "            </point>"
                + "            <point>" // second > hbm.transitionPoint, skipped
                + "                <first>1500</first>\n"
                + "                <second>0.9</second>\n"
                + "            </point>"
                + "            <point>" // same lux value, skipped
                + "                <first>1000</first>\n"
                + "                <second>0.5</second>\n"
                + "            </point>"
                + "        </map>\n"
                + "    </brightnessLimitMap>\n"
                + "    <brightnessLimitMap>\n" // Same type, skipped
                + "        <type>adaptive</type>\n"
                + "        <map>\n"
                + "            <point>"
                + "                <first>2000</first>\n"
                + "                <second>0.35</second>\n"
                + "            </point>"
                + "            <point>"
                + "                <first>6000</first>\n"
                + "                <second>0.55</second>\n"
                + "            </point>"
                + "        </map>\n"
                + "    </brightnessLimitMap>\n"
                + "    <brightnessLimitMap>\n" // Invalid points only, skipped
                + "        <type>default</type>\n"
                + "        <map>\n"
                + "            <point>"
                + "                <first>2500</first>\n"
                + "                <second>0.99</second>\n"
                + "            </point>"
                + "        </map>\n"
                + "    </brightnessLimitMap>\n"
                + "</luxThrottling>";
    }

    private String getRefreshThermalThrottlingMaps() {
        return "<refreshRateThrottlingMap>\n"
               + "    <refreshRateThrottlingPoint>\n"
               + "        <thermalStatus>critical</thermalStatus>\n"
               + "        <refreshRateRange>\n"
               + "            <minimum>30</minimum>\n"
               + "            <maximum>60</maximum>\n"
               + "        </refreshRateRange>\n"
               + "    </refreshRateThrottlingPoint>\n"
               + "    <refreshRateThrottlingPoint>\n"
               + "        <thermalStatus>shutdown</thermalStatus>\n"
               + "        <refreshRateRange>\n"
               + "            <minimum>0</minimum>\n"
               + "            <maximum>30</maximum>\n"
               + "        </refreshRateRange>\n"
               + "    </refreshRateThrottlingPoint>\n"
               + "</refreshRateThrottlingMap>\n"
               + "<refreshRateThrottlingMap id=\"thermalLow\">\n"
               + "    <refreshRateThrottlingPoint>\n"
               + "        <thermalStatus>critical</thermalStatus>\n"
               + "        <refreshRateRange>\n"
               + "            <minimum>30</minimum>\n"
               + "            <maximum>40</maximum>\n"
               + "        </refreshRateRange>\n"
               + "    </refreshRateThrottlingPoint>\n"
               + "</refreshRateThrottlingMap>\n"
               + "<refreshRateThrottlingMap id=\"thermalHigh\">\n"
               + "    <refreshRateThrottlingPoint>\n"
               + "        <thermalStatus>emergency</thermalStatus>\n"
               + "        <refreshRateRange>\n"
               + "            <minimum>40</minimum>\n"
               + "            <maximum>60</maximum>\n"
               + "        </refreshRateRange>\n"
               + "    </refreshRateThrottlingPoint>\n"
               + "</refreshRateThrottlingMap>\n"
               + "<refreshRateThrottlingMap id=\"test\">\n"
               + "    <refreshRateThrottlingPoint>\n"
               + "        <thermalStatus>emergency</thermalStatus>\n"
               + "        <refreshRateRange>\n"
               + "            <minimum>60</minimum>\n"
               + "            <maximum>90</maximum>\n"
               + "        </refreshRateRange>\n"
               + "    </refreshRateThrottlingPoint>\n"
               + "</refreshRateThrottlingMap>\n";
    }

    private String getValidProxSensor() {
        return "<proxSensor>\n"
                +   "<type>test_proximity_sensor</type>\n"
                +   "<name>Test Proximity Sensor</name>\n"
                + "</proxSensor>\n";
    }

    private String getValidProxSensorWithRefreshRateAndVsyncRate() {
        return "<proxSensor>\n"
                +   "<type>test_proximity_sensor</type>\n"
                +   "<name>Test Proximity Sensor</name>\n"
                +   "<refreshRate>\n"
                +       "<minimum>60</minimum>\n"
                +       "<maximum>90</maximum>\n"
                +   "</refreshRate>\n"
                +   "<supportedModes>\n"
                +       "<point>\n"
                +           "<first>60</first>\n"   // refreshRate
                +           "<second>65</second>\n" //vsyncRate
                +       "</point>\n"
                +       "<point>\n"
                +           "<first>120</first>\n"   // refreshRate
                +           "<second>125</second>\n" //vsyncRate
                +       "</point>\n"
                +   "</supportedModes>"
                + "</proxSensor>\n";
    }

    private String getProxSensorWithEmptyValues() {
        return "<proxSensor>\n"
                +   "<type></type>\n"
                +   "<name></name>\n"
                + "</proxSensor>\n";
    }

    private String getHdrBrightnessConfig() {
        return "<hdrBrightnessConfig>\n"
              + "    <brightnessMap>\n"
              + "        <point>\n"
              + "            <first>500</first>\n"
              + "            <second>0.3</second>\n"
              + "        </point>\n"
              + "        <point>\n"
              + "           <first>1200</first>\n"
              + "           <second>0.6</second>\n"
              + "        </point>\n"
              + "    </brightnessMap>\n"
              + "    <brightnessIncreaseDebounceMillis>1000</brightnessIncreaseDebounceMillis>\n"
              + "    <screenBrightnessRampIncrease>0.11</screenBrightnessRampIncrease>\n"
              + "    <brightnessDecreaseDebounceMillis>13000</brightnessDecreaseDebounceMillis>\n"
              + "    <screenBrightnessRampDecrease>0.1</screenBrightnessRampDecrease>\n"
              + "</hdrBrightnessConfig>";
    }

    private String getRampSpeedsIdle() {
        return "<brighteningLightDebounceIdleMillis>"
                +           "2500"
                +       "</brighteningLightDebounceIdleMillis>\n"
                +       "<darkeningLightDebounceIdleMillis>"
                +           "1500"
                +       "</darkeningLightDebounceIdleMillis>\n";
    }

    private String getThresholdsIdle() {
        return  "<ambientBrightnessChangeThresholdsIdle>\n"
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
                +   "</displayBrightnessChangeThresholdsIdle>\n";
    }

    private String getScreenBrightnessRampSlowIdle() {
        return "<screenBrightnessRampSlowDecreaseIdle>"
                +       "0.05"
                +   "</screenBrightnessRampSlowDecreaseIdle>\n"
                +   "<screenBrightnessRampSlowIncreaseIdle>"
                +       "0.06"
                +   "</screenBrightnessRampSlowIncreaseIdle>\n";
    }

    private String getPowerThrottlingConfig() {
        return  "<powerThrottlingConfig >\n"
                +       "<brightnessLowestCapAllowed>0.1</brightnessLowestCapAllowed>\n"
                +       "<pollingWindowMillis>10</pollingWindowMillis>\n"
                +       "<powerThrottlingMap>\n"
                +           "<powerThrottlingPoint>\n"
                +               "<thermalStatus>light</thermalStatus>\n"
                +               "<powerQuotaMilliWatts>800</powerQuotaMilliWatts>\n"
                +        "</powerThrottlingPoint>\n"
                +           "<powerThrottlingPoint>\n"
                +               "<thermalStatus>moderate</thermalStatus>\n"
                +               "<powerQuotaMilliWatts>600</powerQuotaMilliWatts>\n"
                +           "</powerThrottlingPoint>\n"
                +           "<powerThrottlingPoint>\n"
                +               "<thermalStatus>severe</thermalStatus>\n"
                +               "<powerQuotaMilliWatts>400</powerQuotaMilliWatts>\n"
                +           "</powerThrottlingPoint>\n"
                +           "<powerThrottlingPoint>\n"
                +               "<thermalStatus>critical</thermalStatus>\n"
                +               "<powerQuotaMilliWatts>200</powerQuotaMilliWatts>\n"
                +           "</powerThrottlingPoint>\n"
                +           "<powerThrottlingPoint>\n"
                +               "<thermalStatus>emergency</thermalStatus>\n"
                +               "<powerQuotaMilliWatts>100</powerQuotaMilliWatts>\n"
                +           "</powerThrottlingPoint>\n"
                +           "<powerThrottlingPoint>\n"
                +               "<thermalStatus>shutdown</thermalStatus>\n"
                +               "<powerQuotaMilliWatts>50</powerQuotaMilliWatts>\n"
                +           "</powerThrottlingPoint>\n"
                +       "</powerThrottlingMap>\n"
                +       "<powerThrottlingMap id=\"concurrent\">\n"
                +           "<powerThrottlingPoint>\n"
                +               "<thermalStatus>light</thermalStatus>\n"
                +               "<powerQuotaMilliWatts>800</powerQuotaMilliWatts>\n"
                +           "</powerThrottlingPoint>\n"
                +           "<powerThrottlingPoint>\n"
                +               "<thermalStatus>moderate</thermalStatus>\n"
                +               "<powerQuotaMilliWatts>600</powerQuotaMilliWatts>\n"
                +           "</powerThrottlingPoint>\n"
                +           "<powerThrottlingPoint>\n"
                +               "<thermalStatus>severe</thermalStatus>\n"
                +               "<powerQuotaMilliWatts>400</powerQuotaMilliWatts>\n"
                +           "</powerThrottlingPoint>\n"
                +           "<powerThrottlingPoint>\n"
                +               "<thermalStatus>critical</thermalStatus>\n"
                +               "<powerQuotaMilliWatts>200</powerQuotaMilliWatts>\n"
                +           "</powerThrottlingPoint>\n"
                +           "<powerThrottlingPoint>\n"
                +               "<thermalStatus>emergency</thermalStatus>\n"
                +               "<powerQuotaMilliWatts>100</powerQuotaMilliWatts>\n"
                +           "</powerThrottlingPoint>\n"
                +           "<powerThrottlingPoint>\n"
                +               "<thermalStatus>shutdown</thermalStatus>\n"
                +               "<powerQuotaMilliWatts>50</powerQuotaMilliWatts>\n"
                +           "</powerThrottlingPoint>\n"
                +       "</powerThrottlingMap>\n"
                +   "</powerThrottlingConfig>\n";
    }
    private String getScreenBrightnessRampCapsIdle() {
        return "<screenBrightnessRampIncreaseMaxIdleMillis>"
                +       "4000"
                +   "</screenBrightnessRampIncreaseMaxIdleMillis>\n"
                +   "<screenBrightnessRampDecreaseMaxIdleMillis>"
                +       "5000"
                +   "</screenBrightnessRampDecreaseMaxIdleMillis>\n";
    }

    private String getContent() {
        return getContent(getValidLuxThrottling(), getValidProxSensor(),
                /* includeIdleMode= */ true);
    }

    private String getContent(String brightnessCapConfig, String proxSensor,
            boolean includeIdleMode) {
        return "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                + "<displayConfiguration>\n"
                +   "<name>Example Display</name>\n"
                +   "<densityMapping>\n"
                +       "<density>\n"
                +           "<height>480</height>\n"
                +           "<width>720</width>\n"
                +           "<density>120</density>\n"
                +       "</density>\n"
                +       "<density>\n"
                +           "<height>720</height>\n"
                +           "<width>1280</width>\n"
                +           "<density>213</density>\n"
                +       "</density>\n"
                +       "<density>\n"
                +           "<height>1080</height>\n"
                +           "<width>1920</width>\n"
                +           "<density>320</density>\n"
                +       "</density>\n"
                +       "<density>\n"
                +           "<height>2160</height>\n"
                +           "<width>3840</width>\n"
                +           "<density>640</density>\n"
                +       "</density>\n"
                +   "</densityMapping>\n"
                +   "<screenBrightnessMap>\n"
                +       "<point>\n"
                +           "<value>" + BRIGHTNESS[0] + "</value>\n"
                +           "<nits>" + NITS[0] + "</nits>\n"
                +       "</point>\n"
                +       "<point>\n"
                +           "<value>" + BRIGHTNESS[1] + "</value>\n"
                +           "<nits>" + NITS[1] + "</nits>\n"
                +       "</point>\n"
                +       "<point>\n"
                +           "<value>" + BRIGHTNESS[2] + "</value>\n"
                +           "<nits>" + NITS[2] + "</nits>\n"
                +       "</point>\n"
                +   "</screenBrightnessMap>\n"
                +   "<autoBrightness enabled=\"true\">\n"
                +       "<brighteningLightDebounceMillis>2000</brighteningLightDebounceMillis>\n"
                +       "<darkeningLightDebounceMillis>1000</darkeningLightDebounceMillis>\n"
                + (includeIdleMode ? getRampSpeedsIdle() : "")
                +       "<luxToBrightnessMapping>\n"
                +           "<map>\n"
                +               "<point>\n"
                +                   "<first>0</first>\n"
                +                   "<second>0.2</second>\n"
                +               "</point>\n"
                +               "<point>\n"
                +                   "<first>80</first>\n"
                +                   "<second>0.3</second>\n"
                +               "</point>\n"
                +           "</map>\n"
                +       "</luxToBrightnessMapping>\n"
                +       "<luxToBrightnessMapping>\n"
                +           "<setting>dim</setting>\n"
                +           "<map>\n"
                +               "<point>\n"
                +                   "<first>0</first>\n"
                +                   "<second>0.3</second>\n"
                +               "</point>\n"
                +               "<point>\n"
                +                   "<first>90</first>\n"
                +                   "<second>0.4</second>\n"
                +               "</point>\n"
                +           "</map>\n"
                +       "</luxToBrightnessMapping>\n"
                +       "<luxToBrightnessMapping>\n"
                +           "<mode>default</mode>\n"
                +           "<setting>bright</setting>\n"
                +           "<map>\n"
                +               "<point>\n"
                +                   "<first>0</first>\n"
                +                   "<second>0.6</second>\n"
                +               "</point>\n"
                +               "<point>\n"
                +                   "<first>80</first>\n"
                +                   "<second>0.7</second>\n"
                +               "</point>\n"
                +           "</map>\n"
                +       "</luxToBrightnessMapping>\n"
                +       "<luxToBrightnessMapping>\n"
                +           "<mode>doze</mode>\n"
                +           "<map>\n"
                +               "<point>\n"
                +                   "<first>0</first>\n"
                +                   "<second>0.35</second>\n"
                +               "</point>\n"
                +               "<point>\n"
                +                   "<first>95</first>\n"
                +                   "<second>0.45</second>\n"
                +               "</point>\n"
                +           "</map>\n"
                +       "</luxToBrightnessMapping>\n"
                +       "<luxToBrightnessMapping>\n"
                +           "<mode>doze</mode>\n"
                +           "<setting>bright</setting>\n"
                +           "<map>\n"
                +               "<point>\n"
                +                   "<first>0</first>\n"
                +                   "<second>0.4</second>\n"
                +               "</point>\n"
                +               "<point>\n"
                +                   "<first>100</first>\n"
                +                   "<second>0.5</second>\n"
                +               "</point>\n"
                +           "</map>\n"
                +       "</luxToBrightnessMapping>\n"
                +   "</autoBrightness>\n"
                +  getPowerThrottlingConfig()
                +   "<highBrightnessMode enabled=\"true\">\n"
                +       "<transitionPoint>" + BRIGHTNESS[1] + "</transitionPoint>\n"
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
                +       "<allowInLowPowerMode>false</allowInLowPowerMode>\n"
                +       "<minimumHdrPercentOfScreen>0.6</minimumHdrPercentOfScreen>\n"
                +       "<sdrHdrRatioMap>\n"
                +            "<point>\n"
                +                "<sdrNits>2.000</sdrNits>\n"
                +                "<hdrRatio>4.000</hdrRatio>\n"
                +            "</point>\n"
                +            "<point>\n"
                +                "<sdrNits>500.0</sdrNits>\n"
                +                "<hdrRatio>1.6</hdrRatio>\n"
                +            "</point>\n"
                +       "</sdrHdrRatioMap>\n"
                +   "</highBrightnessMode>\n"
                + getHdrBrightnessConfig()
                + brightnessCapConfig
                +   "<lightSensor>\n"
                +       "<type>test_light_sensor</type>\n"
                +       "<name>Test Ambient Light Sensor</name>\n"
                +       "<refreshRate>\n"
                +           "<minimum>60</minimum>\n"
                +           "<maximum>120</maximum>\n"
                +       "</refreshRate>\n"
                +   "</lightSensor>\n"
                +   "<screenOffBrightnessSensor>\n"
                +       "<type>test_binned_brightness_sensor</type>\n"
                +       "<name>Test Binned Brightness Sensor</name>\n"
                +   "</screenOffBrightnessSensor>\n"
                + proxSensor
                +   "<tempSensor>\n"
                +       "<type>DISPLAY</type>\n"
                +       "<name>VIRTUAL-SKIN-DISPLAY</name>\n"
                +   "</tempSensor>\n"
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
                + (includeIdleMode ?  getThresholdsIdle() : "")
                +   "<screenBrightnessRampFastDecrease>0.01</screenBrightnessRampFastDecrease>\n"
                +   "<screenBrightnessRampFastIncrease>0.02</screenBrightnessRampFastIncrease>\n"
                +   "<screenBrightnessRampSlowDecrease>0.03</screenBrightnessRampSlowDecrease>\n"
                +   "<screenBrightnessRampSlowIncrease>0.04</screenBrightnessRampSlowIncrease>\n"
                + (includeIdleMode ? getScreenBrightnessRampSlowIdle() : "")
                +   "<screenBrightnessRampIncreaseMaxMillis>"
                +       "2000"
                +   "</screenBrightnessRampIncreaseMaxMillis>\n"
                +   "<screenBrightnessRampDecreaseMaxMillis>"
                +       "3000"
                +   "</screenBrightnessRampDecreaseMaxMillis>\n"
                + (includeIdleMode ?  getScreenBrightnessRampCapsIdle() : "")
                +   "<ambientLightHorizonLong>5000</ambientLightHorizonLong>\n"
                +   "<ambientLightHorizonShort>50</ambientLightHorizonShort>\n"
                +   "<screenBrightnessRampIncreaseMaxMillis>"
                +       "2000"
                +   "</screenBrightnessRampIncreaseMaxMillis>\n"
                +   "<thermalThrottling>\n"
                +       "<brightnessThrottlingMap>\n"
                +           "<brightnessThrottlingPoint>\n"
                +               "<thermalStatus>light</thermalStatus>\n"
                +               "<brightness>0.4</brightness>\n"
                +           "</brightnessThrottlingPoint>\n"
                +           "<brightnessThrottlingPoint>\n"
                +               "<thermalStatus>moderate</thermalStatus>\n"
                +               "<brightness>0.3</brightness>\n"
                +           "</brightnessThrottlingPoint>\n"
                +           "<brightnessThrottlingPoint>\n"
                +               "<thermalStatus>severe</thermalStatus>\n"
                +               "<brightness>0.2</brightness>\n"
                +           "</brightnessThrottlingPoint>\n"
                +           "<brightnessThrottlingPoint>\n"
                +               "<thermalStatus>critical</thermalStatus>\n"
                +               "<brightness>0.1</brightness>\n"
                +           "</brightnessThrottlingPoint>\n"
                +           "<brightnessThrottlingPoint>\n"
                +               "<thermalStatus>emergency</thermalStatus>\n"
                +               "<brightness>0.05</brightness>\n"
                +           "</brightnessThrottlingPoint>\n"
                +           "<brightnessThrottlingPoint>\n"
                +               "<thermalStatus>shutdown</thermalStatus>\n"
                +               "<brightness>0.025</brightness>\n"
                +           "</brightnessThrottlingPoint>\n"
                +       "</brightnessThrottlingMap>\n"
                +       "<brightnessThrottlingMap id=\"concurrent\">\n"
                +           "<brightnessThrottlingPoint>\n"
                +               "<thermalStatus>light</thermalStatus>\n"
                +               "<brightness>0.2</brightness>\n"
                +           "</brightnessThrottlingPoint>\n"
                +           "<brightnessThrottlingPoint>\n"
                +               "<thermalStatus>moderate</thermalStatus>\n"
                +               "<brightness>0.15</brightness>\n"
                +           "</brightnessThrottlingPoint>\n"
                +           "<brightnessThrottlingPoint>\n"
                +               "<thermalStatus>severe</thermalStatus>\n"
                +               "<brightness>0.1</brightness>\n"
                +           "</brightnessThrottlingPoint>\n"
                +           "<brightnessThrottlingPoint>\n"
                +               "<thermalStatus>critical</thermalStatus>\n"
                +               "<brightness>0.05</brightness>\n"
                +           "</brightnessThrottlingPoint>\n"
                +           "<brightnessThrottlingPoint>\n"
                +               "<thermalStatus>emergency</thermalStatus>\n"
                +               "<brightness>0.025</brightness>\n"
                +           "</brightnessThrottlingPoint>\n"
                +           "<brightnessThrottlingPoint>\n"
                +               "<thermalStatus>shutdown</thermalStatus>\n"
                +               "<brightness>0.0125</brightness>\n"
                +           "</brightnessThrottlingPoint>\n"
                +       "</brightnessThrottlingMap>\n"
                +  getRefreshThermalThrottlingMaps()
                +   "</thermalThrottling>\n"
                +   "<refreshRate>\n"
                +       "<defaultRefreshRate>45</defaultRefreshRate>\n"
                +       "<defaultPeakRefreshRate>85</defaultPeakRefreshRate>\n"
                +       "<refreshRateZoneProfiles>"
                +           "<refreshRateZoneProfile id=\"test1\">"
                +               "<refreshRateRange>\n"
                +                   "<minimum>60</minimum>\n"
                +                   "<maximum>60</maximum>\n"
                +               "</refreshRateRange>\n"
                +           "</refreshRateZoneProfile>\n"
                +           "<refreshRateZoneProfile id=\"test2\">"
                +               "<refreshRateRange>\n"
                +                   "<minimum>80</minimum>\n"
                +                   "<maximum>90</maximum>\n"
                +               "</refreshRateRange>\n"
                +           "</refreshRateZoneProfile>\n"
                +       "</refreshRateZoneProfiles>"
                +       "<defaultRefreshRateInHbmHdr>82</defaultRefreshRateInHbmHdr>\n"
                +       "<defaultRefreshRateInHbmSunlight>83</defaultRefreshRateInHbmSunlight>\n"
                +       "<lowerBlockingZoneConfigs>\n"
                +           "<defaultRefreshRate>75</defaultRefreshRate>\n"
                +           "<refreshRateThermalThrottlingId>thermalLow"
                +           "</refreshRateThermalThrottlingId>\n"
                +           "<blockingZoneThreshold>\n"
                +               "<displayBrightnessPoint>\n"
                +                   "<lux>50</lux>\n"
                +                   "<nits>50</nits>\n"
                +               "</displayBrightnessPoint>\n"
                +               "<displayBrightnessPoint>\n"
                +                   "<lux>60</lux>\n"
                +                   "<nits>300</nits>\n"
                +               "</displayBrightnessPoint>\n"
                +               "<displayBrightnessPoint>\n"
                +                   "<lux>-1</lux>\n"
                +                   "<nits>300</nits>\n"
                +               "</displayBrightnessPoint>\n"
                +               "<displayBrightnessPoint>\n"
                +                   "<lux>60</lux>\n"
                +                   "<nits>-1</nits>\n"
                +               "</displayBrightnessPoint>\n"
                +           "</blockingZoneThreshold>\n"
                +       "</lowerBlockingZoneConfigs>\n"
                +       "<higherBlockingZoneConfigs>\n"
                +           "<defaultRefreshRate>90</defaultRefreshRate>\n"
                +           "<refreshRateThermalThrottlingId>thermalHigh"
                +           "</refreshRateThermalThrottlingId>\n"
                +           "<blockingZoneThreshold>\n"
                +               "<displayBrightnessPoint>\n"
                +                   "<lux>70</lux>\n"
                +                   "<nits>80</nits>\n"
                +               "</displayBrightnessPoint>\n"
                +               "<displayBrightnessPoint>\n"
                +                   "<lux>80</lux>\n"
                +                   "<nits>100</nits>\n"
                +               "</displayBrightnessPoint>\n"
                +               "<displayBrightnessPoint>\n"
                +                   "<lux>-1</lux>\n"
                +                   "<nits>100</nits>\n"
                +               "</displayBrightnessPoint>\n"
                +               "<displayBrightnessPoint>\n"
                +                   "<lux>80</lux>\n"
                +                   "<nits>-1</nits>\n"
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
                +   "<usiVersion>\n"
                +       "<majorVersion>2</majorVersion>\n"
                +       "<minorVersion>0</minorVersion>\n"
                +   "</usiVersion>\n"
                +   "<screenBrightnessCapForWearBedtimeMode>"
                +       "0.1"
                +   "</screenBrightnessCapForWearBedtimeMode>"
                + "</displayConfiguration>\n";
    }

    private void mockDeviceConfigs() {
        when(mResources.getFloat(com.android.internal.R.dimen
                .config_screenBrightnessSettingDefaultFloat)).thenReturn(0.5f);
        when(mResources.getFloat(com.android.internal.R.dimen
                .config_screenBrightnessSettingMaximumFloat)).thenReturn(1.0f);
    }

    private void setupDisplayDeviceConfigFromDisplayConfigFile() throws IOException {
        setupDisplayDeviceConfigFromDisplayConfigFile(getContent());
    }

    private void setupDisplayDeviceConfigFromDisplayConfigFile(String content) throws IOException {
        Path tempFile = Files.createTempFile("display_config", ".tmp");
        Files.write(tempFile, content.getBytes(StandardCharsets.UTF_8));
        mDisplayDeviceConfig = new DisplayDeviceConfig(mContext, mFlags);
        mDisplayDeviceConfig.initFromFile(tempFile.toFile());
    }

    private void setupDisplayDeviceConfigFromConfigResourceFile() {
        TypedArray screenBrightnessNits = createFloatTypedArray(new float[]{2.0f, 250.0f, 650.0f});
        when(mResources.obtainTypedArray(
                com.android.internal.R.array.config_screenBrightnessNits))
                .thenReturn(screenBrightnessNits);
        when(mResources.getIntArray(com.android.internal.R.array
                .config_screenBrightnessBacklight)).thenReturn(new int[]{0, 120, 255});

        TypedArray screenBrightnessLevelNits = createFloatTypedArray(new
                float[]{2.0f, 200.0f, 600.0f});
        when(mResources.obtainTypedArray(
                com.android.internal.R.array.config_autoBrightnessDisplayValuesNits))
                .thenReturn(screenBrightnessLevelNits);
        int[] screenBrightnessLevelLux = new int[]{110, 500};
        when(mResources.getIntArray(
                com.android.internal.R.array.config_autoBrightnessLevels))
                .thenReturn(screenBrightnessLevelLux);
        int[] screenBrightnessLevels = new int[]{50, 100, 150};
        when(mResources.getIntArray(
                com.android.internal.R.array.config_autoBrightnessLcdBacklightValues))
                .thenReturn(screenBrightnessLevels);

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
        when(mResources.getInteger(
                R.integer.config_defaultRefreshRateInHbmHdr))
                .thenReturn(DEFAULT_REFRESH_RATE_IN_HBM_HDR);
        when(mResources.getInteger(
                R.integer.config_defaultRefreshRateInHbmSunlight))
                .thenReturn(DEFAULT_REFRESH_RATE_IN_HBM_SUNLIGHT);

        when(mResources.getString(com.android.internal.R.string.config_displayLightSensorType))
                .thenReturn("test_light_sensor");
        when(mResources.getBoolean(R.bool.config_automatic_brightness_available)).thenReturn(true);

        when(mResources.getInteger(
                R.integer.config_autoBrightnessBrighteningLightDebounce))
                .thenReturn(3000);
        when(mResources.getInteger(
                R.integer.config_autoBrightnessDarkeningLightDebounce))
                .thenReturn(4000);

        when(mResources.getInteger(
                R.integer.config_screenBrightnessCapForWearBedtimeMode))
                .thenReturn(35);

        mDisplayDeviceConfig = DisplayDeviceConfig.create(mContext, /* useConfigXml= */ true,
                mFlags);
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
