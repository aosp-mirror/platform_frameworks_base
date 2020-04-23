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

package android.content.pm;

/**
 * Callbacks from a data loader binder service to report data loader status.
 * @hide
 */
oneway interface IDataLoaderStatusListener {
    /** When this status is returned from DataLoader, it means that the DataLoader
    *   process is running, bound to and has handled onCreate(). */
    const int DATA_LOADER_CREATED = 0;
    /** Listener will receive this status when the DataLoader process died,
    *   binder disconnected or class destroyed. */
    const int DATA_LOADER_DESTROYED = 1;

    /** DataLoader can receive missing pages and read pages notifications,
     *  and ready to provide data. */
    const int DATA_LOADER_STARTED = 2;
    /** DataLoader no longer ready to provide data and is not receiving
    *   any notifications from IncFS. */
    const int DATA_LOADER_STOPPED = 3;

    /** DataLoader streamed everything necessary to continue installation. */
    const int DATA_LOADER_IMAGE_READY = 4;
    /** Installation can't continue as DataLoader failed to stream necessary data. */
    const int DATA_LOADER_IMAGE_NOT_READY = 5;

    /** DataLoader reports that this instance is invalid and can never be restored.
    *   Warning: this is a terminal status that data loader should use carefully and
    *            the system should almost never use - e.g. only if all recovery attempts
    *            fail and all retry limits are exceeded. */
    const int DATA_LOADER_UNRECOVERABLE = 6;

    /** Data loader status callback */
    void onStatusChanged(in int dataLoaderId, in int status);
}

