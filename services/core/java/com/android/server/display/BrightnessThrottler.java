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

import static com.android.server.display.DisplayDeviceConfig.DEFAULT_ID;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.display.BrightnessInfo;
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
import com.android.server.display.DisplayDeviceConfig.ThermalBrightnessThrottlingData;
import com.android.server.display.DisplayDeviceConfig.ThermalBrightnessThrottlingData.ThrottlingLevel;
import com.android.server.display.config.SensorData;
import com.android.server.display.feature.DeviceConfigParameterProvider;
import com.android.server.display.utils.DebugUtils;
import com.android.server.display.utils.DeviceConfigParsingUtils;
import com.android.server.display.utils.SensorUtils;

import java.io.PrintWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * This class monitors various conditions, such as skin temperature throttling status, and limits
 * the allowed brightness range accordingly.
 *
 * @deprecated will be replaced by
 * {@link com.android.server.display.brightness.clamper.BrightnessThermalClamper}
 */
@Deprecated
class BrightnessThrottler {
    private static final String TAG = "BrightnessThrottler";

    // To enable these logs, run:
    // 'adb shell setprop persist.log.tag.BrightnessThrottler DEBUG && adb reboot'
    private static final boolean DEBUG = DebugUtils.isDebuggable(TAG);
    private static final int THROTTLING_INVALID = -1;

    private final Injector mInjector;
    private final Handler mHandler;
    // We need a separate handler for unit testing. These two handlers are the same throughout the
    // non-test code.
    private final Handler mDeviceConfigHandler;
    private final Runnable mThrottlingChangeCallback;
    private final SkinThermalStatusObserver mSkinThermalStatusObserver;
    private final DeviceConfigListener mDeviceConfigListener;
    private final DeviceConfigParameterProvider mConfigParameterProvider;

    private int mThrottlingStatus;

    // Maps the throttling ID to the data. Sourced from DisplayDeviceConfig.
    @NonNull
    private Map<String, ThermalBrightnessThrottlingData> mDdcThermalThrottlingDataMap;

    // Current throttling data being used.
    // Null if we do not support throttling.
    @Nullable
    private ThermalBrightnessThrottlingData mThermalThrottlingData;

    private float mBrightnessCap = PowerManager.BRIGHTNESS_MAX;
    private @BrightnessInfo.BrightnessMaxReason int mBrightnessMaxReason =
        BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE;
    private String mUniqueDisplayId;

    // The most recent string that has been set from DeviceConfig
    private String mThermalBrightnessThrottlingDataString;

    // The brightness throttling configuration that should be used.
    private String mThermalBrightnessThrottlingDataId;

    // Temperature Sensor to be monitored for throttling.
    @NonNull
    private SensorData mTempSensor;

    // This is a collection of brightness throttling data that has been written as overrides from
    // the DeviceConfig. This will always take priority over the display device config data.
    // We need to store the data for every display device, so we do not need to update this each
    // time the underlying display device changes.
    // This map is indexed by uniqueDisplayId, to provide maps for throttlingId -> throttlingData.
    // HashMap< uniqueDisplayId, HashMap< throttlingDataId, ThermalBrightnessThrottlingData >>
    private final Map<String, Map<String, ThermalBrightnessThrottlingData>>
            mThermalBrightnessThrottlingDataOverride = new HashMap<>();

    private final BiFunction<String, String, ThrottlingLevel> mDataPointMapper = (key, value) -> {
        try {
            int status = DeviceConfigParsingUtils.parseThermalStatus(key);
            float brightnessPoint = DeviceConfigParsingUtils.parseBrightness(value);
            return new ThrottlingLevel(status, brightnessPoint);
        } catch (IllegalArgumentException iae) {
            return null;
        }
    };

    private final Function<List<ThrottlingLevel>, ThermalBrightnessThrottlingData>
            mDataSetMapper = ThermalBrightnessThrottlingData::create;

    BrightnessThrottler(Handler handler, Runnable throttlingChangeCallback, String uniqueDisplayId,
            String throttlingDataId,
            @NonNull DisplayDeviceConfig displayDeviceConfig) {
        this(new Injector(), handler, handler, throttlingChangeCallback, uniqueDisplayId,
                throttlingDataId,
                displayDeviceConfig.getThermalBrightnessThrottlingDataMapByThrottlingId(),
                displayDeviceConfig.getTempSensor());
    }

    @VisibleForTesting
    BrightnessThrottler(Injector injector, Handler handler, Handler deviceConfigHandler,
            Runnable throttlingChangeCallback, String uniqueDisplayId, String throttlingDataId,
            @NonNull Map<String, ThermalBrightnessThrottlingData>
                    thermalBrightnessThrottlingDataMap,
            @NonNull SensorData tempSensor) {
        mInjector = injector;

        mHandler = handler;
        mDeviceConfigHandler = deviceConfigHandler;
        mDdcThermalThrottlingDataMap = thermalBrightnessThrottlingDataMap;
        mThrottlingChangeCallback = throttlingChangeCallback;
        mSkinThermalStatusObserver = new SkinThermalStatusObserver(mInjector, mHandler);

        mUniqueDisplayId = uniqueDisplayId;
        mConfigParameterProvider = new DeviceConfigParameterProvider(injector.getDeviceConfig());
        mDeviceConfigListener = new DeviceConfigListener();
        mThermalBrightnessThrottlingDataId = throttlingDataId;
        mDdcThermalThrottlingDataMap = thermalBrightnessThrottlingDataMap;
        loadThermalBrightnessThrottlingDataFromDeviceConfig();
        loadThermalBrightnessThrottlingDataFromDisplayDeviceConfig(mDdcThermalThrottlingDataMap,
                tempSensor, mThermalBrightnessThrottlingDataId, mUniqueDisplayId);
    }

    boolean deviceSupportsThrottling() {
        return mThermalThrottlingData != null;
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
        mConfigParameterProvider.removeOnPropertiesChangedListener(mDeviceConfigListener);
        // We're asked to stop throttling, so reset brightness restrictions.
        mBrightnessCap = PowerManager.BRIGHTNESS_MAX;
        mBrightnessMaxReason = BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE;

        // We set throttling status to an invalid value here so that we act on the first throttling
        // value received from the thermal service after registration, even if that throttling value
        // is THROTTLING_NONE.
        mThrottlingStatus = THROTTLING_INVALID;
    }

    void loadThermalBrightnessThrottlingDataFromDisplayDeviceConfig(
            Map<String, ThermalBrightnessThrottlingData> ddcThrottlingDataMap,
            SensorData tempSensor,
            String brightnessThrottlingDataId,
            String uniqueDisplayId) {
        mDdcThermalThrottlingDataMap = ddcThrottlingDataMap;
        mThermalBrightnessThrottlingDataId = brightnessThrottlingDataId;
        mUniqueDisplayId = uniqueDisplayId;
        mTempSensor = tempSensor;
        resetThermalThrottlingData();
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
            updateThermalThrottling();
        }
    }

    private void updateThermalThrottling() {
        if (!deviceSupportsThrottling()) {
            return;
        }

        float brightnessCap = PowerManager.BRIGHTNESS_MAX;
        int brightnessMaxReason = BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE;

        if (mThrottlingStatus != THROTTLING_INVALID && mThermalThrottlingData != null) {
            // Throttling levels are sorted by increasing severity
            for (ThrottlingLevel level : mThermalThrottlingData.throttlingLevels) {
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
        pw.println("  mThermalBrightnessThrottlingDataId=" + mThermalBrightnessThrottlingDataId);
        pw.println("  mThermalThrottlingData=" + mThermalThrottlingData);
        pw.println("  mUniqueDisplayId=" + mUniqueDisplayId);
        pw.println("  mThrottlingStatus=" + mThrottlingStatus);
        pw.println("  mBrightnessCap=" + mBrightnessCap);
        pw.println("  mBrightnessMaxReason=" +
            BrightnessInfo.briMaxReasonToString(mBrightnessMaxReason));
        pw.println("  mDdcThermalThrottlingDataMap=" + mDdcThermalThrottlingDataMap);
        pw.println("  mThermalBrightnessThrottlingDataOverride="
                + mThermalBrightnessThrottlingDataOverride);
        pw.println("  mThermalBrightnessThrottlingDataString="
                + mThermalBrightnessThrottlingDataString);

        mSkinThermalStatusObserver.dump(pw);
    }

    // The brightness throttling data id may or may not be specified in the string that is passed
    // in, if there is none specified, we assume it is for the default case. Each string passed in
    // here must be for one display and one throttling id.
    // 123,1,critical,0.8
    // 456,2,moderate,0.9,critical,0.7
    // 456,2,moderate,0.9,critical,0.7,default
    // 456,2,moderate,0.9,critical,0.7,id_2
    // displayId, number, <state, val> * number
    // displayId, <number, <state, val> * number>, throttlingId
    private void loadThermalBrightnessThrottlingDataFromDeviceConfig() {
        mThermalBrightnessThrottlingDataString =
                mConfigParameterProvider.getBrightnessThrottlingData();
        mThermalBrightnessThrottlingDataOverride.clear();
        if (mThermalBrightnessThrottlingDataString != null) {
            Map<String, Map<String, ThermalBrightnessThrottlingData>> tempThrottlingData =
                    DeviceConfigParsingUtils.parseDeviceConfigMap(
                    mThermalBrightnessThrottlingDataString, mDataPointMapper, mDataSetMapper);
            mThermalBrightnessThrottlingDataOverride.putAll(tempThrottlingData);
        } else {
            Slog.w(TAG, "DeviceConfig ThermalBrightnessThrottlingData is null");
        }
    }

    private void resetThermalThrottlingData() {
        stop();

        mDeviceConfigListener.startListening();

        // Get throttling data for this id, if it exists
        mThermalThrottlingData = getConfigFromId(mThermalBrightnessThrottlingDataId);

        // Fallback to default id otherwise.
        if (!DEFAULT_ID.equals(mThermalBrightnessThrottlingDataId)
                && mThermalThrottlingData == null) {
            mThermalThrottlingData = getConfigFromId(DEFAULT_ID);
            Slog.d(TAG, "Falling back to default throttling Id");
        }

        if (deviceSupportsThrottling()) {
            mSkinThermalStatusObserver.startObserving(mTempSensor);
        }
    }

    private ThermalBrightnessThrottlingData getConfigFromId(String id) {
        ThermalBrightnessThrottlingData returnValue;

        // Fallback pattern for fetching correct throttling data for this display and id.
        // 1) throttling data from device config for this throttling data id
        returnValue =  mThermalBrightnessThrottlingDataOverride.get(mUniqueDisplayId) == null
                ? null
                : mThermalBrightnessThrottlingDataOverride.get(mUniqueDisplayId).get(id);
        // 2) throttling data from ddc for this throttling data id
        returnValue = returnValue == null
                ? mDdcThermalThrottlingDataMap.get(id)
                : returnValue;

        return returnValue;
    }

    /**
     * Listens to config data change and updates the brightness throttling data using
     * DisplayManager#KEY_BRIGHTNESS_THROTTLING_DATA.
     * The format should be a string similar to: "local:4619827677550801152,2,moderate,0.5,severe,
     * 0.379518072;local:4619827677550801151,1,moderate,0.75"
     * In this order:
     * <displayId>,<no of throttling levels>,[<severity as string>,<brightness cap>][,throttlingId]?
     * Where [<severity as string>,<brightness cap>] is repeated for each throttling level, and the
     * entirety is repeated for each display & throttling data id, separated by a semicolon.
     */
    public class DeviceConfigListener implements DeviceConfig.OnPropertiesChangedListener {
        public Executor mExecutor = new HandlerExecutor(mDeviceConfigHandler);

        public void startListening() {
            mConfigParameterProvider.addOnPropertiesChangedListener(mExecutor, this);
        }

        @Override
        public void onPropertiesChanged(DeviceConfig.Properties properties) {
            loadThermalBrightnessThrottlingDataFromDeviceConfig();
            resetThermalThrottlingData();
        }
    }

    private final class SkinThermalStatusObserver extends IThermalEventListener.Stub {
        private final Injector mInjector;
        private final Handler mHandler;
        private SensorData mObserverTempSensor;

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

            if (mObserverTempSensor.name != null
                    && !mObserverTempSensor.name.equals(temp.getName())) {
                Slog.i(TAG, "Skipping thermal throttling notification as monitored sensor: "
                            + mObserverTempSensor.name
                            + " != notified sensor: "
                            + temp.getName());
                return;
            }
            mHandler.post(() -> {
                final @Temperature.ThrottlingStatus int status = temp.getStatus();
                thermalStatusChanged(status);
            });
        }

        void startObserving(SensorData tempSensor) {
            if (!mStarted || mObserverTempSensor == null) {
                mObserverTempSensor = tempSensor;
                registerThermalListener();
                return;
            }

            String curType = mObserverTempSensor.type;
            mObserverTempSensor = tempSensor;
            if (curType.equals(tempSensor.type)) {
                if (DEBUG) {
                    Slog.d(TAG, "Thermal status observer already started");
                }
                return;
            }
            stopObserving();
            registerThermalListener();
        }

        void registerThermalListener() {
            mThermalService = mInjector.getThermalService();
            if (mThermalService == null) {
                Slog.e(TAG, "Could not observe thermal status. Service not available");
                return;
            }
            int temperatureType = SensorUtils.getSensorTemperatureType(mObserverTempSensor);
            try {
                // We get a callback immediately upon registering so there's no need to query
                // for the current value.
                mThermalService.registerThermalEventListenerWithType(this, temperatureType);
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
            writer.println("    mObserverTempSensor: " + mObserverTempSensor);
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
