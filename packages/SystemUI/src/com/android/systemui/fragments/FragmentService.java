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
import android.content.res.Configuration;
import android.os.Handler;
import android.util.ArrayMap;
import android.util.Log;
import android.view.View;

import com.android.systemui.Dumpable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.statusbar.policy.ConfigurationController;

import java.io.PrintWriter;

import javax.inject.Inject;
import javax.inject.Provider;

/**
 * Holds a map of root views to FragmentHostStates and generates them as needed.
 * Also dispatches the configuration changes to all current FragmentHostStates.
 */
@SysUISingleton
public class FragmentService implements Dumpable {

    private static final String TAG = "FragmentService";

    private final ArrayMap<View, FragmentHostState> mHosts = new ArrayMap<>();
    /**
     * A map with the means to create fragments via Dagger injection.
     *
     * key: the fragment class name.
     * value: A {@link Provider} for the Fragment
     */
    private final ArrayMap<String, Provider<? extends Fragment>> mInjectionMap = new ArrayMap<>();
    private final Handler mHandler = new Handler();
    private final FragmentHostManager.Factory mFragmentHostManagerFactory;

    private ConfigurationController.ConfigurationListener mConfigurationListener =
            new ConfigurationController.ConfigurationListener() {
                @Override
                public void onConfigChanged(Configuration newConfig) {
                    for (FragmentHostState state : mHosts.values()) {
                        state.sendConfigurationChange(newConfig);
                    }
                }
            };

    @Inject
    public FragmentService(
            FragmentHostManager.Factory fragmentHostManagerFactory,
            ConfigurationController configurationController,
            DumpManager dumpManager) {
        mFragmentHostManagerFactory = fragmentHostManagerFactory;
        configurationController.addCallback(mConfigurationListener);

        dumpManager.registerNormalDumpable(this);
    }

    ArrayMap<String, Provider<? extends Fragment>> getInjectionMap() {
        return mInjectionMap;
    }

    /**
     * Adds a new Dagger component object that provides method(s) to create fragments via injection.
     */
    public void addFragmentInstantiationProvider(
            Class<?> fragmentCls, Provider<? extends Fragment> provider) {
        String fragmentName = fragmentCls.getName();
        if (mInjectionMap.containsKey(fragmentName)) {
            Log.w(TAG, "Fragment " + fragmentName + " is already provided by different"
                    + " Dagger component; Not adding method");
            return;
        }
        mInjectionMap.put(fragmentName, provider);
    }

    public FragmentHostManager getFragmentHostManager(View view) {
        View root = view.getRootView();
        FragmentHostState state = mHosts.get(root);
        if (state == null) {
            state = new FragmentHostState(root);
            mHosts.put(root, state);
        }
        return state.getFragmentHostManager();
    }

    public void removeAndDestroy(View view) {
        final FragmentHostState state = mHosts.remove(view.getRootView());
        if (state != null) {
            state.mFragmentHostManager.destroy();
        }
    }

    public void destroyAll() {
        for (FragmentHostState state : mHosts.values()) {
            state.mFragmentHostManager.destroy();
        }
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
        pw.println("Dumping fragments:");
        for (FragmentHostState state : mHosts.values()) {
            state.mFragmentHostManager.getFragmentManager().dump("  ", null, pw, args);
        }
    }

    private class FragmentHostState {
        private final View mView;

        private FragmentHostManager mFragmentHostManager;

        public FragmentHostState(View view) {
            mView = view;
            mFragmentHostManager = mFragmentHostManagerFactory.create(mView);
        }

        public void sendConfigurationChange(Configuration newConfig) {
            mHandler.post(() -> handleSendConfigurationChange(newConfig));
        }

        public FragmentHostManager getFragmentHostManager() {
            return mFragmentHostManager;
        }

        private void handleSendConfigurationChange(Configuration newConfig) {
            mFragmentHostManager.onConfigurationChanged(newConfig);
        }
    }
}
