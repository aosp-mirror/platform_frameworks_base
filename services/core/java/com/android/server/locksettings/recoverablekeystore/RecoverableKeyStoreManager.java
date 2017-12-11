/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.locksettings.recoverablekeystore;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.os.Binder;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.os.UserHandle;

import android.security.recoverablekeystore.KeyEntryRecoveryData;
import android.security.recoverablekeystore.KeyStoreRecoveryData;
import android.security.recoverablekeystore.KeyStoreRecoveryMetadata;
import android.security.recoverablekeystore.RecoverableKeyStoreLoader;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;

/**
 * Class with {@link RecoverableKeyStoreLoader} API implementation and internal methods to interact
 * with {@code LockSettingsService}.
 *
 * @hide
 */
public class RecoverableKeyStoreManager {
    private static final String TAG = "RecoverableKeyStoreManager";

    private static RecoverableKeyStoreManager mInstance;
    private Context mContext;

    /**
     * Returns a new or existing instance.
     *
     * @hide
     */
    public static synchronized RecoverableKeyStoreManager getInstance(Context mContext) {
        if (mInstance == null) {
            mInstance = new RecoverableKeyStoreManager(mContext);
        }
        return mInstance;
    }

    @VisibleForTesting
    RecoverableKeyStoreManager(Context context) {
        mContext = context;
    }

    public int initRecoveryService(
            @NonNull String rootCertificateAlias, @NonNull byte[] signedPublicKeyList, int userId)
            throws RemoteException {
        checkRecoverKeyStorePermission();
        // TODO open /system/etc/security/... cert file
        throw new UnsupportedOperationException();
    }

    /**
     * Gets all data necessary to recover application keys on new device.
     *
     * @return recovery data
     * @hide
     */
    public KeyStoreRecoveryData getRecoveryData(@NonNull byte[] account, int userId)
            throws RemoteException {
        checkRecoverKeyStorePermission();
        final int callingUid = Binder.getCallingUid(); // Recovery agent uid.
        final int callingUserId = UserHandle.getCallingUserId();
        final long callingIdentiy = Binder.clearCallingIdentity();
        try {
            // TODO: Return the latest snapshot for the calling recovery agent.
        } finally {
            Binder.restoreCallingIdentity(callingIdentiy);
        }

        // KeyStoreRecoveryData without application keys and empty recovery blob.
        KeyStoreRecoveryData recoveryData =
                new KeyStoreRecoveryData(
                        /*snapshotVersion=*/ 1,
                        new ArrayList<KeyStoreRecoveryMetadata>(),
                        new ArrayList<KeyEntryRecoveryData>(),
                        /*encryptedRecoveryKeyBlob=*/ new byte[] {});
        throw new ServiceSpecificException(
                RecoverableKeyStoreLoader.UNINITIALIZED_RECOVERY_PUBLIC_KEY);
    }

    public void setServerParameters(long serverParameters, int userId) throws RemoteException {
        checkRecoverKeyStorePermission();
        throw new UnsupportedOperationException();
    }

    public void setRecoveryStatus(
            @NonNull String packageName, @Nullable String[] aliases, int status, int userId)
            throws RemoteException {
        checkRecoverKeyStorePermission();
        throw new UnsupportedOperationException();
    }

    /**
     * Sets recovery secrets list used by all recovery agents for given {@code userId}
     *
     * @hide
     */
    public void setRecoverySecretTypes(
            @NonNull @KeyStoreRecoveryMetadata.UserSecretType int[] secretTypes, int userId)
            throws RemoteException {
        checkRecoverKeyStorePermission();
        throw new UnsupportedOperationException();
    }

    /**
     * Gets secret types necessary to create Recovery Data.
     *
     * @return secret types
     * @hide
     */
    public int[] getRecoverySecretTypes(int userId) throws RemoteException {
        checkRecoverKeyStorePermission();
        throw new UnsupportedOperationException();
    }

    /**
     * Gets secret types RecoverableKeyStoreLoaders is waiting for to create new Recovery Data.
     *
     * @return secret types
     * @hide
     */
    public int[] getPendingRecoverySecretTypes(int userId) throws RemoteException {
        checkRecoverKeyStorePermission();
        throw new UnsupportedOperationException();
    }

    public void recoverySecretAvailable(
            @NonNull KeyStoreRecoveryMetadata recoverySecret, int userId) throws RemoteException {
        final int callingUid = Binder.getCallingUid(); // Recovery agent uid.
        if (recoverySecret.getLockScreenUiFormat() == KeyStoreRecoveryMetadata.TYPE_LOCKSCREEN) {
            throw new SecurityException(
                    "Caller " + callingUid + "is not allowed to set lock screen secret");
        }
        checkRecoverKeyStorePermission();
        // TODO: add hook from LockSettingsService to set lock screen secret.
        throw new UnsupportedOperationException();
    }

    /**
     * Initializes recovery session.
     *
     * @return recovery claim
     * @hide
     */
    public byte[] startRecoverySession(
            @NonNull String sessionId,
            @NonNull byte[] verifierPublicKey,
            @NonNull byte[] vaultParams,
            @NonNull byte[] vaultChallenge,
            @NonNull List<KeyStoreRecoveryMetadata> secrets,
            int userId)
            throws RemoteException {
        checkRecoverKeyStorePermission();
        throw new UnsupportedOperationException();
    }

    public void recoverKeys(
            @NonNull String sessionId,
            @NonNull byte[] recoveryKeyBlob,
            @NonNull List<KeyEntryRecoveryData> applicationKeys,
            int userId)
            throws RemoteException {
        checkRecoverKeyStorePermission();
        throw new UnsupportedOperationException();
    }

    /** This function can only be used inside LockSettingsService. */
    public void lockScreenSecretAvailable(
            @KeyStoreRecoveryMetadata.LockScreenUiFormat int type,
            String unencryptedPassword,
            int userId) {
        // TODO: compute SHA256 or Argon2id depending on secret type.
        throw new UnsupportedOperationException();
    }

    /** This function can only be used inside LockSettingsService. */
    public void lockScreenSecretChanged(
            @KeyStoreRecoveryMetadata.LockScreenUiFormat int type,
            @Nullable String unencryptedPassword,
            int userId) {
        throw new UnsupportedOperationException();
    }

    private void checkRecoverKeyStorePermission() {
        mContext.enforceCallingOrSelfPermission(
                RecoverableKeyStoreLoader.PERMISSION_RECOVER_KEYSTORE,
                "Caller " + Binder.getCallingUid() + " doesn't have RecoverKeyStore permission.");
    }
}
