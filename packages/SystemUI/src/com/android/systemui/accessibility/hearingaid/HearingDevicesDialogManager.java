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

import android.bluetooth.BluetoothDevice;
import android.util.Log;

import androidx.annotation.Nullable;

import com.android.internal.jank.InteractionJankMonitor;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.systemui.animation.DialogCuj;
import com.android.systemui.animation.DialogTransitionAnimator;
import com.android.systemui.animation.Expandable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.statusbar.phone.SystemUIDialog;

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
    private final LocalBluetoothManager mLocalBluetoothManager;

    @Inject
    public HearingDevicesDialogManager(
            DialogTransitionAnimator dialogTransitionAnimator,
            HearingDevicesDialogDelegate.Factory dialogFactory,
            @Nullable LocalBluetoothManager localBluetoothManager) {
        mDialogTransitionAnimator = dialogTransitionAnimator;
        mDialogFactory = dialogFactory;
        mLocalBluetoothManager = localBluetoothManager;
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

        mDialog = mDialogFactory.create(!isAnyBondedHearingDevice()).createDialog();

        if (expandable != null) {
            DialogTransitionAnimator.Controller controller = expandable.dialogTransitionController(
                    new DialogCuj(InteractionJankMonitor.CUJ_SHADE_DIALOG_OPEN,
                            INTERACTION_JANK_TAG));
            if (controller != null) {
                mDialogTransitionAnimator.show(mDialog,
                        controller, /* animateBackgroundBoundsChange= */ true);
                return;
            }
        }
        mDialog.show();
    }

    private void destroyDialog() {
        mDialog.dismiss();
        mDialog = null;
    }

    private boolean isAnyBondedHearingDevice() {
        if (mLocalBluetoothManager == null) {
            return false;
        }
        if (!mLocalBluetoothManager.getBluetoothAdapter().isEnabled()) {
            return false;
        }

        return mLocalBluetoothManager.getCachedDeviceManager().getCachedDevicesCopy().stream()
                .anyMatch(device -> device.isHearingAidDevice()
                        && device.getBondState() != BluetoothDevice.BOND_NONE);
    }
}
