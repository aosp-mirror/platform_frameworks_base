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

import java.io.IOException;

/**
 * Class used to pipe transceive result from the NFC service.
 *
 * @hide
 */
public final class TransceiveResult implements Parcelable {
    public static final int RESULT_SUCCESS = 0;
    public static final int RESULT_FAILURE = 1;
    public static final int RESULT_TAGLOST = 2;
    public static final int RESULT_EXCEEDED_LENGTH = 3;

    final int mResult;
    final byte[] mResponseData;

    public TransceiveResult(final int result, final byte[] data) {
        mResult = result;
        mResponseData = data;
    }

    public byte[] getResponseOrThrow() throws IOException {
        switch (mResult) {
            case RESULT_SUCCESS:
                return mResponseData;
            case RESULT_TAGLOST:
                throw new TagLostException("Tag was lost.");
            case RESULT_EXCEEDED_LENGTH:
                throw new IOException("Transceive length exceeds supported maximum");
            default:
                throw new IOException("Transceive failed");
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mResult);
        if (mResult == RESULT_SUCCESS) {
            dest.writeInt(mResponseData.length);
            dest.writeByteArray(mResponseData);
        }
    }

    public static final @android.annotation.NonNull Parcelable.Creator<TransceiveResult> CREATOR =
            new Parcelable.Creator<TransceiveResult>() {
        @Override
        public TransceiveResult createFromParcel(Parcel in) {
            int result = in.readInt();
            byte[] responseData;

            if (result == RESULT_SUCCESS) {
                int responseLength = in.readInt();
                responseData = new byte[responseLength];
                in.readByteArray(responseData);
            } else {
                responseData = null;
            }
            return new TransceiveResult(result, responseData);
        }

        @Override
        public TransceiveResult[] newArray(int size) {
            return new TransceiveResult[size];
        }
    };

}
