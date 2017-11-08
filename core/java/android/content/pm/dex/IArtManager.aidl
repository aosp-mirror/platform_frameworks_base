/*
** Copyright 2017, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package android.content.pm.dex;

import android.content.pm.dex.ISnapshotRuntimeProfileCallback;

/**
 * A system service that provides access to runtime and compiler artifacts.
 *
 * @hide
 */
interface IArtManager {
    /**
     * Snapshots the runtime profile for an apk belonging to the package {@param packageName}.
     * The apk is identified by {@param codePath}. The calling process must have
     * {@code android.permission.READ_RUNTIME_PROFILE} permission.
     *
     * The result will be posted on {@param callback} with the profile being available as a
     * read-only {@link android.os.ParcelFileDescriptor}.
     */
    oneway void snapshotRuntimeProfile(in String packageName,
        in String codePath, in ISnapshotRuntimeProfileCallback callback);

    /**
     * Returns true if runtime profiles are enabled, false otherwise.
     *
     * The calling process must have {@code android.permission.READ_RUNTIME_PROFILE} permission.
     */
    boolean isRuntimeProfilingEnabled();
}
