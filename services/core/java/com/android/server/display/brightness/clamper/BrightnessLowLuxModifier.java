/*
 * Copyright (C) 2023 The Android Open Source Project
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


import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.hardware.display.DisplayManagerInternal;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.display.BrightnessSynchronizer;
import com.android.server.display.BrightnessMappingStrategy;
import com.android.server.display.DisplayBrightnessState;
import com.android.server.display.DisplayDeviceConfig;
import com.android.server.display.brightness.BrightnessReason;
import com.android.server.display.utils.DebugUtils;

import java.io.PrintWriter;

/**
 * Class used to prevent the screen brightness dipping below a certain value, based on current
 * lux conditions and user preferred minimum.
 */
public class BrightnessLowLuxModifier extends BrightnessModifier {

    // To enable these logs, run:
    // 'adb shell setprop persist.log.tag.BrightnessLowLuxModifier DEBUG && adb reboot'
    private static final String TAG = "BrightnessLowLuxModifier";
    private static final boolean DEBUG = DebugUtils.isDebuggable(TAG);
    private static final float MIN_NITS_DEFAULT = 0.2f;
    private final SettingsObserver mSettingsObserver;
    private final ContentResolver mContentResolver;
    private final Handler mHandler;
    private final BrightnessClamperController.ClamperChangeListener mChangeListener;
    private int mReason;
    private float mBrightnessLowerBound;
    private float mMinNitsAllowed;
    private boolean mIsActive;
    private float mAmbientLux;
    private final DisplayDeviceConfig mDisplayDeviceConfig;

    @VisibleForTesting
    BrightnessLowLuxModifier(Handler handler,
            BrightnessClamperController.ClamperChangeListener listener, Context context,
            DisplayDeviceConfig displayDeviceConfig) {
        super();

        mChangeListener = listener;
        mHandler = handler;
        mContentResolver = context.getContentResolver();
        mSettingsObserver = new SettingsObserver(mHandler);
        mDisplayDeviceConfig = displayDeviceConfig;
        mHandler.post(() -> {
            start();
        });
    }

    /**
     * Calculates new lower bound for brightness range, based on whether the setting is active,
     * the user defined min brightness setting, and current lux environment.
     */
    @VisibleForTesting
    public void recalculateLowerBound() {
        int userId = UserHandle.USER_CURRENT;
        float settingNitsLowerBound = Settings.Secure.getFloatForUser(
                mContentResolver, Settings.Secure.EVEN_DIMMER_MIN_NITS,
                /* def= */ MIN_NITS_DEFAULT, userId);

        boolean isActive = isSettingEnabled()
                && mAmbientLux != BrightnessMappingStrategy.INVALID_LUX;

        final int reason;
        float minNitsAllowed = -1f; // undefined, if setting is off.
        final float minBrightnessAllowed;

        if (isActive) {
            float luxBasedNitsLowerBound = mDisplayDeviceConfig.getMinNitsFromLux(mAmbientLux);
            minNitsAllowed = Math.max(settingNitsLowerBound,
                    luxBasedNitsLowerBound);
            minBrightnessAllowed = getBrightnessFromNits(minNitsAllowed);
            reason = settingNitsLowerBound > luxBasedNitsLowerBound
                    ? BrightnessReason.MODIFIER_MIN_USER_SET_LOWER_BOUND
                    : BrightnessReason.MODIFIER_MIN_LUX;
        } else {
            minBrightnessAllowed = mDisplayDeviceConfig.getEvenDimmerTransitionPoint();
            reason = 0;
        }

        if (mBrightnessLowerBound != minBrightnessAllowed
                || mReason != reason
                || mIsActive != isActive) {
            mIsActive = isActive;
            mReason = reason;
            if (DEBUG) {
                Slog.i(TAG, "isActive: " + isActive
                        + ", minBrightnessAllowed: " + minBrightnessAllowed
                        + ", mAmbientLux: " + mAmbientLux
                        + ", mReason: " + (mReason)
                        + ", minNitsAllowed: " + minNitsAllowed
                );
            }
            mMinNitsAllowed = minNitsAllowed;
            mBrightnessLowerBound = minBrightnessAllowed;
            mChangeListener.onChanged();
        }
    }

    @VisibleForTesting
    public void setAmbientLux(float lux) {
        mAmbientLux = lux;
        recalculateLowerBound();
    }

    @VisibleForTesting
    public boolean isActive() {
        return mIsActive;
    }

    @VisibleForTesting
    public int getBrightnessReason() {
        return mReason;
    }

    @VisibleForTesting
    public float getBrightnessLowerBound() {
        return mBrightnessLowerBound;
    }

    void start() {
        recalculateLowerBound();
    }

    @Override
    boolean shouldApply(DisplayManagerInternal.DisplayPowerRequest request) {
        return mIsActive;
    }

    @Override
    float getBrightnessAdjusted(float currentBrightness,
            DisplayManagerInternal.DisplayPowerRequest request) {
        return Math.max(mBrightnessLowerBound, currentBrightness);
    }

    @Override
    int getModifier() {
        return mReason;
    }

    @Override
    public void apply(DisplayManagerInternal.DisplayPowerRequest request,
            DisplayBrightnessState.Builder stateBuilder) {

        stateBuilder.setMinBrightness(mBrightnessLowerBound);
        float boundedBrightness = Math.max(mBrightnessLowerBound, stateBuilder.getBrightness());
        stateBuilder.setBrightness(boundedBrightness);
        if (BrightnessSynchronizer.floatEquals(stateBuilder.getBrightness(),
                mBrightnessLowerBound)) {
            stateBuilder.getBrightnessReason().addModifier(mReason);
        }
    }

    @Override
    public void stop() {
        mContentResolver.unregisterContentObserver(mSettingsObserver);
    }

    @Override
    public boolean shouldListenToLightSensor() {
        return isSettingEnabled();
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.println("BrightnessLowLuxModifier:");
        pw.println("  mIsActive=" + mIsActive);
        pw.println("  mBrightnessLowerBound=" + mBrightnessLowerBound);
        pw.println("  mReason=" + mReason);
        pw.println("  mAmbientLux=" + mAmbientLux);
        pw.println("  mMinNitsAllowed=" + mMinNitsAllowed);
    }

    /**
     * Defaults to true, on devices where setting is unset.
     *
     * @return if setting indicates feature is enabled
     */
    private boolean isSettingEnabled() {
        return Settings.Secure.getFloatForUser(mContentResolver,
                Settings.Secure.EVEN_DIMMER_ACTIVATED,
                /* def= */ 1.0f, UserHandle.USER_CURRENT) == 1.0f;
    }

    private float getBrightnessFromNits(float nits) {
        return mDisplayDeviceConfig.getBrightnessFromBacklight(
                mDisplayDeviceConfig.getBacklightFromNits(nits));
    }

    private final class SettingsObserver extends ContentObserver {

        SettingsObserver(Handler handler) {
            super(handler);
            mContentResolver.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.EVEN_DIMMER_MIN_NITS),
                    false, this);
            mContentResolver.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.EVEN_DIMMER_ACTIVATED),
                    false, this);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            recalculateLowerBound();
        }
    }
}
