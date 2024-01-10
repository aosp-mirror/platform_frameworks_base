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
import android.os.PowerManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.display.BrightnessSynchronizer;
import com.android.server.display.DisplayBrightnessState;
import com.android.server.display.brightness.BrightnessReason;
import com.android.server.display.utils.DebugUtils;

import java.io.PrintWriter;

/**
 * Class used to prevent the screen brightness dipping below a certain value, based on current
 * lux conditions and user preferred minimum.
 */
public class BrightnessLowLuxModifier implements
        BrightnessStateModifier {

    // To enable these logs, run:
    // 'adb shell setprop persist.log.tag.BrightnessLowLuxModifier DEBUG && adb reboot'
    private static final String TAG = "BrightnessLowLuxModifier";
    private static final boolean DEBUG = DebugUtils.isDebuggable(TAG);
    private final SettingsObserver mSettingsObserver;
    private final ContentResolver mContentResolver;
    private final Handler mHandler;
    private final BrightnessClamperController.ClamperChangeListener mChangeListener;
    protected float mSettingNitsLowerBound = PowerManager.BRIGHTNESS_MIN;
    private int mReason;
    private float mBrightnessLowerBound;
    private boolean mIsActive;

    @VisibleForTesting
    BrightnessLowLuxModifier(Handler handler,
            BrightnessClamperController.ClamperChangeListener listener, Context context) {
        super();

        mChangeListener = listener;
        mHandler = handler;
        mContentResolver = context.getContentResolver();
        mSettingsObserver = new SettingsObserver(mHandler);
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
                /* def= */ PowerManager.BRIGHTNESS_MIN, userId);

        boolean isActive = Settings.Secure.getIntForUser(mContentResolver,
                Settings.Secure.EVEN_DIMMER_ACTIVATED,
                /* def= */ 0, userId) == 1;

        // TODO: luxBasedNitsLowerBound = mMinNitsToLuxSpline(currentLux);
        float luxBasedNitsLowerBound = 0.0f;

        // TODO: final float nitsLowerBound = isActive ? Math.max(settingNitsLowerBound,
                // luxBasedNitsLowerBound) : PowerManager.BRIGHTNESS_MIN;

        final int reason = settingNitsLowerBound > luxBasedNitsLowerBound
                ? BrightnessReason.MODIFIER_MIN_USER_SET_LOWER_BOUND
                : BrightnessReason.MODIFIER_MIN_LUX;

        // TODO: brightnessLowerBound = nitsToBrightnessSpline(nitsLowerBound);
        final float brightnessLowerBound = PowerManager.BRIGHTNESS_MIN;

        if (mBrightnessLowerBound != brightnessLowerBound
                || mReason != reason
                || mIsActive != isActive) {
            mIsActive = isActive;
            mReason = reason;
            if (DEBUG) {
                Slog.i(TAG, "isActive: " + isActive
                        + ", settingNitsLowerBound: " + settingNitsLowerBound
                        + ", lowerBound: " + brightnessLowerBound);
            }
            mBrightnessLowerBound = brightnessLowerBound;
            mChangeListener.onChanged();
        }
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
    public void dump(PrintWriter pw) {
        pw.println("BrightnessLowLuxModifier:");
        pw.println("  mBrightnessLowerBound=" + mBrightnessLowerBound);
        pw.println("  mIsActive=" + mIsActive);
        pw.println("  mReason=" + mReason);
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
