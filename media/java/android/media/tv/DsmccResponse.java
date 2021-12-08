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
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;

/** @hide */
public class DsmccResponse extends BroadcastInfoResponse implements Parcelable {
    public static final int responseType = BroadcastInfoType.DSMCC;

    public static final @NonNull Parcelable.Creator<DsmccResponse> CREATOR =
            new Parcelable.Creator<DsmccResponse>() {
                @Override
                public DsmccResponse createFromParcel(Parcel source) {
                    source.readInt();
                    return createFromParcelBody(source);
                }

                @Override
                public DsmccResponse[] newArray(int size) {
                    return new DsmccResponse[size];
                }
            };

    private final ParcelFileDescriptor mFile;

    public static DsmccResponse createFromParcelBody(Parcel in) {
        return new DsmccResponse(in);
    }

    public DsmccResponse(int requestId, int sequence, int responseResult,
            ParcelFileDescriptor file) {
        super(responseType, requestId, sequence, responseResult);
        mFile = file;
    }

    protected DsmccResponse(Parcel source) {
        super(responseType, source);
        mFile = source.readFileDescriptor();
    }

    public ParcelFileDescriptor getFile() {
        return mFile;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        mFile.writeToParcel(dest, flags);
    }
}
