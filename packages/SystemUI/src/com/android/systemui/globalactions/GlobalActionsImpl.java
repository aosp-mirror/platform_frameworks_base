/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui.globalactions;

import static android.app.StatusBarManager.DISABLE2_GLOBAL_ACTIONS;

import android.content.Context;

import com.android.systemui.plugins.GlobalActions;
import com.android.systemui.statusbar.BlurUtils;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import javax.inject.Inject;

public class GlobalActionsImpl implements GlobalActions, CommandQueue.Callbacks {

    private final Context mContext;
    private final KeyguardStateController mKeyguardStateController;
    private final DeviceProvisionedController mDeviceProvisionedController;
    private final BlurUtils mBlurUtils;
    private final CommandQueue mCommandQueue;
    private final GlobalActionsDialogLite mGlobalActionsDialog;
    private boolean mDisabled;
    private ShutdownUi mShutdownUi;

    @Inject
    public GlobalActionsImpl(Context context, CommandQueue commandQueue,
            GlobalActionsDialogLite globalActionsDialog, BlurUtils blurUtils,
            KeyguardStateController keyguardStateController,
            DeviceProvisionedController deviceProvisionedController,
            ShutdownUi shutdownUi) {
        mContext = context;
        mGlobalActionsDialog = globalActionsDialog;
        mKeyguardStateController = keyguardStateController;
        mDeviceProvisionedController = deviceProvisionedController;
        mCommandQueue = commandQueue;
        mBlurUtils = blurUtils;
        mCommandQueue.addCallback(this);
        mShutdownUi = shutdownUi;
    }

    @Override
    public void destroy() {
        mCommandQueue.removeCallback(this);
        mGlobalActionsDialog.destroy();
    }

    @Override
    public void showGlobalActions(GlobalActionsManager manager) {
        if (mDisabled) return;
        mGlobalActionsDialog.showOrHideDialog(mKeyguardStateController.isShowing(),
                mDeviceProvisionedController.isDeviceProvisioned(), null /* view */);
    }

    @Override
    public void showShutdownUi(boolean isReboot, String reason) {
        mShutdownUi.showShutdownUi(isReboot, reason);
    }
    @Override
    public void disable(int displayId, int state1, int state2, boolean animate) {
        final boolean disabled = (state2 & DISABLE2_GLOBAL_ACTIONS) != 0;
        if (displayId != mContext.getDisplayId() || disabled == mDisabled) return;
        mDisabled = disabled;
        if (disabled) {
            mGlobalActionsDialog.dismissDialog();
        }
    }
}
