/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * Callback class for receiving success or failure callbacks on selecting a backup transport. These
 * methods will all be called on your application's main thread.
 *
 * @hide
 */
oneway interface ISelectBackupTransportCallback {

    /**
     * Called when BackupManager has successfully bound to the requested transport.
     *
     * @param transportName Name of the selected transport. This is the String returned by
     *        {@link BackupTransport#name()}.
     */
    void onSuccess(String transportName);

    /**
     * Called when BackupManager fails to bind to the requested transport.
     *
     * @param reason Error code denoting reason for failure.
     */
    void onFailure(int reason);
}
