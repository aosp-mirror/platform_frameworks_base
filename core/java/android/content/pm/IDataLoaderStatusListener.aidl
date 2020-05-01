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
    /** The DataLoader process died, binder disconnected or class destroyed. */
    const int DATA_LOADER_DESTROYED = 0;
    /** DataLoader process is running and bound to. */
    const int DATA_LOADER_BOUND = 1;
    /** DataLoader has handled onCreate(). */
    const int DATA_LOADER_CREATED = 2;

    /** DataLoader can receive missing pages and read pages notifications,
     *  and ready to provide data. */
    const int DATA_LOADER_STARTED = 3;
    /** DataLoader no longer ready to provide data and is not receiving
    *   any notifications from IncFS. */
    const int DATA_LOADER_STOPPED = 4;

    /** DataLoader streamed everything necessary to continue installation. */
    const int DATA_LOADER_IMAGE_READY = 5;
    /** Installation can't continue as DataLoader failed to stream necessary data. */
    const int DATA_LOADER_IMAGE_NOT_READY = 6;

    /** DataLoader instance can't run at the moment, but might recover later.
     *  It's up to system to decide if the app is still usable. */
    const int DATA_LOADER_UNAVAILABLE = 7;

    /** DataLoader reports that this instance is invalid and can never be restored.
    *   Warning: this is a terminal status that data loader should use carefully and
    *            the system should almost never use - e.g. only if all recovery attempts
    *            fail and all retry limits are exceeded. */
    const int DATA_LOADER_UNRECOVERABLE = 8;

    /** Data loader status callback */
    void onStatusChanged(in int dataLoaderId, in int status);
}

