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

package com.android.server.sensorprivacy;

import static android.hardware.SensorManager.SENSOR_DELAY_NORMAL;

import android.app.AppOpsManager;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.lights.Light;
import android.hardware.lights.LightState;
import android.hardware.lights.LightsManager;
import android.hardware.lights.LightsRequest;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.Looper;
import android.os.SystemClock;
import android.permission.PermissionManager;
import android.util.ArraySet;
import android.util.Pair;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.server.FgThread;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

class CameraPrivacyLightController implements AppOpsManager.OnOpActiveChangedListener,
        SensorEventListener {

    private static final double LIGHT_VALUE_MULTIPLIER = 1 / Math.log(1.1);

    private final Handler mHandler;
    private final Executor mExecutor;
    private final Context mContext;
    private final AppOpsManager mAppOpsManager;
    private final LightsManager mLightsManager;
    private final SensorManager mSensorManager;

    private final Set<String> mActivePackages = new ArraySet<>();
    private final Set<String> mActivePhonePackages = new ArraySet<>();

    private final List<Light> mCameraLights = new ArrayList<>();

    private LightsManager.LightsSession mLightsSession = null;

    private final Sensor mLightSensor;

    private boolean mIsAmbientLightListenerRegistered = false;
    private final long mMovingAverageIntervalMillis;
    /** When average of the time integral over the past {@link #mMovingAverageIntervalMillis}
     *  milliseconds of the log_1.1(lux(t)) is greater than this value, use the daytime brightness
     *  else use nighttime brightness. */
    private final long[] mThresholds;

    private final int[] mColors;
    private final ArrayDeque<Pair<Long, Integer>> mAmbientLightValues = new ArrayDeque<>();
    /** Tracks the Riemann sum of {@link #mAmbientLightValues} to avoid O(n) operations when sum is
     *  needed */
    private long mAlvSum = 0;
    private int mLastLightColor = 0;
    /** The elapsed real time that the ALS was started watching */
    private long mElapsedTimeStartedReading;

    private final Object mDelayedUpdateToken = new Object();

    // Can't mock static native methods, workaround for testing
    private long mElapsedRealTime = -1;

    CameraPrivacyLightController(Context context) {
        this(context, FgThread.get().getLooper());
    }

    @VisibleForTesting
    CameraPrivacyLightController(Context context, Looper looper) {
        mColors = context.getResources().getIntArray(R.array.config_cameraPrivacyLightColors);
        if (ArrayUtils.isEmpty(mColors)) {
            mHandler = null;
            mExecutor = null;
            mContext = null;
            mAppOpsManager = null;
            mLightsManager = null;
            mSensorManager = null;
            mLightSensor = null;
            mMovingAverageIntervalMillis = 0;
            mThresholds = null;
            // Return here before this class starts interacting with other services.
            return;
        }
        mContext = context;

        mHandler = new Handler(looper);
        mExecutor = new HandlerExecutor(mHandler);

        mAppOpsManager = mContext.getSystemService(AppOpsManager.class);
        mLightsManager = mContext.getSystemService(LightsManager.class);
        mSensorManager = mContext.getSystemService(SensorManager.class);
        mMovingAverageIntervalMillis = mContext.getResources()
                .getInteger(R.integer.config_cameraPrivacyLightAlsAveragingIntervalMillis);
        int[] thresholdsLux = mContext.getResources().getIntArray(
                R.array.config_cameraPrivacyLightAlsLuxThresholds);
        if (thresholdsLux.length != mColors.length - 1) {
            throw new IllegalStateException("There must be exactly one more color than thresholds."
                    + " Found " + mColors.length + " colors and " + thresholdsLux.length
                    + " thresholds.");
        }
        mThresholds = new long[thresholdsLux.length];
        for (int i = 0; i < thresholdsLux.length; i++) {
            int luxValue = thresholdsLux[i];
            mThresholds[i] = (long) (Math.log(luxValue) * LIGHT_VALUE_MULTIPLIER);
        }

        List<Light> lights = mLightsManager.getLights();
        for (int i = 0; i < lights.size(); i++) {
            Light light = lights.get(i);
            if (light.getType() == Light.LIGHT_TYPE_CAMERA) {
                mCameraLights.add(light);
            }
        }

        if (mCameraLights.isEmpty()) {
            mLightSensor = null;
            return;
        }

        mAppOpsManager.startWatchingActive(
                new String[] {AppOpsManager.OPSTR_CAMERA, AppOpsManager.OPSTR_PHONE_CALL_CAMERA},
                mExecutor, this);

        // It may be useful in the future to configure devices to know which lights are near which
        // sensors so that we can control individual lights based on their environment.
        mLightSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
    }

    private void addElement(long time, int value) {
        if (mAmbientLightValues.isEmpty()) {
            // Eliminate the size == 1 edge case and assume the light value has been constant for
            // the previous interval
            mAmbientLightValues.add(new Pair<>(time - getCurrentIntervalMillis() - 1, value));
        }
        Pair<Long, Integer> lastElement = mAmbientLightValues.peekLast();
        mAmbientLightValues.add(new Pair<>(time, value));

        mAlvSum += (time - lastElement.first) * lastElement.second;
        removeObsoleteData(time);
    }

    private void removeObsoleteData(long time) {
        while (mAmbientLightValues.size() > 1) {
            Pair<Long, Integer> element0 = mAmbientLightValues.pollFirst(); // NOTICE: POLL
            Pair<Long, Integer> element1 = mAmbientLightValues.peekFirst(); // NOTICE: PEEK
            if (element1.first > time - getCurrentIntervalMillis()) {
                mAmbientLightValues.addFirst(element0);
                break;
            }
            mAlvSum -= (element1.first - element0.first) * element0.second;
        }
    }

    /**
     * Gives the Riemann sum of {@link #mAmbientLightValues} where the part of the interval that
     * stretches outside the time window is removed and the time since the last change is added in.
     */
    private long getLiveAmbientLightTotal() {
        if (mAmbientLightValues.isEmpty()) {
            return mAlvSum;
        }
        long time = getElapsedRealTime();
        removeObsoleteData(time);

        Pair<Long, Integer> firstElement = mAmbientLightValues.peekFirst();
        Pair<Long, Integer> lastElement = mAmbientLightValues.peekLast();

        return mAlvSum - Math.max(0, time - getCurrentIntervalMillis() - firstElement.first)
                * firstElement.second + (time - lastElement.first) * lastElement.second;
    }

    @Override
    public void onOpActiveChanged(String op, int uid, String packageName, boolean active) {
        final Set<String> activePackages;
        if (AppOpsManager.OPSTR_CAMERA.equals(op)) {
            activePackages = mActivePackages;
        } else if (AppOpsManager.OPSTR_PHONE_CALL_CAMERA.equals(op)) {
            activePackages = mActivePhonePackages;
        } else {
            return;
        }

        if (active) {
            activePackages.add(packageName);
        } else {
            activePackages.remove(packageName);
        }

        updateLightSession();
    }

    private void updateLightSession() {
        if (Looper.myLooper() != mHandler.getLooper()) {
            mHandler.post(this::updateLightSession);
            return;
        }

        Set<String> exemptedPackages = PermissionManager.getIndicatorExemptedPackages(mContext);

        boolean shouldSessionEnd = exemptedPackages.containsAll(mActivePackages)
                && exemptedPackages.containsAll(mActivePhonePackages);
        updateSensorListener(shouldSessionEnd);

        if (shouldSessionEnd) {
            if (mLightsSession == null) {
                return;
            }

            mLightsSession.close();
            mLightsSession = null;
        } else {
            int lightColor =
                    mLightSensor == null ? mColors[mColors.length - 1] : computeCurrentLightColor();

            if (mLastLightColor == lightColor && mLightsSession != null) {
                return;
            }
            mLastLightColor = lightColor;

            LightsRequest.Builder requestBuilder = new LightsRequest.Builder();
            for (int i = 0; i < mCameraLights.size(); i++) {
                requestBuilder.addLight(mCameraLights.get(i),
                        new LightState.Builder()
                                .setColor(lightColor)
                                .build());
            }

            if (mLightsSession == null) {
                mLightsSession = mLightsManager.openSession(Integer.MAX_VALUE);
            }

            mLightsSession.requestLights(requestBuilder.build());
        }
    }

    private int computeCurrentLightColor() {
        long liveAmbientLightTotal = getLiveAmbientLightTotal();
        long currentInterval = getCurrentIntervalMillis();

        for (int i = 0; i < mThresholds.length; i++) {
            if (liveAmbientLightTotal < currentInterval * mThresholds[i]) {
                return mColors[i];
            }
        }
        return mColors[mColors.length - 1];
    }

    private void updateSensorListener(boolean shouldSessionEnd) {
        if (shouldSessionEnd && mIsAmbientLightListenerRegistered) {
            mSensorManager.unregisterListener(this);
            mIsAmbientLightListenerRegistered = false;
        }
        if (!shouldSessionEnd && !mIsAmbientLightListenerRegistered && mLightSensor != null) {
            mSensorManager.registerListener(this, mLightSensor, SENSOR_DELAY_NORMAL, mHandler);
            mIsAmbientLightListenerRegistered = true;
            mElapsedTimeStartedReading = getElapsedRealTime();
        }
    }

    private long getElapsedRealTime() {
        return mElapsedRealTime == -1 ? SystemClock.elapsedRealtime() : mElapsedRealTime;
    }

    @VisibleForTesting
    void setElapsedRealTime(long time) {
        mElapsedRealTime = time;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        // Using log space to represent human sensation (Fechner's Law) instead of lux
        // because lux values causes bright flashes to skew the average very high.
        addElement(TimeUnit.NANOSECONDS.toMillis(event.timestamp), Math.max(0,
                (int) (Math.log(event.values[0]) * LIGHT_VALUE_MULTIPLIER)));
        updateLightSession();
        mHandler.removeCallbacksAndMessages(mDelayedUpdateToken);
        mHandler.postDelayed(CameraPrivacyLightController.this::updateLightSession,
                mDelayedUpdateToken, mMovingAverageIntervalMillis);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private long getCurrentIntervalMillis() {
        return Math.min(mMovingAverageIntervalMillis,
                getElapsedRealTime() - mElapsedTimeStartedReading);
    }
}
