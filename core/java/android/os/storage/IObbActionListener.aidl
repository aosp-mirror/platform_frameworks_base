/**
 * Copyright (c) 2016, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.os.storage;

/**
 * Callback class for receiving events from StorageManagerService about Opaque Binary
 * Blobs (OBBs).
 *
 * Don't change the existing transaction Ids as they could be used in the native code.
 * When adding a new method, assign the next available transaction id.
 *
 * @hide - Applications should use StorageManager to interact with OBBs.
 */
oneway interface IObbActionListener {
    /**
     * Return from an OBB action result.
     *
     * @param filename the path to the OBB the operation was performed on
     * @param nonce identifier that is meaningful to the receiver
     * @param status status code as defined in {@link OnObbStateChangeListener}
     */
    void onObbResult(in String filename, int nonce, int status) = 0;

    /**
     * Don't change the existing transaction Ids as they could be used in the native code.
     * When adding a new method, assign the next available transaction id.
     */
}