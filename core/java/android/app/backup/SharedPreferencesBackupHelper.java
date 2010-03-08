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

import android.content.Context;
import android.content.SharedPreferences;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;

/**
 * A helper class which can be used in conjunction with
 * {@link android.app.backup.BackupHelperAgent} to manage the backup of
 * {@link android.content.SharedPreferences}. Whenever backup is performed it
 * will back up all named shared preferences which have changed since the last
 * backup.
 * <p>
 * STOPSHIP: document!
 */
public class SharedPreferencesBackupHelper extends FileBackupHelperBase implements BackupHelper {
    private static final String TAG = "SharedPreferencesBackupHelper";
    private static final boolean DEBUG = false;

    private Context mContext;
    private String[] mPrefGroups;

    /**
     * Construct a helper for backing up and restoring the
     * {@link android.content.SharedPreferences} under the given names.
     *
     * @param context
     * @param prefGroups
     */
    public SharedPreferencesBackupHelper(Context context, String... prefGroups) {
        super(context);

        mContext = context;
        mPrefGroups = prefGroups;
    }

    /**
     * Backs up the configured SharedPreferences groups
     */
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

    /**
     * Restores one entity from the restore data stream to its proper shared
     * preferences file store.
     */
    public void restoreEntity(BackupDataInputStream data) {
        Context context = mContext;
        
        String key = data.getKey();
        if (DEBUG) Log.d(TAG, "got entity '" + key + "' size=" + data.size());

        if (isKeyInList(key, mPrefGroups)) {
            File f = context.getSharedPrefsFile(key).getAbsoluteFile();
            writeFile(f, data);
        }
    }
}

