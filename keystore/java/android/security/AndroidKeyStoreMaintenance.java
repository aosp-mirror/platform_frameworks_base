/*
 * Copyright (C) 2021 The Android Open Source Project
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
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;
import android.os.StrictMode;
import android.security.maintenance.IKeystoreMaintenance;
import android.system.keystore2.Domain;
import android.system.keystore2.KeyDescriptor;
import android.system.keystore2.ResponseCode;
import android.util.Log;

/**
 * @hide This is the client side for IKeystoreMaintenance AIDL.
 * It is used mainly by LockSettingsService.
 */
public class AndroidKeyStoreMaintenance {
    private static final String TAG = "AndroidKeyStoreMaintenance";

    public static final int SYSTEM_ERROR = ResponseCode.SYSTEM_ERROR;
    public static final int INVALID_ARGUMENT = ResponseCode.INVALID_ARGUMENT;
    public static final int PERMISSION_DENIED = ResponseCode.PERMISSION_DENIED;
    public static final int KEY_NOT_FOUND = ResponseCode.KEY_NOT_FOUND;

    private static IKeystoreMaintenance getService() {
        return IKeystoreMaintenance.Stub.asInterface(
                ServiceManager.checkService("android.security.maintenance"));
    }

    /**
     * Informs Keystore 2.0 about adding a user
     *
     * @param userId - Android user id of the user being added
     * @return 0 if successful or a {@code ResponseCode}
     * @hide
     */
    public static int onUserAdded(int userId) {
        StrictMode.noteDiskWrite();
        try {
            getService().onUserAdded(userId);
            return 0;
        } catch (ServiceSpecificException e) {
            Log.e(TAG, "onUserAdded failed", e);
            return e.errorCode;
        } catch (Exception e) {
            Log.e(TAG, "Can not connect to keystore", e);
            return SYSTEM_ERROR;
        }
    }

    /**
     * Tells Keystore to create a user's super keys and store them encrypted by the given secret.
     *
     * @param userId - Android user id of the user
     * @param password - a secret derived from the user's synthetic password
     * @param allowExisting - true if the keys already existing should not be considered an error
     * @return 0 if successful or a {@code ResponseCode}
     * @hide
     */
    public static int initUserSuperKeys(int userId, @NonNull byte[] password,
            boolean allowExisting) {
        StrictMode.noteDiskWrite();
        try {
            getService().initUserSuperKeys(userId, password, allowExisting);
            return 0;
        } catch (ServiceSpecificException e) {
            Log.e(TAG, "initUserSuperKeys failed", e);
            return e.errorCode;
        } catch (Exception e) {
            Log.e(TAG, "Can not connect to keystore", e);
            return SYSTEM_ERROR;
        }
    }

    /**
     * Informs Keystore 2.0 about removing a user
     *
     * @param userId - Android user id of the user being removed
     * @return 0 if successful or a {@code ResponseCode}
     * @hide
     */
    public static int onUserRemoved(int userId) {
        StrictMode.noteDiskWrite();
        try {
            getService().onUserRemoved(userId);
            return 0;
        } catch (ServiceSpecificException e) {
            Log.e(TAG, "onUserRemoved failed", e);
            return e.errorCode;
        } catch (Exception e) {
            Log.e(TAG, "Can not connect to keystore", e);
            return SYSTEM_ERROR;
        }
    }

    /**
     * Informs Keystore 2.0 about changing user's password
     *
     * @param userId   - Android user id of the user
     * @param password - a secret derived from the synthetic password provided by the
     *                 LockSettingsService
     * @return 0 if successful or a {@code ResponseCode}
     * @hide
     */
    public static int onUserPasswordChanged(int userId, @Nullable byte[] password) {
        StrictMode.noteDiskWrite();
        try {
            getService().onUserPasswordChanged(userId, password);
            return 0;
        } catch (ServiceSpecificException e) {
            Log.e(TAG, "onUserPasswordChanged failed", e);
            return e.errorCode;
        } catch (Exception e) {
            Log.e(TAG, "Can not connect to keystore", e);
            return SYSTEM_ERROR;
        }
    }

    /**
     * Tells Keystore that a user's LSKF is being removed, ie the user's lock screen is changing to
     * Swipe or None.  Keystore uses this notification to delete the user's auth-bound keys.
     *
     * @param userId - Android user id of the user
     * @return 0 if successful or a {@code ResponseCode}
     * @hide
     */
    public static int onUserLskfRemoved(int userId) {
        StrictMode.noteDiskWrite();
        try {
            getService().onUserLskfRemoved(userId);
            return 0;
        } catch (ServiceSpecificException e) {
            Log.e(TAG, "onUserLskfRemoved failed", e);
            return e.errorCode;
        } catch (Exception e) {
            Log.e(TAG, "Can not connect to keystore", e);
            return SYSTEM_ERROR;
        }
    }

    /**
     * Informs Keystore 2.0 that an app was uninstalled and the corresponding namespace is to
     * be cleared.
     */
    public static int clearNamespace(@Domain int domain, long namespace) {
        StrictMode.noteDiskWrite();
        try {
            getService().clearNamespace(domain, namespace);
            return 0;
        } catch (ServiceSpecificException e) {
            Log.e(TAG, "clearNamespace failed", e);
            return e.errorCode;
        } catch (Exception e) {
            Log.e(TAG, "Can not connect to keystore", e);
            return SYSTEM_ERROR;
        }
    }

    /**
     * Informs Keystore 2.0 that an off body event was detected.
     */
    public static void onDeviceOffBody() {
        StrictMode.noteDiskWrite();
        try {
            getService().onDeviceOffBody();
        } catch (Exception e) {
            // TODO This fails open. This is not a regression with respect to keystore1 but it
            //      should get fixed.
            Log.e(TAG, "Error while reporting device off body event.", e);
        }
    }

    /**
     * Migrates a key given by the source descriptor to the location designated by the destination
     * descriptor.
     *
     * @param source - The key to migrate may be specified by Domain.APP, Domain.SELINUX, or
     *               Domain.KEY_ID. The caller needs the permissions use, delete, and grant for the
     *               source namespace.
     * @param destination - The new designation for the key may be specified by Domain.APP or
     *                    Domain.SELINUX. The caller need the permission rebind for the destination
     *                    namespace.
     *
     * @return * 0 on success
     *         * KEY_NOT_FOUND if the source did not exist.
     *         * PERMISSION_DENIED if any of the required permissions was missing.
     *         * INVALID_ARGUMENT if the destination was occupied or any domain value other than
     *                   the allowed ones was specified.
     *         * SYSTEM_ERROR if an unexpected error occurred.
     */
    public static int migrateKeyNamespace(KeyDescriptor source, KeyDescriptor destination) {
        StrictMode.noteDiskWrite();
        try {
            getService().migrateKeyNamespace(source, destination);
            return 0;
        } catch (ServiceSpecificException e) {
            Log.e(TAG, "migrateKeyNamespace failed", e);
            return e.errorCode;
        } catch (Exception e) {
            Log.e(TAG, "Can not connect to keystore", e);
            return SYSTEM_ERROR;
        }
    }

    /**
     * Returns the list of Application UIDs that have auth-bound keys that are bound to
     * the given SID. This enables warning the user when they are about to invalidate
     * a SID (for example, removing the LSKF).
     *
     * @param userId - The ID of the user the SID is associated with.
     * @param userSecureId - The SID in question.
     *
     * @return A list of app UIDs.
     */
    public static long[] getAllAppUidsAffectedBySid(int userId, long userSecureId)
            throws KeyStoreException {
        StrictMode.noteDiskWrite();
        try {
            return getService().getAppUidsAffectedBySid(userId, userSecureId);
        } catch (RemoteException | NullPointerException e) {
            throw new KeyStoreException(SYSTEM_ERROR,
                    "Failure to connect to Keystore while trying to get apps affected by SID.");
        } catch (ServiceSpecificException e) {
            throw new KeyStoreException(e.errorCode,
                    "Keystore error while trying to get apps affected by SID.");
        }
    }

    /**
    * Deletes all keys in all KeyMint devices.
    * Called by RecoverySystem before rebooting to recovery in order to delete all KeyMint keys,
    * including synthetic password protector keys (used by LockSettingsService), as well as keys
    * protecting DE and metadata encryption keys (used by vold). This ensures that FBE-encrypted
    * data is unrecoverable even if the data wipe in recovery is interrupted or skipped.
    */
    public static void deleteAllKeys() throws KeyStoreException {
        StrictMode.noteDiskWrite();
        try {
            getService().deleteAllKeys();
        } catch (RemoteException | NullPointerException e) {
            throw new KeyStoreException(SYSTEM_ERROR,
                    "Failure to connect to Keystore while trying to delete all keys.");
        } catch (ServiceSpecificException e) {
            throw new KeyStoreException(e.errorCode,
                    "Keystore error while trying to delete all keys.");
        }
    }
}
