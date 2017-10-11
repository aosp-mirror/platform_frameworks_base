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

package com.android.systemui.fragments;

import android.app.Fragment;
import android.content.Context;
import android.util.Log;
import android.view.View;

import com.android.systemui.Dependency;
import com.android.systemui.plugins.FragmentBase;
import com.android.systemui.plugins.Plugin;
import com.android.systemui.plugins.PluginListener;
import com.android.systemui.plugins.PluginManager;

public class PluginFragmentListener implements PluginListener<Plugin> {

    private static final String TAG = "PluginFragmentListener";

    private final FragmentHostManager mFragmentHostManager;
    private final PluginManager mPluginManager;
    private final Class<? extends Fragment> mDefaultClass;
    private final Class<? extends FragmentBase> mExpectedInterface;
    private final String mTag;

    public PluginFragmentListener(View view, String tag, Class<? extends Fragment> defaultFragment,
            Class<? extends FragmentBase> expectedInterface) {
        mTag = tag;
        mFragmentHostManager = FragmentHostManager.get(view);
        mPluginManager = Dependency.get(PluginManager.class);
        mExpectedInterface = expectedInterface;
        mDefaultClass = defaultFragment;
    }

    public void startListening() {
        mPluginManager.addPluginListener(this, mExpectedInterface,
                false /* Only allow one */);
    }

    public void stopListening() {
        mPluginManager.removePluginListener(this);
    }

    @Override
    public void onPluginConnected(Plugin plugin, Context pluginContext) {
        try {
            mExpectedInterface.cast(plugin);
            Fragment.class.cast(plugin);
            mFragmentHostManager.getPluginManager().setCurrentPlugin(mTag,
                    plugin.getClass().getName(), pluginContext);
        } catch (ClassCastException e) {
            Log.e(TAG, plugin.getClass().getName() + " must be a Fragment and implement "
                    + mExpectedInterface.getName(), e);
        }
    }

    @Override
    public void onPluginDisconnected(Plugin plugin) {
        mFragmentHostManager.getPluginManager().removePlugin(mTag,
                plugin.getClass().getName(), mDefaultClass.getName());
    }
}
