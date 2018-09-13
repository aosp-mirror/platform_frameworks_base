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

import android.content.Context;
import android.os.Looper;

import com.android.systemui.Dependency;
import com.android.systemui.shared.plugins.PluginInitializer;
import com.android.systemui.R;

public class PluginInitializerImpl implements PluginInitializer {
    @Override
    public Looper getBgLooper() {
        return Dependency.get(Dependency.BG_LOOPER);
    }

    @Override
    public Runnable getBgInitCallback() {
        return new Runnable() {
            @Override
            public void run() {
                // Plugin dependencies that don't have another good home can go here, but
                // dependencies that have better places to init can happen elsewhere.
                Dependency.get(PluginDependencyProvider.class)
                        .allowPluginDependency(ActivityStarter.class);
            }
        };
    }

    @Override
    public String[] getWhitelistedPlugins(Context context) {
        return context.getResources().getStringArray(R.array.config_pluginWhitelist);
    }
}
