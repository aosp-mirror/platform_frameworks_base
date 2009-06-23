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

package android.backup;

import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.IOException;
import java.util.TreeMap;
import java.util.Map;

/** @hide */
public class BackupHelperDispatcher {
    private static final String TAG = "BackupHelperDispatcher";

    TreeMap<String,BackupHelper> mHelpers = new TreeMap<String,BackupHelper>();
    
    public BackupHelperDispatcher() {
    }

    public void addHelper(String keyPrefix, BackupHelper helper) {
        mHelpers.put(keyPrefix, helper);
    }

    /** TODO: Make this save and restore the key prefix. */
    public void performBackup(ParcelFileDescriptor oldState, BackupDataOutput data,
             ParcelFileDescriptor newState) {
        // Write out the state files -- mHelpers is a TreeMap, so the order is well defined.
        for (Map.Entry<String,BackupHelper> entry: mHelpers.entrySet()) {
            data.setKeyPrefix(entry.getKey());
            entry.getValue().performBackup(oldState, data, newState);
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
            helper.writeRestoreSnapshot(newState);
        }
    }
}

