/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.backuptest;

import android.app.backup.BackupAgentHelper;
import android.app.backup.FileBackupHelper;
import android.app.backup.SharedPreferencesBackupHelper;

public class BackupTestAgent extends BackupAgentHelper
{
    public void onCreate() {
        addHelper("data_files", new FileBackupHelper(this, BackupTestActivity.FILE_NAME));
        addHelper("more_data_files", new FileBackupHelper(this, "another_file.txt", "3.txt",
                    "empty.txt"));
        addHelper("shared_prefs", new SharedPreferencesBackupHelper(this, "settings", "raw"));
    }
}

