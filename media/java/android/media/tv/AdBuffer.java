/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.media.tv;

import android.annotation.NonNull;
import android.media.MediaCodec.BufferFlag;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SharedMemory;

/**
 * Buffer for advertisement data.
 * @hide
 */
public class AdBuffer implements Parcelable {
    private final int mId;
    @NonNull
    private final String mMimeType;
    @NonNull
    private final SharedMemory mBuffer;
    private final int mOffset;
    private final int mLength;
    private final long mPresentationTimeUs;
    private final int mFlags;

    public AdBuffer(
            int id,
            @NonNull String mimeType,
            @NonNull SharedMemory buffer,
            int offset,
            int length,
            long presentationTimeUs,
            @BufferFlag int flags) {
        this.mId = id;
        this.mMimeType = mimeType;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mimeType);
        this.mBuffer = buffer;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, buffer);
        this.mOffset = offset;
        this.mLength = length;
        this.mPresentationTimeUs = presentationTimeUs;
        this.mFlags = flags;
    }

    /**
     * Gets corresponding AD request ID.
     */
    public int getId() {
        return mId;
    }

    /**
     * Gets the mime type of the data.
     */
    @NonNull
    public String getMimeType() {
        return mMimeType;
    }

    /**
     * Gets the shared memory which stores the data.
     */
    @NonNull
    public SharedMemory getSharedMemory() {
        return mBuffer;
    }

    /**
     * Gets the offset of the buffer.
     */
    public int getOffset() {
        return mOffset;
    }

    /**
     * Gets the data length.
     */
    public int getLength() {
        return mLength;
    }

    /**
     * Gets the presentation time in microseconds.
     */
    public long getPresentationTimeUs() {
        return mPresentationTimeUs;
    }

    /**
     * Gets the flags.
     */
    @BufferFlag
    public int getFlags() {
        return mFlags;
    }

    @Override
    public void writeToParcel(@NonNull android.os.Parcel dest, int flags) {
        dest.writeInt(mId);
        dest.writeString(mMimeType);
        dest.writeTypedObject(mBuffer, flags);
        dest.writeInt(mOffset);
        dest.writeInt(mLength);
        dest.writeLong(mPresentationTimeUs);
        dest.writeInt(mFlags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    private AdBuffer(@NonNull Parcel in) {
        int id = in.readInt();
        String mimeType = in.readString();
        SharedMemory buffer = (SharedMemory) in.readTypedObject(SharedMemory.CREATOR);
        int offset = in.readInt();
        int length = in.readInt();
        long presentationTimeUs = in.readLong();
        int flags = in.readInt();

        this.mId = id;
        this.mMimeType = mimeType;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mMimeType);
        this.mBuffer = buffer;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mBuffer);
        this.mOffset = offset;
        this.mLength = length;
        this.mPresentationTimeUs = presentationTimeUs;
        this.mFlags = flags;
    }

    public static final @NonNull Parcelable.Creator<AdBuffer> CREATOR =
            new Parcelable.Creator<AdBuffer>() {
                @Override
                public AdBuffer[] newArray(int size) {
                    return new AdBuffer[size];
                }

                @Override
                public AdBuffer createFromParcel(Parcel in) {
                    return new AdBuffer(in);
            }
    };
}
