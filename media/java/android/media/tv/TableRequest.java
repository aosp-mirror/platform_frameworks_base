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

/** @hide */
public final class TableRequest extends BroadcastInfoRequest implements Parcelable {
    public static final @TvInputManager.BroadcastInfoType int requestType =
            TvInputManager.BROADCAST_INFO_TYPE_TABLE;

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

    public static TableRequest createFromParcelBody(Parcel in) {
        return new TableRequest(in);
    }

    public TableRequest(int requestId, @RequestOption int option, int tableId,
            @TableName int tableName, int version) {
        super(requestType, requestId, option);
        mTableId = tableId;
        mTableName = tableName;
        mVersion = version;
    }

    protected TableRequest(Parcel source) {
        super(requestType, source);
        mTableId = source.readInt();
        mTableName = source.readInt();
        mVersion = source.readInt();
    }

    public int getTableId() {
        return mTableId;
    }

    public @TableName int getTableName() {
        return mTableName;
    }

    public int getVersion() {
        return mVersion;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeInt(mTableId);
        dest.writeInt(mTableName);
        dest.writeInt(mVersion);
    }
}
