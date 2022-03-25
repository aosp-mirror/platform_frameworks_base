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

import java.util.HashMap;
import java.util.Map;

public class NoOpSettingsAdapter extends SettingsAdapter {

    /**
     * No-op methods for Settings.Global
     */

    private Map<String, Integer> mGlobalIntSettings = new HashMap<>();
    private Map<String, String> mGlobalStringSettings = new HashMap<>();

    @Override
    public int getGlobalInt(ContentResolver cr, String name, int def) {
        return mGlobalIntSettings.getOrDefault(name, def);
    }

    @Override
    public String getGlobalString(ContentResolver resolver, String name) {
        return mGlobalStringSettings.getOrDefault(name, null);
    }

    @Override
    public boolean putGlobalInt(ContentResolver cr, String name, int value) {
        mGlobalIntSettings.put(name, value);
        return true;
    }

    @Override
    public boolean putGlobalString(ContentResolver resolver, String name, String value) {
        mGlobalStringSettings.put(name, value);
        return true;
    }

    /**
     * No-op methods for Settings.System
     */

    private Map<String, Integer> mSystemIntSettings = new HashMap<>();

    @Override
    public int getSystemIntForUser(ContentResolver cr, String name, int def, int userHandle) {
        return mSystemIntSettings.getOrDefault(name, def);
    }

    @Override
    public boolean putSystemIntForUser(ContentResolver cr, String name, int value, int userHandle) {
        mSystemIntSettings.put(name, value);
        return true;
    }

    /**
     * No-op methods for Settings.Secure
     */

    private Map<String, Integer> mSecureIntSettings = new HashMap<>();
    private Map<String, String> mSecureStringSettings = new HashMap<>();

    @Override
    public int getSecureIntForUser(ContentResolver cr, String name, int def, int userHandle) {
        return mSecureIntSettings.getOrDefault(name, def);
    }

    @Override
    public String getSecureStringForUser(ContentResolver resolver, String name, int userHandle) {
        return mSecureStringSettings.getOrDefault(name, null);
    }

    @Override
    public boolean putSecureIntForUser(ContentResolver cr, String name, int value, int userHandle) {
        mSecureIntSettings.put(name, value);
        return true;
    }

    @Override
    public boolean putSecureStringForUser(ContentResolver cr, String name, String value,
            int userHandle) {
        mSecureStringSettings.put(name, value);
        return true;
    }
}

