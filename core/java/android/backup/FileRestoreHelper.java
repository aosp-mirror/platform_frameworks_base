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
import android.util.Log;

import java.io.File;

/** @hide */
public class FileRestoreHelper extends RestoreHelperBase implements RestoreHelper {
    private static final String TAG = "FileRestoreHelper";

    File mFilesDir;

    public FileRestoreHelper(Context context) {
        super(context);
        mFilesDir = context.getFilesDir();
    }

    public void restoreEntity(BackupDataInputStream data) {
        Log.d(TAG, "got entity '" + data.getKey() + "' size=" + data.size()); // TODO: turn this off before ship
        File f = new File(mFilesDir, data.getKey());
        writeFile(f, data);
    }
}

