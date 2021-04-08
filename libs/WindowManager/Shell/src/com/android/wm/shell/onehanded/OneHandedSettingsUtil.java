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

package com.android.wm.shell.onehanded;

import android.annotation.IntDef;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;
import android.provider.Settings;

import androidx.annotation.Nullable;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * APIs for querying or updating one handed settings .
 */
public final class OneHandedSettingsUtil {
    private static final String TAG = "OneHandedSettingsUtil";

    @IntDef(prefix = {"ONE_HANDED_TIMEOUT_"}, value = {
            ONE_HANDED_TIMEOUT_NEVER,
            ONE_HANDED_TIMEOUT_SHORT_IN_SECONDS,
            ONE_HANDED_TIMEOUT_MEDIUM_IN_SECONDS,
            ONE_HANDED_TIMEOUT_LONG_IN_SECONDS,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface OneHandedTimeout {
    }

    /**
     * Never stop one handed automatically
     */
    public static final int ONE_HANDED_TIMEOUT_NEVER = 0;
    /**
     * Auto stop one handed in {@link OneHandedSettingsUtil#ONE_HANDED_TIMEOUT_SHORT_IN_SECONDS}
     */
    public static final int ONE_HANDED_TIMEOUT_SHORT_IN_SECONDS = 4;
    /**
     * Auto stop one handed in {@link OneHandedSettingsUtil#ONE_HANDED_TIMEOUT_MEDIUM_IN_SECONDS}
     */
    public static final int ONE_HANDED_TIMEOUT_MEDIUM_IN_SECONDS = 8;
    /**
     * Auto stop one handed in {@link OneHandedSettingsUtil#ONE_HANDED_TIMEOUT_LONG_IN_SECONDS}
     */
    public static final int ONE_HANDED_TIMEOUT_LONG_IN_SECONDS = 12;

    /**
     * Register one handed preference settings observer
     *
     * @param key       Setting key to monitor in observer
     * @param resolver  ContentResolver of context
     * @param observer  Observer from caller
     * @param newUserId New user id to be registered
     * @return uri key for observing
     */
    @Nullable
    public Uri registerSettingsKeyObserver(String key, ContentResolver resolver,
            ContentObserver observer, int newUserId) {
        Uri uriKey = null;
        uriKey = Settings.Secure.getUriFor(key);
        if (resolver != null && uriKey != null) {
            resolver.registerContentObserver(uriKey, false, observer, newUserId);
        }
        return uriKey;
    }

    /**
     * Unregister one handed preference settings observer
     *
     * @param resolver  ContentResolver of context
     * @param observer  preference key change observer
     */
    public void unregisterSettingsKeyObserver(ContentResolver resolver,
            ContentObserver observer) {
        if (resolver != null) {
            resolver.unregisterContentObserver(observer);
        }
    }

    /**
     * Query one handed enable or disable flag from Settings provider.
     *
     * @return enable or disable one handed mode flag.
     */
    public boolean getSettingsOneHandedModeEnabled(ContentResolver resolver, int userId) {
        return Settings.Secure.getIntForUser(resolver,
                Settings.Secure.ONE_HANDED_MODE_ENABLED, 0 /* Disabled */, userId) == 1;
    }

    /**
     * Query taps app to exit config from Settings provider.
     *
     * @return enable or disable taps app exit.
     */
    public boolean getSettingsTapsAppToExit(ContentResolver resolver, int userId) {
        return Settings.Secure.getIntForUser(resolver,
                Settings.Secure.TAPS_APP_TO_EXIT, 0, userId) == 1;
    }

    /**
     * Query timeout value from Settings provider. Default is
     * {@link OneHandedSettingsUtil#ONE_HANDED_TIMEOUT_MEDIUM_IN_SECONDS}
     *
     * @return timeout value in seconds.
     */
    public @OneHandedTimeout int getSettingsOneHandedModeTimeout(ContentResolver resolver,
            int userId) {
        return Settings.Secure.getIntForUser(resolver,
                Settings.Secure.ONE_HANDED_MODE_TIMEOUT, ONE_HANDED_TIMEOUT_MEDIUM_IN_SECONDS,
                userId);
    }

    /**
     * Returns whether swipe bottom to notification gesture enabled or not.
     */
    public boolean getSettingsSwipeToNotificationEnabled(ContentResolver resolver, int userId) {
        return Settings.Secure.getInt(resolver,
                Settings.Secure.SWIPE_BOTTOM_TO_NOTIFICATION_ENABLED, 0 /* Default OFF */) == 1;
    }

    void dump(PrintWriter pw, String prefix, ContentResolver resolver,
            int userId) {
        final String innerPrefix = prefix + "  ";
        pw.println(innerPrefix + TAG);
        pw.print(innerPrefix + "isOneHandedModeEnable=");
        pw.println(getSettingsOneHandedModeEnabled(resolver, userId));
        pw.print(innerPrefix + "oneHandedTimeOut=");
        pw.println(getSettingsOneHandedModeTimeout(resolver, userId));
        pw.print(innerPrefix + "tapsAppToExit=");
        pw.println(getSettingsTapsAppToExit(resolver, userId));
    }

    public OneHandedSettingsUtil() {
    }
}
