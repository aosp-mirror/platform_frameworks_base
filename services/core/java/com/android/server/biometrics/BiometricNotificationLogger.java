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

import android.hardware.biometrics.BiometricsProtoEnums;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.biometrics.sensors.BiometricNotificationUtils;
import com.android.server.biometrics.log.BiometricFrameworkStatsLogger;

/**
 * A class that logs metric info related to the posting/dismissal of Biometric FRR notifications.
 */
public class BiometricNotificationLogger extends NotificationListenerService {
    private static final String TAG = "FRRNotificationListener";
    private BiometricFrameworkStatsLogger mLogger;

    BiometricNotificationLogger() {
        this(BiometricFrameworkStatsLogger.getInstance());
    }

    @VisibleForTesting
    BiometricNotificationLogger(BiometricFrameworkStatsLogger logger) {
        mLogger = logger;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn, RankingMap map) {
        if (sbn == null || sbn.getTag() == null) {
            return;
        }
        switch (sbn.getTag()) {
            case BiometricNotificationUtils.FACE_ENROLL_NOTIFICATION_TAG:
            case BiometricNotificationUtils.FINGERPRINT_ENROLL_NOTIFICATION_TAG:
                final int modality =
                        sbn.getTag() == BiometricNotificationUtils.FACE_ENROLL_NOTIFICATION_TAG
                                ? BiometricsProtoEnums.MODALITY_FACE
                                : BiometricsProtoEnums.MODALITY_FINGERPRINT;
                Slog.d(TAG, "onNotificationPosted, tag=(" + sbn.getTag() + ")");
                mLogger.logFrameworkNotification(
                        BiometricsProtoEnums.FRR_NOTIFICATION_ACTION_SHOWN,
                        modality
                );
                break;
            default:
                break;
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap,
            int reason) {
        if (sbn == null || sbn.getTag() == null) {
            return;
        }
        switch (sbn.getTag()) {
            case BiometricNotificationUtils.FACE_ENROLL_NOTIFICATION_TAG:
            case BiometricNotificationUtils.FINGERPRINT_ENROLL_NOTIFICATION_TAG:
                Slog.d(TAG, "onNotificationRemoved, tag=("
                        + sbn.getTag() + "), reason=(" + reason + ")");
                final int modality =
                        sbn.getTag() == BiometricNotificationUtils.FACE_ENROLL_NOTIFICATION_TAG
                                ? BiometricsProtoEnums.MODALITY_FACE
                                : BiometricsProtoEnums.MODALITY_FINGERPRINT;
                switch (reason) {
                    // REASON_CLICK = 1
                    case NotificationListenerService.REASON_CLICK:
                        mLogger.logFrameworkNotification(
                                BiometricsProtoEnums.FRR_NOTIFICATION_ACTION_CLICKED,
                                modality);
                        break;
                    // REASON_CANCEL = 2
                    case NotificationListenerService.REASON_CANCEL:
                        mLogger.logFrameworkNotification(
                                BiometricsProtoEnums.FRR_NOTIFICATION_ACTION_DISMISSED,
                                modality);
                        break;
                    default:
                        Slog.d(TAG, "unhandled reason, ignoring logging");
                        break;
                }
                break;
            default:
                break;
        }
    }
}
