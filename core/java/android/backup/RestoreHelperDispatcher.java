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
import java.util.HashMap;
import java.util.Map;

/** @hide */
public class RestoreHelperDispatcher {
    private static final String TAG = "RestoreHelperDispatcher";

    HashMap<String,RestoreHelper> mHelpers = new HashMap<String,RestoreHelper>();

    public void addHelper(String keyPrefix, RestoreHelper helper) {
        mHelpers.put(keyPrefix, helper);
    }

    public void dispatch(BackupDataInput input, ParcelFileDescriptor newState) throws IOException {
        boolean alreadyComplained = false;

        BackupDataInputStream stream = new BackupDataInputStream(input);
        while (input.readNextHeader()) {

            String rawKey = input.getKey();
            int pos = rawKey.indexOf(':');
            if (pos > 0) {
                String prefix = rawKey.substring(0, pos);
                RestoreHelper helper = mHelpers.get(prefix);
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

        if (mHelpers.size() > 1) {
            throw new RuntimeException("RestoreHelperDispatcher won't get your your"
                    + " data in the right order yet.");
        }
        
        // Write out the state files
        for (RestoreHelper helper: mHelpers.values()) {
            // TODO: Write a header for the state
            helper.writeSnapshot(newState);
        }
    }
}

