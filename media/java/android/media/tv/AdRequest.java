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

import android.annotation.NonNull;
import android.annotation.StringDef;
import android.os.Bundle;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** @hide */
public final class AdRequest implements Parcelable {
    @Retention(RetentionPolicy.SOURCE)
    @StringDef(prefix = "REQUEST_TYPE_", value = {
            REQUEST_TYPE_START,
            REQUEST_TYPE_STOP
    })
    public @interface RequestType {}

    public static final String REQUEST_TYPE_START = "START";
    public static final String REQUEST_TYPE_STOP = "STOP";

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
    private final @RequestType String mRequestType;
    private final ParcelFileDescriptor mFileDescriptor;
    private final long mStartTime;
    private final long mStopTime;
    private final long mEchoInterval;
    private final String mMediaFileType;
    private final Bundle mMetadata;

    public AdRequest(int id, @RequestType String requestType, ParcelFileDescriptor fileDescriptor,
            long startTime, long stopTime, long echoInterval, String mediaFileType,
            Bundle metadata) {
        mId = id;
        mRequestType = requestType;
        mFileDescriptor = fileDescriptor;
        mStartTime = startTime;
        mStopTime = stopTime;
        mEchoInterval = echoInterval;
        mMediaFileType = mediaFileType;
        mMetadata = metadata;
    }

    private AdRequest(Parcel source) {
        mId = source.readInt();
        mRequestType = source.readString();
        mFileDescriptor = source.readFileDescriptor();
        mStartTime = source.readLong();
        mStopTime = source.readLong();
        mEchoInterval = source.readLong();
        mMediaFileType = source.readString();
        mMetadata = source.readBundle();
    }

    public int getId() {
        return mId;
    }

    public @RequestType String getRequestType() {
        return mRequestType;
    }

    public ParcelFileDescriptor getFileDescriptor() {
        return mFileDescriptor;
    }

    public long getStartTime() {
        return mStartTime;
    }

    public long getStopTime() {
        return mStopTime;
    }

    public long getEchoInterval() {
        return mEchoInterval;
    }

    public String getMediaFileType() {
        return mMediaFileType;
    }

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
        dest.writeString(mRequestType);
        mFileDescriptor.writeToParcel(dest, flags);
        dest.writeLong(mStartTime);
        dest.writeLong(mStopTime);
        dest.writeLong(mEchoInterval);
        dest.writeString(mMediaFileType);
        dest.writeBundle(mMetadata);
    }
}
