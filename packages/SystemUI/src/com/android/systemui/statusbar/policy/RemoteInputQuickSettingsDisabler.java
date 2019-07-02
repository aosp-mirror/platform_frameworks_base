/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.policy;

import android.app.StatusBarManager;
import android.content.Context;
import android.content.res.Configuration;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.SysUiServiceProvider;
import com.android.systemui.qs.QSFragment;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.phone.StatusBar;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Let {@link RemoteInputView} to control the visibility of QuickSetting.
 */
@Singleton
public class RemoteInputQuickSettingsDisabler
        implements ConfigurationController.ConfigurationListener {

    private Context mContext;
    @VisibleForTesting boolean mRemoteInputActive;
    @VisibleForTesting boolean misLandscape;
    private int mLastOrientation;
    @VisibleForTesting CommandQueue mCommandQueue;

    @Inject
    public RemoteInputQuickSettingsDisabler(Context context,
            ConfigurationController configController) {
        mContext = context;
        mCommandQueue = SysUiServiceProvider.getComponent(context, CommandQueue.class);
        mLastOrientation = mContext.getResources().getConfiguration().orientation;
        configController.addCallback(this);
    }

    public int adjustDisableFlags(int state) {
        if (mRemoteInputActive && misLandscape) {
            state |= StatusBarManager.DISABLE2_QUICK_SETTINGS;
        }

        return state;
    }

    public void setRemoteInputActive(boolean active){
        if(mRemoteInputActive != active){
            mRemoteInputActive = active;
            recomputeDisableFlags();
        }
    }

    @Override
    public void onConfigChanged(Configuration newConfig) {
        if (newConfig.orientation != mLastOrientation) {
            misLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE;
            mLastOrientation = newConfig.orientation;
            recomputeDisableFlags();
        }
    }

    /**
     * Reapplies the disable flags. Then the method adjustDisableFlags in this class will be invoked
     * in {@link QSFragment#disable(int, int, boolean)} and
     * {@link StatusBar#disable(int, int, boolean)}
     * to modify the disable flags according to the status of mRemoteInputActive and misLandscape.
     */
    private void recomputeDisableFlags() {
        mCommandQueue.recomputeDisableFlags(mContext.getDisplayId(), true);
    }
}
