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

package android.os;

import android.os.DropBoxEntry;
import android.os.ParcelFileDescriptor;

/**
 * Enqueues chunks of data (from various sources -- application crashes, kernel
 * log records, etc.).  The queue is size bounded and will drop old data if the
 * enqueued data exceeds the maximum size.
 *
 * <p>This interface is implemented by a system service you can access:
 *
 * <pre>IDropBox.Stub.asInterface(ServiceManager.getService("dropbox"));</pre>
 *
 * <p>Other system services and debugging tools may scan the drop box to upload
 * entries for processing.
 *
 * {@pending}
 */
interface IDropBox {
    /**
     * Stores human-readable text.  The data may be discarded eventually (or even
     * immediately) if space is limited, or ignored entirely if the tag has been
     * blocked (see {@link #isTagEnabled}).
     *
     * @param tag describing the type of entry being stored
     * @param data value to store
     */
    void addText(String tag, String data);

    /**
     * Stores binary data.  The data may be ignored or discarded as with
     * {@link #addText}.
     *
     * @param tag describing the type of entry being stored
     * @param data value to store
     * @param flags describing the data, defined in {@link DropBoxEntry}
     */
    void addData(String tag, in byte[] data, int flags);

    /**
     * Stores data read from a file descriptor.  The data may be ignored or
     * discarded as with {@link #addText}.  You must close your
     * ParcelFileDescriptor object after calling this method!
     *
     * @param tag describing the type of entry being stored
     * @param data file descriptor to read from
     * @param flags describing the data, defined in {@link DropBoxEntry}
     */
    void addFile(String tag, in ParcelFileDescriptor data, int flags);

    /**
     * Checks any blacklists (set in system settings) to see whether a certain
     * tag is allowed.  Entries with disabled tags will be dropped immediately,
     * so you can save the work of actually constructing and sending the data.
     *
     * @param tag that would be used in {@link #addText} or {@link #addFile}
     * @return whether events with that tag would be accepted
     */
    boolean isTagEnabled(String tag);

    /**
     * Gets the next entry from the drop box *after* the specified time.
     * Requires android.permission.READ_LOGS.  You must always call
     * {@link DropBoxEntry#close()} on the return value!
     *
     * @param tag of entry to look for, null for all tags
     * @param millis time of the last entry seen
     * @return the next entry, or null if there are no more entries
     */
    DropBoxEntry getNextEntry(String tag, long millis);

    // TODO: It may be useful to have some sort of notification mechanism
    // when data is added to the dropbox, for demand-driven readers --
    // for now readers need to poll the dropbox to find new data.
}
