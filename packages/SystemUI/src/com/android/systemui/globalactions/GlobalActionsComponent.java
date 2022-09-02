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

import android.content.Context;
import android.os.RemoteException;
import android.os.ServiceManager;

import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.plugins.GlobalActions;
import com.android.systemui.plugins.GlobalActions.GlobalActionsManager;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.CommandQueue.Callbacks;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.policy.ExtensionController;
import com.android.systemui.statusbar.policy.ExtensionController.Extension;

import javax.inject.Inject;
import javax.inject.Provider;

/**
 * Manages power menu plugins and communicates power menu actions to the CentralSurfaces.
 */
@SysUISingleton
public class GlobalActionsComponent extends CoreStartable
        implements Callbacks, GlobalActionsManager {

    private final CommandQueue mCommandQueue;
    private final ExtensionController mExtensionController;
    private final Provider<GlobalActions> mGlobalActionsProvider;
    private GlobalActions mPlugin;
    private Extension<GlobalActions> mExtension;
    private IStatusBarService mBarService;
    private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;

    @Inject
    public GlobalActionsComponent(Context context, CommandQueue commandQueue,
            ExtensionController extensionController,
            Provider<GlobalActions> globalActionsProvider,
            StatusBarKeyguardViewManager statusBarKeyguardViewManager) {
        super(context);
        mCommandQueue = commandQueue;
        mExtensionController = extensionController;
        mGlobalActionsProvider = globalActionsProvider;
        mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;
    }

    @Override
    public void start() {
        mBarService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));
        mExtension = mExtensionController.newExtension(GlobalActions.class)
                .withPlugin(GlobalActions.class)
                .withDefault(mGlobalActionsProvider::get)
                .withCallback(this::onExtensionCallback)
                .build();
        mPlugin = mExtension.get();
        mCommandQueue.addCallback(this);
    }

    private void onExtensionCallback(GlobalActions newPlugin) {
        if (mPlugin != null) {
            mPlugin.destroy();
        }
        mPlugin = newPlugin;
    }

    @Override
    public void handleShowShutdownUi(boolean isReboot, String reason) {
        mExtension.get().showShutdownUi(isReboot, reason);
    }

    @Override
    public void handleShowGlobalActionsMenu() {
        mStatusBarKeyguardViewManager.setGlobalActionsVisible(true);
        mExtension.get().showGlobalActions(this);
    }

    @Override
    public void onGlobalActionsShown() {
        try {
            mBarService.onGlobalActionsShown();
        } catch (RemoteException e) {
        }
    }

    @Override
    public void onGlobalActionsHidden() {
        try {
            mStatusBarKeyguardViewManager.setGlobalActionsVisible(false);
            mBarService.onGlobalActionsHidden();
        } catch (RemoteException e) {
        }
    }

    @Override
    public void shutdown() {
        try {
            mBarService.shutdown();
        } catch (RemoteException e) {
        }
    }

    @Override
    public void reboot(boolean safeMode) {
        try {
            mBarService.reboot(safeMode);
        } catch (RemoteException e) {
        }
    }
}
