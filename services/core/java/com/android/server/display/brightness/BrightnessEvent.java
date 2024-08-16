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

package com.android.server.display.brightness;

import static android.hardware.display.DisplayManagerInternal.DisplayPowerRequest.POLICY_OFF;
import static android.hardware.display.DisplayManagerInternal.DisplayPowerRequest.policyToString;

import static com.android.server.display.AutomaticBrightnessController.AUTO_BRIGHTNESS_MODE_DEFAULT;
import static com.android.server.display.BrightnessMappingStrategy.INVALID_LUX;
import static com.android.server.display.BrightnessMappingStrategy.INVALID_NITS;
import static com.android.server.display.config.DisplayBrightnessMappingConfig.autoBrightnessModeToString;

import android.hardware.display.BrightnessInfo;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Display;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.display.AutomaticBrightnessController;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Represents a particular brightness change event.
 */
public final class BrightnessEvent {
    public static final int FLAG_RBC = 0x1;
    public static final int FLAG_INVALID_LUX = 0x2;
    public static final int FLAG_DOZE_SCALE = 0x4;
    public static final int FLAG_USER_SET = 0x8;
    public static final int FLAG_LOW_POWER_MODE = 0x20;

    private static final SimpleDateFormat FORMAT = new SimpleDateFormat("MM-dd HH:mm:ss.SSS");

    private BrightnessReason mReason = new BrightnessReason();
    private int mDisplayId;
    private String mPhysicalDisplayId;
    private String mPhysicalDisplayName;
    private int mDisplayState;
    @Display.StateReason
    private int mDisplayStateReason;
    private int mDisplayPolicy;
    private long mTime;
    private float mLux;
    private float mNits;
    private float mPercent;
    private float mPreThresholdLux;
    private float mInitialBrightness;
    private float mBrightness;
    private float mUnclampedBrightness;
    private float mRecommendedBrightness;
    private float mPreThresholdBrightness;
    private int mHbmMode;
    private float mHbmMax;
    private int mRbcStrength;
    private float mThermalMax;
    private float mPowerFactor;
    private boolean mWasShortTermModelActive;
    private int mFlags;
    private int mAdjustmentFlags;
    private boolean mAutomaticBrightnessEnabled;
    private String mDisplayBrightnessStrategyName;
    @AutomaticBrightnessController.AutomaticBrightnessMode
    private int mAutoBrightnessMode;

    public BrightnessEvent(BrightnessEvent that) {
        copyFrom(that);
    }

    public BrightnessEvent(int displayId) {
        this.mDisplayId = displayId;
        reset();
    }

    /**
     * A utility to clone a brightness event into another event
     *
     * @param that BrightnessEvent which is to be copied
     */
    public void copyFrom(BrightnessEvent that) {
        mReason.set(that.getReason());
        mDisplayId = that.getDisplayId();
        mPhysicalDisplayId = that.getPhysicalDisplayId();
        mPhysicalDisplayName = that.getPhysicalDisplayName();
        mDisplayState = that.mDisplayState;
        mDisplayStateReason = that.mDisplayStateReason;
        mDisplayPolicy = that.mDisplayPolicy;
        mTime = that.getTime();
        // Lux values
        mLux = that.getLux();
        mPreThresholdLux = that.getPreThresholdLux();
        mNits = that.getNits();
        mPercent = that.getPercent();
        // Brightness values
        mInitialBrightness = that.getInitialBrightness();
        mBrightness = that.getBrightness();
        mUnclampedBrightness = that.getUnclampedBrightness();
        mRecommendedBrightness = that.getRecommendedBrightness();
        mPreThresholdBrightness = that.getPreThresholdBrightness();
        // Different brightness modulations
        mHbmMode = that.getHbmMode();
        mHbmMax = that.getHbmMax();
        mRbcStrength = that.getRbcStrength();
        mThermalMax = that.getThermalMax();
        mPowerFactor = that.getPowerFactor();
        mWasShortTermModelActive = that.wasShortTermModelActive();
        mFlags = that.getFlags();
        mAdjustmentFlags = that.getAdjustmentFlags();
        // Auto-brightness setting
        mAutomaticBrightnessEnabled = that.isAutomaticBrightnessEnabled();
        mDisplayBrightnessStrategyName = that.getDisplayBrightnessStrategyName();
        mAutoBrightnessMode = that.mAutoBrightnessMode;
    }

    /**
     * A utility to reset the BrightnessEvent to default values
     */
    public void reset() {
        mReason = new BrightnessReason();
        mTime = SystemClock.uptimeMillis();
        mPhysicalDisplayId = "";
        mPhysicalDisplayName = "";
        mDisplayState = Display.STATE_UNKNOWN;
        mDisplayStateReason = Display.STATE_REASON_UNKNOWN;
        mDisplayPolicy = POLICY_OFF;
        // Lux values
        mLux = INVALID_LUX;
        mPreThresholdLux = 0;
        mNits = INVALID_NITS;
        mPercent = -1f;
        // Brightness values
        mInitialBrightness = PowerManager.BRIGHTNESS_INVALID_FLOAT;
        mBrightness = PowerManager.BRIGHTNESS_INVALID_FLOAT;
        mUnclampedBrightness = PowerManager.BRIGHTNESS_INVALID_FLOAT;
        mRecommendedBrightness = PowerManager.BRIGHTNESS_INVALID_FLOAT;
        mPreThresholdBrightness = PowerManager.BRIGHTNESS_INVALID_FLOAT;
        // Different brightness modulations
        mHbmMode = BrightnessInfo.HIGH_BRIGHTNESS_MODE_OFF;
        mHbmMax = PowerManager.BRIGHTNESS_MAX;
        mRbcStrength = 0;
        mThermalMax = PowerManager.BRIGHTNESS_MAX;
        mPowerFactor = 1f;
        mWasShortTermModelActive = false;
        mFlags = 0;
        mAdjustmentFlags = 0;
        // Auto-brightness setting
        mAutomaticBrightnessEnabled = true;
        mDisplayBrightnessStrategyName = "";
        mAutoBrightnessMode = AUTO_BRIGHTNESS_MODE_DEFAULT;
    }

    /**
     * A utility to compare two BrightnessEvents. This purposefully ignores comparing time as the
     * two events might have been created at different times, but essentially hold the same
     * underlying values
     *
     * @param that The brightnessEvent with which the current brightnessEvent is to be compared
     * @return A boolean value representing if the two events are same or not.
     */
    public boolean equalsMainData(BrightnessEvent that) {
        // This equals comparison purposefully ignores time since it is regularly changing and
        // we don't want to log a brightness event just because the time changed.
        return mReason.equals(that.mReason)
                && mDisplayId == that.mDisplayId
                && mPhysicalDisplayId.equals(that.mPhysicalDisplayId)
                && mPhysicalDisplayName.equals(that.mPhysicalDisplayName)
                && mDisplayState == that.mDisplayState
                && mDisplayStateReason == that.mDisplayStateReason
                && mDisplayPolicy == that.mDisplayPolicy
                && Float.floatToRawIntBits(mLux) == Float.floatToRawIntBits(that.mLux)
                && Float.floatToRawIntBits(mPreThresholdLux)
                == Float.floatToRawIntBits(that.mPreThresholdLux)
                && Float.floatToRawIntBits(mNits) == Float.floatToRawIntBits(that.mNits)
                && Float.floatToRawIntBits(mPercent) == Float.floatToRawIntBits(that.mPercent)
                && Float.floatToRawIntBits(mBrightness)
                == Float.floatToRawIntBits(that.mBrightness)
                && Float.floatToRawIntBits(mUnclampedBrightness)
                == Float.floatToRawIntBits(that.mUnclampedBrightness)
                && Float.floatToRawIntBits(mRecommendedBrightness)
                == Float.floatToRawIntBits(that.mRecommendedBrightness)
                && Float.floatToRawIntBits(mPreThresholdBrightness)
                == Float.floatToRawIntBits(that.mPreThresholdBrightness)
                && mHbmMode == that.mHbmMode
                && Float.floatToRawIntBits(mHbmMax) == Float.floatToRawIntBits(that.mHbmMax)
                && mRbcStrength == that.mRbcStrength
                && Float.floatToRawIntBits(mThermalMax)
                == Float.floatToRawIntBits(that.mThermalMax)
                && Float.floatToRawIntBits(mPowerFactor)
                == Float.floatToRawIntBits(that.mPowerFactor)
                && mWasShortTermModelActive == that.mWasShortTermModelActive
                && mFlags == that.mFlags
                && mAdjustmentFlags == that.mAdjustmentFlags
                && mAutomaticBrightnessEnabled == that.mAutomaticBrightnessEnabled
                && mDisplayBrightnessStrategyName.equals(that.mDisplayBrightnessStrategyName)
                && mAutoBrightnessMode == that.mAutoBrightnessMode;
    }

    /**
     * A utility to stringify a BrightnessEvent
     * @param includeTime Indicates if the time field is to be added in the stringify version of the
     *                    BrightnessEvent
     * @return A stringified BrightnessEvent
     */
    public String toString(boolean includeTime) {
        return (includeTime ? FORMAT.format(new Date(mTime)) + " - " : "")
                + "BrightnessEvent: "
                + "brt=" + mBrightness + ((mFlags & FLAG_USER_SET) != 0 ? "(user_set)" : "") + " ("
                + mPercent + "%)"
                + ", nits= " + mNits
                + ", lux=" + mLux
                + ", reason=" + mReason.toString(mAdjustmentFlags)
                + ", strat=" + mDisplayBrightnessStrategyName
                + ", state=" + Display.stateToString(mDisplayState)
                + ", stateReason=" + Display.stateReasonToString(mDisplayStateReason)
                + ", policy=" + policyToString(mDisplayPolicy)
                + ", flags=" + flagsToString()
                // Autobrightness
                + ", initBrt=" + mInitialBrightness
                + ", rcmdBrt=" + mRecommendedBrightness
                + ", preBrt=" + mPreThresholdBrightness
                + ", preLux=" + mPreThresholdLux
                + ", wasShortTermModelActive=" + mWasShortTermModelActive
                + ", autoBrightness=" + mAutomaticBrightnessEnabled + " ("
                + autoBrightnessModeToString(mAutoBrightnessMode) + ")"
                // Throttling info
                + ", unclampedBrt=" + mUnclampedBrightness
                + ", hbmMax=" + mHbmMax
                + ", hbmMode=" + BrightnessInfo.hbmToString(mHbmMode)
                + ", thrmMax=" + mThermalMax
                // Modifiers
                + ", rbcStrength=" + mRbcStrength
                + ", powerFactor=" + mPowerFactor
                // Meta
                + ", physDisp=" + mPhysicalDisplayName + "(" + mPhysicalDisplayId + ")"
                + ", logicalId=" + mDisplayId;
    }

    @Override
    public String toString() {
        return toString(/* includeTime */ true);
    }

    public BrightnessReason getReason() {
        return mReason;
    }

    public void setReason(BrightnessReason reason) {
        this.mReason = reason;
    }

    public long getTime() {
        return mTime;
    }

    public void setTime(long time) {
        this.mTime = time;
    }

    public int getDisplayId() {
        return mDisplayId;
    }

    public void setDisplayId(int displayId) {
        this.mDisplayId = displayId;
    }

    public String getPhysicalDisplayId() {
        return mPhysicalDisplayId;
    }

    public void setPhysicalDisplayId(String mPhysicalDisplayId) {
        this.mPhysicalDisplayId = mPhysicalDisplayId;
    }

    public String getPhysicalDisplayName() {
        return mPhysicalDisplayName;
    }

    public void setPhysicalDisplayName(String mPhysicalDisplayName) {
        this.mPhysicalDisplayName = mPhysicalDisplayName;
    }

    public void setDisplayState(int state) {
        mDisplayState = state;
    }

    public void setDisplayStateReason(@Display.StateReason int reason) {
        mDisplayStateReason = reason;
    }

    public void setDisplayPolicy(int policy) {
        mDisplayPolicy = policy;
    }

    public float getLux() {
        return mLux;
    }

    public void setLux(float lux) {
        this.mLux = lux;
    }

    public float getPreThresholdLux() {
        return mPreThresholdLux;
    }

    public void setPreThresholdLux(float preThresholdLux) {
        this.mPreThresholdLux = preThresholdLux;
    }

    public float getInitialBrightness() {
        return mInitialBrightness;
    }

    public void setInitialBrightness(float mInitialBrightness) {
        this.mInitialBrightness = mInitialBrightness;
    }

    public float getBrightness() {
        return mBrightness;
    }

    public void setBrightness(float brightness) {
        this.mBrightness = brightness;
    }

    public float getUnclampedBrightness() {
        return mUnclampedBrightness;
    }

    public void setUnclampedBrightness(float unclampedBrightness) {
        this.mUnclampedBrightness = unclampedBrightness;
    }

    public void setPercent(float percent) {
        this.mPercent = percent;
    }
    public float getPercent() {
        return mPercent;
    }

    public void setNits(float nits) {
        this.mNits = nits;
    }

    public float getNits() {
        return mNits;
    }

    public float getRecommendedBrightness() {
        return mRecommendedBrightness;
    }

    public void setRecommendedBrightness(float recommendedBrightness) {
        this.mRecommendedBrightness = recommendedBrightness;
    }

    public float getPreThresholdBrightness() {
        return mPreThresholdBrightness;
    }

    public void setPreThresholdBrightness(float preThresholdBrightness) {
        this.mPreThresholdBrightness = preThresholdBrightness;
    }

    public int getHbmMode() {
        return mHbmMode;
    }

    public void setHbmMode(int hbmMode) {
        this.mHbmMode = hbmMode;
    }

    public float getHbmMax() {
        return mHbmMax;
    }

    public void setHbmMax(float hbmMax) {
        this.mHbmMax = hbmMax;
    }

    public int getRbcStrength() {
        return mRbcStrength;
    }

    public void setRbcStrength(int mRbcStrength) {
        this.mRbcStrength = mRbcStrength;
    }

    public boolean isRbcEnabled() {
        return (mFlags & FLAG_RBC) != 0;
    }

    public float getThermalMax() {
        return mThermalMax;
    }

    public void setThermalMax(float thermalMax) {
        this.mThermalMax = thermalMax;
    }

    public float getPowerFactor() {
        return mPowerFactor;
    }

    public void setPowerFactor(float mPowerFactor) {
        this.mPowerFactor = mPowerFactor;
    }

    public boolean isLowPowerModeSet() {
        return (mFlags & FLAG_LOW_POWER_MODE) != 0;
    }

    /**
     * Set whether the short term model was active before the brightness event.
     */
    public boolean setWasShortTermModelActive(boolean wasShortTermModelActive) {
        return this.mWasShortTermModelActive = wasShortTermModelActive;
    }

    /**
     * Returns whether the short term model was active before the brightness event.
     */
    public boolean wasShortTermModelActive() {
        return this.mWasShortTermModelActive;
    }

    public int getFlags() {
        return mFlags;
    }

    public void setFlags(int flags) {
        this.mFlags = flags;
    }

    public int getAdjustmentFlags() {
        return mAdjustmentFlags;
    }

    public void setAdjustmentFlags(int adjustmentFlags) {
        this.mAdjustmentFlags = adjustmentFlags;
    }

    public boolean isAutomaticBrightnessEnabled() {
        return mAutomaticBrightnessEnabled;
    }

    public void setDisplayBrightnessStrategyName(String displayBrightnessStrategyName) {
        mDisplayBrightnessStrategyName = displayBrightnessStrategyName;
    }

    public String getDisplayBrightnessStrategyName() {
        return mDisplayBrightnessStrategyName;
    }

    public void setAutomaticBrightnessEnabled(boolean mAutomaticBrightnessEnabled) {
        this.mAutomaticBrightnessEnabled = mAutomaticBrightnessEnabled;
    }

    @AutomaticBrightnessController.AutomaticBrightnessMode
    public int getAutoBrightnessMode() {
        return mAutoBrightnessMode;
    }

    public void setAutoBrightnessMode(
            @AutomaticBrightnessController.AutomaticBrightnessMode int mode) {
        mAutoBrightnessMode = mode;
    }

    /**
     * A utility to stringify flags from a BrightnessEvent
     * @return Stringified flags from BrightnessEvent
     */
    @VisibleForTesting
    public String flagsToString() {
        return ((mFlags & FLAG_USER_SET) != 0 ? "user_set " : "")
                + ((mFlags & FLAG_RBC) != 0 ? "rbc " : "")
                + ((mFlags & FLAG_INVALID_LUX) != 0 ? "invalid_lux " : "")
                + ((mFlags & FLAG_DOZE_SCALE) != 0 ? "doze_scale " : "")
                + ((mFlags & FLAG_LOW_POWER_MODE) != 0 ? "low_power_mode " : "");
    }
}
