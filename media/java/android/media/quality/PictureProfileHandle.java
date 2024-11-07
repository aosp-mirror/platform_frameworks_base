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
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

// TODO(b/337330263): Expose as public API after API review
/**
  * A type-safe handle to a picture profile, which represents a collection of parameters used to
  * configure picture processing hardware to enhance the quality of graphic buffers.
  * @hide
  */
@FlaggedApi(android.media.tv.flags.Flags.FLAG_MEDIA_QUALITY_FW)
public final class PictureProfileHandle implements Parcelable {
    private final long mId;

    @FlaggedApi(android.media.tv.flags.Flags.FLAG_MEDIA_QUALITY_FW)
    public PictureProfileHandle(long id) {
        mId = id;
    }

    @FlaggedApi(android.media.tv.flags.Flags.FLAG_MEDIA_QUALITY_FW)
    public long getId() {
        return mId;
    }

    @FlaggedApi(android.media.tv.flags.Flags.FLAG_MEDIA_QUALITY_FW)
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeLong(mId);
    }

    @FlaggedApi(android.media.tv.flags.Flags.FLAG_MEDIA_QUALITY_FW)
    @Override
    public int describeContents() {
        return 0;
    }

    @FlaggedApi(android.media.tv.flags.Flags.FLAG_MEDIA_QUALITY_FW)
    @NonNull
    public static final Creator<PictureProfileHandle> CREATOR =
            new Creator<PictureProfileHandle>() {
                @Override
                public PictureProfileHandle createFromParcel(Parcel in) {
                    return new PictureProfileHandle(in);
                }

                @Override
                public PictureProfileHandle[] newArray(int size) {
                    return new PictureProfileHandle[size];
                }
            };

    private PictureProfileHandle(@NonNull Parcel in) {
        mId = in.readLong();
    }
}
