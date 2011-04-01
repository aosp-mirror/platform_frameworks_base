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

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import libcore.io.Libcore;
import libcore.io.ErrnoException;
import libcore.io.OsConstants;
import libcore.io.StructStat;

import java.io.File;
import java.util.HashSet;
import java.util.LinkedList;

/**
 * Backs up an application's entire /data/data/&lt;package&gt;/... file system.  This
 * class is used by the desktop full backup mechanism and is not intended for direct
 * use by applications.
 * 
 * {@hide}
 */

public class FullBackupAgent extends BackupAgent {
    // !!! TODO: turn off debugging
    private static final String TAG = "FullBackupAgent";
    private static final boolean DEBUG = true;

    PackageManager mPm;

    private String mMainDir;
    private String mFilesDir;
    private String mDatabaseDir;
    private String mSharedPrefsDir;
    private String mCacheDir;
    private String mLibDir;

    @Override
    public void onCreate() {
        mPm = getPackageManager();
        try {
            ApplicationInfo appInfo = mPm.getApplicationInfo(getPackageName(), 0);
            mMainDir = new File(appInfo.dataDir).getAbsolutePath();
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Unable to find package " + getPackageName());
            throw new RuntimeException(e);
        }

        mFilesDir = getFilesDir().getAbsolutePath();
        mDatabaseDir = getDatabasePath("foo").getParentFile().getAbsolutePath();
        mSharedPrefsDir = getSharedPrefsFile("foo").getParentFile().getAbsolutePath();
        mCacheDir = getCacheDir().getAbsolutePath();

        ApplicationInfo app = getApplicationInfo();
        mLibDir = (app.nativeLibraryDir != null)
                ? new File(app.nativeLibraryDir).getAbsolutePath()
                : null;
    }

    @Override
    public void onBackup(ParcelFileDescriptor oldState, BackupDataOutput data,
            ParcelFileDescriptor newState) {
        // Filters, the scan queue, and the set of resulting entities
        HashSet<String> filterSet = new HashSet<String>();

        // Okay, start with the app's root tree, but exclude all of the canonical subdirs
        if (mLibDir != null) {
            filterSet.add(mLibDir);
        }
        filterSet.add(mCacheDir);
        filterSet.add(mDatabaseDir);
        filterSet.add(mSharedPrefsDir);
        filterSet.add(mFilesDir);
        processTree(FullBackup.ROOT_TREE_TOKEN, mMainDir, filterSet, data);

        // Now do the same for the files dir, db dir, and shared prefs dir
        filterSet.add(mMainDir);
        filterSet.remove(mFilesDir);
        processTree(FullBackup.DATA_TREE_TOKEN, mFilesDir, filterSet, data);

        filterSet.add(mFilesDir);
        filterSet.remove(mDatabaseDir);
        processTree(FullBackup.DATABASE_TREE_TOKEN, mDatabaseDir, filterSet, data);

        filterSet.add(mDatabaseDir);
        filterSet.remove(mSharedPrefsDir);
        processTree(FullBackup.SHAREDPREFS_TREE_TOKEN, mSharedPrefsDir, filterSet, data);
    }

    private void processTree(String domain, String rootPath,
            HashSet<String> excludes, BackupDataOutput data) {
        // Scan the dir tree (if it actually exists) and process each entry we find
        File rootFile = new File(rootPath);
        if (rootFile.exists()) {
            LinkedList<File> scanQueue = new LinkedList<File>();
            scanQueue.add(rootFile);

            while (scanQueue.size() > 0) {
                File file = scanQueue.remove(0);
                String filePath = file.getAbsolutePath();

                // prune this subtree?
                if (excludes.contains(filePath)) {
                    continue;
                }

                // If it's a directory, enqueue its contents for scanning.
                try {
                    StructStat stat = Libcore.os.lstat(filePath);
                    if (OsConstants.S_ISLNK(stat.st_mode)) {
                        if (DEBUG) Log.i(TAG, "Symlink (skipping)!: " + file);
                        continue;
                    } else if (OsConstants.S_ISDIR(stat.st_mode)) {
                        File[] contents = file.listFiles();
                        if (contents != null) {
                            for (File entry : contents) {
                                scanQueue.add(0, entry);
                            }
                        }
                    }
                } catch (ErrnoException e) {
                    if (DEBUG) Log.w(TAG, "Error scanning file " + file + " : " + e);
                    continue;
                }

                // Finally, back this file up before proceeding
                FullBackup.backupToTar(getPackageName(), domain, null, rootPath, filePath, data);
            }
        }
    }

    @Override
    void onSaveApk(BackupDataOutput data) {
        ApplicationInfo app = getApplicationInfo();
        if (DEBUG) Log.i(TAG, "APK flags: system=" + ((app.flags & ApplicationInfo.FLAG_SYSTEM) != 0)
                + " updated=" + ((app.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)
                + " locked=" + ((app.flags & ApplicationInfo.FLAG_FORWARD_LOCK) != 0) );
        if (DEBUG) Log.i(TAG, "codepath: " + getPackageCodePath());

        // Forward-locked apps, system-bundled .apks, etc are filtered out before we get here
        final String pkgName = getPackageName();
        final String apkDir = new File(getPackageCodePath()).getParent();
        FullBackup.backupToTar(pkgName, FullBackup.APK_TREE_TOKEN, null,
                apkDir, getPackageCodePath(), data);

        // Save associated .obb content if it exists and we did save the apk
        // check for .obb and save those too
        final File obbDir = Environment.getExternalStorageAppObbDirectory(pkgName);
        if (obbDir != null) {
            if (DEBUG) Log.i(TAG, "obb dir: " + obbDir.getAbsolutePath());
            File[] obbFiles = obbDir.listFiles();
            if (obbFiles != null) {
                final String obbDirName = obbDir.getAbsolutePath();
                for (File obb : obbFiles) {
                    FullBackup.backupToTar(pkgName, FullBackup.OBB_TREE_TOKEN, null,
                            obbDirName, obb.getAbsolutePath(), data);
                }
            }
        }
    }

    @Override
    public void onRestore(BackupDataInput data, int appVersionCode, ParcelFileDescriptor newState) {
    }
}
