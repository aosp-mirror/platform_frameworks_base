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

import java.io.IOException;

/**
 * Buffer for advertisement data.
 */
public final class AdBuffer implements Parcelable {
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

    /** @hide **/
    public static AdBuffer dupAdBuffer(AdBuffer buffer) throws IOException {
        if (buffer == null) {
            return null;
        }
        return new AdBuffer(buffer.mId, buffer.mMimeType,
                SharedMemory.fromFileDescriptor(buffer.mBuffer.getFdDup()), buffer.mOffset,
                buffer.mLength, buffer.mPresentationTimeUs, buffer.mFlags);
    }

    /**
     * Gets corresponding AD request ID.
     *
     * @return The ID of the ad request
     */
    public int getId() {
        return mId;
    }

    /**
     * Gets the mime type of the data.
     *
     * @return The mime type of the data.
     */
    @NonNull
    public String getMimeType() {
        return mMimeType;
    }

    /**
     * Gets the {@link SharedMemory} which stores the data.
     *
     * <p> Information on how the data in this buffer is formatted can be found using
     * {@link AdRequest#getMetadata()}
     * <p> This data lives in a {@link SharedMemory} instance because of the
     * potentially large amount of data needed to store the ad. This optimizes the
     * data communication between the ad data source and the service responsible for
     * its display.
     *
     * @see SharedMemory#create(String, int)
     * @return The {@link SharedMemory} that stores the data for this ad buffer.
     */
    @NonNull
    public SharedMemory getSharedMemory() {
        return mBuffer;
    }

    /**
     * Gets the offset into the shared memory to begin mapping.
     *
     * @see SharedMemory#map(int, int, int)
     * @return The offset of this ad buffer in the shared memory in bytes.
     */
    public int getOffset() {
        return mOffset;
    }

    /**
     * Gets the data length of this ad buffer.
     *
     * @return The data length of this ad buffer in bytes.
     */
    public int getLength() {
        return mLength;
    }

    /**
     * Gets the presentation time.
     *
     * @return The presentation time in microseconds.
     */
    public long getPresentationTimeUs() {
        return mPresentationTimeUs;
    }

    /**
     * Gets the buffer flags for this ad buffer.
     *
     * @see android.media.MediaCodec
     * @return The buffer flags for this ad buffer.
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
