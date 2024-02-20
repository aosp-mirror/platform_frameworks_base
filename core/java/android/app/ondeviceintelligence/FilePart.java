/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.app.ondeviceintelligence;

import static android.app.ondeviceintelligence.flags.Flags.FLAG_ENABLE_ON_DEVICE_INTELLIGENCE;

import android.annotation.FlaggedApi;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.PersistableBundle;

import android.annotation.NonNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Objects;

/**
 * Represents file data with an associated file descriptor sent to and received from remote
 * processing. The interface ensures that the underlying file-descriptor is always opened in
 * read-only mode.
 *
 * @hide
 */
@FlaggedApi(FLAG_ENABLE_ON_DEVICE_INTELLIGENCE)
@SystemApi
public final class FilePart implements Parcelable {
    private final String mPartKey;
    private final PersistableBundle mPartParams;
    private final ParcelFileDescriptor mParcelFileDescriptor;

    private FilePart(@NonNull String partKey, @NonNull PersistableBundle partParams,
            @NonNull ParcelFileDescriptor parcelFileDescriptor) {
        Objects.requireNonNull(partKey);
        Objects.requireNonNull(partParams);
        this.mPartKey = partKey;
        this.mPartParams = partParams;
        this.mParcelFileDescriptor = Objects.requireNonNull(parcelFileDescriptor);
    }

    /**
     * Create a file part using a filePath and any additional params.
     */
    public FilePart(@NonNull String partKey, @NonNull PersistableBundle partParams,
            @NonNull String filePath)
            throws FileNotFoundException {
        this(partKey, partParams, Objects.requireNonNull(ParcelFileDescriptor.open(
                new File(Objects.requireNonNull(filePath)), ParcelFileDescriptor.MODE_READ_ONLY)));
    }

    /**
     * Create a file part using a file input stream and any additional params.
     * It is the caller's responsibility to close the stream. It is safe to do so as soon as this
     * call returns.
     */
    public FilePart(@NonNull String partKey, @NonNull PersistableBundle partParams,
            @NonNull FileInputStream fileInputStream)
            throws IOException {
        this(partKey, partParams, ParcelFileDescriptor.dup(fileInputStream.getFD()));
    }

    /**
     * Returns a FileInputStream for the associated File.
     * Caller must close the associated stream when done reading from it.
     *
     * @return the FileInputStream associated with the FilePart.
     */
    @NonNull
    public FileInputStream getFileInputStream() {
        return new FileInputStream(mParcelFileDescriptor.getFileDescriptor());
    }

    /**
     * Returns the unique key associated with the part. Each Part key added to a content object
     * should be ensured to be unique.
     */
    @NonNull
    public String getFilePartKey() {
        return mPartKey;
    }

    /**
     * Returns the params associated with Part.
     */
    @NonNull
    public PersistableBundle getFilePartParams() {
        return mPartParams;
    }


    @Override
    public int describeContents() {
        return CONTENTS_FILE_DESCRIPTOR;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString8(getFilePartKey());
        dest.writePersistableBundle(getFilePartParams());
        mParcelFileDescriptor.writeToParcel(dest, flags
                | Parcelable.PARCELABLE_WRITE_RETURN_VALUE); // This flag ensures that the sender's
        // copy of the Pfd is closed as soon as the Binder call succeeds.
    }

    @NonNull
    public static final Creator<FilePart> CREATOR = new Creator<>() {
        @Override
        public FilePart createFromParcel(Parcel in) {
            return new FilePart(in.readString(), in.readTypedObject(PersistableBundle.CREATOR),
                    in.readParcelable(
                            getClass().getClassLoader(), ParcelFileDescriptor.class));
        }

        @Override
        public FilePart[] newArray(int size) {
            return new FilePart[size];
        }
    };
}
