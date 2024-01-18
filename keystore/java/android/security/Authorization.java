/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.security;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.security.keymint.HardwareAuthToken;
import android.hardware.security.keymint.HardwareAuthenticatorType;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;
import android.os.StrictMode;
import android.security.authorization.IKeystoreAuthorization;
import android.system.keystore2.ResponseCode;
import android.util.Log;

/**
 * @hide This is the client side for IKeystoreAuthorization AIDL.
 * It shall only be used by biometric authentication providers and Gatekeeper.
 */
public class Authorization {
    private static final String TAG = "KeystoreAuthorization";

    public static final int SYSTEM_ERROR = ResponseCode.SYSTEM_ERROR;

    /**
     * @return an instance of IKeystoreAuthorization
     */
    public static IKeystoreAuthorization getService() {
        return IKeystoreAuthorization.Stub.asInterface(
                    ServiceManager.checkService("android.security.authorization"));
    }

    /**
     * Adds an auth token to keystore2.
     *
     * @param authToken created by Android authenticators.
     * @return 0 if successful or {@code ResponseCode.SYSTEM_ERROR}.
     */
    public static int addAuthToken(@NonNull HardwareAuthToken authToken) {
        StrictMode.noteSlowCall("addAuthToken");
        try {
            getService().addAuthToken(authToken);
            return 0;
        } catch (RemoteException | NullPointerException e) {
            Log.w(TAG, "Can not connect to keystore", e);
            return SYSTEM_ERROR;
        } catch (ServiceSpecificException e) {
            return e.errorCode;
        }
    }

    /**
     * Add an auth token to Keystore 2.0 in the legacy serialized auth token format.
     * @param authToken
     * @return 0 if successful or a {@code ResponseCode}.
     */
    public static int addAuthToken(@NonNull byte[] authToken) {
        return addAuthToken(AuthTokenUtils.toHardwareAuthToken(authToken));
    }

    /**
     * Tells Keystore that the device is now unlocked for a user.
     *
     * @param userId - the user's Android user ID
     * @param password - a secret derived from the user's synthetic password, if the unlock method
     *                   is LSKF (or equivalent) and thus has made the synthetic password available
     * @return 0 if successful or a {@code ResponseCode}.
     */
    public static int onDeviceUnlocked(int userId, @Nullable byte[] password) {
        StrictMode.noteDiskWrite();
        try {
            getService().onDeviceUnlocked(userId, password);
            return 0;
        } catch (RemoteException | NullPointerException e) {
            Log.w(TAG, "Can not connect to keystore", e);
            return SYSTEM_ERROR;
        } catch (ServiceSpecificException e) {
            return e.errorCode;
        }
    }

    /**
     * Tells Keystore that the device is now locked for a user.
     *
     * @param userId - the user's Android user ID
     * @param unlockingSids - list of biometric SIDs with which the device may be unlocked again
     * @param weakUnlockEnabled - true if non-strong biometric or trust agent unlock is enabled
     * @return 0 if successful or a {@code ResponseCode}.
     */
    public static int onDeviceLocked(int userId, @NonNull long[] unlockingSids,
            boolean weakUnlockEnabled) {
        StrictMode.noteDiskWrite();
        try {
            getService().onDeviceLocked(userId, unlockingSids, weakUnlockEnabled);
            return 0;
        } catch (RemoteException | NullPointerException e) {
            Log.w(TAG, "Can not connect to keystore", e);
            return SYSTEM_ERROR;
        } catch (ServiceSpecificException e) {
            return e.errorCode;
        }
    }

    /**
     * Gets the last authentication time of the given user and authenticators.
     *
     * @param userId user id
     * @param authenticatorTypes an array of {@link HardwareAuthenticatorType}.
     * @return the last authentication time or
     * {@link BiometricConstants#BIOMETRIC_NO_AUTHENTICATION}.
     */
    public static long getLastAuthenticationTime(
            long userId, @HardwareAuthenticatorType int[] authenticatorTypes) {
        try {
            return getService().getLastAuthTime(userId, authenticatorTypes);
        } catch (RemoteException | NullPointerException e) {
            Log.w(TAG, "Can not connect to keystore", e);
            return BiometricConstants.BIOMETRIC_NO_AUTHENTICATION;
        } catch (ServiceSpecificException e) {
            return BiometricConstants.BIOMETRIC_NO_AUTHENTICATION;
        }
    }

}
