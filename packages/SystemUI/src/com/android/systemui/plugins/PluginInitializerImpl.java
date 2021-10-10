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
import android.os.Build;
import android.os.Looper;
import android.util.Log;

import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.shared.plugins.PluginEnabler;
import com.android.systemui.shared.plugins.PluginInitializer;
import com.android.systemui.shared.plugins.PluginManagerImpl;

public class PluginInitializerImpl implements PluginInitializer {

    /**
     * True if WTFs should lead to crashes
     */
    private static final boolean WTFS_SHOULD_CRASH = false;
    private boolean mWtfsSet;

    @Override
    public Looper getBgLooper() {
        return Dependency.get(Dependency.BG_LOOPER);
    }

    @Override
    public void onPluginManagerInit() {
        // Plugin dependencies that don't have another good home can go here, but
        // dependencies that have better places to init can happen elsewhere.
        Dependency.get(PluginDependencyProvider.class)
                .allowPluginDependency(ActivityStarter.class);
    }

    @Override
    public String[] getWhitelistedPlugins(Context context) {
        return context.getResources().getStringArray(R.array.config_pluginWhitelist);
    }

    public PluginEnabler getPluginEnabler(Context context) {
        return new PluginEnablerImpl(context);
    }

    @Override
    public void handleWtfs() {
        if (WTFS_SHOULD_CRASH && !mWtfsSet) {
            mWtfsSet = true;
            Log.setWtfHandler(new Log.TerribleFailureHandler() {
                @Override
                public void onTerribleFailure(String tag, Log.TerribleFailure what,
                        boolean system) {
                    throw new PluginManagerImpl.CrashWhilePluginActiveException(what);
                }
            });
        }
    }

    @Override
    public boolean isDebuggable() {
        return Build.IS_DEBUGGABLE;
    }
}
