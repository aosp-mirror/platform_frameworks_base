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
package android.security.authenticationpolicy;

import static android.Manifest.permission.MANAGE_SECURE_LOCK_DEVICE;
import static android.security.Flags.FLAG_SECURE_LOCKDOWN;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.os.RemoteException;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * AuthenticationPolicyManager is a centralized interface for managing authentication related
 * policies on the device. This includes device locking capabilities to protect users in "at risk"
 * environments.
 *
 * AuthenticationPolicyManager is designed to protect Android users by integrating with apps and
 * key system components, such as the lock screen. It is not related to enterprise control surfaces
 * and does not offer additional administrative controls.
 *
 * <p>
 * To use this class, call {@link #enableSecureLockDevice} to enable secure lock on the device.
 * This will require the caller to have the
 * {@link android.Manifest.permission#MANAGE_SECURE_LOCK_DEVICE} permission.
 *
 * <p>
 * To disable secure lock on the device, call {@link #disableSecureLockDevice}. This will require
 * the caller to have the {@link android.Manifest.permission#MANAGE_SECURE_LOCK_DEVICE} permission.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(FLAG_SECURE_LOCKDOWN)
@SystemService(Context.AUTHENTICATION_POLICY_SERVICE)
public final class AuthenticationPolicyManager {
    private static final String TAG = "AuthenticationPolicyManager";

    @NonNull private final IAuthenticationPolicyService mAuthenticationPolicyService;
    @NonNull private final Context mContext;

    /**
     * Error result code for {@link #enableSecureLockDevice} and {@link
     * #disableSecureLockDevice}.
     *
     * Secure lock device request status unknown.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(FLAG_SECURE_LOCKDOWN)
    public static final int ERROR_UNKNOWN = 0;

    /**
     * Success result code for {@link #enableSecureLockDevice} and {@link #disableSecureLockDevice}.
     *
     * Secure lock device request successful.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(FLAG_SECURE_LOCKDOWN)
    public static final int SUCCESS = 1;

    /**
     * Error result code for {@link #enableSecureLockDevice} and {@link #disableSecureLockDevice}.
     *
     * Secure lock device is unsupported.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(FLAG_SECURE_LOCKDOWN)
    public static final int ERROR_UNSUPPORTED = 2;


    /**
     * Error result code for {@link #enableSecureLockDevice} and {@link #disableSecureLockDevice}.
     *
     * Invalid secure lock device request params provided.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(FLAG_SECURE_LOCKDOWN)
    public static final int ERROR_INVALID_PARAMS = 3;


    /**
     * Error result code for {@link #enableSecureLockDevice} and {@link #disableSecureLockDevice}.
     *
     * Secure lock device is unavailable because there are no biometrics enrolled on the device.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(FLAG_SECURE_LOCKDOWN)
    public static final int ERROR_NO_BIOMETRICS_ENROLLED = 4;

    /**
     * Error result code for {@link #enableSecureLockDevice} and {@link #disableSecureLockDevice}.
     *
     * Secure lock device is unavailable because the device has no biometric hardware or the
     * biometric sensors do not meet
     * {@link android.hardware.biometrics.BiometricManager.Authenticators#BIOMETRIC_STRONG}
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(FLAG_SECURE_LOCKDOWN)
    public static final int ERROR_INSUFFICIENT_BIOMETRICS = 5;

    /**
     * Error result code for {@link #enableSecureLockDevice}.
     *
     * Secure lock is already enabled.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(FLAG_SECURE_LOCKDOWN)
    public static final int ERROR_ALREADY_ENABLED = 6;

    /**
     * Communicates the current status of a request to enable secure lock on the device.
     *
     * @hide
     */
    @IntDef(prefix = {"ENABLE_SECURE_LOCK_DEVICE_STATUS_"}, value = {
            ERROR_UNKNOWN,
            SUCCESS,
            ERROR_UNSUPPORTED,
            ERROR_INVALID_PARAMS,
            ERROR_NO_BIOMETRICS_ENROLLED,
            ERROR_INSUFFICIENT_BIOMETRICS,
            ERROR_ALREADY_ENABLED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface EnableSecureLockDeviceRequestStatus {}

    /**
     * Communicates the current status of a request to disable secure lock on the device.
     *
     * @hide
     */
    @IntDef(prefix = {"DISABLE_SECURE_LOCK_DEVICE_STATUS_"}, value = {
            ERROR_UNKNOWN,
            SUCCESS,
            ERROR_UNSUPPORTED,
            ERROR_INVALID_PARAMS,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DisableSecureLockDeviceRequestStatus {}

    /** @hide */
    public AuthenticationPolicyManager(@NonNull Context context,
            @NonNull IAuthenticationPolicyService authenticationPolicyService) {
        mContext = context;
        mAuthenticationPolicyService = authenticationPolicyService;
    }

    /**
     * Called by a privileged component to remotely enable secure lock on the device.
     *
     * Secure lock is an enhanced security state that restricts access to sensitive data (app
     * notifications, widgets, quick settings, assistant, etc) and requires multi-factor
     * authentication for device entry, such as
     * {@link android.hardware.biometrics.BiometricManager.Authenticators#DEVICE_CREDENTIAL} and
     * {@link android.hardware.biometrics.BiometricManager.Authenticators#BIOMETRIC_STRONG}.
     *
     * If secure lock is already enabled when this method is called, it will return
     * {@link ERROR_ALREADY_ENABLED}.
     *
     * @param params EnableSecureLockDeviceParams for caller to supply params related to the secure
     *               lock device request
     * @return @EnableSecureLockDeviceRequestStatus int indicating the result of the secure lock
     * device request
     *
     * @hide
     */
    @EnableSecureLockDeviceRequestStatus
    @RequiresPermission(MANAGE_SECURE_LOCK_DEVICE)
    @SystemApi
    @FlaggedApi(FLAG_SECURE_LOCKDOWN)
    public int enableSecureLockDevice(@NonNull EnableSecureLockDeviceParams params) {
        try {
            return mAuthenticationPolicyService.enableSecureLockDevice(params);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Called by a privileged component to disable secure lock on the device.
     *
     * If secure lock is already disabled when this method is called, it will return
     * {@link SUCCESS}.
     *
     * @param params @DisableSecureLockDeviceParams for caller to supply params related to the
     *               secure lock device request
     * @return @DisableSecureLockDeviceRequestStatus int indicating the result of the secure lock
     * device request
     *
     * @hide
     */
    @DisableSecureLockDeviceRequestStatus
    @RequiresPermission(MANAGE_SECURE_LOCK_DEVICE)
    @SystemApi
    @FlaggedApi(FLAG_SECURE_LOCKDOWN)
    public int disableSecureLockDevice(@NonNull DisableSecureLockDeviceParams params) {
        try {
            return mAuthenticationPolicyService.disableSecureLockDevice(params);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
