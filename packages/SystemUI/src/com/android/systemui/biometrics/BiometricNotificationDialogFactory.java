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

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.hardware.biometrics.BiometricSourceType;
import android.hardware.face.Face;
import android.hardware.face.FaceManager;
import android.hardware.fingerprint.Fingerprint;
import android.hardware.fingerprint.FingerprintManager;
import android.provider.Settings;
import android.util.Log;

import com.android.systemui.R;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.statusbar.phone.SystemUIDialog;

import javax.inject.Inject;

/**
 * Manages the creation of dialogs to be shown for biometric re enroll notifications.
 */
@SysUISingleton
public class BiometricNotificationDialogFactory {
    private static final String TAG = "BiometricNotificationDialogFactory";

    @Inject
    BiometricNotificationDialogFactory() {}

    Dialog createReenrollDialog(final Context context, final SystemUIDialog sysuiDialog,
            BiometricSourceType biometricSourceType) {
        if (biometricSourceType == BiometricSourceType.FACE) {
            sysuiDialog.setTitle(context.getString(R.string.face_re_enroll_dialog_title));
            sysuiDialog.setMessage(context.getString(R.string.face_re_enroll_dialog_content));
        } else if (biometricSourceType == BiometricSourceType.FINGERPRINT) {
            FingerprintManager fingerprintManager = context.getSystemService(
                    FingerprintManager.class);
            sysuiDialog.setTitle(context.getString(R.string.fingerprint_re_enroll_dialog_title));
            if (fingerprintManager.getEnrolledFingerprints().size() == 1) {
                sysuiDialog.setMessage(context.getString(
                        R.string.fingerprint_re_enroll_dialog_content_singular));
            } else {
                sysuiDialog.setMessage(context.getString(
                        R.string.fingerprint_re_enroll_dialog_content));
            }
        }

        sysuiDialog.setPositiveButton(R.string.biometric_re_enroll_dialog_confirm,
                (dialog, which) -> onReenrollDialogConfirm(context, biometricSourceType));
        sysuiDialog.setNegativeButton(R.string.biometric_re_enroll_dialog_cancel,
                (dialog, which) -> {});
        return sysuiDialog;
    }

    private static Dialog createReenrollFailureDialog(Context context,
            BiometricSourceType biometricType) {
        final SystemUIDialog sysuiDialog = new SystemUIDialog(context);

        if (biometricType == BiometricSourceType.FACE) {
            sysuiDialog.setMessage(context.getString(
                    R.string.face_reenroll_failure_dialog_content));
        } else if (biometricType == BiometricSourceType.FINGERPRINT) {
            sysuiDialog.setMessage(context.getString(
                    R.string.fingerprint_reenroll_failure_dialog_content));
        }

        sysuiDialog.setPositiveButton(R.string.ok, (dialog, which) -> {});
        return sysuiDialog;
    }

    private static void onReenrollDialogConfirm(final Context context,
            BiometricSourceType biometricType) {
        if (biometricType == BiometricSourceType.FACE) {
            reenrollFace(context);
        } else if (biometricType == BiometricSourceType.FINGERPRINT) {
            reenrollFingerprint(context);
        }
    }

    private static void reenrollFingerprint(Context context) {
        FingerprintManager fingerprintManager = context.getSystemService(FingerprintManager.class);
        if (fingerprintManager == null) {
            Log.e(TAG, "Not launching enrollment. Fingerprint manager was null!");
            createReenrollFailureDialog(context, BiometricSourceType.FINGERPRINT).show();
            return;
        }

        if (!fingerprintManager.hasEnrolledTemplates(context.getUserId())) {
            createReenrollFailureDialog(context, BiometricSourceType.FINGERPRINT).show();
            return;
        }

        // Remove all enrolled fingerprint. Launch enrollment if successful.
        fingerprintManager.removeAll(context.getUserId(),
                new FingerprintManager.RemovalCallback() {
                    boolean mDidShowFailureDialog;

                    @Override
                    public void onRemovalError(Fingerprint fingerprint, int errMsgId,
                            CharSequence errString) {
                        Log.e(TAG, "Not launching enrollment."
                                + "Failed to remove existing face(s).");
                        if (!mDidShowFailureDialog) {
                            mDidShowFailureDialog = true;
                            createReenrollFailureDialog(context, BiometricSourceType.FINGERPRINT)
                                    .show();
                        }
                    }

                    @Override
                    public void onRemovalSucceeded(Fingerprint fingerprint, int remaining) {
                        if (!mDidShowFailureDialog && remaining == 0) {
                            Intent intent = new Intent(Settings.ACTION_FINGERPRINT_ENROLL);
                            intent.setPackage("com.android.settings");
                            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            context.startActivity(intent);
                        }
                    }
                });
    }

    private static void reenrollFace(Context context) {
        FaceManager faceManager = context.getSystemService(FaceManager.class);
        if (faceManager == null) {
            Log.e(TAG, "Not launching enrollment. Face manager was null!");
            createReenrollFailureDialog(context, BiometricSourceType.FACE).show();
            return;
        }

        if (!faceManager.hasEnrolledTemplates(context.getUserId())) {
            createReenrollFailureDialog(context, BiometricSourceType.FACE).show();
            return;
        }

        // Remove all enrolled faces. Launch enrollment if successful.
        faceManager.removeAll(context.getUserId(),
                new FaceManager.RemovalCallback() {
                    boolean mDidShowFailureDialog;

                    @Override
                    public void onRemovalError(Face face, int errMsgId, CharSequence errString) {
                        Log.e(TAG, "Not launching enrollment."
                                + "Failed to remove existing face(s).");
                        if (!mDidShowFailureDialog) {
                            mDidShowFailureDialog = true;
                            createReenrollFailureDialog(context, BiometricSourceType.FACE).show();
                        }
                    }

                    @Override
                    public void onRemovalSucceeded(Face face, int remaining) {
                        if (!mDidShowFailureDialog && remaining == 0) {
                            Intent intent = new Intent("android.settings.FACE_ENROLL");
                            intent.setPackage("com.android.settings");
                            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            context.startActivity(intent);
                        }
                    }
                });
    }
}
