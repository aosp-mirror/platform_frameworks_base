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
import android.hardware.security.keymint.HardwareAuthToken;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;
import android.os.StrictMode;
import android.security.authorization.IKeystoreAuthorization;
import android.security.authorization.LockScreenEvent;
import android.system.keystore2.ResponseCode;
import android.util.Log;

/**
 * @hide This is the client side for IKeystoreAuthorization AIDL.
 * It shall only be used by biometric authentication providers and Gatekeeper.
 */
public class Authorization {
    private static final String TAG = "KeystoreAuthorization";

    public static final int SYSTEM_ERROR = ResponseCode.SYSTEM_ERROR;

    private static IKeystoreAuthorization getService() {
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
     * Informs keystore2 about lock screen event.
     *
     * @param locked            - whether it is a lock (true) or unlock (false) event
     * @param syntheticPassword - if it is an unlock event with the password, pass the synthetic
     *                            password provided by the LockSettingService
     * @param unlockingSids     - KeyMint secure user IDs that should be permitted to unlock
     *                            UNLOCKED_DEVICE_REQUIRED keys.
     *
     * @return 0 if successful or a {@code ResponseCode}.
     */
    public static int onLockScreenEvent(@NonNull boolean locked, @NonNull int userId,
            @Nullable byte[] syntheticPassword, @Nullable long[] unlockingSids) {
        StrictMode.noteDiskWrite();
        try {
            if (locked) {
                getService().onLockScreenEvent(LockScreenEvent.LOCK, userId, null, unlockingSids);
            } else {
                getService().onLockScreenEvent(
                        LockScreenEvent.UNLOCK, userId, syntheticPassword, unlockingSids);
            }
            return 0;
        } catch (RemoteException | NullPointerException e) {
            Log.w(TAG, "Can not connect to keystore", e);
            return SYSTEM_ERROR;
        } catch (ServiceSpecificException e) {
            return e.errorCode;
        }
    }

}
