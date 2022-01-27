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

import android.content.Context;
import android.hardware.display.BrightnessInfo;
import android.os.Handler;
import android.os.IThermalEventListener;
import android.os.IThermalService;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.Temperature;
import android.util.Slog;

import com.android.server.display.DisplayDeviceConfig.BrightnessThrottlingData.ThrottlingLevel;
import com.android.server.display.DisplayDeviceConfig.BrightnessThrottlingData;

import java.io.PrintWriter;

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
    private BrightnessThrottlingData mThrottlingData;
    private final Runnable mThrottlingChangeCallback;
    private final SkinThermalStatusObserver mSkinThermalStatusObserver;
    private int mThrottlingStatus;
    private float mBrightnessCap = PowerManager.BRIGHTNESS_MAX;
    private @BrightnessInfo.BrightnessMaxReason int mBrightnessMaxReason =
        BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE;

    BrightnessThrottler(Handler handler, BrightnessThrottlingData throttlingData,
            Runnable throttlingChangeCallback) {
        this(new Injector(), handler, throttlingData, throttlingChangeCallback);
    }

    BrightnessThrottler(Injector injector, Handler handler, BrightnessThrottlingData throttlingData,
            Runnable throttlingChangeCallback) {
        mInjector = injector;
        mHandler = handler;
        mThrottlingData = throttlingData;
        mThrottlingChangeCallback = throttlingChangeCallback;
        mSkinThermalStatusObserver = new SkinThermalStatusObserver(mInjector, mHandler);

        resetThrottlingData(mThrottlingData);
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

        // We're asked to stop throttling, so reset brightness restrictions.
        mBrightnessCap = PowerManager.BRIGHTNESS_MAX;
        mBrightnessMaxReason = BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE;

        // We set throttling status to an invalid value here so that we act on the first throttling
        // value received from the thermal service after registration, even if that throttling value
        // is THROTTLING_NONE.
        mThrottlingStatus = THROTTLING_INVALID;
    }

    void resetThrottlingData(BrightnessThrottlingData throttlingData) {
        stop();
        mThrottlingData = throttlingData;

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
        pw.println("  mThrottlingStatus=" + mThrottlingStatus);
        pw.println("  mBrightnessCap=" + mBrightnessCap);
        pw.println("  mBrightnessMaxReason=" +
            BrightnessInfo.briMaxReasonToString(mBrightnessMaxReason));

        mSkinThermalStatusObserver.dump(pw);
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
    }
}
