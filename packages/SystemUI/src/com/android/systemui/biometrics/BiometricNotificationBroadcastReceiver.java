/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.biometrics;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.biometrics.BiometricSourceType;

import com.android.systemui.dagger.SysUISingleton;

import javax.inject.Inject;

/**
 * Receives broadcasts sent by {@link BiometricNotificationService} and takes
 * the appropriate action.
 */
@SysUISingleton
public class BiometricNotificationBroadcastReceiver extends BroadcastReceiver {
    static final String ACTION_SHOW_FACE_REENROLL_DIALOG = "face_action_show_reenroll_dialog";
    static final String ACTION_SHOW_FINGERPRINT_REENROLL_DIALOG =
            "fingerprint_action_show_reenroll_dialog";

    private static final String TAG = "BiometricNotificationBroadcastReceiver";

    private final Context mContext;
    private final BiometricNotificationDialogFactory mNotificationDialogFactory;
    @Inject
    BiometricNotificationBroadcastReceiver(
            Context context,
            BiometricNotificationDialogFactory notificationDialogFactory) {
        mContext = context;
        mNotificationDialogFactory = notificationDialogFactory;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();

        switch (action) {
            case ACTION_SHOW_FACE_REENROLL_DIALOG:
                mNotificationDialogFactory.createReenrollDialog(
                        mContext.getUserId(),
                        mContext::startActivity,
                        BiometricSourceType.FACE)
                        .show();
                break;
            case ACTION_SHOW_FINGERPRINT_REENROLL_DIALOG:
                mNotificationDialogFactory.createReenrollDialog(
                        mContext.getUserId(),
                        mContext::startActivity,
                        BiometricSourceType.FINGERPRINT)
                        .show();
                break;
            default:
                break;
        }
    }
}
