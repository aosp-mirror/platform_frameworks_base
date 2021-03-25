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

import android.annotation.NonNull;
import android.annotation.Nullable;

/**
 * Definition of a file in a streaming installation session.
 * You can use this class to retrieve the information of such a file, such as its name, size and
 * metadata. These file attributes will be consistent with those used in:
 * {@code android.content.pm.PackageInstaller.Session#addFile}, when the file was first added
 * into the session.
 *
 * @see android.content.pm.PackageInstaller.Session#addFile
 */
public final class InstallationFile {
    private final @NonNull InstallationFileParcel mParcel;

    /**
     * Constructor, internal use only
     * @hide
     */
    public InstallationFile(@PackageInstaller.FileLocation int location, @NonNull String name,
            long lengthBytes, @Nullable byte[] metadata, @Nullable byte[] signature) {
        mParcel = new InstallationFileParcel();
        mParcel.location = location;
        mParcel.name = name;
        mParcel.size = lengthBytes;
        mParcel.metadata = metadata;
        mParcel.signature = signature;
    }

    /**
     * Installation Location of this file. Can be one of the following three locations:
     * <ul>
     *     <li>(1) {@code PackageInstaller.LOCATION_DATA_APP}</li>
     *     <li>(2) {@code PackageInstaller.LOCATION_MEDIA_OBB}</li>
     *     <li>(3) {@code PackageInstaller.LOCATION_MEDIA_DATA}</li>
     * </ul>
     * @see android.content.pm.PackageInstaller
     * @return Integer that denotes the installation location of the file.
     */
    public @PackageInstaller.FileLocation int getLocation() {
        return mParcel.location;
    }

    /**
     * @return Name of the file.
     */
    public @NonNull String getName() {
        return mParcel.name;
    }

    /**
     * @return File size in bytes.
     */
    public long getLengthBytes() {
        return mParcel.size;
    }

    /**
     * @return File metadata as a byte array
     */
    public @Nullable byte[] getMetadata() {
        return mParcel.metadata;
    }

    /**
     * @return File signature info as a byte array
     */
    public @Nullable byte[] getSignature() {
        return mParcel.signature;
    }

    /** @hide */
    public @NonNull InstallationFileParcel getData() {
        return mParcel;
    }
}
