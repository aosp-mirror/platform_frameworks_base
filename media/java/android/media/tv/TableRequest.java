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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A request for Table from broadcast signal.
 */
public final class TableRequest extends BroadcastInfoRequest implements Parcelable {
    private static final @TvInputManager.BroadcastInfoType int REQUEST_TYPE =
            TvInputManager.BROADCAST_INFO_TYPE_TABLE;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({TABLE_NAME_PAT, TABLE_NAME_PMT})
    public @interface TableName {}

    public static final int TABLE_NAME_PAT = 0;
    public static final int TABLE_NAME_PMT = 1;

    public static final @NonNull Parcelable.Creator<TableRequest> CREATOR =
            new Parcelable.Creator<TableRequest>() {
                @Override
                public TableRequest createFromParcel(Parcel source) {
                    source.readInt();
                    return createFromParcelBody(source);
                }

                @Override
                public TableRequest[] newArray(int size) {
                    return new TableRequest[size];
                }
            };

    private final int mTableId;
    private final @TableName int mTableName;
    private final int mVersion;

    static TableRequest createFromParcelBody(Parcel in) {
        return new TableRequest(in);
    }

    public TableRequest(int requestId, @RequestOption int option, int tableId,
            @TableName int tableName, int version) {
        super(REQUEST_TYPE, requestId, option);
        mTableId = tableId;
        mTableName = tableName;
        mVersion = version;
    }

    TableRequest(Parcel source) {
        super(REQUEST_TYPE, source);
        mTableId = source.readInt();
        mTableName = source.readInt();
        mVersion = source.readInt();
    }

    /**
     * Gets the ID of requested table.
     */
    public int getTableId() {
        return mTableId;
    }

    /**
     * Gets the name of requested table.
     */
    public @TableName int getTableName() {
        return mTableName;
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

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeInt(mTableId);
        dest.writeInt(mTableName);
        dest.writeInt(mVersion);
    }
}
