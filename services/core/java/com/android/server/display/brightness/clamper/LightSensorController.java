/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.display.brightness.clamper;

import android.annotation.Nullable;
import android.content.res.Resources;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Slog;
import android.view.Display;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.display.config.SensorData;
import com.android.server.display.utils.AmbientFilter;
import com.android.server.display.utils.AmbientFilterFactory;
import com.android.server.display.utils.DebugUtils;
import com.android.server.display.utils.SensorUtils;

import java.io.PrintWriter;
import java.util.concurrent.TimeUnit;

/**
 * Manages light sensor subscription and notifies its listener about ambient lux changes
 */
public class LightSensorController {
    private static final String TAG = "LightSensorController";

    // To enable these logs, run:
    // 'adb shell setprop persist.log.tag.LightSensorController DEBUG && adb reboot'
    private static final boolean DEBUG = DebugUtils.isDebuggable(TAG);
    static final float INVALID_LUX = -1f;

    private final SensorManager mSensorManager;
    private final LightSensorListener mLightSensorListener;
    private final Handler mHandler;
    private final Injector mInjector;
    private final AmbientFilter mAmbientFilter;

    private Sensor mLightSensor;
    private Sensor mRegisteredLightSensor = null;
    private final int mLightSensorRate;

    private final SensorEventListener mLightSensorEventListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            long now = mInjector.getTime();
            mAmbientFilter.addValue(TimeUnit.NANOSECONDS.toMillis(event.timestamp),
                    event.values[0]);
            final float lux = mAmbientFilter.getEstimate(now);
            mLightSensorListener.onAmbientLuxChange(lux);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // unused
        }
    };

    LightSensorController(SensorManager sensorManager, Resources resources,
            LightSensorListener listener, Handler handler) {
        this(sensorManager, resources, listener, handler, new Injector());
    }

    @VisibleForTesting
    LightSensorController(SensorManager sensorManager, Resources resources,
            LightSensorListener listener, Handler handler, Injector injector) {
        mSensorManager = sensorManager;
        mLightSensorRate = injector.getLightSensorRate(resources);
        mAmbientFilter = injector.getAmbientFilter(resources);
        mLightSensorListener = listener;
        mHandler = handler;
        mInjector = injector;
    }

    void restart() {
        if (mRegisteredLightSensor == mLightSensor) {
            return;
        }
        if (mRegisteredLightSensor != null) {
            stop();
        }
        if (mLightSensor == null) {
            return;
        }

        mSensorManager.registerListener(mLightSensorEventListener,
                mLightSensor, mLightSensorRate * 1000, mHandler);
        mRegisteredLightSensor = mLightSensor;

        if (DEBUG) {
            Slog.d(TAG, "restart");
        }
    }

    void stop() {
        if (mRegisteredLightSensor == null) {
            return;
        }
        mSensorManager.unregisterListener(mLightSensorEventListener);
        mRegisteredLightSensor = null;
        mAmbientFilter.clear();
        mLightSensorListener.onAmbientLuxChange(INVALID_LUX);
        if (DEBUG) {
            Slog.d(TAG, "stop");
        }
    }

    void configure(SensorData sensorData, int displayId) {
        final int fallbackType = displayId == Display.DEFAULT_DISPLAY
                ? Sensor.TYPE_LIGHT : SensorUtils.NO_FALLBACK;
        mLightSensor = mInjector.getLightSensor(mSensorManager, sensorData, fallbackType);
    }

    void dump(PrintWriter writer) {
        writer.println("LightSensorController");
        writer.println("  mLightSensor=" + mLightSensor);
        writer.println("  mRegisteredLightSensor=" + mRegisteredLightSensor);
    }

    static class Injector {
        @Nullable
        Sensor getLightSensor(SensorManager sensorManager, SensorData sensorData,
                int fallbackType) {
            return SensorUtils.findSensor(sensorManager, sensorData, fallbackType);
        }

        AmbientFilter getAmbientFilter(Resources resources) {
            return AmbientFilterFactory.createBrightnessFilter(TAG, resources);
        }

        int getLightSensorRate(Resources resources) {
            return resources.getInteger(R.integer.config_autoBrightnessLightSensorRate);
        }

        // should be consistent with SensorEvent.timestamp
        long getTime() {
            return SystemClock.elapsedRealtime();
        }
    }

    interface  LightSensorListener {
        void onAmbientLuxChange(float ambientLux);
    }
}
