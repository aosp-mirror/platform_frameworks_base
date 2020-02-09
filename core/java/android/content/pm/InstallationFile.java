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
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Defines the properties of a file in an installation session.
 * @hide
 */
@SystemApi
public final class InstallationFile implements Parcelable {
    private final @PackageInstaller.FileLocation int mLocation;
    private final @NonNull String mName;
    private final long mLengthBytes;
    private final @Nullable byte[] mMetadata;
    private final @Nullable byte[] mSignature;

    public InstallationFile(@PackageInstaller.FileLocation int location, @NonNull String name,
            long lengthBytes, @Nullable byte[] metadata, @Nullable byte[] signature) {
        mLocation = location;
        mName = name;
        mLengthBytes = lengthBytes;
        mMetadata = metadata;
        mSignature = signature;
    }

    public @PackageInstaller.FileLocation int getLocation() {
        return mLocation;
    }

    public @NonNull String getName() {
        return mName;
    }

    public long getLengthBytes() {
        return mLengthBytes;
    }

    public @Nullable byte[] getMetadata() {
        return mMetadata;
    }

    public @Nullable byte[] getSignature() {
        return mSignature;
    }

    private InstallationFile(Parcel source) {
        mLocation = source.readInt();
        mName = source.readString();
        mLengthBytes = source.readLong();
        mMetadata = source.createByteArray();
        mSignature = source.createByteArray();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mLocation);
        dest.writeString(mName);
        dest.writeLong(mLengthBytes);
        dest.writeByteArray(mMetadata);
        dest.writeByteArray(mSignature);
    }

    public static final @NonNull Creator<InstallationFile> CREATOR =
            new Creator<InstallationFile>() {
        public InstallationFile createFromParcel(Parcel source) {
            return new InstallationFile(source);
        }

        public InstallationFile[] newArray(int size) {
            return new InstallationFile[size];
        }
    };

}
