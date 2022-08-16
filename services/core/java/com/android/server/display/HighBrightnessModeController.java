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

import android.content.Context;
import android.database.ContentObserver;
import android.hardware.display.BrightnessInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.IThermalEventListener;
import android.os.IThermalService;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.Temperature;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.MathUtils;
import android.util.Slog;
import android.util.TimeUtils;
import android.view.SurfaceControlHdrLayerInfoListener;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FrameworkStatsLog;
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

    @VisibleForTesting
    static final float HBM_TRANSITION_POINT_INVALID = Float.POSITIVE_INFINITY;

    public interface HdrBrightnessDeviceConfig {
        float getHdrBrightnessFromSdr(float sdrBrightness);
    }

    private final float mBrightnessMin;
    private final float mBrightnessMax;
    private final Handler mHandler;
    private final Runnable mHbmChangeCallback;
    private final Runnable mRecalcRunnable;
    private final Clock mClock;
    private final SkinThermalStatusObserver mSkinThermalStatusObserver;
    private final Context mContext;
    private final SettingsObserver mSettingsObserver;
    private final Injector mInjector;

    private HdrListener mHdrListener;
    private HighBrightnessModeData mHbmData;
    private HdrBrightnessDeviceConfig mHdrBrightnessCfg;
    private IBinder mRegisteredDisplayToken;

    private boolean mIsInAllowedAmbientRange = false;
    private boolean mIsTimeAvailable = false;
    private boolean mIsAutoBrightnessEnabled = false;
    private boolean mIsAutoBrightnessOffByState = false;

    // The following values are typically reported by DisplayPowerController.
    // This value includes brightness throttling effects.
    private float mBrightness;
    // This value excludes brightness throttling effects.
    private float mUnthrottledBrightness;
    private @BrightnessInfo.BrightnessMaxReason int mThrottlingReason =
        BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE;

    private int mHbmMode = BrightnessInfo.HIGH_BRIGHTNESS_MODE_OFF;
    private boolean mIsHdrLayerPresent = false;
    private boolean mIsThermalStatusWithinLimit = true;
    private boolean mIsBlockedByLowPowerMode = false;
    private int mWidth;
    private int mHeight;
    private float mAmbientLux;
    private int mDisplayStatsId;
    private int mHbmStatsState = FrameworkStatsLog.DISPLAY_HBM_STATE_CHANGED__STATE__HBM_OFF;

    /**
     * If HBM is currently running, this is the start time for the current HBM session.
     */
    private long mRunningStartTimeMillis = -1;

    /**
     * List of previous HBM-events ordered from most recent to least recent.
     * Meant to store only the events that fall into the most recent
     * {@link mHbmData.timeWindowMillis}.
     */
    private LinkedList<HbmEvent> mEvents = new LinkedList<>();

    HighBrightnessModeController(Handler handler, int width, int height, IBinder displayToken,
            String displayUniqueId, float brightnessMin, float brightnessMax,
            HighBrightnessModeData hbmData, HdrBrightnessDeviceConfig hdrBrightnessCfg,
            Runnable hbmChangeCallback, Context context) {
        this(new Injector(), handler, width, height, displayToken, displayUniqueId, brightnessMin,
            brightnessMax, hbmData, hdrBrightnessCfg, hbmChangeCallback, context);
    }

    @VisibleForTesting
    HighBrightnessModeController(Injector injector, Handler handler, int width, int height,
            IBinder displayToken, String displayUniqueId, float brightnessMin, float brightnessMax,
            HighBrightnessModeData hbmData, HdrBrightnessDeviceConfig hdrBrightnessCfg,
            Runnable hbmChangeCallback, Context context) {
        mInjector = injector;
        mContext = context;
        mClock = injector.getClock();
        mHandler = handler;
        mBrightness = brightnessMin;
        mBrightnessMin = brightnessMin;
        mBrightnessMax = brightnessMax;
        mHbmChangeCallback = hbmChangeCallback;
        mSkinThermalStatusObserver = new SkinThermalStatusObserver(mInjector, mHandler);
        mSettingsObserver = new SettingsObserver(mHandler);
        mRecalcRunnable = this::recalculateTimeAllowance;
        mHdrListener = new HdrListener();

        resetHbmData(width, height, displayToken, displayUniqueId, hbmData, hdrBrightnessCfg);
    }

    void setAutoBrightnessEnabled(int state) {
        final boolean isEnabled = state == AutomaticBrightnessController.AUTO_BRIGHTNESS_ENABLED;
        mIsAutoBrightnessOffByState =
                state == AutomaticBrightnessController.AUTO_BRIGHTNESS_OFF_DUE_TO_DISPLAY_STATE;
        if (!deviceSupportsHbm() || isEnabled == mIsAutoBrightnessEnabled) {
            return;
        }
        if (DEBUG) {
            Slog.d(TAG, "setAutoBrightnessEnabled( " + isEnabled + " )");
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

    float getNormalBrightnessMax() {
        return deviceSupportsHbm() ? mHbmData.transitionPoint : mBrightnessMax;
    }

    float getHdrBrightnessValue() {
        if (mHdrBrightnessCfg != null) {
            float hdrBrightness = mHdrBrightnessCfg.getHdrBrightnessFromSdr(mBrightness);
            if (hdrBrightness != PowerManager.BRIGHTNESS_INVALID) {
                return hdrBrightness;
            }
        }

        // For HDR brightness, we take the current brightness and scale it to the max. The reason
        // we do this is because we want brightness to go to HBM max when it would normally go
        // to normal max, meaning it should not wait to go to 10000 lux (or whatever the transition
        // point happens to be) in order to go full HDR. Likewise, HDR on manual brightness should
        // automatically scale the brightness without forcing the user to adjust to higher values.
        return MathUtils.map(getCurrentBrightnessMin(), getCurrentBrightnessMax(),
                mBrightnessMin, mBrightnessMax, mBrightness);
    }

    void onAmbientLuxChange(float ambientLux) {
        mAmbientLux = ambientLux;
        if (!deviceSupportsHbm() || !mIsAutoBrightnessEnabled) {
            return;
        }

        final boolean isHighLux = (ambientLux >= mHbmData.minimumLux);
        if (isHighLux != mIsInAllowedAmbientRange) {
            mIsInAllowedAmbientRange = isHighLux;
            recalculateTimeAllowance();
        }
    }

    void onBrightnessChanged(float brightness, float unthrottledBrightness,
            @BrightnessInfo.BrightnessMaxReason int throttlingReason) {
        if (!deviceSupportsHbm()) {
            return;
        }
        mBrightness = brightness;
        mUnthrottledBrightness = unthrottledBrightness;
        mThrottlingReason = throttlingReason;

        // If we are starting or ending a high brightness mode session, store the current
        // session in mRunningStartTimeMillis, or the old one in mEvents.
        final boolean wasHbmDrainingAvailableTime = mRunningStartTimeMillis != -1;
        final boolean shouldHbmDrainAvailableTime = mBrightness > mHbmData.transitionPoint
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

    float getTransitionPoint() {
        if (deviceSupportsHbm()) {
            return mHbmData.transitionPoint;
        } else {
            return HBM_TRANSITION_POINT_INVALID;
        }
    }

    void stop() {
        registerHdrListener(null /*displayToken*/);
        mSkinThermalStatusObserver.stopObserving();
        mSettingsObserver.stopObserving();
    }

    void resetHbmData(int width, int height, IBinder displayToken, String displayUniqueId,
            HighBrightnessModeData hbmData, HdrBrightnessDeviceConfig hdrBrightnessCfg) {
        mWidth = width;
        mHeight = height;
        mHbmData = hbmData;
        mHdrBrightnessCfg = hdrBrightnessCfg;
        mDisplayStatsId = displayUniqueId.hashCode();

        unregisterHdrListener();
        mSkinThermalStatusObserver.stopObserving();
        mSettingsObserver.stopObserving();
        if (deviceSupportsHbm()) {
            registerHdrListener(displayToken);
            recalculateTimeAllowance();
            if (mHbmData.thermalStatusLimit > PowerManager.THERMAL_STATUS_NONE) {
                mIsThermalStatusWithinLimit = true;
                mSkinThermalStatusObserver.startObserving();
            }
            if (!mHbmData.allowInLowPowerMode) {
                mIsBlockedByLowPowerMode = false;
                mSettingsObserver.startObserving();
            }
        }
    }

    void dump(PrintWriter pw) {
        mHandler.runWithScissors(() -> dumpLocal(pw), 1000);
    }

    @VisibleForTesting
    HdrListener getHdrListener() {
        return mHdrListener;
    }

    private void dumpLocal(PrintWriter pw) {
        pw.println("HighBrightnessModeController:");
        pw.println("  mBrightness=" + mBrightness);
        pw.println("  mUnthrottledBrightness=" + mUnthrottledBrightness);
        pw.println("  mThrottlingReason=" + BrightnessInfo.briMaxReasonToString(mThrottlingReason));
        pw.println("  mCurrentMin=" + getCurrentBrightnessMin());
        pw.println("  mCurrentMax=" + getCurrentBrightnessMax());
        pw.println("  mHbmMode=" + BrightnessInfo.hbmToString(mHbmMode)
                + (mHbmMode == BrightnessInfo.HIGH_BRIGHTNESS_MODE_HDR
                ? "(" + getHdrBrightnessValue() + ")" : ""));
        pw.println("  mHbmStatsState=" + hbmStatsStateToString(mHbmStatsState));
        pw.println("  mHbmData=" + mHbmData);
        pw.println("  mAmbientLux=" + mAmbientLux
                + (mIsAutoBrightnessEnabled ? "" : " (old/invalid)"));
        pw.println("  mIsInAllowedAmbientRange=" + mIsInAllowedAmbientRange);
        pw.println("  mIsAutoBrightnessEnabled=" + mIsAutoBrightnessEnabled);
        pw.println("  mIsAutoBrightnessOffByState=" + mIsAutoBrightnessOffByState);
        pw.println("  mIsHdrLayerPresent=" + mIsHdrLayerPresent);
        pw.println("  mBrightnessMin=" + mBrightnessMin);
        pw.println("  mBrightnessMax=" + mBrightnessMax);
        pw.println("  remainingTime=" + calculateRemainingTime(mClock.uptimeMillis()));
        pw.println("  mIsTimeAvailable= " + mIsTimeAvailable);
        pw.println("  mRunningStartTimeMillis=" + TimeUtils.formatUptime(mRunningStartTimeMillis));
        pw.println("  mIsThermalStatusWithinLimit=" + mIsThermalStatusWithinLimit);
        pw.println("  mIsBlockedByLowPowerMode=" + mIsBlockedByLowPowerMode);
        pw.println("  width*height=" + mWidth + "*" + mHeight);
        pw.println("  mEvents=");
        final long currentTime = mClock.uptimeMillis();
        long lastStartTime = currentTime;
        if (mRunningStartTimeMillis != -1) {
            lastStartTime = dumpHbmEvent(pw, new HbmEvent(mRunningStartTimeMillis, currentTime));
        }
        for (HbmEvent event : mEvents) {
            if (lastStartTime > event.endTimeMillis) {
                pw.println("    event: [normal brightness]: "
                        + TimeUtils.formatDuration(lastStartTime - event.endTimeMillis));
            }
            lastStartTime = dumpHbmEvent(pw, event);
        }

        mSkinThermalStatusObserver.dump(pw);
    }

    private long dumpHbmEvent(PrintWriter pw, HbmEvent event) {
        final long duration = event.endTimeMillis - event.startTimeMillis;
        pw.println("    event: ["
                + TimeUtils.formatUptime(event.startTimeMillis) + ", "
                + TimeUtils.formatUptime(event.endTimeMillis) + "] ("
                + TimeUtils.formatDuration(duration) + ")");
        return event.startTimeMillis;
    }

    private boolean isCurrentlyAllowed() {
        // Returns true if HBM is allowed (above the ambient lux threshold) and there's still
        // time within the current window for additional HBM usage. We return false if there is an
        // HDR layer because we don't want the brightness MAX to change for HDR, which has its
        // brightness scaled in a different way than sunlight HBM that doesn't require changing
        // the MAX. HDR also needs to work under manual brightness which never adjusts the
        // brightness maximum; so we implement HDR-HBM in a way that doesn't adjust the max.
        // See {@link #getHdrBrightnessValue}.
        return !mIsHdrLayerPresent
                && (mIsAutoBrightnessEnabled && mIsTimeAvailable && mIsInAllowedAmbientRange
                && mIsThermalStatusWithinLimit && !mIsBlockedByLowPowerMode);
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
                && remainingTime > 0 && mBrightness > mHbmData.transitionPoint;
        mIsTimeAvailable = isAllowedWithoutRestrictions || isOnlyAllowedToStayOn;

        // Calculate the time at which we want to recalculate mIsTimeAvailable in case a lux or
        // brightness change doesn't happen before then.
        long nextTimeout = -1;
        if (mBrightness > mHbmData.transitionPoint) {
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
                    + ", isHdrLayerPresent: " + mIsHdrLayerPresent
                    + ", isAutoBrightnessEnabled: " +  mIsAutoBrightnessEnabled
                    + ", mIsTimeAvailable: " + mIsTimeAvailable
                    + ", mIsInAllowedAmbientRange: " + mIsInAllowedAmbientRange
                    + ", mIsThermalStatusWithinLimit: " + mIsThermalStatusWithinLimit
                    + ", mIsBlockedByLowPowerMode: " + mIsBlockedByLowPowerMode
                    + ", mBrightness: " + mBrightness
                    + ", mUnthrottledBrightness: " + mUnthrottledBrightness
                    + ", mThrottlingReason: "
                        + BrightnessInfo.briMaxReasonToString(mThrottlingReason)
                    + ", RunningStartTimeMillis: " + mRunningStartTimeMillis
                    + ", nextTimeout: " + (nextTimeout != -1 ? (nextTimeout - currentTime) : -1)
                    + ", events: " + mEvents);
        }

        if (nextTimeout != -1) {
            mHandler.removeCallbacks(mRecalcRunnable);
            mHandler.postAtTime(mRecalcRunnable, nextTimeout + 1);
        }
        // Update the state of the world
        updateHbmMode();
    }

    private void updateHbmMode() {
        int newHbmMode = calculateHighBrightnessMode();
        updateHbmStats(newHbmMode);
        if (mHbmMode != newHbmMode) {
            mHbmMode = newHbmMode;
            mHbmChangeCallback.run();
        }
    }

    private void updateHbmStats(int newMode) {
        final float transitionPoint = mHbmData.transitionPoint;
        int state = FrameworkStatsLog.DISPLAY_HBM_STATE_CHANGED__STATE__HBM_OFF;
        if (newMode == BrightnessInfo.HIGH_BRIGHTNESS_MODE_HDR
                && getHdrBrightnessValue() > transitionPoint) {
            state = FrameworkStatsLog.DISPLAY_HBM_STATE_CHANGED__STATE__HBM_ON_HDR;
        } else if (newMode == BrightnessInfo.HIGH_BRIGHTNESS_MODE_SUNLIGHT
                && mBrightness > transitionPoint) {
            state = FrameworkStatsLog.DISPLAY_HBM_STATE_CHANGED__STATE__HBM_ON_SUNLIGHT;
        }
        if (state == mHbmStatsState) {
            return;
        }

        int reason =
                FrameworkStatsLog.DISPLAY_HBM_STATE_CHANGED__REASON__HBM_TRANSITION_REASON_UNKNOWN;
        final boolean oldHbmSv = (mHbmStatsState
                == FrameworkStatsLog.DISPLAY_HBM_STATE_CHANGED__STATE__HBM_ON_SUNLIGHT);
        final boolean newHbmSv =
                (state == FrameworkStatsLog.DISPLAY_HBM_STATE_CHANGED__STATE__HBM_ON_SUNLIGHT);
        if (oldHbmSv && !newHbmSv) {
            // HighBrightnessModeController (HBMC) currently supports throttling from two sources:
            //     1. Internal, received from HBMC.SkinThermalStatusObserver.notifyThrottling()
            //     2. External, received from HBMC.onBrightnessChanged()
            // TODO(b/216373254): Deprecate internal throttling source
            final boolean internalThermalThrottling = !mIsThermalStatusWithinLimit;
            final boolean externalThermalThrottling =
                mUnthrottledBrightness > transitionPoint && // We would've liked HBM brightness...
                mBrightness <= transitionPoint &&           // ...but we got NBM, because of...
                mThrottlingReason == BrightnessInfo.BRIGHTNESS_MAX_REASON_THERMAL; // ...thermals.

            // If more than one conditions are flipped and turn off HBM sunlight
            // visibility, only one condition will be reported to make it simple.
            if (!mIsAutoBrightnessEnabled && mIsAutoBrightnessOffByState) {
                reason = FrameworkStatsLog
                                 .DISPLAY_HBM_STATE_CHANGED__REASON__HBM_SV_OFF_DISPLAY_OFF;
            } else if (!mIsAutoBrightnessEnabled) {
                reason = FrameworkStatsLog
                                 .DISPLAY_HBM_STATE_CHANGED__REASON__HBM_SV_OFF_AUTOBRIGHTNESS_OFF;
            } else if (!mIsInAllowedAmbientRange) {
                reason = FrameworkStatsLog.DISPLAY_HBM_STATE_CHANGED__REASON__HBM_SV_OFF_LUX_DROP;
            } else if (!mIsTimeAvailable) {
                reason = FrameworkStatsLog.DISPLAY_HBM_STATE_CHANGED__REASON__HBM_SV_OFF_TIME_LIMIT;
            } else if (internalThermalThrottling || externalThermalThrottling) {
                reason = FrameworkStatsLog
                                 .DISPLAY_HBM_STATE_CHANGED__REASON__HBM_SV_OFF_THERMAL_LIMIT;
            } else if (mIsHdrLayerPresent) {
                reason = FrameworkStatsLog
                                 .DISPLAY_HBM_STATE_CHANGED__REASON__HBM_SV_OFF_HDR_PLAYING;
            } else if (mIsBlockedByLowPowerMode) {
                reason = FrameworkStatsLog
                                 .DISPLAY_HBM_STATE_CHANGED__REASON__HBM_SV_OFF_BATTERY_SAVE_ON;
            } else if (mBrightness <= mHbmData.transitionPoint) {
                // This must be after external thermal check.
                reason = FrameworkStatsLog
                            .DISPLAY_HBM_STATE_CHANGED__REASON__HBM_SV_OFF_LOW_REQUESTED_BRIGHTNESS;
            }
        }

        mInjector.reportHbmStateChange(mDisplayStatsId, state, reason);
        mHbmStatsState = state;
    }

    private String hbmStatsStateToString(int hbmStatsState) {
        switch (hbmStatsState) {
        case FrameworkStatsLog.DISPLAY_HBM_STATE_CHANGED__STATE__HBM_OFF:
            return "HBM_OFF";
        case FrameworkStatsLog.DISPLAY_HBM_STATE_CHANGED__STATE__HBM_ON_HDR:
            return "HBM_ON_HDR";
        case FrameworkStatsLog.DISPLAY_HBM_STATE_CHANGED__STATE__HBM_ON_SUNLIGHT:
            return "HBM_ON_SUNLIGHT";
        default:
            return String.valueOf(hbmStatsState);
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

    @VisibleForTesting
    class HdrListener extends SurfaceControlHdrLayerInfoListener {
        @Override
        public void onHdrInfoChanged(IBinder displayToken, int numberOfHdrLayers,
                int maxW, int maxH, int flags) {
            mHandler.post(() -> {
                mIsHdrLayerPresent = numberOfHdrLayers > 0
                        && (float) (maxW * maxH) >= ((float) (mWidth * mHeight)
                                   * mHbmData.minimumHdrPercentOfScreen);
                // Calling the brightness update so that we can recalculate
                // brightness with HDR in mind.
                onBrightnessChanged(mBrightness, mUnthrottledBrightness, mThrottlingReason);
            });
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
                Slog.d(TAG, "New thermal throttling status "
                        + ", current thermal status = " + temp.getStatus()
                        + ", threshold = " + mHbmData.thermalStatusLimit);
            }
            mHandler.post(() -> {
                mIsThermalStatusWithinLimit = temp.getStatus() <= mHbmData.thermalStatusLimit;
                // This recalculates HbmMode and runs mHbmChangeCallback if the mode has changed
                updateHbmMode();
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
                Slog.w(TAG, "Could not observe thermal status. Service not available");
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
            mIsThermalStatusWithinLimit = true;
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

    private final class SettingsObserver extends ContentObserver {
        private final Uri mLowPowerModeSetting = Settings.Global.getUriFor(
                Settings.Global.LOW_POWER_MODE);
        private boolean mStarted;

        SettingsObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            updateLowPower();
        }

        void startObserving() {
            if (!mStarted) {
                mContext.getContentResolver().registerContentObserver(mLowPowerModeSetting,
                        false /*notifyForDescendants*/, this, UserHandle.USER_ALL);
                mStarted = true;
                updateLowPower();
            }
        }

        void stopObserving() {
            mIsBlockedByLowPowerMode = false;
            if (mStarted) {
                mContext.getContentResolver().unregisterContentObserver(this);
                mStarted = false;
            }
        }

        private void updateLowPower() {
            final boolean isLowPowerMode = isLowPowerMode();
            if (isLowPowerMode == mIsBlockedByLowPowerMode) {
                return;
            }
            if (DEBUG) {
                Slog.d(TAG, "Settings.Global.LOW_POWER_MODE enabled: " + isLowPowerMode);
            }
            mIsBlockedByLowPowerMode = isLowPowerMode;
            // this recalculates HbmMode and runs mHbmChangeCallback if the mode has changed
            updateHbmMode();
        }

        private boolean isLowPowerMode() {
            return Settings.Global.getInt(
                    mContext.getContentResolver(), Settings.Global.LOW_POWER_MODE, 0) != 0;
        }
    }

    public static class Injector {
        public Clock getClock() {
            return SystemClock::uptimeMillis;
        }

        public IThermalService getThermalService() {
            return IThermalService.Stub.asInterface(
                    ServiceManager.getService(Context.THERMAL_SERVICE));
        }

        public void reportHbmStateChange(int display, int state, int reason) {
            FrameworkStatsLog.write(
                    FrameworkStatsLog.DISPLAY_HBM_STATE_CHANGED, display, state, reason);
        }
    }
}
