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

package com.android.server.locksettings;

import android.annotation.Nullable;
import android.content.Context;
import android.os.RemoteException;
import android.provider.DeviceConfig;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.locksettings.ResumeOnRebootServiceProvider.ResumeOnRebootServiceConnection;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

import javax.crypto.SecretKey;

/**
 * An implementation of the {@link RebootEscrowProviderInterface} by communicating with server to
 * encrypt & decrypt the blob.
 */
class RebootEscrowProviderServerBasedImpl implements RebootEscrowProviderInterface {
    private static final String TAG = "RebootEscrowProviderServerBased";

    // Timeout for service binding
    private static final long DEFAULT_SERVICE_TIMEOUT_IN_SECONDS = 10;

    /**
     * Use the default lifetime of 10 minutes. The lifetime covers the following activities:
     * Server wrap secret -> device reboot -> server unwrap blob.
     */
    private static final long DEFAULT_SERVER_BLOB_LIFETIME_IN_MILLIS = 600_000;

    private final LockSettingsStorage mStorage;

    private final Injector mInjector;

    private byte[] mServerBlob;

    static class Injector {
        private ResumeOnRebootServiceConnection mServiceConnection = null;

        Injector(Context context) {
            mServiceConnection = new ResumeOnRebootServiceProvider(context).getServiceConnection();
            if (mServiceConnection == null) {
                Slog.e(TAG, "Failed to resolve resume on reboot server service.");
            }
        }

        Injector(ResumeOnRebootServiceConnection serviceConnection) {
            mServiceConnection = serviceConnection;
        }

        @Nullable
        private ResumeOnRebootServiceConnection getServiceConnection() {
            return mServiceConnection;
        }

        long getServiceTimeoutInSeconds() {
            return DeviceConfig.getLong(DeviceConfig.NAMESPACE_OTA,
                    "server_based_service_timeout_in_seconds",
                    DEFAULT_SERVICE_TIMEOUT_IN_SECONDS);
        }

        long getServerBlobLifetimeInMillis() {
            return DeviceConfig.getLong(DeviceConfig.NAMESPACE_OTA,
                    "server_based_server_blob_lifetime_in_millis",
                    DEFAULT_SERVER_BLOB_LIFETIME_IN_MILLIS);
        }
    }

    RebootEscrowProviderServerBasedImpl(Context context, LockSettingsStorage storage) {
        this(storage, new Injector(context));
    }

    @VisibleForTesting
    RebootEscrowProviderServerBasedImpl(LockSettingsStorage storage, Injector injector) {
        mStorage = storage;
        mInjector = injector;
    }

    @Override
    public int getType() {
        return TYPE_SERVER_BASED;
    }

    @Override
    public boolean hasRebootEscrowSupport() {
        return mInjector.getServiceConnection() != null;
    }

    private byte[] unwrapServerBlob(byte[] serverBlob, SecretKey decryptionKey) throws
            TimeoutException, RemoteException, IOException {
        ResumeOnRebootServiceConnection serviceConnection = mInjector.getServiceConnection();
        if (serviceConnection == null) {
            Slog.w(TAG, "Had reboot escrow data for users, but resume on reboot server"
                    + " service is unavailable");
            return null;
        }

        // Decrypt with k_k from the key store first.
        byte[] decryptedBlob = AesEncryptionUtil.decrypt(decryptionKey, serverBlob);
        if (decryptedBlob == null) {
            Slog.w(TAG, "Decrypted server blob should not be null");
            return null;
        }

        // Ask the server connection service to decrypt the inner layer, to get the reboot
        // escrow key (k_s).
        serviceConnection.bindToService(mInjector.getServiceTimeoutInSeconds());
        byte[] escrowKeyBytes = serviceConnection.unwrap(decryptedBlob,
                mInjector.getServiceTimeoutInSeconds());
        serviceConnection.unbindService();

        return escrowKeyBytes;
    }

    @Override
    public RebootEscrowKey getAndClearRebootEscrowKey(SecretKey decryptionKey) throws IOException {
        if (mServerBlob == null) {
            mServerBlob = mStorage.readRebootEscrowServerBlob();
        }
        // Delete the server blob in storage.
        mStorage.removeRebootEscrowServerBlob();
        if (mServerBlob == null) {
            Slog.w(TAG, "Failed to read reboot escrow server blob from storage");
            return null;
        }
        if (decryptionKey == null) {
            Slog.w(TAG, "Failed to decrypt the escrow key; decryption key from keystore is"
                    + " null.");
            return null;
        }

        Slog.i(TAG, "Loaded reboot escrow server blob from storage");
        try {
            byte[] escrowKeyBytes = unwrapServerBlob(mServerBlob, decryptionKey);
            if (escrowKeyBytes == null) {
                Slog.w(TAG, "Decrypted reboot escrow key bytes should not be null");
                return null;
            } else if (escrowKeyBytes.length != 32) {
                Slog.e(TAG, "Decrypted reboot escrow key has incorrect size "
                        + escrowKeyBytes.length);
                return null;
            }

            return RebootEscrowKey.fromKeyBytes(escrowKeyBytes);
        } catch (TimeoutException | RemoteException e) {
            Slog.w(TAG, "Failed to decrypt the server blob ", e);
            return null;
        }
    }

    @Override
    public void clearRebootEscrowKey() {
        mStorage.removeRebootEscrowServerBlob();
    }

    private byte[] wrapEscrowKey(byte[] escrowKeyBytes, SecretKey encryptionKey) throws
            TimeoutException, RemoteException, IOException {
        ResumeOnRebootServiceConnection serviceConnection = mInjector.getServiceConnection();
        if (serviceConnection == null) {
            Slog.w(TAG, "Failed to encrypt the reboot escrow key: resume on reboot server"
                    + " service is unavailable");
            return null;
        }

        serviceConnection.bindToService(mInjector.getServiceTimeoutInSeconds());
        // Ask the server connection service to encrypt the reboot escrow key.
        byte[] serverEncryptedBlob = serviceConnection.wrapBlob(escrowKeyBytes,
                mInjector.getServerBlobLifetimeInMillis(), mInjector.getServiceTimeoutInSeconds());
        serviceConnection.unbindService();

        if (serverEncryptedBlob == null) {
            Slog.w(TAG, "Server encrypted reboot escrow key cannot be null");
            return null;
        }

        // Additionally wrap the server blob with a local key.
        return AesEncryptionUtil.encrypt(encryptionKey, serverEncryptedBlob);
    }

    @Override
    public boolean storeRebootEscrowKey(RebootEscrowKey escrowKey, SecretKey encryptionKey) {
        mStorage.removeRebootEscrowServerBlob();
        try {
            byte[] wrappedBlob = wrapEscrowKey(escrowKey.getKeyBytes(), encryptionKey);
            if (wrappedBlob == null) {
                Slog.w(TAG, "Failed to encrypt the reboot escrow key");
                return false;
            }
            mStorage.writeRebootEscrowServerBlob(wrappedBlob);

            Slog.i(TAG, "Reboot escrow key encrypted and stored.");
            return true;
        } catch (TimeoutException | RemoteException | IOException e) {
            Slog.w(TAG, "Failed to encrypt the reboot escrow key ", e);
        }

        return false;
    }
}
