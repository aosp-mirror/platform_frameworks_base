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
 * Observer of a full backup or restore process.  The observer is told "interesting"
 * information about an ongoing full backup or restore action.
 *
 * {@hide}
 */

oneway interface IFullBackupRestoreObserver {
    /**
     * Notification: a full backup operation has begun.
     */
    void onStartBackup();

    /**
     * Notification: the system has begun backing up the given package.
     *
     * @param name The name of the application being saved.  This will typically be a
     *     user-meaningful name such as "Browser" rather than a package name such as
     *     "com.android.browser", though this is not guaranteed.
     */
    void onBackupPackage(String name);

    /**
     * Notification: the full backup operation has ended.
     */
    void onEndBackup();

    /**
     * Notification: a restore-from-full-backup operation has begun.
     */
    void onStartRestore();

    /**
     * Notification: the system has begun restore of the given package.
     *
     * @param name The name of the application being saved.  This will typically be a
     *     user-meaningful name such as "Browser" rather than a package name such as
     *     "com.android.browser", though this is not guaranteed.
     */
    void onRestorePackage(String name);

    /**
     * Notification: the restore-from-full-backup operation has ended.
     */
    void onEndRestore();

    /**
     * The user's window of opportunity for confirming the operation has timed out.
     */
    void onTimeout();
}
