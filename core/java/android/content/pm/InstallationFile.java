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
import android.annotation.SystemApi;

/**
 * Defines the properties of a file in an installation session.
 * @hide
 */
@SystemApi
public final class InstallationFile {
    private final @NonNull InstallationFileParcel mParcel;

    public InstallationFile(@PackageInstaller.FileLocation int location, @NonNull String name,
            long lengthBytes, @Nullable byte[] metadata, @Nullable byte[] signature) {
        mParcel = new InstallationFileParcel();
        mParcel.location = location;
        mParcel.name = name;
        mParcel.size = lengthBytes;
        mParcel.metadata = metadata;
        mParcel.signature = signature;
    }

    public @PackageInstaller.FileLocation int getLocation() {
        return mParcel.location;
    }

    public @NonNull String getName() {
        return mParcel.name;
    }

    public long getLengthBytes() {
        return mParcel.size;
    }

    public @Nullable byte[] getMetadata() {
        return mParcel.metadata;
    }

    public @Nullable byte[] getSignature() {
        return mParcel.signature;
    }

    /** @hide */
    public @NonNull InstallationFileParcel getData() {
        return mParcel;
    }
}
