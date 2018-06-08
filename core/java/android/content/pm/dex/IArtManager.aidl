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
     * Snapshots a runtime profile according to the {@code profileType} parameter.
     *
     * If {@code profileType} is {@link ArtManager#PROFILE_APPS} the method will snapshot
     * the profile for for an apk belonging to the package {@code packageName}.
     * The apk is identified by {@code codePath}.
     *
     * If {@code profileType} is {@code ArtManager.PROFILE_BOOT_IMAGE} the method will snapshot
     * the profile for the boot image. In this case {@code codePath can be null}. The parameters
     * {@code packageName} and {@code codePath} are ignored.
     *
     * The calling process must have {@code android.permission.READ_RUNTIME_PROFILE} permission.
     *
     * The result will be posted on the {@code executor} using the given {@code callback}.
     * The profile will be available as a read-only {@link android.os.ParcelFileDescriptor}.
     *
     * This method will throw {@link IllegalStateException} if
     * {@link ArtManager#isRuntimeProfilingEnabled(int)} does not return true for the given
     * {@code profileType}.
     */
    void snapshotRuntimeProfile(int profileType, in String packageName,
        in String codePath, in ISnapshotRuntimeProfileCallback callback, String callingPackage);

     /**
       * Returns true if runtime profiles are enabled for the given type, false otherwise.
       * The type can be can be either {@code ArtManager.PROFILE_APPS}
       * or {@code ArtManager.PROFILE_BOOT_IMAGE}.
       *
       * @param profileType
       */
    boolean isRuntimeProfilingEnabled(int profileType, String callingPackage);
}
