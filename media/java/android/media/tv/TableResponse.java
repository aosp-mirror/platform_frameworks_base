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
import android.annotation.Nullable;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * A response for Table from broadcast signal.
 */
public final class TableResponse extends BroadcastInfoResponse implements Parcelable {
    private static final @TvInputManager.BroadcastInfoType int RESPONSE_TYPE =
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

    static TableResponse createFromParcelBody(Parcel in) {
        return new TableResponse(in);
    }

    public TableResponse(int requestId, int sequence, @ResponseResult int responseResult,
            @Nullable Uri tableUri, int version, int size) {
        super(RESPONSE_TYPE, requestId, sequence, responseResult);
        mTableUri = tableUri;
        mVersion = version;
        mSize = size;
    }

    TableResponse(Parcel source) {
        super(RESPONSE_TYPE, source);
        String uriString = source.readString();
        mTableUri = uriString == null ? null : Uri.parse(uriString);
        mVersion = source.readInt();
        mSize = source.readInt();
    }

    /**
     * Gets the URI in TvProvider database.
     */
    @Nullable
    public Uri getTableUri() {
        return mTableUri;
    }

    /**
     * Gets the version number of requested table. If it is null, value will be -1.
     * <p>The consistency of version numbers between request and response depends on
     * {@link BroadcastInfoRequest.RequestOption}. If the request has RequestOption value
     * REQUEST_OPTION_AUTO_UPDATE, then the response may be set to the latest version which may be
     * different from the version of the request. Otherwise, response with a different version from
     * its request will be considered invalid.
     */
    public int getVersion() {
        return mVersion;
    }

    /**
     * Gets the Size number of table.
     */
    public int getSize() {
        return mSize;
    }

    @Override
    public int describeContents() {
        return 0;
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
