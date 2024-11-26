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
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

/**
  * A type-safe handle to a picture profile used to apply picture processing to a SurfaceControl.
  *
  * A picture profile represents a collection of parameters used to configure picture processing
  * to enhance the quality of graphic buffers.
  *
  * @hide
  */
@SystemApi
@FlaggedApi(android.media.tv.flags.Flags.FLAG_APPLY_PICTURE_PROFILES)
public final class PictureProfileHandle implements Parcelable {
    public static final @NonNull PictureProfileHandle NONE = new PictureProfileHandle(0);

    private final long mId;

    /** @hide */
    public PictureProfileHandle(long id) {
        mId = id;
    }

    /** @hide */
    @SystemApi
    @FlaggedApi(android.media.tv.flags.Flags.FLAG_APPLY_PICTURE_PROFILES)
    public long getId() {
        return mId;
    }

    /** @hide */
    @SystemApi
    @Override
    @FlaggedApi(android.media.tv.flags.Flags.FLAG_APPLY_PICTURE_PROFILES)
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeLong(mId);
    }

    /** @hide */
    @SystemApi
    @Override
    @FlaggedApi(android.media.tv.flags.Flags.FLAG_APPLY_PICTURE_PROFILES)
    public int describeContents() {
        return 0;
    }

    /** @hide */
    @SystemApi
    @FlaggedApi(android.media.tv.flags.Flags.FLAG_APPLY_PICTURE_PROFILES)
    public static final @NonNull Creator<PictureProfileHandle> CREATOR =
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
