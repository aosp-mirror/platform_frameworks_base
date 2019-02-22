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
package android.telephony.ims;

import android.annotation.CheckResult;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Pass an instance of this class to
 * {@link RcsMessage#insertFileTransfer(RcsFileTransferCreationParams)} create an
 * {@link RcsFileTransferPart} and save it into storage.
 *
 * @hide
 */
public final class RcsFileTransferCreationParams implements Parcelable {
    private String mRcsFileTransferSessionId;
    private Uri mContentUri;
    private String mContentMimeType;
    private long mFileSize;
    private long mTransferOffset;
    private int mWidth;
    private int mHeight;
    private long mMediaDuration;
    private Uri mPreviewUri;
    private String mPreviewMimeType;
    private @RcsFileTransferPart.RcsFileTransferStatus int mFileTransferStatus;

    /**
     * @return Returns the globally unique RCS file transfer session ID for the
     * {@link RcsFileTransferPart} to be created
     */
    public String getRcsFileTransferSessionId() {
        return mRcsFileTransferSessionId;
    }

    /**
     * @return Returns the URI for the content of the {@link RcsFileTransferPart} to be created
     */
    public Uri getContentUri() {
        return mContentUri;
    }

    /**
     * @return Returns the MIME type for the content of the {@link RcsFileTransferPart} to be
     * created
     */
    public String getContentMimeType() {
        return mContentMimeType;
    }

    /**
     * @return Returns the file size in bytes for the {@link RcsFileTransferPart} to be created
     */
    public long getFileSize() {
        return mFileSize;
    }

    /**
     * @return Returns the transfer offset for the {@link RcsFileTransferPart} to be created. The
     * file transfer offset is defined as how many bytes have been successfully transferred to the
     * receiver of this file transfer.
     */
    public long getTransferOffset() {
        return mTransferOffset;
    }

    /**
     * @return Returns the width of the {@link RcsFileTransferPart} to be created. The value is in
     * pixels.
     */
    public int getWidth() {
        return mWidth;
    }

    /**
     * @return Returns the height of the {@link RcsFileTransferPart} to be created. The value is in
     * pixels.
     */
    public int getHeight() {
        return mHeight;
    }

    /**
     * @return Returns the duration of the {@link RcsFileTransferPart} to be created.
     */
    public long getMediaDuration() {
        return mMediaDuration;
    }

    /**
     * @return Returns the URI of the preview of the content of the {@link RcsFileTransferPart} to
     * be created. This should only be used for multi-media files.
     */
    public Uri getPreviewUri() {
        return mPreviewUri;
    }

    /**
     * @return Returns the MIME type of the preview of the content of the
     * {@link RcsFileTransferPart} to be created. This should only be used for multi-media files.
     */
    public String getPreviewMimeType() {
        return mPreviewMimeType;
    }

    /**
     * @return Returns the status of the {@link RcsFileTransferPart} to be created.
     */
    public @RcsFileTransferPart.RcsFileTransferStatus int getFileTransferStatus() {
        return mFileTransferStatus;
    }

    /**
     * @hide
     */
    RcsFileTransferCreationParams(Builder builder) {
        mRcsFileTransferSessionId = builder.mRcsFileTransferSessionId;
        mContentUri = builder.mContentUri;
        mContentMimeType = builder.mContentMimeType;
        mFileSize = builder.mFileSize;
        mTransferOffset = builder.mTransferOffset;
        mWidth = builder.mWidth;
        mHeight = builder.mHeight;
        mMediaDuration = builder.mLength;
        mPreviewUri = builder.mPreviewUri;
        mPreviewMimeType = builder.mPreviewMimeType;
        mFileTransferStatus = builder.mFileTransferStatus;
    }

    /**
     * A builder to create instances of {@link RcsFileTransferCreationParams}
     */
    public class Builder {
        private String mRcsFileTransferSessionId;
        private Uri mContentUri;
        private String mContentMimeType;
        private long mFileSize;
        private long mTransferOffset;
        private int mWidth;
        private int mHeight;
        private long mLength;
        private Uri mPreviewUri;
        private String mPreviewMimeType;
        private @RcsFileTransferPart.RcsFileTransferStatus int mFileTransferStatus;

        /**
         * Sets the globally unique RCS file transfer session ID for the {@link RcsFileTransferPart}
         * to be created
         *
         * @param sessionId The RCS file transfer session ID
         * @return The same instance of {@link Builder} to chain methods
         */
        @CheckResult
        public Builder setFileTransferSessionId(String sessionId) {
            mRcsFileTransferSessionId = sessionId;
            return this;
        }

        /**
         * Sets the URI for the content of the {@link RcsFileTransferPart} to be created
         *
         * @param contentUri The URI for the file
         * @return The same instance of {@link Builder} to chain methods
         */
        @CheckResult
        public Builder setContentUri(Uri contentUri) {
            mContentUri = contentUri;
            return this;
        }

        /**
         * Sets the MIME type for the content of the {@link RcsFileTransferPart} to be created
         *
         * @param contentType The MIME type of the file
         * @return The same instance of {@link Builder} to chain methods
         */
        @CheckResult
        public Builder setContentMimeType(String contentType) {
            mContentMimeType = contentType;
            return this;
        }

        /**
         * Sets the file size for the {@link RcsFileTransferPart} to be created
         *
         * @param size The size of the file in bytes
         * @return The same instance of {@link Builder} to chain methods
         */
        @CheckResult
        public Builder setFileSize(long size) {
            mFileSize = size;
            return this;
        }

        /**
         * Sets the transfer offset for the {@link RcsFileTransferPart} to be created. The file
         * transfer offset is defined as how many bytes have been successfully transferred to the
         * receiver of this file transfer.
         *
         * @param offset The transfer offset in bytes
         * @return The same instance of {@link Builder} to chain methods
         */
        @CheckResult
        public Builder setTransferOffset(long offset) {
            mTransferOffset = offset;
            return this;
        }

        /**
         * Sets the width of the {@link RcsFileTransferPart} to be created. This should only be used
         * for multi-media files.
         *
         * @param width The width of the multi-media file in pixels.
         * @return The same instance of {@link Builder} to chain methods
         */
        @CheckResult
        public Builder setWidth(int width) {
            mWidth = width;
            return this;
        }

        /**
         * Sets the height of the {@link RcsFileTransferPart} to be created. This should only be
         * used for multi-media files.
         *
         * @param height The height of the multi-media file in pixels.
         * @return The same instance of {@link Builder} to chain methods
         */
        @CheckResult
        public Builder setHeight(int height) {
            mHeight = height;
            return this;
        }

        /**
         * Sets the length of the {@link RcsFileTransferPart} to be created. This should only be
         * used for multi-media files such as audio or video.
         *
         * @param length The length of the multi-media file in milliseconds
         * @return The same instance of {@link Builder} to chain methods
         */
        @CheckResult
        public Builder setMediaDuration(long length) {
            mLength = length;
            return this;
        }

        /**
         * Sets the URI of the preview of the content of the {@link RcsFileTransferPart} to be
         * created. This should only be used for multi-media files.
         *
         * @param previewUri The URI of the preview of the file transfer
         * @return The same instance of {@link Builder} to chain methods
         */
        @CheckResult
        public Builder setPreviewUri(Uri previewUri) {
            mPreviewUri = previewUri;
            return this;
        }

        /**
         * Sets the MIME type of the preview of the content of the {@link RcsFileTransferPart} to
         * be created. This should only be used for multi-media files.
         *
         * @param previewType The MIME type of the preview of the file transfer
         * @return The same instance of {@link Builder} to chain methods
         */
        @CheckResult
        public Builder setPreviewMimeType(String previewType) {
            mPreviewMimeType = previewType;
            return this;
        }

        /**
         * Sets the status of the {@link RcsFileTransferPart} to be created.
         *
         * @param status The status of the file transfer
         * @return The same instance of {@link Builder} to chain methods
         */
        @CheckResult
        public Builder setFileTransferStatus(
                @RcsFileTransferPart.RcsFileTransferStatus int status) {
            mFileTransferStatus = status;
            return this;
        }

        /**
         * Creates an instance of {@link RcsFileTransferCreationParams} with the given
         * parameters.
         *
         * @return The same instance of {@link Builder} to chain methods
         * @see RcsMessage#insertFileTransfer(RcsFileTransferCreationParams)
         */
        public RcsFileTransferCreationParams build() {
            return new RcsFileTransferCreationParams(this);
        }
    }

    private RcsFileTransferCreationParams(Parcel in) {
        mRcsFileTransferSessionId = in.readString();
        mContentUri = in.readParcelable(Uri.class.getClassLoader());
        mContentMimeType = in.readString();
        mFileSize = in.readLong();
        mTransferOffset = in.readLong();
        mWidth = in.readInt();
        mHeight = in.readInt();
        mMediaDuration = in.readLong();
        mPreviewUri = in.readParcelable(Uri.class.getClassLoader());
        mPreviewMimeType = in.readString();
        mFileTransferStatus = in.readInt();
    }

    public static final Creator<RcsFileTransferCreationParams> CREATOR =
            new Creator<RcsFileTransferCreationParams>() {
                @Override
                public RcsFileTransferCreationParams createFromParcel(Parcel in) {
                    return new RcsFileTransferCreationParams(in);
                }

                @Override
                public RcsFileTransferCreationParams[] newArray(int size) {
                    return new RcsFileTransferCreationParams[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mRcsFileTransferSessionId);
        dest.writeParcelable(mContentUri, flags);
        dest.writeString(mContentMimeType);
        dest.writeLong(mFileSize);
        dest.writeLong(mTransferOffset);
        dest.writeInt(mWidth);
        dest.writeInt(mHeight);
        dest.writeLong(mMediaDuration);
        dest.writeParcelable(mPreviewUri, flags);
        dest.writeString(mPreviewMimeType);
        dest.writeInt(mFileTransferStatus);
    }
}
