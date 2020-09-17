/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.backup.encryption.transport;

import static com.android.server.backup.encryption.BackupEncryptionService.TAG;

import android.app.backup.BackupTransport;
import android.app.backup.RestoreDescription;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.backup.IBackupTransport;
import com.android.server.backup.encryption.KeyValueEncrypter;
import com.android.server.backup.transport.DelegatingTransport;
import com.android.server.backup.transport.TransportClient;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicReference;

/**
 * This is an implementation of {@link IBackupTransport} that encrypts (or decrypts) the data when
 * sending it (or receiving it) from the {@link IBackupTransport} returned by {@link
 * TransportClient.connect(String)}.
 */
public class IntermediateEncryptingTransport extends DelegatingTransport {
    private static final String BACKUP_TEMP_DIR = "backup";
    private static final String RESTORE_TEMP_DIR = "restore";

    private final TransportClient mTransportClient;
    private final Object mConnectLock = new Object();
    private final Context mContext;
    private volatile IBackupTransport mRealTransport;
    private AtomicReference<String> mNextRestorePackage = new AtomicReference<>();
    private final KeyValueEncrypter mKeyValueEncrypter;
    private final boolean mShouldEncrypt;

    IntermediateEncryptingTransport(
            TransportClient transportClient, Context context, boolean shouldEncrypt) {
        this(transportClient, context, new KeyValueEncrypter(context), shouldEncrypt);
    }

    @VisibleForTesting
    IntermediateEncryptingTransport(
            TransportClient transportClient, Context context, KeyValueEncrypter keyValueEncrypter,
            boolean shouldEncrypt) {
        mTransportClient = transportClient;
        mContext = context;
        mKeyValueEncrypter = keyValueEncrypter;
        mShouldEncrypt = shouldEncrypt;
    }

    @Override
    protected IBackupTransport getDelegate() throws RemoteException {
        if (mRealTransport == null) {
            connect();
        }
        Log.d(TAG, "real transport = " + mRealTransport.name());
        return mRealTransport;
    }

    @Override
    public int performBackup(PackageInfo packageInfo, ParcelFileDescriptor inFd, int flags)
            throws RemoteException {
        if (!mShouldEncrypt) {
            return super.performBackup(packageInfo, inFd, flags);
        }

        File encryptedStorageFile = getBackupTempStorage(packageInfo.packageName);
        if (encryptedStorageFile == null) {
            return BackupTransport.TRANSPORT_ERROR;
        }

        // Encrypt the backup data and write it into a temp file.
        try (OutputStream encryptedOutput = new FileOutputStream(encryptedStorageFile)) {
            mKeyValueEncrypter.encryptKeyValueData(packageInfo.packageName, inFd,
                    encryptedOutput);
        } catch (Throwable e) {
            Log.e(TAG, "Failed to encrypt backup data: ", e);
            return BackupTransport.TRANSPORT_ERROR;
        }

        // Pass the temp file to the real transport for backup.
        try (FileInputStream encryptedInput = new FileInputStream(encryptedStorageFile)) {
            return super.performBackup(
                    packageInfo, ParcelFileDescriptor.dup(encryptedInput.getFD()), flags);
        } catch (IOException e) {
            Log.e(TAG, "Failed to read encrypted data from temp storage: ", e);
            return BackupTransport.TRANSPORT_ERROR;
        }
    }

    @Override
    public int getRestoreData(ParcelFileDescriptor outFd) throws RemoteException {
        if (!mShouldEncrypt) {
            return super.getRestoreData(outFd);
        }

        String nextRestorePackage = mNextRestorePackage.get();
        if (nextRestorePackage == null) {
            Log.e(TAG, "No next restore package set");
            return BackupTransport.TRANSPORT_ERROR;
        }

        File encryptedStorageFile = getRestoreTempStorage(nextRestorePackage);
        if (encryptedStorageFile == null) {
            return BackupTransport.TRANSPORT_ERROR;
        }

        // Get encrypted restore data from the real transport and write it into a temp file.
        try (FileOutputStream outputStream = new FileOutputStream(encryptedStorageFile)) {
            int status = super.getRestoreData(ParcelFileDescriptor.dup(outputStream.getFD()));
            if (status != BackupTransport.TRANSPORT_OK) {
                Log.e(TAG, "Failed to read restore data from transport, status = " + status);
                return status;
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to write encrypted data to temp storage: ", e);
            return BackupTransport.TRANSPORT_ERROR;
        }

        // Decrypt the data and write it into the fd given by the real transport.
        try (InputStream inputStream = new FileInputStream(encryptedStorageFile)) {
            mKeyValueEncrypter.decryptKeyValueData(nextRestorePackage, inputStream, outFd);
            encryptedStorageFile.delete();
        } catch (Exception e) {
            Log.e(TAG, "Failed to decrypt restored data: ", e);
            return BackupTransport.TRANSPORT_ERROR;
        }

        return BackupTransport.TRANSPORT_OK;
    }

    @Override
    public RestoreDescription nextRestorePackage() throws RemoteException {
        if (!mShouldEncrypt) {
            return super.nextRestorePackage();
        }

        RestoreDescription restoreDescription = super.nextRestorePackage();
        mNextRestorePackage.set(restoreDescription.getPackageName());

        return restoreDescription;
    }

    @VisibleForTesting
    protected File getBackupTempStorage(String packageName) {
        return getTempStorage(packageName, BACKUP_TEMP_DIR);
    }

    @VisibleForTesting
    protected File getRestoreTempStorage(String packageName) {
        return getTempStorage(packageName, RESTORE_TEMP_DIR);
    }

    private File getTempStorage(String packageName, String operationType) {
        File encryptedDir = new File(mContext.getFilesDir(), operationType);
        encryptedDir.mkdir();
        File encryptedFile = new File(encryptedDir, packageName);
        try {
            encryptedFile.createNewFile();
        } catch (IOException e) {
            Log.e(TAG, "Failed to create temp file for encrypted data: ", e);
        }
        return encryptedFile;
    }

    private void connect() throws RemoteException {
        Log.i(TAG, "connecting " + mTransportClient);
        synchronized (mConnectLock) {
            if (mRealTransport == null) {
                mRealTransport = mTransportClient.connect("IntermediateEncryptingTransport");
                if (mRealTransport == null) {
                    throw new RemoteException("Could not connect: " + mTransportClient);
                }
            }
        }
    }

    @VisibleForTesting
    TransportClient getClient() {
        return mTransportClient;
    }

    @VisibleForTesting
    void setNextRestorePackage(String nextRestorePackage) {
        mNextRestorePackage.set(nextRestorePackage);
    }
}
