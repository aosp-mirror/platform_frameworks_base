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

/**
 * Callback class for receiving progress reports during a restore operation.
 *
 * @hide
 */
interface IRestoreObserver {
    /**
     * The restore operation has begun.
     *
     * @param numPackages The total number of packages being processed in
     *   this restore operation.
     */
    void restoreStarting(int numPackages);

    /**
     * An indication of which package is being restored currently, out of the
     * total number provided in the restoreStarting() callback.  This method
     * is not guaranteed to be called.
     *
     * @param nowBeingRestored The index, between 1 and the numPackages parameter
     *   to the restoreStarting() callback, of the package now being restored.
     */
    void onUpdate(int nowBeingRestored);

    /**
     * The restore operation has completed.
     *
     * @param error Zero on success; a nonzero error code if the restore operation
     *   as a whole failed.
     */
    void restoreFinished(int error);
}
