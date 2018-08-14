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

package android.app.backup;

import android.annotation.UnsupportedAppUsage;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;

/** @hide */
public class BackupHelperDispatcher {
    private static final String TAG = "BackupHelperDispatcher";

    private static class Header {
        @UnsupportedAppUsage
        int chunkSize; // not including the header
        @UnsupportedAppUsage
        String keyPrefix;
    }

    TreeMap<String,BackupHelper> mHelpers = new TreeMap<String,BackupHelper>();
    
    public BackupHelperDispatcher() {
    }

    public void addHelper(String keyPrefix, BackupHelper helper) {
        mHelpers.put(keyPrefix, helper);
    }

    public void performBackup(ParcelFileDescriptor oldState, BackupDataOutput data,
             ParcelFileDescriptor newState) throws IOException {
        // First, do the helpers that we've already done, since they're already in the state
        // file.
        int err;
        Header header = new Header();
        TreeMap<String,BackupHelper> helpers = (TreeMap<String,BackupHelper>)mHelpers.clone();
        FileDescriptor oldStateFD = null;

        if (oldState != null) {
            oldStateFD = oldState.getFileDescriptor();
            while ((err = readHeader_native(header, oldStateFD)) >= 0) {
                if (err == 0) {
                    BackupHelper helper = helpers.get(header.keyPrefix);
                    Log.d(TAG, "handling existing helper '" + header.keyPrefix + "' " + helper);
                    if (helper != null) {
                        doOneBackup(oldState, data, newState, header, helper);
                        helpers.remove(header.keyPrefix);
                    } else {
                        skipChunk_native(oldStateFD, header.chunkSize);
                    }
                }
            }
        }

        // Then go through and do the rest that we haven't done.
        for (Map.Entry<String,BackupHelper> entry: helpers.entrySet()) {
            header.keyPrefix = entry.getKey();
            Log.d(TAG, "handling new helper '" + header.keyPrefix + "'");
            BackupHelper helper = entry.getValue();
            doOneBackup(oldState, data, newState, header, helper);
        }
    }

    private void doOneBackup(ParcelFileDescriptor oldState, BackupDataOutput data,
            ParcelFileDescriptor newState, Header header, BackupHelper helper) 
            throws IOException {
        int err;
        FileDescriptor newStateFD = newState.getFileDescriptor();

        // allocate space for the header in the file
        int pos = allocateHeader_native(header, newStateFD);
        if (pos < 0) {
            throw new IOException("allocateHeader_native failed (error " + pos + ")");
        }

        data.setKeyPrefix(header.keyPrefix);

        // do the backup
        helper.performBackup(oldState, data, newState);

        // fill in the header (seeking back to pos).  The file pointer will be returned to
        // where it was at the end of performBackup.  Header.chunkSize will not be filled in.
        err = writeHeader_native(header, newStateFD, pos);
        if (err != 0) {
            throw new IOException("writeHeader_native failed (error " + err + ")");
        }
    }

    public void performRestore(BackupDataInput input, int appVersionCode,
            ParcelFileDescriptor newState)
            throws IOException {
        boolean alreadyComplained = false;

        BackupDataInputStream stream = new BackupDataInputStream(input);
        while (input.readNextHeader()) {

            String rawKey = input.getKey();
            int pos = rawKey.indexOf(':');
            if (pos > 0) {
                String prefix = rawKey.substring(0, pos);
                BackupHelper helper = mHelpers.get(prefix);
                if (helper != null) {
                    stream.dataSize = input.getDataSize();
                    stream.key = rawKey.substring(pos+1);
                    helper.restoreEntity(stream);
                } else {
                    if (!alreadyComplained) {
                        Log.w(TAG, "Couldn't find helper for: '" + rawKey + "'");
                        alreadyComplained = true;
                    }
                }
            } else {
                if (!alreadyComplained) {
                    Log.w(TAG, "Entity with no prefix: '" + rawKey + "'");
                    alreadyComplained = true;
                }
            }
            input.skipEntityData(); // In case they didn't consume the data.
        }

        // Write out the state files -- mHelpers is a TreeMap, so the order is well defined.
        for (BackupHelper helper: mHelpers.values()) {
            helper.writeNewStateDescription(newState);
        }
    }

    private static native int readHeader_native(Header h, FileDescriptor fd);
    private static native int skipChunk_native(FileDescriptor fd, int bytesToSkip);

    private static native int allocateHeader_native(Header h, FileDescriptor fd);
    private static native int writeHeader_native(Header h, FileDescriptor fd, int pos);
}

