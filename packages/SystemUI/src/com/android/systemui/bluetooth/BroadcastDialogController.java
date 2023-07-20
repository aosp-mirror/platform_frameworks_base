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

import android.content.Context;
import android.view.View;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.animation.DialogLaunchAnimator;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.media.dialog.MediaOutputDialogFactory;

import javax.inject.Inject;

/**
 * Controller to create BroadcastDialog objects.
 */
@SysUISingleton
public class BroadcastDialogController {

    private Context mContext;
    private UiEventLogger mUiEventLogger;
    private DialogLaunchAnimator mDialogLaunchAnimator;
    private MediaOutputDialogFactory mMediaOutputDialogFactory;

    @Inject
    public BroadcastDialogController(Context context, UiEventLogger uiEventLogger,
            DialogLaunchAnimator dialogLaunchAnimator,
            MediaOutputDialogFactory mediaOutputDialogFactory) {
        mContext = context;
        mUiEventLogger = uiEventLogger;
        mDialogLaunchAnimator = dialogLaunchAnimator;
        mMediaOutputDialogFactory = mediaOutputDialogFactory;
    }

    public void createBroadcastDialog(String switchAppName, String outputPkgName,
            boolean aboveStatusBar, View view) {
        BroadcastDialog broadcastDialog = new BroadcastDialog(mContext, mMediaOutputDialogFactory,
                switchAppName, outputPkgName, mUiEventLogger);
        if (view != null) {
            mDialogLaunchAnimator.showFromView(broadcastDialog, view);
        } else {
            broadcastDialog.show();
        }
    }
}
