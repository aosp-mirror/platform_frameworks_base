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

package android.media.tv;

import android.annotation.NonNull;
import android.os.Parcelable;

/** @hide */
public class SignalingDataInfo implements Parcelable {
    public static final @NonNull Parcelable.Creator<SignalingDataInfo> CREATOR =
            new Parcelable.Creator<SignalingDataInfo>() {
                @Override
                public SignalingDataInfo[] newArray(int size) {
                    return new SignalingDataInfo[size];
                }

                @Override
                public SignalingDataInfo createFromParcel(@NonNull android.os.Parcel in) {
                    return new SignalingDataInfo(in);
                }
            };

    private int mTableId;
    private @NonNull String mTable;
    private int mMetadataType;
    private int mVersion;
    private int mGroup;
    private @NonNull String mEncoding;

    public SignalingDataInfo(
            int tableId,
            @NonNull String table,
            int metadataType,
            int version,
            int group,
            @NonNull String encoding) {
        this.mTableId = tableId;
        this.mTable = table;
        com.android.internal.util.AnnotationValidations.validate(NonNull.class, null, mTable);
        this.mMetadataType = metadataType;
        this.mVersion = version;
        this.mGroup = group;
        this.mEncoding = encoding;
        com.android.internal.util.AnnotationValidations.validate(NonNull.class, null, mEncoding);
    }

    public int getTableId() {
        return mTableId;
    }

    public @NonNull String getTable() {
        return mTable;
    }

    public int getMetadataType() {
        return mMetadataType;
    }

    public int getVersion() {
        return mVersion;
    }

    public int getGroup() {
        return mGroup;
    }

    public @NonNull String getEncoding() {
        return mEncoding;
    }

    @Override
    public void writeToParcel(@NonNull android.os.Parcel dest, int flags) {
        dest.writeInt(mTableId);
        dest.writeString(mTable);
        dest.writeInt(mMetadataType);
        dest.writeInt(mVersion);
        dest.writeInt(mGroup);
        dest.writeString(mEncoding);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    SignalingDataInfo(@NonNull android.os.Parcel in) {
        int tableId = in.readInt();
        String table = in.readString();
        int metadataType = in.readInt();
        int version = in.readInt();
        int group = in.readInt();
        String encoding = in.readString();

        this.mTableId = tableId;
        this.mTable = table;
        com.android.internal.util.AnnotationValidations.validate(NonNull.class, null, mTable);
        this.mMetadataType = metadataType;
        this.mVersion = version;
        this.mGroup = group;
        this.mEncoding = encoding;
        com.android.internal.util.AnnotationValidations.validate(NonNull.class, null, mEncoding);
    }
}
