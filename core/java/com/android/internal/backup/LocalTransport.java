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
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.SELinux;
import android.util.Log;

import com.android.org.bouncycastle.util.encoders.Base64;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;

import libcore.io.ErrnoException;
import libcore.io.Libcore;
import libcore.io.StructStat;
import static libcore.io.OsConstants.*;

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

    // The currently-active restore set always has the same (nonzero!) token
    private static final long CURRENT_SET_TOKEN = 1;

    private Context mContext;
    private File mDataDir = new File(Environment.getDownloadCacheDirectory(), "backup");
    private File mCurrentSetDir = new File(mDataDir, Long.toString(CURRENT_SET_TOKEN));

    private PackageInfo[] mRestorePackages = null;
    private int mRestorePackage = -1;  // Index into mRestorePackages
    private File mRestoreDataDir;
    private long mRestoreToken;


    public LocalTransport(Context context) {
        mContext = context;
        mCurrentSetDir.mkdirs();
        if (!SELinux.restorecon(mCurrentSetDir)) {
            Log.e(TAG, "SELinux restorecon failed for " + mCurrentSetDir);
        }
    }

    public String name() {
        return new ComponentName(mContext, this.getClass()).flattenToShortString();
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
        deleteContents(mCurrentSetDir);
        return BackupConstants.TRANSPORT_OK;
    }

    public int performBackup(PackageInfo packageInfo, ParcelFileDescriptor data) {
        if (DEBUG) {
            try {
            StructStat ss = Libcore.os.fstat(data.getFileDescriptor());
            Log.v(TAG, "performBackup() pkg=" + packageInfo.packageName
                    + " size=" + ss.st_size);
            } catch (ErrnoException e) {
                Log.w(TAG, "Unable to stat input file in performBackup() on "
                        + packageInfo.packageName);
            }
        }

        File packageDir = new File(mCurrentSetDir, packageInfo.packageName);
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
                    if (DEBUG) {
                        try {
                            long cur = Libcore.os.lseek(data.getFileDescriptor(), 0, SEEK_CUR);
                            Log.v(TAG, "  read entity data; new pos=" + cur);
                        }
                        catch (ErrnoException e) {
                            Log.w(TAG, "Unable to stat input file in performBackup() on "
                                    + packageInfo.packageName);
                        }
                    }

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

        File packageDir = new File(mCurrentSetDir, packageInfo.packageName);
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
    static final long[] POSSIBLE_SETS = { 2, 3, 4, 5, 6, 7, 8, 9 }; 
    public RestoreSet[] getAvailableRestoreSets() throws android.os.RemoteException {
        long[] existing = new long[POSSIBLE_SETS.length + 1];
        int num = 0;

        // see which possible non-current sets exist, then put the current set at the end
        for (long token : POSSIBLE_SETS) {
            if ((new File(mDataDir, Long.toString(token))).exists()) {
                existing[num++] = token;
            }
        }
        // and always the currently-active set last
        existing[num++] = CURRENT_SET_TOKEN;

        RestoreSet[] available = new RestoreSet[num];
        for (int i = 0; i < available.length; i++) {
            available[i] = new RestoreSet("Local disk image", "flash", existing[i]);
        }
        return available;
    }

    public long getCurrentRestoreSet() {
        // The current restore set always has the same token
        return CURRENT_SET_TOKEN;
    }

    public int startRestore(long token, PackageInfo[] packages) {
        if (DEBUG) Log.v(TAG, "start restore " + token);
        mRestorePackages = packages;
        mRestorePackage = -1;
        mRestoreToken = token;
        mRestoreDataDir = new File(mDataDir, Long.toString(token));
        return BackupConstants.TRANSPORT_OK;
    }

    public String nextRestorePackage() {
        if (mRestorePackages == null) throw new IllegalStateException("startRestore not called");
        while (++mRestorePackage < mRestorePackages.length) {
            String name = mRestorePackages[mRestorePackage].packageName;
            // skip packages where we have a data dir but no actual contents
            String[] contents = (new File(mRestoreDataDir, name)).list();
            if (contents != null && contents.length > 0) {
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
        File packageDir = new File(mRestoreDataDir, mRestorePackages[mRestorePackage].packageName);

        // The restore set is the concatenation of the individual record blobs,
        // each of which is a file in the package's directory.  We return the
        // data in lexical order sorted by key, so that apps which use synthetic
        // keys like BLOB_1, BLOB_2, etc will see the date in the most obvious
        // order.
        ArrayList<DecodedFilename> blobs = contentsByKey(packageDir);
        if (blobs == null) {  // nextRestorePackage() ensures the dir exists, so this is an error
            Log.e(TAG, "No keys for package: " + packageDir);
            return BackupConstants.TRANSPORT_ERROR;
        }

        // We expect at least some data if the directory exists in the first place
        if (DEBUG) Log.v(TAG, "  getRestoreData() found " + blobs.size() + " key files");
        BackupDataOutput out = new BackupDataOutput(outFd.getFileDescriptor());
        try {
            for (DecodedFilename keyEntry : blobs) {
                File f = keyEntry.file;
                FileInputStream in = new FileInputStream(f);
                try {
                    int size = (int) f.length();
                    byte[] buf = new byte[size];
                    in.read(buf);
                    if (DEBUG) Log.v(TAG, "    ... key=" + keyEntry.key + " size=" + size);
                    out.writeEntityHeader(keyEntry.key, size);
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

    static class DecodedFilename implements Comparable<DecodedFilename> {
        public File file;
        public String key;

        public DecodedFilename(File f) {
            file = f;
            key = new String(Base64.decode(f.getName()));
        }

        @Override
        public int compareTo(DecodedFilename other) {
            // sorts into ascending lexical order by decoded key
            return key.compareTo(other.key);
        }
    }

    // Return a list of the files in the given directory, sorted lexically by
    // the Base64-decoded file name, not by the on-disk filename
    private ArrayList<DecodedFilename> contentsByKey(File dir) {
        File[] allFiles = dir.listFiles();
        if (allFiles == null || allFiles.length == 0) {
            return null;
        }

        // Decode the filenames into keys then sort lexically by key
        ArrayList<DecodedFilename> contents = new ArrayList<DecodedFilename>();
        for (File f : allFiles) {
            contents.add(new DecodedFilename(f));
        }
        Collections.sort(contents);
        return contents;
    }

    public void finishRestore() {
        if (DEBUG) Log.v(TAG, "finishRestore()");
    }
}
