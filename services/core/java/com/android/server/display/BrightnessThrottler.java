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

import android.annotation.NonNull;
import android.content.Context;
import android.hardware.display.BrightnessInfo;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.IThermalEventListener;
import android.os.IThermalService;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.Temperature;
import android.provider.DeviceConfig;
import android.provider.DeviceConfigInterface;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.display.DisplayDeviceConfig.BrightnessThrottlingData;
import com.android.server.display.DisplayDeviceConfig.BrightnessThrottlingData.ThrottlingLevel;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * This class monitors various conditions, such as skin temperature throttling status, and limits
 * the allowed brightness range accordingly.
 */
class BrightnessThrottler {
    private static final String TAG = "BrightnessThrottler";
    private static final boolean DEBUG = false;

    private static final int THROTTLING_INVALID = -1;

    private final Injector mInjector;
    private final Handler mHandler;
    // We need a separate handler for unit testing. These two handlers are the same throughout the
    // non-test code.
    private final Handler mDeviceConfigHandler;
    private final Runnable mThrottlingChangeCallback;
    private final SkinThermalStatusObserver mSkinThermalStatusObserver;
    private final DeviceConfigListener mDeviceConfigListener;
    private final DeviceConfigInterface mDeviceConfig;

    private int mThrottlingStatus;
    private BrightnessThrottlingData mThrottlingData;
    private BrightnessThrottlingData mDdcThrottlingData;
    private float mBrightnessCap = PowerManager.BRIGHTNESS_MAX;
    private @BrightnessInfo.BrightnessMaxReason int mBrightnessMaxReason =
        BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE;
    private String mUniqueDisplayId;

    // The most recent string that has been set from DeviceConfig
    private String mBrightnessThrottlingDataString;

    // This is a collection of brightness throttling data that has been written as overrides from
    // the DeviceConfig. This will always take priority over the display device config data.
    private HashMap<String, BrightnessThrottlingData> mBrightnessThrottlingDataOverride =
            new HashMap<>(1);

    BrightnessThrottler(Handler handler, BrightnessThrottlingData throttlingData,
            Runnable throttlingChangeCallback, String uniqueDisplayId) {
        this(new Injector(), handler, handler, throttlingData, throttlingChangeCallback,
                uniqueDisplayId);
    }

    @VisibleForTesting
    BrightnessThrottler(Injector injector, Handler handler, Handler deviceConfigHandler,
            BrightnessThrottlingData throttlingData, Runnable throttlingChangeCallback,
            String uniqueDisplayId) {
        mInjector = injector;

        mHandler = handler;
        mDeviceConfigHandler = deviceConfigHandler;
        mThrottlingData = throttlingData;
        mDdcThrottlingData = throttlingData;
        mThrottlingChangeCallback = throttlingChangeCallback;
        mSkinThermalStatusObserver = new SkinThermalStatusObserver(mInjector, mHandler);

        mUniqueDisplayId = uniqueDisplayId;
        mDeviceConfig = injector.getDeviceConfig();
        mDeviceConfigListener = new DeviceConfigListener();

        resetThrottlingData(mThrottlingData, mUniqueDisplayId);
    }

    boolean deviceSupportsThrottling() {
        return mThrottlingData != null;
    }

    float getBrightnessCap() {
        return mBrightnessCap;
    }

    int getBrightnessMaxReason() {
        return mBrightnessMaxReason;
    }

    boolean isThrottled() {
        return mBrightnessMaxReason != BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE;
    }

    void stop() {
        mSkinThermalStatusObserver.stopObserving();
        mDeviceConfig.removeOnPropertiesChangedListener(mDeviceConfigListener);
        // We're asked to stop throttling, so reset brightness restrictions.
        mBrightnessCap = PowerManager.BRIGHTNESS_MAX;
        mBrightnessMaxReason = BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE;

        // We set throttling status to an invalid value here so that we act on the first throttling
        // value received from the thermal service after registration, even if that throttling value
        // is THROTTLING_NONE.
        mThrottlingStatus = THROTTLING_INVALID;
    }

    private void resetThrottlingData() {
        resetThrottlingData(mDdcThrottlingData, mUniqueDisplayId);
    }

    void resetThrottlingData(BrightnessThrottlingData throttlingData, String displayId) {
        stop();

        mUniqueDisplayId = displayId;
        mDdcThrottlingData = throttlingData;
        mDeviceConfigListener.startListening();
        reloadBrightnessThrottlingDataOverride();
        mThrottlingData = mBrightnessThrottlingDataOverride.getOrDefault(mUniqueDisplayId,
                throttlingData);

        if (deviceSupportsThrottling()) {
            mSkinThermalStatusObserver.startObserving();
        }
    }

    private float verifyAndConstrainBrightnessCap(float brightness) {
        if (brightness < PowerManager.BRIGHTNESS_MIN) {
            Slog.e(TAG, "brightness " + brightness + " is lower than the minimum possible "
                    + "brightness " + PowerManager.BRIGHTNESS_MIN);
            brightness = PowerManager.BRIGHTNESS_MIN;
        }

        if (brightness > PowerManager.BRIGHTNESS_MAX) {
            Slog.e(TAG, "brightness " + brightness + " is higher than the maximum possible "
                    + "brightness " + PowerManager.BRIGHTNESS_MAX);
            brightness = PowerManager.BRIGHTNESS_MAX;
        }

        return brightness;
    }

    private void thermalStatusChanged(@Temperature.ThrottlingStatus int newStatus) {
        if (mThrottlingStatus != newStatus) {
            mThrottlingStatus = newStatus;
            updateThrottling();
        }
    }

    private void updateThrottling() {
        if (!deviceSupportsThrottling()) {
            return;
        }

        float brightnessCap = PowerManager.BRIGHTNESS_MAX;
        int brightnessMaxReason = BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE;

        if (mThrottlingStatus != THROTTLING_INVALID) {
            // Throttling levels are sorted by increasing severity
            for (ThrottlingLevel level : mThrottlingData.throttlingLevels) {
                if (level.thermalStatus <= mThrottlingStatus) {
                    brightnessCap = level.brightness;
                    brightnessMaxReason = BrightnessInfo.BRIGHTNESS_MAX_REASON_THERMAL;
                } else {
                    // Throttling levels that are greater than the current status are irrelevant
                    break;
                }
            }
        }

        if (mBrightnessCap != brightnessCap || mBrightnessMaxReason != brightnessMaxReason) {
            mBrightnessCap = verifyAndConstrainBrightnessCap(brightnessCap);
            mBrightnessMaxReason = brightnessMaxReason;

            if (DEBUG) {
                Slog.d(TAG, "State changed: mBrightnessCap = " + mBrightnessCap
                        + ", mBrightnessMaxReason = "
                        + BrightnessInfo.briMaxReasonToString(mBrightnessMaxReason));
            }

            if (mThrottlingChangeCallback != null) {
                mThrottlingChangeCallback.run();
            }
        }
    }

    void dump(PrintWriter pw) {
        mHandler.runWithScissors(() -> dumpLocal(pw), 1000);
    }

    private void dumpLocal(PrintWriter pw) {
        pw.println("BrightnessThrottler:");
        pw.println("  mThrottlingData=" + mThrottlingData);
        pw.println("  mDdcThrottlingData=" + mDdcThrottlingData);
        pw.println("  mUniqueDisplayId=" + mUniqueDisplayId);
        pw.println("  mThrottlingStatus=" + mThrottlingStatus);
        pw.println("  mBrightnessCap=" + mBrightnessCap);
        pw.println("  mBrightnessMaxReason=" +
            BrightnessInfo.briMaxReasonToString(mBrightnessMaxReason));
        pw.println("  mBrightnessThrottlingDataOverride=" + mBrightnessThrottlingDataOverride);
        pw.println("  mBrightnessThrottlingDataString=" + mBrightnessThrottlingDataString);

        mSkinThermalStatusObserver.dump(pw);
    }

    private String getBrightnessThrottlingDataString() {
        return mDeviceConfig.getString(DeviceConfig.NAMESPACE_DISPLAY_MANAGER,
                DisplayManager.DeviceConfig.KEY_BRIGHTNESS_THROTTLING_DATA,
                /* defaultValue= */ null);
    }

    private boolean parseAndSaveData(@NonNull String strArray,
            @NonNull HashMap<String, BrightnessThrottlingData> tempBrightnessThrottlingData) {
        boolean validConfig = true;
        String[] items = strArray.split(",");
        int i = 0;

        try {
            String uniqueDisplayId = items[i++];

            // number of throttling points
            int noOfThrottlingPoints = Integer.parseInt(items[i++]);
            List<ThrottlingLevel> throttlingLevels = new ArrayList<>(noOfThrottlingPoints);

            // throttling level and point
            for (int j = 0; j < noOfThrottlingPoints; j++) {
                String severity = items[i++];
                int status = parseThermalStatus(severity);

                float brightnessPoint = parseBrightness(items[i++]);

                throttlingLevels.add(new ThrottlingLevel(status, brightnessPoint));
            }
            BrightnessThrottlingData toSave =
                    DisplayDeviceConfig.BrightnessThrottlingData.create(throttlingLevels);
            tempBrightnessThrottlingData.put(uniqueDisplayId, toSave);
        } catch (NumberFormatException | IndexOutOfBoundsException
                | UnknownThermalStatusException e) {
            validConfig = false;
            Slog.e(TAG, "Throttling data is invalid array: '" + strArray + "'", e);
        }

        if (i != items.length) {
            validConfig = false;
        }

        return validConfig;
    }

    public void reloadBrightnessThrottlingDataOverride() {
        HashMap<String, BrightnessThrottlingData> tempBrightnessThrottlingData =
                new HashMap<>(1);
        mBrightnessThrottlingDataString = getBrightnessThrottlingDataString();
        boolean validConfig = true;
        mBrightnessThrottlingDataOverride.clear();
        if (mBrightnessThrottlingDataString != null) {
            String[] throttlingDataSplits = mBrightnessThrottlingDataString.split(";");
            for (String s : throttlingDataSplits) {
                if (!parseAndSaveData(s, tempBrightnessThrottlingData)) {
                    validConfig = false;
                    break;
                }
            }

            if (validConfig) {
                mBrightnessThrottlingDataOverride.putAll(tempBrightnessThrottlingData);
                tempBrightnessThrottlingData.clear();
            }

        } else {
            Slog.w(TAG, "DeviceConfig BrightnessThrottlingData is null");
        }
    }

    /**
     * Listens to config data change and updates the brightness throttling data using
     * DisplayManager#KEY_BRIGHTNESS_THROTTLING_DATA.
     * The format should be a string similar to: "local:4619827677550801152,2,moderate,0.5,severe,
     * 0.379518072;local:4619827677550801151,1,moderate,0.75"
     * In this order:
     * <displayId>,<no of throttling levels>,[<severity as string>,<brightness cap>]
     * Where the latter part is repeated for each throttling level, and the entirety is repeated
     * for each display, separated by a semicolon.
     */
    public class DeviceConfigListener implements DeviceConfig.OnPropertiesChangedListener {
        public Executor mExecutor = new HandlerExecutor(mDeviceConfigHandler);

        public void startListening() {
            mDeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_DISPLAY_MANAGER,
                    mExecutor, this);
        }

        @Override
        public void onPropertiesChanged(DeviceConfig.Properties properties) {
            reloadBrightnessThrottlingDataOverride();
            resetThrottlingData();
        }
    }

    private float parseBrightness(String intVal) throws NumberFormatException {
        float value = Float.parseFloat(intVal);
        if (value < PowerManager.BRIGHTNESS_MIN || value > PowerManager.BRIGHTNESS_MAX) {
            throw new NumberFormatException("Brightness constraint value out of bounds.");
        }
        return value;
    }

    @PowerManager.ThermalStatus private int parseThermalStatus(@NonNull String value)
            throws UnknownThermalStatusException {
        switch (value) {
            case "none":
                return PowerManager.THERMAL_STATUS_NONE;
            case "light":
                return PowerManager.THERMAL_STATUS_LIGHT;
            case "moderate":
                return PowerManager.THERMAL_STATUS_MODERATE;
            case "severe":
                return PowerManager.THERMAL_STATUS_SEVERE;
            case "critical":
                return PowerManager.THERMAL_STATUS_CRITICAL;
            case "emergency":
                return PowerManager.THERMAL_STATUS_EMERGENCY;
            case "shutdown":
                return PowerManager.THERMAL_STATUS_SHUTDOWN;
            default:
                throw new UnknownThermalStatusException("Invalid Thermal Status: " + value);
        }
    }

    private static class UnknownThermalStatusException extends Exception {
        UnknownThermalStatusException(String message) {
            super(message);
        }
    }

    private final class SkinThermalStatusObserver extends IThermalEventListener.Stub {
        private final Injector mInjector;
        private final Handler mHandler;

        private IThermalService mThermalService;
        private boolean mStarted;

        SkinThermalStatusObserver(Injector injector, Handler handler) {
            mInjector = injector;
            mHandler = handler;
        }

        @Override
        public void notifyThrottling(Temperature temp) {
            if (DEBUG) {
                Slog.d(TAG, "New thermal throttling status = " + temp.getStatus());
            }
            mHandler.post(() -> {
                final @Temperature.ThrottlingStatus int status = temp.getStatus();
                thermalStatusChanged(status);
            });
        }

        void startObserving() {
            if (mStarted) {
                if (DEBUG) {
                    Slog.d(TAG, "Thermal status observer already started");
                }
                return;
            }
            mThermalService = mInjector.getThermalService();
            if (mThermalService == null) {
                Slog.e(TAG, "Could not observe thermal status. Service not available");
                return;
            }
            try {
                // We get a callback immediately upon registering so there's no need to query
                // for the current value.
                mThermalService.registerThermalEventListenerWithType(this, Temperature.TYPE_SKIN);
                mStarted = true;
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to register thermal status listener", e);
            }
        }

        void stopObserving() {
            if (!mStarted) {
                if (DEBUG) {
                    Slog.d(TAG, "Stop skipped because thermal status observer not started");
                }
                return;
            }
            try {
                mThermalService.unregisterThermalEventListener(this);
                mStarted = false;
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to unregister thermal status listener", e);
            }
            mThermalService = null;
        }

        void dump(PrintWriter writer) {
            writer.println("  SkinThermalStatusObserver:");
            writer.println("    mStarted: " + mStarted);
            if (mThermalService != null) {
                writer.println("    ThermalService available");
            } else {
                writer.println("    ThermalService not available");
            }
        }
    }

    public static class Injector {
        public IThermalService getThermalService() {
            return IThermalService.Stub.asInterface(
                    ServiceManager.getService(Context.THERMAL_SERVICE));
        }

        @NonNull
        public DeviceConfigInterface getDeviceConfig() {
            return DeviceConfigInterface.REAL;
        }
    }
}
