/*
 * Copyright (C) 2011 The Android Open Source Project
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

/**
 * Global constant definitions et cetera related to the full-backup-to-fd
 * binary format.
 *
 * @hide
 */
public class FullBackup {
    public static String APK_TREE_TOKEN = "a";
    public static String OBB_TREE_TOKEN = "obb";
    public static String ROOT_TREE_TOKEN = "r";
    public static String DATA_TREE_TOKEN = "f";
    public static String DATABASE_TREE_TOKEN = "db";
    public static String SHAREDPREFS_TREE_TOKEN = "sp";
    public static String CACHE_TREE_TOKEN = "c";

    public static String FULL_BACKUP_INTENT_ACTION = "fullback";
    public static String FULL_RESTORE_INTENT_ACTION = "fullrest";
    public static String CONF_TOKEN_INTENT_EXTRA = "conftoken";

    static public native int backupToTar(String packageName, String domain,
            String linkdomain, String rootpath, String path, BackupDataOutput output);
}
