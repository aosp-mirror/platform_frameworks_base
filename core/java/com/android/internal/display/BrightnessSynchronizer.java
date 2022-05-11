/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.internal.display;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManager.DisplayListener;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.MathUtils;
import android.view.Display;

/**
 * BrightnessSynchronizer helps convert between the int (old) system and float
 * (new) system for storing the brightness. It has methods to convert between the two and also
 * observes for when one of the settings is changed and syncs this with the other.
 */
public class BrightnessSynchronizer {

    private static final int MSG_UPDATE_FLOAT = 1;
    private static final int MSG_UPDATE_INT = 2;
    private static final int MSG_UPDATE_BOTH = 3;

    private static final String TAG = "BrightnessSynchronizer";
    private static final Uri BRIGHTNESS_URI =
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS);

    // The tolerance within which we consider brightness values approximately equal to eachother.
    // This value is approximately 1/3 of the smallest possible brightness value.
    public static final float EPSILON = 0.001f;

    private DisplayManager mDisplayManager;
    private final Context mContext;

    private final Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_UPDATE_FLOAT:
                    updateBrightnessFloatFromInt(msg.arg1);
                    break;
                case MSG_UPDATE_INT:
                    updateBrightnessIntFromFloat(Float.intBitsToFloat(msg.arg1));
                    break;
                case MSG_UPDATE_BOTH:
                    updateBoth(Float.intBitsToFloat(msg.arg1));
                    break;
                default:
                    super.handleMessage(msg);
            }

        }
    };

    private float mPreferredSettingValue;

    public BrightnessSynchronizer(Context context) {
        mContext = context;
    }

    /**
     * Starts brightnessSyncObserver to ensure that the float and int brightness values stay
     * in sync.
     * This also ensures that values are synchronized at system start up too.
     * So we force an update to the int value, since float is the source of truth. Fallback to int
     * value, if float is invalid. If both are invalid, use default float value from config.
     */
    public void startSynchronizing() {
        if (mDisplayManager == null) {
            mDisplayManager = mContext.getSystemService(DisplayManager.class);
        }

        final BrightnessSyncObserver brightnessSyncObserver;
        brightnessSyncObserver = new BrightnessSyncObserver();
        brightnessSyncObserver.startObserving();

        final float currentFloatBrightness = getScreenBrightnessFloat();
        final int currentIntBrightness = getScreenBrightnessInt(mContext);

        if (!Float.isNaN(currentFloatBrightness)) {
            updateBrightnessIntFromFloat(currentFloatBrightness);
        } else if (currentIntBrightness != -1) {
            updateBrightnessFloatFromInt(currentIntBrightness);
        } else {
            final float defaultBrightness = mContext.getResources().getFloat(
                    com.android.internal.R.dimen.config_screenBrightnessSettingDefaultFloat);
            mDisplayManager.setBrightness(Display.DEFAULT_DISPLAY, defaultBrightness);

        }
    }

    /**
     * Converts between the int brightness system and the float brightness system.
     */
    public static float brightnessIntToFloat(int brightnessInt) {
        if (brightnessInt == PowerManager.BRIGHTNESS_OFF) {
            return PowerManager.BRIGHTNESS_OFF_FLOAT;
        } else if (brightnessInt == PowerManager.BRIGHTNESS_INVALID) {
            return PowerManager.BRIGHTNESS_INVALID_FLOAT;
        } else {
            final float minFloat = PowerManager.BRIGHTNESS_MIN;
            final float maxFloat = PowerManager.BRIGHTNESS_MAX;
            final float minInt = PowerManager.BRIGHTNESS_OFF + 1;
            final float maxInt = PowerManager.BRIGHTNESS_ON;
            return MathUtils.constrainedMap(minFloat, maxFloat, minInt, maxInt, brightnessInt);
        }
    }

    /**
     * Converts between the float brightness system and the int brightness system.
     */
    public static int brightnessFloatToInt(float brightnessFloat) {
        return Math.round(brightnessFloatToIntRange(brightnessFloat));
    }

    /**
     * Translates specified value from the float brightness system to the int brightness system,
     * given the min/max of each range. Accounts for special values such as OFF and invalid values.
     * Value returned as a float primitive (to preserve precision), but is a value within the
     * int-system range.
     */
    public static float brightnessFloatToIntRange(float brightnessFloat) {
        if (floatEquals(brightnessFloat, PowerManager.BRIGHTNESS_OFF_FLOAT)) {
            return PowerManager.BRIGHTNESS_OFF;
        } else if (Float.isNaN(brightnessFloat)) {
            return PowerManager.BRIGHTNESS_INVALID;
        } else {
            final float minFloat = PowerManager.BRIGHTNESS_MIN;
            final float maxFloat = PowerManager.BRIGHTNESS_MAX;
            final float minInt = PowerManager.BRIGHTNESS_OFF + 1;
            final float maxInt = PowerManager.BRIGHTNESS_ON;
            return MathUtils.constrainedMap(minInt, maxInt, minFloat, maxFloat, brightnessFloat);
        }
    }

    /**
     * Gets the stored screen brightness float value from the display brightness setting.
     * @return brightness
     */
    private float getScreenBrightnessFloat() {
        return mDisplayManager.getBrightness(Display.DEFAULT_DISPLAY);
    }

    /**
     * Gets the stored screen brightness int from the system settings.
     * @param context for accessing settings
     *
     * @return brightness
     */
    private static int getScreenBrightnessInt(Context context) {
        return Settings.System.getIntForUser(context.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS, PowerManager.BRIGHTNESS_INVALID,
                UserHandle.USER_CURRENT);
    }

    /**
     * Updates the settings based on a passed in int value. This is called whenever the int
     * setting changes. mPreferredSettingValue holds the most recently updated brightness value
     * as a float that we would like the display to be set to.
     *
     * We then schedule an update to both the int and float settings, but, remove all the other
     * messages to update all, to prevent us getting stuck in a loop.
     *
     * @param value Brightness value as int to store in the float setting.
     */
    private void updateBrightnessFloatFromInt(int value) {
        if (brightnessFloatToInt(mPreferredSettingValue) == value) {
            return;
        }

        mPreferredSettingValue = brightnessIntToFloat(value);
        final int newBrightnessAsIntBits = Float.floatToIntBits(mPreferredSettingValue);
        mHandler.removeMessages(MSG_UPDATE_BOTH);
        mHandler.obtainMessage(MSG_UPDATE_BOTH, newBrightnessAsIntBits, 0).sendToTarget();
    }

    /**
     * Updates the settings based on a passed in float value. This is called whenever the float
     * setting changes. mPreferredSettingValue holds the most recently updated brightness value
     * as a float that we would like the display to be set to.
     *
     * We then schedule an update to both the int and float settings, but, remove all the other
     * messages to update all, to prevent us getting stuck in a loop.
     *
     * @param value Brightness setting as float to store in int setting.
     */
    private void updateBrightnessIntFromFloat(float value) {
        if (floatEquals(mPreferredSettingValue, value)) {
            return;
        }

        mPreferredSettingValue = value;
        final int newBrightnessAsIntBits = Float.floatToIntBits(mPreferredSettingValue);
        mHandler.removeMessages(MSG_UPDATE_BOTH);
        mHandler.obtainMessage(MSG_UPDATE_BOTH, newBrightnessAsIntBits, 0).sendToTarget();
    }


    /**
     * Updates both setting values if they have changed
     * mDisplayManager.setBrightness automatically checks for changes
     * Settings.System.putIntForUser needs to be checked, to prevent an extra callback to this class
     *
     * @param newBrightnessFloat Brightness setting as float to store in both settings
     */
    private void updateBoth(float newBrightnessFloat) {
        int newBrightnessInt = brightnessFloatToInt(newBrightnessFloat);
        mDisplayManager.setBrightness(Display.DEFAULT_DISPLAY, newBrightnessFloat);
        if (getScreenBrightnessInt(mContext) != newBrightnessInt) {
            Settings.System.putIntForUser(mContext.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, newBrightnessInt, UserHandle.USER_CURRENT);
        }

    }

    /**
     * Tests whether two brightness float values are within a small enough tolerance
     * of each other.
     * @param a first float to compare
     * @param b second float to compare
     * @return whether the two values are within a small enough tolerance value
     */
    public static boolean floatEquals(float a, float b) {
        if (a == b) {
            return true;
        } else if (Float.isNaN(a) && Float.isNaN(b)) {
            return true;
        } else if (Math.abs(a - b) < EPSILON) {
            return true;
        } else {
            return false;
        }
    }

    private class BrightnessSyncObserver {
        private final DisplayListener mListener = new DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {}

            @Override
            public void onDisplayRemoved(int displayId) {}

            @Override
            public void onDisplayChanged(int displayId) {
                float currentFloat = getScreenBrightnessFloat();
                int toSend = Float.floatToIntBits(currentFloat);
                mHandler.removeMessages(MSG_UPDATE_INT);
                mHandler.obtainMessage(MSG_UPDATE_INT, toSend, 0).sendToTarget();
            }
        };

        private final ContentObserver mContentObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                if (selfChange) {
                    return;
                }
                if (BRIGHTNESS_URI.equals(uri)) {
                    int currentBrightness = getScreenBrightnessInt(mContext);
                    mHandler.removeMessages(MSG_UPDATE_FLOAT);
                    mHandler.obtainMessage(MSG_UPDATE_FLOAT, currentBrightness, 0).sendToTarget();
                }
            }
        };

        public void startObserving() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.unregisterContentObserver(mContentObserver);
            cr.registerContentObserver(BRIGHTNESS_URI, false, mContentObserver,
                    UserHandle.USER_ALL);

            mDisplayManager.registerDisplayListener(mListener, mHandler,
                    DisplayManager.EVENT_FLAG_DISPLAY_CHANGED
                        | DisplayManager.EVENT_FLAG_DISPLAY_BRIGHTNESS);
        }

        public void stopObserving() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.unregisterContentObserver(mContentObserver);
            mDisplayManager.unregisterDisplayListener(mListener);
        }
    }
}
