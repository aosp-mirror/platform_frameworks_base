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
import android.net.Uri;
import android.os.Handler;
import android.os.PowerManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.display.utils.DebugUtils;

import java.io.PrintWriter;

/**
 * Class used to prevent the screen brightness dipping below a certain value, based on current
 * lux conditions.
 */
public class BrightnessMinClamper extends BrightnessClamper {

    // To enable these logs, run:
    // 'adb shell setprop persist.log.tag.BrightnessMinClamper DEBUG && adb reboot'
    private static final String TAG = "BrightnessMinClamper";
    private static final boolean DEBUG = DebugUtils.isDebuggable(TAG);

    private final SettingsObserver mSettingsObserver;

    ContentResolver mContentResolver;
    private float mNitsLowerBound;

    @VisibleForTesting
    BrightnessMinClamper(Handler handler,
            BrightnessClamperController.ClamperChangeListener listener, Context context) {
        super(handler, listener);

        mContentResolver = context.getContentResolver();
        mSettingsObserver = new SettingsObserver(mHandler);
        mHandler.post(() -> {
            start();
        });
    }

    private void recalculateLowerBound() {
        final int userId = UserHandle.USER_CURRENT;
        float settingNitsLowerBound = Settings.Secure.getFloatForUser(
                mContentResolver, Settings.Secure.EVEN_DIMMER_MIN_NITS,
                /* def= */ PowerManager.BRIGHTNESS_MIN, userId);

        boolean isActive = Settings.Secure.getIntForUser(mContentResolver,
                Settings.Secure.EVEN_DIMMER_ACTIVATED,
                /* def= */ 0, userId) == 1;

        // TODO: luxBasedNitsLowerBound = mMinNitsToLuxSpline(currentLux);
        float luxBasedNitsLowerBound = PowerManager.BRIGHTNESS_MIN;
        final float nitsLowerBound = Math.max(settingNitsLowerBound, luxBasedNitsLowerBound);

        if (mNitsLowerBound != nitsLowerBound || mIsActive != isActive) {
            mIsActive = isActive;
            mNitsLowerBound = nitsLowerBound;
            if (DEBUG) {
                Slog.i(TAG, "mIsActive: " + mIsActive);
            }
            // TODO: mBrightnessCap = nitsToBrightnessSpline(mNitsLowerBound);
            mChangeListener.onChanged();
        }
    }

    void start() {
        recalculateLowerBound();
    }


    @Override
    Type getType() {
        return Type.LUX;
    }

    @Override
    void onDeviceConfigChanged() {
        // TODO
    }

    @Override
    void onDisplayChanged(Object displayData) {

    }

    @Override
    void stop() {
        mContentResolver.unregisterContentObserver(mSettingsObserver);
    }

    @Override
    void dump(PrintWriter pw) {
        pw.println("BrightnessMinClamper:");
        pw.println("  mBrightnessCap=" + mBrightnessCap);
        pw.println("  mIsActive=" + mIsActive);
        pw.println("  mNitsLowerBound=" + mNitsLowerBound);
        super.dump(pw);
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
