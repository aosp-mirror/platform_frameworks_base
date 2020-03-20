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
    /** Data loader status */
    const int DATA_LOADER_CREATED = 0;
    const int DATA_LOADER_DESTROYED = 1;

    const int DATA_LOADER_STARTED = 2;
    const int DATA_LOADER_STOPPED = 3;

    const int DATA_LOADER_IMAGE_READY = 4;
    const int DATA_LOADER_IMAGE_NOT_READY = 5;

    const int DATA_LOADER_UNRECOVERABLE = 6;

    /** Data loader status callback */
    void onStatusChanged(in int dataLoaderId, in int status);
}

