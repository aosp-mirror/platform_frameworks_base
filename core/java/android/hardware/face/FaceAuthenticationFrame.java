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

package android.hardware.face;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Data model for a frame captured during face authentication.
 *
 * @hide
 */
public final class FaceAuthenticationFrame implements Parcelable {
    @NonNull private final FaceDataFrame mData;

    /**
     * Data model for a frame captured during face authentication.
     *
     * @param data Information about the current frame.
     */
    public FaceAuthenticationFrame(@NonNull FaceDataFrame data) {
        mData = data;
    }

    /**
     * @return Information about the current frame.
     */
    @NonNull
    public FaceDataFrame getData() {
        return mData;
    }

    private FaceAuthenticationFrame(@NonNull Parcel source) {
        mData = source.readParcelable(FaceDataFrame.class.getClassLoader(), android.hardware.face.FaceDataFrame.class);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(mData, flags);
    }

    public static final Creator<FaceAuthenticationFrame> CREATOR =
            new Creator<FaceAuthenticationFrame>() {

        @Override
        public FaceAuthenticationFrame createFromParcel(Parcel source) {
            return new FaceAuthenticationFrame(source);
        }

        @Override
        public FaceAuthenticationFrame[] newArray(int size) {
            return new FaceAuthenticationFrame[size];
        }
    };
}
