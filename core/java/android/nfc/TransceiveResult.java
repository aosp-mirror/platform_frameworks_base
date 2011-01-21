/*
 * Copyright (C) 2011, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.nfc;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Class used to pipe transceive result from the NFC service.
 *
 * @hide
 */
public final class TransceiveResult implements Parcelable {
    private final boolean mTagLost;
    private final boolean mSuccess;
    private final byte[] mResponseData;

    public TransceiveResult(final boolean success, final boolean tagIsLost,
            final byte[] data) {
        mSuccess = success;
        mTagLost = tagIsLost;
        mResponseData = data;
    }

    public boolean isSuccessful() {
        return mSuccess;
    }

    public boolean isTagLost() {
        return mTagLost;
    }

    public byte[] getResponseData() {
        return mResponseData;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mSuccess ? 1 : 0);
        dest.writeInt(mTagLost ? 1 : 0);
        if (mSuccess) {
            dest.writeInt(mResponseData.length);
            dest.writeByteArray(mResponseData);
        }
    }

    public static final Parcelable.Creator<TransceiveResult> CREATOR =
            new Parcelable.Creator<TransceiveResult>() {
        @Override
        public TransceiveResult createFromParcel(Parcel in) {
            boolean success = (in.readInt() == 1) ? true : false;
            boolean tagLost = (in.readInt() == 1) ? true : false;
            byte[] responseData;

            if (success) {
                int responseLength = in.readInt();
                responseData = new byte[responseLength];
                in.readByteArray(responseData);
            } else {
                responseData = null;
            }
            return new TransceiveResult(success, tagLost, responseData);
        }

        @Override
        public TransceiveResult[] newArray(int size) {
            return new TransceiveResult[size];
        }
    };

}
