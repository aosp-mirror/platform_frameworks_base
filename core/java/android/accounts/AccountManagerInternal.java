/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.accounts;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.os.RemoteCallback;

/**
 * Account manager local system service interface.
 *
 * @hide Only for use within the system server.
 */
public abstract class AccountManagerInternal {

    /**
     * Listener for explicit UID account access grant changes.
     */
    public interface OnAppPermissionChangeListener {

        /**
         * Called when the explicit grant state for a given UID to
         * access an account changes.
         *
         * @param account The account
         * @param uid The UID for which the grant changed
         */
        public void onAppPermissionChanged(Account account, int uid);
    }

    /**
     * Requests that a given package is given access to an account.
     * The provided callback will be invoked with a {@link android.os.Bundle}
     * containing the result which will be a boolean value mapped to the
     * {@link AccountManager#KEY_BOOLEAN_RESULT} key.
     *
     * @param account The account for which to request.
     * @param packageName The package name for which to request.
     * @param userId Concrete user id for which to request.
     * @param callback A callback for receiving the result.
     */
    public abstract void requestAccountAccess(@NonNull Account account,
            @NonNull String packageName, @IntRange(from = 0) int userId,
            @NonNull RemoteCallback callback);

    /**
     * Check whether the given UID has access to the account.
     *
     * @param account The account
     * @param uid The UID
     * @return Whether the UID can access the account
     */
    public abstract boolean hasAccountAccess(@NonNull Account account, @IntRange(from = 0) int uid);

    /**
     * Adds a listener for explicit UID account access grant changes.
     *
     * @param listener The listener
     */
    public abstract void addOnAppPermissionChangeListener(
            @NonNull OnAppPermissionChangeListener listener);

    /**
     * Backups the account access permissions.
     * @param userId The user for which to backup.
     * @return The backup data.
     */
    public abstract byte[] backupAccountAccessPermissions(int userId);

    /**
     * Restores the account access permissions.
     * @param data The restore data.
     * @param userId The user for which to restore.
     */
    public abstract void restoreAccountAccessPermissions(byte[] data, int userId);
}
