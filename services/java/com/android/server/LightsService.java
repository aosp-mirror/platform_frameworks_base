/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.server;

import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

public class LightsService {
    private static final String TAG = "LightsService";

    static final int LIGHT_ID_BACKLIGHT = 0;
    static final int LIGHT_ID_KEYBOARD = 1;
    static final int LIGHT_ID_BUTTONS = 2;
    static final int LIGHT_ID_BATTERY = 3;
    static final int LIGHT_ID_NOTIFICATIONS = 4;
    static final int LIGHT_ID_ATTENTION = 5;

    static final int LIGHT_FLASH_NONE = 0;
    static final int LIGHT_FLASH_TIMED = 1;
    static final int LIGHT_FLASH_HARDWARE = 2;

    /**
     * Light brightness is managed by a user setting.
     */
    static final int BRIGHTNESS_MODE_USER = 0;

    /**
     * Light brightness is managed by a light sensor.
     */
    static final int BRIGHTNESS_MODE_SENSOR = 1;

    private boolean mAttentionLightOn;
    private boolean mPulsing;

    LightsService(Context context) {

        mNativePointer = init_native();
        mContext = context;
    }

    protected void finalize() throws Throwable {
        finalize_native(mNativePointer);
        super.finalize();
    }

    void setLightOff(int light) {
        setLight_native(mNativePointer, light, 0, LIGHT_FLASH_NONE, 0, 0, 0);
    }

    void setLightBrightness(int light, int brightness, int brightnessMode) {
        int b = brightness & 0x000000ff;
        b = 0xff000000 | (b << 16) | (b << 8) | b;
        setLight_native(mNativePointer, light, b, LIGHT_FLASH_NONE, 0, 0, brightnessMode);
    }

    void setLightColor(int light, int color) {
        setLight_native(mNativePointer, light, color, LIGHT_FLASH_NONE, 0, 0, 0);
    }

    void setLightFlashing(int light, int color, int mode, int onMS, int offMS) {
        setLight_native(mNativePointer, light, color, mode, onMS, offMS, 0);
    }

    public void setAttentionLight(boolean on, int color) {
        // Not worthy of a permission.  We shouldn't have a flashlight permission.
        synchronized (this) {
            mAttentionLightOn = on;
            mPulsing = false;
            setLight_native(mNativePointer, LIGHT_ID_ATTENTION, color,
                    LIGHT_FLASH_HARDWARE, on ? 3 : 0, 0, 0);
        }
    }

    public void pulseBreathingLight() {
        synchronized (this) {
            // HACK: Added at the last minute of cupcake -- design this better;
            // Don't reuse the attention light -- make another one.
            if (false) {
                Log.d(TAG, "pulseBreathingLight mAttentionLightOn=" + mAttentionLightOn
                        + " mPulsing=" + mPulsing);
            }
            if (!mAttentionLightOn && !mPulsing) {
                mPulsing = true;
                setLight_native(mNativePointer, LIGHT_ID_ATTENTION, 0x00ffffff,
                        LIGHT_FLASH_HARDWARE, 7, 0, 0);
                mH.sendMessageDelayed(Message.obtain(mH, 1), 3000);
            }
        }
    }

    private Handler mH = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            synchronized (this) {
                if (false) {
                    Log.d(TAG, "pulse cleanup handler firing mPulsing=" + mPulsing);
                }
                if (mPulsing) {
                    mPulsing = false;
                    setLight_native(mNativePointer, LIGHT_ID_ATTENTION,
                            mAttentionLightOn ? 0xffffffff : 0,
                            LIGHT_FLASH_NONE, 0, 0, 0);
                }
            }
        }
    };

    private static native int init_native();
    private static native void finalize_native(int ptr);

    private static native void setLight_native(int ptr, int light, int color, int mode,
            int onMS, int offMS, int brightnessMode);

    private final Context mContext;

    private int mNativePointer;
}
