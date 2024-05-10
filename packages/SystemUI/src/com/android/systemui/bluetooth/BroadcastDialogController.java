/**
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.bluetooth;

import android.view.View;

import com.android.systemui.animation.DialogLaunchAnimator;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.statusbar.phone.SystemUIDialog;

import javax.inject.Inject;

/**
 * Controller to create BroadcastDialog objects.
 */
@SysUISingleton
public class BroadcastDialogController {

    private final DialogLaunchAnimator mDialogLaunchAnimator;
    private final BroadcastDialogDelegate.Factory mBroadcastDialogFactory;

    @Inject
    public BroadcastDialogController(
            DialogLaunchAnimator dialogLaunchAnimator,
            BroadcastDialogDelegate.Factory broadcastDialogFactory) {
        mDialogLaunchAnimator = dialogLaunchAnimator;
        mBroadcastDialogFactory = broadcastDialogFactory;
    }

    /** Creates a [BroadcastDialog] for the user to switch broadcast or change the output device
     *
     * @param currentBroadcastAppName Indicates the APP name currently broadcasting
     * @param outputPkgName Indicates the output media package name to be switched
     */
    public void createBroadcastDialog(
            String currentBroadcastAppName, String outputPkgName, View view) {
        SystemUIDialog broadcastDialog = mBroadcastDialogFactory.create(
                currentBroadcastAppName, outputPkgName).createDialog();
        if (view != null) {
            mDialogLaunchAnimator.showFromView(broadcastDialog, view);
        } else {
            broadcastDialog.show();
        }
    }
}
