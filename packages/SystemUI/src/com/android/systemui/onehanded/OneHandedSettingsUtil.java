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

package com.android.systemui.onehanded;

import android.annotation.IntDef;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;
import android.provider.Settings;

import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * APIs for querying or updating one handed settings .
 */
@Singleton
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

    @VisibleForTesting
    @Inject
    OneHandedSettingsUtil() {
    }

    /**
     * Register one handed preference settings observer
     *
     * @param key      Setting key to monitor in observer
     * @param resolver ContentResolver of context
     * @param observer Observer from caller
     * @return uri key for observing
     */
    public static Uri registerSettingsKeyObserver(String key, ContentResolver resolver,
            ContentObserver observer) {
        Uri uriKey = null;
        uriKey = Settings.Secure.getUriFor(key);
        if (resolver != null && uriKey != null) {
            resolver.registerContentObserver(uriKey, false, observer);
        }
        return uriKey;
    }

    /**
     * Unregister one handed preference settings observer
     *
     * @param resolver ContentResolver of context
     * @param observer preference key change observer
     */
    public static void unregisterSettingsKeyObserver(ContentResolver resolver,
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
    public static boolean getSettingsOneHandedModeEnabled(ContentResolver resolver) {
        return Settings.Secure.getInt(resolver,
                Settings.Secure.ONE_HANDED_MODE_ENABLED, 0 /* Disabled */) == 1;
    }

    /**
     * Query taps app to exit config from Settings provider.
     *
     * @return enable or disable taps app exit.
     */
    public static boolean getSettingsTapsAppToExit(ContentResolver resolver) {
        return Settings.Secure.getInt(resolver,
                Settings.Secure.TAPS_APP_TO_EXIT, 0) == 1;
    }

    /**
     * Query timeout value from Settings provider.
     * Default is {@link OneHandedSettingsUtil#ONE_HANDED_TIMEOUT_MEDIUM_IN_SECONDS}
     *
     * @return timeout value in seconds.
     */
    public static @OneHandedTimeout int getSettingsOneHandedModeTimeout(ContentResolver resolver) {
        return Settings.Secure.getInt(resolver,
                Settings.Secure.ONE_HANDED_MODE_TIMEOUT, ONE_HANDED_TIMEOUT_MEDIUM_IN_SECONDS);
    }

    protected static void dump(PrintWriter pw, String prefix, ContentResolver resolver) {
        final String innerPrefix = prefix + "  ";
        pw.println(prefix + TAG);
        pw.print(innerPrefix + "isOneHandedModeEnable=");
        pw.println(getSettingsOneHandedModeEnabled(resolver));
        pw.print(innerPrefix + "oneHandedTimeOut=");
        pw.println(getSettingsOneHandedModeTimeout(resolver));
        pw.print(innerPrefix + "tapsAppToExit=");
        pw.println(getSettingsTapsAppToExit(resolver));
    }
}
