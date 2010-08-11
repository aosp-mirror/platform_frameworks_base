/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.os.storage;

/**
 * Callback class for receiving events from MountService about
 * Opaque Binary Blobs (OBBs).
 *
 * @hide - Applications should use android.os.storage.StorageManager
 * to interact with OBBs.
 */
interface IObbActionListener {
    /**
     * Return from an OBB action result.
     *
     * @param filename the path to the OBB the operation was performed on
     * @param returnCode status of the operation
     */
    void onObbResult(String filename, String status);
}
