/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.annotation.NonNull;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;
import android.view.Display;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Saves brightness to a persistent data store, enabling each logical display to have its own
 * brightness.
 */
public class BrightnessSetting {
    private static final String TAG = "BrightnessSetting";

    private static final int MSG_BRIGHTNESS_CHANGED = 1;
    private static final Uri BRIGHTNESS_FLOAT_URI =
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_FLOAT);
    private final PersistentDataStore mPersistentDataStore;

    private final boolean mIsDefaultDisplay;
    private final Context mContext;
    private final LogicalDisplay mLogicalDisplay;
    private final Object mLock = new Object();

    private final Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == MSG_BRIGHTNESS_CHANGED) {
                float brightnessVal = Float.intBitsToFloat(msg.arg1);
                notifyListeners(brightnessVal);
            }
        }
    };

    private final ContentObserver mBrightnessSettingsObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (selfChange) {
                return;
            }
            if (BRIGHTNESS_FLOAT_URI.equals(uri)) {
                float brightness = getScreenBrightnessSettingFloat();
                setBrightness(brightness, true);
            }
        }
    };

    private final CopyOnWriteArrayList<BrightnessSettingListener> mListeners =
            new CopyOnWriteArrayList<BrightnessSettingListener>();

    private float mBrightness;

    BrightnessSetting(@NonNull PersistentDataStore persistentDataStore,
            @NonNull LogicalDisplay logicalDisplay,
            @NonNull Context context) {
        mPersistentDataStore = persistentDataStore;
        mLogicalDisplay = logicalDisplay;
        mContext = context;
        mIsDefaultDisplay = mLogicalDisplay.getDisplayIdLocked() == Display.DEFAULT_DISPLAY;
        mBrightness = mPersistentDataStore.getBrightness(
                mLogicalDisplay.getPrimaryDisplayDeviceLocked());
        if (mIsDefaultDisplay) {
            mContext.getContentResolver().registerContentObserver(BRIGHTNESS_FLOAT_URI,
                    false, mBrightnessSettingsObserver);
        }
    }

    /**
     * Returns the brightness from the brightness setting
     *
     * @return brightness for the current display
     */
    public float getBrightness() {
        return mBrightness;
    }

    /**
     * Registers listener for brightness setting change events.
     */
    public void registerListener(BrightnessSettingListener l) {
        if (!mListeners.contains(l)) {
            mListeners.add(l);
        }
    }

    /**
     * Unregisters listener for brightness setting change events.
     *
     * @param l listener
     */
    public void unregisterListener(BrightnessSettingListener l) {
        mListeners.remove(l);
    }

    void setBrightness(float brightness) {
        setBrightness(brightness, false);
    }

    private void setBrightness(float brightness, boolean isFromSystemSetting) {
        if (brightness == mBrightness) {
            return;
        }
        if (Float.isNaN(brightness)) {
            Slog.w(TAG, "Attempting to set invalid brightness");
            return;
        }
        synchronized (mLock) {

            mBrightness = brightness;

            // If it didn't come from us
            if (mIsDefaultDisplay && !isFromSystemSetting) {
                Settings.System.putFloatForUser(mContext.getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS_FLOAT, brightness,
                        UserHandle.USER_CURRENT);
            }
            mPersistentDataStore.setBrightness(mLogicalDisplay.getPrimaryDisplayDeviceLocked(),
                    brightness);
            int toSend = Float.floatToIntBits(mBrightness);
            Message msg = mHandler.obtainMessage(MSG_BRIGHTNESS_CHANGED, toSend, 0);
            mHandler.sendMessage(msg);
        }
    }

    private float getScreenBrightnessSettingFloat() {
        return Settings.System.getFloatForUser(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_FLOAT, PowerManager.BRIGHTNESS_INVALID_FLOAT,
                UserHandle.USER_CURRENT);
    }

    private void notifyListeners(float brightness) {
        for (BrightnessSettingListener l : mListeners) {
            l.onBrightnessChanged(brightness);
        }
    }

    /**
     * Listener for changes to system brightness.
     */
    public interface BrightnessSettingListener {

        /**
         * Notify that the brightness has changed.
         */
        void onBrightnessChanged(float brightness);
    }
}
