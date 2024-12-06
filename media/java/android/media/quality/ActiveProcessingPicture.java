/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.media.quality;

import android.annotation.FlaggedApi;
import android.media.tv.flags.Flags;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

/**
 * Active picture represents an image or video undergoing picture processing which uses a picture
 * profile. The picture profile is used to configure the picture processing parameters.
 */
@FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW)
public final class ActiveProcessingPicture implements Parcelable {
    private final int mId;
    private final String mProfileId;

    public ActiveProcessingPicture(int id, @NonNull String profileId) {
        mId = id;
        mProfileId = profileId;
    }

    /** @hide */
    ActiveProcessingPicture(Parcel in) {
        mId = in.readInt();
        mProfileId = in.readString();
    }

    @NonNull
    public static final Creator<ActiveProcessingPicture> CREATOR = new Creator<>() {
        @Override
        public ActiveProcessingPicture createFromParcel(Parcel in) {
            return new ActiveProcessingPicture(in);
        }

        @Override
        public ActiveProcessingPicture[] newArray(int size) {
            return new ActiveProcessingPicture[size];
        }
    };

    /**
     * An ID that uniquely identifies the active content.
     *
     * <p>The ID is assigned by the system to distinguish different active contents.
     */
    public int getId() {
        return mId;
    }

    /**
     * The ID of the picture profile used to configure the content.
     */
    @NonNull
    public String getProfileId() {
        return mProfileId;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mId);
        dest.writeString(mProfileId);
    }
}
