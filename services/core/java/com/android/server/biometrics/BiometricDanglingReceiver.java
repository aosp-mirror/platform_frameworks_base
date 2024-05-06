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
package com.android.server.biometrics;

import static android.content.Intent.ACTION_CLOSE_SYSTEM_DIALOGS;

import android.annotation.NonNull;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.provider.Settings;
import android.util.Slog;

import com.android.server.biometrics.sensors.BiometricNotificationUtils;

/**
 * Receives broadcast to biometrics dangling notification.
 */
public class BiometricDanglingReceiver extends BroadcastReceiver {
    private static final String TAG = "BiometricDanglingReceiver";

    public static final String ACTION_FINGERPRINT_RE_ENROLL_LAUNCH =
            "action_fingerprint_re_enroll_launch";
    public static final String ACTION_FINGERPRINT_RE_ENROLL_DISMISS =
            "action_fingerprint_re_enroll_dismiss";

    public static final String ACTION_FACE_RE_ENROLL_LAUNCH =
            "action_face_re_enroll_launch";
    public static final String ACTION_FACE_RE_ENROLL_DISMISS =
            "action_face_re_enroll_dismiss";

    public static final String FACE_SETTINGS_ACTION = "android.settings.FACE_SETTINGS";

    private static final String SETTINGS_PACKAGE = "com.android.settings";

    /**
     * Constructor for BiometricDanglingReceiver.
     *
     * @param context context
     * @param modality the value from BiometricsProtoEnums.MODALITY_*
     */
    public BiometricDanglingReceiver(@NonNull Context context, int modality) {
        final IntentFilter intentFilter = new IntentFilter();
        if (modality == BiometricsProtoEnums.MODALITY_FINGERPRINT) {
            intentFilter.addAction(ACTION_FINGERPRINT_RE_ENROLL_LAUNCH);
            intentFilter.addAction(ACTION_FINGERPRINT_RE_ENROLL_DISMISS);
        } else if (modality == BiometricsProtoEnums.MODALITY_FACE) {
            intentFilter.addAction(ACTION_FACE_RE_ENROLL_LAUNCH);
            intentFilter.addAction(ACTION_FACE_RE_ENROLL_DISMISS);
        }
        context.registerReceiver(this, intentFilter);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Slog.d(TAG, "Received: " + intent.getAction());
        if (ACTION_FINGERPRINT_RE_ENROLL_LAUNCH.equals(intent.getAction())) {
            launchBiometricEnrollActivity(context, Settings.ACTION_FINGERPRINT_ENROLL);
            BiometricNotificationUtils.cancelFingerprintReEnrollNotification(context);
        } else if (ACTION_FINGERPRINT_RE_ENROLL_DISMISS.equals(intent.getAction())) {
            BiometricNotificationUtils.cancelFingerprintReEnrollNotification(context);
        } else if (ACTION_FACE_RE_ENROLL_LAUNCH.equals(intent.getAction())) {
            launchBiometricEnrollActivity(context, FACE_SETTINGS_ACTION);
            BiometricNotificationUtils.cancelFaceReEnrollNotification(context);
        } else if (ACTION_FACE_RE_ENROLL_DISMISS.equals(intent.getAction())) {
            BiometricNotificationUtils.cancelFaceReEnrollNotification(context);
        }
        context.unregisterReceiver(this);
    }

    private void launchBiometricEnrollActivity(Context context, String action) {
        context.sendBroadcast(new Intent(ACTION_CLOSE_SYSTEM_DIALOGS));
        final Intent intent = new Intent(action);
        intent.setPackage(SETTINGS_PACKAGE);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
}
