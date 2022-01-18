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

import android.annotation.NonNull;
import android.content.Context;
import android.content.res.Resources;
import android.hardware.display.DisplayManagerInternal;
import android.hardware.display.DisplayManagerInternal.RefreshRateLimitation;
import android.os.Environment;
import android.os.PowerManager;
import android.text.TextUtils;
import android.util.MathUtils;
import android.util.Slog;
import android.util.Spline;
import android.view.DisplayAddress;

import com.android.internal.R;
import com.android.internal.display.BrightnessSynchronizer;
import com.android.server.display.config.BrightnessThresholds;
import com.android.server.display.config.DisplayConfiguration;
import com.android.server.display.config.DisplayQuirks;
import com.android.server.display.config.HbmTiming;
import com.android.server.display.config.HighBrightnessMode;
import com.android.server.display.config.NitsMap;
import com.android.server.display.config.Point;
import com.android.server.display.config.RefreshRateRange;
import com.android.server.display.config.SensorDetails;
import com.android.server.display.config.ThermalStatus;
import com.android.server.display.config.Thresholds;
import com.android.server.display.config.XmlParser;

import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.xml.datatype.DatatypeConfigurationException;

/**
 * Reads and stores display-specific configurations.
 */
public class DisplayDeviceConfig {
    private static final String TAG = "DisplayDeviceConfig";

    public static final float HIGH_BRIGHTNESS_MODE_UNSUPPORTED = Float.NaN;

    public static final String QUIRK_CAN_SET_BRIGHTNESS_VIA_HWC = "canSetBrightnessViaHwc";

    private static final float BRIGHTNESS_DEFAULT = 0.5f;
    private static final String ETC_DIR = "etc";
    private static final String DISPLAY_CONFIG_DIR = "displayconfig";
    private static final String CONFIG_FILE_FORMAT = "display_%s.xml";
    private static final String PORT_SUFFIX_FORMAT = "port_%d";
    private static final String STABLE_ID_SUFFIX_FORMAT = "id_%d";
    private static final String NO_SUFFIX_FORMAT = "%d";
    private static final long STABLE_FLAG = 1L << 62;

    // Float.NaN (used as invalid for brightness) cannot be stored in config.xml
    // so -2 is used instead
    private static final float INVALID_BRIGHTNESS_IN_CONFIG = -2f;

    private static final float NITS_INVALID = -1;

    private final Context mContext;

    // The details of the ambient light sensor associated with this display.
    private final SensorData mAmbientLightSensor = new SensorData();

    // The details of the proximity sensor associated with this display.
    private final SensorData mProximitySensor = new SensorData();

    private final List<RefreshRateLimitation> mRefreshRateLimitations =
            new ArrayList<>(2 /*initialCapacity*/);

    // Nits and backlight values that are loaded from either the display device config file, or
    // config.xml. These are the raw values and just used for the dumpsys
    private float[] mRawNits;
    private float[] mRawBacklight;

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

    private float mBacklightMinimum = Float.NaN;
    private float mBacklightMaximum = Float.NaN;
    private float mBrightnessDefault = Float.NaN;
    private float mBrightnessRampFastDecrease = Float.NaN;
    private float mBrightnessRampFastIncrease = Float.NaN;
    private float mBrightnessRampSlowDecrease = Float.NaN;
    private float mBrightnessRampSlowIncrease = Float.NaN;
    private float mScreenBrighteningMinThreshold = 0.0f;     // Retain behaviour as though there is
    private float mScreenDarkeningMinThreshold = 0.0f;       // no minimum threshold for change in
    private float mAmbientLuxBrighteningMinThreshold = 0.0f; // screen brightness or ambient
    private float mAmbientLuxDarkeningMinThreshold = 0.0f;   // brightness.
    private Spline mBrightnessToBacklightSpline;
    private Spline mBacklightToBrightnessSpline;
    private Spline mBacklightToNitsSpline;
    private List<String> mQuirks;
    private boolean mIsHighBrightnessModeEnabled = false;
    private HighBrightnessModeData mHbmData;
    private String mLoadedFrom = null;

    private DisplayDeviceConfig(Context context) {
        mContext = context;
    }

    /**
     * Creates an instance for the specified display.
     * Tries to find a file with identifier in the following priority order:
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
            boolean isDefaultDisplay) {
        DisplayDeviceConfig config;

        config = loadConfigFromDirectory(context, Environment.getProductDirectory(),
                physicalDisplayId);
        if (config != null) {
            return config;
        }

        config = loadConfigFromDirectory(context, Environment.getVendorDirectory(),
                physicalDisplayId);
        if (config != null) {
            return config;
        }

        // If no config can be loaded from any ddc xml at all,
        // prepare a whole config using the global config.xml.
        // Guaranteed not null
        return create(context, isDefaultDisplay);
    }

    /**
     * Creates an instance using global values since no display device config xml exists.
     * Uses values from config or PowerManager.
     *
     * @param context
     * @param useConfigXml
     * @return A configuration instance.
     */
    public static DisplayDeviceConfig create(Context context, boolean useConfigXml) {
        DisplayDeviceConfig config;
        if (useConfigXml) {
            config = getConfigFromGlobalXml(context);
        } else {
            config = getConfigFromPmValues(context);
        }
        return config;
    }

    private static DisplayDeviceConfig loadConfigFromDirectory(Context context,
            File baseDirectory, long physicalDisplayId) {
        DisplayDeviceConfig config;
        // Create config using filename from physical ID (including "stable" bit).
        config = getConfigFromSuffix(context, baseDirectory, STABLE_ID_SUFFIX_FORMAT,
                physicalDisplayId);
        if (config != null) {
            return config;
        }

        // Create config using filename from physical ID (excluding "stable" bit).
        final long withoutStableFlag = physicalDisplayId & ~STABLE_FLAG;
        config = getConfigFromSuffix(context, baseDirectory, NO_SUFFIX_FORMAT, withoutStableFlag);
        if (config != null) {
            return config;
        }

        // Create config using filename from port ID.
        final DisplayAddress.Physical physicalAddress =
                DisplayAddress.fromPhysicalDisplayId(physicalDisplayId);
        int port = physicalAddress.getPort();
        config = getConfigFromSuffix(context, baseDirectory, PORT_SUFFIX_FORMAT, port);
        return config;
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
     * Calculates the backlight value, as recognised by the HAL, from the brightness value
     * given that the rest of the system deals with.
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
     * @return The mapped nits or 0 if no mapping exits.
     */
    public float getNitsFromBacklight(float backlight) {
        if (mBacklightToNitsSpline == null) {
            Slog.wtf(TAG, "requesting nits when no mapping exists.");
            return NITS_INVALID;
        }
        backlight = Math.max(backlight, mBacklightMinimum);
        return mBacklightToNitsSpline.interpolate(backlight);
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

    public float getScreenBrighteningMinThreshold() {
        return mScreenBrighteningMinThreshold;
    }

    public float getScreenDarkeningMinThreshold() {
        return mScreenDarkeningMinThreshold;
    }

    public float getAmbientLuxBrighteningMinThreshold() {
        return mAmbientLuxBrighteningMinThreshold;
    }

    public float getAmbientLuxDarkeningMinThreshold() {
        return mAmbientLuxDarkeningMinThreshold;
    }

    SensorData getAmbientLightSensor() {
        return mAmbientLightSensor;
    }

    SensorData getProximitySensor() {
        return mProximitySensor;
    }

    /**
     * @param quirkValue The quirk to test.
     * @return {@code true} if the specified quirk is present in this configuration,
     * {@code false} otherwise.
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

    public List<RefreshRateLimitation> getRefreshRateLimitations() {
        return mRefreshRateLimitations;
    }

    @Override
    public String toString() {
        String str = "DisplayDeviceConfig{"
                + "mLoadedFrom=" + mLoadedFrom
                + ", mBacklight=" + Arrays.toString(mBacklight)
                + ", mNits=" + Arrays.toString(mNits)
                + ", mRawBacklight=" + Arrays.toString(mRawBacklight)
                + ", mRawNits=" + Arrays.toString(mRawNits)
                + ", mBrightness=" + Arrays.toString(mBrightness)
                + ", mBrightnessToBacklightSpline=" + mBrightnessToBacklightSpline
                + ", mBacklightToBrightnessSpline=" + mBacklightToBrightnessSpline
                + ", mBacklightMinimum=" + mBacklightMinimum
                + ", mBacklightMaximum=" + mBacklightMaximum
                + ", mBrightnessDefault=" + mBrightnessDefault
                + ", mQuirks=" + mQuirks
                + ", isHbmEnabled=" + mIsHighBrightnessModeEnabled
                + ", mHbmData=" + mHbmData
                + ", mBrightnessRampFastDecrease=" + mBrightnessRampFastDecrease
                + ", mBrightnessRampFastIncrease=" + mBrightnessRampFastIncrease
                + ", mBrightnessRampSlowDecrease=" + mBrightnessRampSlowDecrease
                + ", mBrightnessRampSlowIncrease=" + mBrightnessRampSlowIncrease
                + ", mScreenDarkeningMinThreshold=" + mScreenDarkeningMinThreshold
                + ", mScreenBrighteningMinThreshold=" + mScreenBrighteningMinThreshold
                + ", mAmbientLuxDarkeningMinThreshold=" + mAmbientLuxDarkeningMinThreshold
                + ", mAmbientLuxBrighteningMinThreshold=" + mAmbientLuxBrighteningMinThreshold
                + ", mAmbientLightSensor=" + mAmbientLightSensor
                + ", mProximitySensor=" + mProximitySensor
                + ", mRefreshRateLimitations= " + Arrays.toString(mRefreshRateLimitations.toArray())
                + "}";
        return str;
    }

    private static DisplayDeviceConfig getConfigFromSuffix(Context context, File baseDirectory,
            String suffixFormat, long idNumber) {

        final String suffix = String.format(suffixFormat, idNumber);
        final String filename = String.format(CONFIG_FILE_FORMAT, suffix);
        final File filePath = Environment.buildPath(
                baseDirectory, ETC_DIR, DISPLAY_CONFIG_DIR, filename);
        final DisplayDeviceConfig config = new DisplayDeviceConfig(context);
        if (config.initFromFile(filePath)) {
            return config;
        }
        return null;
    }

    private static DisplayDeviceConfig getConfigFromGlobalXml(Context context) {
        DisplayDeviceConfig config = new DisplayDeviceConfig(context);
        config.initFromGlobalXml();
        return config;
    }

    private static DisplayDeviceConfig getConfigFromPmValues(Context context) {
        DisplayDeviceConfig config = new DisplayDeviceConfig(context);
        config.initFromDefaultValues();
        return config;
    }

    private boolean initFromFile(File configFile) {
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
                loadBrightnessDefaultFromDdcXml(config);
                loadBrightnessConstraintsFromConfigXml();
                loadBrightnessMap(config);
                loadHighBrightnessModeData(config);
                loadQuirks(config);
                loadBrightnessRamps(config);
                loadAmbientLightSensorFromDdc(config);
                loadProxSensorFromDdc(config);
                loadBrightnessChangeThresholds(config);
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
        loadAmbientLightSensorFromConfigXml();
        setProxSensorUnspecified();
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
        setSimpleMappingStrategyValues();
        loadAmbientLightSensorFromConfigXml();
        setProxSensorUnspecified();
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
        mBrightnessToBacklightSpline = Spline.createSpline(mBrightness, mBacklight);
        mBacklightToBrightnessSpline = Spline.createSpline(mBacklight, mBrightness);
        mBacklightToNitsSpline = Spline.createSpline(mBacklight, mNits);
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
            mHbmData.thermalStatusLimit = convertThermalStatus(hbm.getThermalStatusLimit_all());
            mHbmData.allowInLowPowerMode = hbm.getAllowInLowPowerMode_all();
            final RefreshRateRange rr = hbm.getRefreshRate_all();
            if (rr != null) {
                final float min = rr.getMinimum().floatValue();
                final float max = rr.getMaximum().floatValue();
                mRefreshRateLimitations.add(new RefreshRateLimitation(
                        DisplayManagerInternal.REFRESH_RATE_LIMIT_HIGH_BRIGHTNESS_MODE, min, max));
            }
        }
    }

    private void loadBrightnessRamps(DisplayConfiguration config) {
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

    private void loadAmbientLightSensorFromConfigXml() {
        mAmbientLightSensor.name = "";
        mAmbientLightSensor.type = mContext.getResources().getString(
                com.android.internal.R.string.config_displayLightSensorType);
    }

    private void loadAmbientLightSensorFromDdc(DisplayConfiguration config) {
        final SensorDetails sensorDetails = config.getLightSensor();
        if (sensorDetails != null) {
            mAmbientLightSensor.type = sensorDetails.getType();
            mAmbientLightSensor.name = sensorDetails.getName();
            final RefreshRateRange rr = sensorDetails.getRefreshRate();
            if (rr != null) {
                mAmbientLightSensor.minRefreshRate = rr.getMinimum().floatValue();
                mAmbientLightSensor.maxRefreshRate = rr.getMaximum().floatValue();
            }
        } else {
            loadAmbientLightSensorFromConfigXml();
        }
    }

    private void setProxSensorUnspecified() {
        mProximitySensor.name = "";
        mProximitySensor.type = "";
    }

    private void loadProxSensorFromDdc(DisplayConfiguration config) {
        SensorDetails sensorDetails = config.getProxSensor();
        if (sensorDetails != null) {
            mProximitySensor.name = sensorDetails.getName();
            mProximitySensor.type = sensorDetails.getType();
            final RefreshRateRange rr = sensorDetails.getRefreshRate();
            if (rr != null) {
                mProximitySensor.minRefreshRate = rr.getMinimum().floatValue();
                mProximitySensor.maxRefreshRate = rr.getMaximum().floatValue();
            }
        } else {
            setProxSensorUnspecified();
        }
    }

    private void loadBrightnessChangeThresholds(DisplayConfiguration config) {
        Thresholds displayBrightnessThresholds = config.getDisplayBrightnessChangeThresholds();
        Thresholds ambientBrightnessThresholds = config.getAmbientBrightnessChangeThresholds();

        if (displayBrightnessThresholds != null) {
            BrightnessThresholds brighteningScreen =
                    displayBrightnessThresholds.getBrighteningThresholds();
            BrightnessThresholds darkeningScreen =
                    displayBrightnessThresholds.getDarkeningThresholds();

            final BigDecimal screenBrighteningThreshold = brighteningScreen.getMinimum();
            final BigDecimal screenDarkeningThreshold = darkeningScreen.getMinimum();

            if (screenBrighteningThreshold != null) {
                mScreenBrighteningMinThreshold = screenBrighteningThreshold.floatValue();
            }
            if (screenDarkeningThreshold != null) {
                mScreenDarkeningMinThreshold = screenDarkeningThreshold.floatValue();
            }
        }

        if (ambientBrightnessThresholds != null) {
            BrightnessThresholds brighteningAmbientLux =
                    ambientBrightnessThresholds.getBrighteningThresholds();
            BrightnessThresholds darkeningAmbientLux =
                    ambientBrightnessThresholds.getDarkeningThresholds();

            final BigDecimal ambientBrighteningThreshold = brighteningAmbientLux.getMinimum();
            final BigDecimal ambientDarkeningThreshold =  darkeningAmbientLux.getMinimum();

            if (ambientBrighteningThreshold != null) {
                mAmbientLuxBrighteningMinThreshold = ambientBrighteningThreshold.floatValue();
            }
            if (ambientDarkeningThreshold != null) {
                mAmbientLuxDarkeningMinThreshold = ambientDarkeningThreshold.floatValue();
            }
        }
    }

    private @PowerManager.ThermalStatus int convertThermalStatus(ThermalStatus value) {
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

    static class SensorData {
        public String type;
        public String name;
        public float minRefreshRate = 0.0f;
        public float maxRefreshRate = Float.POSITIVE_INFINITY;

        @Override
        public String toString() {
            return "Sensor{"
                    + "type: " + type
                    + ", name: " + name
                    + ", refreshRateRange: [" + minRefreshRate + ", " + maxRefreshRate + "]"
                    + "} ";
        }

        /**
         * @return True if the sensor matches both the specified name and type, or one if only
         * one is specified (not-empty). Always returns false if both parameters are null or empty.
         */
        public boolean matches(String sensorName, String sensorType) {
            final boolean isNameSpecified = !TextUtils.isEmpty(sensorName);
            final boolean isTypeSpecified = !TextUtils.isEmpty(sensorType);
            return (isNameSpecified || isTypeSpecified)
                    && (!isNameSpecified || sensorName.equals(name))
                    && (!isTypeSpecified || sensorType.equals(type));
        }
    }

    /**
     * Container for high brightness mode configuration data.
     */
    static class HighBrightnessModeData {
        /** Minimum lux needed to enter high brightness mode */
        public float minimumLux;

        /** Brightness level at which we transition from normal to high-brightness. */
        public float transitionPoint;

        /** Enable HBM only if the thermal status is not higher than this. */
        public @PowerManager.ThermalStatus int thermalStatusLimit;

        /** Whether HBM is allowed when {@code Settings.Global.LOW_POWER_MODE} is active. */
        public boolean allowInLowPowerMode;

        /** Time window for HBM. */
        public long timeWindowMillis;

        /** Maximum time HBM is allowed to be during in a {@code timeWindowMillis}. */
        public long timeMaxMillis;

        /** Minimum time that HBM can be on before being enabled. */
        public long timeMinMillis;

        HighBrightnessModeData() {}

        HighBrightnessModeData(float minimumLux, float transitionPoint, long timeWindowMillis,
                long timeMaxMillis, long timeMinMillis,
                @PowerManager.ThermalStatus int thermalStatusLimit, boolean allowInLowPowerMode) {
            this.minimumLux = minimumLux;
            this.transitionPoint = transitionPoint;
            this.timeWindowMillis = timeWindowMillis;
            this.timeMaxMillis = timeMaxMillis;
            this.timeMinMillis = timeMinMillis;
            this.thermalStatusLimit = thermalStatusLimit;
            this.allowInLowPowerMode = allowInLowPowerMode;
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
            other.thermalStatusLimit = thermalStatusLimit;
            other.allowInLowPowerMode = allowInLowPowerMode;
        }

        @Override
        public String toString() {
            return "HBM{"
                    + "minLux: " + minimumLux
                    + ", transition: " + transitionPoint
                    + ", timeWindow: " + timeWindowMillis + "ms"
                    + ", timeMax: " + timeMaxMillis + "ms"
                    + ", timeMin: " + timeMinMillis + "ms"
                    + ", thermalStatusLimit: " + thermalStatusLimit
                    + ", allowInLowPowerMode: " + allowInLowPowerMode
                    + "} ";
        }
    }
}
