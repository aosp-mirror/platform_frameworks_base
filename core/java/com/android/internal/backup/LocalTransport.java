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

package com.android.internal.backup;

import android.app.backup.BackupDataInput;
import android.app.backup.BackupDataOutput;
import android.app.backup.RestoreSet;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import com.android.org.bouncycastle.util.encoders.Base64;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;

/**
 * Backup transport for stashing stuff into a known location on disk, and
 * later restoring from there.  For testing only.
 */

public class LocalTransport extends IBackupTransport.Stub {
    private static final String TAG = "LocalTransport";
    private static final boolean DEBUG = true;

    private static final String TRANSPORT_DIR_NAME
            = "com.android.internal.backup.LocalTransport";

    private static final String TRANSPORT_DESTINATION_STRING
            = "Backing up to debug-only private cache";

    // The single hardcoded restore set always has the same (nonzero!) token
    private static final long RESTORE_TOKEN = 1;

    private Context mContext;
    private File mDataDir = new File(Environment.getDownloadCacheDirectory(), "backup");
    private PackageInfo[] mRestorePackages = null;
    private int mRestorePackage = -1;  // Index into mRestorePackages


    public LocalTransport(Context context) {
        mContext = context;
    }

    public Intent configurationIntent() {
        // The local transport is not user-configurable
        return null;
    }

    public String currentDestinationString() {
        return TRANSPORT_DESTINATION_STRING;
    }

    public String transportDirName() {
        return TRANSPORT_DIR_NAME;
    }

    public long requestBackupTime() {
        // any time is a good time for local backup
        return 0;
    }

    public int initializeDevice() {
        if (DEBUG) Log.v(TAG, "wiping all data");
        deleteContents(mDataDir);
        return BackupConstants.TRANSPORT_OK;
    }

    public int performBackup(PackageInfo packageInfo, ParcelFileDescriptor data) {
        if (DEBUG) Log.v(TAG, "performBackup() pkg=" + packageInfo.packageName);

        File packageDir = new File(mDataDir, packageInfo.packageName);
        packageDir.mkdirs();

        // Each 'record' in the restore set is kept in its own file, named by
        // the record key.  Wind through the data file, extracting individual
        // record operations and building a set of all the updates to apply
        // in this update.
        BackupDataInput changeSet = new BackupDataInput(data.getFileDescriptor());
        try {
            int bufSize = 512;
            byte[] buf = new byte[bufSize];
            while (changeSet.readNextHeader()) {
                String key = changeSet.getKey();
                String base64Key = new String(Base64.encode(key.getBytes()));
                File entityFile = new File(packageDir, base64Key);

                int dataSize = changeSet.getDataSize();

                if (DEBUG) Log.v(TAG, "Got change set key=" + key + " size=" + dataSize
                        + " key64=" + base64Key);

                if (dataSize >= 0) {
                    if (entityFile.exists()) {
                        entityFile.delete();
                    }
                    FileOutputStream entity = new FileOutputStream(entityFile);

                    if (dataSize > bufSize) {
                        bufSize = dataSize;
                        buf = new byte[bufSize];
                    }
                    changeSet.readEntityData(buf, 0, dataSize);
                    if (DEBUG) Log.v(TAG, "  data size " + dataSize);

                    try {
                        entity.write(buf, 0, dataSize);
                    } catch (IOException e) {
                        Log.e(TAG, "Unable to update key file " + entityFile.getAbsolutePath());
                        return BackupConstants.TRANSPORT_ERROR;
                    } finally {
                        entity.close();
                    }
                } else {
                    entityFile.delete();
                }
            }
            return BackupConstants.TRANSPORT_OK;
        } catch (IOException e) {
            // oops, something went wrong.  abort the operation and return error.
            Log.v(TAG, "Exception reading backup input:", e);
            return BackupConstants.TRANSPORT_ERROR;
        }
    }

    // Deletes the contents but not the given directory
    private void deleteContents(File dirname) {
        File[] contents = dirname.listFiles();
        if (contents != null) {
            for (File f : contents) {
                if (f.isDirectory()) {
                    // delete the directory's contents then fall through
                    // and delete the directory itself.
                    deleteContents(f);
                }
                f.delete();
            }
        }
    }

    public int clearBackupData(PackageInfo packageInfo) {
        if (DEBUG) Log.v(TAG, "clearBackupData() pkg=" + packageInfo.packageName);

        File packageDir = new File(mDataDir, packageInfo.packageName);
        final File[] fileset = packageDir.listFiles();
        if (fileset != null) {
            for (File f : fileset) {
                f.delete();
            }
            packageDir.delete();
        }
        return BackupConstants.TRANSPORT_OK;
    }

    public int finishBackup() {
        if (DEBUG) Log.v(TAG, "finishBackup()");
        return BackupConstants.TRANSPORT_OK;
    }

    // Restore handling
    public RestoreSet[] getAvailableRestoreSets() throws android.os.RemoteException {
        // one hardcoded restore set
        RestoreSet set = new RestoreSet("Local disk image", "flash", RESTORE_TOKEN);
        RestoreSet[] array = { set };
        return array;
    }

    public long getCurrentRestoreSet() {
        // The hardcoded restore set always has the same token
        return RESTORE_TOKEN;
    }

    public int startRestore(long token, PackageInfo[] packages) {
        if (DEBUG) Log.v(TAG, "start restore " + token);
        mRestorePackages = packages;
        mRestorePackage = -1;
        return BackupConstants.TRANSPORT_OK;
    }

    public String nextRestorePackage() {
        if (mRestorePackages == null) throw new IllegalStateException("startRestore not called");
        while (++mRestorePackage < mRestorePackages.length) {
            String name = mRestorePackages[mRestorePackage].packageName;
            if (new File(mDataDir, name).isDirectory()) {
                if (DEBUG) Log.v(TAG, "  nextRestorePackage() = " + name);
                return name;
            }
        }

        if (DEBUG) Log.v(TAG, "  no more packages to restore");
        return "";
    }

    public int getRestoreData(ParcelFileDescriptor outFd) {
        if (mRestorePackages == null) throw new IllegalStateException("startRestore not called");
        if (mRestorePackage < 0) throw new IllegalStateException("nextRestorePackage not called");
        File packageDir = new File(mDataDir, mRestorePackages[mRestorePackage].packageName);

        // The restore set is the concatenation of the individual record blobs,
        // each of which is a file in the package's directory
        File[] blobs = packageDir.listFiles();
        if (blobs == null) {  // nextRestorePackage() ensures the dir exists, so this is an error
            Log.e(TAG, "Error listing directory: " + packageDir);
            return BackupConstants.TRANSPORT_ERROR;
        }

        // We expect at least some data if the directory exists in the first place
        if (DEBUG) Log.v(TAG, "  getRestoreData() found " + blobs.length + " key files");
        BackupDataOutput out = new BackupDataOutput(outFd.getFileDescriptor());
        try {
            for (File f : blobs) {
                FileInputStream in = new FileInputStream(f);
                try {
                    int size = (int) f.length();
                    byte[] buf = new byte[size];
                    in.read(buf);
                    String key = new String(Base64.decode(f.getName()));
                    if (DEBUG) Log.v(TAG, "    ... key=" + key + " size=" + size);
                    out.writeEntityHeader(key, size);
                    out.writeEntityData(buf, size);
                } finally {
                    in.close();
                }
            }
            return BackupConstants.TRANSPORT_OK;
        } catch (IOException e) {
            Log.e(TAG, "Unable to read backup records", e);
            return BackupConstants.TRANSPORT_ERROR;
        }
    }

    public void finishRestore() {
        if (DEBUG) Log.v(TAG, "finishRestore()");
    }
}
