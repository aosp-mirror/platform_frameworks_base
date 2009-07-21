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

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.io.FileDescriptor;

/** @hide */
public class SharedPreferencesBackupHelper extends FileBackupHelperBase implements BackupHelper {
    private static final String TAG = "SharedPreferencesBackupHelper";

    private Context mContext;
    private String[] mPrefGroups;

    public SharedPreferencesBackupHelper(Context context, String... prefGroups) {
        super(context);

        mContext = context;
        mPrefGroups = prefGroups;
    }

    public void performBackup(ParcelFileDescriptor oldState, BackupDataOutput data,
            ParcelFileDescriptor newState) {
        Context context = mContext;
        
        // make filenames for the prefGroups
        String[] prefGroups = mPrefGroups;
        final int N = prefGroups.length;
        String[] files = new String[N];
        for (int i=0; i<N; i++) {
            files[i] = context.getSharedPrefsFile(prefGroups[i]).getAbsolutePath();
        }

        // go
        performBackup_checked(oldState, data, newState, files, prefGroups);
    }

    public void restoreEntity(BackupDataInputStream data) {
        Context context = mContext;
        
        // TODO: turn this off before ship
        Log.d(TAG, "got entity '" + data.getKey() + "' size=" + data.size());
        String key = data.getKey();
        if (isKeyInList(key, mPrefGroups)) {
            File f = context.getSharedPrefsFile(key).getAbsoluteFile();
            writeFile(f, data);
        }
    }
}

