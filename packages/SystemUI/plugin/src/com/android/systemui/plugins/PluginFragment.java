/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.annotation.Nullable;
import android.app.Fragment;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Bundle;
import android.view.LayoutInflater;

public abstract class PluginFragment extends Fragment implements Plugin {

    private static final String KEY_PLUGIN_PACKAGE = "plugin_package_name";
    private Context mPluginContext;

    @Override
    public void onCreate(Context sysuiContext, Context pluginContext) {
        mPluginContext = pluginContext;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            Context sysuiContext = getContext();
            Context pluginContext = recreatePluginContext(sysuiContext, savedInstanceState);
            onCreate(sysuiContext, pluginContext);
        }
        if (mPluginContext == null) {
            throw new RuntimeException("PluginFragments must call super.onCreate("
                    + "Context sysuiContext, Context pluginContext)");
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(KEY_PLUGIN_PACKAGE, getContext().getPackageName());
    }

    private Context recreatePluginContext(Context sysuiContext, Bundle savedInstanceState) {
        final String pkg = savedInstanceState.getString(KEY_PLUGIN_PACKAGE);
        try {
            ApplicationInfo appInfo = sysuiContext.getPackageManager().getApplicationInfo(pkg, 0);
            return PluginManager.getInstance(sysuiContext).getContext(appInfo, pkg);
        } catch (NameNotFoundException e) {
            throw new RuntimeException("Plugin with invalid package? " + pkg, e);
        }
    }

    @Override
    public LayoutInflater getLayoutInflater(Bundle savedInstanceState) {
        return super.getLayoutInflater(savedInstanceState).cloneInContext(mPluginContext);
    }

    /**
     * Should only be called after {@link Plugin#onCreate(Context, Context)}.
     */
    @Override
    public Context getContext() {
        return mPluginContext != null ? mPluginContext : super.getContext();
    }
}
