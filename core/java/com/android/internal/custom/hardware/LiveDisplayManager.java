/*
 * Copyright (C) 2016 The CyanogenMod Project
 *               2018 The LineageOS Project
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
package com.android.internal.custom.hardware;

import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import android.util.Range;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.android.internal.custom.app.LineageContextConstants;

/**
 * LiveDisplay is an advanced set of features for improving
 * display quality under various ambient conditions.
 *
 * The backend service is constructed with a set of LiveDisplayFeatures
 * which provide capabilities such as outdoor mode, night mode,
 * and calibration. It interacts with LineageHardwareService to relay
 * changes down to the lower layers.
 *
 * Multiple adaptive modes are supported, and various hardware
 * features such as CABC, ACO and color enhancement are also
 * managed by LiveDisplay.
 */
public class LiveDisplayManager {

    /**
     * Disable all LiveDisplay adaptive features
     */
    public static final int MODE_OFF = 0;

    /**
     * Change color temperature to night mode
     */
    public static final int MODE_NIGHT = 1;

    /**
     * Enable automatic detection of appropriate mode
     */
    public static final int MODE_AUTO = 2;

    /**
     * Increase brightness/contrast/saturation for sunlight
     */
    public static final int MODE_OUTDOOR = 3;

    /**
     * Change color temperature to day mode, and allow
     * detection of outdoor conditions
     */
    public static final int MODE_DAY = 4;

    /** @hide */
    public static final int MODE_FIRST = MODE_OFF;
    /** @hide */
    public static final int MODE_LAST = MODE_DAY;

    /**
     * Content adaptive backlight control, adjust images to
     * increase brightness in order to reduce backlight level
     */
    public static final int FEATURE_CABC = 10;

    /**
     * Adjust images to increase contrast
     */
    public static final int FEATURE_AUTO_CONTRAST = 11;

    /**
     * Adjust image to improve saturation and color
     */
    public static final int FEATURE_COLOR_ENHANCEMENT = 12;

    /**
     * Capable of adjusting RGB levels
     */
    public static final int FEATURE_COLOR_ADJUSTMENT = 13;

    /**
     * System supports outdoor mode, but environmental sensing
     * is done by an external application.
     */
    public static final int FEATURE_MANAGED_OUTDOOR_MODE = 14;

    /**
     * System supports multiple display calibrations
     * for different viewing intents.
     */
    public static final int FEATURE_DISPLAY_MODES = 15;

    /**
     * System supports direct range-based control of display
     * color balance (temperature). This is preferred over
     * simple RGB adjustment.
     */
    public static final int FEATURE_COLOR_BALANCE = 16;

    /**
     * System supports manual hue/saturation/intensity/contrast
     * adjustment of display.
     */
    public static final int FEATURE_PICTURE_ADJUSTMENT = 17;

    /**
     * System supports grayscale matrix overlay
     */
    public static final int FEATURE_READING_ENHANCEMENT = 18;

    public static final int ADJUSTMENT_HUE = 0;
    public static final int ADJUSTMENT_SATURATION = 1;
    public static final int ADJUSTMENT_INTENSITY = 2;
    public static final int ADJUSTMENT_CONTRAST = 3;

    /** @hide */
    public static final int FEATURE_FIRST = FEATURE_CABC;
    /** @hide */
    public static final int FEATURE_LAST = FEATURE_PICTURE_ADJUSTMENT;

    private static final String TAG = "LiveDisplay";

    private final Context mContext;
    private final LiveDisplayConfig mConfig;

    private static LiveDisplayManager sInstance;
    private static ILiveDisplayService sService;

    /**
     * @hide to prevent subclassing from outside of the framework
     */
    private LiveDisplayManager(Context context) {
        Context appContext = context.getApplicationContext();
        if (appContext != null) {
            mContext = appContext;
        } else {
            mContext = context;
        }
        sService = getService();

        if (!checkService()) {
            Log.wtf(TAG, "Unable to get LiveDisplayService. The service either" +
                    " crashed, was not started, or the interface has been called to early in" +
                    " SystemServer init");
        }

        try {
            mConfig = sService.getConfig();
            if (mConfig == null) {
                Log.w(TAG, "Unable to get LiveDisplay configuration!");
            }
        } catch (RemoteException e) {
            throw new RuntimeException("Unable to fetch LiveDisplay configuration!", e);
        }
    }

    /**
     * Get or create an instance of the {@link com.android.internal.custom.hardware.LiveDisplayManager}
     * @param context
     * @return {@link LiveDisplayManager}
     */
    public synchronized static LiveDisplayManager getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new LiveDisplayManager(context);
        }
        return sInstance;
    }

    /** @hide */
    public static ILiveDisplayService getService() {
        if (sService != null) {
            return sService;
        }
        IBinder b = ServiceManager.getService(LineageContextConstants.LINEAGE_LIVEDISPLAY_SERVICE);
        if (b != null) {
            sService = ILiveDisplayService.Stub.asInterface(b);
            return sService;
        }
        return null;
    }

    /**
     * @return true if service is valid
     */
    private boolean checkService() {
        if (sService == null) {
            Log.w(TAG, "not connected to LineageHardwareManagerService");
            return false;
        }
        return true;
    }

    /**
     * Gets the static configuration and settings.
     *
     * @return the configuration
     */
    public LiveDisplayConfig getConfig() {
        return mConfig;
    }

    /**
     * Returns the current adaptive mode.
     *
     * @return id of the selected mode
     */
    public int getMode() {
        try {
            return checkService() ? sService.getMode() : MODE_OFF;
        } catch (RemoteException e) {
            return MODE_OFF;
        }
    }

    /**
     * Selects a new adaptive mode.
     *
     * @param mode
     * @return true if the mode was selected
     */
    public boolean setMode(int mode) {
        try {
            return checkService() && sService.setMode(mode);
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Checks if the auto contrast optimization feature is enabled.
     *
     * @return true if enabled
     */
    public boolean isAutoContrastEnabled() {
        try {
            return checkService() && sService.isAutoContrastEnabled();
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Sets the state of auto contrast optimization
     *
     * @param enabled
     * @return true if state was changed
     */
    public boolean setAutoContrastEnabled(boolean enabled) {
        try {
            return checkService() && sService.setAutoContrastEnabled(enabled);
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Checks if the CABC feature is enabled
     *
     * @return true if enabled
     */
    public boolean isCABCEnabled() {
        try {
            return checkService() && sService.isCABCEnabled();
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Sets the state of CABC
     *
     * @param enabled
     * @return true if state was changed
     */
    public boolean setCABCEnabled(boolean enabled) {
        try {
            return checkService() && sService.setCABCEnabled(enabled);
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Checks if the color enhancement feature is enabled
     *
     * @return true if enabled
     */
    public boolean isColorEnhancementEnabled() {
        try {
            return checkService() && sService.isColorEnhancementEnabled();
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Sets the state of color enhancement
     *
     * @param enabled
     * @return true if state was changed
     */
    public boolean setColorEnhancementEnabled(boolean enabled) {
        try {
            return checkService() && sService.setColorEnhancementEnabled(enabled);
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Gets the user-specified color temperature to use in the daytime.
     *
     * @return the day color temperature
     */
    public int getDayColorTemperature() {
        try {
            return checkService() ? sService.getDayColorTemperature() : -1;
        } catch (RemoteException e) {
            return -1;
        }
    }

    /**
     * Sets the color temperature to use in the daytime.
     *
     * @param temperature
     * @return true if state was changed
     */
    public boolean setDayColorTemperature(int temperature) {
        try {
            return checkService() && sService.setDayColorTemperature(temperature);
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Gets the user-specified color temperature to use at night.
     *
     * @return the night color temperature
     */
    public int getNightColorTemperature() {
        try {
            return checkService() ? sService.getNightColorTemperature() : -1;
        } catch (RemoteException e) {
            return -1;
        }
    }

    /**
     * Sets the color temperature to use at night.
     *
     * @param temperature
     * @return true if state was changed
     */
    public boolean setNightColorTemperature(int temperature) {
        try {
            return checkService() && sService.setNightColorTemperature(temperature);
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Checks if outdoor mode should be enabled automatically when under extremely high
     * ambient light. This is typically around 12000 lux.
     *
     * @return if outdoor conditions should be detected
     */
    public boolean isAutomaticOutdoorModeEnabled() {
        try {
            return checkService() && sService.isAutomaticOutdoorModeEnabled();
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Enables automatic detection of outdoor conditions. Outdoor mode is triggered
     * when high ambient light is detected and it's not night.
     *
     * @param enabled
     * @return true if state was changed
     */
    public boolean setAutomaticOutdoorModeEnabled(boolean enabled) {
        try {
            return checkService() && sService.setAutomaticOutdoorModeEnabled(enabled);
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Gets the current RGB triplet which is applied as a color adjustment.
     * The values are floats between 0 and 1. A setting of { 1.0, 1.0, 1.0 }
     * means that no adjustment is made.
     *
     * @return array of { R, G, B } offsets
     */
    public float[] getColorAdjustment() {
        try {
            if (checkService()) {
                return sService.getColorAdjustment();
            }
        } catch (RemoteException e) {
        }
        return new float[] { 1.0f, 1.0f, 1.0f };
    }

    /**
     * Sets the color adjustment to use. This can be set by the user to calibrate
     * their screen. This should be sent in the format { R, G, B } as floats from
     * 0 to 1. A setting of { 1.0, 1.0, 1.0 } means that no adjustment is made.
     * The hardware implementation may refuse certain values which make the display
     * unreadable, such as { 0, 0, 0 }. This calibration will be combined with other
     * internal adjustments, such as night mode, if necessary.
     *
     * @param array of { R, G, B } offsets
     * @return true if state was changed
     */
    public boolean setColorAdjustment(float[] adj) {
        try {
            return checkService() && sService.setColorAdjustment(adj);
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Gets the current picture adjustment settings (hue, saturation, intensity, contrast)
     *
     * @return HSIC object with current settings
     */
    public HSIC getPictureAdjustment() {
        try {
            if (checkService()) {
                return sService.getPictureAdjustment();
            }
        } catch (RemoteException e) {
        }
        return null;
    }

    /**
     * Sets a new picture adjustment
     *
     * @param hsic
     * @return true if success
     */
    public boolean setPictureAdjustment(final HSIC hsic) {
        try {
            return checkService() && sService.setPictureAdjustment(hsic);
        } catch (RemoteException e) {
        }
        return false;
    }

    /**
     * Gets the default picture adjustment for the current display mode
     *
     * @return HSIC object with default values
     */
    public HSIC getDefaultPictureAdjustment() {
        try {
            if (checkService()) {
                return sService.getDefaultPictureAdjustment();
            }
        } catch (RemoteException e) {
        }
        return null;
    }

    /**
     * Determine whether night mode is enabled (be it automatic or manual)
     */
    public boolean isNightModeEnabled() {
        // This method might be called before config has been set up
        // so a NPE would have been thrown, just report night mode is disabled instead
        try {
            return getMode() == MODE_NIGHT || sService.isNight();
        } catch (NullPointerException e) {
            Log.w(TAG, "Can\'t check whether night mode is enabled because the service isn\'t ready");
        } catch (RemoteException ignored) {
        }
        return false;
    }
}
