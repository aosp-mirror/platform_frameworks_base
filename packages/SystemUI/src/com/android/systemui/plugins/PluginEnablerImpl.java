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
import android.content.pm.PackageManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.shared.plugins.PluginEnabler;

public class PluginEnablerImpl implements PluginEnabler {

    final private PackageManager mPm;

    public PluginEnablerImpl(Context context) {
        this(context.getPackageManager());
    }

    @VisibleForTesting public PluginEnablerImpl(PackageManager pm) {
        mPm = pm;
    }

    @Override
    public void setEnabled(ComponentName component, boolean enabled) {
        final int desiredState = enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        mPm.setComponentEnabledSetting(component, desiredState, PackageManager.DONT_KILL_APP);
    }

    @Override
    public boolean isEnabled(ComponentName component) {
        return mPm.getComponentEnabledSetting(component)
                != PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    }
}
