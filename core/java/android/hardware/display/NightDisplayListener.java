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

package android.hardware.display;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings.Secure;

import java.time.LocalTime;

/**
 * @hide
 */
public class NightDisplayListener {

    private final Context mContext;
    private final int mUserId;
    private final ColorDisplayManager mManager;

    private ContentObserver mContentObserver;
    private Callback mCallback;

    public NightDisplayListener(@NonNull Context context) {
        this(context, ActivityManager.getCurrentUser());
    }

    public NightDisplayListener(@NonNull Context context, @UserIdInt int userId) {
        mContext = context.getApplicationContext();
        mUserId = userId;
        mManager = mContext.getSystemService(ColorDisplayManager.class);
    }

    /**
     * Register a callback to be invoked whenever the Night display settings are changed.
     */
    public void setCallback(Callback callback) {
        final Callback oldCallback = mCallback;
        if (oldCallback != callback) {
            mCallback = callback;

            if (mContentObserver == null) {
                mContentObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
                    @Override
                    public void onChange(boolean selfChange, Uri uri) {
                        super.onChange(selfChange, uri);
                        onSettingChanged(uri);
                    }
                };
            }

            if (callback == null) {
                // Stop listening for changes now that there IS NOT a callback.
                mContext.getContentResolver().unregisterContentObserver(mContentObserver);
            } else if (oldCallback == null) {
                // Start listening for changes now that there IS a callback.
                final ContentResolver cr = mContext.getContentResolver();
                cr.registerContentObserver(Secure.getUriFor(Secure.NIGHT_DISPLAY_ACTIVATED),
                        false /* notifyForDescendants */, mContentObserver, mUserId);
                cr.registerContentObserver(Secure.getUriFor(Secure.NIGHT_DISPLAY_AUTO_MODE),
                        false /* notifyForDescendants */, mContentObserver, mUserId);
                cr.registerContentObserver(Secure.getUriFor(Secure.NIGHT_DISPLAY_CUSTOM_START_TIME),
                        false /* notifyForDescendants */, mContentObserver, mUserId);
                cr.registerContentObserver(Secure.getUriFor(Secure.NIGHT_DISPLAY_CUSTOM_END_TIME),
                        false /* notifyForDescendants */, mContentObserver, mUserId);
                cr.registerContentObserver(Secure.getUriFor(Secure.NIGHT_DISPLAY_COLOR_TEMPERATURE),
                        false /* notifyForDescendants */, mContentObserver, mUserId);
            }
        }
    }

    private void onSettingChanged(Uri uri) {
        final String setting = uri == null ? null : uri.getLastPathSegment();
        if (setting == null || mCallback == null) {
            return;
        }

        switch (setting) {
            case Secure.NIGHT_DISPLAY_ACTIVATED:
                mCallback.onActivated(mManager.isNightDisplayActivated());
                break;
            case Secure.NIGHT_DISPLAY_AUTO_MODE:
                mCallback.onAutoModeChanged(mManager.getNightDisplayAutoMode());
                break;
            case Secure.NIGHT_DISPLAY_CUSTOM_START_TIME:
                mCallback.onCustomStartTimeChanged(mManager.getNightDisplayCustomStartTime());
                break;
            case Secure.NIGHT_DISPLAY_CUSTOM_END_TIME:
                mCallback.onCustomEndTimeChanged(mManager.getNightDisplayCustomEndTime());
                break;
            case Secure.NIGHT_DISPLAY_COLOR_TEMPERATURE:
                mCallback.onColorTemperatureChanged(mManager.getNightDisplayColorTemperature());
                break;
        }
    }

    /**
     * Callback invoked whenever the Night display settings are changed.
     */
    public interface Callback {
        /**
         * Callback invoked when the activated state changes.
         *
         * @param activated {@code true} if Night display is activated
         */
        default void onActivated(boolean activated) {}
        /**
         * Callback invoked when the auto mode changes.
         *
         * @param autoMode the auto mode to use
         */
        default void onAutoModeChanged(int autoMode) {}
        /**
         * Callback invoked when the time to automatically activate Night display changes.
         *
         * @param startTime the local time to automatically activate Night display
         */
        default void onCustomStartTimeChanged(LocalTime startTime) {}
        /**
         * Callback invoked when the time to automatically deactivate Night display changes.
         *
         * @param endTime the local time to automatically deactivate Night display
         */
        default void onCustomEndTimeChanged(LocalTime endTime) {}

        /**
         * Callback invoked when the color temperature changes.
         *
         * @param colorTemperature the color temperature to tint the screen
         */
        default void onColorTemperatureChanged(int colorTemperature) {}
    }
}
