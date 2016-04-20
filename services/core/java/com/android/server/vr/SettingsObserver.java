/**
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.server.vr;

import android.annotation.NonNull;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArraySet;

import java.util.Objects;
import java.util.Set;

/**
 * Detects changes in a given setting.
 *
 * @hide
 */
public class SettingsObserver {

    private final String mSecureSettingName;
    private final BroadcastReceiver mSettingRestoreReceiver;
    private final ContentObserver mContentObserver;
    private final Set<SettingChangeListener> mSettingsListeners = new ArraySet<>();

    /**
     * Implement this to receive callbacks when the setting tracked by this observer changes.
     */
    public interface SettingChangeListener {

        /**
         * Called when the tracked setting has changed.
         */
        void onSettingChanged();


        /**
         * Called when the tracked setting has been restored for a particular user.
         *
         * @param prevValue the previous value of the setting.
         * @param newValue the new value of the setting.
         * @param userId the user ID for which this setting has been restored.
         */
        void onSettingRestored(String prevValue, String newValue, int userId);
    }

    private SettingsObserver(@NonNull final Context context, @NonNull final Handler handler,
            @NonNull final Uri settingUri, @NonNull final String secureSettingName) {

        mSecureSettingName = secureSettingName;
        mSettingRestoreReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SETTING_RESTORED.equals(intent.getAction())) {
                    String element = intent.getStringExtra(Intent.EXTRA_SETTING_NAME);
                    if (Objects.equals(element, secureSettingName)) {
                        String prevValue = intent.getStringExtra(Intent.EXTRA_SETTING_PREVIOUS_VALUE);
                        String newValue = intent.getStringExtra(Intent.EXTRA_SETTING_NEW_VALUE);
                        sendSettingRestored(prevValue, newValue, getSendingUserId());
                    }
                }
            }
        };

        mContentObserver = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                if (uri == null || settingUri.equals(uri)) {
                    sendSettingChanged();
                }
            }
        };

        ContentResolver resolver = context.getContentResolver();
        resolver.registerContentObserver(settingUri, false, mContentObserver,
                UserHandle.USER_ALL);
    }

    /**
     * Create a SettingsObserver instance.
     *
     * @param context the context to query for settings changes.
     * @param handler the handler to use for a settings ContentObserver.
     * @param settingName the setting to track.
     * @return a SettingsObserver instance.
     */
    public static SettingsObserver build(@NonNull Context context, @NonNull Handler handler,
            @NonNull String settingName) {
        Uri settingUri = Settings.Secure.getUriFor(settingName);

        return new SettingsObserver(context, handler, settingUri, settingName);
    }

    /**
     * Add a listener for setting changes.
     *
     * @param listener a {@link SettingChangeListener} instance.
     */
    public void addListener(@NonNull SettingChangeListener listener) {
        mSettingsListeners.add(listener);
    }

    /**
     * Remove a listener for setting changes.
     *
     * @param listener a {@link SettingChangeListener} instance.
     */
    public void removeListener(@NonNull SettingChangeListener listener) {
        mSettingsListeners.remove(listener);

    }

    private void sendSettingChanged() {
        for (SettingChangeListener l : mSettingsListeners) {
            l.onSettingChanged();
        }
    }

    private void sendSettingRestored(final String prevValue, final String newValue, final int userId) {
        for (SettingChangeListener l : mSettingsListeners) {
            l.onSettingRestored(prevValue, newValue, userId);
        }
    }

}
