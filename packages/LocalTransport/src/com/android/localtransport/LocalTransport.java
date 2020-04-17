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

package com.android.localtransport;

import android.annotation.Nullable;
import android.app.backup.BackupAgent;
import android.app.backup.BackupDataInput;
import android.app.backup.BackupDataOutput;
import android.app.backup.BackupTransport;
import android.app.backup.RestoreDescription;
import android.app.backup.RestoreSet;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStat;
import android.util.ArrayMap;
import android.util.Base64;
import android.util.Log;

import libcore.io.IoUtils;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;

/**
 * Backup transport for stashing stuff into a known location on disk, and
 * later restoring from there.  For testing only.
 */

public class LocalTransport extends BackupTransport {
    private static final String TAG = "LocalTransport";
    private static final boolean DEBUG = false;

    private static final String TRANSPORT_DIR_NAME
            = "com.android.localtransport.LocalTransport";

    private static final String TRANSPORT_DESTINATION_STRING
            = "Backing up to debug-only private cache";

    private static final String TRANSPORT_DATA_MANAGEMENT_LABEL
            = "";

    private static final String INCREMENTAL_DIR = "_delta";
    private static final String FULL_DATA_DIR = "_full";

    // The currently-active restore set always has the same (nonzero!) token
    private static final long CURRENT_SET_TOKEN = 1;

    // Size quotas at reasonable values, similar to the current cloud-storage limits
    private static final long FULL_BACKUP_SIZE_QUOTA = 25 * 1024 * 1024;
    protected static final long KEY_VALUE_BACKUP_SIZE_QUOTA = 5 * 1024 * 1024;

    private Context mContext;
    private File mDataDir;
    private File mCurrentSetDir;
    protected File mCurrentSetIncrementalDir;
    private File mCurrentSetFullDir;

    protected PackageInfo[] mRestorePackages = null;
    protected int mRestorePackage = -1;  // Index into mRestorePackages
    protected int mRestoreType;
    private File mRestoreSetDir;
    protected File mRestoreSetIncrementalDir;
    private File mRestoreSetFullDir;

    // Additional bookkeeping for full backup
    private String mFullTargetPackage;
    private ParcelFileDescriptor mSocket;
    private FileInputStream mSocketInputStream;
    private BufferedOutputStream mFullBackupOutputStream;
    private byte[] mFullBackupBuffer;
    private long mFullBackupSize;

    private FileInputStream mCurFullRestoreStream;
    private byte[] mFullRestoreBuffer;
    private final LocalTransportParameters mParameters;

    private void makeDataDirs() {
        mDataDir = mContext.getFilesDir();
        mCurrentSetDir = new File(mDataDir, Long.toString(CURRENT_SET_TOKEN));
        mCurrentSetIncrementalDir = new File(mCurrentSetDir, INCREMENTAL_DIR);
        mCurrentSetFullDir = new File(mCurrentSetDir, FULL_DATA_DIR);

        mCurrentSetDir.mkdirs();
        mCurrentSetFullDir.mkdir();
        mCurrentSetIncrementalDir.mkdir();
    }

    public LocalTransport(Context context, LocalTransportParameters parameters) {
        mContext = context;
        mParameters = parameters;
        makeDataDirs();
    }

    public LocalTransportParameters getParameters() {
        return mParameters;
    }

    @Override
    public String name() {
        return new ComponentName(mContext, this.getClass()).flattenToShortString();
    }

    @Override
    public Intent configurationIntent() {
        // The local transport is not user-configurable
        return null;
    }

    @Override
    public String currentDestinationString() {
        return TRANSPORT_DESTINATION_STRING;
    }

    public Intent dataManagementIntent() {
        // The local transport does not present a data-management UI
        // TODO: consider adding simple UI to wipe the archives entirely,
        // for cleaning up the cache partition.
        return null;
    }

    @Override
    @Nullable
    public CharSequence dataManagementIntentLabel() {
        return TRANSPORT_DATA_MANAGEMENT_LABEL;
    }

    @Override
    public String transportDirName() {
        return TRANSPORT_DIR_NAME;
    }

    @Override
    public int getTransportFlags() {
        int flags = super.getTransportFlags();
        // Testing for a fake flag and having it set as a boolean in settings prevents anyone from
        // using this it to pull data from the agent
        if (mParameters.isFakeEncryptionFlag()) {
            flags |= BackupAgent.FLAG_FAKE_CLIENT_SIDE_ENCRYPTION_ENABLED;
        }
        return flags;
    }

    @Override
    public long requestBackupTime() {
        // any time is a good time for local backup
        return 0;
    }

    @Override
    public int initializeDevice() {
        if (DEBUG) Log.v(TAG, "wiping all data");
        deleteContents(mCurrentSetDir);
        makeDataDirs();
        return TRANSPORT_OK;
    }

    // Encapsulation of a single k/v element change
    private class KVOperation {
        final String key;     // Element filename, not the raw key, for efficiency
        final byte[] value;   // null when this is a deletion operation

        KVOperation(String k, byte[] v) {
            key = k;
            value = v;
        }
    }

    @Override
    public int performBackup(PackageInfo packageInfo, ParcelFileDescriptor data) {
        return performBackup(packageInfo, data, /*flags=*/ 0);
    }

    @Override
    public int performBackup(PackageInfo packageInfo, ParcelFileDescriptor data, int flags) {
        try {
            return performBackupInternal(packageInfo, data, flags);
        } finally {
            IoUtils.closeQuietly(data);
        }
    }

    private int performBackupInternal(
            PackageInfo packageInfo, ParcelFileDescriptor data, int flags) {
        if ((flags & BackupTransport.FLAG_DATA_NOT_CHANGED) != 0) {
            // For unchanged data notifications we do nothing and tell the
            // caller everything was OK
            return BackupTransport.TRANSPORT_OK;
        }

        boolean isIncremental = (flags & FLAG_INCREMENTAL) != 0;
        boolean isNonIncremental = (flags & FLAG_NON_INCREMENTAL) != 0;

        if (isIncremental) {
            Log.i(TAG, "Performing incremental backup for " + packageInfo.packageName);
        } else if (isNonIncremental) {
            Log.i(TAG, "Performing non-incremental backup for " + packageInfo.packageName);
        } else {
            Log.i(TAG, "Performing backup for " + packageInfo.packageName);
        }

        if (DEBUG) {
            try {
                StructStat ss = Os.fstat(data.getFileDescriptor());
                Log.v(TAG, "performBackup() pkg=" + packageInfo.packageName
                        + " size=" + ss.st_size + " flags=" + flags);
            } catch (ErrnoException e) {
                Log.w(TAG, "Unable to stat input file in performBackup() on "
                        + packageInfo.packageName);
            }
        }

        File packageDir = new File(mCurrentSetIncrementalDir, packageInfo.packageName);
        boolean hasDataForPackage = !packageDir.mkdirs();

        if (isIncremental) {
            if (mParameters.isNonIncrementalOnly() || !hasDataForPackage) {
                if (mParameters.isNonIncrementalOnly()) {
                    Log.w(TAG, "Transport is in non-incremental only mode.");

                } else {
                    Log.w(TAG,
                            "Requested incremental, but transport currently stores no data for the "
                                    + "package, requesting non-incremental retry.");
                }
                return TRANSPORT_NON_INCREMENTAL_BACKUP_REQUIRED;
            }
        }
        if (isNonIncremental && hasDataForPackage) {
            Log.w(TAG, "Requested non-incremental, deleting existing data.");
            clearBackupData(packageInfo);
            packageDir.mkdirs();
        }

        // Each 'record' in the restore set is kept in its own file, named by
        // the record key.  Wind through the data file, extracting individual
        // record operations and building a list of all the updates to apply
        // in this update.
        final ArrayList<KVOperation> changeOps;
        try {
            changeOps = parseBackupStream(data);
        } catch (IOException e) {
            // oops, something went wrong.  abort the operation and return error.
            Log.v(TAG, "Exception reading backup input", e);
            return TRANSPORT_ERROR;
        }

        // Okay, now we've parsed out the delta's individual operations.  We need to measure
        // the effect against what we already have in the datastore to detect quota overrun.
        // So, we first need to tally up the current in-datastore size per key.
        final ArrayMap<String, Integer> datastore = new ArrayMap<>();
        int totalSize = parseKeySizes(packageDir, datastore);

        // ... and now figure out the datastore size that will result from applying the
        // sequence of delta operations
        if (DEBUG) {
            if (changeOps.size() > 0) {
                Log.v(TAG, "Calculating delta size impact");
            } else {
                Log.v(TAG, "No operations in backup stream, so no size change");
            }
        }
        int updatedSize = totalSize;
        for (KVOperation op : changeOps) {
            // Deduct the size of the key we're about to replace, if any
            final Integer curSize = datastore.get(op.key);
            if (curSize != null) {
                updatedSize -= curSize.intValue();
                if (DEBUG && op.value == null) {
                    Log.v(TAG, "  delete " + op.key + ", updated total " + updatedSize);
                }
            }

            // And add back the size of the value we're about to store, if any
            if (op.value != null) {
                updatedSize += op.value.length;
                if (DEBUG) {
                    Log.v(TAG, ((curSize == null) ? "  new " : "  replace ")
                            +  op.key + ", updated total " + updatedSize);
                }
            }
        }

        // If our final size is over quota, report the failure
        if (updatedSize > KEY_VALUE_BACKUP_SIZE_QUOTA) {
            if (DEBUG) {
                Log.i(TAG, "New datastore size " + updatedSize
                        + " exceeds quota " + KEY_VALUE_BACKUP_SIZE_QUOTA);
            }
            return TRANSPORT_QUOTA_EXCEEDED;
        }

        // No problem with storage size, so go ahead and apply the delta operations
        // (in the order that the app provided them)
        for (KVOperation op : changeOps) {
            File element = new File(packageDir, op.key);

            // this is either a deletion or a rewrite-from-zero, so we can just remove
            // the existing file and proceed in either case.
            element.delete();

            // if this wasn't a deletion, put the new data in place
            if (op.value != null) {
                try (FileOutputStream out = new FileOutputStream(element)) {
                    out.write(op.value, 0, op.value.length);
                } catch (IOException e) {
                    Log.e(TAG, "Unable to update key file " + element, e);
                    return TRANSPORT_ERROR;
                }
            }
        }
        return TRANSPORT_OK;
    }

    // Parses a backup stream into individual key/value operations
    private ArrayList<KVOperation> parseBackupStream(ParcelFileDescriptor data)
            throws IOException {
        ArrayList<KVOperation> changeOps = new ArrayList<>();
        BackupDataInput changeSet = new BackupDataInput(data.getFileDescriptor());
        while (changeSet.readNextHeader()) {
            String key = changeSet.getKey();
            String base64Key = new String(Base64.encode(key.getBytes(), Base64.NO_WRAP));
            int dataSize = changeSet.getDataSize();
            if (DEBUG) {
                Log.v(TAG, "  Delta operation key " + key + "   size " + dataSize
                        + "   key64 " + base64Key);
            }

            byte[] buf = (dataSize >= 0) ? new byte[dataSize] : null;
            if (dataSize >= 0) {
                changeSet.readEntityData(buf, 0, dataSize);
            }
            changeOps.add(new KVOperation(base64Key, buf));
        }
        return changeOps;
    }

    // Reads the given datastore directory, building a table of the value size of each
    // keyed element, and returning the summed total.
    private int parseKeySizes(File packageDir, ArrayMap<String, Integer> datastore) {
        int totalSize = 0;
        final String[] elements = packageDir.list();
        if (elements != null) {
            if (DEBUG) {
                Log.v(TAG, "Existing datastore contents:");
            }
            for (String file : elements) {
                File element = new File(packageDir, file);
                String key = file;  // filename
                int size = (int) element.length();
                totalSize += size;
                if (DEBUG) {
                    Log.v(TAG, "  key " + key + "   size " + size);
                }
                datastore.put(key, size);
            }
            if (DEBUG) {
                Log.v(TAG, "  TOTAL: " + totalSize);
            }
        } else {
            if (DEBUG) {
                Log.v(TAG, "No existing data for this package");
            }
        }
        return totalSize;
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

    @Override
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

        return TRANSPORT_OK;
    }

    @Override
    public int finishBackup() {
        if (DEBUG) Log.v(TAG, "finishBackup() of " + mFullTargetPackage);
        return tearDownFullBackup();
    }

    // ------------------------------------------------------------------------------------
    // Full backup handling

    private int tearDownFullBackup() {
        if (mSocket != null) {
            try {
                if (mFullBackupOutputStream != null) {
                    mFullBackupOutputStream.flush();
                    mFullBackupOutputStream.close();
                }
                mSocketInputStream = null;
                mFullTargetPackage = null;
                mSocket.close();
            } catch (IOException e) {
                if (DEBUG) {
                    Log.w(TAG, "Exception caught in tearDownFullBackup()", e);
                }
                return TRANSPORT_ERROR;
            } finally {
                mSocket = null;
                mFullBackupOutputStream = null;
            }
        }
        return TRANSPORT_OK;
    }

    private File tarballFile(String pkgName) {
        return new File(mCurrentSetFullDir, pkgName);
    }

    @Override
    public long requestFullBackupTime() {
        return 0;
    }

    @Override
    public int checkFullBackupSize(long size) {
        int result = TRANSPORT_OK;
        // Decline zero-size "backups"
        if (size <= 0) {
            result = TRANSPORT_PACKAGE_REJECTED;
        } else if (size > FULL_BACKUP_SIZE_QUOTA) {
            result = TRANSPORT_QUOTA_EXCEEDED;
        }
        if (result != TRANSPORT_OK) {
            if (DEBUG) {
                Log.v(TAG, "Declining backup of size " + size);
            }
        }
        return result;
    }

    @Override
    public int performFullBackup(PackageInfo targetPackage, ParcelFileDescriptor socket) {
        if (mSocket != null) {
            Log.e(TAG, "Attempt to initiate full backup while one is in progress");
            return TRANSPORT_ERROR;
        }

        if (DEBUG) {
            Log.i(TAG, "performFullBackup : " + targetPackage);
        }

        // We know a priori that we run in the system process, so we need to make
        // sure to dup() our own copy of the socket fd.  Transports which run in
        // their own processes must not do this.
        try {
            mFullBackupSize = 0;
            mSocket = ParcelFileDescriptor.dup(socket.getFileDescriptor());
            mSocketInputStream = new FileInputStream(mSocket.getFileDescriptor());
        } catch (IOException e) {
            Log.e(TAG, "Unable to process socket for full backup");
            return TRANSPORT_ERROR;
        }

        mFullTargetPackage = targetPackage.packageName;
        mFullBackupBuffer = new byte[4096];

        return TRANSPORT_OK;
    }

    @Override
    public int sendBackupData(final int numBytes) {
        if (mSocket == null) {
            Log.w(TAG, "Attempted sendBackupData before performFullBackup");
            return TRANSPORT_ERROR;
        }

        mFullBackupSize += numBytes;
        if (mFullBackupSize > FULL_BACKUP_SIZE_QUOTA) {
            return TRANSPORT_QUOTA_EXCEEDED;
        }

        if (numBytes > mFullBackupBuffer.length) {
            mFullBackupBuffer = new byte[numBytes];
        }

        if (mFullBackupOutputStream == null) {
            FileOutputStream tarstream;
            try {
                File tarball = tarballFile(mFullTargetPackage);
                tarstream = new FileOutputStream(tarball);
            } catch (FileNotFoundException e) {
                return TRANSPORT_ERROR;
            }
            mFullBackupOutputStream = new BufferedOutputStream(tarstream);
        }

        int bytesLeft = numBytes;
        while (bytesLeft > 0) {
            try {
                int nRead = mSocketInputStream.read(mFullBackupBuffer, 0, bytesLeft);
                if (nRead < 0) {
                    // Something went wrong if we expect data but saw EOD
                    Log.w(TAG, "Unexpected EOD; failing backup");
                    return TRANSPORT_ERROR;
                }
                mFullBackupOutputStream.write(mFullBackupBuffer, 0, nRead);
                bytesLeft -= nRead;
            } catch (IOException e) {
                Log.e(TAG, "Error handling backup data for " + mFullTargetPackage);
                return TRANSPORT_ERROR;
            }
        }
        if (DEBUG) {
            Log.v(TAG, "   stored " + numBytes + " of data");
        }
        return TRANSPORT_OK;
    }

    // For now we can't roll back, so just tear everything down.
    @Override
    public void cancelFullBackup() {
        if (DEBUG) {
            Log.i(TAG, "Canceling full backup of " + mFullTargetPackage);
        }
        File archive = tarballFile(mFullTargetPackage);
        tearDownFullBackup();
        if (archive.exists()) {
            archive.delete();
        }
    }

    // ------------------------------------------------------------------------------------
    // Restore handling
    static final long[] POSSIBLE_SETS = { 2, 3, 4, 5, 6, 7, 8, 9 };

    @Override
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

    @Override
    public long getCurrentRestoreSet() {
        // The current restore set always has the same token
        return CURRENT_SET_TOKEN;
    }

    @Override
    public int startRestore(long token, PackageInfo[] packages) {
        if (DEBUG) Log.v(TAG, "start restore " + token + " : " + packages.length
                + " matching packages");
        mRestorePackages = packages;
        mRestorePackage = -1;
        mRestoreSetDir = new File(mDataDir, Long.toString(token));
        mRestoreSetIncrementalDir = new File(mRestoreSetDir, INCREMENTAL_DIR);
        mRestoreSetFullDir = new File(mRestoreSetDir, FULL_DATA_DIR);
        return TRANSPORT_OK;
    }

    @Override
    public RestoreDescription nextRestorePackage() {
        if (DEBUG) {
            Log.v(TAG, "nextRestorePackage() : mRestorePackage=" + mRestorePackage
                    + " length=" + mRestorePackages.length);
        }
        if (mRestorePackages == null) throw new IllegalStateException("startRestore not called");

        boolean found;
        while (++mRestorePackage < mRestorePackages.length) {
            String name = mRestorePackages[mRestorePackage].packageName;

            // If we have key/value data for this package, deliver that
            // skip packages where we have a data dir but no actual contents
            found = hasRestoreDataForPackage(name);
            if (found) {
                mRestoreType = RestoreDescription.TYPE_KEY_VALUE;
            }

            if (!found) {
                // No key/value data; check for [non-empty] full data
                File maybeFullData = new File(mRestoreSetFullDir, name);
                if (maybeFullData.length() > 0) {
                    if (DEBUG) {
                        Log.v(TAG, "  nextRestorePackage(TYPE_FULL_STREAM) @ "
                                + mRestorePackage + " = " + name);
                    }
                    mRestoreType = RestoreDescription.TYPE_FULL_STREAM;
                    mCurFullRestoreStream = null;   // ensure starting from the ground state
                    found = true;
                }
            }

            if (found) {
                return new RestoreDescription(name, mRestoreType);
            }

            if (DEBUG) {
                Log.v(TAG, "  ... package @ " + mRestorePackage + " = " + name
                        + " has no data; skipping");
            }
        }

        if (DEBUG) Log.v(TAG, "  no more packages to restore");
        return RestoreDescription.NO_MORE_PACKAGES;
    }

    protected boolean hasRestoreDataForPackage(String packageName) {
        String[] contents = (new File(mRestoreSetIncrementalDir, packageName)).list();
        if (contents != null && contents.length > 0) {
            if (DEBUG) {
                Log.v(TAG, "  nextRestorePackage(TYPE_KEY_VALUE) @ "
                        + mRestorePackage + " = " + packageName);
            }
            return true;
        }
        return false;
    }

    @Override
    public int getRestoreData(ParcelFileDescriptor outFd) {
        if (mRestorePackages == null) throw new IllegalStateException("startRestore not called");
        if (mRestorePackage < 0) throw new IllegalStateException("nextRestorePackage not called");
        if (mRestoreType != RestoreDescription.TYPE_KEY_VALUE) {
            throw new IllegalStateException("getRestoreData(fd) for non-key/value dataset");
        }
        File packageDir = new File(mRestoreSetIncrementalDir,
                mRestorePackages[mRestorePackage].packageName);

        // The restore set is the concatenation of the individual record blobs,
        // each of which is a file in the package's directory.  We return the
        // data in lexical order sorted by key, so that apps which use synthetic
        // keys like BLOB_1, BLOB_2, etc will see the date in the most obvious
        // order.
        ArrayList<DecodedFilename> blobs = contentsByKey(packageDir);
        if (blobs == null) {  // nextRestorePackage() ensures the dir exists, so this is an error
            Log.e(TAG, "No keys for package: " + packageDir);
            return TRANSPORT_ERROR;
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
            return TRANSPORT_OK;
        } catch (IOException e) {
            Log.e(TAG, "Unable to read backup records", e);
            return TRANSPORT_ERROR;
        }
    }

    static class DecodedFilename implements Comparable<DecodedFilename> {
        public File file;
        public String key;

        public DecodedFilename(File f) {
            file = f;
            key = new String(Base64.decode(f.getName(), Base64.DEFAULT));
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

    @Override
    public void finishRestore() {
        if (DEBUG) Log.v(TAG, "finishRestore()");
        if (mRestoreType == RestoreDescription.TYPE_FULL_STREAM) {
            resetFullRestoreState();
        }
        mRestoreType = 0;
    }

    // ------------------------------------------------------------------------------------
    // Full restore handling

    private void resetFullRestoreState() {
        IoUtils.closeQuietly(mCurFullRestoreStream);
        mCurFullRestoreStream = null;
        mFullRestoreBuffer = null;
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
    @Override
    public int getNextFullRestoreDataChunk(ParcelFileDescriptor socket) {
        if (mRestoreType != RestoreDescription.TYPE_FULL_STREAM) {
            throw new IllegalStateException("Asked for full restore data for non-stream package");
        }

        // first chunk?
        if (mCurFullRestoreStream == null) {
            final String name = mRestorePackages[mRestorePackage].packageName;
            if (DEBUG) Log.i(TAG, "Starting full restore of " + name);
            File dataset = new File(mRestoreSetFullDir, name);
            try {
                mCurFullRestoreStream = new FileInputStream(dataset);
            } catch (IOException e) {
                // If we can't open the target package's tarball, we return the single-package
                // error code and let the caller go on to the next package.
                Log.e(TAG, "Unable to read archive for " + name);
                return TRANSPORT_PACKAGE_REJECTED;
            }
            mFullRestoreBuffer = new byte[2*1024];
        }

        FileOutputStream stream = new FileOutputStream(socket.getFileDescriptor());

        int nRead;
        try {
            nRead = mCurFullRestoreStream.read(mFullRestoreBuffer);
            if (nRead < 0) {
                // EOF: tell the caller we're done
                nRead = NO_MORE_DATA;
            } else if (nRead == 0) {
                // This shouldn't happen when reading a FileInputStream; we should always
                // get either a positive nonzero byte count or -1.  Log the situation and
                // treat it as EOF.
                Log.w(TAG, "read() of archive file returned 0; treating as EOF");
                nRead = NO_MORE_DATA;
            } else {
                if (DEBUG) {
                    Log.i(TAG, "   delivering restore chunk: " + nRead);
                }
                stream.write(mFullRestoreBuffer, 0, nRead);
            }
        } catch (IOException e) {
            return TRANSPORT_ERROR;  // Hard error accessing the file; shouldn't happen
        } finally {
            IoUtils.closeQuietly(socket);
        }

        return nRead;
    }

    /**
     * If the OS encounters an error while processing {@link RestoreDescription#TYPE_FULL_STREAM}
     * data for restore, it will invoke this method to tell the transport that it should
     * abandon the data download for the current package.  The OS will then either call
     * {@link #nextRestorePackage()} again to move on to restoring the next package in the
     * set being iterated over, or will call {@link #finishRestore()} to shut down the restore
     * operation.
     *
     * @return {@link #TRANSPORT_OK} if the transport was successful in shutting down the
     *    current stream cleanly, or {@link #TRANSPORT_ERROR} to indicate a serious
     *    transport-level failure.  If the transport reports an error here, the entire restore
     *    operation will immediately be finished with no further attempts to restore app data.
     */
    @Override
    public int abortFullRestore() {
        if (mRestoreType != RestoreDescription.TYPE_FULL_STREAM) {
            throw new IllegalStateException("abortFullRestore() but not currently restoring");
        }
        resetFullRestoreState();
        mRestoreType = 0;
        return TRANSPORT_OK;
    }

    @Override
    public long getBackupQuota(String packageName, boolean isFullBackup) {
        return isFullBackup ? FULL_BACKUP_SIZE_QUOTA : KEY_VALUE_BACKUP_SIZE_QUOTA;
    }
}
