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

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View.Visibility;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.systemui.animation.DialogTransitionAnimator;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.phone.SystemUIDialog;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedFactory;
import dagger.assisted.AssistedInject;

/**
 * Dialog for showing hearing devices controls.
 */
public class HearingDevicesDialogDelegate implements SystemUIDialog.Delegate {

    private final SystemUIDialog.Factory mSystemUIDialogFactory;
    private final DialogTransitionAnimator mDialogTransitionAnimator;
    private final ActivityStarter mActivityStarter;
    private final boolean mShowPairNewDevice;

    private SystemUIDialog mDialog;
    private Button mPairButton;

    /** Factory to create a {@link HearingDevicesDialogDelegate} dialog instance. */
    @AssistedFactory
    public interface Factory {
        /** Create a {@link HearingDevicesDialogDelegate} instance */
        HearingDevicesDialogDelegate create(
                boolean showPairNewDevice);
    }

    @AssistedInject
    public HearingDevicesDialogDelegate(
            @Assisted boolean showPairNewDevice,
            SystemUIDialog.Factory systemUIDialogFactory,
            ActivityStarter activityStarter,
            DialogTransitionAnimator dialogTransitionAnimator) {
        mShowPairNewDevice = showPairNewDevice;
        mSystemUIDialogFactory = systemUIDialogFactory;
        mActivityStarter = activityStarter;
        mDialogTransitionAnimator = dialogTransitionAnimator;
    }

    @Override
    public SystemUIDialog createDialog() {
        SystemUIDialog dialog = mSystemUIDialogFactory.create(this);
        dismissDialogIfExists();
        mDialog = dialog;

        return dialog;
    }

    @Override
    public void beforeCreate(@NonNull SystemUIDialog dialog, @Nullable Bundle savedInstanceState) {
        dialog.setTitle(R.string.quick_settings_hearing_devices_dialog_title);
        dialog.setView(LayoutInflater.from(dialog.getContext()).inflate(
                R.layout.hearing_devices_tile_dialog, null));
        dialog.setPositiveButton(
                R.string.quick_settings_done,
                /* onClick = */ null,
                /* dismissOnClick = */ true
        );
    }

    @Override
    public void onCreate(@NonNull SystemUIDialog dialog, @Nullable Bundle savedInstanceState) {
        mPairButton = dialog.requireViewById(R.id.pair_new_device_button);

        setupPairNewDeviceButton(dialog, mShowPairNewDevice ? VISIBLE : GONE);
    }

    private void setupPairNewDeviceButton(SystemUIDialog dialog, @Visibility int visibility) {
        if (visibility == VISIBLE) {
            mPairButton.setOnClickListener(v -> {
                dismissDialogIfExists();
                final Intent intent = new Intent(Settings.ACTION_HEARING_DEVICE_PAIRING_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                mActivityStarter.postStartActivityDismissingKeyguard(intent, /* delay= */ 0,
                        mDialogTransitionAnimator.createActivityTransitionController(dialog));
            });
        } else {
            mPairButton.setVisibility(GONE);
        }
    }

    private void dismissDialogIfExists() {
        if (mDialog != null) {
            mDialog.dismiss();
        }
    }
}
