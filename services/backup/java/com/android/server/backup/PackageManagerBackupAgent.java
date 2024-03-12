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

package com.android.server.backup;

import android.app.backup.BackupAgent;
import android.app.backup.BackupDataInput;
import android.app.backup.BackupDataOutput;
import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.backup.utils.BackupEligibilityRules;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
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
import java.util.Set;

/**
 * We back up the signatures of each package so that during a system restore, we can verify that the
 * app whose data we think we have matches the app actually resident on the device.
 *
 * <p>Since the Package Manager isn't a proper "application" we just provide a direct IBackupAgent
 * implementation and hand-construct it at need.
 */
public class PackageManagerBackupAgent extends BackupAgent {
    private static final String TAG = "PMBA";
    private static final boolean DEBUG = false;

    // key under which we store global metadata (individual app metadata
    // is stored using the package name as a key)
    @VisibleForTesting static final String GLOBAL_METADATA_KEY = "@meta@";

    // key under which we store the identity of the user's chosen default home app
    private static final String DEFAULT_HOME_KEY = "@home@";

    // Sentinel: start of state file, followed by a version number
    // Note that STATE_FILE_VERSION=2 is tied to UNDEFINED_ANCESTRAL_RECORD_VERSION=-1 *as well as*
    // ANCESTRAL_RECORD_VERSION=1 (introduced Android P).
    // Should the ANCESTRAL_RECORD_VERSION be bumped up in the future, STATE_FILE_VERSION will also
    // need bumping up, assuming more data needs saving to the state file.
    @VisibleForTesting static final String STATE_FILE_HEADER = "=state=";
    @VisibleForTesting static final int STATE_FILE_VERSION = 2;

    // key under which we store the saved ancestral-dataset format (starting from Android P)
    // IMPORTANT: this key needs to come first in the restore data stream (to find out
    // whether this version of Android knows how to restore the incoming data set), so it needs
    // to be always the first one in alphabetical order of all the keys
    @VisibleForTesting static final String ANCESTRAL_RECORD_KEY = "@ancestral_record@";

    // Current version of the saved ancestral-dataset format
    // Note that this constant was not used until Android P, and started being used
    // to version @pm@ data for forwards-compatibility.
    @VisibleForTesting static final int ANCESTRAL_RECORD_VERSION = 1;

    // Undefined version of the saved ancestral-dataset file format means that the restore data
    // is coming from pre-Android P device.
    private static final int UNDEFINED_ANCESTRAL_RECORD_VERSION = -1;

    private int mUserId;
    private List<PackageInfo> mAllPackages;
    private PackageManager mPackageManager;
    // version & signature info of each app in a restore set
    private HashMap<String, Metadata> mRestoredSignatures;
    // The version info of each backed-up app as read from the state file
    private HashMap<String, Metadata> mStateVersions = new HashMap<String, Metadata>();

    private final HashSet<String> mExisting = new HashSet<String>();
    private int mStoredSdkVersion;
    private String mStoredIncrementalVersion;
    private ComponentName mStoredHomeComponent;
    private long mStoredHomeVersion;
    private ArrayList<byte[]> mStoredHomeSigHashes;

    private boolean mHasMetadata;
    private ComponentName mRestoredHome;
    private long mRestoredHomeVersion;
    private String mRestoredHomeInstaller;
    private ArrayList<byte[]> mRestoredHomeSigHashes;

    // For compactness we store the SHA-256 hash of each app's Signatures
    // rather than the Signature blocks themselves.
    public class Metadata {
        public long versionCode;
        public ArrayList<byte[]> sigHashes;

        Metadata(long version, ArrayList<byte[]> hashes) {
            versionCode = version;
            sigHashes = hashes;
        }
    }

    // We're constructed with the set of applications that are participating
    // in backup.  This set changes as apps are installed & removed.
    public PackageManagerBackupAgent(
            PackageManager packageMgr, List<PackageInfo> packages, int userId) {
        init(packageMgr, packages, userId);
    }

    public PackageManagerBackupAgent(
            PackageManager packageMgr, int userId, BackupEligibilityRules backupEligibilityRules) {
        init(packageMgr, null, userId);

        evaluateStorablePackages(backupEligibilityRules);
    }

    private void init(PackageManager packageMgr, List<PackageInfo> packages, int userId) {
        mPackageManager = packageMgr;
        mAllPackages = packages;
        mRestoredSignatures = null;
        mHasMetadata = false;

        mStoredSdkVersion = Build.VERSION.SDK_INT;
        mStoredIncrementalVersion = Build.VERSION.INCREMENTAL;
        mUserId = userId;
    }

    // We will need to refresh our understanding of what is eligible for
    // backup periodically; this entry point serves that purpose.
    public void evaluateStorablePackages(BackupEligibilityRules backupEligibilityRules) {
        mAllPackages = getStorableApplications(mPackageManager, mUserId, backupEligibilityRules);
    }

    /** Gets all packages installed on user {@code userId} eligible for backup. */
    public static List<PackageInfo> getStorableApplications(
            PackageManager pm, int userId, BackupEligibilityRules backupEligibilityRules) {
        List<PackageInfo> pkgs =
                pm.getInstalledPackagesAsUser(PackageManager.GET_SIGNING_CERTIFICATES, userId);
        int N = pkgs.size();
        for (int a = N - 1; a >= 0; a--) {
            PackageInfo pkg = pkgs.get(a);
            if (!backupEligibilityRules.appIsEligibleForBackup(pkg.applicationInfo)) {
                pkgs.remove(a);
            }
        }
        return pkgs;
    }

    public boolean hasMetadata() {
        return mHasMetadata;
    }

    public int getSourceSdk() {
        return mStoredSdkVersion;
    }

    public Metadata getRestoredMetadata(String packageName) {
        if (mRestoredSignatures == null) {
            Slog.w(TAG, "getRestoredMetadata() before metadata read!");
            return null;
        }

        return mRestoredSignatures.get(packageName);
    }

    public Set<String> getRestoredPackages() {
        if (mRestoredSignatures == null) {
            Slog.w(TAG, "getRestoredPackages() before metadata read!");
            return null;
        }

        // This is technically the set of packages on the originating handset
        // that had backup agents at all, not limited to the set of packages
        // that had actually contributed a restore dataset, but it's a
        // close enough approximation for our purposes and does not require any
        // additional involvement by the transport to obtain.
        return mRestoredSignatures.keySet();
    }

    @Override
    public void onBackup(
            ParcelFileDescriptor oldState, BackupDataOutput data, ParcelFileDescriptor newState) {
        if (DEBUG) Slog.v(TAG, "onBackup()");

        // The backed up data is the signature block for each app, keyed by the package name.

        ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream(); // we'll reuse these
        DataOutputStream outputBufferStream = new DataOutputStream(outputBuffer);
        parseStateFile(oldState);

        // If the stored version string differs, we need to re-backup all
        // of the metadata.  We force this by removing everything from the
        // "already backed up" map built by parseStateFile().
        if (mStoredIncrementalVersion == null
                || !mStoredIncrementalVersion.equals(Build.VERSION.INCREMENTAL)) {
            Slog.i(
                    TAG,
                    "Previous metadata "
                            + mStoredIncrementalVersion
                            + " mismatch vs "
                            + Build.VERSION.INCREMENTAL
                            + " - rewriting");
            mExisting.clear();
        }

        /*
         * Ancestral record version:
         *
         * int ancestralRecordVersion -- the version of the format in which this backup set is
         *                               produced
         */
        try {
            if (DEBUG) Slog.v(TAG, "Storing ancestral record version key");
            outputBufferStream.writeInt(ANCESTRAL_RECORD_VERSION);
            writeEntity(data, ANCESTRAL_RECORD_KEY, outputBuffer.toByteArray());
        } catch (IOException e) {
            // Real error writing data
            Slog.e(TAG, "Unable to write package backup data file!");
            return;
        }

        try {
            /*
             * Global metadata:
             *
             * int SDKversion -- the SDK version of the OS itself on the device
             *                   that produced this backup set. Before Android P it was used to
             *                   reject backups from later OSes onto earlier ones.
             * String incremental -- the incremental release name of the OS stored in
             *                       the backup set.
             */
            outputBuffer.reset();
            if (!mExisting.contains(GLOBAL_METADATA_KEY)) {
                if (DEBUG) Slog.v(TAG, "Storing global metadata key");
                outputBufferStream.writeInt(Build.VERSION.SDK_INT);
                outputBufferStream.writeUTF(Build.VERSION.INCREMENTAL);
                writeEntity(data, GLOBAL_METADATA_KEY, outputBuffer.toByteArray());
            } else {
                if (DEBUG) Slog.v(TAG, "Global metadata key already stored");
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
                        info =
                                mPackageManager.getPackageInfoAsUser(
                                        packName, PackageManager.GET_SIGNING_CERTIFICATES, mUserId);
                    } catch (NameNotFoundException e) {
                        // Weird; we just found it, and now are told it doesn't exist.
                        // Treat it as having been removed from the device.
                        mExisting.add(packName);
                        continue;
                    }

                    if (mExisting.contains(packName)) {
                        // We have backed up this app before.  Check whether the version
                        // of the backup matches the version of the current app; if they
                        // don't match, the app has been updated and we need to store its
                        // metadata again.  In either case, take it out of mExisting so that
                        // we don't consider it deleted later.
                        mExisting.remove(packName);
                        if (info.getLongVersionCode() == mStateVersions.get(packName).versionCode) {
                            continue;
                        }
                    }

                    SigningInfo signingInfo = info.signingInfo;
                    if (signingInfo == null) {
                        Slog.w(
                                TAG,
                                "Not backing up package "
                                        + packName
                                        + " since it appears to have no signatures.");
                        continue;
                    }

                    // We need to store this app's metadata
                    /*
                     * Metadata for each package:
                     *
                     * int version       -- [4] the package's versionCode
                     * byte[] signatures -- [len] flattened signature hash array of the package
                     */

                    // marshal the version code in a canonical form
                    outputBuffer.reset();
                    if (info.versionCodeMajor != 0) {
                        outputBufferStream.writeInt(Integer.MIN_VALUE);
                        outputBufferStream.writeLong(info.getLongVersionCode());
                    } else {
                        outputBufferStream.writeInt(info.versionCode);
                    }
                    // retrieve the newest sigs to back up
                    Signature[] infoSignatures = signingInfo.getApkContentsSigners();
                    writeSignatureHashArray(
                            outputBufferStream, BackupUtils.hashSignatureArray(infoSignatures));

                    if (DEBUG) {
                        Slog.v(
                                TAG,
                                "+ writing metadata for "
                                        + packName
                                        + " version="
                                        + info.getLongVersionCode()
                                        + " entityLen="
                                        + outputBuffer.size());
                    }

                    // Now we can write the backup entity for this package
                    writeEntity(data, packName, outputBuffer.toByteArray());
                }
            }

            // At this point, the only entries in 'existing' are apps that were
            // mentioned in the saved state file, but appear to no longer be present
            // on the device.  We want to preserve the entry for them, however,
            // because we want the right thing to happen if the user goes through
            // a backup / uninstall / backup / reinstall sequence.
            if (DEBUG) {
                if (mExisting.size() > 0) {
                    StringBuilder sb = new StringBuilder(64);
                    sb.append("Preserving metadata for deleted packages:");
                    for (String app : mExisting) {
                        sb.append(' ');
                        sb.append(app);
                    }
                    Slog.v(TAG, sb.toString());
                }
            }
        } catch (IOException e) {
            // Real error writing data
            Slog.e(TAG, "Unable to write package backup data file!");
            return;
        }

        // Finally, write the new state blob -- just the list of all apps we handled
        writeStateFile(mAllPackages, newState);
    }

    private static void writeEntity(BackupDataOutput data, String key, byte[] bytes)
            throws IOException {
        data.writeEntityHeader(key, bytes.length);
        data.writeEntityData(bytes, bytes.length);
    }

    @Override
    public void onRestore(BackupDataInput data, int appVersionCode, ParcelFileDescriptor newState)
            throws IOException {
        if (DEBUG) Slog.v(TAG, "onRestore()");

        // "Restore" here is a misnomer.  What we're really doing is reading back the
        // set of app signatures associated with each backed-up app in this restore
        // image.  We'll use those later to determine what we can legitimately restore.

        // we expect the ANCESTRAL_RECORD_KEY ("@ancestral_record@") to always come first in the
        // restore set - based on that value we use different mechanisms to consume the data;
        // if the ANCESTRAL_RECORD_KEY is missing in the restore set, it means that the data is
        // is coming from a pre-Android P device, and we consume the header data in the legacy way
        // TODO: add a CTS test to verify that backups of PMBA generated on Android P+ always
        //       contain the ANCESTRAL_RECORD_KEY, and it's always the first key
        int ancestralRecordVersion = getAncestralRecordVersionValue(data);

        RestoreDataConsumer consumer = getRestoreDataConsumer(ancestralRecordVersion);
        if (consumer == null) {
            Slog.w(
                    TAG,
                    "Ancestral restore set version is unknown"
                            + " to this Android version; not restoring");
            return;
        } else {
            consumer.consumeRestoreData(data);
        }
    }

    private int getAncestralRecordVersionValue(BackupDataInput data) throws IOException {
        int ancestralRecordVersionValue = UNDEFINED_ANCESTRAL_RECORD_VERSION;
        if (data.readNextHeader()) {
            String key = data.getKey();
            int dataSize = data.getDataSize();

            if (DEBUG) Slog.v(TAG, "   got key=" + key + " dataSize=" + dataSize);

            if (ANCESTRAL_RECORD_KEY.equals(key)) {
                // generic setup to parse any entity data
                byte[] inputBytes = new byte[dataSize];
                data.readEntityData(inputBytes, 0, dataSize);
                ByteArrayInputStream inputBuffer = new ByteArrayInputStream(inputBytes);
                DataInputStream inputBufferStream = new DataInputStream(inputBuffer);

                ancestralRecordVersionValue = inputBufferStream.readInt();
            }
        }
        return ancestralRecordVersionValue;
    }

    private RestoreDataConsumer getRestoreDataConsumer(int ancestralRecordVersion) {
        switch (ancestralRecordVersion) {
            case UNDEFINED_ANCESTRAL_RECORD_VERSION:
                return new LegacyRestoreDataConsumer();
            case 1:
                return new AncestralVersion1RestoreDataConsumer();
            default:
                Slog.e(TAG, "Unrecognized ANCESTRAL_RECORD_VERSION: " + ancestralRecordVersion);
                return null;
        }
    }

    private static void writeSignatureHashArray(DataOutputStream out, ArrayList<byte[]> hashes)
            throws IOException {
        // the number of entries in the array
        out.writeInt(hashes.size());

        // the hash arrays themselves as length + contents
        for (byte[] buffer : hashes) {
            out.writeInt(buffer.length);
            out.write(buffer);
        }
    }

    private static ArrayList<byte[]> readSignatureHashArray(DataInputStream in) {
        try {
            int num;
            try {
                num = in.readInt();
            } catch (EOFException e) {
                // clean termination
                Slog.w(TAG, "Read empty signature block");
                return null;
            }

            if (DEBUG) Slog.v(TAG, " ... unflatten read " + num);

            // Sensical?
            if (num > 20) {
                Slog.e(TAG, "Suspiciously large sig count in restore data; aborting");
                throw new IllegalStateException("Bad restore state");
            }

            // This could be a "legacy" block of actual signatures rather than their hashes.
            // If this is the case, convert them now.  We judge based on the payload size:
            // if the blocks are all 256 bits (32 bytes) then we take them to be SHA-256 hashes;
            // otherwise we take them to be Signatures.
            boolean nonHashFound = false;
            ArrayList<byte[]> sigs = new ArrayList<byte[]>(num);
            for (int i = 0; i < num; i++) {
                int len = in.readInt();
                byte[] readHash = new byte[len];
                in.read(readHash);
                sigs.add(readHash);
                if (len != 32) {
                    nonHashFound = true;
                }
            }

            if (nonHashFound) {
                // Replace with the hashes.
                sigs = BackupUtils.hashSignatureArray(sigs);
            }

            return sigs;
        } catch (IOException e) {
            Slog.e(TAG, "Unable to read signatures");
            return null;
        }
    }

    // Util: parse out an existing state file into a usable structure
    private void parseStateFile(ParcelFileDescriptor stateFile) {
        mExisting.clear();
        mStateVersions.clear();
        mStoredSdkVersion = 0;
        mStoredIncrementalVersion = null;
        mStoredHomeComponent = null;
        mStoredHomeVersion = 0;
        mStoredHomeSigHashes = null;

        // The state file is just the list of app names we have stored signatures for
        // with the exception of the metadata block, to which is also appended the
        // version numbers corresponding with the last time we wrote this PM block.
        // If they mismatch the current system, we'll re-store the metadata key.
        FileInputStream instream = new FileInputStream(stateFile.getFileDescriptor());
        BufferedInputStream inbuffer = new BufferedInputStream(instream);
        DataInputStream in = new DataInputStream(inbuffer);

        try {
            boolean ignoreExisting = false;
            String pkg = in.readUTF();

            // Validate the state file version is sensical to us
            if (pkg.equals(STATE_FILE_HEADER)) {
                int stateVersion = in.readInt();
                if (stateVersion > STATE_FILE_VERSION) {
                    Slog.w(
                            TAG,
                            "Unsupported state file version "
                                    + stateVersion
                                    + ", redoing from start");
                    return;
                }
                pkg = in.readUTF();
            } else {
                // This is an older version of the state file in which the lead element
                // is not a STATE_FILE_VERSION string.  If that's the case, we want to
                // make sure to write our full backup dataset when given an opportunity.
                // We trigger that by simply not marking the restored package metadata
                // as known-to-exist-in-archive.
                Slog.i(TAG, "Older version of saved state - rewriting");
                ignoreExisting = true;
            }

            // First comes the preferred home app data, if any, headed by the DEFAULT_HOME_KEY tag
            if (pkg.equals(DEFAULT_HOME_KEY)) {
                // flattened component name, version, signature of the home app
                mStoredHomeComponent = ComponentName.unflattenFromString(in.readUTF());
                mStoredHomeVersion = in.readLong();
                mStoredHomeSigHashes = readSignatureHashArray(in);

                pkg = in.readUTF(); // set up for the next block of state
            } else {
                // else no preferred home app on the ancestral device - fall through to the rest
            }

            // After (possible) home app data comes the global metadata block
            if (pkg.equals(GLOBAL_METADATA_KEY)) {
                mStoredSdkVersion = in.readInt();
                mStoredIncrementalVersion = in.readUTF();
                if (!ignoreExisting) {
                    mExisting.add(GLOBAL_METADATA_KEY);
                }
            } else {
                Slog.e(TAG, "No global metadata in state file!");
                return;
            }

            // The global metadata was last; now read all the apps
            while (true) {
                pkg = in.readUTF();
                int versionCodeInt = in.readInt();
                long versionCode;
                if (versionCodeInt == Integer.MIN_VALUE) {
                    versionCode = in.readLong();
                } else {
                    versionCode = versionCodeInt;
                }

                if (!ignoreExisting) {
                    mExisting.add(pkg);
                }
                mStateVersions.put(pkg, new Metadata(versionCode, null));
            }
        } catch (EOFException eof) {
            // safe; we're done
        } catch (IOException e) {
            // whoops, bad state file.  abort.
            Slog.e(TAG, "Unable to read Package Manager state file: " + e);
        }
    }

    private ComponentName getPreferredHomeComponent() {
        return mPackageManager.getHomeActivities(new ArrayList<ResolveInfo>());
    }

    // Util: write out our new backup state file
    @VisibleForTesting
    static void writeStateFile(List<PackageInfo> pkgs, ParcelFileDescriptor stateFile) {
        FileOutputStream outstream = new FileOutputStream(stateFile.getFileDescriptor());
        BufferedOutputStream outbuf = new BufferedOutputStream(outstream);
        DataOutputStream out = new DataOutputStream(outbuf);

        // by the time we get here we know we've done all our backing up
        try {
            // state file version header
            out.writeUTF(STATE_FILE_HEADER);
            out.writeInt(STATE_FILE_VERSION);

            // Conclude with the metadata block
            out.writeUTF(GLOBAL_METADATA_KEY);
            out.writeInt(Build.VERSION.SDK_INT);
            out.writeUTF(Build.VERSION.INCREMENTAL);

            // now write all the app names + versions
            for (PackageInfo pkg : pkgs) {
                out.writeUTF(pkg.packageName);
                if (pkg.versionCodeMajor != 0) {
                    out.writeInt(Integer.MIN_VALUE);
                    out.writeLong(pkg.getLongVersionCode());
                } else {
                    out.writeInt(pkg.versionCode);
                }
            }

            out.flush();
        } catch (IOException e) {
            Slog.e(TAG, "Unable to write package manager state file!");
        }
    }

    interface RestoreDataConsumer {
        void consumeRestoreData(BackupDataInput data) throws IOException;
    }

    private class LegacyRestoreDataConsumer implements RestoreDataConsumer {

        public void consumeRestoreData(BackupDataInput data) throws IOException {
            List<ApplicationInfo> restoredApps = new ArrayList<ApplicationInfo>();
            HashMap<String, Metadata> sigMap = new HashMap<String, Metadata>();
            int storedSystemVersion = -1;

            if (DEBUG) Slog.i(TAG, "Using LegacyRestoreDataConsumer");
            // we already have the first header read and "cached", since ANCESTRAL_RECORD_KEY
            // was missing
            while (true) {
                String key = data.getKey();
                int dataSize = data.getDataSize();

                if (DEBUG) Slog.v(TAG, "   got key=" + key + " dataSize=" + dataSize);

                // generic setup to parse any entity data
                byte[] inputBytes = new byte[dataSize];
                data.readEntityData(inputBytes, 0, dataSize);
                ByteArrayInputStream inputBuffer = new ByteArrayInputStream(inputBytes);
                DataInputStream inputBufferStream = new DataInputStream(inputBuffer);

                if (key.equals(GLOBAL_METADATA_KEY)) {
                    int storedSdkVersion = inputBufferStream.readInt();
                    if (DEBUG) Slog.v(TAG, "   storedSystemVersion = " + storedSystemVersion);
                    mStoredSdkVersion = storedSdkVersion;
                    mStoredIncrementalVersion = inputBufferStream.readUTF();
                    mHasMetadata = true;
                    if (DEBUG) {
                        Slog.i(
                                TAG,
                                "Restore set version "
                                        + storedSystemVersion
                                        + " is compatible with OS version "
                                        + Build.VERSION.SDK_INT
                                        + " ("
                                        + mStoredIncrementalVersion
                                        + " vs "
                                        + Build.VERSION.INCREMENTAL
                                        + ")");
                    }
                } else if (key.equals(DEFAULT_HOME_KEY)) {
                    String cn = inputBufferStream.readUTF();
                    mRestoredHome = ComponentName.unflattenFromString(cn);
                    mRestoredHomeVersion = inputBufferStream.readLong();
                    mRestoredHomeInstaller = inputBufferStream.readUTF();
                    mRestoredHomeSigHashes = readSignatureHashArray(inputBufferStream);
                    if (DEBUG) {
                        Slog.i(
                                TAG,
                                "   read preferred home app "
                                        + mRestoredHome
                                        + " version="
                                        + mRestoredHomeVersion
                                        + " installer="
                                        + mRestoredHomeInstaller
                                        + " sig="
                                        + mRestoredHomeSigHashes);
                    }
                } else {
                    // it's a file metadata record
                    int versionCodeInt = inputBufferStream.readInt();
                    long versionCode;
                    if (versionCodeInt == Integer.MIN_VALUE) {
                        versionCode = inputBufferStream.readLong();
                    } else {
                        versionCode = versionCodeInt;
                    }
                    ArrayList<byte[]> sigs = readSignatureHashArray(inputBufferStream);
                    if (DEBUG) {
                        Slog.i(
                                TAG,
                                "   read metadata for "
                                        + key
                                        + " dataSize="
                                        + dataSize
                                        + " versionCode="
                                        + versionCode
                                        + " sigs="
                                        + sigs);
                    }

                    if (sigs == null || sigs.size() == 0) {
                        Slog.w(
                                TAG,
                                "Not restoring package "
                                        + key
                                        + " since it appears to have no signatures.");
                        continue;
                    }

                    ApplicationInfo app = new ApplicationInfo();
                    app.packageName = key;
                    restoredApps.add(app);
                    sigMap.put(key, new Metadata(versionCode, sigs));
                }

                boolean readNextHeader = data.readNextHeader();
                if (!readNextHeader) {
                    if (DEBUG) {
                        Slog.v(
                                TAG,
                                "LegacyRestoreDataConsumer:"
                                        + " we're done reading all the headers");
                    }
                    break;
                }
            }

            // On successful completion, cache the signature map for the Backup Manager to use
            mRestoredSignatures = sigMap;
        }
    }

    private class AncestralVersion1RestoreDataConsumer implements RestoreDataConsumer {

        public void consumeRestoreData(BackupDataInput data) throws IOException {
            List<ApplicationInfo> restoredApps = new ArrayList<ApplicationInfo>();
            HashMap<String, Metadata> sigMap = new HashMap<String, Metadata>();
            int storedSystemVersion = -1;

            if (DEBUG) Slog.i(TAG, "Using AncestralVersion1RestoreDataConsumer");
            while (data.readNextHeader()) {
                String key = data.getKey();
                int dataSize = data.getDataSize();

                if (DEBUG) Slog.v(TAG, "   got key=" + key + " dataSize=" + dataSize);

                // generic setup to parse any entity data
                byte[] inputBytes = new byte[dataSize];
                data.readEntityData(inputBytes, 0, dataSize);
                ByteArrayInputStream inputBuffer = new ByteArrayInputStream(inputBytes);
                DataInputStream inputBufferStream = new DataInputStream(inputBuffer);

                if (key.equals(GLOBAL_METADATA_KEY)) {
                    int storedSdkVersion = inputBufferStream.readInt();
                    if (DEBUG) Slog.v(TAG, "   storedSystemVersion = " + storedSystemVersion);
                    mStoredSdkVersion = storedSdkVersion;
                    mStoredIncrementalVersion = inputBufferStream.readUTF();
                    mHasMetadata = true;
                    if (DEBUG) {
                        Slog.i(
                                TAG,
                                "Restore set version "
                                        + storedSystemVersion
                                        + " is compatible with OS version "
                                        + Build.VERSION.SDK_INT
                                        + " ("
                                        + mStoredIncrementalVersion
                                        + " vs "
                                        + Build.VERSION.INCREMENTAL
                                        + ")");
                    }
                } else if (key.equals(DEFAULT_HOME_KEY)) {
                    // Default home app data is no longer backed up by this agent. This code is
                    // kept to handle restore of old backups that still contain home app data.
                    String cn = inputBufferStream.readUTF();
                    mRestoredHome = ComponentName.unflattenFromString(cn);
                    mRestoredHomeVersion = inputBufferStream.readLong();
                    mRestoredHomeInstaller = inputBufferStream.readUTF();
                    mRestoredHomeSigHashes = readSignatureHashArray(inputBufferStream);
                    if (DEBUG) {
                        Slog.i(
                                TAG,
                                "   read preferred home app "
                                        + mRestoredHome
                                        + " version="
                                        + mRestoredHomeVersion
                                        + " installer="
                                        + mRestoredHomeInstaller
                                        + " sig="
                                        + mRestoredHomeSigHashes);
                    }
                } else {
                    // it's a file metadata record
                    int versionCodeInt = inputBufferStream.readInt();
                    long versionCode;
                    if (versionCodeInt == Integer.MIN_VALUE) {
                        versionCode = inputBufferStream.readLong();
                    } else {
                        versionCode = versionCodeInt;
                    }
                    ArrayList<byte[]> sigs = readSignatureHashArray(inputBufferStream);
                    if (DEBUG) {
                        Slog.i(
                                TAG,
                                "   read metadata for "
                                        + key
                                        + " dataSize="
                                        + dataSize
                                        + " versionCode="
                                        + versionCode
                                        + " sigs="
                                        + sigs);
                    }

                    if (sigs == null || sigs.size() == 0) {
                        Slog.w(
                                TAG,
                                "Not restoring package "
                                        + key
                                        + " since it appears to have no signatures.");
                        continue;
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
    }
}
