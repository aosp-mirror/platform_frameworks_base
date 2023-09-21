/*
 * Copyright (C) 2009 The Android Open Source Project
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

import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.StrictMode;
import android.os.UserHandle;
import android.security.maintenance.UserState;

/**
 * @hide This should not be made public in its present form because it
 * assumes that private and secret key bytes are available and would
 * preclude the use of hardware crypto.
 */
public class KeyStore {
    private static final String TAG = "KeyStore";

    // ResponseCodes - see system/security/keystore/include/keystore/keystore.h
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final int NO_ERROR = 1;

    // Used for UID field to indicate the calling UID.
    public static final int UID_SELF = -1;

    // States
    public enum State {
        @UnsupportedAppUsage
        UNLOCKED,
        @UnsupportedAppUsage
        LOCKED,
        UNINITIALIZED
    };

    private static final KeyStore KEY_STORE = new KeyStore();

    @UnsupportedAppUsage
    public static KeyStore getInstance() {
        return KEY_STORE;
    }

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public State state(int userId) {
        int userState = AndroidKeyStoreMaintenance.getState(userId);
        switch (userState) {
            case UserState.UNINITIALIZED:
                return KeyStore.State.UNINITIALIZED;
            case UserState.LSKF_UNLOCKED:
                return KeyStore.State.UNLOCKED;
            case UserState.LSKF_LOCKED:
                return KeyStore.State.LOCKED;
            default:
                throw new AssertionError(userState);
        }
    }

    /** @hide */
    @UnsupportedAppUsage
    public State state() {
        return state(UserHandle.myUserId());
    }

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public byte[] get(String key) {
        return null;
    }

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public boolean delete(String key) {
        return false;
    }

    /**
     * List uids of all keys that are auth bound to the current user.
     * Only system is allowed to call this method.
     * @hide
     * @deprecated This function always returns null.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public int[] listUidsOfAuthBoundKeys() {
        return null;
    }


    /**
     * @hide
     * @deprecated This function has no effect.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public boolean unlock(String password) {
        return false;
    }

    /**
     *
     * @return
     * @deprecated This function always returns true.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public boolean isEmpty() {
        return true;
    }

    /**
     * Add an authentication record to the keystore authorization table.
     *
     * @param authToken The packed bytes of a hw_auth_token_t to be provided to keymaster.
     * @return {@code KeyStore.NO_ERROR} on success, otherwise an error value corresponding to
     * a {@code KeymasterDefs.KM_ERROR_} value or {@code KeyStore} ResponseCode.
     */
    public int addAuthToken(byte[] authToken) {
        StrictMode.noteDiskWrite();

        return Authorization.addAuthToken(authToken);
    }

    /**
     * Notify keystore that the device went off-body.
     */
    public void onDeviceOffBody() {
        AndroidKeyStoreMaintenance.onDeviceOffBody();
    }

    /**
     * Returns a {@link KeyStoreException} corresponding to the provided keystore/keymaster error
     * code.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static KeyStoreException getKeyStoreException(int errorCode) {
        return new KeyStoreException(-10000, "Should not be called.");
    }
}
