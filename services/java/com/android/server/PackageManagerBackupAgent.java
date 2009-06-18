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

// !!!TODO: take this out
import java.util.zip.CRC32;

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

    private List<ApplicationInfo> mAllApps;
    private PackageManager mPackageManager;
    private HashMap<String, Signature[]> mRestoredSignatures;

    // We're constructed with the set of applications that are participating
    // in backup.  This set changes as apps are installed & removed.
    PackageManagerBackupAgent(PackageManager packageMgr, List<ApplicationInfo> apps) {
        mPackageManager = packageMgr;
        mAllApps = apps;
        mRestoredSignatures = null;
    }

    public Signature[] getRestoredSignatures(String packageName) {
        if (mRestoredSignatures == null) {
            return null;
        }

        return mRestoredSignatures.get(packageName);
    }
    
    // The backed up data is the signature block for each app, keyed by
    // the package name.
    public void onBackup(ParcelFileDescriptor oldState, BackupDataOutput data,
            ParcelFileDescriptor newState) {
        HashSet<String> existing = parseStateFile(oldState);

        // For each app we have on device, see if we've backed it up yet.  If not,
        // write its signature block to the output, keyed on the package name.
        for (ApplicationInfo app : mAllApps) {
            String packName = app.packageName;
            if (!existing.contains(packName)) {
                // We haven't stored this app's signatures yet, so we do that now
                try {
                    PackageInfo info = mPackageManager.getPackageInfo(packName,
                            PackageManager.GET_SIGNATURES);
                    // build a byte array out of the signature list
                    byte[] sigs = flattenSignatureArray(info.signatures);
//                  !!! TODO: take out this debugging
                    if (DEBUG) {
                        CRC32 crc = new CRC32();
                        crc.update(sigs);
                        Log.i(TAG, "+ flat sig array for " + packName + " : "
                                + crc.getValue());
                    }
                    data.writeEntityHeader(packName, sigs.length);
                    data.writeEntityData(sigs, sigs.length);
                } catch (NameNotFoundException e) {
                    // Weird; we just found it, and now are told it doesn't exist.
                    // Treat it as having been removed from the device.
                    existing.add(packName);
                } catch (IOException e) {
                    // Real error writing data
                    Log.e(TAG, "Unable to write package backup data file!");
                    return;
                }
            } else {
                // We've already backed up this app.  Remove it from the set so
                // we can tell at the end what has disappeared from the device.
                if (!existing.remove(packName)) {
                    Log.d(TAG, "*** failed to remove " + packName + " from package set!");
                }
            }
        }

        // At this point, the only entries in 'existing' are apps that were
        // mentioned in the saved state file, but appear to no longer be present
        // on the device.  Write a deletion entity for them.
        for (String app : existing) {
            try {
                data.writeEntityHeader(app, -1);
            } catch (IOException e) {
                Log.e(TAG, "Unable to write package deletions!");
                return;
            }
        }

        // Finally, write the new state blob -- just the list of all apps we handled
        writeStateFile(mAllApps, newState);
    }

    // "Restore" here is a misnomer.  What we're really doing is reading back the
    // set of app signatures associated with each backed-up app in this restore
    // image.  We'll use those later to determine what we can legitimately restore.
    public void onRestore(BackupDataInput data, ParcelFileDescriptor newState)
            throws IOException {
        List<ApplicationInfo> restoredApps = new ArrayList<ApplicationInfo>();
        HashMap<String, Signature[]> sigMap = new HashMap<String, Signature[]>();

        while (data.readNextHeader()) {
            int dataSize = data.getDataSize();
            byte[] buf = new byte[dataSize];
            data.readEntityData(buf, 0, dataSize);

            Signature[] sigs = unflattenSignatureArray(buf);
            String pkg = data.getKey();
//          !!! TODO: take out this debugging
            if (DEBUG) {
                CRC32 crc = new CRC32();
                crc.update(buf);
                Log.i(TAG, "- unflat sig array for " + pkg + " : "
                        + crc.getValue());
            }

            ApplicationInfo app = new ApplicationInfo();
            app.packageName = pkg;
            restoredApps.add(app);
            sigMap.put(pkg, sigs);
        }

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

    private Signature[] unflattenSignatureArray(byte[] buffer) {
        ByteArrayInputStream inBufStream = new ByteArrayInputStream(buffer);
        DataInputStream in = new DataInputStream(inBufStream);
        Signature[] sigs = null;

        try {
            int num = in.readInt();
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
    private HashSet<String> parseStateFile(ParcelFileDescriptor stateFile) {
        HashSet<String> set = new HashSet<String>();
        // The state file is just the list of app names we have stored signatures for
        FileInputStream instream = new FileInputStream(stateFile.getFileDescriptor());
        DataInputStream in = new DataInputStream(instream);

        int bufSize = 256;
        byte[] buf = new byte[bufSize];
        try {
            int nameSize = in.readInt();
            if (bufSize < nameSize) {
                bufSize = nameSize + 32;
                buf = new byte[bufSize];
            }
            in.read(buf, 0, nameSize);
            String pkg = new String(buf, 0, nameSize);
            set.add(pkg);
        } catch (EOFException eof) {
            // safe; we're done
        } catch (IOException e) {
            // whoops, bad state file.  abort.
            Log.e(TAG, "Unable to read Package Manager state file");
            return null;
        }
        return set;
    }

    // Util: write a set of names into a new state file
    private void writeStateFile(List<ApplicationInfo> apps, ParcelFileDescriptor stateFile) {
        FileOutputStream outstream = new FileOutputStream(stateFile.getFileDescriptor());
        DataOutputStream out = new DataOutputStream(outstream);

        for (ApplicationInfo app : apps) {
            try {
                byte[] pkgNameBuf = app.packageName.getBytes();
                out.writeInt(pkgNameBuf.length);
                out.write(pkgNameBuf);
            } catch (IOException e) {
                Log.e(TAG, "Unable to write package manager state file!");
                return;
            }
        }
    }
}
