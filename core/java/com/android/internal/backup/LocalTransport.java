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
import android.app.backup.BackupTransport;
import android.app.backup.RestoreDescription;
import android.app.backup.RestoreSet;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.SELinux;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStat;
import android.util.Log;

import com.android.org.bouncycastle.util.encoders.Base64;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import static android.system.OsConstants.*;

/**
 * Backup transport for stashing stuff into a known location on disk, and
 * later restoring from there.  For testing only.
 */

public class LocalTransport extends BackupTransport {
    private static final String TAG = "LocalTransport";
    private static final boolean DEBUG = true;

    private static final String TRANSPORT_DIR_NAME
            = "com.android.internal.backup.LocalTransport";

    private static final String TRANSPORT_DESTINATION_STRING
            = "Backing up to debug-only private cache";

    private static final String INCREMENTAL_DIR = "_delta";
    private static final String FULL_DATA_DIR = "_full";

    // The currently-active restore set always has the same (nonzero!) token
    private static final long CURRENT_SET_TOKEN = 1;

    private Context mContext;
    private File mDataDir = new File(Environment.getDownloadCacheDirectory(), "backup");
    private File mCurrentSetDir = new File(mDataDir, Long.toString(CURRENT_SET_TOKEN));
    private File mCurrentSetIncrementalDir = new File(mCurrentSetDir, INCREMENTAL_DIR);
    private File mCurrentSetFullDir = new File(mCurrentSetDir, FULL_DATA_DIR);

    private PackageInfo[] mRestorePackages = null;
    private int mRestorePackage = -1;  // Index into mRestorePackages
    private int mRestoreType;
    private File mRestoreSetDir;
    private File mRestoreSetIncrementalDir;
    private File mRestoreSetFullDir;
    private long mRestoreToken;

    // Additional bookkeeping for full backup
    private String mFullTargetPackage;
    private ParcelFileDescriptor mSocket;
    private FileInputStream mSocketInputStream;
    private BufferedOutputStream mFullBackupOutputStream;
    private byte[] mFullBackupBuffer;

    private File mFullRestoreSetDir;
    private HashSet<String> mFullRestorePackages;

    public LocalTransport(Context context) {
        mContext = context;
        mCurrentSetDir.mkdirs();
        mCurrentSetFullDir.mkdir();
        mCurrentSetIncrementalDir.mkdir();
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
        return BackupTransport.TRANSPORT_OK;
    }

    public int performBackup(PackageInfo packageInfo, ParcelFileDescriptor data) {
        if (DEBUG) {
            try {
            StructStat ss = Os.fstat(data.getFileDescriptor());
            Log.v(TAG, "performBackup() pkg=" + packageInfo.packageName
                    + " size=" + ss.st_size);
            } catch (ErrnoException e) {
                Log.w(TAG, "Unable to stat input file in performBackup() on "
                        + packageInfo.packageName);
            }
        }

        File packageDir = new File(mCurrentSetIncrementalDir, packageInfo.packageName);
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
                            long cur = Os.lseek(data.getFileDescriptor(), 0, SEEK_CUR);
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
                        return BackupTransport.TRANSPORT_ERROR;
                    } finally {
                        entity.close();
                    }
                } else {
                    entityFile.delete();
                }
            }
            return BackupTransport.TRANSPORT_OK;
        } catch (IOException e) {
            // oops, something went wrong.  abort the operation and return error.
            Log.v(TAG, "Exception reading backup input:", e);
            return BackupTransport.TRANSPORT_ERROR;
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

        File packageDir = new File(mCurrentSetIncrementalDir, packageInfo.packageName);
        final File[] fileset = packageDir.listFiles();
        if (fileset != null) {
            for (File f : fileset) {
                f.delete();
            }
            packageDir.delete();
        }

        packageDir = new File(mCurrentSetFullDir, packageInfo.packageName);
        final File[] tarballs = packageDir.listFiles();
        if (tarballs != null) {
            for (File f : tarballs) {
                f.delete();
            }
            packageDir.delete();
        }

        return BackupTransport.TRANSPORT_OK;
    }

    public int finishBackup() {
        if (DEBUG) Log.v(TAG, "finishBackup()");
        if (mSocket != null) {
            if (DEBUG) {
                Log.v(TAG, "Concluding full backup of " + mFullTargetPackage);
            }
            try {
                mFullBackupOutputStream.flush();
                mFullBackupOutputStream.close();
                mSocketInputStream = null;
                mFullTargetPackage = null;
                mSocket.close();
            } catch (IOException e) {
                return BackupTransport.TRANSPORT_ERROR;
            } finally {
                mSocket = null;
            }
        }
        return BackupTransport.TRANSPORT_OK;
    }

    // ------------------------------------------------------------------------------------
    // Full backup handling
    public long requestFullBackupTime() {
        return 0;
    }

    public int performFullBackup(PackageInfo targetPackage, ParcelFileDescriptor socket) {
        if (mSocket != null) {
            Log.e(TAG, "Attempt to initiate full backup while one is in progress");
            return BackupTransport.TRANSPORT_ERROR;
        }

        if (DEBUG) {
            Log.i(TAG, "performFullBackup : " + targetPackage);
        }

        // We know a priori that we run in the system process, so we need to make
        // sure to dup() our own copy of the socket fd.  Transports which run in
        // their own processes must not do this.
        try {
            mSocket = ParcelFileDescriptor.dup(socket.getFileDescriptor());
            mSocketInputStream = new FileInputStream(mSocket.getFileDescriptor());
        } catch (IOException e) {
            Log.e(TAG, "Unable to process socket for full backup");
            return BackupTransport.TRANSPORT_ERROR;
        }

        mFullTargetPackage = targetPackage.packageName;
        FileOutputStream tarstream;
        try {
            File tarball = new File(mCurrentSetFullDir, mFullTargetPackage);
            tarstream = new FileOutputStream(tarball);
        } catch (FileNotFoundException e) {
            return BackupTransport.TRANSPORT_ERROR;
        }
        mFullBackupOutputStream = new BufferedOutputStream(tarstream);
        mFullBackupBuffer = new byte[4096];

        return BackupTransport.TRANSPORT_OK;
    }

    public int sendBackupData(int numBytes) {
        if (mFullBackupBuffer == null) {
            Log.w(TAG, "Attempted sendBackupData before performFullBackup");
            return BackupTransport.TRANSPORT_ERROR;
        }

        if (numBytes > mFullBackupBuffer.length) {
            mFullBackupBuffer = new byte[numBytes];
        }
        while (numBytes > 0) {
            try {
            int nRead = mSocketInputStream.read(mFullBackupBuffer, 0, numBytes);
            if (nRead < 0) {
                // Something went wrong if we expect data but saw EOD
                Log.w(TAG, "Unexpected EOD; failing backup");
                return BackupTransport.TRANSPORT_ERROR;
            }
            mFullBackupOutputStream.write(mFullBackupBuffer, 0, nRead);
            numBytes -= nRead;
            } catch (IOException e) {
                Log.e(TAG, "Error handling backup data for " + mFullTargetPackage);
                return BackupTransport.TRANSPORT_ERROR;
            }
        }
        return BackupTransport.TRANSPORT_OK;
    }

    // ------------------------------------------------------------------------------------
    // Restore handling
    static final long[] POSSIBLE_SETS = { 2, 3, 4, 5, 6, 7, 8, 9 }; 
    public RestoreSet[] getAvailableRestoreSets() {
        long[] existing = new long[POSSIBLE_SETS.length + 1];
        int num = 0;

        // see which possible non-current sets exist...
        for (long token : POSSIBLE_SETS) {
            if ((new File(mDataDir, Long.toString(token))).exists()) {
                existing[num++] = token;
            }
        }
        // ...and always the currently-active set last
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
        mRestoreSetDir = new File(mDataDir, Long.toString(token));
        mRestoreSetIncrementalDir = new File(mRestoreSetDir, INCREMENTAL_DIR);
        mRestoreSetFullDir = new File(mRestoreSetDir, FULL_DATA_DIR);
        return BackupTransport.TRANSPORT_OK;
    }

    @Override
    public RestoreDescription nextRestorePackage() {
        if (mRestorePackages == null) throw new IllegalStateException("startRestore not called");

        boolean found = false;
        while (++mRestorePackage < mRestorePackages.length) {
            String name = mRestorePackages[mRestorePackage].packageName;

            // If we have key/value data for this package, deliver that
            // skip packages where we have a data dir but no actual contents
            String[] contents = (new File(mRestoreSetIncrementalDir, name)).list();
            if (contents != null && contents.length > 0) {
                if (DEBUG) Log.v(TAG, "  nextRestorePackage(TYPE_KEY_VALUE) = " + name);
                mRestoreType = RestoreDescription.TYPE_KEY_VALUE;
                found = true;
            }

            if (!found) {
                // No key/value data; check for [non-empty] full data
                File maybeFullData = new File(mRestoreSetFullDir, name);
                if (maybeFullData.length() > 0) {
                    if (DEBUG) Log.v(TAG, "  nextRestorePackage(TYPE_FULL_STREAM) = " + name);
                    mRestoreType = RestoreDescription.TYPE_FULL_STREAM;
                    found = true;
                }
            }

            if (found) {
                return new RestoreDescription(name, mRestoreType);
            }
        }

        if (DEBUG) Log.v(TAG, "  no more packages to restore");
        return RestoreDescription.NO_MORE_PACKAGES;
    }

    public int getRestoreData(ParcelFileDescriptor outFd) {
        if (mRestorePackages == null) throw new IllegalStateException("startRestore not called");
        if (mRestorePackage < 0) throw new IllegalStateException("nextRestorePackage not called");
        if (mRestoreType != RestoreDescription.TYPE_KEY_VALUE) {
            throw new IllegalStateException("getRestoreData(fd) for non-key/value dataset");
        }
        File packageDir = new File(mRestoreSetDir, mRestorePackages[mRestorePackage].packageName);

        // The restore set is the concatenation of the individual record blobs,
        // each of which is a file in the package's directory.  We return the
        // data in lexical order sorted by key, so that apps which use synthetic
        // keys like BLOB_1, BLOB_2, etc will see the date in the most obvious
        // order.
        ArrayList<DecodedFilename> blobs = contentsByKey(packageDir);
        if (blobs == null) {  // nextRestorePackage() ensures the dir exists, so this is an error
            Log.e(TAG, "No keys for package: " + packageDir);
            return BackupTransport.TRANSPORT_ERROR;
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
            return BackupTransport.TRANSPORT_OK;
        } catch (IOException e) {
            Log.e(TAG, "Unable to read backup records", e);
            return BackupTransport.TRANSPORT_ERROR;
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

    // ------------------------------------------------------------------------------------
    // Full restore handling

    public int prepareFullRestore(long token, String[] targetPackages) {
        mRestoreSetDir = new File(mDataDir, Long.toString(token));
        mFullRestoreSetDir = new File(mRestoreSetDir, FULL_DATA_DIR);
        mFullRestorePackages = new HashSet<String>();
        if (mFullRestoreSetDir.exists()) {
            List<String> pkgs = Arrays.asList(mFullRestoreSetDir.list());
            HashSet<String> available = new HashSet<String>(pkgs);

            for (int i = 0; i < targetPackages.length; i++) {
                if (available.contains(targetPackages[i])) {
                    mFullRestorePackages.add(targetPackages[i]);
                }
            }
        }
        return BackupTransport.TRANSPORT_OK;
    }

    /**
     * Ask the transport what package's full data will be restored next.  When all apps'
     * data has been delivered, the transport should return {@code null} here.
     * @return The package name of the next application whose data will be restored, or
     *    {@code null} if all available package has been delivered.
     */
    public String getNextFullRestorePackage() {
        return null;
    }

    /**
     * Ask the transport to provide data for the "current" package being restored.  The
     * transport then writes some data to the socket supplied to this call, and returns
     * the number of bytes written.  The system will then read that many bytes and
     * stream them to the application's agent for restore, then will call this method again
     * to receive the next chunk of the archive.  This sequence will be repeated until the
     * transport returns zero indicating that all of the package's data has been delivered
     * (or returns a negative value indicating some sort of hard error condition at the
     * transport level).
     *
     * <p>After this method returns zero, the system will then call
     * {@link #getNextFullRestorePackage()} to begin the restore process for the next
     * application, and the sequence begins again.
     *
     * @param socket The file descriptor that the transport will use for delivering the
     *    streamed archive.
     * @return 0 when no more data for the current package is available.  A positive value
     *    indicates the presence of that much data to be delivered to the app.  A negative
     *    return value is treated as equivalent to {@link BackupTransport#TRANSPORT_ERROR},
     *    indicating a fatal error condition that precludes further restore operations
     *    on the current dataset.
     */
    public int getNextFullRestoreDataChunk(ParcelFileDescriptor socket) {
        return 0;
    }
}
