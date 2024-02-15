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

package com.android.server.display;

import static com.android.server.display.BrightnessMappingStrategy.INVALID_NITS;
import static com.android.server.display.utils.DeviceConfigParsingUtils.ambientBrightnessThresholdsIntToFloat;
import static com.android.server.display.utils.DeviceConfigParsingUtils.displayBrightnessThresholdsIntToFloat;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.hardware.display.DisplayManagerInternal;
import android.hardware.display.DisplayManagerInternal.RefreshRateLimitation;
import android.hardware.input.HostUsiVersion;
import android.os.Environment;
import android.os.PowerManager;
import android.text.TextUtils;
import android.util.MathUtils;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Spline;
import android.view.DisplayAddress;
import android.view.SurfaceControl;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.display.BrightnessSynchronizer;
import com.android.server.display.config.AutoBrightness;
import com.android.server.display.config.BlockingZoneConfig;
import com.android.server.display.config.BrightnessLimitMap;
import com.android.server.display.config.BrightnessThresholds;
import com.android.server.display.config.BrightnessThrottlingMap;
import com.android.server.display.config.BrightnessThrottlingPoint;
import com.android.server.display.config.Density;
import com.android.server.display.config.DisplayBrightnessMappingConfig;
import com.android.server.display.config.DisplayBrightnessPoint;
import com.android.server.display.config.DisplayConfiguration;
import com.android.server.display.config.DisplayQuirks;
import com.android.server.display.config.HbmTiming;
import com.android.server.display.config.HdrBrightnessData;
import com.android.server.display.config.HighBrightnessMode;
import com.android.server.display.config.IntegerArray;
import com.android.server.display.config.LuxThrottling;
import com.android.server.display.config.NitsMap;
import com.android.server.display.config.NonNegativeFloatToFloatPoint;
import com.android.server.display.config.Point;
import com.android.server.display.config.PowerThrottlingConfig;
import com.android.server.display.config.PowerThrottlingMap;
import com.android.server.display.config.PowerThrottlingPoint;
import com.android.server.display.config.PredefinedBrightnessLimitNames;
import com.android.server.display.config.RefreshRateConfigs;
import com.android.server.display.config.RefreshRateRange;
import com.android.server.display.config.RefreshRateThrottlingMap;
import com.android.server.display.config.RefreshRateThrottlingPoint;
import com.android.server.display.config.RefreshRateZone;
import com.android.server.display.config.SdrHdrRatioMap;
import com.android.server.display.config.SdrHdrRatioPoint;
import com.android.server.display.config.SensorData;
import com.android.server.display.config.ThermalStatus;
import com.android.server.display.config.ThermalThrottling;
import com.android.server.display.config.ThresholdPoint;
import com.android.server.display.config.UsiVersion;
import com.android.server.display.config.XmlParser;
import com.android.server.display.feature.DisplayManagerFlags;
import com.android.server.display.utils.DebugUtils;

import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.xml.datatype.DatatypeConfigurationException;

/**
 * Reads and stores display-specific configurations. File format:
 * <pre>
 *  {@code
 *    <displayConfiguration>
 *      <name>Built-In Display</name>
 *      <densityMapping>
 *        <density>
 *          <height>480</height>
 *          <width>720</width>
 *          <density>120</density>
 *        </density>
 *        <density>
 *          <height>720</height>
 *          <width>1280</width>
 *          <density>213</density>
 *        </density>
 *        <density>
 *          <height>1080</height>
 *          <width>1920</width>
 *          <density>320</density>
 *        </density>
 *        <density>
 *          <height>2160</height>
 *          <width>3840</width>
 *          <density>640</density>
 *        </density>
 *      </densityMapping>
 *
 *      <screenBrightnessMap>
 *        <point>
 *          <value>0.0</value>
 *          <nits>2.0</nits>
 *        </point>
 *        <point>
 *          <value>0.62</value>
 *          <nits>500.0</nits>
 *        </point>
 *        <point>
 *          <value>1.0</value>
 *          <nits>800.0</nits>
 *        </point>
 *      </screenBrightnessMap>
 *
 *      <screenBrightnessDefault>0.65</screenBrightnessDefault>
 *      <powerThrottlingConfig>
 *        <brightnessLowestCapAllowed>0.1</brightnessLowestCapAllowed>
 *        <pollingWindowMillis>15</pollingWindowMillis>
 *          <powerThrottlingMap>
 *              <powerThrottlingPoint>
 *                  <thermalStatus>severe</thermalStatus>
 *                  <powerQuotaMilliWatts>200.6</powerQuotaMilliWatts>
 *              </powerThrottlingPoint>
 *              <powerThrottlingPoint>
 *                  <thermalStatus>critical</thermalStatus>
 *                  <powerQuotaMilliWatts>300</powerQuotaMilliWatts>
 *              </powerThrottlingPoint>
 *          </powerThrottlingMap>
 *          <powerThrottlingMap id="id_2"> // optional attribute, leave blank for default
 *             <powerThrottlingPoint>
 *                 <thermalStatus>moderate</thermalStatus>
 *                 <powerQuotaMilliWatts>400</powerQuotaMilliWatts>
 *             </powerThrottlingPoint>
 *             <powerThrottlingPoint>
 *                 <thermalStatus>severe</thermalStatus>
 *                 <powerQuotaMilliWatts>250</powerQuotaMilliWatts>
 *            </powerThrottlingPoint>
 *          </powerThrottlingMap>
 *      </powerThrottlingConfig>
 *
 *      <thermalThrottling>
 *        <brightnessThrottlingMap>
 *          <brightnessThrottlingPoint>
 *            <thermalStatus>severe</thermalStatus>
 *            <brightness>0.1</brightness>
 *          </brightnessThrottlingPoint>
 *          <brightnessThrottlingPoint>
 *            <thermalStatus>critical</thermalStatus>
 *            <brightness>0.01</brightness>
 *          </brightnessThrottlingPoint>
 *        </brightnessThrottlingMap>
 *        <brightnessThrottlingMap id="id_2"> // optional attribute, leave blank for default
 *             <brightnessThrottlingPoint>
 *                 <thermalStatus>moderate</thermalStatus>
 *                 <brightness>0.2</brightness>
 *             </brightnessThrottlingPoint>
 *             <brightnessThrottlingPoint>
 *                 <thermalStatus>severe</thermalStatus>
 *                 <brightness>0.1</brightness>
 *            </brightnessThrottlingPoint>
 *        </brightnessThrottlingMap>
         <refreshRateThrottlingMap>
 *            <refreshRateThrottlingPoint>
 *                <thermalStatus>critical</thermalStatus>
 *                <refreshRateRange>
 *                     <minimum>0</minimum>
 *                     <maximum>60</maximum>
 *                 </refreshRateRange>
 *            </refreshRateThrottlingPoint>
 *        </refreshRateThrottlingMap>
 *      </thermalThrottling>
 *
 *      <refreshRate>
 *       <refreshRateZoneProfiles>
 *         <refreshRateZoneProfile id="concurrent">
 *           <refreshRateRange>
 *             <minimum>60</minimum>
 *             <maximum>60</maximum>
 *            </refreshRateRange>
 *          </refreshRateZoneProfile>
 *        </refreshRateZoneProfiles>
 *        <defaultRefreshRateInHbmHdr>75</defaultRefreshRateInHbmHdr>
 *        <defaultRefreshRateInHbmSunlight>75</defaultRefreshRateInHbmSunlight>
 *        <lowerBlockingZoneConfigs>
 *          <defaultRefreshRate>75</defaultRefreshRate>
 *          <refreshRateThermalThrottlingId>id_of_a_throttling_map</refreshRateThermalThrottlingId>
 *          <blockingZoneThreshold>
 *            <displayBrightnessPoint>
 *              <lux>50</lux>
 *              <nits>45.3</nits>
 *            </displayBrightnessPoint>
 *            <displayBrightnessPoint>
 *              <lux>60</lux>
 *              <nits>55.2</nits>
 *            </displayBrightnessPoint>
 *          </blockingZoneThreshold>
 *        </lowerBlockingZoneConfigs>
 *        <higherBlockingZoneConfigs>
 *          <defaultRefreshRate>90</defaultRefreshRate>
 *          <blockingZoneThreshold>
 *            <displayBrightnessPoint>
 *              <lux>500</lux>
 *              <nits>245.3</nits>
 *            </displayBrightnessPoint>
 *            <displayBrightnessPoint>
 *              <lux>600</lux>
 *              <nits>232.3</nits>
 *            </displayBrightnessPoint>
 *          </blockingZoneThreshold>
 *        </higherBlockingZoneConfigs>
 *      </refreshRate>
 *
 *      <highBrightnessMode enabled="true">
 *        <transitionPoint>0.62</transitionPoint>
 *        <minimumLux>10000</minimumLux>
 *        <timing>
 *          <timeWindowSecs>1800</timeWindowSecs> // Window in which we restrict HBM.
 *          <timeMaxSecs>300</timeMaxSecs>        // Maximum time of HBM allowed in that window.
 *          <timeMinSecs>60</timeMinSecs>         // Minimum time remaining required to switch
 *        </timing>                               //   HBM on for.
 *        <refreshRate>
 *          <minimum>120</minimum>
 *          <maximum>120</maximum>
 *        </refreshRate>
 *        <allowInLowPowerMode>false</allowInLowPowerMode>
 *        <minimumHdrPercentOfScreen>0.6</minimumHdrPercentOfScreen>
 *        <sdrHdrRatioMap>
 *          <point>
 *            <sdrNits>2.000</sdrNits>
 *            <hdrRatio>4.000</hdrRatio>
 *          </point>
 *        </sdrHdrRatioMap>
 *      </highBrightnessMode>
 *      <hdrBrightnessConfig>
 *         <brightnessMap>
 *             <point>
 *                <first>500</first>
 *                <second>0.3</second>
 *             </point>
 *             <point>
 *                 <first>1200</first>
 *                 <second>0.6</second>
 *             </point>
 *         </brightnessMap>
 *         <brightnessIncreaseDebounceMillis>1000</brightnessIncreaseDebounceMillis>
 *         <brightnessIncreaseDurationMillis>10000</brightnessIncreaseDurationMillis>
 *         <brightnessDecreaseDebounceMillis>13000</brightnessDecreaseDebounceMillis>
 *         <brightnessDecreaseDurationMillis>10000</brightnessDecreaseDurationMillis>
 *      </hdrBrightnessConfig>
 *      <luxThrottling>
 *        <brightnessLimitMap>
 *          <type>default</type>
 *          <map>
 *            <point>
 *                <first>5000</first>
 *                <second>0.3</second>
 *            </point>
 *            <point>
 *               <first>5000</first>
 *               <second>0.3</second>
 *            </point>
 *          </map>
 *        </brightnessPeakMap>
 *      </luxThrottling>
 *
 *      <quirks>
 *       <quirk>canSetBrightnessViaHwc</quirk>
 *      </quirks>
 *
 *      <autoBrightness enabled="true">
 *          <brighteningLightDebounceMillis>
 *              2000
 *          </brighteningLightDebounceMillis>
 *          <darkeningLightDebounceMillis>
 *              4000
 *          </darkeningLightDebounceMillis>
 *          <brighteningLightDebounceIdleMillis>
 *              2000
 *          </brighteningLightDebounceIdleMillis>
 *          <darkeningLightDebounceIdleMillis>
 *              1000
 *          </darkeningLightDebounceIdleMillis>
 *          <luxToBrightnessMapping>
 *            <mode>default</mode>
 *            <map>
 *              <point>
 *                <first>0</first>
 *                <second>0.2</second>
 *              </point>
 *              <point>
 *                <first>80</first>
 *                <second>0.3</second>
 *              </point>
 *            </map>
 *          </luxToBrightnessMapping>
 *          <luxToBrightnessMapping>
 *            <mode>doze</mode>
 *            <setting>dim</setting>
 *            <map>
 *              <point>
 *                <first>0</first>
 *                <second>0.2</second>
 *              </point>
 *              <point>
 *                <first>80</first>
 *                <second>0.3</second>
 *              </point>
 *            </map>
 *          </luxToBrightnessMapping>
 *      </autoBrightness>
 *
 *      <screenBrightnessRampFastDecrease>0.01</screenBrightnessRampFastDecrease>
 *      <screenBrightnessRampFastIncrease>0.02</screenBrightnessRampFastIncrease>
 *      <screenBrightnessRampSlowDecrease>0.03</screenBrightnessRampSlowDecrease>
 *      <screenBrightnessRampSlowIncrease>0.04</screenBrightnessRampSlowIncrease>
 *      <screenBrightnessRampSlowDecreaseIdle>0.05</screenBrightnessRampSlowDecreaseIdle>
 *      <screenBrightnessRampSlowIncreaseIdle>0.06</screenBrightnessRampSlowIncreaseIdle>
 *
 *      <screenBrightnessRampIncreaseMaxMillis>2000</screenBrightnessRampIncreaseMaxMillis>
 *      <screenBrightnessRampDecreaseMaxMillis>3000</screenBrightnessRampDecreaseMaxMillis>
 *      <screenBrightnessRampIncreaseMaxIdleMillis>2000</screenBrightnessRampIncreaseMaxIdleMillis>
 *      <screenBrightnessRampDecreaseMaxIdleMillis>2000</screenBrightnessRampDecreaseMaxIdleMillis>
 *
 *      <lightSensor>
 *        <type>android.sensor.light</type>
 *        <name>1234 Ambient Light Sensor</name>
 *        <refreshRate>
 *          <minimum>60</minimum>
 *          <maximum>120</maximum>
 *        </refreshRate>
 *      </lightSensor>
 *      <screenOffBrightnessSensor>
 *        <type>com.google.sensor.binned_brightness</type>
 *        <name>Binned Brightness 0 (wake-up)</name>
 *      </screenOffBrightnessSensor>
 *      <proxSensor>
 *        <type>android.sensor.proximity</type>
 *        <name>1234 Proximity Sensor</name>
 *        <refreshRate>
 *             <minimum>60</minimum>
 *             <maximum>60</maximum>
 *         </refreshRate>
 *         <supportedModes>
 *             <point>
 *                 <first>60</first>   // refreshRate
 *                 <second>60</second> //vsyncRate
 *             </point>
 *             <point>
 *                 <first>120</first>   // refreshRate
 *                 <second>120</second> //vsyncRate
 *             </point>
 *          </supportedModes>
 *      </proxSensor>
 *
 *      <ambientLightHorizonLong>10001</ambientLightHorizonLong>
 *      <ambientLightHorizonShort>2001</ambientLightHorizonShort>
 *
 *     <ambientBrightnessChangeThresholds>  // Thresholds for lux changes
 *         <brighteningThresholds>
 *             // Minimum change needed in ambient brightness to brighten screen.
 *             <minimum>10</minimum>
 *             // Percentage increase of lux needed to increase the screen brightness at a lux range
 *             // above the specified threshold.
 *             <brightnessThresholdPoints>
 *                 <brightnessThresholdPoint>
 *                     <threshold>0</threshold><percentage>13</percentage>
 *                 </brightnessThresholdPoint>
 *                 <brightnessThresholdPoint>
 *                     <threshold>100</threshold><percentage>14</percentage>
 *                 </brightnessThresholdPoint>
 *                 <brightnessThresholdPoint>
 *                     <threshold>200</threshold><percentage>15</percentage>
 *                 </brightnessThresholdPoint>
 *             </brightnessThresholdPoints>
 *         </brighteningThresholds>
 *         <darkeningThresholds>
 *             // Minimum change needed in ambient brightness to darken screen.
 *             <minimum>30</minimum>
 *             // Percentage increase of lux needed to decrease the screen brightness at a lux range
 *             // above the specified threshold.
 *             <brightnessThresholdPoints>
 *                 <brightnessThresholdPoint>
 *                     <threshold>0</threshold><percentage>15</percentage>
 *                 </brightnessThresholdPoint>
 *                 <brightnessThresholdPoint>
 *                     <threshold>300</threshold><percentage>16</percentage>
 *                 </brightnessThresholdPoint>
 *                 <brightnessThresholdPoint>
 *                     <threshold>400</threshold><percentage>17</percentage>
 *                 </brightnessThresholdPoint>
 *             </brightnessThresholdPoints>
 *         </darkeningThresholds>
 *     </ambientBrightnessChangeThresholds>
 *     <displayBrightnessChangeThresholds>   // Thresholds for screen brightness changes
 *         <brighteningThresholds>
 *             // Minimum change needed in screen brightness to brighten screen.
 *             <minimum>0.1</minimum>
 *             // Percentage increase of screen brightness needed to increase the screen brightness
 *             // at a lux range above the specified threshold.
 *             <brightnessThresholdPoints>
 *                 <brightnessThresholdPoint>
 *                     <threshold>0</threshold>
 *                     <percentage>9</percentage>
 *                 </brightnessThresholdPoint>
 *                 <brightnessThresholdPoint>
 *                     <threshold>0.10</threshold>
 *                     <percentage>10</percentage>
 *                 </brightnessThresholdPoint>
 *                 <brightnessThresholdPoint>
 *                     <threshold>0.20</threshold>
 *                     <percentage>11</percentage>
 *                 </brightnessThresholdPoint>
 *             </brightnessThresholdPoints>
 *         </brighteningThresholds>
 *         <darkeningThresholds>
 *             // Minimum change needed in screen brightness to darken screen.
 *             <minimum>0.3</minimum>
 *             // Percentage increase of screen brightness needed to decrease the screen brightness
 *             // at a lux range above the specified threshold.
 *             <brightnessThresholdPoints>
 *                 <brightnessThresholdPoint>
 *                     <threshold>0</threshold><percentage>11</percentage>
 *                 </brightnessThresholdPoint>
 *                 <brightnessThresholdPoint>
 *                     <threshold>0.11</threshold><percentage>12</percentage>
 *                 </brightnessThresholdPoint>
 *                 <brightnessThresholdPoint>
 *                     <threshold>0.21</threshold><percentage>13</percentage>
 *                 </brightnessThresholdPoint>
 *             </brightnessThresholdPoints>
 *         </darkeningThresholds>
 *     </displayBrightnessChangeThresholds>
 *     <ambientBrightnessChangeThresholdsIdle>   // Thresholds for lux changes in idle mode
 *         <brighteningThresholds>
 *             // Minimum change needed in ambient brightness to brighten screen in idle mode
 *             <minimum>20</minimum>
 *             // Percentage increase of lux needed to increase the screen brightness at a lux range
 *             // above the specified threshold whilst in idle mode.
 *             <brightnessThresholdPoints>
 *                 <brightnessThresholdPoint>
 *                     <threshold>0</threshold><percentage>21</percentage>
 *                 </brightnessThresholdPoint>
 *                 <brightnessThresholdPoint>
 *                     <threshold>500</threshold><percentage>22</percentage>
 *                 </brightnessThresholdPoint>
 *                 <brightnessThresholdPoint>
 *                     <threshold>600</threshold><percentage>23</percentage>
 *                 </brightnessThresholdPoint>
 *             </brightnessThresholdPoints>
 *         </brighteningThresholds>
 *         <darkeningThresholds>
 *             // Minimum change needed in ambient brightness to darken screen in idle mode
 *             <minimum>40</minimum>
 *             // Percentage increase of lux needed to decrease the screen brightness at a lux range
 *             // above the specified threshold whilst in idle mode.
 *             <brightnessThresholdPoints>
 *                 <brightnessThresholdPoint>
 *                     <threshold>0</threshold><percentage>23</percentage>
 *                 </brightnessThresholdPoint>
 *                 <brightnessThresholdPoint>
 *                     <threshold>700</threshold><percentage>24</percentage>
 *                 </brightnessThresholdPoint>
 *                 <brightnessThresholdPoint>
 *                     <threshold>800</threshold><percentage>25</percentage>
 *                 </brightnessThresholdPoint>
 *             </brightnessThresholdPoints>
 *         </darkeningThresholds>
 *     </ambientBrightnessChangeThresholdsIdle>
 *     <displayBrightnessChangeThresholdsIdle>    // Thresholds for idle screen brightness changes
 *         <brighteningThresholds>
 *             // Minimum change needed in screen brightness to brighten screen in idle mode
 *             <minimum>0.2</minimum>
 *             // Percentage increase of screen brightness needed to increase the screen brightness
 *             // at a lux range above the specified threshold whilst in idle mode
 *             <brightnessThresholdPoints>
 *                 <brightnessThresholdPoint>
 *                     <threshold>0</threshold><percentage>17</percentage>
 *                 </brightnessThresholdPoint>
 *                 <brightnessThresholdPoint>
 *                     <threshold>0.12</threshold><percentage>18</percentage>
 *                 </brightnessThresholdPoint>
 *                 <brightnessThresholdPoint>
 *                     <threshold>0.22</threshold><percentage>19</percentage>
 *                 </brightnessThresholdPoint>
 *             </brightnessThresholdPoints>
 *         </brighteningThresholds>
 *         <darkeningThresholds>
 *             // Minimum change needed in screen brightness to darken screen in idle mode
 *             <minimum>0.4</minimum>
 *             // Percentage increase of screen brightness needed to decrease the screen brightness
 *             // at a lux range above the specified threshold whilst in idle mode
 *             <brightnessThresholdPoints>
 *                 <brightnessThresholdPoint>
 *                     <threshold>0</threshold><percentage>19</percentage>
 *                 </brightnessThresholdPoint>
 *                 <brightnessThresholdPoint>
 *                     <threshold>0.13</threshold><percentage>20</percentage>
 *                 </brightnessThresholdPoint>
 *                 <brightnessThresholdPoint>
 *                     <threshold>0.23</threshold><percentage>21</percentage>
 *                 </brightnessThresholdPoint>
 *             </brightnessThresholdPoints>
 *         </darkeningThresholds>
 *     </displayBrightnessChangeThresholdsIdle>
 *     <screenOffBrightnessSensorValueToLux>
 *         <item>-1</item>
 *         <item>0</item>
 *         <item>5</item>
 *         <item>80</item>
 *         <item>1500</item>
 *     </screenOffBrightnessSensorValueToLux>
 *     // The version of the Universal Stylus Initiative (USI) protocol supported by this display.
 *     // This should be omitted if the display does not support USI styluses.
 *     <usiVersion>
 *         <majorVersion>2</majorVersion>
 *         <minorVersion>0</minorVersion>
 *     </usiVersion>
 *     <screenBrightnessCapForWearBedtimeMode>0.1</screenBrightnessCapForWearBedtimeMode>
 *    </displayConfiguration>
 *  }
 *  </pre>
 */
public class DisplayDeviceConfig {
    private static final String TAG = "DisplayDeviceConfig";

    // To enable these logs, run:
    // 'adb shell setprop persist.log.tag.DisplayDeviceConfig DEBUG && adb reboot'
    private static final boolean DEBUG = DebugUtils.isDebuggable(TAG);

    public static final float HIGH_BRIGHTNESS_MODE_UNSUPPORTED = Float.NaN;

    public static final String QUIRK_CAN_SET_BRIGHTNESS_VIA_HWC = "canSetBrightnessViaHwc";

    public static final String DEFAULT_ID = "default";

    public static final int DEFAULT_LOW_REFRESH_RATE = 60;

    @VisibleForTesting
    static final float BRIGHTNESS_DEFAULT = 0.5f;
    private static final String ETC_DIR = "etc";
    private static final String DISPLAY_CONFIG_DIR = "displayconfig";
    private static final String CONFIG_FILE_FORMAT = "display_%s.xml";
    private static final String DEFAULT_CONFIG_FILE = "default.xml";
    private static final String DEFAULT_CONFIG_FILE_WITH_UIMODE_FORMAT = "default_%s.xml";
    private static final String PORT_SUFFIX_FORMAT = "port_%d";
    private static final String STABLE_ID_SUFFIX_FORMAT = "id_%d";
    private static final String NO_SUFFIX_FORMAT = "%d";
    private static final long STABLE_FLAG = 1L << 62;
    private static final int DEFAULT_PEAK_REFRESH_RATE = 0;
    private static final int DEFAULT_REFRESH_RATE = 60;
    private static final int DEFAULT_REFRESH_RATE_IN_HBM = 0;
    private static final int DEFAULT_HIGH_REFRESH_RATE = 0;
    private static final float[] DEFAULT_BRIGHTNESS_THRESHOLDS = new float[]{};

    private static final float[] DEFAULT_AMBIENT_THRESHOLD_LEVELS = new float[]{0f};
    private static final float[] DEFAULT_AMBIENT_BRIGHTENING_THRESHOLDS = new float[]{100f};
    private static final float[] DEFAULT_AMBIENT_DARKENING_THRESHOLDS = new float[]{200f};
    private static final float[] DEFAULT_SCREEN_THRESHOLD_LEVELS = new float[]{0f};
    private static final float[] DEFAULT_SCREEN_BRIGHTENING_THRESHOLDS = new float[]{100f};
    private static final float[] DEFAULT_SCREEN_DARKENING_THRESHOLDS = new float[]{200f};

    private static final int INTERPOLATION_DEFAULT = 0;
    private static final int INTERPOLATION_LINEAR = 1;

    // Float.NaN (used as invalid for brightness) cannot be stored in config.xml
    // so -2 is used instead
    private static final float INVALID_BRIGHTNESS_IN_CONFIG = -2f;

    // Length of the ambient light horizon used to calculate the long term estimate of ambient
    // light.
    private static final int AMBIENT_LIGHT_LONG_HORIZON_MILLIS = 10000;

    // Length of the ambient light horizon used to calculate short-term estimate of ambient light.
    private static final int AMBIENT_LIGHT_SHORT_HORIZON_MILLIS = 2000;

    // Invalid value of AutoBrightness brightening and darkening light debounce
    private static final int INVALID_AUTO_BRIGHTNESS_LIGHT_DEBOUNCE = -1;

    @VisibleForTesting
    static final float HDR_PERCENT_OF_SCREEN_REQUIRED_DEFAULT = 0.5f;

    private final Context mContext;

    // The details of the ambient light sensor associated with this display.
    private SensorData mAmbientLightSensor;

    // The details of the doze brightness sensor associated with this display.
    private SensorData mScreenOffBrightnessSensor;

    // The details of the proximity sensor associated with this display.
    // Is null when no sensor should be used for that display
    @Nullable
    private SensorData mProximitySensor;

    private final List<RefreshRateLimitation> mRefreshRateLimitations =
            new ArrayList<>(2 /*initialCapacity*/);

    // Name of the display, if configured.
    @Nullable
    private String mName;

    // Nits and backlight values that are loaded from either the display device config file, or
    // config.xml. These are the raw values and just used for the dumpsys
    private float[] mRawNits;
    private float[] mRawBacklight;
    private int mInterpolationType;

    // These arrays are calculated from the raw arrays, but clamped to contain values equal to and
    // between mBacklightMinimum and mBacklightMaximum. These three arrays should all be the same
    // length
    // Nits array that is used to store the entire range of nits values that the device supports
    private float[] mNits;
    // Backlight array holds the values that the HAL uses to display the corresponding nits values
    private float[] mBacklight;
    // Purely an array that covers the ranges of values 0.0 - 1.0, indicating the system brightness
    // for the corresponding values above
    private float[] mBrightness;

    @Nullable
    private DisplayBrightnessMappingConfig mDisplayBrightnessMapping;

    private float mBacklightMinimum = Float.NaN;
    private float mBacklightMaximum = Float.NaN;
    private float mBrightnessDefault = Float.NaN;
    private float mBrightnessRampFastDecrease = Float.NaN;
    private float mBrightnessRampFastIncrease = Float.NaN;
    private float mBrightnessRampSlowDecrease = Float.NaN;
    private float mBrightnessRampSlowIncrease = Float.NaN;
    private float mBrightnessRampSlowDecreaseIdle = Float.NaN;
    private float mBrightnessRampSlowIncreaseIdle = Float.NaN;
    private long mBrightnessRampDecreaseMaxMillis = 0;
    private long mBrightnessRampIncreaseMaxMillis = 0;
    private long mBrightnessRampDecreaseMaxIdleMillis = 0;
    private long mBrightnessRampIncreaseMaxIdleMillis = 0;
    private int mAmbientHorizonLong = AMBIENT_LIGHT_LONG_HORIZON_MILLIS;
    private int mAmbientHorizonShort = AMBIENT_LIGHT_SHORT_HORIZON_MILLIS;
    private float mScreenBrighteningMinThreshold = 0.0f;     // Retain behaviour as though there is
    private float mScreenBrighteningMinThresholdIdle = 0.0f; // no minimum threshold for change in
    private float mScreenDarkeningMinThreshold = 0.0f;       // screen brightness or ambient
    private float mScreenDarkeningMinThresholdIdle = 0.0f;   // brightness.
    private float mAmbientLuxBrighteningMinThreshold = 0.0f;
    private float mAmbientLuxBrighteningMinThresholdIdle = 0.0f;
    private float mAmbientLuxDarkeningMinThreshold = 0.0f;
    private float mAmbientLuxDarkeningMinThresholdIdle = 0.0f;

    // Screen brightness thresholds levels & percentages
    private float[] mScreenBrighteningLevels = DEFAULT_SCREEN_THRESHOLD_LEVELS;
    private float[] mScreenBrighteningPercentages = DEFAULT_SCREEN_BRIGHTENING_THRESHOLDS;
    private float[] mScreenDarkeningLevels = DEFAULT_SCREEN_THRESHOLD_LEVELS;
    private float[] mScreenDarkeningPercentages = DEFAULT_SCREEN_DARKENING_THRESHOLDS;

    // Screen brightness thresholds levels & percentages for idle mode
    private float[] mScreenBrighteningLevelsIdle = DEFAULT_SCREEN_THRESHOLD_LEVELS;
    private float[] mScreenBrighteningPercentagesIdle = DEFAULT_SCREEN_BRIGHTENING_THRESHOLDS;
    private float[] mScreenDarkeningLevelsIdle = DEFAULT_SCREEN_THRESHOLD_LEVELS;
    private float[] mScreenDarkeningPercentagesIdle = DEFAULT_SCREEN_DARKENING_THRESHOLDS;

    // Ambient brightness thresholds levels & percentages
    private float[] mAmbientBrighteningLevels = DEFAULT_AMBIENT_THRESHOLD_LEVELS;
    private float[] mAmbientBrighteningPercentages = DEFAULT_AMBIENT_BRIGHTENING_THRESHOLDS;
    private float[] mAmbientDarkeningLevels = DEFAULT_AMBIENT_THRESHOLD_LEVELS;
    private float[] mAmbientDarkeningPercentages = DEFAULT_AMBIENT_DARKENING_THRESHOLDS;

    // Ambient brightness thresholds levels & percentages for idle mode
    private float[] mAmbientBrighteningLevelsIdle = DEFAULT_AMBIENT_THRESHOLD_LEVELS;
    private float[] mAmbientBrighteningPercentagesIdle = DEFAULT_AMBIENT_BRIGHTENING_THRESHOLDS;
    private float[] mAmbientDarkeningLevelsIdle = DEFAULT_AMBIENT_THRESHOLD_LEVELS;
    private float[] mAmbientDarkeningPercentagesIdle = DEFAULT_AMBIENT_DARKENING_THRESHOLDS;

    // A mapping between screen off sensor values and lux values
    private int[] mScreenOffBrightnessSensorValueToLux;

    private Spline mBrightnessToBacklightSpline;
    private Spline mBacklightToBrightnessSpline;
    private Spline mBacklightToNitsSpline;
    private Spline mNitsToBacklightSpline;
    private List<String> mQuirks;
    private boolean mIsHighBrightnessModeEnabled = false;
    private HighBrightnessModeData mHbmData;
    @Nullable
    private PowerThrottlingConfigData mPowerThrottlingConfigData;
    private DensityMapping mDensityMapping;
    private String mLoadedFrom = null;
    private Spline mSdrToHdrRatioSpline;

    // Represents the auto-brightness brightening light debounce.
    private long mAutoBrightnessBrighteningLightDebounce =
            INVALID_AUTO_BRIGHTNESS_LIGHT_DEBOUNCE;

    // Represents the auto-brightness darkening light debounce.
    private long mAutoBrightnessDarkeningLightDebounce =
            INVALID_AUTO_BRIGHTNESS_LIGHT_DEBOUNCE;

    // Represents the auto-brightness brightening light debounce for idle screen brightness mode.
    private long mAutoBrightnessBrighteningLightDebounceIdle =
            INVALID_AUTO_BRIGHTNESS_LIGHT_DEBOUNCE;

    // Represents the auto-brightness darkening light debounce for idle screen brightness mode.
    private long mAutoBrightnessDarkeningLightDebounceIdle =
            INVALID_AUTO_BRIGHTNESS_LIGHT_DEBOUNCE;

    // This setting allows non-default displays to have autobrightness enabled.
    private boolean mAutoBrightnessAvailable = false;
    // This stores the raw value loaded from the config file - true if not written.
    private boolean mDdcAutoBrightnessAvailable = true;

    /**
     * The default peak refresh rate for a given device. This value prevents the framework from
     * using higher refresh rates, even if display modes with higher refresh rates are available
     * from hardware composer. Only has an effect if the value is non-zero.
     */
    private int mDefaultPeakRefreshRate = DEFAULT_PEAK_REFRESH_RATE;

    /**
     * The default refresh rate for a given device. This value sets the higher default
     * refresh rate. If the hardware composer on the device supports display modes with
     * a higher refresh rate than the default value specified here, the framework may use those
     * higher refresh rate modes if an app chooses one by setting preferredDisplayModeId or calling
     * setFrameRate(). We have historically allowed fallback to mDefaultPeakRefreshRate if
     * mDefaultRefreshRate is set to 0, but this is not supported anymore.
     */
    private int mDefaultRefreshRate = DEFAULT_REFRESH_RATE;

    /**
     * Default refresh rate while the device has high brightness mode enabled for HDR.
     */
    private int mDefaultRefreshRateInHbmHdr = DEFAULT_REFRESH_RATE_IN_HBM;

    /**
     * Default refresh rate while the device has high brightness mode enabled for Sunlight.
     */
    private int mDefaultRefreshRateInHbmSunlight = DEFAULT_REFRESH_RATE_IN_HBM;
    /**
     * Default refresh rate in the high zone defined by brightness and ambient thresholds.
     * If non-positive, then the refresh rate is unchanged even if thresholds are configured.
     */
    private int mDefaultHighBlockingZoneRefreshRate = DEFAULT_HIGH_REFRESH_RATE;

    /**
     * Default refresh rate in the zone defined by brightness and ambient thresholds.
     * If non-positive, then the refresh rate is unchanged even if thresholds are configured.
     */
    private int mDefaultLowBlockingZoneRefreshRate = DEFAULT_LOW_REFRESH_RATE;

    // Refresh rate profiles, currently only for concurrent mode profile and controlled by Layout
    private final Map<String, SurfaceControl.RefreshRateRange> mRefreshRateZoneProfiles =
            new HashMap<>();

    /**
     * The display uses different gamma curves for different refresh rates. It's hard for panel
     * vendors to tune the curves to have exact same brightness for different refresh rate. So
     * brightness flickers could be observed at switch time. The issue is worse at the gamma lower
     * end. In addition, human eyes are more sensitive to the flicker at darker environment. To
     * prevent flicker, we only support higher refresh rates if the display brightness is above a
     * threshold. For example, no higher refresh rate if display brightness <= disp0 && ambient
     * brightness <= amb0 || display brightness <= disp1 && ambient brightness <= amb1
     *
     * Brightness thresholds are paired with lux thresholds - they both have to be met.
     *
     * A negative brightness or lux value means that only one threshold should be used - e.g. if
     * the brightness value is negative, only the lux threshold is applied.
     */
    private float[] mLowDisplayBrightnessThresholds = DEFAULT_BRIGHTNESS_THRESHOLDS;
    private float[] mLowAmbientBrightnessThresholds = DEFAULT_BRIGHTNESS_THRESHOLDS;

    /**
     * The display uses different gamma curves for different refresh rates. It's hard for panel
     * vendors to tune the curves to have exact same brightness for different refresh rate. So
     * brightness flickers could be observed at switch time. The issue can be observed on the screen
     * with even full white content at the high brightness. To prevent flickering, we support fixed
     * refresh rates if the display and ambient brightness are equal to or above the provided
     * thresholds. You can define multiple threshold levels as higher brightness environments may
     * have lower display brightness requirements for the flickering is visible. For example, fixed
     * refresh rate if display brightness >= disp0 && ambient brightness >= amb0 || display
     * brightness >= disp1 && ambient brightness >= amb1
     *
     * Brightness thresholds are paired with lux thresholds - they both have to be met.
     *
     * A negative brightness or lux value means that only one threshold should be used - e.g. if
     * the brightness value is negative, only the lux threshold is applied.
     */
    private float[] mHighDisplayBrightnessThresholds = DEFAULT_BRIGHTNESS_THRESHOLDS;
    private float[] mHighAmbientBrightnessThresholds = DEFAULT_BRIGHTNESS_THRESHOLDS;

    /**
     * Thermal throttling maps for the low and high blocking zones.
     */
    private String mLowBlockingZoneThermalMapId = null;
    private String mHighBlockingZoneThermalMapId = null;

    private final HashMap<String, ThermalBrightnessThrottlingData>
            mThermalBrightnessThrottlingDataMapByThrottlingId = new HashMap<>();

    private final HashMap<String, PowerThrottlingData>
            mPowerThrottlingDataMapByThrottlingId = new HashMap<>();

    private final Map<String, SparseArray<SurfaceControl.RefreshRateRange>>
            mRefreshRateThrottlingMap = new HashMap<>();

    private final Map<BrightnessLimitMapType, Map<Float, Float>>
            mLuxThrottlingData = new HashMap<>();

    @Nullable
    private HostUsiVersion mHostUsiVersion;

    @Nullable
    private HdrBrightnessData mHdrBrightnessData;

    /**
     * Maximum screen brightness setting when screen brightness capped in Wear Bedtime mode.
     */
    private float mBrightnessCapForWearBedtimeMode;

    private final DisplayManagerFlags mFlags;

    @VisibleForTesting
    DisplayDeviceConfig(Context context, DisplayManagerFlags flags) {
        mContext = context;
        mFlags = flags;
    }

    /**
     * Creates an instance for the specified display. Tries to find a file with identifier in the
     * following priority order:
     * <ol>
     *     <li>physicalDisplayId</li>
     *     <li>physicalDisplayId without a stable flag (old system)</li>
     *     <li>portId</li>
     * </ol>
     *
     * @param physicalDisplayId The display ID for which to load the configuration.
     * @return A configuration instance for the specified display.
     */
    public static DisplayDeviceConfig create(Context context, long physicalDisplayId,
            boolean isFirstDisplay, DisplayManagerFlags flags) {
        final DisplayDeviceConfig config = createWithoutDefaultValues(context, physicalDisplayId,
                isFirstDisplay, flags);

        config.copyUninitializedValuesFromSecondaryConfig(loadDefaultConfigurationXml(context));
        return config;
    }

    /**
     * Creates an instance using global values since no display device config xml exists. Uses
     * values from config or PowerManager.
     *
     * @param context      The context from which the DisplayDeviceConfig is to be constructed.
     * @param useConfigXml A flag indicating if values are to be loaded from the configuration file,
     *                     or the default values.
     * @return A configuration instance.
     */
    public static DisplayDeviceConfig create(Context context, boolean useConfigXml,
            DisplayManagerFlags flags) {
        final DisplayDeviceConfig config;
        if (useConfigXml) {
            config = getConfigFromGlobalXml(context, flags);
        } else {
            config = getConfigFromPmValues(context, flags);
        }
        return config;
    }

    private static DisplayDeviceConfig createWithoutDefaultValues(Context context,
            long physicalDisplayId, boolean isFirstDisplay, DisplayManagerFlags flags) {
        DisplayDeviceConfig config;

        config = loadConfigFromDirectory(context, Environment.getProductDirectory(),
                physicalDisplayId, flags);
        if (config != null) {
            return config;
        }

        config = loadConfigFromDirectory(context, Environment.getVendorDirectory(),
                physicalDisplayId, flags);
        if (config != null) {
            return config;
        }

        // If no config can be loaded from any ddc xml at all,
        // prepare a whole config using the global config.xml.
        // Guaranteed not null
        return create(context, isFirstDisplay, flags);
    }

    private static DisplayConfiguration loadDefaultConfigurationXml(Context context) {
        List<File> defaultXmlLocations = new ArrayList<>();
        defaultXmlLocations.add(Environment.buildPath(Environment.getProductDirectory(),
                ETC_DIR, DISPLAY_CONFIG_DIR, DEFAULT_CONFIG_FILE));
        defaultXmlLocations.add(Environment.buildPath(Environment.getVendorDirectory(),
                ETC_DIR, DISPLAY_CONFIG_DIR, DEFAULT_CONFIG_FILE));

        // Read config_defaultUiModeType directly because UiModeManager hasn't started yet.
        final int uiModeType = context.getResources()
                .getInteger(com.android.internal.R.integer.config_defaultUiModeType);
        final String uiModeTypeStr = Configuration.getUiModeTypeString(uiModeType);
        if (uiModeTypeStr != null) {
            defaultXmlLocations.add(Environment.buildPath(Environment.getRootDirectory(),
                    ETC_DIR, DISPLAY_CONFIG_DIR,
                    String.format(DEFAULT_CONFIG_FILE_WITH_UIMODE_FORMAT, uiModeTypeStr)));
        }
        defaultXmlLocations.add(Environment.buildPath(Environment.getRootDirectory(),
                ETC_DIR, DISPLAY_CONFIG_DIR, DEFAULT_CONFIG_FILE));

        final File configFile = getFirstExistingFile(defaultXmlLocations);
        if (configFile == null) {
            // Display configuration files aren't required to exist.
            return null;
        }

        DisplayConfiguration defaultConfig = null;

        try (InputStream in = new BufferedInputStream(new FileInputStream(configFile))) {
            defaultConfig = XmlParser.read(in);
            if (defaultConfig == null) {
                Slog.i(TAG, "Default DisplayDeviceConfig file is null");
            }
        } catch (IOException | DatatypeConfigurationException | XmlPullParserException e) {
            Slog.e(TAG, "Encountered an error while reading/parsing display config file: "
                    + configFile, e);
        }

        return defaultConfig;
    }

    private static File getFirstExistingFile(Collection<File> files) {
        for (File file : files) {
            if (file.exists() && file.isFile()) {
                return file;
            }
        }
        return null;
    }

    private static DisplayDeviceConfig loadConfigFromDirectory(Context context,
            File baseDirectory, long physicalDisplayId, DisplayManagerFlags flags) {
        DisplayDeviceConfig config;
        // Create config using filename from physical ID (including "stable" bit).
        config = getConfigFromSuffix(context, baseDirectory, STABLE_ID_SUFFIX_FORMAT,
                physicalDisplayId, flags);
        if (config != null) {
            return config;
        }

        // Create config using filename from physical ID (excluding "stable" bit).
        final long withoutStableFlag = physicalDisplayId & ~STABLE_FLAG;
        config = getConfigFromSuffix(context, baseDirectory, NO_SUFFIX_FORMAT, withoutStableFlag,
                flags);
        if (config != null) {
            return config;
        }

        // Create config using filename from port ID.
        final DisplayAddress.Physical physicalAddress =
                DisplayAddress.fromPhysicalDisplayId(physicalDisplayId);
        int port = physicalAddress.getPort();
        config = getConfigFromSuffix(context, baseDirectory, PORT_SUFFIX_FORMAT, port, flags);
        return config;
    }

    /** The name of the display.
     *
     * @return The name of the display.
     */
    @Nullable
    public String getName() {
        return mName;
    }

    /**
     * Return the brightness mapping nits array.
     *
     * @return The brightness mapping nits array.
     */
    public float[] getNits() {
        return mNits;
    }

    /**
     * Return the brightness mapping backlight array.
     *
     * @return The backlight mapping value array.
     */
    public float[] getBacklight() {
        return mBacklight;
    }

    /**
     * Calculates the backlight value, as recognised by the HAL, from the brightness value given
     * that the rest of the system deals with.
     *
     * @param brightness value on the framework scale of 0-1
     * @return backlight value on the HAL scale of 0-1
     */
    public float getBacklightFromBrightness(float brightness) {
        return mBrightnessToBacklightSpline.interpolate(brightness);
    }

    /**
     * Calculates the nits value for the specified backlight value if a mapping exists.
     *
     * @return The mapped nits or {@link BrightnessMappingStrategy.INVALID_NITS} if no mapping
     * exits.
     */
    public float getNitsFromBacklight(float backlight) {
        if (mBacklightToNitsSpline == null) {
            return INVALID_NITS;
        }
        backlight = Math.max(backlight, mBacklightMinimum);
        return mBacklightToNitsSpline.interpolate(backlight);
    }

    /**
     * @return true if there is sdrHdrRatioMap, false otherwise.
     */
    public boolean hasSdrToHdrRatioSpline() {
        return mSdrToHdrRatioSpline != null;
    }

    /**
     * Calculate the HDR brightness for the specified SDR brightenss, restricted by the
     * maxDesiredHdrSdrRatio (the ratio between the HDR luminance and SDR luminance)
     *
     * @return the HDR brightness or BRIGHTNESS_INVALID when no mapping exists.
     */
    public float getHdrBrightnessFromSdr(float brightness, float maxDesiredHdrSdrRatio) {
        if (mSdrToHdrRatioSpline == null) {
            return PowerManager.BRIGHTNESS_INVALID;
        }

        float backlight = getBacklightFromBrightness(brightness);
        float nits = getNitsFromBacklight(backlight);
        if (nits == INVALID_NITS) {
            return PowerManager.BRIGHTNESS_INVALID;
        }

        float ratio = Math.min(mSdrToHdrRatioSpline.interpolate(nits), maxDesiredHdrSdrRatio);
        float hdrNits = nits * ratio;
        if (mNitsToBacklightSpline == null) {
            return PowerManager.BRIGHTNESS_INVALID;
        }

        float hdrBacklight = mNitsToBacklightSpline.interpolate(hdrNits);
        hdrBacklight = Math.max(mBacklightMinimum, Math.min(mBacklightMaximum, hdrBacklight));
        float hdrBrightness = mBacklightToBrightnessSpline.interpolate(hdrBacklight);

        if (DEBUG) {
            Slog.d(TAG, "getHdrBrightnessFromSdr: sdr brightness " + brightness
                    + " backlight " + backlight
                    + " nits " + nits
                    + " ratio " + ratio
                    + " hdrNits " + hdrNits
                    + " hdrBacklight " + hdrBacklight
                    + " hdrBrightness " + hdrBrightness
            );
        }
        return hdrBrightness;
    }

    /**
     * Return an array of equal length to backlight and nits, that covers the entire system
     * brightness range of 0.0-1.0.
     *
     * @return brightness array
     */
    public float[] getBrightness() {
        return mBrightness;
    }

    /**
     * Return the default brightness on a scale of 0.0f - 1.0f
     *
     * @return default brightness
     */
    public float getBrightnessDefault() {
        return mBrightnessDefault;
    }

    public float getBrightnessRampFastDecrease() {
        return mBrightnessRampFastDecrease;
    }

    public float getBrightnessRampFastIncrease() {
        return mBrightnessRampFastIncrease;
    }

    public float getBrightnessRampSlowDecrease() {
        return mBrightnessRampSlowDecrease;
    }

    public float getBrightnessRampSlowIncrease() {
        return mBrightnessRampSlowIncrease;
    }

    public float getBrightnessRampSlowDecreaseIdle() {
        return mBrightnessRampSlowDecreaseIdle;
    }

    public float getBrightnessRampSlowIncreaseIdle() {
        return mBrightnessRampSlowIncreaseIdle;
    }

    public long getBrightnessRampDecreaseMaxMillis() {
        return mBrightnessRampDecreaseMaxMillis;
    }

    public long getBrightnessRampIncreaseMaxMillis() {
        return mBrightnessRampIncreaseMaxMillis;
    }

    public long getBrightnessRampDecreaseMaxIdleMillis() {
        return mBrightnessRampDecreaseMaxIdleMillis;
    }

    public long getBrightnessRampIncreaseMaxIdleMillis() {
        return mBrightnessRampIncreaseMaxIdleMillis;
    }

    public int getAmbientHorizonLong() {
        return mAmbientHorizonLong;
    }

    public int getAmbientHorizonShort() {
        return mAmbientHorizonShort;
    }

    /**
     * The minimum value for the screen brightness increase to actually occur.
     * @return float value in brightness scale of 0 - 1.
     */
    public float getScreenBrighteningMinThreshold() {
        return mScreenBrighteningMinThreshold;
    }

    /**
     * The minimum value for the screen brightness decrease to actually occur.
     * @return float value in brightness scale of 0 - 1.
     */
    public float getScreenDarkeningMinThreshold() {
        return mScreenDarkeningMinThreshold;
    }

    /**
     * The minimum value for the screen brightness increase to actually occur while in idle screen
     * brightness mode.
     * @return float value in brightness scale of 0 - 1.
     */
    public float getScreenBrighteningMinThresholdIdle() {
        return mScreenBrighteningMinThresholdIdle;
    }

    /**
     * The minimum value for the screen brightness decrease to actually occur while in idle screen
     * brightness mode.
     * @return float value in brightness scale of 0 - 1.
     */
    public float getScreenDarkeningMinThresholdIdle() {
        return mScreenDarkeningMinThresholdIdle;
    }

    /**
     * The minimum value for the ambient lux increase for a screen brightness change to actually
     * occur.
     * @return float value in lux.
     */
    public float getAmbientLuxBrighteningMinThreshold() {
        return mAmbientLuxBrighteningMinThreshold;
    }

    /**
     * The minimum value for the ambient lux decrease for a screen brightness change to actually
     * occur.
     * @return float value in lux.
     */
    public float getAmbientLuxDarkeningMinThreshold() {
        return mAmbientLuxDarkeningMinThreshold;
    }

    /**
     * The minimum value for the ambient lux increase for a screen brightness change to actually
     * occur while in idle screen brightness mode.
     * @return float value in lux.
     */
    public float getAmbientLuxBrighteningMinThresholdIdle() {
        return mAmbientLuxBrighteningMinThresholdIdle;
    }

    /**
     * The minimum value for the ambient lux decrease for a screen brightness change to actually
     * occur while in idle screen brightness mode.
     * @return float value in lux.
     */
    public float getAmbientLuxDarkeningMinThresholdIdle() {
        return mAmbientLuxDarkeningMinThresholdIdle;
    }

    /**
     * The array that describes the range of screen brightness that each threshold percentage
     * applies within.
     *
     * The (zero-based) index is calculated as follows
     * value = current screen brightness value
     * level = mScreenBrighteningLevels
     *
     * condition                       return
     * value < level[0]                = 0.0f
     * level[n] <= value < level[n+1]  = mScreenBrighteningPercentages[n]
     * level[MAX] <= value             = mScreenBrighteningPercentages[MAX]
     *
     * @return the screen brightness levels between 0.0 and 1.0 for which each
     * mScreenBrighteningPercentages applies
     */
    public float[] getScreenBrighteningLevels() {
        return mScreenBrighteningLevels;
    }

    /**
     * The array that describes the screen brightening threshold percentage change at each screen
     * brightness level described in mScreenBrighteningLevels.
     *
     * @return the percentages between 0 and 100 of brightness increase required in order for the
     * screen brightness to change
     */
    public float[] getScreenBrighteningPercentages() {
        return mScreenBrighteningPercentages;
    }

    /**
     * The array that describes the range of screen brightness that each threshold percentage
     * applies within.
     *
     * The (zero-based) index is calculated as follows
     * value = current screen brightness value
     * level = mScreenDarkeningLevels
     *
     * condition                       return
     * value < level[0]                = 0.0f
     * level[n] <= value < level[n+1]  = mScreenDarkeningPercentages[n]
     * level[MAX] <= value             = mScreenDarkeningPercentages[MAX]
     *
     * @return the screen brightness levels between 0.0 and 1.0 for which each
     * mScreenDarkeningPercentages applies
     */
    public float[] getScreenDarkeningLevels() {
        return mScreenDarkeningLevels;
    }

    /**
     * The array that describes the screen darkening threshold percentage change at each screen
     * brightness level described in mScreenDarkeningLevels.
     *
     * @return the percentages between 0 and 100 of brightness decrease required in order for the
     * screen brightness to change
     */
    public float[] getScreenDarkeningPercentages() {
        return mScreenDarkeningPercentages;
    }

    /**
     * The array that describes the range of ambient brightness that each threshold
     * percentage applies within.
     *
     * The (zero-based) index is calculated as follows
     * value = current ambient brightness value
     * level = mAmbientBrighteningLevels
     *
     * condition                       return
     * value < level[0]                = 0.0f
     * level[n] <= value < level[n+1]  = mAmbientBrighteningPercentages[n]
     * level[MAX] <= value             = mAmbientBrighteningPercentages[MAX]
     *
     * @return the ambient brightness levels from 0 lux upwards for which each
     * mAmbientBrighteningPercentages applies
     */
    public float[] getAmbientBrighteningLevels() {
        return mAmbientBrighteningLevels;
    }

    /**
     * The array that describes the ambient brightening threshold percentage change at each ambient
     * brightness level described in mAmbientBrighteningLevels.
     *
     * @return the percentages between 0 and 100 of brightness increase required in order for the
     * screen brightness to change
     */
    public float[] getAmbientBrighteningPercentages() {
        return mAmbientBrighteningPercentages;
    }

    /**
     * The array that describes the range of ambient brightness that each threshold percentage
     * applies within.
     *
     * The (zero-based) index is calculated as follows
     * value = current ambient brightness value
     * level = mAmbientDarkeningLevels
     *
     * condition                       return
     * value < level[0]                = 0.0f
     * level[n] <= value < level[n+1]  = mAmbientDarkeningPercentages[n]
     * level[MAX] <= value             = mAmbientDarkeningPercentages[MAX]
     *
     * @return the ambient brightness levels from 0 lux upwards for which each
     * mAmbientDarkeningPercentages applies
     */
    public float[] getAmbientDarkeningLevels() {
        return mAmbientDarkeningLevels;
    }

    /**
     * The array that describes the ambient darkening threshold percentage change at each ambient
     * brightness level described in mAmbientDarkeningLevels.
     *
     * @return the percentages between 0 and 100 of brightness decrease required in order for the
     * screen brightness to change
     */
    public float[] getAmbientDarkeningPercentages() {
        return mAmbientDarkeningPercentages;
    }

    /**
     * The array that describes the range of screen brightness that each threshold percentage
     * applies within whilst in idle screen brightness mode.
     *
     * The (zero-based) index is calculated as follows
     * value = current screen brightness value
     * level = mScreenBrighteningLevelsIdle
     *
     * condition                       return
     * value < level[0]                = 0.0f
     * level[n] <= value < level[n+1]  = mScreenBrighteningPercentagesIdle[n]
     * level[MAX] <= value             = mScreenBrighteningPercentagesIdle[MAX]
     *
     * @return the screen brightness levels between 0.0 and 1.0 for which each
     * mScreenBrighteningPercentagesIdle applies
     */
    public float[] getScreenBrighteningLevelsIdle() {
        return mScreenBrighteningLevelsIdle;
    }

    /**
     * The array that describes the screen brightening threshold percentage change at each screen
     * brightness level described in mScreenBrighteningLevelsIdle.
     *
     * @return the percentages between 0 and 100 of brightness increase required in order for the
     * screen brightness to change while in idle mode.
     */
    public float[] getScreenBrighteningPercentagesIdle() {
        return mScreenBrighteningPercentagesIdle;
    }

    /**
     * The array that describes the range of screen brightness that each threshold percentage
     * applies within whilst in idle screen brightness mode.
     *
     * The (zero-based) index is calculated as follows
     * value = current screen brightness value
     * level = mScreenDarkeningLevelsIdle
     *
     * condition                       return
     * value < level[0]                = 0.0f
     * level[n] <= value < level[n+1]  = mScreenDarkeningPercentagesIdle[n]
     * level[MAX] <= value             = mScreenDarkeningPercentagesIdle[MAX]
     *
     * @return the screen brightness levels between 0.0 and 1.0 for which each
     * mScreenDarkeningPercentagesIdle applies
     */
    public float[] getScreenDarkeningLevelsIdle() {
        return mScreenDarkeningLevelsIdle;
    }

    /**
     * The array that describes the screen darkening threshold percentage change at each screen
     * brightness level described in mScreenDarkeningLevelsIdle.
     *
     * @return the percentages between 0 and 100 of brightness decrease required in order for the
     * screen brightness to change while in idle mode.
     */
    public float[] getScreenDarkeningPercentagesIdle() {
        return mScreenDarkeningPercentagesIdle;
    }

    /**
     * The array that describes the range of ambient brightness that each threshold percentage
     * applies within whilst in idle screen brightness mode.
     *
     * The (zero-based) index is calculated as follows
     * value = current ambient brightness value
     * level = mAmbientBrighteningLevelsIdle
     *
     * condition                       return
     * value < level[0]                = 0.0f
     * level[n] <= value < level[n+1]  = mAmbientBrighteningPercentagesIdle[n]
     * level[MAX] <= value             = mAmbientBrighteningPercentagesIdle[MAX]
     *
     * @return the ambient brightness levels from 0 lux upwards for which each
     * mAmbientBrighteningPercentagesIdle applies
     */
    public float[] getAmbientBrighteningLevelsIdle() {
        return mAmbientBrighteningLevelsIdle;
    }

    /**
     * The array that describes the ambient brightness threshold percentage change whilst in
     * idle screen brightness mode at each ambient brightness level described in
     * mAmbientBrighteningLevelsIdle.
     *
     * @return the percentages between 0 and 100 of ambient brightness increase required in order
     * for the screen brightness to change
     */
    public float[] getAmbientBrighteningPercentagesIdle() {
        return mAmbientBrighteningPercentagesIdle;
    }

    /**
     * The array that describes the range of ambient brightness that each threshold percentage
     * applies within whilst in idle screen brightness mode.
     *
     * The (zero-based) index is calculated as follows
     * value = current ambient brightness value
     * level = mAmbientDarkeningLevelsIdle
     *
     * condition                       return
     * value < level[0]                = 0.0f
     * level[n] <= value < level[n+1]  = mAmbientDarkeningPercentagesIdle[n]
     * level[MAX] <= value             = mAmbientDarkeningPercentagesIdle[MAX]
     *
     * @return the ambient brightness levels from 0 lux upwards for which each
     * mAmbientDarkeningPercentagesIdle applies
     */
    public float[] getAmbientDarkeningLevelsIdle() {
        return mAmbientDarkeningLevelsIdle;
    }

    /**
     * The array that describes the ambient brightness threshold percentage change whilst in
     * idle screen brightness mode at each ambient brightness level described in
     * mAmbientDarkeningLevelsIdle.
     *
     * @return the percentages between 0 and 100 of ambient brightness decrease required in order
     * for the screen brightness to change
     */
    public float[] getAmbientDarkeningPercentagesIdle() {
        return mAmbientDarkeningPercentagesIdle;
    }

    public SensorData getAmbientLightSensor() {
        return mAmbientLightSensor;
    }

    SensorData getScreenOffBrightnessSensor() {
        return mScreenOffBrightnessSensor;
    }

    @Nullable
    public SensorData getProximitySensor() {
        return mProximitySensor;
    }

    boolean isAutoBrightnessAvailable() {
        return mAutoBrightnessAvailable;
    }

    /**
     * @param quirkValue The quirk to test.
     * @return {@code true} if the specified quirk is present in this configuration, {@code false}
     * otherwise.
     */
    public boolean hasQuirk(String quirkValue) {
        return mQuirks != null && mQuirks.contains(quirkValue);
    }

    /**
     * @return high brightness mode configuration data for the display.
     */
    public HighBrightnessModeData getHighBrightnessModeData() {
        if (!mIsHighBrightnessModeEnabled || mHbmData == null) {
            return null;
        }

        HighBrightnessModeData hbmData = new HighBrightnessModeData();
        mHbmData.copyTo(hbmData);
        return hbmData;
    }

    /**
     * @return Power throttling configuration data for the display.
     */
    @Nullable
    public PowerThrottlingConfigData getPowerThrottlingConfigData() {
        return mPowerThrottlingConfigData;
    }

    @NonNull
    public Map<BrightnessLimitMapType, Map<Float, Float>> getLuxThrottlingData() {
        return mLuxThrottlingData;
    }

    public List<RefreshRateLimitation> getRefreshRateLimitations() {
        return mRefreshRateLimitations;
    }

    public DensityMapping getDensityMapping() {
        return mDensityMapping;
    }

    /**
     * @return brightness throttling configuration data for this display, for each throttling id.
     */
    public HashMap<String, ThermalBrightnessThrottlingData>
            getThermalBrightnessThrottlingDataMapByThrottlingId() {
        return mThermalBrightnessThrottlingDataMapByThrottlingId;
    }

    /**
     * @param id - throttling data id or null for default
     * @return refresh rate throttling configuration
     */
    @Nullable
    public SparseArray<SurfaceControl.RefreshRateRange> getThermalRefreshRateThrottlingData(
            @Nullable String id) {
        String key = id == null ? DEFAULT_ID : id;
        return mRefreshRateThrottlingMap.get(key);
    }

    /**
     * @return power throttling configuration data for this display, for each throttling id.
     **/
    public HashMap<String, PowerThrottlingData>
            getPowerThrottlingDataMapByThrottlingId() {
        return mPowerThrottlingDataMapByThrottlingId;
    }

    /**
     * @return Auto brightness darkening light debounce
     */
    public long getAutoBrightnessDarkeningLightDebounce() {
        return mAutoBrightnessDarkeningLightDebounce;
    }

    /**
     * @return Auto brightness brightening light debounce
     */
    public long getAutoBrightnessBrighteningLightDebounce() {
        return mAutoBrightnessBrighteningLightDebounce;
    }

    /**
     * @return Auto brightness darkening light debounce for idle screen brightness mode
     */
    public long getAutoBrightnessDarkeningLightDebounceIdle() {
        return mAutoBrightnessDarkeningLightDebounceIdle;
    }

    /**
     * @return Auto brightness brightening light debounce for idle screen brightness mode
     */
    public long getAutoBrightnessBrighteningLightDebounceIdle() {
        return mAutoBrightnessBrighteningLightDebounceIdle;
    }

    /**
     * @param mode The auto-brightness mode
     * @param preset The brightness preset. Presets are used on devices that allow users to choose
     *               from a set of predefined options in display auto-brightness settings.
     * @return The default auto-brightness brightening ambient lux levels for the specified mode
     * and preset
     */
    public float[] getAutoBrightnessBrighteningLevelsLux(
            @AutomaticBrightnessController.AutomaticBrightnessMode int mode, int preset) {
        if (mDisplayBrightnessMapping == null) {
            return null;
        }
        return mDisplayBrightnessMapping.getLuxArray(mode, preset);
    }

    /**
     * @return Auto brightness brightening nits levels
     */
    public float[] getAutoBrightnessBrighteningLevelsNits() {
        if (mDisplayBrightnessMapping == null) {
            return null;
        }
        return mDisplayBrightnessMapping.getNitsArray();
    }

    /**
     * @param mode The auto-brightness mode
     * @param preset The brightness preset. Presets are used on devices that allow users to choose
     *               from a set of predefined options in display auto-brightness settings.
     * @return The default auto-brightness brightening levels for the specified mode and preset
     */
    public float[] getAutoBrightnessBrighteningLevels(
            @AutomaticBrightnessController.AutomaticBrightnessMode int mode, int preset) {
        if (mDisplayBrightnessMapping == null) {
            return null;
        }
        return mDisplayBrightnessMapping.getBrightnessArray(mode, preset);
    }

    /**
     * @return Default peak refresh rate of the associated display
     */
    public int getDefaultPeakRefreshRate() {
        return mDefaultPeakRefreshRate;
    }

    /**
     * @return Default refresh rate of the associated display
     */
    public int getDefaultRefreshRate() {
        return mDefaultRefreshRate;
    }

    /**
     * @return Default refresh rate while the device has high brightness mode enabled for HDR.
     */
    public int getDefaultRefreshRateInHbmHdr() {
        return mDefaultRefreshRateInHbmHdr;
    }

    /**
     * @return Default refresh rate while the device has high brightness mode enabled because of
     * high lux.
     */
    public int getDefaultRefreshRateInHbmSunlight() {
        return mDefaultRefreshRateInHbmSunlight;
    }

    /**
     * @return Default refresh rate in the higher blocking zone of the associated display
     */
    public int getDefaultHighBlockingZoneRefreshRate() {
        return mDefaultHighBlockingZoneRefreshRate;
    }

    /**
     * @return Default refresh rate in the lower blocking zone of the associated display
     */
    public int getDefaultLowBlockingZoneRefreshRate() {
        return mDefaultLowBlockingZoneRefreshRate;
    }

    /**
     * @return HDR brightness related configuration
     */
    @Nullable
    public HdrBrightnessData getHdrBrightnessData() {
        return mHdrBrightnessData;
    }

    /**
     * @return Refresh rate range for specific profile id or null
     */
    @Nullable
    public SurfaceControl.RefreshRateRange getRefreshRange(@Nullable String id) {
        if (TextUtils.isEmpty(id)) {
            return null;
        }
        return mRefreshRateZoneProfiles.get(id);
    }

    @NonNull
    @VisibleForTesting
    Map<String, SurfaceControl.RefreshRateRange> getRefreshRangeProfiles() {
        return mRefreshRateZoneProfiles;
    }

    /**
     * @return An array of lower display brightness thresholds. This, in combination with lower
     * ambient brightness thresholds help define buckets in which the refresh rate switching is not
     * allowed.
     *
     * A negative threshold value means that only the lux threshold is applied.
     */
    public float[] getLowDisplayBrightnessThresholds() {
        return mLowDisplayBrightnessThresholds;
    }

    /**
     * @return An array of lower ambient brightness thresholds. This, in combination with lower
     * display brightness thresholds help define buckets in which the refresh rate switching is not
     * allowed.
     *
     * A negative threshold value means that only the display brightness threshold is applied.
     */
    public float[] getLowAmbientBrightnessThresholds() {
        return mLowAmbientBrightnessThresholds;
    }

    /**
     * @return The refresh rate thermal map for low blocking zone.
     */
    public SparseArray<SurfaceControl.RefreshRateRange> getLowBlockingZoneThermalMap() {
        return getThermalRefreshRateThrottlingData(mLowBlockingZoneThermalMapId);
    }

    /**
     * @return An array of high display brightness thresholds. This, in combination with high
     * ambient brightness thresholds help define buckets in which the refresh rate switching is not
     * allowed.
     *
     * A negative threshold value means that only the lux threshold is applied.
     */
    public float[] getHighDisplayBrightnessThresholds() {
        return mHighDisplayBrightnessThresholds;
    }

    /**
     * @return An array of high ambient brightness thresholds. This, in combination with high
     * display brightness thresholds help define buckets in which the refresh rate switching is not
     * allowed.
     *
     * A negative threshold value means that only the display brightness threshold is applied.
     */
    public float[] getHighAmbientBrightnessThresholds() {
        return mHighAmbientBrightnessThresholds;
    }

    /**
     * @return The refresh rate thermal map for high blocking zone.
     */
    public SparseArray<SurfaceControl.RefreshRateRange> getHighBlockingZoneThermalMap() {
        return getThermalRefreshRateThrottlingData(mHighBlockingZoneThermalMapId);
    }

    /**
     * @return A mapping from screen off brightness sensor readings to lux values. This estimates
     * the ambient lux when the screen is off to determine the initial brightness
     */
    public int[] getScreenOffBrightnessSensorValueToLux() {
        return mScreenOffBrightnessSensorValueToLux;
    }

    /**
     * @return The USI version supported by this display, or null if USI is not supported.
     * @see HostUsiVersion
     */
    @Nullable
    public HostUsiVersion getHostUsiVersion() {
        return mHostUsiVersion;
    }

    /**
     * @return Maximum screen brightness setting when screen brightness capped in Wear Bedtime mode.
     */
    public float getBrightnessCapForWearBedtimeMode() {
        return mBrightnessCapForWearBedtimeMode;
    }

    @Override
    public String toString() {
        return "DisplayDeviceConfig{"
                + "mLoadedFrom=" + mLoadedFrom
                + "\n"
                + "mBacklight=" + Arrays.toString(mBacklight)
                + ", mNits=" + Arrays.toString(mNits)
                + ", mRawBacklight=" + Arrays.toString(mRawBacklight)
                + ", mRawNits=" + Arrays.toString(mRawNits)
                + ", mInterpolationType=" + mInterpolationType
                + "mBrightness=" + Arrays.toString(mBrightness)
                + "\n"
                + "mBrightnessToBacklightSpline=" + mBrightnessToBacklightSpline
                + ", mBacklightToBrightnessSpline=" + mBacklightToBrightnessSpline
                + ", mNitsToBacklightSpline=" + mNitsToBacklightSpline
                + ", mBacklightMinimum=" + mBacklightMinimum
                + ", mBacklightMaximum=" + mBacklightMaximum
                + ", mBrightnessDefault=" + mBrightnessDefault
                + ", mQuirks=" + mQuirks
                + ", mIsHighBrightnessModeEnabled=" + mIsHighBrightnessModeEnabled
                + "\n"
                + "mLuxThrottlingData=" + mLuxThrottlingData
                + ", mHbmData=" + mHbmData
                + ", mSdrToHdrRatioSpline=" + mSdrToHdrRatioSpline
                + ", mThermalBrightnessThrottlingDataMapByThrottlingId="
                + mThermalBrightnessThrottlingDataMapByThrottlingId
                + "\n"
                + ", mPowerThrottlingDataMapByThrottlingId="
                + mPowerThrottlingDataMapByThrottlingId
                + "\n"
                + "mBrightnessRampFastDecrease=" + mBrightnessRampFastDecrease
                + ", mBrightnessRampFastIncrease=" + mBrightnessRampFastIncrease
                + ", mBrightnessRampSlowDecrease=" + mBrightnessRampSlowDecrease
                + ", mBrightnessRampSlowIncrease=" + mBrightnessRampSlowIncrease
                + ", mBrightnessRampSlowDecreaseIdle=" + mBrightnessRampSlowDecreaseIdle
                + ", mBrightnessRampSlowIncreaseIdle=" + mBrightnessRampSlowIncreaseIdle
                + ", mBrightnessRampDecreaseMaxMillis=" + mBrightnessRampDecreaseMaxMillis
                + ", mBrightnessRampIncreaseMaxMillis=" + mBrightnessRampIncreaseMaxMillis
                + ", mBrightnessRampDecreaseMaxIdleMillis=" + mBrightnessRampDecreaseMaxIdleMillis
                + ", mBrightnessRampIncreaseMaxIdleMillis=" + mBrightnessRampIncreaseMaxIdleMillis
                + "\n"
                + "mAmbientHorizonLong=" + mAmbientHorizonLong
                + ", mAmbientHorizonShort=" + mAmbientHorizonShort
                + "\n"
                + "mScreenDarkeningMinThreshold=" + mScreenDarkeningMinThreshold
                + ", mScreenDarkeningMinThresholdIdle=" + mScreenDarkeningMinThresholdIdle
                + ", mScreenBrighteningMinThreshold=" + mScreenBrighteningMinThreshold
                + ", mScreenBrighteningMinThresholdIdle=" + mScreenBrighteningMinThresholdIdle
                + ", mAmbientLuxDarkeningMinThreshold=" + mAmbientLuxDarkeningMinThreshold
                + ", mAmbientLuxDarkeningMinThresholdIdle=" + mAmbientLuxDarkeningMinThresholdIdle
                + ", mAmbientLuxBrighteningMinThreshold=" + mAmbientLuxBrighteningMinThreshold
                + ", mAmbientLuxBrighteningMinThresholdIdle="
                + mAmbientLuxBrighteningMinThresholdIdle
                + "\n"
                + "mScreenBrighteningLevels=" + Arrays.toString(
                mScreenBrighteningLevels)
                + ", mScreenBrighteningPercentages=" + Arrays.toString(
                mScreenBrighteningPercentages)
                + ", mScreenDarkeningLevels=" + Arrays.toString(
                mScreenDarkeningLevels)
                + ", mScreenDarkeningPercentages=" + Arrays.toString(
                mScreenDarkeningPercentages)
                + ", mAmbientBrighteningLevels=" + Arrays.toString(
                mAmbientBrighteningLevels)
                + ", mAmbientBrighteningPercentages=" + Arrays.toString(
                mAmbientBrighteningPercentages)
                + ", mAmbientDarkeningLevels=" + Arrays.toString(
                mAmbientDarkeningLevels)
                + ", mAmbientDarkeningPercentages=" + Arrays.toString(
                mAmbientDarkeningPercentages)
                + "\n"
                + "mAmbientBrighteningLevelsIdle=" + Arrays.toString(
                mAmbientBrighteningLevelsIdle)
                + ", mAmbientBrighteningPercentagesIdle=" + Arrays.toString(
                mAmbientBrighteningPercentagesIdle)
                + ", mAmbientDarkeningLevelsIdle=" + Arrays.toString(
                mAmbientDarkeningLevelsIdle)
                + ", mAmbientDarkeningPercentagesIdle=" + Arrays.toString(
                mAmbientDarkeningPercentagesIdle)
                + ", mScreenBrighteningLevelsIdle=" + Arrays.toString(
                mScreenBrighteningLevelsIdle)
                + ", mScreenBrighteningPercentagesIdle=" + Arrays.toString(
                mScreenBrighteningPercentagesIdle)
                + ", mScreenDarkeningLevelsIdle=" + Arrays.toString(
                mScreenDarkeningLevelsIdle)
                + ", mScreenDarkeningPercentagesIdle=" + Arrays.toString(
                mScreenDarkeningPercentagesIdle)
                + "\n"
                + "mAmbientLightSensor=" + mAmbientLightSensor
                + ", mScreenOffBrightnessSensor=" + mScreenOffBrightnessSensor
                + ", mProximitySensor=" + mProximitySensor
                + ", mRefreshRateLimitations= " + Arrays.toString(mRefreshRateLimitations.toArray())
                + ", mDensityMapping= " + mDensityMapping
                + ", mAutoBrightnessBrighteningLightDebounce= "
                + mAutoBrightnessBrighteningLightDebounce
                + ", mAutoBrightnessDarkeningLightDebounce= "
                + mAutoBrightnessDarkeningLightDebounce
                + ", mAutoBrightnessBrighteningLightDebounceIdle= "
                + mAutoBrightnessBrighteningLightDebounceIdle
                + ", mAutoBrightnessDarkeningLightDebounceIdle= "
                + mAutoBrightnessDarkeningLightDebounceIdle
                + ", mDisplayBrightnessMapping= " + mDisplayBrightnessMapping
                + ", mDdcAutoBrightnessAvailable= " + mDdcAutoBrightnessAvailable
                + ", mAutoBrightnessAvailable= " + mAutoBrightnessAvailable
                + "\n"
                + "mDefaultLowBlockingZoneRefreshRate= " + mDefaultLowBlockingZoneRefreshRate
                + ", mDefaultHighBlockingZoneRefreshRate= " + mDefaultHighBlockingZoneRefreshRate
                + ", mDefaultPeakRefreshRate= " + mDefaultPeakRefreshRate
                + ", mDefaultRefreshRate= " + mDefaultRefreshRate
                + ", mRefreshRateZoneProfiles= " + mRefreshRateZoneProfiles
                + ", mDefaultRefreshRateInHbmHdr= " + mDefaultRefreshRateInHbmHdr
                + ", mDefaultRefreshRateInHbmSunlight= " + mDefaultRefreshRateInHbmSunlight
                + ", mRefreshRateThrottlingMap= " + mRefreshRateThrottlingMap
                + ", mLowBlockingZoneThermalMapId= " + mLowBlockingZoneThermalMapId
                + ", mHighBlockingZoneThermalMapId= " + mHighBlockingZoneThermalMapId
                + "\n"
                + "mLowDisplayBrightnessThresholds= "
                + Arrays.toString(mLowDisplayBrightnessThresholds)
                + ", mLowAmbientBrightnessThresholds= "
                + Arrays.toString(mLowAmbientBrightnessThresholds)
                + ", mHighDisplayBrightnessThresholds= "
                + Arrays.toString(mHighDisplayBrightnessThresholds)
                + ", mHighAmbientBrightnessThresholds= "
                + Arrays.toString(mHighAmbientBrightnessThresholds)
                + "\n"
                + "mScreenOffBrightnessSensorValueToLux= " + Arrays.toString(
                mScreenOffBrightnessSensorValueToLux)
                + "\n"
                + "mUsiVersion= " + mHostUsiVersion + "\n"
                + "mHdrBrightnessData= " + mHdrBrightnessData + "\n"
                + "mBrightnessCapForWearBedtimeMode= " + mBrightnessCapForWearBedtimeMode
                + "}";
    }

    private static DisplayDeviceConfig getConfigFromSuffix(Context context, File baseDirectory,
            String suffixFormat, long idNumber, DisplayManagerFlags flags) {

        final String suffix = String.format(Locale.ROOT, suffixFormat, idNumber);
        final String filename = String.format(Locale.ROOT, CONFIG_FILE_FORMAT, suffix);
        final File filePath = Environment.buildPath(
                baseDirectory, ETC_DIR, DISPLAY_CONFIG_DIR, filename);
        final DisplayDeviceConfig config = new DisplayDeviceConfig(context, flags);
        if (config.initFromFile(filePath)) {
            return config;
        }
        return null;
    }

    private static DisplayDeviceConfig getConfigFromGlobalXml(Context context,
            DisplayManagerFlags flags) {
        DisplayDeviceConfig config = new DisplayDeviceConfig(context, flags);
        config.initFromGlobalXml();
        return config;
    }

    private static DisplayDeviceConfig getConfigFromPmValues(Context context,
            DisplayManagerFlags flags) {
        DisplayDeviceConfig config = new DisplayDeviceConfig(context, flags);
        config.initFromDefaultValues();
        return config;
    }

    @VisibleForTesting
    boolean initFromFile(File configFile) {
        if (!configFile.exists()) {
            // Display configuration files aren't required to exist.
            return false;
        }

        if (!configFile.isFile()) {
            Slog.e(TAG, "Display configuration is not a file: " + configFile + ", skipping");
            return false;
        }

        try (InputStream in = new BufferedInputStream(new FileInputStream(configFile))) {
            final DisplayConfiguration config = XmlParser.read(in);
            if (config != null) {
                loadName(config);
                loadDensityMapping(config);
                loadBrightnessDefaultFromDdcXml(config);
                loadBrightnessConstraintsFromConfigXml();
                loadBrightnessMap(config);
                loadThermalThrottlingConfig(config);
                loadPowerThrottlingConfigData(config);
                loadHighBrightnessModeData(config);
                loadLuxThrottling(config);
                loadQuirks(config);
                loadBrightnessRamps(config);
                mAmbientLightSensor = SensorData.loadAmbientLightSensorConfig(config,
                        mContext.getResources());
                mScreenOffBrightnessSensor = SensorData.loadScreenOffBrightnessSensorConfig(config);
                mProximitySensor = SensorData.loadProxSensorConfig(config);
                loadAmbientHorizonFromDdc(config);
                loadBrightnessChangeThresholds(config);
                loadAutoBrightnessConfigValues(config);
                loadRefreshRateSetting(config);
                loadScreenOffBrightnessSensorValueToLuxFromDdc(config);
                loadUsiVersion(config);
                mHdrBrightnessData = HdrBrightnessData.loadConfig(config);
                loadBrightnessCapForWearBedtimeMode(config);
            } else {
                Slog.w(TAG, "DisplayDeviceConfig file is null");
            }
        } catch (IOException | DatatypeConfigurationException | XmlPullParserException e) {
            Slog.e(TAG, "Encountered an error while reading/parsing display config file: "
                    + configFile, e);
        }
        mLoadedFrom = configFile.toString();
        return true;
    }

    private void initFromGlobalXml() {
        // If no ddc exists, use config.xml
        loadBrightnessDefaultFromConfigXml();
        loadBrightnessConstraintsFromConfigXml();
        loadBrightnessMapFromConfigXml();
        loadBrightnessRampsFromConfigXml();
        mAmbientLightSensor = SensorData.loadAmbientLightSensorConfig(mContext.getResources());
        mProximitySensor = SensorData.loadSensorUnspecifiedConfig();
        loadBrightnessChangeThresholdsFromXml();
        loadAutoBrightnessConfigsFromConfigXml();
        loadAutoBrightnessAvailableFromConfigXml();
        loadRefreshRateSetting(null);
        loadBrightnessCapForWearBedtimeModeFromConfigXml();
        mLoadedFrom = "<config.xml>";
    }

    private void initFromDefaultValues() {
        // Set all to basic values
        mLoadedFrom = "Static values";
        mBacklightMinimum = PowerManager.BRIGHTNESS_MIN;
        mBacklightMaximum = PowerManager.BRIGHTNESS_MAX;
        mBrightnessDefault = BRIGHTNESS_DEFAULT;
        mBrightnessRampFastDecrease = PowerManager.BRIGHTNESS_MAX;
        mBrightnessRampFastIncrease = PowerManager.BRIGHTNESS_MAX;
        mBrightnessRampSlowDecrease = PowerManager.BRIGHTNESS_MAX;
        mBrightnessRampSlowIncrease = PowerManager.BRIGHTNESS_MAX;
        mBrightnessRampSlowDecreaseIdle = PowerManager.BRIGHTNESS_MAX;
        mBrightnessRampSlowIncreaseIdle = PowerManager.BRIGHTNESS_MAX;
        mBrightnessRampDecreaseMaxMillis = 0;
        mBrightnessRampIncreaseMaxMillis = 0;
        mBrightnessRampDecreaseMaxIdleMillis = 0;
        mBrightnessRampIncreaseMaxIdleMillis = 0;
        setSimpleMappingStrategyValues();
        mAmbientLightSensor = SensorData.loadAmbientLightSensorConfig(mContext.getResources());
        mProximitySensor = SensorData.loadSensorUnspecifiedConfig();
        loadAutoBrightnessAvailableFromConfigXml();
    }

    private void copyUninitializedValuesFromSecondaryConfig(DisplayConfiguration defaultConfig) {
        if (defaultConfig == null) {
            return;
        }

        if (mDensityMapping == null) {
            loadDensityMapping(defaultConfig);
        }
    }

    private void loadName(DisplayConfiguration config) {
        mName = config.getName();
    }

    private void loadDensityMapping(DisplayConfiguration config) {
        if (config.getDensityMapping() == null) {
            return;
        }

        final List<Density> entriesFromXml = config.getDensityMapping().getDensity();

        final DensityMapping.Entry[] entries =
                new DensityMapping.Entry[entriesFromXml.size()];
        for (int i = 0; i < entriesFromXml.size(); i++) {
            final Density density = entriesFromXml.get(i);
            entries[i] = new DensityMapping.Entry(
                    density.getWidth().intValue(),
                    density.getHeight().intValue(),
                    density.getDensity().intValue());
        }
        mDensityMapping = DensityMapping.createByOwning(entries);
    }

    private void loadBrightnessDefaultFromDdcXml(DisplayConfiguration config) {
        // Default brightness values are stored in the displayDeviceConfig file,
        // Or we fallback standard values if not.
        // Priority 1: Value in the displayDeviceConfig
        // Priority 2: Value in the config.xml (float)
        // Priority 3: Value in the config.xml (int)
        if (config != null) {
            BigDecimal configBrightnessDefault = config.getScreenBrightnessDefault();
            if (configBrightnessDefault != null) {
                mBrightnessDefault = configBrightnessDefault.floatValue();
            } else {
                loadBrightnessDefaultFromConfigXml();
            }
        }
    }

    private void loadBrightnessDefaultFromConfigXml() {
        // Priority 1: Value in the config.xml (float)
        // Priority 2: Value in the config.xml (int)
        final float def = mContext.getResources().getFloat(com.android.internal.R.dimen
                .config_screenBrightnessSettingDefaultFloat);
        if (def == INVALID_BRIGHTNESS_IN_CONFIG) {
            mBrightnessDefault = BrightnessSynchronizer.brightnessIntToFloat(
                    mContext.getResources().getInteger(com.android.internal.R.integer
                            .config_screenBrightnessSettingDefault));
        } else {
            mBrightnessDefault = def;
        }
    }

    private void loadBrightnessConstraintsFromConfigXml() {
        // TODO(b/175373898) add constraints (min / max) to ddc.
        final float min = mContext.getResources().getFloat(com.android.internal.R.dimen
                .config_screenBrightnessSettingMinimumFloat);
        final float max = mContext.getResources().getFloat(com.android.internal.R.dimen
                .config_screenBrightnessSettingMaximumFloat);
        if (min == INVALID_BRIGHTNESS_IN_CONFIG || max == INVALID_BRIGHTNESS_IN_CONFIG) {
            mBacklightMinimum = BrightnessSynchronizer.brightnessIntToFloat(
                    mContext.getResources().getInteger(com.android.internal.R.integer
                            .config_screenBrightnessSettingMinimum));
            mBacklightMaximum = BrightnessSynchronizer.brightnessIntToFloat(
                    mContext.getResources().getInteger(com.android.internal.R.integer
                            .config_screenBrightnessSettingMaximum));
        } else {
            mBacklightMinimum = min;
            mBacklightMaximum = max;
        }
    }

    private void loadBrightnessMap(DisplayConfiguration config) {
        final NitsMap map = config.getScreenBrightnessMap();
        // Map may not exist in display device config
        if (map == null) {
            loadBrightnessMapFromConfigXml();
            return;
        }

        // Use the (preferred) display device config mapping
        final List<Point> points = map.getPoint();
        final int size = points.size();

        float[] nits = new float[size];
        float[] backlight = new float[size];

        mInterpolationType = convertInterpolationType(map.getInterpolation());
        int i = 0;
        for (Point point : points) {
            nits[i] = point.getNits().floatValue();
            backlight[i] = point.getValue().floatValue();
            if (i > 0) {
                if (nits[i] < nits[i - 1]) {
                    Slog.e(TAG, "screenBrightnessMap must be non-decreasing, ignoring rest "
                            + " of configuration. Nits: " + nits[i] + " < " + nits[i - 1]);
                    return;
                }

                if (backlight[i] < backlight[i - 1]) {
                    Slog.e(TAG, "screenBrightnessMap must be non-decreasing, ignoring rest "
                            + " of configuration. Value: " + backlight[i] + " < "
                            + backlight[i - 1]);
                    return;
                }
            }
            ++i;
        }
        mRawNits = nits;
        mRawBacklight = backlight;
        constrainNitsAndBacklightArrays();
    }

    private Spline loadSdrHdrRatioMap(HighBrightnessMode hbmConfig) {
        final SdrHdrRatioMap sdrHdrRatioMap = hbmConfig.getSdrHdrRatioMap_all();

        if (sdrHdrRatioMap == null) {
            return null;
        }

        final List<SdrHdrRatioPoint> points = sdrHdrRatioMap.getPoint();
        final int size = points.size();
        if (size <= 0) {
            return null;
        }

        float[] nits = new float[size];
        float[] ratios = new float[size];

        int i = 0;
        for (SdrHdrRatioPoint point : points) {
            nits[i] = point.getSdrNits().floatValue();
            if (i > 0) {
                if (nits[i] < nits[i - 1]) {
                    Slog.e(TAG, "sdrHdrRatioMap must be non-decreasing, ignoring rest "
                            + " of configuration. nits: " + nits[i] + " < "
                            + nits[i - 1]);
                    return null;
                }
            }
            ratios[i] = point.getHdrRatio().floatValue();
            ++i;
        }

        return Spline.createSpline(nits, ratios);
    }

    private void loadThermalThrottlingConfig(DisplayConfiguration config) {
        final ThermalThrottling throttlingConfig = config.getThermalThrottling();
        if (throttlingConfig == null) {
            Slog.i(TAG, "No thermal throttling config found");
            return;
        }
        loadThermalBrightnessThrottlingMaps(throttlingConfig);
        loadThermalRefreshRateThrottlingMap(throttlingConfig);
    }

    private void loadThermalBrightnessThrottlingMaps(ThermalThrottling throttlingConfig) {
        final List<BrightnessThrottlingMap> maps = throttlingConfig.getBrightnessThrottlingMap();
        if (maps == null || maps.isEmpty()) {
            Slog.i(TAG, "No brightness throttling map found");
            return;
        }

        for (BrightnessThrottlingMap map : maps) {
            final List<BrightnessThrottlingPoint> points = map.getBrightnessThrottlingPoint();
            // At least 1 point is guaranteed by the display device config schema
            List<ThermalBrightnessThrottlingData.ThrottlingLevel> throttlingLevels =
                    new ArrayList<>(points.size());

            boolean badConfig = false;
            for (BrightnessThrottlingPoint point : points) {
                ThermalStatus status = point.getThermalStatus();
                if (!thermalStatusIsValid(status)) {
                    badConfig = true;
                    break;
                }

                throttlingLevels.add(new ThermalBrightnessThrottlingData.ThrottlingLevel(
                        convertThermalStatus(status), point.getBrightness().floatValue()));
            }

            if (!badConfig) {
                String id = map.getId() == null ? DEFAULT_ID
                        : map.getId();
                if (mThermalBrightnessThrottlingDataMapByThrottlingId.containsKey(id)) {
                    throw new RuntimeException("Brightness throttling data with ID " + id
                            + " already exists");
                }
                mThermalBrightnessThrottlingDataMapByThrottlingId.put(id,
                        ThermalBrightnessThrottlingData.create(throttlingLevels));
            }
        }
    }

    private void loadThermalRefreshRateThrottlingMap(ThermalThrottling throttlingConfig) {
        List<RefreshRateThrottlingMap> maps = throttlingConfig.getRefreshRateThrottlingMap();
        if (maps == null || maps.isEmpty()) {
            Slog.w(TAG, "RefreshRateThrottling: map not found");
            return;
        }

        for (RefreshRateThrottlingMap map : maps) {
            List<RefreshRateThrottlingPoint> points = map.getRefreshRateThrottlingPoint();
            String id = map.getId() == null ? DEFAULT_ID : map.getId();

            if (points == null || points.isEmpty()) {
                // Expected at lease 1 throttling point for each map
                Slog.w(TAG, "RefreshRateThrottling: points not found for mapId=" + id);
                continue;
            }
            if (mRefreshRateThrottlingMap.containsKey(id)) {
                Slog.wtf(TAG, "RefreshRateThrottling: map already exists, mapId=" + id);
                continue;
            }

            SparseArray<SurfaceControl.RefreshRateRange> refreshRates = new SparseArray<>();
            for (RefreshRateThrottlingPoint point : points) {
                ThermalStatus status = point.getThermalStatus();
                if (!thermalStatusIsValid(status)) {
                    Slog.wtf(TAG,
                            "RefreshRateThrottling: Invalid thermalStatus=" + status.getRawName()
                                    + ",mapId=" + id);
                    continue;
                }
                int thermalStatusInt = convertThermalStatus(status);
                if (refreshRates.contains(thermalStatusInt)) {
                    Slog.wtf(TAG, "RefreshRateThrottling: thermalStatus=" + status.getRawName()
                            + " is already in the map, mapId=" + id);
                    continue;
                }

                refreshRates.put(thermalStatusInt, new SurfaceControl.RefreshRateRange(
                        point.getRefreshRateRange().getMinimum().floatValue(),
                        point.getRefreshRateRange().getMaximum().floatValue()
                ));
            }
            if (refreshRates.size() == 0) {
                Slog.w(TAG, "RefreshRateThrottling: no valid throttling points found for map, "
                        + "mapId=" + id);
                continue;
            }
            mRefreshRateThrottlingMap.put(id, refreshRates);
        }
    }

    private boolean loadPowerThrottlingMaps(PowerThrottlingConfig throttlingConfig) {
        final List<PowerThrottlingMap> maps = throttlingConfig.getPowerThrottlingMap();
        if (maps == null || maps.isEmpty()) {
            Slog.i(TAG, "No power throttling map found");
            return false;
        }

        for (PowerThrottlingMap map : maps) {
            final List<PowerThrottlingPoint> points = map.getPowerThrottlingPoint();
            // At least 1 point is guaranteed by the display device config schema
            List<PowerThrottlingData.ThrottlingLevel> throttlingLevels =
                    new ArrayList<>(points.size());

            boolean badConfig = false;
            for (PowerThrottlingPoint point : points) {
                ThermalStatus status = point.getThermalStatus();
                if (!thermalStatusIsValid(status)) {
                    badConfig = true;
                    break;
                }

                throttlingLevels.add(new PowerThrottlingData.ThrottlingLevel(
                        convertThermalStatus(status),
                            point.getPowerQuotaMilliWatts().floatValue()));
            }

            if (!badConfig) {
                String id = map.getId() == null ? DEFAULT_ID : map.getId();
                if (mPowerThrottlingDataMapByThrottlingId.containsKey(id)) {
                    throw new RuntimeException("Power throttling data with ID " + id
                            + " already exists");
                }
                mPowerThrottlingDataMapByThrottlingId.put(id,
                        PowerThrottlingData.create(throttlingLevels));
            }
        }
        return true;
    }

    private void loadPowerThrottlingConfigData(DisplayConfiguration config) {
        final PowerThrottlingConfig powerThrottlingCfg = config.getPowerThrottlingConfig();
        if (powerThrottlingCfg == null) {
            return;
        }
        if (!loadPowerThrottlingMaps(powerThrottlingCfg)) {
            return;
        }
        float lowestBrightnessCap = powerThrottlingCfg.getBrightnessLowestCapAllowed().floatValue();
        int pollingWindowMillis = powerThrottlingCfg.getPollingWindowMillis().intValue();
        mPowerThrottlingConfigData = new PowerThrottlingConfigData(lowestBrightnessCap,
                                                                   pollingWindowMillis);
    }

    private void loadRefreshRateSetting(DisplayConfiguration config) {
        final RefreshRateConfigs refreshRateConfigs =
                (config == null) ? null : config.getRefreshRate();
        BlockingZoneConfig lowerBlockingZoneConfig =
                (refreshRateConfigs == null) ? null
                        : refreshRateConfigs.getLowerBlockingZoneConfigs();
        BlockingZoneConfig higherBlockingZoneConfig =
                (refreshRateConfigs == null) ? null
                        : refreshRateConfigs.getHigherBlockingZoneConfigs();
        loadPeakDefaultRefreshRate(refreshRateConfigs);
        loadDefaultRefreshRate(refreshRateConfigs);
        loadDefaultRefreshRateInHbm(refreshRateConfigs);
        loadLowerRefreshRateBlockingZones(lowerBlockingZoneConfig);
        loadHigherRefreshRateBlockingZones(higherBlockingZoneConfig);
        loadRefreshRateZoneProfiles(refreshRateConfigs);
    }

    private void loadPeakDefaultRefreshRate(RefreshRateConfigs refreshRateConfigs) {
        if (refreshRateConfigs == null || refreshRateConfigs.getDefaultPeakRefreshRate() == null) {
            mDefaultPeakRefreshRate = mContext.getResources().getInteger(
                R.integer.config_defaultPeakRefreshRate);
        } else {
            mDefaultPeakRefreshRate =
                refreshRateConfigs.getDefaultPeakRefreshRate().intValue();
        }
    }

    private void loadDefaultRefreshRate(RefreshRateConfigs refreshRateConfigs) {
        if (refreshRateConfigs == null || refreshRateConfigs.getDefaultRefreshRate() == null) {
            mDefaultRefreshRate = mContext.getResources().getInteger(
                R.integer.config_defaultRefreshRate);
        } else {
            mDefaultRefreshRate =
                refreshRateConfigs.getDefaultRefreshRate().intValue();
        }
    }

    /** Loads the refresh rate profiles. */
    private void loadRefreshRateZoneProfiles(RefreshRateConfigs refreshRateConfigs) {
        if (refreshRateConfigs == null || refreshRateConfigs.getRefreshRateZoneProfiles() == null) {
            return;
        }
        for (RefreshRateZone zone :
                refreshRateConfigs.getRefreshRateZoneProfiles().getRefreshRateZoneProfile()) {
            RefreshRateRange range = zone.getRefreshRateRange();
            mRefreshRateZoneProfiles.put(
                    zone.getId(),
                    new SurfaceControl.RefreshRateRange(
                    range.getMinimum().floatValue(), range.getMaximum().floatValue()));
        }
    }

    private void loadDefaultRefreshRateInHbm(RefreshRateConfigs refreshRateConfigs) {
        if (refreshRateConfigs != null
                && refreshRateConfigs.getDefaultRefreshRateInHbmHdr() != null) {
            mDefaultRefreshRateInHbmHdr = refreshRateConfigs.getDefaultRefreshRateInHbmHdr()
                    .intValue();
        } else {
            mDefaultRefreshRateInHbmHdr = mContext.getResources().getInteger(
                    R.integer.config_defaultRefreshRateInHbmHdr);
        }

        if (refreshRateConfigs != null
                && refreshRateConfigs.getDefaultRefreshRateInHbmSunlight() != null) {
            mDefaultRefreshRateInHbmSunlight =
                    refreshRateConfigs.getDefaultRefreshRateInHbmSunlight().intValue();
        } else {
            mDefaultRefreshRateInHbmSunlight = mContext.getResources().getInteger(
                R.integer.config_defaultRefreshRateInHbmSunlight);
        }
    }

    /**
     * Loads the refresh rate configurations pertaining to the lower blocking zones.
     */
    private void loadLowerRefreshRateBlockingZones(BlockingZoneConfig lowerBlockingZoneConfig) {
        if (lowerBlockingZoneConfig != null) {
            mLowBlockingZoneThermalMapId =
                    lowerBlockingZoneConfig.getRefreshRateThermalThrottlingId();
        }
        loadLowerBlockingZoneDefaultRefreshRate(lowerBlockingZoneConfig);
        loadLowerBrightnessThresholds(lowerBlockingZoneConfig);
    }

    /**
     * Loads the refresh rate configurations pertaining to the upper blocking zones.
     */
    private void loadHigherRefreshRateBlockingZones(BlockingZoneConfig upperBlockingZoneConfig) {
        if (upperBlockingZoneConfig != null) {
            mHighBlockingZoneThermalMapId =
                    upperBlockingZoneConfig.getRefreshRateThermalThrottlingId();
        }
        loadHigherBlockingZoneDefaultRefreshRate(upperBlockingZoneConfig);
        loadHigherBrightnessThresholds(upperBlockingZoneConfig);
    }

    /**
     * Loads the default peak refresh rate. Internally, this takes care of loading
     * the value from the display config, and if not present, falls back to config.xml.
     */
    private void loadHigherBlockingZoneDefaultRefreshRate(
                BlockingZoneConfig upperBlockingZoneConfig) {
        if (upperBlockingZoneConfig == null) {
            mDefaultHighBlockingZoneRefreshRate = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_fixedRefreshRateInHighZone);
        } else {
            mDefaultHighBlockingZoneRefreshRate =
                upperBlockingZoneConfig.getDefaultRefreshRate().intValue();
        }
    }

    /**
     * Loads the default refresh rate. Internally, this takes care of loading
     * the value from the display config, and if not present, falls back to config.xml.
     */
    private void loadLowerBlockingZoneDefaultRefreshRate(
                BlockingZoneConfig lowerBlockingZoneConfig) {
        if (lowerBlockingZoneConfig == null) {
            mDefaultLowBlockingZoneRefreshRate = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_defaultRefreshRateInZone);
        } else {
            mDefaultLowBlockingZoneRefreshRate =
                lowerBlockingZoneConfig.getDefaultRefreshRate().intValue();
        }
    }

    /**
     * Loads the lower brightness thresholds for refresh rate switching. Internally, this takes care
     * of loading the value from the display config, and if not present, falls back to config.xml.
     */
    private void loadLowerBrightnessThresholds(BlockingZoneConfig lowerBlockingZoneConfig) {
        if (lowerBlockingZoneConfig == null) {
            int[] lowDisplayBrightnessThresholdsInt = mContext.getResources().getIntArray(
                R.array.config_brightnessThresholdsOfPeakRefreshRate);
            int[] lowAmbientBrightnessThresholdsInt = mContext.getResources().getIntArray(
                R.array.config_ambientThresholdsOfPeakRefreshRate);
            if (lowDisplayBrightnessThresholdsInt == null
                    || lowAmbientBrightnessThresholdsInt == null
                    || lowDisplayBrightnessThresholdsInt.length
                    != lowAmbientBrightnessThresholdsInt.length) {
                throw new RuntimeException("display low brightness threshold array and ambient "
                    + "brightness threshold array have different length: "
                    + "lowDisplayBrightnessThresholdsInt="
                    + Arrays.toString(lowDisplayBrightnessThresholdsInt)
                    + ", lowAmbientBrightnessThresholdsInt="
                    + Arrays.toString(lowAmbientBrightnessThresholdsInt));
            }

            mLowDisplayBrightnessThresholds =
                    displayBrightnessThresholdsIntToFloat(lowDisplayBrightnessThresholdsInt);
            mLowAmbientBrightnessThresholds =
                    ambientBrightnessThresholdsIntToFloat(lowAmbientBrightnessThresholdsInt);
        } else {
            List<DisplayBrightnessPoint> lowerThresholdDisplayBrightnessPoints =
                    lowerBlockingZoneConfig.getBlockingZoneThreshold().getDisplayBrightnessPoint();
            int size = lowerThresholdDisplayBrightnessPoints.size();
            mLowDisplayBrightnessThresholds = new float[size];
            mLowAmbientBrightnessThresholds = new float[size];
            for (int i = 0; i < size; i++) {
                float thresholdNits = lowerThresholdDisplayBrightnessPoints
                        .get(i).getNits().floatValue();
                if (thresholdNits < 0) {
                    // A negative value means that there's no threshold
                    mLowDisplayBrightnessThresholds[i] = thresholdNits;
                } else {
                    float thresholdBacklight = mNitsToBacklightSpline.interpolate(thresholdNits);
                    mLowDisplayBrightnessThresholds[i] =
                            mBacklightToBrightnessSpline.interpolate(thresholdBacklight);
                }

                mLowAmbientBrightnessThresholds[i] = lowerThresholdDisplayBrightnessPoints
                    .get(i).getLux().floatValue();
            }
        }
    }

    /**
     * Loads the higher brightness thresholds for refresh rate switching. Internally, this takes
     * care of loading the value from the display config, and if not present, falls back to
     * config.xml.
     */
    private void loadHigherBrightnessThresholds(BlockingZoneConfig blockingZoneConfig) {
        if (blockingZoneConfig == null) {
            int[] highDisplayBrightnessThresholdsInt = mContext.getResources().getIntArray(
                R.array.config_highDisplayBrightnessThresholdsOfFixedRefreshRate);
            int[] highAmbientBrightnessThresholdsInt = mContext.getResources().getIntArray(
                R.array.config_highAmbientBrightnessThresholdsOfFixedRefreshRate);
            if (highDisplayBrightnessThresholdsInt == null
                    || highAmbientBrightnessThresholdsInt == null
                    || highDisplayBrightnessThresholdsInt.length
                    != highAmbientBrightnessThresholdsInt.length) {
                throw new RuntimeException("display high brightness threshold array and ambient "
                    + "brightness threshold array have different length: "
                    + "highDisplayBrightnessThresholdsInt="
                    + Arrays.toString(highDisplayBrightnessThresholdsInt)
                    + ", highAmbientBrightnessThresholdsInt="
                    + Arrays.toString(highAmbientBrightnessThresholdsInt));
            }

            mHighDisplayBrightnessThresholds =
                    displayBrightnessThresholdsIntToFloat(highDisplayBrightnessThresholdsInt);
            mHighAmbientBrightnessThresholds =
                    ambientBrightnessThresholdsIntToFloat(highAmbientBrightnessThresholdsInt);
        } else {
            List<DisplayBrightnessPoint> higherThresholdDisplayBrightnessPoints =
                    blockingZoneConfig.getBlockingZoneThreshold().getDisplayBrightnessPoint();
            int size = higherThresholdDisplayBrightnessPoints.size();
            mHighDisplayBrightnessThresholds = new float[size];
            mHighAmbientBrightnessThresholds = new float[size];
            for (int i = 0; i < size; i++) {
                float thresholdNits = higherThresholdDisplayBrightnessPoints
                        .get(i).getNits().floatValue();
                if (thresholdNits < 0) {
                    // A negative value means that there's no threshold
                    mHighDisplayBrightnessThresholds[i] = thresholdNits;
                } else {
                    float thresholdBacklight = mNitsToBacklightSpline.interpolate(thresholdNits);
                    mHighDisplayBrightnessThresholds[i] =
                            mBacklightToBrightnessSpline.interpolate(thresholdBacklight);
                }

                mHighAmbientBrightnessThresholds[i] = higherThresholdDisplayBrightnessPoints
                    .get(i).getLux().floatValue();
            }
        }
    }

    private void loadAutoBrightnessConfigValues(DisplayConfiguration config) {
        final AutoBrightness autoBrightness = config.getAutoBrightness();
        loadAutoBrightnessBrighteningLightDebounce(autoBrightness);
        loadAutoBrightnessDarkeningLightDebounce(autoBrightness);
        // Idle must be called after interactive, since we fall back to it if needed.
        loadAutoBrightnessBrighteningLightDebounceIdle(autoBrightness);
        loadAutoBrightnessDarkeningLightDebounceIdle(autoBrightness);
        mDisplayBrightnessMapping = new DisplayBrightnessMappingConfig(mContext, mFlags,
                autoBrightness, mBacklightToBrightnessSpline);
        loadEnableAutoBrightness(autoBrightness);
    }

    /**
     * Loads the auto-brightness brightening light debounce. Internally, this takes care of loading
     * the value from the display config, and if not present, falls back to config.xml.
     */
    private void loadAutoBrightnessBrighteningLightDebounce(AutoBrightness autoBrightnessConfig) {
        if (autoBrightnessConfig == null
                || autoBrightnessConfig.getBrighteningLightDebounceMillis() == null) {
            mAutoBrightnessBrighteningLightDebounce = mContext.getResources().getInteger(
                    com.android.internal.R.integer.config_autoBrightnessBrighteningLightDebounce);
        } else {
            mAutoBrightnessBrighteningLightDebounce =
                    autoBrightnessConfig.getBrighteningLightDebounceMillis().intValue();
        }
    }

    /**
     * Loads the auto-brightness darkening light debounce. Internally, this takes care of loading
     * the value from the display config, and if not present, falls back to config.xml.
     */
    private void loadAutoBrightnessDarkeningLightDebounce(AutoBrightness autoBrightnessConfig) {
        if (autoBrightnessConfig == null
                || autoBrightnessConfig.getDarkeningLightDebounceMillis() == null) {
            mAutoBrightnessDarkeningLightDebounce = mContext.getResources().getInteger(
                    com.android.internal.R.integer.config_autoBrightnessDarkeningLightDebounce);
        } else {
            mAutoBrightnessDarkeningLightDebounce =
                    autoBrightnessConfig.getDarkeningLightDebounceMillis().intValue();
        }
    }

    /**
     * Loads the auto-brightness brightening light debounce for idle mode. Internally, this takes
     * care of loading the value from the display config, and if not present, falls back to
     * whichever interactive value was chosen.
     */
    private void loadAutoBrightnessBrighteningLightDebounceIdle(
            AutoBrightness autoBrightnessConfig) {
        if (autoBrightnessConfig == null
                || autoBrightnessConfig.getBrighteningLightDebounceIdleMillis() == null) {
            mAutoBrightnessBrighteningLightDebounceIdle = mAutoBrightnessBrighteningLightDebounce;
        } else {
            mAutoBrightnessBrighteningLightDebounceIdle =
                    autoBrightnessConfig.getBrighteningLightDebounceIdleMillis().intValue();
        }
    }

    /**
     * Loads the auto-brightness darkening light debounce for idle mode. Internally, this takes
     * care of loading the value from the display config, and if not present, falls back to
     * whichever interactive value was chosen.
     */
    private void loadAutoBrightnessDarkeningLightDebounceIdle(AutoBrightness autoBrightnessConfig) {
        if (autoBrightnessConfig == null
                || autoBrightnessConfig.getDarkeningLightDebounceIdleMillis() == null) {
            mAutoBrightnessDarkeningLightDebounceIdle = mAutoBrightnessDarkeningLightDebounce;
        } else {
            mAutoBrightnessDarkeningLightDebounceIdle =
                    autoBrightnessConfig.getDarkeningLightDebounceIdleMillis().intValue();
        }
    }

    private void loadAutoBrightnessAvailableFromConfigXml() {
        mAutoBrightnessAvailable = mContext.getResources().getBoolean(
                R.bool.config_automatic_brightness_available);
    }

    private void loadBrightnessMapFromConfigXml() {
        // Use the config.xml mapping
        final Resources res = mContext.getResources();
        final float[] sysNits = BrightnessMappingStrategy.getFloatArray(res.obtainTypedArray(
                com.android.internal.R.array.config_screenBrightnessNits));
        final int[] sysBrightness = res.getIntArray(
                com.android.internal.R.array.config_screenBrightnessBacklight);
        final float[] sysBrightnessFloat = new float[sysBrightness.length];

        for (int i = 0; i < sysBrightness.length; i++) {
            sysBrightnessFloat[i] = BrightnessSynchronizer.brightnessIntToFloat(
                    sysBrightness[i]);
        }

        // These arrays are allowed to be empty, we set null values so that
        // BrightnessMappingStrategy will create a SimpleMappingStrategy instead.
        if (sysBrightnessFloat.length == 0 || sysNits.length == 0) {
            setSimpleMappingStrategyValues();
            return;
        }

        mRawNits = sysNits;
        mRawBacklight = sysBrightnessFloat;
        constrainNitsAndBacklightArrays();
    }

    private void setSimpleMappingStrategyValues() {
        // No translation from backlight to brightness should occur if we are using a
        // SimpleMappingStrategy (ie they should be the same) so the splines are
        // set to be linear, between 0.0 and 1.0
        mNits = null;
        mBacklight = null;
        float[] simpleMappingStrategyArray = new float[]{0.0f, 1.0f};
        mBrightnessToBacklightSpline = Spline.createSpline(simpleMappingStrategyArray,
                simpleMappingStrategyArray);
        mBacklightToBrightnessSpline = Spline.createSpline(simpleMappingStrategyArray,
                simpleMappingStrategyArray);
    }

    /**
     * Change the nits and backlight arrays, so that they cover only the allowed backlight values
     * Use the brightness minimum and maximum values to clamp these arrays.
     */
    private void constrainNitsAndBacklightArrays() {
        if (mRawBacklight[0] > mBacklightMinimum
                || mRawBacklight[mRawBacklight.length - 1] < mBacklightMaximum
                || mBacklightMinimum > mBacklightMaximum) {
            throw new IllegalStateException("Min or max values are invalid"
                    + "; raw min=" + mRawBacklight[0]
                    + "; raw max=" + mRawBacklight[mRawBacklight.length - 1]
                    + "; backlight min=" + mBacklightMinimum
                    + "; backlight max=" + mBacklightMaximum);
        }

        float[] newNits = new float[mRawBacklight.length];
        float[] newBacklight = new float[mRawBacklight.length];
        // Find the starting index of the clamped arrays. This may be less than the min so
        // we'll need to clamp this value still when actually doing the remapping.
        int newStart = 0;
        for (int i = 0; i < mRawBacklight.length - 1; i++) {
            if (mRawBacklight[i + 1] > mBacklightMinimum) {
                newStart = i;
                break;
            }
        }

        boolean isLastValue = false;
        int newIndex = 0;
        for (int i = newStart; i < mRawBacklight.length && !isLastValue; i++) {
            newIndex = i - newStart;
            final float newBacklightVal;
            final float newNitsVal;
            isLastValue = mRawBacklight[i] >= mBacklightMaximum
                    || i >= mRawBacklight.length - 1;
            // Clamp beginning and end to valid backlight values.
            if (newIndex == 0) {
                newBacklightVal = MathUtils.max(mRawBacklight[i], mBacklightMinimum);
                newNitsVal = rawBacklightToNits(i, newBacklightVal);
            } else if (isLastValue) {
                newBacklightVal = MathUtils.min(mRawBacklight[i], mBacklightMaximum);
                newNitsVal = rawBacklightToNits(i - 1, newBacklightVal);
            } else {
                newBacklightVal = mRawBacklight[i];
                newNitsVal = mRawNits[i];
            }
            newBacklight[newIndex] = newBacklightVal;
            newNits[newIndex] = newNitsVal;
        }
        mBacklight = Arrays.copyOf(newBacklight, newIndex + 1);
        mNits = Arrays.copyOf(newNits, newIndex + 1);
        createBacklightConversionSplines();
    }

    private float rawBacklightToNits(int i, float backlight) {
        return MathUtils.map(mRawBacklight[i], mRawBacklight[i + 1],
                mRawNits[i], mRawNits[i + 1], backlight);
    }

    // This method creates a brightness spline that is of equal length with proportional increments
    // to the backlight spline. The values of this array range from 0.0f to 1.0f instead of the
    // potential constrained range that the backlight array covers
    // These splines are used to convert from the system brightness value to the HAL backlight
    // value
    private void createBacklightConversionSplines() {
        mBrightness = new float[mBacklight.length];
        for (int i = 0; i < mBrightness.length; i++) {
            mBrightness[i] = MathUtils.map(mBacklight[0],
                    mBacklight[mBacklight.length - 1],
                    PowerManager.BRIGHTNESS_MIN, PowerManager.BRIGHTNESS_MAX, mBacklight[i]);
        }
        mBrightnessToBacklightSpline = mInterpolationType == INTERPOLATION_LINEAR
                ? Spline.createLinearSpline(mBrightness, mBacklight)
                : Spline.createSpline(mBrightness, mBacklight);
        mBacklightToBrightnessSpline = mInterpolationType == INTERPOLATION_LINEAR
                ? Spline.createLinearSpline(mBacklight, mBrightness)
                : Spline.createSpline(mBacklight, mBrightness);
        mBacklightToNitsSpline = mInterpolationType == INTERPOLATION_LINEAR
                ? Spline.createLinearSpline(mBacklight, mNits)
                : Spline.createSpline(mBacklight, mNits);
        mNitsToBacklightSpline = mInterpolationType == INTERPOLATION_LINEAR
                ? Spline.createLinearSpline(mNits, mBacklight)
                : Spline.createSpline(mNits, mBacklight);
    }

    private void loadQuirks(DisplayConfiguration config) {
        final DisplayQuirks quirks = config.getQuirks();
        if (quirks != null) {
            mQuirks = new ArrayList<>(quirks.getQuirk());
        }
    }

    private void loadHighBrightnessModeData(DisplayConfiguration config) {
        final HighBrightnessMode hbm = config.getHighBrightnessMode();
        if (hbm != null) {
            mIsHighBrightnessModeEnabled = hbm.getEnabled();
            mHbmData = new HighBrightnessModeData();
            mHbmData.minimumLux = hbm.getMinimumLux_all().floatValue();
            float transitionPointBacklightScale = hbm.getTransitionPoint_all().floatValue();
            if (transitionPointBacklightScale >= mBacklightMaximum) {
                throw new IllegalArgumentException("HBM transition point invalid. "
                        + mHbmData.transitionPoint + " is not less than "
                        + mBacklightMaximum);
            }
            mHbmData.transitionPoint =
                    mBacklightToBrightnessSpline.interpolate(transitionPointBacklightScale);
            final HbmTiming hbmTiming = hbm.getTiming_all();
            mHbmData.timeWindowMillis = hbmTiming.getTimeWindowSecs_all().longValue() * 1000;
            mHbmData.timeMaxMillis = hbmTiming.getTimeMaxSecs_all().longValue() * 1000;
            mHbmData.timeMinMillis = hbmTiming.getTimeMinSecs_all().longValue() * 1000;
            mHbmData.allowInLowPowerMode = hbm.getAllowInLowPowerMode_all();
            final RefreshRateRange rr = hbm.getRefreshRate_all();
            if (rr != null) {
                final float min = rr.getMinimum().floatValue();
                final float max = rr.getMaximum().floatValue();
                mRefreshRateLimitations.add(new RefreshRateLimitation(
                        DisplayManagerInternal.REFRESH_RATE_LIMIT_HIGH_BRIGHTNESS_MODE, min, max));
            }
            BigDecimal minHdrPctOfScreen = hbm.getMinimumHdrPercentOfScreen_all();
            if (minHdrPctOfScreen != null) {
                mHbmData.minimumHdrPercentOfScreen = minHdrPctOfScreen.floatValue();
                if (mHbmData.minimumHdrPercentOfScreen > 1
                        || mHbmData.minimumHdrPercentOfScreen < 0) {
                    Slog.w(TAG, "Invalid minimum HDR percent of screen: "
                            + String.valueOf(mHbmData.minimumHdrPercentOfScreen));
                    mHbmData.minimumHdrPercentOfScreen = HDR_PERCENT_OF_SCREEN_REQUIRED_DEFAULT;
                }
            } else {
                mHbmData.minimumHdrPercentOfScreen = HDR_PERCENT_OF_SCREEN_REQUIRED_DEFAULT;
            }

            mSdrToHdrRatioSpline = loadSdrHdrRatioMap(hbm);
        }
    }

    private void loadLuxThrottling(DisplayConfiguration config) {
        LuxThrottling cfg = config.getLuxThrottling();
        if (cfg != null) {
            HighBrightnessMode hbm = config.getHighBrightnessMode();
            float hbmTransitionPoint = hbm != null ? hbm.getTransitionPoint_all().floatValue()
                    : PowerManager.BRIGHTNESS_MAX;
            List<BrightnessLimitMap> limitMaps = cfg.getBrightnessLimitMap();
            for (BrightnessLimitMap map : limitMaps) {
                PredefinedBrightnessLimitNames type = map.getType();
                BrightnessLimitMapType mappedType = BrightnessLimitMapType.convert(type);
                if (mappedType == null) {
                    Slog.wtf(TAG, "Invalid NBM config: unsupported map type=" + type);
                    continue;
                }
                if (mLuxThrottlingData.containsKey(mappedType)) {
                    Slog.wtf(TAG, "Invalid NBM config: duplicate map type=" + mappedType);
                    continue;
                }
                Map<Float, Float> luxToTransitionPointMap = new HashMap<>();

                List<NonNegativeFloatToFloatPoint> points = map.getMap().getPoint();
                for (NonNegativeFloatToFloatPoint point : points) {
                    float lux = point.getFirst().floatValue();
                    float maxBrightness = point.getSecond().floatValue();
                    if (maxBrightness > hbmTransitionPoint) {
                        Slog.wtf(TAG,
                                "Invalid NBM config: maxBrightness is greater than hbm"
                                        + ".transitionPoint. type="
                                        + type + "; lux=" + lux + "; maxBrightness="
                                        + maxBrightness);
                        continue;
                    }
                    if (luxToTransitionPointMap.containsKey(lux)) {
                        Slog.wtf(TAG,
                                "Invalid NBM config: duplicate lux key. type=" + type + "; lux="
                                        + lux);
                        continue;
                    }
                    luxToTransitionPointMap.put(lux,
                            mBacklightToBrightnessSpline.interpolate(maxBrightness));
                }
                if (!luxToTransitionPointMap.isEmpty()) {
                    mLuxThrottlingData.put(mappedType, luxToTransitionPointMap);
                }
            }
        }
    }

    private void loadBrightnessRamps(DisplayConfiguration config) {
        // Interactive must come first, since idle falls back to it when values are unspecified.
        loadBrightnessRampsInteractive(config);
        loadBrightnessRampsIdle(config);
    }

    private void loadBrightnessRampsInteractive(DisplayConfiguration config) {
        // Priority 1: Value in the display device config (float)
        // Priority 2: Value in the config.xml (int)
        final BigDecimal fastDownDecimal = config.getScreenBrightnessRampFastDecrease();
        final BigDecimal fastUpDecimal = config.getScreenBrightnessRampFastIncrease();
        final BigDecimal slowDownDecimal = config.getScreenBrightnessRampSlowDecrease();
        final BigDecimal slowUpDecimal = config.getScreenBrightnessRampSlowIncrease();

        if (fastDownDecimal != null && fastUpDecimal != null && slowDownDecimal != null
                && slowUpDecimal != null) {
            mBrightnessRampFastDecrease = fastDownDecimal.floatValue();
            mBrightnessRampFastIncrease = fastUpDecimal.floatValue();
            mBrightnessRampSlowDecrease = slowDownDecimal.floatValue();
            mBrightnessRampSlowIncrease = slowUpDecimal.floatValue();
        } else {
            if (fastDownDecimal != null || fastUpDecimal != null || slowDownDecimal != null
                    || slowUpDecimal != null) {
                Slog.w(TAG, "Per display brightness ramp values ignored because not all "
                        + "values are present in display device config");
            }
            loadBrightnessRampsFromConfigXml();
        }

        final BigInteger increaseMax = config.getScreenBrightnessRampIncreaseMaxMillis();
        if (increaseMax != null) {
            mBrightnessRampIncreaseMaxMillis = increaseMax.intValue();
        }
        final BigInteger decreaseMax = config.getScreenBrightnessRampDecreaseMaxMillis();
        if (decreaseMax != null) {
            mBrightnessRampDecreaseMaxMillis = decreaseMax.intValue();
        }
    }

    private void loadBrightnessRampsIdle(DisplayConfiguration config) {
        // Priority 1: Idle value in the display device config (float)
        // Priority 2: Fallback - Interactive value from wherever.
        final BigDecimal slowDownDecimalIdle = config.getScreenBrightnessRampSlowDecreaseIdle();
        final BigDecimal slowUpDecimalIdle = config.getScreenBrightnessRampSlowIncreaseIdle();

        if (slowDownDecimalIdle != null && slowUpDecimalIdle != null) {
            mBrightnessRampSlowDecreaseIdle = slowDownDecimalIdle.floatValue();
            mBrightnessRampSlowIncreaseIdle = slowUpDecimalIdle.floatValue();
        } else {
            if (slowDownDecimalIdle != null || slowUpDecimalIdle != null) {
                Slog.w(TAG, "Per display idle brightness ramp values ignored because not all "
                        + "values are present in display device config");
            }
            // If these values don't exist, fall back to interactive mode values, since
            // there are no idle ramp values in config.xml
            mBrightnessRampSlowDecreaseIdle = mBrightnessRampSlowDecrease;
            mBrightnessRampSlowIncreaseIdle = mBrightnessRampSlowIncrease;
        }

        final BigInteger increaseMaxIdle = config.getScreenBrightnessRampIncreaseMaxIdleMillis();
        if (increaseMaxIdle != null) {
            mBrightnessRampIncreaseMaxIdleMillis = increaseMaxIdle.intValue();
        } else {
            mBrightnessRampIncreaseMaxIdleMillis = mBrightnessRampIncreaseMaxMillis;
        }
        final BigInteger decreaseMaxIdle = config.getScreenBrightnessRampDecreaseMaxIdleMillis();
        if (decreaseMaxIdle != null) {
            mBrightnessRampDecreaseMaxIdleMillis = decreaseMaxIdle.intValue();
        } else {
            mBrightnessRampDecreaseMaxIdleMillis = mBrightnessRampDecreaseMaxMillis;
        }
    }

    private void loadBrightnessRampsFromConfigXml() {
        mBrightnessRampFastIncrease = BrightnessSynchronizer.brightnessIntToFloat(
                mContext.getResources().getInteger(R.integer.config_brightness_ramp_rate_fast));
        mBrightnessRampSlowIncrease = BrightnessSynchronizer.brightnessIntToFloat(
                mContext.getResources().getInteger(R.integer.config_brightness_ramp_rate_slow));
        // config.xml uses the same values for both increasing and decreasing brightness
        // transitions so we assign them to the same values here.
        mBrightnessRampFastDecrease = mBrightnessRampFastIncrease;
        mBrightnessRampSlowDecrease = mBrightnessRampSlowIncrease;
    }

    private void loadAutoBrightnessConfigsFromConfigXml() {
        mDisplayBrightnessMapping = new DisplayBrightnessMappingConfig(mContext, mFlags,
                /* autoBrightnessConfig= */ null, mBacklightToBrightnessSpline);
    }

    private void loadBrightnessChangeThresholdsFromXml() {
        loadBrightnessChangeThresholds(/* config= */ null);
    }

    private void loadBrightnessChangeThresholds(DisplayConfiguration config) {
        loadDisplayBrightnessThresholds(config);
        loadAmbientBrightnessThresholds(config);
        loadDisplayBrightnessThresholdsIdle(config);
        loadAmbientBrightnessThresholdsIdle(config);
    }

    private void loadDisplayBrightnessThresholds(DisplayConfiguration config) {
        BrightnessThresholds brighteningScreen = null;
        BrightnessThresholds darkeningScreen = null;
        if (config != null && config.getDisplayBrightnessChangeThresholds() != null) {
            brighteningScreen =
                    config.getDisplayBrightnessChangeThresholds().getBrighteningThresholds();
            darkeningScreen =
                    config.getDisplayBrightnessChangeThresholds().getDarkeningThresholds();

        }

        // Screen bright/darkening threshold levels for active mode
        Pair<float[], float[]> screenBrighteningPair = getBrightnessLevelAndPercentage(
                brighteningScreen,
                com.android.internal.R.array.config_screenThresholdLevels,
                com.android.internal.R.array.config_screenBrighteningThresholds,
                DEFAULT_SCREEN_THRESHOLD_LEVELS, DEFAULT_SCREEN_BRIGHTENING_THRESHOLDS,
                /* potentialOldBrightnessScale= */ true);

        mScreenBrighteningLevels = screenBrighteningPair.first;
        mScreenBrighteningPercentages = screenBrighteningPair.second;

        Pair<float[], float[]> screenDarkeningPair = getBrightnessLevelAndPercentage(
                darkeningScreen,
                com.android.internal.R.array.config_screenThresholdLevels,
                com.android.internal.R.array.config_screenDarkeningThresholds,
                DEFAULT_SCREEN_THRESHOLD_LEVELS, DEFAULT_SCREEN_DARKENING_THRESHOLDS,
                /* potentialOldBrightnessScale= */ true);
        mScreenDarkeningLevels = screenDarkeningPair.first;
        mScreenDarkeningPercentages = screenDarkeningPair.second;

        // Screen bright/darkening threshold minimums for active mode
        if (brighteningScreen != null && brighteningScreen.getMinimum() != null) {
            mScreenBrighteningMinThreshold = brighteningScreen.getMinimum().floatValue();
        }
        if (darkeningScreen != null && darkeningScreen.getMinimum() != null) {
            mScreenDarkeningMinThreshold = darkeningScreen.getMinimum().floatValue();
        }
    }

    private void loadAmbientBrightnessThresholds(DisplayConfiguration config) {
        // Ambient Brightness Threshold Levels
        BrightnessThresholds brighteningAmbientLux = null;
        BrightnessThresholds darkeningAmbientLux = null;
        if (config != null && config.getAmbientBrightnessChangeThresholds() != null) {
            brighteningAmbientLux =
                    config.getAmbientBrightnessChangeThresholds().getBrighteningThresholds();
            darkeningAmbientLux =
                    config.getAmbientBrightnessChangeThresholds().getDarkeningThresholds();
        }

        // Ambient bright/darkening threshold levels for active mode
        Pair<float[], float[]> ambientBrighteningPair = getBrightnessLevelAndPercentage(
                brighteningAmbientLux,
                com.android.internal.R.array.config_ambientThresholdLevels,
                com.android.internal.R.array.config_ambientBrighteningThresholds,
                DEFAULT_AMBIENT_THRESHOLD_LEVELS, DEFAULT_AMBIENT_BRIGHTENING_THRESHOLDS);
        mAmbientBrighteningLevels = ambientBrighteningPair.first;
        mAmbientBrighteningPercentages = ambientBrighteningPair.second;

        Pair<float[], float[]> ambientDarkeningPair = getBrightnessLevelAndPercentage(
                darkeningAmbientLux,
                com.android.internal.R.array.config_ambientThresholdLevels,
                com.android.internal.R.array.config_ambientDarkeningThresholds,
                DEFAULT_AMBIENT_THRESHOLD_LEVELS, DEFAULT_AMBIENT_DARKENING_THRESHOLDS);
        mAmbientDarkeningLevels = ambientDarkeningPair.first;
        mAmbientDarkeningPercentages = ambientDarkeningPair.second;

        // Ambient bright/darkening threshold minimums for active/idle mode
        if (brighteningAmbientLux != null && brighteningAmbientLux.getMinimum() != null) {
            mAmbientLuxBrighteningMinThreshold =
                    brighteningAmbientLux.getMinimum().floatValue();
        }

        if (darkeningAmbientLux != null && darkeningAmbientLux.getMinimum() != null) {
            mAmbientLuxDarkeningMinThreshold = darkeningAmbientLux.getMinimum().floatValue();
        }
    }

    private void loadDisplayBrightnessThresholdsIdle(DisplayConfiguration config) {
        BrightnessThresholds brighteningScreenIdle = null;
        BrightnessThresholds darkeningScreenIdle = null;
        if (config != null && config.getDisplayBrightnessChangeThresholdsIdle() != null) {
            brighteningScreenIdle =
                    config.getDisplayBrightnessChangeThresholdsIdle().getBrighteningThresholds();
            darkeningScreenIdle =
                    config.getDisplayBrightnessChangeThresholdsIdle().getDarkeningThresholds();
        }

        Pair<float[], float[]> screenBrighteningPair = getBrightnessLevelAndPercentage(
                brighteningScreenIdle,
                com.android.internal.R.array.config_screenThresholdLevels,
                com.android.internal.R.array.config_screenBrighteningThresholds,
                DEFAULT_SCREEN_THRESHOLD_LEVELS, DEFAULT_SCREEN_BRIGHTENING_THRESHOLDS,
                /* potentialOldBrightnessScale= */ true);
        mScreenBrighteningLevelsIdle = screenBrighteningPair.first;
        mScreenBrighteningPercentagesIdle = screenBrighteningPair.second;

        Pair<float[], float[]> screenDarkeningPair = getBrightnessLevelAndPercentage(
                darkeningScreenIdle,
                com.android.internal.R.array.config_screenThresholdLevels,
                com.android.internal.R.array.config_screenDarkeningThresholds,
                DEFAULT_SCREEN_THRESHOLD_LEVELS, DEFAULT_SCREEN_DARKENING_THRESHOLDS,
                /* potentialOldBrightnessScale= */ true);
        mScreenDarkeningLevelsIdle = screenDarkeningPair.first;
        mScreenDarkeningPercentagesIdle = screenDarkeningPair.second;

        if (brighteningScreenIdle != null
                && brighteningScreenIdle.getMinimum() != null) {
            mScreenBrighteningMinThresholdIdle =
                    brighteningScreenIdle.getMinimum().floatValue();
        }
        if (darkeningScreenIdle != null && darkeningScreenIdle.getMinimum() != null) {
            mScreenDarkeningMinThresholdIdle =
                    darkeningScreenIdle.getMinimum().floatValue();
        }
    }

    private void loadAmbientBrightnessThresholdsIdle(DisplayConfiguration config) {
        BrightnessThresholds brighteningAmbientLuxIdle = null;
        BrightnessThresholds darkeningAmbientLuxIdle = null;
        if (config != null && config.getAmbientBrightnessChangeThresholdsIdle() != null) {
            brighteningAmbientLuxIdle =
                    config.getAmbientBrightnessChangeThresholdsIdle().getBrighteningThresholds();
            darkeningAmbientLuxIdle =
                    config.getAmbientBrightnessChangeThresholdsIdle().getDarkeningThresholds();
        }

        Pair<float[], float[]> ambientBrighteningPair = getBrightnessLevelAndPercentage(
                brighteningAmbientLuxIdle,
                com.android.internal.R.array.config_ambientThresholdLevels,
                com.android.internal.R.array.config_ambientBrighteningThresholds,
                DEFAULT_AMBIENT_THRESHOLD_LEVELS, DEFAULT_AMBIENT_BRIGHTENING_THRESHOLDS);
        mAmbientBrighteningLevelsIdle = ambientBrighteningPair.first;
        mAmbientBrighteningPercentagesIdle = ambientBrighteningPair.second;

        Pair<float[], float[]> ambientDarkeningPair = getBrightnessLevelAndPercentage(
                darkeningAmbientLuxIdle,
                com.android.internal.R.array.config_ambientThresholdLevels,
                com.android.internal.R.array.config_ambientDarkeningThresholds,
                DEFAULT_AMBIENT_THRESHOLD_LEVELS, DEFAULT_AMBIENT_DARKENING_THRESHOLDS);
        mAmbientDarkeningLevelsIdle = ambientDarkeningPair.first;
        mAmbientDarkeningPercentagesIdle = ambientDarkeningPair.second;

        if (brighteningAmbientLuxIdle != null
                && brighteningAmbientLuxIdle.getMinimum() != null) {
            mAmbientLuxBrighteningMinThresholdIdle =
                    brighteningAmbientLuxIdle.getMinimum().floatValue();
        }

        if (darkeningAmbientLuxIdle != null && darkeningAmbientLuxIdle.getMinimum() != null) {
            mAmbientLuxDarkeningMinThresholdIdle =
                    darkeningAmbientLuxIdle.getMinimum().floatValue();
        }
    }

    private Pair<float[], float[]> getBrightnessLevelAndPercentage(BrightnessThresholds thresholds,
            int configFallbackThreshold, int configFallbackPercentage, float[] defaultLevels,
            float[] defaultPercentage) {
        return getBrightnessLevelAndPercentage(thresholds, configFallbackThreshold,
                configFallbackPercentage, defaultLevels, defaultPercentage, false);
    }

    // Returns two float arrays, one of the brightness levels and one of the corresponding threshold
    // percentages for brightness levels at or above the lux value.
    // Historically, config.xml would have an array for brightness levels that was 1 shorter than
    // the levels array. Now we prepend a 0 to this array so they can be treated the same in the
    // rest of the framework. Values were also defined in different units (permille vs percent).
    private Pair<float[], float[]> getBrightnessLevelAndPercentage(BrightnessThresholds thresholds,
            int configFallbackThreshold, int configFallbackPermille,
            float[] defaultLevels, float[] defaultPercentage,
            boolean potentialOldBrightnessScale) {
        if (thresholds != null
                && thresholds.getBrightnessThresholdPoints() != null
                && thresholds.getBrightnessThresholdPoints()
                        .getBrightnessThresholdPoint().size() != 0) {

            // The level and percentages arrays are equal length in the ddc (new system)
            List<ThresholdPoint> points =
                    thresholds.getBrightnessThresholdPoints().getBrightnessThresholdPoint();
            final int size = points.size();

            float[] thresholdLevels = new float[size];
            float[] thresholdPercentages = new float[size];

            int i = 0;
            for (ThresholdPoint point : points) {
                thresholdLevels[i] = point.getThreshold().floatValue();
                thresholdPercentages[i] = point.getPercentage().floatValue();
                i++;
            }
            return new Pair<>(thresholdLevels, thresholdPercentages);
        } else {
            // The level and percentages arrays are unequal length in config.xml (old system)
            // We prefix the array with a 0 value to ensure they can be handled consistently
            // with the new system.

            // Load levels array
            int[] configThresholdArray = mContext.getResources().getIntArray(
                    configFallbackThreshold);
            int configThresholdsSize;
            if (configThresholdArray == null || configThresholdArray.length == 0) {
                configThresholdsSize = 1;
            } else {
                configThresholdsSize = configThresholdArray.length + 1;
            }


            // Load percentage array
            int[] configPermille = mContext.getResources().getIntArray(
                    configFallbackPermille);

            // Ensure lengths match up
            boolean emptyArray = configPermille == null || configPermille.length == 0;
            if (emptyArray && configThresholdsSize == 1) {
                return new Pair<>(defaultLevels, defaultPercentage);
            }
            if (emptyArray || configPermille.length != configThresholdsSize) {
                throw new IllegalArgumentException(
                        "Brightness threshold arrays do not align in length");
            }

            // Calculate levels array
            float[] configThresholdWithZeroPrefixed = new float[configThresholdsSize];
            // Start at 1, so that 0 index value is 0.0f (default)
            for (int i = 1; i < configThresholdsSize; i++) {
                configThresholdWithZeroPrefixed[i] = (float) configThresholdArray[i - 1];
            }
            if (potentialOldBrightnessScale) {
                configThresholdWithZeroPrefixed =
                        constraintInRangeIfNeeded(configThresholdWithZeroPrefixed);
            }

            // Calculate percentages array
            float[] configPercentage = new float[configThresholdsSize];
            for (int i = 0; i < configPermille.length; i++) {
                configPercentage[i] = configPermille[i] / 10.0f;
            }            return new Pair<>(configThresholdWithZeroPrefixed, configPercentage);
        }
    }

    /**
     * This check is due to historical reasons, where screen thresholdLevels used to be
     * integer values in the range of [0-255], but then was changed to be float values from [0,1].
     * To accommodate both the possibilities, we first check if all the thresholdLevels are in
     * [0,1], and if not, we divide all the levels with 255 to bring them down to the same scale.
     */
    private float[] constraintInRangeIfNeeded(float[] thresholdLevels) {
        if (isAllInRange(thresholdLevels, /* minValueInclusive= */ 0.0f,
                /* maxValueInclusive= */ 1.0f)) {
            return thresholdLevels;
        }

        Slog.w(TAG, "Detected screen thresholdLevels on a deprecated brightness scale");
        float[] thresholdLevelsScaled = new float[thresholdLevels.length];
        for (int index = 0; thresholdLevels.length > index; ++index) {
            thresholdLevelsScaled[index] = thresholdLevels[index] / 255.0f;
        }
        return thresholdLevelsScaled;
    }

    private boolean isAllInRange(float[] configArray, float minValueInclusive,
            float maxValueInclusive) {
        for (float v : configArray) {
            if (v < minValueInclusive || v > maxValueInclusive) {
                return false;
            }
        }
        return true;
    }

    private boolean thermalStatusIsValid(ThermalStatus value) {
        if (value == null) {
            return false;
        }

        switch (value) {
            case none:
            case light:
            case moderate:
            case severe:
            case critical:
            case emergency:
            case shutdown:
                return true;
            default:
                return false;
        }
    }

    @VisibleForTesting
    static @PowerManager.ThermalStatus int convertThermalStatus(ThermalStatus value) {
        if (value == null) {
            return PowerManager.THERMAL_STATUS_NONE;
        }
        switch (value) {
            case none:
                return PowerManager.THERMAL_STATUS_NONE;
            case light:
                return PowerManager.THERMAL_STATUS_LIGHT;
            case moderate:
                return PowerManager.THERMAL_STATUS_MODERATE;
            case severe:
                return PowerManager.THERMAL_STATUS_SEVERE;
            case critical:
                return PowerManager.THERMAL_STATUS_CRITICAL;
            case emergency:
                return PowerManager.THERMAL_STATUS_EMERGENCY;
            case shutdown:
                return PowerManager.THERMAL_STATUS_SHUTDOWN;
            default:
                Slog.wtf(TAG, "Unexpected Thermal Status: " + value);
                return PowerManager.THERMAL_STATUS_NONE;
        }
    }

    private int convertInterpolationType(String value) {
        if (TextUtils.isEmpty(value)) {
            return INTERPOLATION_DEFAULT;
        }

        if ("linear".equals(value)) {
            return INTERPOLATION_LINEAR;
        }

        Slog.wtf(TAG, "Unexpected Interpolation Type: " + value);
        return INTERPOLATION_DEFAULT;
    }

    private void loadAmbientHorizonFromDdc(DisplayConfiguration config) {
        final BigInteger configLongHorizon = config.getAmbientLightHorizonLong();
        if (configLongHorizon != null) {
            mAmbientHorizonLong = configLongHorizon.intValue();
        }
        final BigInteger configShortHorizon = config.getAmbientLightHorizonShort();
        if (configShortHorizon != null) {
            mAmbientHorizonShort = configShortHorizon.intValue();
        }
    }

    /**
     * Extracts a float array from the specified {@link TypedArray}.
     *
     * @param array The array to convert.
     * @return the given array as a float array.
     */
    public static float[] getFloatArray(TypedArray array, float defaultValue) {
        final int n = array.length();
        float[] vals = new float[n];
        for (int i = 0; i < n; i++) {
            vals[i] = array.getFloat(i, defaultValue);
        }
        array.recycle();
        return vals;
    }

    /**
     * @param lux The lux array
     * @return The lux array with 0 appended at the beginning - the first lux value should always
     * be 0
     */
    public static float[] getLuxLevels(int[] lux) {
        // The first control point is implicit and always at 0 lux.
        float[] levels = new float[lux.length + 1];
        for (int i = 0; i < lux.length; i++) {
            levels[i + 1] = (float) lux[i];
        }
        return levels;
    }

    private void loadEnableAutoBrightness(AutoBrightness autobrightness) {
        // mDdcAutoBrightnessAvailable is initialised to true, so that we fallback to using the
        // config.xml values if the autobrightness tag is not defined in the ddc file.
        // Autobrightness can still be turned off globally via config_automatic_brightness_available
        mDdcAutoBrightnessAvailable = true;
        if (autobrightness != null) {
            mDdcAutoBrightnessAvailable = autobrightness.getEnabled();
        }

        mAutoBrightnessAvailable = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_automatic_brightness_available)
                && mDdcAutoBrightnessAvailable;
    }

    private void loadScreenOffBrightnessSensorValueToLuxFromDdc(DisplayConfiguration config) {
        IntegerArray sensorValueToLux = config.getScreenOffBrightnessSensorValueToLux();
        if (sensorValueToLux == null) {
            return;
        }

        List<BigInteger> items = sensorValueToLux.getItem();
        mScreenOffBrightnessSensorValueToLux = new int[items.size()];
        for (int i = 0; i < items.size(); i++) {
            mScreenOffBrightnessSensorValueToLux[i] = items.get(i).intValue();
        }
    }

    private void loadUsiVersion(DisplayConfiguration config) {
        final UsiVersion usiVersion = config.getUsiVersion();
        mHostUsiVersion = usiVersion != null
                ? new HostUsiVersion(
                        usiVersion.getMajorVersion().intValue(),
                        usiVersion.getMinorVersion().intValue())
                : null;
    }

    private void loadBrightnessCapForWearBedtimeMode(DisplayConfiguration config) {
        if (config != null) {
            BigDecimal configBrightnessCap = config.getScreenBrightnessCapForWearBedtimeMode();
            if (configBrightnessCap != null) {
                mBrightnessCapForWearBedtimeMode = configBrightnessCap.floatValue();
            } else {
                loadBrightnessCapForWearBedtimeModeFromConfigXml();
            }
        }
    }

    private void loadBrightnessCapForWearBedtimeModeFromConfigXml() {
        mBrightnessCapForWearBedtimeMode = BrightnessSynchronizer.brightnessIntToFloat(
                mContext.getResources().getInteger(com.android.internal.R.integer
                        .config_screenBrightnessCapForWearBedtimeMode));
    }

    /**
     * Container for high brightness mode configuration data.
     */
    static class HighBrightnessModeData {
        /** Minimum lux needed to enter high brightness mode */
        public float minimumLux;

        /** Brightness level at which we transition from normal to high-brightness. */
        public float transitionPoint;

        /** Whether HBM is allowed when {@code Settings.Global.LOW_POWER_MODE} is active. */
        public boolean allowInLowPowerMode;

        /** Time window for HBM. */
        public long timeWindowMillis;

        /** Maximum time HBM is allowed to be during in a {@code timeWindowMillis}. */
        public long timeMaxMillis;

        /** Minimum time that HBM can be on before being enabled. */
        public long timeMinMillis;

        /** Minimum HDR video size to enter high brightness mode */
        public float minimumHdrPercentOfScreen;

        HighBrightnessModeData() {}

        HighBrightnessModeData(float minimumLux, float transitionPoint, long timeWindowMillis,
                long timeMaxMillis, long timeMinMillis, boolean allowInLowPowerMode,
                float minimumHdrPercentOfScreen) {
            this.minimumLux = minimumLux;
            this.transitionPoint = transitionPoint;
            this.timeWindowMillis = timeWindowMillis;
            this.timeMaxMillis = timeMaxMillis;
            this.timeMinMillis = timeMinMillis;
            this.allowInLowPowerMode = allowInLowPowerMode;
            this.minimumHdrPercentOfScreen = minimumHdrPercentOfScreen;
        }

        /**
         * Copies the HBM data to the specified parameter instance.
         * @param other the instance to copy data to.
         */
        public void copyTo(@NonNull HighBrightnessModeData other) {
            other.minimumLux = minimumLux;
            other.timeWindowMillis = timeWindowMillis;
            other.timeMaxMillis = timeMaxMillis;
            other.timeMinMillis = timeMinMillis;
            other.transitionPoint = transitionPoint;
            other.allowInLowPowerMode = allowInLowPowerMode;
            other.minimumHdrPercentOfScreen = minimumHdrPercentOfScreen;
        }

        @Override
        public String toString() {
            return "HBM{"
                    + "minLux: " + minimumLux
                    + ", transition: " + transitionPoint
                    + ", timeWindow: " + timeWindowMillis + "ms"
                    + ", timeMax: " + timeMaxMillis + "ms"
                    + ", timeMin: " + timeMinMillis + "ms"
                    + ", allowInLowPowerMode: " + allowInLowPowerMode
                    + ", minimumHdrPercentOfScreen: " + minimumHdrPercentOfScreen
                    + "} ";
        }
    }

    /**
     * Container for Power throttling configuration data.
     * TODO(b/302814899): extract to separate class.
     */
    public static class PowerThrottlingConfigData {
        /** Lowest brightness cap allowed for this device. */
        public final float brightnessLowestCapAllowed;
        /** Time window for polling power in seconds. */
        public final int pollingWindowMillis;
        public PowerThrottlingConfigData(float brightnessLowestCapAllowed,
                int pollingWindowMillis) {
            this.brightnessLowestCapAllowed = brightnessLowestCapAllowed;
            this.pollingWindowMillis = pollingWindowMillis;
        }

        @Override
        public String toString() {
            return "PowerThrottlingConfigData{"
                    + "brightnessLowestCapAllowed: "
                    + brightnessLowestCapAllowed
                    + ", pollingWindowMillis: " + pollingWindowMillis
                    + "} ";
        }
    }

    /**
     * Container for power throttling data.
     * TODO(b/302814899): extract to separate class and unify with ThermalBrightnessThrottlingData.
     */
    public static class PowerThrottlingData {
        public List<ThrottlingLevel> throttlingLevels;

        /**
         * thermal status to power quota mapping.
         */
        public static class ThrottlingLevel {
            public @PowerManager.ThermalStatus int thermalStatus;
            public float powerQuotaMilliWatts;

            public ThrottlingLevel(
                    @PowerManager.ThermalStatus int thermalStatus, float powerQuotaMilliWatts) {
                this.thermalStatus = thermalStatus;
                this.powerQuotaMilliWatts = powerQuotaMilliWatts;
            }

            @Override
            public String toString() {
                return "[" + thermalStatus + "," + powerQuotaMilliWatts + "]";
            }

            @Override
            public boolean equals(Object obj) {
                if (!(obj instanceof ThrottlingLevel)) {
                    return false;
                }
                ThrottlingLevel otherThrottlingLevel = (ThrottlingLevel) obj;

                return otherThrottlingLevel.thermalStatus == this.thermalStatus
                        && otherThrottlingLevel.powerQuotaMilliWatts == this.powerQuotaMilliWatts;
            }

            @Override
            public int hashCode() {
                int result = 1;
                result = 31 * result + thermalStatus;
                result = 31 * result + Float.hashCode(powerQuotaMilliWatts);
                return result;
            }
        }


        /**
         * Creates multiple temperature based throttling levels of power quota.
         */
        public static PowerThrottlingData create(
                List<ThrottlingLevel> throttlingLevels) {
            if (throttlingLevels == null || throttlingLevels.size() == 0) {
                Slog.e(TAG, "PowerThrottlingData received null or empty throttling levels");
                return null;
            }

            ThrottlingLevel prevLevel = throttlingLevels.get(0);
            final int numLevels = throttlingLevels.size();
            for (int i = 1; i < numLevels; i++) {
                ThrottlingLevel thisLevel = throttlingLevels.get(i);

                if (thisLevel.thermalStatus <= prevLevel.thermalStatus) {
                    Slog.e(TAG, "powerThrottlingMap must be strictly increasing, ignoring "
                            + "configuration. ThermalStatus " + thisLevel.thermalStatus + " <= "
                            + prevLevel.thermalStatus);
                    return null;
                }

                if (thisLevel.powerQuotaMilliWatts >= prevLevel.powerQuotaMilliWatts) {
                    Slog.e(TAG, "powerThrottlingMap must be strictly decreasing, ignoring "
                            + "configuration. powerQuotaMilliWatts "
                            + thisLevel.powerQuotaMilliWatts + " >= "
                            + prevLevel.powerQuotaMilliWatts);
                    return null;
                }

                prevLevel = thisLevel;
            }
            return new PowerThrottlingData(throttlingLevels);
        }

        @Override
        public String toString() {
            return "PowerThrottlingData{"
                    + "throttlingLevels:" + throttlingLevels
                    + "} ";
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }

            if (!(obj instanceof PowerThrottlingData)) {
                return false;
            }

            PowerThrottlingData otherData = (PowerThrottlingData) obj;
            return throttlingLevels.equals(otherData.throttlingLevels);
        }

        @Override
        public int hashCode() {
            return throttlingLevels.hashCode();
        }

        @VisibleForTesting
        PowerThrottlingData(List<ThrottlingLevel> inLevels) {
            throttlingLevels = new ArrayList<>(inLevels.size());
            for (ThrottlingLevel level : inLevels) {
                throttlingLevels.add(new ThrottlingLevel(level.thermalStatus,
                        level.powerQuotaMilliWatts));
            }
        }
    }

    /**
     * Container for brightness throttling data.
     */
    public static class ThermalBrightnessThrottlingData {
        public List<ThrottlingLevel> throttlingLevels;

        /**
         * thermal status to brightness cap holder
         */
        public static class ThrottlingLevel {
            public @PowerManager.ThermalStatus int thermalStatus;
            public float brightness;

            public ThrottlingLevel(
                    @PowerManager.ThermalStatus int thermalStatus, float brightness) {
                this.thermalStatus = thermalStatus;
                this.brightness = brightness;
            }

            @Override
            public String toString() {
                return "[" + thermalStatus + "," + brightness + "]";
            }

            @Override
            public boolean equals(Object obj) {
                if (!(obj instanceof ThrottlingLevel)) {
                    return false;
                }
                ThrottlingLevel otherThrottlingLevel = (ThrottlingLevel) obj;

                return otherThrottlingLevel.thermalStatus == this.thermalStatus
                        && otherThrottlingLevel.brightness == this.brightness;
            }

            @Override
            public int hashCode() {
                int result = 1;
                result = 31 * result + thermalStatus;
                result = 31 * result + Float.hashCode(brightness);
                return result;
            }
        }


        /**
         * Creates multiple temperature based throttling levels of brightness
         */
        public static ThermalBrightnessThrottlingData create(
                List<ThrottlingLevel> throttlingLevels) {
            if (throttlingLevels == null || throttlingLevels.size() == 0) {
                Slog.e(TAG, "BrightnessThrottlingData received null or empty throttling levels");
                return null;
            }

            ThrottlingLevel prevLevel = throttlingLevels.get(0);
            final int numLevels = throttlingLevels.size();
            for (int i = 1; i < numLevels; i++) {
                ThrottlingLevel thisLevel = throttlingLevels.get(i);

                if (thisLevel.thermalStatus <= prevLevel.thermalStatus) {
                    Slog.e(TAG, "brightnessThrottlingMap must be strictly increasing, ignoring "
                            + "configuration. ThermalStatus " + thisLevel.thermalStatus + " <= "
                            + prevLevel.thermalStatus);
                    return null;
                }

                if (thisLevel.brightness >= prevLevel.brightness) {
                    Slog.e(TAG, "brightnessThrottlingMap must be strictly decreasing, ignoring "
                            + "configuration. Brightness " + thisLevel.brightness + " >= "
                            + thisLevel.brightness);
                    return null;
                }

                prevLevel = thisLevel;
            }

            for (ThrottlingLevel level : throttlingLevels) {
                // Non-negative brightness values are enforced by device config schema
                if (level.brightness > PowerManager.BRIGHTNESS_MAX) {
                    Slog.e(TAG, "brightnessThrottlingMap contains a brightness value exceeding "
                            + "system max. Brightness " + level.brightness + " > "
                            + PowerManager.BRIGHTNESS_MAX);
                    return null;
                }
            }

            return new ThermalBrightnessThrottlingData(throttlingLevels);
        }

        @Override
        public String toString() {
            return "ThermalBrightnessThrottlingData{"
                    + "throttlingLevels:" + throttlingLevels
                    + "} ";
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }

            if (!(obj instanceof ThermalBrightnessThrottlingData)) {
                return false;
            }

            ThermalBrightnessThrottlingData otherData = (ThermalBrightnessThrottlingData) obj;
            return throttlingLevels.equals(otherData.throttlingLevels);
        }

        @Override
        public int hashCode() {
            return throttlingLevels.hashCode();
        }

        @VisibleForTesting
        ThermalBrightnessThrottlingData(List<ThrottlingLevel> inLevels) {
            throttlingLevels = new ArrayList<>(inLevels.size());
            for (ThrottlingLevel level : inLevels) {
                throttlingLevels.add(new ThrottlingLevel(level.thermalStatus, level.brightness));
            }
        }
    }

    public enum BrightnessLimitMapType {
        DEFAULT, ADAPTIVE;

        @Nullable
        private static BrightnessLimitMapType convert(PredefinedBrightnessLimitNames type) {
            switch (type) {
                case _default:
                    return DEFAULT;
                case adaptive:
                    return ADAPTIVE;
            }
            return null;
        }
    }
}
