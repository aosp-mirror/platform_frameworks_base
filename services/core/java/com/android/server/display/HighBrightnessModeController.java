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

import android.annotation.Nullable;
import android.content.Context;
import android.database.ContentObserver;
import android.hardware.display.BrightnessInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.MathUtils;
import android.util.Slog;
import android.util.TimeUtils;
import android.view.SurfaceControlHdrLayerInfoListener;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.display.BrightnessSynchronizer;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.display.DisplayDeviceConfig.HighBrightnessModeData;
import com.android.server.display.DisplayManagerService.Clock;
import com.android.server.display.utils.DebugUtils;

import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.Iterator;

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

    // To enable these logs, run:
    // 'adb shell setprop persist.log.tag.HighBrightnessModeController DEBUG && adb reboot'
    private static final boolean DEBUG = DebugUtils.isDebuggable(TAG);

    @VisibleForTesting
    static final float HBM_TRANSITION_POINT_INVALID = Float.POSITIVE_INFINITY;

    private static final float DEFAULT_MAX_DESIRED_HDR_SDR_RATIO = 1.0f;

    public interface HdrBrightnessDeviceConfig {
        // maxDesiredHdrSdrRatio will restrict the HDR brightness if the ratio is less than
        // Float.POSITIVE_INFINITY
        float getHdrBrightnessFromSdr(float sdrBrightness, float maxDesiredHdrSdrRatio);
    }

    private final float mBrightnessMin;
    private final float mBrightnessMax;
    private final Handler mHandler;
    private final Runnable mHbmChangeCallback;
    private final Runnable mRecalcRunnable;
    private final Clock mClock;
    private final Context mContext;
    private final SettingsObserver mSettingsObserver;
    private final Injector mInjector;

    private HdrListener mHdrListener;

    @Nullable
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
    // mMaxDesiredHdrSdrRatio should only be applied when there is a valid backlight->nits mapping
    private float mMaxDesiredHdrSdrRatio = DEFAULT_MAX_DESIRED_HDR_SDR_RATIO;
    private boolean mForceHbmChangeCallback = false;
    private boolean mIsBlockedByLowPowerMode = false;
    private int mWidth;
    private int mHeight;
    private float mAmbientLux;
    private int mDisplayStatsId;
    private int mHbmStatsState = FrameworkStatsLog.DISPLAY_HBM_STATE_CHANGED__STATE__HBM_OFF;

    /**
     * If HBM is currently running, this is the start time and set of all events,
     * for the current HBM session.
     */
    @Nullable
    private HighBrightnessModeMetadata mHighBrightnessModeMetadata;

    HighBrightnessModeController(Handler handler, int width, int height, IBinder displayToken,
            String displayUniqueId, float brightnessMin, float brightnessMax,
            HighBrightnessModeData hbmData, HdrBrightnessDeviceConfig hdrBrightnessCfg,
            Runnable hbmChangeCallback, HighBrightnessModeMetadata hbmMetadata, Context context) {
        this(new Injector(), handler, width, height, displayToken, displayUniqueId, brightnessMin,
            brightnessMax, hbmData, hdrBrightnessCfg, hbmChangeCallback, hbmMetadata, context);
    }

    @VisibleForTesting
    HighBrightnessModeController(Injector injector, Handler handler, int width, int height,
            IBinder displayToken, String displayUniqueId, float brightnessMin, float brightnessMax,
            HighBrightnessModeData hbmData, HdrBrightnessDeviceConfig hdrBrightnessCfg,
            Runnable hbmChangeCallback, HighBrightnessModeMetadata hbmMetadata, Context context) {
        mInjector = injector;
        mContext = context;
        mClock = injector.getClock();
        mHandler = handler;
        mBrightness = brightnessMin;
        mBrightnessMin = brightnessMin;
        mBrightnessMax = brightnessMax;
        mHbmChangeCallback = hbmChangeCallback;
        mHighBrightnessModeMetadata = hbmMetadata;
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
        if (!deviceSupportsHbm() || isHbmCurrentlyAllowed()) {
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
            float hdrBrightness = mHdrBrightnessCfg.getHdrBrightnessFromSdr(
                    mBrightness, mMaxDesiredHdrSdrRatio);
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
        final long runningStartTime = mHighBrightnessModeMetadata.getRunningStartTimeMillis();
        final boolean wasHbmDrainingAvailableTime = runningStartTime != -1;
        final boolean shouldHbmDrainAvailableTime = mBrightness > mHbmData.transitionPoint
                && !mIsHdrLayerPresent;
        if (wasHbmDrainingAvailableTime != shouldHbmDrainAvailableTime) {
            final long currentTime = mClock.uptimeMillis();
            if (shouldHbmDrainAvailableTime) {
                mHighBrightnessModeMetadata.setRunningStartTimeMillis(currentTime);
            } else {
                final HbmEvent hbmEvent = new HbmEvent(runningStartTime, currentTime);
                mHighBrightnessModeMetadata.addHbmEvent(hbmEvent);
                mHighBrightnessModeMetadata.setRunningStartTimeMillis(-1);

                if (DEBUG) {
                    Slog.d(TAG, "New HBM event: "
                            + mHighBrightnessModeMetadata.getHbmEventQueue().peekFirst());
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
        mSettingsObserver.stopObserving();
    }

    void setHighBrightnessModeMetadata(HighBrightnessModeMetadata hbmInfo) {
        mHighBrightnessModeMetadata = hbmInfo;
    }

    void resetHbmData(int width, int height, IBinder displayToken, String displayUniqueId,
            HighBrightnessModeData hbmData, HdrBrightnessDeviceConfig hdrBrightnessCfg) {
        mWidth = width;
        mHeight = height;
        mHbmData = hbmData;
        mHdrBrightnessCfg = hdrBrightnessCfg;
        mDisplayStatsId = displayUniqueId.hashCode();

        unregisterHdrListener();
        mSettingsObserver.stopObserving();
        if (deviceSupportsHbm()) {
            registerHdrListener(displayToken);
            recalculateTimeAllowance();
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
        pw.println("  mIsBlockedByLowPowerMode=" + mIsBlockedByLowPowerMode);
        pw.println("  width*height=" + mWidth + "*" + mHeight);

        if (mHighBrightnessModeMetadata != null) {
            pw.println("  mRunningStartTimeMillis="
                    + TimeUtils.formatUptime(
                    mHighBrightnessModeMetadata.getRunningStartTimeMillis()));
            pw.println("  mEvents=");
            final long currentTime = mClock.uptimeMillis();
            long lastStartTime = currentTime;
            long runningStartTimeMillis = mHighBrightnessModeMetadata.getRunningStartTimeMillis();
            if (runningStartTimeMillis != -1) {
                lastStartTime = dumpHbmEvent(pw, new HbmEvent(runningStartTimeMillis, currentTime));
            }
            for (HbmEvent event : mHighBrightnessModeMetadata.getHbmEventQueue()) {
                if (lastStartTime > event.getEndTimeMillis()) {
                    pw.println("    event: [normal brightness]: "
                            + TimeUtils.formatDuration(lastStartTime - event.getEndTimeMillis()));
                }
                lastStartTime = dumpHbmEvent(pw, event);
            }
        } else {
            pw.println("  mHighBrightnessModeMetadata=null");
        }
    }

    private long dumpHbmEvent(PrintWriter pw, HbmEvent event) {
        final long duration = event.getEndTimeMillis() - event.getStartTimeMillis();
        pw.println("    event: ["
                + TimeUtils.formatUptime(event.getStartTimeMillis()) + ", "
                + TimeUtils.formatUptime(event.getEndTimeMillis()) + "] ("
                + TimeUtils.formatDuration(duration) + ")");
        return event.getStartTimeMillis();
    }

    boolean isHbmCurrentlyAllowed() {
        // Returns true if HBM is allowed (above the ambient lux threshold) and there's still
        // time within the current window for additional HBM usage. We return false if there is an
        // HDR layer because we don't want the brightness MAX to change for HDR, which has its
        // brightness scaled in a different way than sunlight HBM that doesn't require changing
        // the MAX. HDR also needs to work under manual brightness which never adjusts the
        // brightness maximum; so we implement HDR-HBM in a way that doesn't adjust the max.
        // See {@link #getHdrBrightnessValue}.
        return !mIsHdrLayerPresent
                && (mIsAutoBrightnessEnabled && mIsTimeAvailable && mIsInAllowedAmbientRange
                && !mIsBlockedByLowPowerMode);
    }

    boolean deviceSupportsHbm() {
        return mHbmData != null && mHighBrightnessModeMetadata != null;
    }

    private long calculateRemainingTime(long currentTime) {
        if (!deviceSupportsHbm()) {
            return 0;
        }

        long timeAlreadyUsed = 0;

        // First, lets see how much time we've taken for any currently running
        // session of HBM.
        long runningStartTimeMillis = mHighBrightnessModeMetadata.getRunningStartTimeMillis();
        if (runningStartTimeMillis > 0) {
            if (runningStartTimeMillis > currentTime) {
                Slog.e(TAG, "Start time set to the future. curr: " + currentTime
                        + ", start: " + runningStartTimeMillis);
                mHighBrightnessModeMetadata.setRunningStartTimeMillis(currentTime);
                runningStartTimeMillis = currentTime;
            }
            timeAlreadyUsed = currentTime - runningStartTimeMillis;
        }

        if (DEBUG) {
            Slog.d(TAG, "Time already used after current session: " + timeAlreadyUsed);
        }

        // Next, lets iterate through the history of previous sessions and add those times.
        final long windowstartTimeMillis = currentTime - mHbmData.timeWindowMillis;
        Iterator<HbmEvent> it = mHighBrightnessModeMetadata.getHbmEventQueue().iterator();
        while (it.hasNext()) {
            final HbmEvent event = it.next();

            // If this event ended before the current Timing window, discard forever and ever.
            if (event.getEndTimeMillis() < windowstartTimeMillis) {
                it.remove();
                continue;
            }

            final long startTimeMillis = Math.max(event.getStartTimeMillis(),
                            windowstartTimeMillis);
            timeAlreadyUsed += event.getEndTimeMillis() - startTimeMillis;
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
        final ArrayDeque<HbmEvent> hbmEvents = mHighBrightnessModeMetadata.getHbmEventQueue();
        if (mBrightness > mHbmData.transitionPoint) {
            // if we're in high-lux now, timeout when we run out of allowed time.
            nextTimeout = currentTime + remainingTime;
        } else if (!mIsTimeAvailable && hbmEvents.size() > 0) {
            // If we are not allowed...timeout when the oldest event moved outside of the timing
            // window by at least minTime. Basically, we're calculating the soonest time we can
            // get {@code timeMinMillis} back to us.
            final long windowstartTimeMillis = currentTime - mHbmData.timeWindowMillis;
            final HbmEvent lastEvent = hbmEvents.peekLast();
            final long startTimePlusMinMillis =
                    Math.max(windowstartTimeMillis, lastEvent.getStartTimeMillis())
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
                    + ", isHBMCurrentlyAllowed: " + isHbmCurrentlyAllowed()
                    + ", isHdrLayerPresent: " + mIsHdrLayerPresent
                    + ", mMaxDesiredHdrSdrRatio: " + mMaxDesiredHdrSdrRatio
                    + ", isAutoBrightnessEnabled: " +  mIsAutoBrightnessEnabled
                    + ", mIsTimeAvailable: " + mIsTimeAvailable
                    + ", mIsInAllowedAmbientRange: " + mIsInAllowedAmbientRange
                    + ", mIsBlockedByLowPowerMode: " + mIsBlockedByLowPowerMode
                    + ", mBrightness: " + mBrightness
                    + ", mUnthrottledBrightness: " + mUnthrottledBrightness
                    + ", mThrottlingReason: "
                        + BrightnessInfo.briMaxReasonToString(mThrottlingReason)
                    + ", RunningStartTimeMillis: "
                        + mHighBrightnessModeMetadata.getRunningStartTimeMillis()
                    + ", nextTimeout: " + (nextTimeout != -1 ? (nextTimeout - currentTime) : -1)
                    + ", events: " + hbmEvents);
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
        if (mHbmMode != newHbmMode || mForceHbmChangeCallback) {
            mForceHbmChangeCallback = false;
            mHbmMode = newHbmMode;
            mHbmChangeCallback.run();
        }
    }

    private void updateHbmStats(int newMode) {
        int state = FrameworkStatsLog.DISPLAY_HBM_STATE_CHANGED__STATE__HBM_OFF;
        if (newMode == BrightnessInfo.HIGH_BRIGHTNESS_MODE_HDR
                && getHdrBrightnessValue() > mHbmData.transitionPoint) {
            state = FrameworkStatsLog.DISPLAY_HBM_STATE_CHANGED__STATE__HBM_ON_HDR;
        } else if (newMode == BrightnessInfo.HIGH_BRIGHTNESS_MODE_SUNLIGHT
                && mBrightness > mHbmData.transitionPoint) {
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
            } else if (isThermalThrottlingActive()) {
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

    @VisibleForTesting
    boolean isThermalThrottlingActive() {
        // We would've liked HBM, but we got NBM (normal brightness mode) because of thermals.
        return mUnthrottledBrightness > mHbmData.transitionPoint
                && mBrightness <= mHbmData.transitionPoint
                && mThrottlingReason == BrightnessInfo.BRIGHTNESS_MAX_REASON_THERMAL;
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
        } else if (isHbmCurrentlyAllowed()) {
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

    @VisibleForTesting
    class HdrListener extends SurfaceControlHdrLayerInfoListener {
        @Override
        public void onHdrInfoChanged(IBinder displayToken, int numberOfHdrLayers,
                int maxW, int maxH, int flags, float maxDesiredHdrSdrRatio) {
            mHandler.post(() -> {
                Trace.traceBegin(Trace.TRACE_TAG_POWER, "HBMController#onHdrInfoChanged");
                mIsHdrLayerPresent = numberOfHdrLayers > 0
                        && (float) (maxW * maxH) >= ((float) (mWidth * mHeight)
                                   * mHbmData.minimumHdrPercentOfScreen);

                float candidateDesiredHdrSdrRatio =
                        mIsHdrLayerPresent && mHdrBrightnessCfg != null
                                ? maxDesiredHdrSdrRatio
                                : DEFAULT_MAX_DESIRED_HDR_SDR_RATIO;

                if (candidateDesiredHdrSdrRatio < 1.0f) {
                    Slog.w(TAG, "Ignoring invalid desired HDR/SDR Ratio: "
                            + candidateDesiredHdrSdrRatio);
                    candidateDesiredHdrSdrRatio = DEFAULT_MAX_DESIRED_HDR_SDR_RATIO;
                }

                if (!BrightnessSynchronizer.floatEquals(
                        mMaxDesiredHdrSdrRatio, candidateDesiredHdrSdrRatio)) {
                    mForceHbmChangeCallback = true;
                    mMaxDesiredHdrSdrRatio = candidateDesiredHdrSdrRatio;
                }

                // Calling the brightness update so that we can recalculate
                // brightness with HDR in mind.
                onBrightnessChanged(mBrightness, mUnthrottledBrightness, mThrottlingReason);
                Trace.traceEnd(Trace.TRACE_TAG_POWER);
            });
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

        public void reportHbmStateChange(int display, int state, int reason) {
            FrameworkStatsLog.write(
                    FrameworkStatsLog.DISPLAY_HBM_STATE_CHANGED, display, state, reason);
        }
    }
}
