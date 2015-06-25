/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.security.keymaster;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * @hide
 */
public class KeymasterBlob implements Parcelable {
    public byte[] blob;

    public KeymasterBlob(byte[] blob) {
        this.blob = blob;
    }
    public static final Parcelable.Creator<KeymasterBlob> CREATOR = new
            Parcelable.Creator<KeymasterBlob>() {
                public KeymasterBlob createFromParcel(Parcel in) {
                    return new KeymasterBlob(in);
                }

                public KeymasterBlob[] newArray(int length) {
                    return new KeymasterBlob[length];
                }
            };

    protected KeymasterBlob(Parcel in) {
        blob = in.createByteArray();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeByteArray(blob);
    }
}
