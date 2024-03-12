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

import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Intent;
import android.content.res.Resources;
import android.hardware.biometrics.BiometricSourceType;
import android.hardware.face.Face;
import android.hardware.face.FaceManager;
import android.hardware.fingerprint.Fingerprint;
import android.hardware.fingerprint.FingerprintManager;
import android.provider.Settings;
import android.util.Log;

import com.android.systemui.res.R;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.statusbar.phone.SystemUIDialog;

import javax.inject.Inject;
import javax.inject.Provider;

/**
 * Manages the creation of dialogs to be shown for biometric re enroll notifications.
 */
@SysUISingleton
public class BiometricNotificationDialogFactory {
    private static final String TAG = "BiometricNotificationDialogFactory";
    private final Resources mResources;
    private final SystemUIDialog.Factory mSystemUIDialogFactory;
    @Nullable private final FingerprintManager mFingerprintManager;
    @Nullable private final FaceManager mFaceManager;

    @Inject
    BiometricNotificationDialogFactory(
            @Main Resources resources,
            SystemUIDialog.Factory systemUIDialogFactory,
            @Nullable FingerprintManager fingerprintManager,
            @Nullable FaceManager faceManager) {
        mResources = resources;
        mSystemUIDialogFactory = systemUIDialogFactory;
        mFingerprintManager = fingerprintManager;
        mFaceManager = faceManager;
    }

    Dialog createReenrollDialog(
            int userId, ActivityStarter activityStarter, BiometricSourceType biometricSourceType) {
        SystemUIDialog sysuiDialog = mSystemUIDialogFactory.create();
        if (biometricSourceType == BiometricSourceType.FACE) {
            sysuiDialog.setTitle(mResources.getString(R.string.face_re_enroll_dialog_title));
            sysuiDialog.setMessage(mResources.getString(R.string.face_re_enroll_dialog_content));
        } else if (biometricSourceType == BiometricSourceType.FINGERPRINT) {
            sysuiDialog.setTitle(mResources.getString(R.string.fingerprint_re_enroll_dialog_title));
            if (mFingerprintManager.getEnrolledFingerprints().size() == 1) {
                sysuiDialog.setMessage(mResources.getString(
                        R.string.fingerprint_re_enroll_dialog_content_singular));
            } else {
                sysuiDialog.setMessage(mResources.getString(
                        R.string.fingerprint_re_enroll_dialog_content));
            }
        }

        sysuiDialog.setPositiveButton(R.string.biometric_re_enroll_dialog_confirm,
                (dialog, which) -> onReenrollDialogConfirm(
                        userId, biometricSourceType, activityStarter));
        sysuiDialog.setNegativeButton(R.string.biometric_re_enroll_dialog_cancel,
                (dialog, which) -> {});
        return sysuiDialog;
    }

    private Dialog createReenrollFailureDialog(BiometricSourceType biometricType) {
        final SystemUIDialog sysuiDialog = mSystemUIDialogFactory.create();

        if (biometricType == BiometricSourceType.FACE) {
            sysuiDialog.setMessage(mResources.getString(
                    R.string.face_reenroll_failure_dialog_content));
        } else if (biometricType == BiometricSourceType.FINGERPRINT) {
            sysuiDialog.setMessage(mResources.getString(
                    R.string.fingerprint_reenroll_failure_dialog_content));
        }

        sysuiDialog.setPositiveButton(R.string.ok, (dialog, which) -> {});
        return sysuiDialog;
    }

    private void onReenrollDialogConfirm(
            int userId, BiometricSourceType biometricType, ActivityStarter activityStarter) {
        if (biometricType == BiometricSourceType.FACE) {
            reenrollFace(userId, activityStarter);
        } else if (biometricType == BiometricSourceType.FINGERPRINT) {
            reenrollFingerprint(userId, activityStarter);
        }
    }

    @SuppressLint("MissingPermission")
    private void reenrollFingerprint(int userId, ActivityStarter activityStarter) {
        if (mFingerprintManager == null) {
            Log.e(TAG, "Not launching enrollment. Fingerprint manager was null!");
            createReenrollFailureDialog(BiometricSourceType.FINGERPRINT).show();
            return;
        }

        if (!mFingerprintManager.hasEnrolledTemplates(userId)) {
            createReenrollFailureDialog(BiometricSourceType.FINGERPRINT).show();
            return;
        }

        // Remove all enrolled fingerprint. Launch enrollment if successful.
        mFingerprintManager.removeAll(userId,
                new FingerprintManager.RemovalCallback() {
                    boolean mDidShowFailureDialog;

                    @Override
                    public void onRemovalError(
                            Fingerprint fingerprint, int errMsgId, CharSequence errString) {
                        Log.e(TAG, "Not launching enrollment."
                                + "Failed to remove existing face(s).");
                        if (!mDidShowFailureDialog) {
                            mDidShowFailureDialog = true;
                            createReenrollFailureDialog(BiometricSourceType.FINGERPRINT)
                                    .show();
                        }
                    }

                    @Override
                    public void onRemovalSucceeded(Fingerprint fingerprint, int remaining) {
                        if (!mDidShowFailureDialog && remaining == 0) {
                            Intent intent = new Intent(Settings.ACTION_FINGERPRINT_ENROLL);
                            intent.setPackage("com.android.settings");
                            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            activityStarter.startActivity(intent);
                        }
                    }
                });
    }

    @SuppressLint("MissingPermission")
    private void reenrollFace(int userId, ActivityStarter activityStarter) {
        if (mFaceManager == null) {
            Log.e(TAG, "Not launching enrollment. Face manager was null!");
            createReenrollFailureDialog(BiometricSourceType.FACE).show();
            return;
        }

        if (!mFaceManager.hasEnrolledTemplates(userId)) {
            createReenrollFailureDialog(BiometricSourceType.FACE).show();
            return;
        }

        // Remove all enrolled faces. Launch enrollment if successful.
        mFaceManager.removeAll(userId,
                new FaceManager.RemovalCallback() {
                    boolean mDidShowFailureDialog;

                    @Override
                    public void onRemovalError(Face face, int errMsgId, CharSequence errString) {
                        Log.e(TAG, "Not launching enrollment."
                                + "Failed to remove existing face(s).");
                        if (!mDidShowFailureDialog) {
                            mDidShowFailureDialog = true;
                            createReenrollFailureDialog(BiometricSourceType.FACE).show();
                        }
                    }

                    @Override
                    public void onRemovalSucceeded(Face face, int remaining) {
                        if (!mDidShowFailureDialog && remaining == 0) {
                            Intent intent = new Intent("android.settings.FACE_ENROLL");
                            intent.setPackage("com.android.settings");
                            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            activityStarter.startActivity(intent);
                        }
                    }
                });
    }

    interface ActivityStarter {
        void startActivity(Intent intent);
    }
}
