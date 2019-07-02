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

package com.android.settingslib.development;

import android.content.Context;
import android.content.Intent;
import android.os.SystemProperties;

import androidx.annotation.VisibleForTesting;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;

import com.android.settingslib.R;

public abstract class AbstractLogdSizePreferenceController extends
        DeveloperOptionsPreferenceController implements Preference.OnPreferenceChangeListener {
    public static final String ACTION_LOGD_SIZE_UPDATED = "com.android.settingslib.development."
            + "AbstractLogdSizePreferenceController.LOGD_SIZE_UPDATED";
    public static final String EXTRA_CURRENT_LOGD_VALUE = "CURRENT_LOGD_VALUE";

    @VisibleForTesting
    static final String LOW_RAM_CONFIG_PROPERTY_KEY = "ro.config.low_ram";
    private static final String SELECT_LOGD_SIZE_KEY = "select_logd_size";
    @VisibleForTesting
    static final String SELECT_LOGD_SIZE_PROPERTY = "persist.logd.size";
    static final String SELECT_LOGD_TAG_PROPERTY = "persist.log.tag";
    // Tricky, isLoggable only checks for first character, assumes silence
    static final String SELECT_LOGD_TAG_SILENCE = "Settings";
    @VisibleForTesting
    static final String SELECT_LOGD_SNET_TAG_PROPERTY = "persist.log.tag.snet_event_log";
    private static final String SELECT_LOGD_RUNTIME_SNET_TAG_PROPERTY = "log.tag.snet_event_log";
    private static final String SELECT_LOGD_DEFAULT_SIZE_PROPERTY = "ro.logd.size";
    @VisibleForTesting
    static final String SELECT_LOGD_DEFAULT_SIZE_VALUE = "262144";
    private static final String SELECT_LOGD_SVELTE_DEFAULT_SIZE_VALUE = "65536";
    // 32768 is merely a menu marker, 64K is our lowest log buffer size we replace it with.
    @VisibleForTesting
    static final String SELECT_LOGD_MINIMUM_SIZE_VALUE = "65536";
    static final String SELECT_LOGD_OFF_SIZE_MARKER_VALUE = "32768";
    @VisibleForTesting
    static final String DEFAULT_SNET_TAG = "I";

    private ListPreference mLogdSize;

    public AbstractLogdSizePreferenceController(Context context) {
        super(context);
    }

    @Override
    public String getPreferenceKey() {
        return SELECT_LOGD_SIZE_KEY;
    }

    @Override
    public void displayPreference(PreferenceScreen screen) {
        super.displayPreference(screen);
        if (isAvailable()) {
            mLogdSize = (ListPreference) screen.findPreference(SELECT_LOGD_SIZE_KEY);
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (preference == mLogdSize) {
            writeLogdSizeOption(newValue);
            return true;
        } else {
            return false;
        }
    }

    public void enablePreference(boolean enabled) {
        if (isAvailable()) {
            mLogdSize.setEnabled(enabled);
        }
    }

    private String defaultLogdSizeValue() {
        String defaultValue = SystemProperties.get(SELECT_LOGD_DEFAULT_SIZE_PROPERTY);
        if ((defaultValue == null) || (defaultValue.length() == 0)) {
            if (SystemProperties.get("ro.config.low_ram").equals("true")) {
                defaultValue = SELECT_LOGD_SVELTE_DEFAULT_SIZE_VALUE;
            } else {
                defaultValue = SELECT_LOGD_DEFAULT_SIZE_VALUE;
            }
        }
        return defaultValue;
    }

    public void updateLogdSizeValues() {
        if (mLogdSize != null) {
            String currentTag = SystemProperties.get(SELECT_LOGD_TAG_PROPERTY);
            String currentValue = SystemProperties.get(SELECT_LOGD_SIZE_PROPERTY);
            if ((currentTag != null) && currentTag.startsWith(SELECT_LOGD_TAG_SILENCE)) {
                currentValue = SELECT_LOGD_OFF_SIZE_MARKER_VALUE;
            }
            LocalBroadcastManager.getInstance(mContext).sendBroadcastSync(
                    new Intent(ACTION_LOGD_SIZE_UPDATED)
                            .putExtra(EXTRA_CURRENT_LOGD_VALUE, currentValue));
            if ((currentValue == null) || (currentValue.length() == 0)) {
                currentValue = defaultLogdSizeValue();
            }
            String[] values = mContext.getResources()
                    .getStringArray(R.array.select_logd_size_values);
            String[] titles = mContext.getResources()
                    .getStringArray(R.array.select_logd_size_titles);
            int index = 2; // punt to second entry if not found
            if (SystemProperties.get("ro.config.low_ram").equals("true")) {
                mLogdSize.setEntries(R.array.select_logd_size_lowram_titles);
                titles = mContext.getResources()
                        .getStringArray(R.array.select_logd_size_lowram_titles);
                index = 1;
            }
            String[] summaries = mContext.getResources()
                    .getStringArray(R.array.select_logd_size_summaries);
            for (int i = 0; i < titles.length; i++) {
                if (currentValue.equals(values[i])
                        || currentValue.equals(titles[i])) {
                    index = i;
                    break;
                }
            }
            mLogdSize.setValue(values[index]);
            mLogdSize.setSummary(summaries[index]);
        }
    }

    public void writeLogdSizeOption(Object newValue) {
        boolean disable = (newValue != null) &&
                (newValue.toString().equals(SELECT_LOGD_OFF_SIZE_MARKER_VALUE));
        String currentTag = SystemProperties.get(SELECT_LOGD_TAG_PROPERTY);
        if (currentTag == null) {
            currentTag = "";
        }
        // filter clean and unstack all references to our setting
        String newTag = currentTag.replaceAll(
                ",+" + SELECT_LOGD_TAG_SILENCE, "").replaceFirst(
                "^" + SELECT_LOGD_TAG_SILENCE + ",*", "").replaceAll(
                ",+", ",").replaceFirst(
                ",+$", "");
        if (disable) {
            newValue = SELECT_LOGD_MINIMUM_SIZE_VALUE;
            // Make sure snet_event_log get through first, but do not override
            String snetValue = SystemProperties.get(SELECT_LOGD_SNET_TAG_PROPERTY);
            if ((snetValue == null) || (snetValue.length() == 0)) {
                snetValue = SystemProperties.get(SELECT_LOGD_RUNTIME_SNET_TAG_PROPERTY);
                if ((snetValue == null) || (snetValue.length() == 0)) {
                    SystemProperties.set(SELECT_LOGD_SNET_TAG_PROPERTY, DEFAULT_SNET_TAG);
                }
            }
            // Silence all log sources, security logs notwithstanding
            if (newTag.length() != 0) {
                newTag = "," + newTag;
            }
            // Stack settings, stack to help preserve original value
            newTag = SELECT_LOGD_TAG_SILENCE + newTag;
        }
        if (!newTag.equals(currentTag)) {
            SystemProperties.set(SELECT_LOGD_TAG_PROPERTY, newTag);
        }
        String defaultValue = defaultLogdSizeValue();
        final String size = ((newValue != null) && (newValue.toString().length() != 0)) ?
                newValue.toString() : defaultValue;
        SystemProperties.set(SELECT_LOGD_SIZE_PROPERTY, defaultValue.equals(size) ? "" : size);
        SystemProperties.set("ctl.start", "logd-reinit");
        SystemPropPoker.getInstance().poke();
        updateLogdSizeValues();
    }
}
