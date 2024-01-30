/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.hardware.display;

import android.Manifest;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.content.ContentResolver;
import android.content.Context;
import android.metrics.LogMaker;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceManager.ServiceNotFoundException;
import android.provider.Settings.Secure;

import com.android.internal.R;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.LocalTime;

/**
 * Manages the display's color transforms and modes.
 *
 * @hide
 */
@SystemApi
@SystemService(Context.COLOR_DISPLAY_SERVICE)
public final class ColorDisplayManager {

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({CAPABILITY_NONE, CAPABILITY_PROTECTED_CONTENT, CAPABILITY_HARDWARE_ACCELERATION_GLOBAL,
            CAPABILITY_HARDWARE_ACCELERATION_PER_APP})
    public @interface CapabilityType {}

    /**
     * The device does not support color transforms.
     *
     * @hide
     */
    @SystemApi
    public static final int CAPABILITY_NONE = 0x0;
    /**
     * The device can use GPU composition on protected content (layers whose buffers are protected
     * in the trusted memory zone).
     *
     * @hide
     */
    @SystemApi
    public static final int CAPABILITY_PROTECTED_CONTENT = 0x1;
    /**
     * The device's hardware can efficiently apply transforms to the entire display.
     *
     * @hide
     */
    @SystemApi
    public static final int CAPABILITY_HARDWARE_ACCELERATION_GLOBAL = 0x2;
    /**
     * The device's hardware can efficiently apply transforms to a specific Surface (window) so
     * that apps can be transformed independently of one another.
     *
     * @hide
     */
    @SystemApi
    public static final int CAPABILITY_HARDWARE_ACCELERATION_PER_APP = 0x4;

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({ AUTO_MODE_DISABLED, AUTO_MODE_CUSTOM_TIME, AUTO_MODE_TWILIGHT })
    public @interface AutoMode {}

    /**
     * Auto mode value to prevent Night display from being automatically activated. It can still
     * be activated manually via {@link #setNightDisplayActivated(boolean)}.
     *
     * @see #setNightDisplayAutoMode(int)
     *
     * @hide
     */
    @SystemApi
    public static final int AUTO_MODE_DISABLED = 0;
    /**
     * Auto mode value to automatically activate Night display at a specific start and end time.
     *
     * @see #setNightDisplayAutoMode(int)
     * @see #setNightDisplayCustomStartTime(LocalTime)
     * @see #setNightDisplayCustomEndTime(LocalTime)
     *
     * @hide
     */
    @SystemApi
    public static final int AUTO_MODE_CUSTOM_TIME = 1;
    /**
     * Auto mode value to automatically activate Night display from sunset to sunrise.
     *
     * @see #setNightDisplayAutoMode(int)
     *
     * @hide
     */
    @SystemApi
    public static final int AUTO_MODE_TWILIGHT = 2;

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({COLOR_MODE_NATURAL, COLOR_MODE_BOOSTED, COLOR_MODE_SATURATED, COLOR_MODE_AUTOMATIC})
    public @interface ColorMode {}

    /**
     * Color mode with natural colors.
     *
     * @hide
     * @see #setColorMode(int)
     */
    public static final int COLOR_MODE_NATURAL = 0;
    /**
     * Color mode with boosted colors.
     *
     * @hide
     * @see #setColorMode(int)
     */
    public static final int COLOR_MODE_BOOSTED = 1;
    /**
     * Color mode with saturated colors.
     *
     * @hide
     * @see #setColorMode(int)
     */
    public static final int COLOR_MODE_SATURATED = 2;
    /**
     * Color mode with automatic colors.
     *
     * @hide
     * @see #setColorMode(int)
     */
    public static final int COLOR_MODE_AUTOMATIC = 3;

    /**
     * Display color mode range reserved for vendor customizations by the RenderIntent definition in
     * hardware/interfaces/graphics/common/1.1/types.hal. These are NOT directly related to (but ARE
     * mutually exclusive with) the {@link ColorMode} constants, but ARE directly related (and ARE
     * mutually exclusive with) the DISPLAY_COLOR_* constants in DisplayTransformManager.
     *
     * @hide
     */
    public static final int VENDOR_COLOR_MODE_RANGE_MIN = 256; // 0x100
    /**
     * @hide
     */
    public static final int VENDOR_COLOR_MODE_RANGE_MAX = 511; // 0x1ff

    private final ColorDisplayManagerInternal mManager;
    private MetricsLogger mMetricsLogger;

    /**
     * @hide
     */
    public ColorDisplayManager() {
        mManager = ColorDisplayManagerInternal.getInstance();
    }

    /**
     * (De)activates the night display transform.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.CONTROL_DISPLAY_COLOR_TRANSFORMS)
    public boolean setNightDisplayActivated(boolean activated) {
        return mManager.setNightDisplayActivated(activated);
    }

    /**
     * Returns whether the night display transform is currently active.
     *
     * @hide
     */
    public boolean isNightDisplayActivated() {
        return mManager.isNightDisplayActivated();
    }

    /**
     * Sets the color temperature of the night display transform.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.CONTROL_DISPLAY_COLOR_TRANSFORMS)
    public boolean setNightDisplayColorTemperature(int temperature) {
        return mManager.setNightDisplayColorTemperature(temperature);
    }

    /**
     * Gets the color temperature of the night display transform.
     *
     * @hide
     */
    public int getNightDisplayColorTemperature() {
        return mManager.getNightDisplayColorTemperature();
    }

    /**
     * Returns the current auto mode value controlling when Night display will be automatically
     * activated. One of {@link #AUTO_MODE_DISABLED}, {@link #AUTO_MODE_CUSTOM_TIME}, or
     * {@link #AUTO_MODE_TWILIGHT}.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.CONTROL_DISPLAY_COLOR_TRANSFORMS)
    public @AutoMode int getNightDisplayAutoMode() {
        return mManager.getNightDisplayAutoMode();
    }

    /**
     * Returns the current auto mode value, without validation, or {@code 1} if the auto mode has
     * never been set.
     *
     * @hide
     */
    public int getNightDisplayAutoModeRaw() {
        return mManager.getNightDisplayAutoModeRaw();
    }

    /**
     * Sets the current auto mode value controlling when Night display will be automatically
     * activated. One of {@link #AUTO_MODE_DISABLED}, {@link #AUTO_MODE_CUSTOM_TIME}, or
     * {@link #AUTO_MODE_TWILIGHT}.
     *
     * @param autoMode the new auto mode to use
     * @return {@code true} if new auto mode was set successfully
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.CONTROL_DISPLAY_COLOR_TRANSFORMS)
    public boolean setNightDisplayAutoMode(@AutoMode int autoMode) {
        if (autoMode != AUTO_MODE_DISABLED
                && autoMode != AUTO_MODE_CUSTOM_TIME
                && autoMode != AUTO_MODE_TWILIGHT) {
            throw new IllegalArgumentException("Invalid autoMode: " + autoMode);
        }
        if (mManager.getNightDisplayAutoMode() != autoMode) {
            getMetricsLogger().write(new LogMaker(
                    MetricsEvent.ACTION_NIGHT_DISPLAY_AUTO_MODE_CHANGED)
                    .setType(MetricsEvent.TYPE_ACTION)
                    .setSubtype(autoMode));
        }
        return mManager.setNightDisplayAutoMode(autoMode);
    }

    /**
     * Returns the local time when Night display will be automatically activated when using
     * {@link ColorDisplayManager#AUTO_MODE_CUSTOM_TIME}.
     *
     * @hide
     */
    public @NonNull LocalTime getNightDisplayCustomStartTime() {
        return mManager.getNightDisplayCustomStartTime().getLocalTime();
    }

    /**
     * Sets the local time when Night display will be automatically activated when using
     * {@link ColorDisplayManager#AUTO_MODE_CUSTOM_TIME}.
     *
     * @param startTime the local time to automatically activate Night display
     * @return {@code true} if the new custom start time was set successfully
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.CONTROL_DISPLAY_COLOR_TRANSFORMS)
    public boolean setNightDisplayCustomStartTime(@NonNull LocalTime startTime) {
        if (startTime == null) {
            throw new IllegalArgumentException("startTime cannot be null");
        }
        getMetricsLogger().write(new LogMaker(
                MetricsEvent.ACTION_NIGHT_DISPLAY_AUTO_MODE_CUSTOM_TIME_CHANGED)
                .setType(MetricsEvent.TYPE_ACTION)
                .setSubtype(0));
        return mManager.setNightDisplayCustomStartTime(new Time(startTime));
    }

    /**
     * Returns the local time when Night display will be automatically deactivated when using
     * {@link #AUTO_MODE_CUSTOM_TIME}.
     *
     * @hide
     */
    public @NonNull LocalTime getNightDisplayCustomEndTime() {
        return mManager.getNightDisplayCustomEndTime().getLocalTime();
    }

    /**
     * Sets the local time when Night display will be automatically deactivated when using
     * {@link #AUTO_MODE_CUSTOM_TIME}.
     *
     * @param endTime the local time to automatically deactivate Night display
     * @return {@code true} if the new custom end time was set successfully
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.CONTROL_DISPLAY_COLOR_TRANSFORMS)
    public boolean setNightDisplayCustomEndTime(@NonNull LocalTime endTime) {
        if (endTime == null) {
            throw new IllegalArgumentException("endTime cannot be null");
        }
        getMetricsLogger().write(new LogMaker(
                MetricsEvent.ACTION_NIGHT_DISPLAY_AUTO_MODE_CUSTOM_TIME_CHANGED)
                .setType(MetricsEvent.TYPE_ACTION)
                .setSubtype(1));
        return mManager.setNightDisplayCustomEndTime(new Time(endTime));
    }

    /**
     * Sets the current display color mode.
     *
     * @hide
     */
    public void setColorMode(int colorMode) {
        mManager.setColorMode(colorMode);
    }

    /**
     * Gets the current display color mode.
     *
     * @hide
     */
    public int getColorMode() {
        return mManager.getColorMode();
    }

    /**
     * Returns whether the specified color mode is part of the standard set.
     *
     * @hide
     */
    public static boolean isStandardColorMode(int mode) {
        return mode == ColorDisplayManager.COLOR_MODE_NATURAL
                || mode == ColorDisplayManager.COLOR_MODE_BOOSTED
                || mode == ColorDisplayManager.COLOR_MODE_SATURATED
                || mode == ColorDisplayManager.COLOR_MODE_AUTOMATIC;
    }

    /**
     * Returns whether the device has a wide color gamut display.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.CONTROL_DISPLAY_COLOR_TRANSFORMS)
    public boolean isDeviceColorManaged() {
        return mManager.isDeviceColorManaged();
    }

    /**
     * Set the level of color saturation to apply to the display.
     *
     * @param saturationLevel 0-100 (inclusive), where 100 is full saturation
     * @return whether the saturation level change was applied successfully
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.CONTROL_DISPLAY_COLOR_TRANSFORMS)
    public boolean setSaturationLevel(@IntRange(from = 0, to = 100) int saturationLevel) {
        return mManager.setSaturationLevel(saturationLevel);
    }

    /**
     * Gets whether or not a non-default saturation level is currently applied to the display.
     *
     * @return {@code true} if the display is not at full saturation
     * @hide
     */
    @TestApi
    @FlaggedApi(android.app.Flags.FLAG_MODES_API)
    @RequiresPermission(Manifest.permission.CONTROL_DISPLAY_COLOR_TRANSFORMS)
    public boolean isSaturationActivated() {
        return mManager.isSaturationActivated();
    }

    /**
     * Set the level of color saturation to apply to a specific app.
     *
     * @param packageName the package name of the app whose windows should be desaturated
     * @param saturationLevel 0-100 (inclusive), where 100 is full saturation
     * @return whether the saturation level change was applied successfully
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.CONTROL_DISPLAY_COLOR_TRANSFORMS)
    public boolean setAppSaturationLevel(@NonNull String packageName,
            @IntRange(from = 0, to = 100) int saturationLevel) {
        return mManager.setAppSaturationLevel(packageName, saturationLevel);
    }

    /**
     * Enables or disables display white balance.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.CONTROL_DISPLAY_COLOR_TRANSFORMS)
    public boolean setDisplayWhiteBalanceEnabled(boolean enabled) {
        return mManager.setDisplayWhiteBalanceEnabled(enabled);
    }

    /**
     * Returns whether display white balance is currently enabled. Even if enabled, it may or may
     * not be active, if another transform with higher priority is active.
     *
     * @hide
     */
    public boolean isDisplayWhiteBalanceEnabled() {
        return mManager.isDisplayWhiteBalanceEnabled();
    }

    /**
     * Enables or disables reduce bright colors.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.CONTROL_DISPLAY_COLOR_TRANSFORMS)
    public boolean setReduceBrightColorsActivated(boolean activated) {
        return mManager.setReduceBrightColorsActivated(activated);
    }

    /**
     * Returns whether reduce bright colors is currently enabled.
     *
     * @hide
     */
    public boolean isReduceBrightColorsActivated() {
        return mManager.isReduceBrightColorsActivated();
    }

    /**
     * Set the strength level of bright color reduction to apply to the display.
     *
     * @param strength 0-100 (inclusive), where 100 is full strength
     * @return whether the change was applied successfully
     * @hide
     */
    @RequiresPermission(Manifest.permission.CONTROL_DISPLAY_COLOR_TRANSFORMS)
    public boolean setReduceBrightColorsStrength(@IntRange(from = 0, to = 100) int strength) {
        return mManager.setReduceBrightColorsStrength(strength);
    }

    /**
     * Gets the strength of the bright color reduction transform.
     *
     * @hide
     */
    public int getReduceBrightColorsStrength() {
        return mManager.getReduceBrightColorsStrength();
    }

    /**
     * Gets the brightness impact of the bright color reduction transform, as in the factor by which
     * the current brightness (in nits) should be multiplied to obtain the brightness offset 'b'.
     *
     * @hide
     */
    public float getReduceBrightColorsOffsetFactor() {
        return mManager.getReduceBrightColorsOffsetFactor();
    }

    /**
     * Returns {@code true} if Night Display is supported by the device.
     *
     * @hide
     */
    public static boolean isNightDisplayAvailable(Context context) {
        return context.getResources().getBoolean(R.bool.config_nightDisplayAvailable);
    }

    /**
     * Returns the minimum allowed color temperature (in Kelvin) to tint the display when
     * activated.
     *
     * @hide
     */
    public static int getMinimumColorTemperature(Context context) {
        return context.getResources()
                .getInteger(R.integer.config_nightDisplayColorTemperatureMin);
    }

    /**
     * Returns the maximum allowed color temperature (in Kelvin) to tint the display when
     * activated.
     *
     * @hide
     */
    public static int getMaximumColorTemperature(Context context) {
        return context.getResources()
                .getInteger(R.integer.config_nightDisplayColorTemperatureMax);
    }

    /**
     * Returns {@code true} if display white balance is supported by the device.
     *
     * @hide
     */
    public static boolean isDisplayWhiteBalanceAvailable(Context context) {
        return context.getResources().getBoolean(R.bool.config_displayWhiteBalanceAvailable);
    }

    /**
     * Returns {@code true} if reduce bright colors is supported by the device.
     *
     * @hide
     */
    public static boolean isReduceBrightColorsAvailable(Context context) {
        return context.getResources().getBoolean(R.bool.config_reduceBrightColorsAvailable);
    }

    /**
     * Returns the minimum allowed brightness reduction strength in percentage when activated.
     *
     * @hide
     */
    public static int getMinimumReduceBrightColorsStrength(Context context) {
        return context.getResources()
                .getInteger(R.integer.config_reduceBrightColorsStrengthMin);
    }

    /**
     * Returns the maximum allowed brightness reduction strength in percentage when activated.
     *
     * @hide
     */
    public static int getMaximumReduceBrightColorsStrength(Context context) {
        return context.getResources()
                .getInteger(R.integer.config_reduceBrightColorsStrengthMax);
    }

    /**
     * Check if the color transforms are color accelerated. Some transforms are experimental only
     * on non-accelerated platforms due to the performance implications.
     *
     * @hide
     */
    public static boolean isColorTransformAccelerated(Context context) {
        return context.getResources().getBoolean(R.bool.config_setColorTransformAccelerated);
    }

    /**
     * Returns the available software and hardware color transform capabilities of this device.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.CONTROL_DISPLAY_COLOR_TRANSFORMS)
    public @CapabilityType int getTransformCapabilities() {
        return mManager.getTransformCapabilities();
    }

    /**
     * Returns whether accessibility transforms are currently enabled, which determines whether
     * color modes are currently configurable for this device.
     *
     * @hide
     */
    public static boolean areAccessibilityTransformsEnabled(Context context) {
        final ContentResolver cr = context.getContentResolver();
        return Secure.getInt(cr, Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED, 0) == 1
                || Secure.getInt(cr, Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED, 0) == 1;
    }

    private MetricsLogger getMetricsLogger() {
        if (mMetricsLogger == null) {
            mMetricsLogger = new MetricsLogger();
        }
        return mMetricsLogger;
    }

    private static class ColorDisplayManagerInternal {

        private static ColorDisplayManagerInternal sInstance;

        private final IColorDisplayManager mCdm;

        private ColorDisplayManagerInternal(IColorDisplayManager colorDisplayManager) {
            mCdm = colorDisplayManager;
        }

        public static ColorDisplayManagerInternal getInstance() {
            synchronized (ColorDisplayManagerInternal.class) {
                if (sInstance == null) {
                    try {
                        IBinder b = ServiceManager.getServiceOrThrow(Context.COLOR_DISPLAY_SERVICE);
                        sInstance = new ColorDisplayManagerInternal(
                                IColorDisplayManager.Stub.asInterface(b));
                    } catch (ServiceNotFoundException e) {
                        throw new IllegalStateException(e);
                    }
                }
                return sInstance;
            }
        }

        boolean isNightDisplayActivated() {
            try {
                return mCdm.isNightDisplayActivated();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        boolean setNightDisplayActivated(boolean activated) {
            try {
                return mCdm.setNightDisplayActivated(activated);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        int getNightDisplayColorTemperature() {
            try {
                return mCdm.getNightDisplayColorTemperature();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        boolean setNightDisplayColorTemperature(int temperature) {
            try {
                return mCdm.setNightDisplayColorTemperature(temperature);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        int getNightDisplayAutoMode() {
            try {
                return mCdm.getNightDisplayAutoMode();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        int getNightDisplayAutoModeRaw() {
            try {
                return mCdm.getNightDisplayAutoModeRaw();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        boolean setNightDisplayAutoMode(int autoMode) {
            try {
                return mCdm.setNightDisplayAutoMode(autoMode);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        Time getNightDisplayCustomStartTime() {
            try {
                return mCdm.getNightDisplayCustomStartTime();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        boolean setNightDisplayCustomStartTime(Time startTime) {
            try {
                return mCdm.setNightDisplayCustomStartTime(startTime);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        Time getNightDisplayCustomEndTime() {
            try {
                return mCdm.getNightDisplayCustomEndTime();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        boolean setNightDisplayCustomEndTime(Time endTime) {
            try {
                return mCdm.setNightDisplayCustomEndTime(endTime);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        boolean isDeviceColorManaged() {
            try {
                return mCdm.isDeviceColorManaged();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        boolean setSaturationLevel(int saturationLevel) {
            try {
                return mCdm.setSaturationLevel(saturationLevel);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        boolean isSaturationActivated() {
            try {
                return mCdm.isSaturationActivated();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        boolean setAppSaturationLevel(String packageName, int saturationLevel) {
            try {
                return mCdm.setAppSaturationLevel(packageName, saturationLevel);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        boolean isDisplayWhiteBalanceEnabled() {
            try {
                return mCdm.isDisplayWhiteBalanceEnabled();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        boolean setDisplayWhiteBalanceEnabled(boolean enabled) {
            try {
                return mCdm.setDisplayWhiteBalanceEnabled(enabled);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        boolean isReduceBrightColorsActivated() {
            try {
                return mCdm.isReduceBrightColorsActivated();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        boolean setReduceBrightColorsActivated(boolean activated) {
            try {
                return mCdm.setReduceBrightColorsActivated(activated);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        int getReduceBrightColorsStrength() {
            try {
                return mCdm.getReduceBrightColorsStrength();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        boolean setReduceBrightColorsStrength(int strength) {
            try {
                return mCdm.setReduceBrightColorsStrength(strength);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        float getReduceBrightColorsOffsetFactor() {
            try {
                return mCdm.getReduceBrightColorsOffsetFactor();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        int getColorMode() {
            try {
                return mCdm.getColorMode();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        void setColorMode(int colorMode) {
            try {
                mCdm.setColorMode(colorMode);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        int getTransformCapabilities() {
            try {
                return mCdm.getTransformCapabilities();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }
}
