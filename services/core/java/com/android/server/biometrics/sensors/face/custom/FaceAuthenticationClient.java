/*
* Copyright (C) 2022 The Pixel Experience Project
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package com.android.server.biometrics.sensors.face.custom;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.hardware.biometrics.BiometricAuthenticator;
import android.os.IBinder;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.util.custom.faceunlock.IFaceService;
import com.android.server.biometrics.Utils;
import com.android.server.biometrics.sensors.AuthenticationClient;
import com.android.server.biometrics.sensors.BiometricNotificationUtils;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.LockoutTracker;
import com.android.server.biometrics.sensors.face.UsageStats;
import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;

import java.util.ArrayList;
import java.util.function.Supplier;

class FaceAuthenticationClient extends AuthenticationClient<IFaceService> {
    private static final String TAG = "FaceAuthenticationClient";
    private final int[] mBiometricPromptIgnoreList;
    private final int[] mBiometricPromptIgnoreListVendor;
    private final ContentResolver mContentResolver;
    private final boolean mCustomHaptics;
    private final int[] mKeyguardIgnoreList;
    private final int[] mKeyguardIgnoreListVendor;
    private final UsageStats mUsageStats;
    private int mLastAcquire;

    FaceAuthenticationClient(Context context, Supplier<IFaceService> lazyDaemon, IBinder token, long requestId, ClientMonitorCallbackConverter listener, int targetUserId, long operationId, boolean restricted, String owner, int cookie, boolean requireConfirmation, int sensorId, BiometricLogger biometricLogger, BiometricContext biometricContext, boolean isStrongBiometric, LockoutTracker lockoutTracker, UsageStats usageStats, boolean allowBackgroundAuthentication) {
        super(context, lazyDaemon, token, listener, targetUserId, operationId, restricted, owner, cookie, requireConfirmation, sensorId, biometricLogger, biometricContext, isStrongBiometric, null /* taskStackListener */, lockoutTracker, allowBackgroundAuthentication, true, false);
        mUsageStats = usageStats;
        setRequestId(requestId);
        Resources resources = getContext().getResources();
        mBiometricPromptIgnoreList = resources.getIntArray(
                R.array.config_face_acquire_biometricprompt_ignorelist);
        mBiometricPromptIgnoreListVendor = resources.getIntArray(
                R.array.config_face_acquire_vendor_biometricprompt_ignorelist);
        mKeyguardIgnoreList = resources.getIntArray(
                R.array.config_face_acquire_keyguard_ignorelist);
        mKeyguardIgnoreListVendor = resources.getIntArray(
                R.array.config_face_acquire_vendor_keyguard_ignorelist);
        ContentResolver contentResolver = context.getContentResolver();
        mContentResolver = contentResolver;
        mCustomHaptics = Settings.Global.getInt(contentResolver, "face_custom_success_error", 0) == 1;
    }

    @Override
    protected void startHalOperation() {
        try {
            getFreshDaemon().authenticate(mOperationId);
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception when requesting auth", e);
            onError(1, 0);
            mCallback.onClientFinished(this, false);
        }
    }

    @Override
    protected void handleLifecycleAfterAuth(boolean authenticated) {
    }

    @Override
    protected void stopHalOperation() {
        try {
            getFreshDaemon().cancel();
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception when requesting cancel", e);
            onError(1, 0);
            mCallback.onClientFinished(this, false);
        }
    }

    @Override
    public boolean wasUserDetected() {
        return mLastAcquire != 11 && mLastAcquire != 21;
    }

    @Override
    public void onAuthenticated(BiometricAuthenticator.Identifier identifier, boolean authenticated, ArrayList<Byte> token) {
        super.onAuthenticated(identifier, authenticated, token);
        mUsageStats.addEvent(new UsageStats.AuthenticationEvent(getStartTimeMs(), System.currentTimeMillis() - getStartTimeMs(), authenticated, 0, 0, getTargetUserId()));
        mCallback.onClientFinished(this, true);
    }

    @Override
    public void onError(int error, int vendorCode) {
        mUsageStats.addEvent(new UsageStats.AuthenticationEvent(getStartTimeMs(), System.currentTimeMillis() - getStartTimeMs(), false, error, vendorCode, getTargetUserId()));
        if (error == 16) {
            BiometricNotificationUtils.showReEnrollmentNotification(getContext());
        }
        super.onError(error, vendorCode);
    }

    private int[] getAcquireIgnorelist() {
        return isBiometricPrompt() ? mBiometricPromptIgnoreList : mKeyguardIgnoreList;
    }

    private int[] getAcquireVendorIgnorelist() {
        return isBiometricPrompt() ? mBiometricPromptIgnoreListVendor : mKeyguardIgnoreListVendor;
    }

    private boolean shouldSend(int acquireInfo, int vendorCode) {
        if (acquireInfo == 22) {
            return !Utils.listContains(getAcquireVendorIgnorelist(), vendorCode);
        }
        return !Utils.listContains(getAcquireIgnorelist(), acquireInfo);
    }

    @Override
    public void onAcquired(int acquireInfo, int vendorCode) {
        mLastAcquire = acquireInfo;
        if (acquireInfo == 13) {
            BiometricNotificationUtils.showReEnrollmentNotification(getContext());
        }
        onAcquiredInternal(acquireInfo, vendorCode, shouldSend(acquireInfo, vendorCode));
    }
}
