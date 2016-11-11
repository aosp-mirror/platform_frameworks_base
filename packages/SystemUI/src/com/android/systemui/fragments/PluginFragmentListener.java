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
import android.util.Log;
import android.view.View;

import com.android.systemui.plugins.FragmentBase;
import com.android.systemui.plugins.Plugin;
import com.android.systemui.plugins.PluginListener;
import com.android.systemui.plugins.PluginManager;

public class PluginFragmentListener implements PluginListener<Plugin> {

    private static final String TAG = "PluginFragmentListener";

    private final FragmentHostManager mFragmentHostManager;
    private final PluginManager mPluginManager;
    private final Class<? extends Fragment> mDefaultClass;
    private final int mId;
    private final String mTag;
    private final Class<? extends FragmentBase> mExpectedInterface;

    public PluginFragmentListener(View view, String tag, int id,
            Class<? extends Fragment> defaultFragment,
            Class<? extends FragmentBase> expectedInterface) {
        mFragmentHostManager = FragmentHostManager.get(view);
        mPluginManager = PluginManager.getInstance(view.getContext());
        mExpectedInterface = expectedInterface;
        mTag = tag;
        mDefaultClass = defaultFragment;
        mId = id;
    }

    public void startListening(String action, int version) {
        try {
            setFragment(mDefaultClass.newInstance());
        } catch (InstantiationException | IllegalAccessException e) {
            Log.e(TAG, "Couldn't instantiate " + mDefaultClass.getName(), e);
        }
        mPluginManager.addPluginListener(action, this, version, false /* Only allow one */);
    }

    public void stopListening() {
        mPluginManager.removePluginListener(this);
    }

    private void setFragment(Fragment fragment) {
        mFragmentHostManager.getFragmentManager().beginTransaction()
                .replace(mId, fragment, mTag)
                .commit();
    }

    @Override
    public void onPluginConnected(Plugin plugin) {
        try {
            mExpectedInterface.cast(plugin);
            setFragment((Fragment) plugin);
        } catch (ClassCastException e) {
            Log.e(TAG, plugin.getClass().getName() + " must be a Fragment and implement "
                    + mExpectedInterface.getName(), e);
        }
    }

    @Override
    public void onPluginDisconnected(Plugin plugin) {
        try {
            setFragment(mDefaultClass.newInstance());
        } catch (InstantiationException | IllegalAccessException e) {
            Log.e(TAG, "Couldn't instantiate " + mDefaultClass.getName(), e);
        }
    }
}
