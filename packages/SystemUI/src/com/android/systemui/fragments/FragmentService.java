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

import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.util.ArrayMap;
import android.util.Log;
import android.view.View;

import com.android.systemui.ConfigurationChangedReceiver;
import com.android.systemui.Dumpable;
import com.android.systemui.SystemUI;
import com.android.systemui.SystemUIApplication;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Holds a map of root views to FragmentHostStates and generates them as needed.
 * Also dispatches the configuration changes to all current FragmentHostStates.
 */
public class FragmentService implements ConfigurationChangedReceiver, Dumpable {

    private static final String TAG = "FragmentService";

    private final ArrayMap<View, FragmentHostState> mHosts = new ArrayMap<>();
    private final Handler mHandler = new Handler();
    private final Context mContext;

    public FragmentService(Context context) {
        mContext = context;
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

    public void destroyAll() {
        for (FragmentHostState state : mHosts.values()) {
            state.mFragmentHostManager.destroy();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        for (FragmentHostState state : mHosts.values()) {
            state.sendConfigurationChange(newConfig);
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Dumping fragments:");
        for (FragmentHostState state : mHosts.values()) {
            state.mFragmentHostManager.getFragmentManager().dump("  ", fd, pw, args);
        }
    }

    private class FragmentHostState {
        private final View mView;

        private FragmentHostManager mFragmentHostManager;

        public FragmentHostState(View view) {
            mView = view;
            mFragmentHostManager = new FragmentHostManager(mContext, FragmentService.this, mView);
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
