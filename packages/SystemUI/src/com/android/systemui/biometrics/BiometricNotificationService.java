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

import static android.app.PendingIntent.FLAG_IMMUTABLE;

import static com.android.systemui.biometrics.BiometricNotificationBroadcastReceiver.ACTION_SHOW_FACE_REENROLL_DIALOG;
import static com.android.systemui.biometrics.BiometricNotificationBroadcastReceiver.ACTION_SHOW_FINGERPRINT_REENROLL_DIALOG;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.biometrics.BiometricFaceConstants;
import android.hardware.biometrics.BiometricSourceType;
import android.hardware.biometrics.BiometricStateListener;
import android.hardware.face.FaceManager;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.CoreStartable;
import com.android.systemui.res.R;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import java.util.Optional;

import javax.inject.Inject;

/**
 * Handles showing system notifications related to biometric unlock.
 */
@SysUISingleton
public class BiometricNotificationService implements CoreStartable {

    private static final String TAG = "BiometricNotificationService";
    private static final String CHANNEL_ID = "BiometricHiPriNotificationChannel";
    private static final String CHANNEL_NAME = " Biometric Unlock";
    private static final int FACE_NOTIFICATION_ID = 1;
    private static final int FINGERPRINT_NOTIFICATION_ID = 2;
    private static final long SHOW_NOTIFICATION_DELAY_MS = 5_000L; // 5 seconds
    private static final int REENROLL_REQUIRED = 1;
    private static final int REENROLL_NOT_REQUIRED = 0;

    private final Context mContext;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final KeyguardStateController mKeyguardStateController;
    private final Handler mHandler;
    private final NotificationManager mNotificationManager;
    private final BiometricNotificationBroadcastReceiver mBroadcastReceiver;
    private final FingerprintReEnrollNotification mFingerprintReEnrollNotification;
    private final FingerprintManager mFingerprintManager;
    private final FaceManager mFaceManager;
    private NotificationChannel mNotificationChannel;
    private boolean mFaceNotificationQueued;
    private boolean mFingerprintNotificationQueued;
    private boolean mFingerprintReenrollRequired;

    private final KeyguardStateController.Callback mKeyguardStateControllerCallback =
            new KeyguardStateController.Callback() {
                private boolean mIsShowing = true;
                @Override
                public void onKeyguardShowingChanged() {
                    if (mKeyguardStateController.isShowing()
                            || mKeyguardStateController.isShowing() == mIsShowing) {
                        mIsShowing = mKeyguardStateController.isShowing();
                        return;
                    }
                    mIsShowing = mKeyguardStateController.isShowing();
                    if (isFaceReenrollRequired(mContext) && !mFaceNotificationQueued) {
                        queueFaceReenrollNotification();
                    }
                    if (mFingerprintReenrollRequired && !mFingerprintNotificationQueued) {
                        mFingerprintReenrollRequired = false;
                        queueFingerprintReenrollNotification();
                    }
                }
            };

    private final KeyguardUpdateMonitorCallback mKeyguardUpdateMonitorCallback =
            new KeyguardUpdateMonitorCallback() {
                @Override
                public void onBiometricError(int msgId, String errString,
                        BiometricSourceType biometricSourceType) {
                    if (msgId == BiometricFaceConstants.BIOMETRIC_ERROR_RE_ENROLL
                            && biometricSourceType == BiometricSourceType.FACE) {
                        Settings.Secure.putIntForUser(mContext.getContentResolver(),
                                Settings.Secure.FACE_UNLOCK_RE_ENROLL, REENROLL_REQUIRED,
                                UserHandle.USER_CURRENT);
                    }
                }

                @Override
                public void onBiometricHelp(int msgId, String helpString,
                        BiometricSourceType biometricSourceType) {
                    if (biometricSourceType == BiometricSourceType.FINGERPRINT
                            && mFingerprintReEnrollNotification.isFingerprintReEnrollRequired(
                                    msgId)) {
                        mFingerprintReenrollRequired = true;
                    }
                }
            };

    private final BiometricStateListener mFaceStateListener = new BiometricStateListener() {
        @Override
        public void onEnrollmentsChanged(int userId, int sensorId, boolean hasEnrollments) {
            mNotificationManager.cancelAsUser(TAG, FACE_NOTIFICATION_ID, UserHandle.CURRENT);
        }
    };

    private final BiometricStateListener mFingerprintStateListener = new BiometricStateListener() {
        @Override
        public void onEnrollmentsChanged(int userId, int sensorId, boolean hasEnrollments) {
            mNotificationManager.cancelAsUser(TAG, FINGERPRINT_NOTIFICATION_ID, UserHandle.CURRENT);
        }
    };

    @Inject
    public BiometricNotificationService(@NonNull Context context,
            @NonNull KeyguardUpdateMonitor keyguardUpdateMonitor,
            @NonNull KeyguardStateController keyguardStateController,
            @NonNull Handler handler, @NonNull NotificationManager notificationManager,
            @NonNull BiometricNotificationBroadcastReceiver biometricNotificationBroadcastReceiver,
            @NonNull Optional<FingerprintReEnrollNotification> fingerprintReEnrollNotification,
            @Nullable FingerprintManager fingerprintManager,
            @Nullable FaceManager faceManager) {
        mContext = context;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mKeyguardStateController = keyguardStateController;
        mHandler = handler;
        mNotificationManager = notificationManager;
        mBroadcastReceiver = biometricNotificationBroadcastReceiver;
        mFingerprintReEnrollNotification = fingerprintReEnrollNotification.orElse(
                new FingerprintReEnrollNotificationImpl());
        mFingerprintManager = fingerprintManager;
        mFaceManager = faceManager;
    }

    @Override
    public void start() {
        mKeyguardUpdateMonitor.registerCallback(mKeyguardUpdateMonitorCallback);
        mKeyguardStateController.addCallback(mKeyguardStateControllerCallback);
        mNotificationChannel = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH);
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ACTION_SHOW_FINGERPRINT_REENROLL_DIALOG);
        intentFilter.addAction(ACTION_SHOW_FACE_REENROLL_DIALOG);
        mContext.registerReceiver(mBroadcastReceiver, intentFilter,
                Context.RECEIVER_EXPORTED_UNAUDITED);
        if (mFingerprintManager != null) {
            mFingerprintManager.registerBiometricStateListener(mFingerprintStateListener);
        }
        if (mFaceManager != null) {
            mFaceManager.registerBiometricStateListener(mFaceStateListener);
        }
        Settings.Secure.putIntForUser(mContext.getContentResolver(),
                Settings.Secure.FACE_UNLOCK_RE_ENROLL, REENROLL_NOT_REQUIRED,
                UserHandle.USER_CURRENT);
    }

    private void queueFaceReenrollNotification() {
        Log.d(TAG, "Face re-enroll notification queued.");
        mFaceNotificationQueued = true;
        final String title = mContext.getString(R.string.face_re_enroll_notification_title);
        final String content = mContext.getString(
                R.string.biometric_re_enroll_notification_content);
        final String name = mContext.getString(R.string.face_re_enroll_notification_name);
        mHandler.postDelayed(
                () -> showNotification(ACTION_SHOW_FACE_REENROLL_DIALOG, title, content, name,
                        FACE_NOTIFICATION_ID),
                SHOW_NOTIFICATION_DELAY_MS);
    }

    private void queueFingerprintReenrollNotification() {
        Log.d(TAG, "Fingerprint re-enroll notification queued.");
        mFingerprintNotificationQueued = true;
        final String title = mContext.getString(R.string.fingerprint_re_enroll_notification_title);
        final String content = mContext.getString(
                R.string.biometric_re_enroll_notification_content);
        final String name = mContext.getString(R.string.fingerprint_re_enroll_notification_name);
        mHandler.postDelayed(
                () -> showNotification(ACTION_SHOW_FINGERPRINT_REENROLL_DIALOG, title, content,
                        name, FINGERPRINT_NOTIFICATION_ID),
                SHOW_NOTIFICATION_DELAY_MS);
    }

    private void showNotification(String action, CharSequence title, CharSequence content,
            CharSequence name, int notificationId) {
        if (notificationId == FACE_NOTIFICATION_ID) {
            mFaceNotificationQueued = false;
        } else if (notificationId == FINGERPRINT_NOTIFICATION_ID) {
            mFingerprintNotificationQueued = false;
        }

        if (mNotificationManager == null) {
            Log.e(TAG, "Failed to show notification "
                    + action + ". Notification manager is null!");
            return;
        }

        final Intent onClickIntent = new Intent(action);
        final PendingIntent onClickPendingIntent = PendingIntent.getBroadcastAsUser(mContext,
                0 /* requestCode */, onClickIntent, FLAG_IMMUTABLE, UserHandle.CURRENT);

        final Notification notification = new Notification.Builder(mContext, CHANNEL_ID)
                .setCategory(Notification.CATEGORY_SYSTEM)
                .setSmallIcon(com.android.internal.R.drawable.ic_lock)
                .setContentTitle(title)
                .setContentText(content)
                .setSubText(name)
                .setContentIntent(onClickPendingIntent)
                .setAutoCancel(true)
                .setLocalOnly(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .build();

        mNotificationManager.createNotificationChannel(mNotificationChannel);
        mNotificationManager.notifyAsUser(TAG, notificationId, notification, UserHandle.CURRENT);
    }

    private boolean isFaceReenrollRequired(Context context) {
        final int settingValue =
                Settings.Secure.getIntForUser(context.getContentResolver(),
                        Settings.Secure.FACE_UNLOCK_RE_ENROLL, REENROLL_NOT_REQUIRED,
                        UserHandle.USER_CURRENT);
        return settingValue == REENROLL_REQUIRED;
    }
}
