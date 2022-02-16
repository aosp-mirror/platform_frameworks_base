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
import android.view.View;

import com.android.systemui.Dumpable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.qs.QSFragment;
import com.android.systemui.statusbar.phone.CollapsedStatusBarFragment;
import com.android.systemui.statusbar.policy.ConfigurationController;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import javax.inject.Inject;

import dagger.Subcomponent;

/**
 * Holds a map of root views to FragmentHostStates and generates them as needed.
 * Also dispatches the configuration changes to all current FragmentHostStates.
 */
@SysUISingleton
public class FragmentService implements Dumpable {

    private static final String TAG = "FragmentService";

    private final ArrayMap<View, FragmentHostState> mHosts = new ArrayMap<>();
    private final ArrayMap<String, Method> mInjectionMap = new ArrayMap<>();
    private final Handler mHandler = new Handler();
    private final FragmentCreator mFragmentCreator;

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
    public FragmentService(FragmentCreator.Factory fragmentCreatorFactory,
            ConfigurationController configurationController) {
        mFragmentCreator = fragmentCreatorFactory.build();
        initInjectionMap();
        configurationController.addCallback(mConfigurationListener);
    }

    ArrayMap<String, Method> getInjectionMap() {
        return mInjectionMap;
    }

    FragmentCreator getFragmentCreator() {
        return mFragmentCreator;
    }

    private void initInjectionMap() {
        for (Method method : FragmentCreator.class.getDeclaredMethods()) {
            if (Fragment.class.isAssignableFrom(method.getReturnType())
                    && (method.getModifiers() & Modifier.PUBLIC) != 0) {
                mInjectionMap.put(method.getReturnType().getName(), method);
            }
        }
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
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Dumping fragments:");
        for (FragmentHostState state : mHosts.values()) {
            state.mFragmentHostManager.getFragmentManager().dump("  ", fd, pw, args);
        }
    }

    /**
     * The subcomponent of dagger that holds all fragments that need injection.
     */
    @Subcomponent
    public interface FragmentCreator {
        /** Factory for creating a FragmentCreator. */
        @Subcomponent.Factory
        interface Factory {
            FragmentCreator build();
        }
        /**
         * Inject a QSFragment.
         */
        QSFragment createQSFragment();

        /** Inject a CollapsedStatusBarFragment. */
        CollapsedStatusBarFragment createCollapsedStatusBarFragment();
    }

    private class FragmentHostState {
        private final View mView;

        private FragmentHostManager mFragmentHostManager;

        public FragmentHostState(View view) {
            mView = view;
            mFragmentHostManager = new FragmentHostManager(FragmentService.this, mView);
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
