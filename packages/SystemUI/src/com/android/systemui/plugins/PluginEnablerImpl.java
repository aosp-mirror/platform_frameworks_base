/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.plugins;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.shared.plugins.PluginEnabler;

import javax.inject.Inject;
import javax.inject.Singleton;

/** */
@Singleton
public class PluginEnablerImpl implements PluginEnabler {
    private static final String TAG = "PluginEnablerImpl";
    private static final String CRASH_DISABLED_PLUGINS_PREF_FILE = "auto_disabled_plugins_prefs";

    private final PackageManager mPm;
    private final SharedPreferences mAutoDisabledPrefs;

    public PluginEnablerImpl(Context context) {
        this(context, context.getPackageManager());
    }

    @Inject
    @VisibleForTesting public PluginEnablerImpl(Context context, PackageManager pm) {
        mAutoDisabledPrefs = context.getSharedPreferences(
                CRASH_DISABLED_PLUGINS_PREF_FILE, Context.MODE_PRIVATE);
        mPm = pm;
    }

    @Override
    public void setEnabled(ComponentName component) {
        setDisabled(component, ENABLED);
    }

    @Override
    public void setDisabled(ComponentName component, @DisableReason int reason) {
        boolean enabled = reason == ENABLED;
        final int desiredState = enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        mPm.setComponentEnabledSetting(component, desiredState, PackageManager.DONT_KILL_APP);
        if (enabled) {
            mAutoDisabledPrefs.edit().remove(component.flattenToString()).apply();
        } else {
            mAutoDisabledPrefs.edit().putInt(component.flattenToString(), reason).apply();
        }
    }

    @Override
    public boolean isEnabled(ComponentName component) {
        try {
            return mPm.getComponentEnabledSetting(component)
                    != PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        } catch (IllegalArgumentException ex) {
            Log.e(TAG, "Package Manager Exception", ex);
            return false;
        }
    }

    @Override
    public @DisableReason int getDisableReason(ComponentName componentName) {
        if (isEnabled(componentName)) {
            return ENABLED;
        }
        return mAutoDisabledPrefs.getInt(componentName.flattenToString(), DISABLED_UNKNOWN);
    }
}
