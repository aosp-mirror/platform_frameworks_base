/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.audio;

import android.content.ContentResolver;
import android.provider.Settings;

/**
 * Adapter for methods that read and write settings in android.provider.Settings.
 */
public class SettingsAdapter {
    public static SettingsAdapter getDefaultAdapter() {
        return new SettingsAdapter();
    }

    /**
     * Wrapper methods for Settings.Global
     */

    /** Wraps {@link Settings.Global#getInt(ContentResolver, String, int)} */
    public int getGlobalInt(ContentResolver cr, String name, int def) {
        return Settings.Global.getInt(cr, name, def);
    }

    /** Wraps {@link Settings.Global#getString(ContentResolver, String)} */
    public String getGlobalString(ContentResolver resolver, String name) {
        return Settings.Global.getString(resolver, name);
    }

    /** Wraps {@link Settings.Global#putInt(ContentResolver, String, int)} */
    public boolean putGlobalInt(ContentResolver cr, String name, int value) {
        return Settings.Global.putInt(cr, name, value);
    }

    /** Wraps {@link Settings.Global#putString(ContentResolver, String, String)} */
    public boolean putGlobalString(ContentResolver resolver, String name, String value) {
        return Settings.Global.putString(resolver, name, value);
    }

    /**
     * Wrapper methods for Settings.System
     */

    /** Wraps {@link Settings.System#getIntForUser(ContentResolver, String, int, int)} */
    public int getSystemIntForUser(ContentResolver cr, String name, int def, int userHandle) {
        return Settings.System.getIntForUser(cr, name, def, userHandle);
    }

    /** Wraps {@link Settings.System#putIntForUser(ContentResolver, String, int, int)} */
    public boolean putSystemIntForUser(ContentResolver cr, String name, int value, int userHandle) {
        return Settings.System.putIntForUser(cr, name, value, userHandle);
    }

    /**
     * Wrapper methods for Settings.Secure
     */

    /** Wraps {@link Settings.Secure#getIntForUser(ContentResolver, String, int, int)} */
    public int getSecureIntForUser(ContentResolver cr, String name, int def, int userHandle) {
        return Settings.Secure.getIntForUser(cr, name, def, userHandle);
    }

    /** Wraps {@link Settings.Secure#getStringForUser(ContentResolver, String, int)} */
    public String getSecureStringForUser(ContentResolver resolver, String name, int userHandle) {
        return Settings.Secure.getStringForUser(resolver, name, userHandle);
    }

    /** Wraps {@link Settings.Secure#putIntForUser(ContentResolver, String, int, int)} */
    public boolean putSecureIntForUser(ContentResolver cr, String name, int value, int userHandle) {
        return Settings.Secure.putIntForUser(cr, name, value, userHandle);
    }

    /** Wraps {@link Settings.Secure#putStringForUser(ContentResolver, String, String, int)} */
    public boolean putSecureStringForUser(ContentResolver cr, String name, String value,
            int userHandle) {
        return Settings.Secure.putStringForUser(cr, name, value, userHandle);
    }
}
