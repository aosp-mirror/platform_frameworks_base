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

package com.android.internal.backup;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;

/** {@hide} */
interface IBackupTransport {
/* STOPSHIP - don't ship with this comment in place
    Things the transport interface has to do:
    1. set up the connection to the destination
        - set up encryption
        - for Google cloud, log in using the user's gaia credential or whatever
        - for sd, spin off the backup transport and establish communication with it
    2. send each app's backup transaction
        - parse the data file for key/value pointers etc
        - send key/blobsize set to the Google cloud, get back quota ok/rejected response
        - sd/adb doesn't preflight; no per-app quota
        - app's entire change is essentially atomic
        - cloud transaction encrypts then sends each key/value pair separately; we already
          parsed the data when preflighting so we don't have to again here
        - sd target streams raw data into encryption envelope then to sd?
    3. shut down connection to destination
        - cloud: tear down connection etc
        - sd: close the file and shut down the writer proxy
*/
    /**
     * Establish a connection to the back-end data repository, if necessary.  If the transport
     * needs to initialize state that is not tied to individual applications' backup operations,
     * this is where it should be done.
     *
     * @return Zero on success; a nonzero error code on failure.
     */
    int startSession();

    /**
     * Send one application's data to the backup destination.
     *
     * @param packageName The identity of the application whose data is being backed up.
     * @param data The data stream that resulted from invoking the application's
     *        BackupService.doBackup() method.  This may be a pipe rather than a
     *        file on persistent media, so it may not be seekable.
     * @return Zero on success; a nonzero error code on failure.
     */
    int performBackup(String packageName, in ParcelFileDescriptor data);

    /**
     * Terminate the backup session, closing files, freeing memory, and cleaning up whatever
     * other state the transport required.
     *
     * @return Zero on success; a nonzero error code on failure.  Even on failure, the session
     *         is torn down and must be restarted if another backup is attempted.
     */
    int endSession();
}
