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

import android.hardware.display.BrightnessInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Slog;
import android.view.SurfaceControlHdrLayerInfoListener;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.display.DisplayDeviceConfig.HighBrightnessModeData;
import com.android.server.display.DisplayManagerService.Clock;

import java.io.PrintWriter;
import java.util.Iterator;
import java.util.LinkedList;

/**
 * Controls the status of high-brightness mode for devices that support it. This class assumes that
 * an instance is always created even if a device does not support high-brightness mode (HBM); in
 * the case where it is not supported, the majority of the logic is skipped. On devices that support
 * HBM, we keep track of the ambient lux as well as historical usage of HBM to determine when HBM is
 * allowed and not. This class's output is simply a brightness-range maximum value (queried via
 * {@link #getCurrentBrightnessMax}) that changes depending on whether HBM is enabled or not.
 */
class HighBrightnessModeController {
    private static final String TAG = "HighBrightnessModeController";

    private static final boolean DEBUG = false;

    private final float mBrightnessMin;
    private final float mBrightnessMax;
    private final Handler mHandler;
    private final Runnable mHbmChangeCallback;
    private final Runnable mRecalcRunnable;
    private final Clock mClock;

    private SurfaceControlHdrLayerInfoListener mHdrListener;
    private HighBrightnessModeData mHbmData;
    private IBinder mRegisteredDisplayToken;

    private boolean mIsInAllowedAmbientRange = false;
    private boolean mIsTimeAvailable = false;
    private boolean mIsAutoBrightnessEnabled = false;
    private float mAutoBrightness;
    private int mHbmMode = BrightnessInfo.HIGH_BRIGHTNESS_MODE_OFF;
    private boolean mIsHdrLayerPresent = false;

    /**
     * If HBM is currently running, this is the start time for the current HBM session.
     */
    private long mRunningStartTimeMillis = -1;

    /**
     * List of previous HBM-events ordered from most recent to least recent.
     * Meant to store only the events that fall into the most recent
     * {@link mHbmData.timeWindowSecs}.
     */
    private LinkedList<HbmEvent> mEvents = new LinkedList<>();

    HighBrightnessModeController(Handler handler, IBinder displayToken, float brightnessMin,
            float brightnessMax, HighBrightnessModeData hbmData, Runnable hbmChangeCallback) {
        this(SystemClock::uptimeMillis, handler, displayToken, brightnessMin, brightnessMax,
                hbmData, hbmChangeCallback);
    }

    @VisibleForTesting
    HighBrightnessModeController(Clock clock, Handler handler, IBinder displayToken,
            float brightnessMin, float brightnessMax, HighBrightnessModeData hbmData,
            Runnable hbmChangeCallback) {
        mClock = clock;
        mHandler = handler;
        mBrightnessMin = brightnessMin;
        mBrightnessMax = brightnessMax;
        mHbmChangeCallback = hbmChangeCallback;
        mAutoBrightness = PowerManager.BRIGHTNESS_INVALID_FLOAT;
        mRecalcRunnable = this::recalculateTimeAllowance;
        mHdrListener = new HdrListener();

        resetHbmData(displayToken, hbmData);
    }

    void setAutoBrightnessEnabled(boolean isEnabled) {
        if (!deviceSupportsHbm() || isEnabled == mIsAutoBrightnessEnabled) {
            return;
        }
        if (DEBUG) {
            Slog.d(TAG, "setAutoBrightness( " + isEnabled + " )");
        }
        mIsAutoBrightnessEnabled = isEnabled;
        mIsInAllowedAmbientRange = false; // reset when auto-brightness switches
        recalculateTimeAllowance();
    }

    float getCurrentBrightnessMin() {
        return mBrightnessMin;
    }

    float getCurrentBrightnessMax() {
        if (!deviceSupportsHbm() || isCurrentlyAllowed()) {
            // Either the device doesn't support HBM, or HBM range is currently allowed (device
            // it in a high-lux environment). In either case, return the highest brightness
            // level supported by the device.
            return mBrightnessMax;
        } else {
            // Hbm is not allowed, only allow up to the brightness where we
            // transition to high brightness mode.
            return mHbmData.transitionPoint;
        }
    }

    float getHdrBrightnessValue() {
        return mBrightnessMax;
    }

    void onAmbientLuxChange(float ambientLux) {
        if (!deviceSupportsHbm() || !mIsAutoBrightnessEnabled) {
            return;
        }

        final boolean isHighLux = (ambientLux >= mHbmData.minimumLux);
        if (isHighLux != mIsInAllowedAmbientRange) {
            mIsInAllowedAmbientRange = isHighLux;
            recalculateTimeAllowance();
        }
    }

    void onAutoBrightnessChanged(float autoBrightness) {
        if (!deviceSupportsHbm()) {
            return;
        }
        final float oldAutoBrightness = mAutoBrightness;
        mAutoBrightness = autoBrightness;

        // If we are starting or ending a high brightness mode session, store the current
        // session in mRunningStartTimeMillis, or the old one in mEvents.
        final boolean wasHbmDrainingAvailableTime = mRunningStartTimeMillis != -1;
        final boolean shouldHbmDrainAvailableTime = mAutoBrightness > mHbmData.transitionPoint
                && !mIsHdrLayerPresent;
        if (wasHbmDrainingAvailableTime != shouldHbmDrainAvailableTime) {
            final long currentTime = mClock.uptimeMillis();
            if (shouldHbmDrainAvailableTime) {
                mRunningStartTimeMillis = currentTime;
            } else {
                mEvents.addFirst(new HbmEvent(mRunningStartTimeMillis, currentTime));
                mRunningStartTimeMillis = -1;

                if (DEBUG) {
                    Slog.d(TAG, "New HBM event: " + mEvents.getFirst());
                }
            }
        }

        recalculateTimeAllowance();
    }

    int getHighBrightnessMode() {
        return mHbmMode;
    }

    void stop() {
        registerHdrListener(null /*displayToken*/);
    }

    void resetHbmData(IBinder displayToken, HighBrightnessModeData hbmData) {
        mHbmData = hbmData;
        unregisterHdrListener();
        if (deviceSupportsHbm()) {
            registerHdrListener(displayToken);
            recalculateTimeAllowance();
        }
    }

    void dump(PrintWriter pw) {
        pw.println("HighBrightnessModeController:");
        pw.println("  mCurrentMin=" + getCurrentBrightnessMin());
        pw.println("  mCurrentMax=" + getCurrentBrightnessMax());
        pw.println("  mHbmMode=" + BrightnessInfo.hbmToString(mHbmMode));
        pw.println("  remainingTime=" + calculateRemainingTime(mClock.uptimeMillis()));
        pw.println("  mHbmData=" + mHbmData);
        pw.println("  mIsInAllowedAmbientRange=" + mIsInAllowedAmbientRange);
        pw.println("  mIsTimeAvailable= " + mIsTimeAvailable);
        pw.println("  mIsAutoBrightnessEnabled=" + mIsAutoBrightnessEnabled);
        pw.println("  mAutoBrightness=" + mAutoBrightness);
        pw.println("  mIsHdrLayerPresent=" + mIsHdrLayerPresent);
        pw.println("  mBrightnessMin=" + mBrightnessMin);
        pw.println("  mBrightnessMax=" + mBrightnessMax);
    }

    private boolean isCurrentlyAllowed() {
        return mIsHdrLayerPresent
                || (mIsAutoBrightnessEnabled && mIsTimeAvailable && mIsInAllowedAmbientRange);
    }

    private boolean deviceSupportsHbm() {
        return mHbmData != null;
    }

    private long calculateRemainingTime(long currentTime) {
        if (!deviceSupportsHbm()) {
            return 0;
        }

        long timeAlreadyUsed = 0;

        // First, lets see how much time we've taken for any currently running
        // session of HBM.
        if (mRunningStartTimeMillis > 0) {
            if (mRunningStartTimeMillis > currentTime) {
                Slog.e(TAG, "Start time set to the future. curr: " + currentTime
                        + ", start: " + mRunningStartTimeMillis);
                mRunningStartTimeMillis = currentTime;
            }
            timeAlreadyUsed = currentTime - mRunningStartTimeMillis;
        }

        if (DEBUG) {
            Slog.d(TAG, "Time already used after current session: " + timeAlreadyUsed);
        }

        // Next, lets iterate through the history of previous sessions and add those times.
        final long windowstartTimeMillis = currentTime - mHbmData.timeWindowMillis;
        Iterator<HbmEvent> it = mEvents.iterator();
        while (it.hasNext()) {
            final HbmEvent event = it.next();

            // If this event ended before the current Timing window, discard forever and ever.
            if (event.endTimeMillis < windowstartTimeMillis) {
                it.remove();
                continue;
            }

            final long startTimeMillis = Math.max(event.startTimeMillis, windowstartTimeMillis);
            timeAlreadyUsed += event.endTimeMillis - startTimeMillis;
        }

        if (DEBUG) {
            Slog.d(TAG, "Time already used after all sessions: " + timeAlreadyUsed);
        }

        return Math.max(0, mHbmData.timeMaxMillis - timeAlreadyUsed);
    }

    /**
     * Recalculates the allowable HBM time.
     */
    private void recalculateTimeAllowance() {
        final long currentTime = mClock.uptimeMillis();
        final long remainingTime = calculateRemainingTime(currentTime);

        // We allow HBM if there is more than the minimum required time available
        // or if brightness is already in the high range, if there is any time left at all.
        final boolean isAllowedWithoutRestrictions = remainingTime >= mHbmData.timeMinMillis;
        final boolean isOnlyAllowedToStayOn = !isAllowedWithoutRestrictions
                && remainingTime > 0 && mAutoBrightness > mHbmData.transitionPoint;
        mIsTimeAvailable = isAllowedWithoutRestrictions || isOnlyAllowedToStayOn;

        // Calculate the time at which we want to recalculate mIsTimeAvailable in case a lux or
        // brightness change doesn't happen before then.
        long nextTimeout = -1;
        if (mAutoBrightness > mHbmData.transitionPoint) {
            // if we're in high-lux now, timeout when we run out of allowed time.
            nextTimeout = currentTime + remainingTime;
        } else if (!mIsTimeAvailable && mEvents.size() > 0) {
            // If we are not allowed...timeout when the oldest event moved outside of the timing
            // window by at least minTime. Basically, we're calculating the soonest time we can
            // get {@code timeMinMillis} back to us.
            final long windowstartTimeMillis = currentTime - mHbmData.timeWindowMillis;
            final HbmEvent lastEvent = mEvents.getLast();
            final long startTimePlusMinMillis =
                    Math.max(windowstartTimeMillis, lastEvent.startTimeMillis)
                    + mHbmData.timeMinMillis;
            final long timeWhenMinIsGainedBack =
                    currentTime + (startTimePlusMinMillis - windowstartTimeMillis) - remainingTime;
            nextTimeout = timeWhenMinIsGainedBack;
        }

        if (DEBUG) {
            Slog.d(TAG, "HBM recalculated.  IsAllowedWithoutRestrictions: "
                    + isAllowedWithoutRestrictions
                    + ", isOnlyAllowedToStayOn: " + isOnlyAllowedToStayOn
                    + ", remainingAllowedTime: " + remainingTime
                    + ", isLuxHigh: " + mIsInAllowedAmbientRange
                    + ", isHBMCurrentlyAllowed: " + isCurrentlyAllowed()
                    + ", brightness: " + mAutoBrightness
                    + ", RunningStartTimeMillis: " + mRunningStartTimeMillis
                    + ", nextTimeout: " + (nextTimeout != -1 ? (nextTimeout - currentTime) : -1)
                    + ", events: " + mEvents);
        }

        if (nextTimeout != -1) {
            mHandler.removeCallbacks(mRecalcRunnable);
            mHandler.postAtTime(mRecalcRunnable, nextTimeout + 1);
        }

        // Update the state of the world
        int newHbmMode = calculateHighBrightnessMode();
        if (mHbmMode != newHbmMode) {
            mHbmMode = newHbmMode;
            mHbmChangeCallback.run();
        }
    }

    private int calculateHighBrightnessMode() {
        if (!deviceSupportsHbm()) {
            return BrightnessInfo.HIGH_BRIGHTNESS_MODE_OFF;
        } else if (mIsHdrLayerPresent) {
            return BrightnessInfo.HIGH_BRIGHTNESS_MODE_HDR;
        } else if (isCurrentlyAllowed()) {
            return BrightnessInfo.HIGH_BRIGHTNESS_MODE_SUNLIGHT;
        }

        return BrightnessInfo.HIGH_BRIGHTNESS_MODE_OFF;
    }

    private void registerHdrListener(IBinder displayToken) {
        if (mRegisteredDisplayToken == displayToken) {
            return;
        }

        unregisterHdrListener();
        mRegisteredDisplayToken = displayToken;
        if (mRegisteredDisplayToken != null) {
            mHdrListener.register(mRegisteredDisplayToken);
        }
    }

    private void unregisterHdrListener() {
        if (mRegisteredDisplayToken != null) {
            mHdrListener.unregister(mRegisteredDisplayToken);
            mIsHdrLayerPresent = false;
        }
    }

    /**
     * Represents an event in which High Brightness Mode was enabled.
     */
    private static class HbmEvent {
        public long startTimeMillis;
        public long endTimeMillis;

        HbmEvent(long startTimeMillis, long endTimeMillis) {
            this.startTimeMillis = startTimeMillis;
            this.endTimeMillis = endTimeMillis;
        }

        @Override
        public String toString() {
            return "[Event: {" + startTimeMillis + ", " + endTimeMillis + "}, total: "
                    + ((endTimeMillis - startTimeMillis) / 1000) + "]";
        }
    }

    private class HdrListener extends SurfaceControlHdrLayerInfoListener {
        @Override
        public void onHdrInfoChanged(IBinder displayToken, int numberOfHdrLayers,
                int maxW, int maxH, int flags) {
            mHandler.post(() -> {
                mIsHdrLayerPresent = numberOfHdrLayers > 0;
                // Calling the auto-brightness update so that we can recalculate
                // auto-brightness with HDR in mind. When HDR layers are present,
                // we don't limit auto-brightness' HBM time limits.
                onAutoBrightnessChanged(mAutoBrightness);
            });
        }
    }
}
