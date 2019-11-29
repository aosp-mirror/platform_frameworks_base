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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Defines the properties of a file in an installation session.
 * TODO(b/136132412): update with new APIs.
 *
 * @hide
 */
public final class InstallationFile implements Parcelable {
    public static final int FILE_TYPE_UNKNOWN = -1;
    public static final int FILE_TYPE_APK = 0;
    public static final int FILE_TYPE_LIB = 1;
    public static final int FILE_TYPE_OBB = 2;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"FILE_TYPE_"}, value = {
            FILE_TYPE_APK,
            FILE_TYPE_LIB,
            FILE_TYPE_OBB,
    })
    public @interface FileType {
    }

    private String mFileName;
    private @FileType int mFileType;
    private long mFileSize;
    private byte[] mMetadata;

    public InstallationFile(@NonNull String fileName, long fileSize,
            @Nullable byte[] metadata) {
        mFileName = fileName;
        mFileSize = fileSize;
        mMetadata = metadata;
        if (fileName.toLowerCase().endsWith(".apk")) {
            mFileType = FILE_TYPE_APK;
        } else if (fileName.toLowerCase().endsWith(".obb")) {
            mFileType = FILE_TYPE_OBB;
        } else if (fileName.toLowerCase().endsWith(".so") && fileName.toLowerCase().startsWith(
                "lib/")) {
            mFileType = FILE_TYPE_LIB;
        } else {
            mFileType = FILE_TYPE_UNKNOWN;
        }
    }

    public @FileType int getFileType() {
        return mFileType;
    }

    public @NonNull String getName() {
        return mFileName;
    }

    public long getSize() {
        return mFileSize;
    }

    public @Nullable byte[] getMetadata() {
        return mMetadata;
    }

    private InstallationFile(Parcel source) {
        mFileName = source.readString();
        mFileType = source.readInt();
        mFileSize = source.readLong();
        mMetadata = source.createByteArray();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mFileName);
        dest.writeInt(mFileType);
        dest.writeLong(mFileSize);
        dest.writeByteArray(mMetadata);
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
