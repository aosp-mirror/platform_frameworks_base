/*
 * Copyright (C) 2016 The CyanogenMod Project
 *               2019 The LineageOS Project
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
package com.android.server.custom.display;

import static com.android.internal.custom.hardware.LiveDisplayManager.MODE_AUTO;
import static com.android.internal.custom.hardware.LiveDisplayManager.MODE_DAY;
import static com.android.internal.custom.hardware.LiveDisplayManager.MODE_OUTDOOR;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;

import java.io.PrintWriter;
import java.util.BitSet;

import com.android.internal.custom.hardware.LineageHardwareManager;
import com.android.internal.custom.hardware.LiveDisplayManager;
import android.provider.Settings;

public class OutdoorModeController extends LiveDisplayFeature {

    private final LineageHardwareManager mHardware;
    private AmbientLuxObserver mLuxObserver;

    // hardware capabilities
    private final boolean mUseOutdoorMode;

    // default values
    private final int mDefaultOutdoorLux;
    private final int mOutdoorLuxHysteresis;
    private final boolean mDefaultAutoOutdoorMode;
    private final boolean mSelfManaged;

    // internal state
    private boolean mIsOutdoor;
    private boolean mIsSensorEnabled;

    // sliding window for sensor event smoothing
    private static final int SENSOR_WINDOW_MS = 3000;

    public OutdoorModeController(Context context, Handler handler) {
        super(context, handler);

        mHardware = LineageHardwareManager.getInstance(mContext);
        mUseOutdoorMode = mHardware.isSupported(LineageHardwareManager.FEATURE_SUNLIGHT_ENHANCEMENT);
        mSelfManaged = mUseOutdoorMode && mHardware.isSunlightEnhancementSelfManaged();

        mDefaultOutdoorLux = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_outdoorAmbientLux);
        mOutdoorLuxHysteresis = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_outdoorAmbientLuxHysteresis);
        mDefaultAutoOutdoorMode = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_defaultAutoOutdoorMode);
    }

    @Override
    public void onStart() {
        if (!mUseOutdoorMode) {
            return;
        }

        if (!mSelfManaged) {
            mLuxObserver = new AmbientLuxObserver(mContext, mHandler.getLooper(),
                    mDefaultOutdoorLux, mOutdoorLuxHysteresis, SENSOR_WINDOW_MS);
        }

        registerSettings(
                Settings.System.getUriFor(Settings.System.DISPLAY_AUTO_OUTDOOR_MODE));
    }

    @Override
    public boolean getCapabilities(final BitSet caps) {
        if (mUseOutdoorMode) {
            caps.set(LiveDisplayManager.MODE_AUTO);
            caps.set(LiveDisplayManager.MODE_OUTDOOR);
            if (mSelfManaged) {
                caps.set(LiveDisplayManager.FEATURE_MANAGED_OUTDOOR_MODE);
            }
        }
        return mUseOutdoorMode;
    }

    @Override
    protected void onUpdate() {
        updateOutdoorMode();
    }

    @Override
    protected void onTwilightUpdated() {
        updateOutdoorMode();
    }

    @Override
    protected synchronized void onScreenStateChanged() {
        if (!mUseOutdoorMode) {
            return;
        }

        // toggle the sensor when screen on/off
        updateSensorState();

        // Disable outdoor mode on screen off so that we don't melt the users
        // face if they turn it back on in normal conditions
        if (!isScreenOn() && !mSelfManaged && getMode() != MODE_OUTDOOR) {
            mIsOutdoor = false;
            mHardware.set(LineageHardwareManager.FEATURE_SUNLIGHT_ENHANCEMENT, false);
        }
    }

    @Override
    public synchronized void onSettingsChanged(Uri uri) {
        updateOutdoorMode();
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.println();
        pw.println("OutdoorModeController Configuration:");
        pw.println("  mSelfManaged=" + mSelfManaged);
        if (!mSelfManaged) {
            pw.println("  mDefaultOutdoorLux=" + mDefaultOutdoorLux);
            pw.println("  mOutdoorLuxHysteresis=" + mOutdoorLuxHysteresis);
            pw.println();
            pw.println("  OutdoorModeController State:");
            pw.println("    mAutoOutdoorMode=" + isAutomaticOutdoorModeEnabled());
            pw.println("    mIsOutdoor=" + mIsOutdoor);
            pw.println("    mIsNight=" + isNight());
            pw.println("    hardware state=" +
                    mHardware.get(LineageHardwareManager.FEATURE_SUNLIGHT_ENHANCEMENT));
        }
        mLuxObserver.dump(pw);
    }

    private synchronized void updateSensorState() {
        if (!mUseOutdoorMode || mLuxObserver == null || mSelfManaged) {
            return;
        }

        /*
         * Light sensor:
         */
        boolean sensorEnabled = false;
        // no sensor if low power mode or when the screen is off
        if (isScreenOn() && !isLowPowerMode()) {
            if (isAutomaticOutdoorModeEnabled()) {
                int mode = getMode();
                if (mode == MODE_DAY) {
                    // always turn it on if day mode is selected
                    sensorEnabled = true;
                } else if (mode == MODE_AUTO && !isNight()) {
                    // in auto mode we turn it on during actual daytime
                    sensorEnabled = true;
                }
            }
        }
        if (mIsSensorEnabled != sensorEnabled) {
            mIsSensorEnabled = sensorEnabled;
            mLuxObserver.setTransitionListener(sensorEnabled ? mListener : null);
        }
    }

    /**
     * Outdoor mode is optionally enabled when ambient lux > 10000 and it's daytime
     * Melt faces!
     *
     * TODO: Use the camera or RGB sensor to determine if it's really sunlight
     */
    private synchronized void updateOutdoorMode() {
        if (!mUseOutdoorMode) {
            return;
        }

        updateSensorState();

        /*
         * Should we turn on outdoor mode or not?
         *
         * Do nothing if the screen is off.
         */
        if (isScreenOn()) {
            boolean enabled = false;
            // turn it off in low power mode
            if (!isLowPowerMode()) {
                int mode = getMode();
                // turn it on if the user manually selected the mode
                if (mode == MODE_OUTDOOR) {
                    enabled = true;
                } else if (isAutomaticOutdoorModeEnabled()) {
                    // self-managed mode means we just flip a switch and an external
                    // implementation does all the sensing. this allows the user
                    // to turn on/off the feature.
                    if (mSelfManaged) {
                        enabled = true;
                    } else if (mIsOutdoor) {
                        // if we're here, the sensor detects extremely bright light.
                        if (mode == MODE_DAY) {
                            // if the user manually selected day mode, go ahead and
                            // melt their face
                            enabled = true;
                        } else if (mode == MODE_AUTO && !isNight()) {
                            // if we're in auto mode, we should also check if it's
                            // night time, since we don't get much sun at night
                            // on this planet :)
                            enabled = true;
                        }
                    }
                }
            }
            mHardware.set(LineageHardwareManager.FEATURE_SUNLIGHT_ENHANCEMENT, enabled);
        }
    }

    private final AmbientLuxObserver.TransitionListener mListener =
            new AmbientLuxObserver.TransitionListener() {
        @Override
        public void onTransition(final int state, float ambientLux) {
            final boolean outdoor = state == 1;
            synchronized (OutdoorModeController.this) {
                if (mIsOutdoor == outdoor) {
                    return;
                }

                mIsOutdoor = outdoor;
                updateOutdoorMode();
            }
        }
    };

    boolean setAutomaticOutdoorModeEnabled(boolean enabled) {
        if (!mUseOutdoorMode) {
            return false;
        }
        putBoolean(Settings.System.DISPLAY_AUTO_OUTDOOR_MODE, enabled);
        return true;
    }

    boolean isAutomaticOutdoorModeEnabled() {
        return mUseOutdoorMode &&
                getBoolean(Settings.System.DISPLAY_AUTO_OUTDOOR_MODE,
                           getDefaultAutoOutdoorMode());
    }

    boolean getDefaultAutoOutdoorMode() {
        return mDefaultAutoOutdoorMode;
    }
}
