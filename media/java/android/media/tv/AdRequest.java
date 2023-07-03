/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * An advertisement request which can be sent to TV input to request AD operations.
 */
public final class AdRequest implements Parcelable {
    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "REQUEST_TYPE_", value = {
            REQUEST_TYPE_START,
            REQUEST_TYPE_STOP
    })
    public @interface RequestType {}

    /**
     * Request to start an advertisement.
     */
    public static final int REQUEST_TYPE_START = 1;
    /**
     * Request to stop an advertisement.
     */
    public static final int REQUEST_TYPE_STOP = 2;

    public static final @NonNull Parcelable.Creator<AdRequest> CREATOR =
            new Parcelable.Creator<AdRequest>() {
                @Override
                public AdRequest createFromParcel(Parcel source) {
                    return new AdRequest(source);
                }

                @Override
                public AdRequest[] newArray(int size) {
                    return new AdRequest[size];
                }
            };

    private final int mId;
    private final @RequestType int mRequestType;
    private final ParcelFileDescriptor mFileDescriptor;
    private final long mStartTime;
    private final long mStopTime;
    private final long mEchoInterval;
    private final String mMediaFileType;
    private final Bundle mMetadata;
    private final Uri mUri;

    /**
     * The key for video metadata.
     *
     * @see #getMetadata()
     * @hide
     */
    public static final String KEY_VIDEO_METADATA = "key_video_metadata";

    /**
     * The key for audio metadata.
     *
     * @see #getMetadata()
     * @hide
     */
    public static final String KEY_AUDIO_METADATA = "key_audio_metadata";

    public AdRequest(int id, @RequestType int requestType,
            @Nullable ParcelFileDescriptor fileDescriptor, long startTime, long stopTime,
            long echoInterval, @Nullable String mediaFileType, @NonNull Bundle metadata) {
        this(id, requestType, fileDescriptor, null, startTime, stopTime, echoInterval,
                mediaFileType, metadata);
    }

    public AdRequest(int id, @RequestType int requestType, @Nullable Uri uri, long startTime,
            long stopTime, long echoInterval, @NonNull Bundle metadata) {
        this(id, requestType, null, uri, startTime, stopTime, echoInterval, null, metadata);
    }

    private AdRequest(int id, @RequestType int requestType,
            @Nullable ParcelFileDescriptor fileDescriptor, @Nullable Uri uri, long startTime,
            long stopTime, long echoInterval, @Nullable String mediaFileType,
            @NonNull Bundle metadata) {
        mId = id;
        mRequestType = requestType;
        mFileDescriptor = fileDescriptor;
        mStartTime = startTime;
        mStopTime = stopTime;
        mEchoInterval = echoInterval;
        mMediaFileType = mediaFileType;
        mMetadata = metadata;
        mUri = uri;
    }

    private AdRequest(Parcel source) {
        mId = source.readInt();
        mRequestType = source.readInt();
        int readInt = source.readInt();
        if (readInt == 1) {
            mFileDescriptor = ParcelFileDescriptor.CREATOR.createFromParcel(source);
            mUri = null;
        } else if (readInt == 2) {
            String stringUri = source.readString();
            mUri = stringUri == null ? null : Uri.parse(stringUri);
            mFileDescriptor = null;
        } else {
            mFileDescriptor = null;
            mUri = null;
        }
        mStartTime = source.readLong();
        mStopTime = source.readLong();
        mEchoInterval = source.readLong();
        mMediaFileType = source.readString();
        mMetadata = source.readBundle();
    }

    /**
     * Gets the ID of AD request.
     */
    public int getId() {
        return mId;
    }

    /**
     * Gets the request type.
     */
    @RequestType
    public int getRequestType() {
        return mRequestType;
    }

    /**
     * Gets the file descriptor of the AD media.
     *
     * @return The file descriptor of the AD media. Can be {@code null} for
     *         {@link #REQUEST_TYPE_STOP} or a URI is used.
     */
    @Nullable
    public ParcelFileDescriptor getFileDescriptor() {
        return mFileDescriptor;
    }

    /**
     * Gets the URI of the AD media.
     *
     * @return The URI of the AD media. Can be {@code null} for {@link #REQUEST_TYPE_STOP} or a file
     *         descriptor is used.
     */
    @Nullable
    public Uri getUri() {
        return mUri;
    }

    /**
     * Gets the start time of the AD media in milliseconds.
     * <p>0 means start immediately
     */
    public long getStartTimeMillis() {
        return mStartTime;
    }

    /**
     * Gets the stop time of the AD media in milliseconds.
     * <p>-1 means until the end
     */
    public long getStopTimeMillis() {
        return mStopTime;
    }

    /**
     * Gets the echo interval in milliseconds.
     * <p>The interval TV input needs to echo and inform TV interactive app service the video
     * playback elapsed time.
     *
     * @see android.media.tv.AdResponse
     */
    public long getEchoIntervalMillis() {
        return mEchoInterval;
    }

    /**
     * Gets the media file type such as mp4, mob, avi.
     *
     * @return The media file type. Can be {@code null} for {@link #REQUEST_TYPE_STOP}.
     */
    @Nullable
    public String getMediaFileType() {
        return mMediaFileType;
    }

    /**
     * Gets the metadata of the media file.
     *
     * <p>This includes additional information the TV input needs to play the AD media. This may
     * include fields in {@link android.media.MediaFormat} like
     * {@link android.media.MediaFormat#KEY_SAMPLE_RATE}, or integrity information like SHA. What
     * data is included depends on the format of the media file.
     *
     * @return The metadata of the media file. Can be an empty bundle for
     *         {@link #REQUEST_TYPE_STOP}.
     */
    @NonNull
    public Bundle getMetadata() {
        return mMetadata;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mId);
        dest.writeInt(mRequestType);
        if (mFileDescriptor != null) {
            dest.writeInt(1);
            mFileDescriptor.writeToParcel(dest, flags);
        } else if (mUri != null) {
            dest.writeInt(2);
            String stringUri = mUri.toString();
            dest.writeString(stringUri);
        } else {
            dest.writeInt(0);
        }
        dest.writeLong(mStartTime);
        dest.writeLong(mStopTime);
        dest.writeLong(mEchoInterval);
        dest.writeString(mMediaFileType);
        dest.writeBundle(mMetadata);
    }
}
