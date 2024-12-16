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

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

/**
  * A type-safe handle to a sound profile.
  *
  * @hide
  */
public final class SoundProfileHandle implements Parcelable {
    public static final @NonNull SoundProfileHandle NONE = new SoundProfileHandle(-1000);

    private final long mId;

    /** @hide */
    public SoundProfileHandle(long id) {
        mId = id;
    }

    /** @hide */
    public long getId() {
        return mId;
    }

    /** @hide */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeLong(mId);
    }

    /** @hide */
    @Override
    public int describeContents() {
        return 0;
    }

    /** @hide */
    public static final @NonNull Creator<SoundProfileHandle> CREATOR =
            new Creator<SoundProfileHandle>() {
                @Override
                public SoundProfileHandle createFromParcel(Parcel in) {
                    return new SoundProfileHandle(in);
                }

                @Override
                public SoundProfileHandle[] newArray(int size) {
                    return new SoundProfileHandle[size];
                }
            };

    private SoundProfileHandle(@NonNull Parcel in) {
        mId = in.readLong();
    }
}
