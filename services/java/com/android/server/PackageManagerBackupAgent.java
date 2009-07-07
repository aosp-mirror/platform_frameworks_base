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

package com.android.server;

import android.app.BackupAgent;
import android.backup.BackupDataInput;
import android.backup.BackupDataOutput;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.Signature;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * We back up the signatures of each package so that during a system restore,
 * we can verify that the app whose data we think we have matches the app
 * actually resident on the device.
 *
 * Since the Package Manager isn't a proper "application" we just provide a
 * direct IBackupAgent implementation and hand-construct it at need.
 */
public class PackageManagerBackupAgent extends BackupAgent {
    private static final String TAG = "PMBA";
    private static final boolean DEBUG = true;

    // key under which we store global metadata (individual app metadata
    // is stored using the package name as a key)
    private static final String GLOBAL_METADATA_KEY = "@meta@";

    private List<PackageInfo> mAllPackages;
    private PackageManager mPackageManager;
    // version & signature info of each app in a restore set
    private HashMap<String, Metadata> mRestoredSignatures;
    // The version info of each backed-up app as read from the state file
    private HashMap<String, Metadata> mStateVersions = new HashMap<String, Metadata>();

    private final HashSet<String> mExisting = new HashSet<String>();
    private int mStoredSdkVersion;
    private String mStoredIncrementalVersion;
    private boolean mHasMetadata;

    public class Metadata {
        public int versionCode;
        public Signature[] signatures;

        Metadata(int version, Signature[] sigs) {
            versionCode = version;
            signatures = sigs;
        }
    }

    // We're constructed with the set of applications that are participating
    // in backup.  This set changes as apps are installed & removed.
    PackageManagerBackupAgent(PackageManager packageMgr, List<PackageInfo> packages) {
        mPackageManager = packageMgr;
        mAllPackages = packages;
        mRestoredSignatures = null;
        mHasMetadata = false;
    }

    public boolean hasMetadata() {
        return mHasMetadata;
    }

    public Metadata getRestoredMetadata(String packageName) {
        if (mRestoredSignatures == null) {
            Log.w(TAG, "getRestoredMetadata() before metadata read!");
            return null;
        }

        return mRestoredSignatures.get(packageName);
    }
    
    // The backed up data is the signature block for each app, keyed by
    // the package name.
    public void onBackup(ParcelFileDescriptor oldState, BackupDataOutput data,
            ParcelFileDescriptor newState) {
        if (DEBUG) Log.v(TAG, "onBackup()");

        ByteArrayOutputStream bufStream = new ByteArrayOutputStream();  // we'll reuse these
        DataOutputStream outWriter = new DataOutputStream(bufStream);
        parseStateFile(oldState);

        // If the stored version string differs, we need to re-backup all
        // of the metadata.  We force this by removing everything from the
        // "already backed up" map built by parseStateFile().
        if (mStoredIncrementalVersion == null
                || !mStoredIncrementalVersion.equals(Build.VERSION.INCREMENTAL)) {
            Log.i(TAG, "Previous metadata " + mStoredIncrementalVersion + " mismatch vs "
                    + Build.VERSION.INCREMENTAL + " - rewriting");
            mExisting.clear();
        }

        try {
            /*
             * Global metadata:
             *
             * int SDKversion -- the SDK version of the OS itself on the device
             *                   that produced this backup set.  Used to reject
             *                   backups from later OSes onto earlier ones.
             * String incremental -- the incremental release name of the OS stored in
             *                       the backup set.
             */
            if (!mExisting.contains(GLOBAL_METADATA_KEY)) {
                if (DEBUG) Log.v(TAG, "Storing global metadata key");
                outWriter.writeInt(Build.VERSION.SDK_INT);
                outWriter.writeUTF(Build.VERSION.INCREMENTAL);
                byte[] metadata = bufStream.toByteArray();
                data.writeEntityHeader(GLOBAL_METADATA_KEY, metadata.length);
                data.writeEntityData(metadata, metadata.length);
            } else {
                if (DEBUG) Log.v(TAG, "Global metadata key already stored");
                // don't consider it to have been skipped/deleted
                mExisting.remove(GLOBAL_METADATA_KEY);
            }

            // For each app we have on device, see if we've backed it up yet.  If not,
            // write its signature block to the output, keyed on the package name.
            for (PackageInfo pkg : mAllPackages) {
                String packName = pkg.packageName;
                if (packName.equals(GLOBAL_METADATA_KEY)) {
                    // We've already handled the metadata key; skip it here
                    continue;
                } else {
                    PackageInfo info = null;
                    try {
                        info = mPackageManager.getPackageInfo(packName,
                                PackageManager.GET_SIGNATURES);
                    } catch (NameNotFoundException e) {
                        // Weird; we just found it, and now are told it doesn't exist.
                        // Treat it as having been removed from the device.
                        mExisting.add(packName);
                        continue;
                    }

                    boolean doBackup = false;
                    if (!mExisting.contains(packName)) {
                        // We haven't backed up this app before
                        doBackup = true;
                    } else {
                        // We *have* backed this one up before.  Check whether the version
                        // of the backup matches the version of the current app; if they
                        // don't match, the app has been updated and we need to store its
                        // metadata again.  In either case, take it out of mExisting so that
                        // we don't consider it deleted later.
                        if (info.versionCode != mStateVersions.get(packName).versionCode) {
                            doBackup = true;
                        }
                        mExisting.remove(packName);
                    }

                    if (doBackup) {
                        // We need to store this app's metadata
                        /*
                         * Metadata for each package:
                         *
                         * int version       -- [4] the package's versionCode
                         * byte[] signatures -- [len] flattened Signature[] of the package
                         */

                        // marshal the version code in a canonical form
                        bufStream.reset();
                        outWriter.writeInt(info.versionCode);
                        byte[] versionBuf = bufStream.toByteArray();

                        byte[] sigs = flattenSignatureArray(info.signatures);

                        // !!! TODO: take out this debugging
                        if (DEBUG) {
                            Log.v(TAG, "+ metadata for " + packName
                                    + " version=" + info.versionCode
                                    + " versionLen=" + versionBuf.length
                                    + " sigsLen=" + sigs.length);
                        }
                        // Now we can write the backup entity for this package
                        data.writeEntityHeader(packName, versionBuf.length + sigs.length);
                        data.writeEntityData(versionBuf, versionBuf.length);
                        data.writeEntityData(sigs, sigs.length);
                    }
                }
            }

            // At this point, the only entries in 'existing' are apps that were
            // mentioned in the saved state file, but appear to no longer be present
            // on the device.  Write a deletion entity for them.
            for (String app : mExisting) {
                // !!! TODO: take out this msg
                if (DEBUG) Log.v(TAG, "- removing metadata for deleted pkg " + app);
                try {
                    data.writeEntityHeader(app, -1);
                } catch (IOException e) {
                    Log.e(TAG, "Unable to write package deletions!");
                    return;
                }
            }
        } catch (IOException e) {
            // Real error writing data
            Log.e(TAG, "Unable to write package backup data file!");
            return;
        }

        // Finally, write the new state blob -- just the list of all apps we handled
        writeStateFile(mAllPackages, newState);
    }

    // "Restore" here is a misnomer.  What we're really doing is reading back the
    // set of app signatures associated with each backed-up app in this restore
    // image.  We'll use those later to determine what we can legitimately restore.
    public void onRestore(BackupDataInput data, int appVersionCode, ParcelFileDescriptor newState)
            throws IOException {
        List<ApplicationInfo> restoredApps = new ArrayList<ApplicationInfo>();
        HashMap<String, Metadata> sigMap = new HashMap<String, Metadata>();
        if (DEBUG) Log.v(TAG, "onRestore()");
        int storedSystemVersion = -1;

        while (data.readNextHeader()) {
            String key = data.getKey();
            int dataSize = data.getDataSize();

            if (DEBUG) Log.v(TAG, "   got key=" + key + " dataSize=" + dataSize);

            // generic setup to parse any entity data
            byte[] dataBuf = new byte[dataSize];
            data.readEntityData(dataBuf, 0, dataSize);
            ByteArrayInputStream baStream = new ByteArrayInputStream(dataBuf);
            DataInputStream in = new DataInputStream(baStream);

            if (key.equals(GLOBAL_METADATA_KEY)) {
                int storedSdkVersion = in.readInt();
                if (DEBUG) Log.v(TAG, "   storedSystemVersion = " + storedSystemVersion);
                if (storedSystemVersion > Build.VERSION.SDK_INT) {
                    // returning before setting the sig map means we rejected the restore set
                    Log.w(TAG, "Restore set was from a later version of Android; not restoring");
                    return;
                }
                mStoredSdkVersion = storedSdkVersion;
                mStoredIncrementalVersion = in.readUTF();
                mHasMetadata = true;
                // !!! TODO: remove this debugging output
                if (DEBUG) {
                    Log.i(TAG, "Restore set version " + storedSystemVersion
                            + " is compatible with OS version " + Build.VERSION.SDK_INT
                            + " (" + mStoredIncrementalVersion + " vs "
                            + Build.VERSION.INCREMENTAL + ")");
                }
            } else {
                // it's a file metadata record
                int versionCode = in.readInt();
                Signature[] sigs = unflattenSignatureArray(in);
//              !!! TODO: take out this debugging
                if (DEBUG) {
                    Log.i(TAG, "   restored metadata for " + key
                            + " dataSize=" + dataSize
                            + " versionCode=" + versionCode + " sigs=" + sigs);
                }

                ApplicationInfo app = new ApplicationInfo();
                app.packageName = key;
                restoredApps.add(app);
                sigMap.put(key, new Metadata(versionCode, sigs));
            }
        }

        // On successful completion, cache the signature map for the Backup Manager to use
        mRestoredSignatures = sigMap;
    }


    // Util: convert an array of Signatures into a flattened byte buffer.  The
    // flattened format contains enough info to reconstruct the signature array.
    private byte[] flattenSignatureArray(Signature[] allSigs) {
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(outBuf);

        // build the set of subsidiary buffers
        try {
            // first the # of signatures in the array
            out.writeInt(allSigs.length);

            // then the signatures themselves, length + flattened buffer
            for (Signature sig : allSigs) {
                byte[] flat = sig.toByteArray();
                out.writeInt(flat.length);
                out.write(flat);
            }
        } catch (IOException e) {
            // very strange; we're writing to memory here.  abort.
            return null;
        }

        return outBuf.toByteArray();
    }

    private Signature[] unflattenSignatureArray(/*byte[] buffer*/ DataInputStream in) {
        Signature[] sigs = null;

        try {
            int num = in.readInt();
            Log.v(TAG, " ... unflatten read " + num);
            sigs = new Signature[num];
            for (int i = 0; i < num; i++) {
                int len = in.readInt();
                byte[] flatSig = new byte[len];
                in.read(flatSig);
                sigs[i] = new Signature(flatSig);
            }
        } catch (EOFException e) {
            // clean termination
            if (sigs == null) {
                Log.w(TAG, "Empty signature block found");
            }
        } catch (IOException e) {
            Log.d(TAG, "Unable to unflatten sigs");
            return null;
        }

        return sigs;
    }

    // Util: parse out an existing state file into a usable structure
    private void parseStateFile(ParcelFileDescriptor stateFile) {
        mExisting.clear();
        mStateVersions.clear();
        mStoredSdkVersion = 0;
        mStoredIncrementalVersion = null;

        // The state file is just the list of app names we have stored signatures for
        // with the exception of the metadata block, to which is also appended the
        // version numbers corresponding with the last time we wrote this PM block.
        // If they mismatch the current system, we'll re-store the metadata key.
        FileInputStream instream = new FileInputStream(stateFile.getFileDescriptor());
        DataInputStream in = new DataInputStream(instream);

        int bufSize = 256;
        byte[] buf = new byte[bufSize];
        try {
            String pkg = in.readUTF();
            if (pkg.equals(GLOBAL_METADATA_KEY)) {
                mStoredSdkVersion = in.readInt();
                mStoredIncrementalVersion = in.readUTF();
                mExisting.add(GLOBAL_METADATA_KEY);
            } else {
                Log.e(TAG, "No global metadata in state file!");
                return;
            }

            // The global metadata was first; now read all the apps
            while (true) {
                pkg = in.readUTF();
                int versionCode = in.readInt();
                mExisting.add(pkg);
                mStateVersions.put(pkg, new Metadata(versionCode, null));
            }
        } catch (EOFException eof) {
            // safe; we're done
        } catch (IOException e) {
            // whoops, bad state file.  abort.
            Log.e(TAG, "Unable to read Package Manager state file: " + e);
        }
    }

    // Util: write out our new backup state file
    private void writeStateFile(List<PackageInfo> pkgs, ParcelFileDescriptor stateFile) {
        FileOutputStream outstream = new FileOutputStream(stateFile.getFileDescriptor());
        DataOutputStream out = new DataOutputStream(outstream);

        try {
            // by the time we get here we know we've stored the global metadata record
            out.writeUTF(GLOBAL_METADATA_KEY);
            out.writeInt(Build.VERSION.SDK_INT);
            out.writeUTF(Build.VERSION.INCREMENTAL);

            // now write all the app names too
            for (PackageInfo pkg : pkgs) {
                out.writeUTF(pkg.packageName);
                out.writeInt(pkg.versionCode);
            }
        } catch (IOException e) {
            Log.e(TAG, "Unable to write package manager state file!");
            return;
        }
    }
}
