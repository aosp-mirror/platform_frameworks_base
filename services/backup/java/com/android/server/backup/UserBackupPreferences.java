/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Manages the persisted backup preferences per user. */
public class UserBackupPreferences {
    private static final String PREFERENCES_FILE = "backup_preferences";

    private final SharedPreferences mPreferences;
    private final SharedPreferences.Editor mEditor;

    UserBackupPreferences(Context conext, File storageDir) {
        File excludedKeysFile = new File(storageDir, PREFERENCES_FILE);
        mPreferences = conext.getSharedPreferences(excludedKeysFile, Context.MODE_PRIVATE);
        mEditor = mPreferences.edit();
    }

    void addExcludedKeys(String packageName, List<String> keys) {
        Set<String> existingKeys =
                new HashSet<>(mPreferences.getStringSet(packageName, Collections.emptySet()));
        existingKeys.addAll(keys);
        mEditor.putStringSet(packageName, existingKeys);
        mEditor.commit();
    }

    Set<String> getExcludedRestoreKeysForPackage(String packageName) {
        return mPreferences.getStringSet(packageName, Collections.emptySet());
    }
}
