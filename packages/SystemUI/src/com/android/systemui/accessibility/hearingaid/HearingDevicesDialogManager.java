/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.accessibility.hearingaid;

import android.util.Log;

import androidx.concurrent.futures.CallbackToFutureAdapter;

import com.android.internal.jank.InteractionJankMonitor;
import com.android.systemui.animation.DialogCuj;
import com.android.systemui.animation.DialogTransitionAnimator;
import com.android.systemui.animation.Expandable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.statusbar.phone.SystemUIDialog;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * Factory to create {@link HearingDevicesDialogDelegate} objects and manage its lifecycle.
 */
@SysUISingleton
public class HearingDevicesDialogManager {

    private static final boolean DEBUG = true;
    private static final String TAG = "HearingDevicesDialogManager";
    private static final String INTERACTION_JANK_TAG = "hearing_devices_tile";
    private SystemUIDialog mDialog;
    private final DialogTransitionAnimator mDialogTransitionAnimator;
    private final HearingDevicesDialogDelegate.Factory mDialogFactory;
    private final HearingDevicesChecker mDevicesChecker;
    private final Executor mBackgroundExecutor;
    private final Executor mMainExecutor;

    @Inject
    public HearingDevicesDialogManager(
            DialogTransitionAnimator dialogTransitionAnimator,
            HearingDevicesDialogDelegate.Factory dialogFactory,
            HearingDevicesChecker devicesChecker,
            @Background Executor backgroundExecutor,
            @Main Executor mainExecutor) {
        mDialogTransitionAnimator = dialogTransitionAnimator;
        mDialogFactory = dialogFactory;
        mDevicesChecker = devicesChecker;
        mBackgroundExecutor = backgroundExecutor;
        mMainExecutor = mainExecutor;
    }

    /**
     * Shows the dialog.
     *
     * @param expandable {@link Expandable} from which the dialog is shown.
     */
    public void showDialog(Expandable expandable) {
        if (mDialog != null) {
            if (DEBUG) {
                Log.d(TAG, "HearingDevicesDialog already showing. Destroy it first.");
            }
            destroyDialog();
        }

        final ListenableFuture<Boolean> pairedHearingDeviceCheckTask =
                CallbackToFutureAdapter.getFuture(completer -> {
                    mBackgroundExecutor.execute(
                            () -> {
                                completer.set(mDevicesChecker.isAnyPairedHearingDevice());
                            });
                    // This value is used only for debug purposes: it will be used in toString()
                    // of returned future or error cases.
                    return "isAnyPairedHearingDevice check";
                });
        pairedHearingDeviceCheckTask.addListener(() -> {
            try {
                mDialog = mDialogFactory.create(!pairedHearingDeviceCheckTask.get()).createDialog();

                if (expandable != null) {
                    DialogTransitionAnimator.Controller controller =
                            expandable.dialogTransitionController(
                                    new DialogCuj(InteractionJankMonitor.CUJ_SHADE_DIALOG_OPEN,
                                            INTERACTION_JANK_TAG));
                    if (controller != null) {
                        mDialogTransitionAnimator.show(mDialog,
                                controller, /* animateBackgroundBoundsChange= */ true);
                        return;
                    }
                }
                mDialog.show();

            } catch (InterruptedException | ExecutionException e) {
                Log.e(TAG, "Exception occurs while running pairedHearingDeviceCheckTask", e);
            }
        }, mMainExecutor);
    }

    private void destroyDialog() {
        mDialog.dismiss();
        mDialog = null;
    }
}
