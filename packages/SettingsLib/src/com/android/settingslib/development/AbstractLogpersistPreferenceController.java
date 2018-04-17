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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.SystemProperties;
import androidx.annotation.VisibleForTesting;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;
import android.text.TextUtils;

import com.android.settingslib.R;
import com.android.settingslib.core.ConfirmationDialogController;
import com.android.settingslib.core.lifecycle.Lifecycle;
import com.android.settingslib.core.lifecycle.LifecycleObserver;
import com.android.settingslib.core.lifecycle.events.OnCreate;
import com.android.settingslib.core.lifecycle.events.OnDestroy;

public abstract class AbstractLogpersistPreferenceController extends
        DeveloperOptionsPreferenceController implements Preference.OnPreferenceChangeListener,
        LifecycleObserver, OnCreate, OnDestroy, ConfirmationDialogController {

    private static final String SELECT_LOGPERSIST_KEY = "select_logpersist";
    private static final String SELECT_LOGPERSIST_PROPERTY = "persist.logd.logpersistd";
    @VisibleForTesting
    static final String ACTUAL_LOGPERSIST_PROPERTY = "logd.logpersistd";
    @VisibleForTesting
    static final String SELECT_LOGPERSIST_PROPERTY_SERVICE = "logcatd";
    private static final String SELECT_LOGPERSIST_PROPERTY_CLEAR = "clear";
    private static final String SELECT_LOGPERSIST_PROPERTY_STOP = "stop";
    private static final String SELECT_LOGPERSIST_PROPERTY_BUFFER =
            "persist.logd.logpersistd.buffer";
    @VisibleForTesting
    static final String ACTUAL_LOGPERSIST_PROPERTY_BUFFER = "logd.logpersistd.buffer";
    private static final String ACTUAL_LOGPERSIST_PROPERTY_ENABLE = "logd.logpersistd.enable";

    private ListPreference mLogpersist;
    private boolean mLogpersistCleared;

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String currentValue = intent.getStringExtra(
                    AbstractLogdSizePreferenceController.EXTRA_CURRENT_LOGD_VALUE);
            onLogdSizeSettingUpdate(currentValue);
        }
    };

    public AbstractLogpersistPreferenceController(Context context, Lifecycle lifecycle) {
        super(context);
        if (isAvailable() && lifecycle != null) {
            lifecycle.addObserver(this);
        }
    }

    @Override
    public boolean isAvailable() {
        return TextUtils.equals(SystemProperties.get("ro.debuggable", "0"), "1");
    }

    @Override
    public String getPreferenceKey() {
        return SELECT_LOGPERSIST_KEY;
    }

    @Override
    public void displayPreference(PreferenceScreen screen) {
        super.displayPreference(screen);
        if (isAvailable()) {
            mLogpersist = (ListPreference) screen.findPreference(SELECT_LOGPERSIST_KEY);
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (preference == mLogpersist) {
            writeLogpersistOption(newValue, false);
            return true;
        } else {
            return false;
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        LocalBroadcastManager.getInstance(mContext).registerReceiver(mReceiver,
                new IntentFilter(AbstractLogdSizePreferenceController.ACTION_LOGD_SIZE_UPDATED));
    }

    @Override
    public void onDestroy() {
        LocalBroadcastManager.getInstance(mContext).unregisterReceiver(mReceiver);
    }

    public void enablePreference(boolean enabled) {
        if (isAvailable()) {
            mLogpersist.setEnabled(enabled);
        }
    }

    private void onLogdSizeSettingUpdate(String currentValue) {
        if (mLogpersist != null) {
            String currentLogpersistEnable
                    = SystemProperties.get(ACTUAL_LOGPERSIST_PROPERTY_ENABLE);
            if ((currentLogpersistEnable == null)
                    || !currentLogpersistEnable.equals("true")
                    || currentValue.equals(
                    AbstractLogdSizePreferenceController.SELECT_LOGD_OFF_SIZE_MARKER_VALUE)) {
                writeLogpersistOption(null, true);
                mLogpersist.setEnabled(false);
            } else if (DevelopmentSettingsEnabler.isDevelopmentSettingsEnabled(mContext)) {
                mLogpersist.setEnabled(true);
            }
        }
    }

    public void updateLogpersistValues() {
        if (mLogpersist == null) {
            return;
        }
        String currentValue = SystemProperties.get(ACTUAL_LOGPERSIST_PROPERTY);
        if (currentValue == null) {
            currentValue = "";
        }
        String currentBuffers = SystemProperties.get(ACTUAL_LOGPERSIST_PROPERTY_BUFFER);
        if ((currentBuffers == null) || (currentBuffers.length() == 0)) {
            currentBuffers = "all";
        }
        int index = 0;
        if (currentValue.equals(SELECT_LOGPERSIST_PROPERTY_SERVICE)) {
            index = 1;
            if (currentBuffers.equals("kernel")) {
                index = 3;
            } else if (!currentBuffers.equals("all") &&
                    !currentBuffers.contains("radio") &&
                    currentBuffers.contains("security") &&
                    currentBuffers.contains("kernel")) {
                index = 2;
                if (!currentBuffers.contains("default")) {
                    String[] contains = {"main", "events", "system", "crash"};
                    for (String type : contains) {
                        if (!currentBuffers.contains(type)) {
                            index = 1;
                            break;
                        }
                    }
                }
            }
        }
        mLogpersist.setValue(
                mContext.getResources().getStringArray(R.array.select_logpersist_values)[index]);
        mLogpersist.setSummary(
                mContext.getResources().getStringArray(R.array.select_logpersist_summaries)[index]);
        if (index != 0) {
            mLogpersistCleared = false;
        } else if (!mLogpersistCleared) {
            // would File.delete() directly but need to switch uid/gid to access
            SystemProperties.set(ACTUAL_LOGPERSIST_PROPERTY, SELECT_LOGPERSIST_PROPERTY_CLEAR);
            SystemPropPoker.getInstance().poke();
            mLogpersistCleared = true;
        }
    }

    protected void setLogpersistOff(boolean update) {
        SystemProperties.set(SELECT_LOGPERSIST_PROPERTY_BUFFER, "");
        // deal with trampoline of empty properties
        SystemProperties.set(ACTUAL_LOGPERSIST_PROPERTY_BUFFER, "");
        SystemProperties.set(SELECT_LOGPERSIST_PROPERTY, "");
        SystemProperties.set(ACTUAL_LOGPERSIST_PROPERTY,
                update ? "" : SELECT_LOGPERSIST_PROPERTY_STOP);
        SystemPropPoker.getInstance().poke();
        if (update) {
            updateLogpersistValues();
        } else {
            for (int i = 0; i < 3; i++) {
                String currentValue = SystemProperties.get(ACTUAL_LOGPERSIST_PROPERTY);
                if ((currentValue == null) || currentValue.equals("")) {
                    break;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    // Ignore
                }
            }
        }
    }

    public void writeLogpersistOption(Object newValue, boolean skipWarning) {
        if (mLogpersist == null) {
            return;
        }
        String currentTag = SystemProperties.get(
                AbstractLogdSizePreferenceController.SELECT_LOGD_TAG_PROPERTY);
        if ((currentTag != null) && currentTag.startsWith(
                AbstractLogdSizePreferenceController.SELECT_LOGD_TAG_SILENCE)) {
            newValue = null;
            skipWarning = true;
        }

        if ((newValue == null) || newValue.toString().equals("")) {
            if (skipWarning) {
                mLogpersistCleared = false;
            } else if (!mLogpersistCleared) {
                // if transitioning from on to off, pop up an are you sure?
                String currentValue = SystemProperties.get(ACTUAL_LOGPERSIST_PROPERTY);
                if ((currentValue != null) &&
                        currentValue.equals(SELECT_LOGPERSIST_PROPERTY_SERVICE)) {
                    showConfirmationDialog(mLogpersist);
                    return;
                }
            }
            setLogpersistOff(true);
            return;
        }

        String currentBuffer = SystemProperties.get(ACTUAL_LOGPERSIST_PROPERTY_BUFFER);
        if ((currentBuffer != null) && !currentBuffer.equals(newValue.toString())) {
            setLogpersistOff(false);
        }
        SystemProperties.set(SELECT_LOGPERSIST_PROPERTY_BUFFER, newValue.toString());
        SystemProperties.set(SELECT_LOGPERSIST_PROPERTY, SELECT_LOGPERSIST_PROPERTY_SERVICE);
        SystemPropPoker.getInstance().poke();
        for (int i = 0; i < 3; i++) {
            String currentValue = SystemProperties.get(ACTUAL_LOGPERSIST_PROPERTY);
            if ((currentValue != null)
                    && currentValue.equals(SELECT_LOGPERSIST_PROPERTY_SERVICE)) {
                break;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                // Ignore
            }
        }
        updateLogpersistValues();
    }
}
