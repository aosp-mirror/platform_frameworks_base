/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.hardware.biometrics;

import static android.Manifest.permission.USE_BIOMETRIC;
import static android.Manifest.permission.USE_BIOMETRIC_INTERNAL;

import android.annotation.IntDef;
import android.annotation.RequiresPermission;
import android.annotation.SystemService;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.RemoteException;
import android.util.Slog;

/**
 * A class that contains biometric utilities. For authentication, see {@link BiometricPrompt}.
 */
@SystemService(Context.BIOMETRIC_SERVICE)
public class BiometricManager {

    private static final String TAG = "BiometricManager";

    /**
     * No error detected.
     */
    public static final int BIOMETRIC_SUCCESS =
            BiometricConstants.BIOMETRIC_SUCCESS;

    /**
     * The hardware is unavailable. Try again later.
     */
    public static final int BIOMETRIC_ERROR_HW_UNAVAILABLE =
            BiometricConstants.BIOMETRIC_ERROR_HW_UNAVAILABLE;

    /**
     * The user does not have any biometrics enrolled.
     */
    public static final int BIOMETRIC_ERROR_NONE_ENROLLED =
            BiometricConstants.BIOMETRIC_ERROR_NO_BIOMETRICS;

    /**
     * There is no biometric hardware.
     */
    public static final int BIOMETRIC_ERROR_NO_HARDWARE =
            BiometricConstants.BIOMETRIC_ERROR_HW_NOT_PRESENT;

    @IntDef({BIOMETRIC_SUCCESS,
            BIOMETRIC_ERROR_HW_UNAVAILABLE,
            BIOMETRIC_ERROR_NONE_ENROLLED,
            BIOMETRIC_ERROR_NO_HARDWARE})
    @interface BiometricError {}

    private final Context mContext;
    private final IBiometricService mService;
    private final boolean mHasHardware;

    /**
     * @param context
     * @return
     * @hide
     */
    public static boolean hasBiometrics(Context context) {
        final PackageManager pm = context.getPackageManager();
        return pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)
                || pm.hasSystemFeature(PackageManager.FEATURE_IRIS)
                || pm.hasSystemFeature(PackageManager.FEATURE_FACE);
    }

    /**
     * @hide
     * @param context
     * @param service
     */
    public BiometricManager(Context context, IBiometricService service) {
        mContext = context;
        mService = service;

        mHasHardware = hasBiometrics(context);
    }

    /**
     * Determine if biometrics can be used. In other words, determine if {@link BiometricPrompt}
     * can be expected to be shown (hardware available, templates enrolled, user-enabled).
     *
     * @return Returns {@link #BIOMETRIC_ERROR_NONE_ENROLLED} if the user does not have any
     *     enrolled, or {@link #BIOMETRIC_ERROR_HW_UNAVAILABLE} if none are currently
     *     supported/enabled. Returns {@link #BIOMETRIC_SUCCESS} if a biometric can currently be
     *     used (enrolled and available).
     */
    @RequiresPermission(USE_BIOMETRIC)
    public @BiometricError int canAuthenticate() {
        if (mService != null) {
            try {
                return mService.canAuthenticate(mContext.getOpPackageName());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        } else {
            if (!mHasHardware) {
                return BIOMETRIC_ERROR_NO_HARDWARE;
            } else {
                Slog.w(TAG, "hasEnrolledBiometrics(): Service not connected");
                return BIOMETRIC_ERROR_HW_UNAVAILABLE;
            }
        }
    }

    /**
     * Listens for changes to biometric eligibility on keyguard from user settings.
     * @param callback
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    public void registerEnabledOnKeyguardCallback(IBiometricEnabledOnKeyguardCallback callback) {
        if (mService != null) {
            try {
                mService.registerEnabledOnKeyguardCallback(callback);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        } else {
            Slog.w(TAG, "registerEnabledOnKeyguardCallback(): Service not connected");
        }
    }

    /**
     * Sets the active user.
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    public void setActiveUser(int userId) {
        if (mService != null) {
            try {
                mService.setActiveUser(userId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        } else {
            Slog.w(TAG, "setActiveUser(): Service not connected");
        }
    }

    /**
     * Reset the timeout when user authenticates with strong auth (e.g. PIN, pattern or password)
     *
     * @param token an opaque token returned by password confirmation.
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    public void resetTimeout(byte[] token) {
        if (mService != null) {
            try {
                mService.resetTimeout(token);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        } else {
            Slog.w(TAG, "resetTimeout(): Service not connected");
        }
    }

    /**
     * TODO(b/123378871): Remove when moved.
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    public void onConfirmDeviceCredentialSuccess() {
        if (mService != null) {
            try {
                mService.onConfirmDeviceCredentialSuccess();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        } else {
            Slog.w(TAG, "onConfirmDeviceCredentialSuccess(): Service not connected");
        }
    }

    /**
     * TODO(b/123378871): Remove when moved.
     * @hide
     */
    @RequiresPermission(USE_BIOMETRIC_INTERNAL)
    public void onConfirmDeviceCredentialError(int error, String message) {
        if (mService != null) {
            try {
                mService.onConfirmDeviceCredentialError(error, message);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        } else {
            Slog.w(TAG, "onConfirmDeviceCredentialError(): Service not connected");
        }
    }
}

