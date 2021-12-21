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
import android.os.Parcelable;
import android.net.Uri;

/** @hide */
public final class TableResponse extends BroadcastInfoResponse implements Parcelable {
    public static final @TvInputManager.BroadcastInfoType int responseType =
            TvInputManager.BROADCAST_INFO_TYPE_TABLE;

    public static final @NonNull Parcelable.Creator<TableResponse> CREATOR =
            new Parcelable.Creator<TableResponse>() {
                @Override
                public TableResponse createFromParcel(Parcel source) {
                    source.readInt();
                    return createFromParcelBody(source);
                }

                @Override
                public TableResponse[] newArray(int size) {
                    return new TableResponse[size];
                }
            };

    private final Uri mTableUri;
    private final int mVersion;
    private final int mSize;

    public static TableResponse createFromParcelBody(Parcel in) {
        return new TableResponse(in);
    }

    public TableResponse(int requestId, int sequence, @ResponseResult int responseResult,
            Uri tableUri, int version, int size) {
        super(responseType, requestId, sequence, responseResult);
        mTableUri = tableUri;
        mVersion = version;
        mSize = size;
    }

    protected TableResponse(Parcel source) {
        super(responseType, source);
        String uriString = source.readString();
        mTableUri = uriString == null ? null : Uri.parse(uriString);
        mVersion = source.readInt();
        mSize = source.readInt();
    }

    public Uri getTableUri() {
        return mTableUri;
    }

    public int getVersion() {
        return mVersion;
    }

    public int getSize() {
        return mSize;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        String uriString = mTableUri == null ? null : mTableUri.toString();
        dest.writeString(uriString);
        dest.writeInt(mVersion);
        dest.writeInt(mSize);
    }
}
