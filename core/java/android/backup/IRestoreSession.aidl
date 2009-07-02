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

import android.backup.RestoreSet;
import android.backup.IRestoreObserver;

/**
 * Binder interface used by clients who wish to manage a restore operation.  Every
 * method in this interface requires the android.permission.BACKUP permission.
 *
 * {@hide}
 */
interface IRestoreSession {
    /**
     * Ask the current transport what the available restore sets are.
     *
     * @return A bundle containing two elements:  an int array under the key
     *   "tokens" whose entries are a transport-private identifier for each backup set;
     *   and a String array under the key "names" whose entries are the user-meaningful
     *   text corresponding to the backup sets at each index in the tokens array.
     */
    RestoreSet[] getAvailableRestoreSets();

    /**
     * Restore the given set onto the device, replacing the current data of any app
     * contained in the restore set with the data previously backed up.
     *
     * @param token The token from {@link getAvailableRestoreSets()} corresponding to
     *   the restore set that should be used.
     * @param observer If non-null, this binder points to an object that will receive
     *   progress callbacks during the restore operation.
     */
    int performRestore(long token, IRestoreObserver observer);

    /**
     * End this restore session.  After this method is called, the IRestoreSession binder
     * is no longer valid.
     */
    void endRestoreSession();
}
