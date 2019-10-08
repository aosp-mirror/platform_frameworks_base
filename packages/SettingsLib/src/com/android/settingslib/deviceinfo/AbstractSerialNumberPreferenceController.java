/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.settingslib.deviceinfo;

import android.content.Context;
import android.os.Build;
import android.text.TextUtils;

import androidx.annotation.VisibleForTesting;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;

import com.android.settingslib.core.AbstractPreferenceController;

/**
 * Preference controller for displaying device serial number. Wraps {@link Build#getSerial()}.
 */
public class AbstractSerialNumberPreferenceController extends AbstractPreferenceController {

    @VisibleForTesting
    static final String KEY_SERIAL_NUMBER = "serial_number";

    private final String mSerialNumber;

    public AbstractSerialNumberPreferenceController(Context context) {
        this(context, Build.getSerial());
    }

    @VisibleForTesting
    AbstractSerialNumberPreferenceController(Context context, String serialNumber) {
        super(context);
        mSerialNumber = serialNumber;
    }

    @Override
    public boolean isAvailable() {
        return !TextUtils.isEmpty(mSerialNumber);
    }

    @Override
    public void displayPreference(PreferenceScreen screen) {
        super.displayPreference(screen);
        final Preference pref = screen.findPreference(KEY_SERIAL_NUMBER);
        if (pref != null) {
            pref.setSummary(mSerialNumber);
        }
    }

    @Override
    public String getPreferenceKey() {
        return KEY_SERIAL_NUMBER;
    }
}
