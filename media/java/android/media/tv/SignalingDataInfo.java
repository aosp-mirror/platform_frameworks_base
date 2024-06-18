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

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.media.tv.flags.Flags;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Describes a metadata object of a {@link SignalingDataResponse}.
 */
@FlaggedApi(Flags.FLAG_TIAF_V_APIS)
public final class SignalingDataInfo implements Parcelable {
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

    private final @NonNull String mTable;
    private final @NonNull @SignalingDataRequest.SignalingMetadata String mSignalingDataType;
    private final int mVersion;
    private final int mGroup;
    private final @NonNull String mEncoding;

    /**
     * This value for {@link #getGroup()} denotes that there's no group associated with this
     * metadata.
     */
    public static final int LLS_NO_GROUP_ID = -1;

    /**
     * The encoding of the content is UTF-8. This is the default value.
     */
    public static final String CONTENT_ENCODING_UTF_8 = "UTF-8";

    /**
     *  A/344:2023-5 9.2.10 compliant string for when the encoding of the content is Base64.
     */
    public static final String CONTENT_ENCODING_BASE64 = "Base64";

    /**
     * @hide
     */
    @android.annotation.StringDef(prefix = "CONTENT_ENCODING_", value = {
            CONTENT_ENCODING_UTF_8,
            CONTENT_ENCODING_BASE64
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ContentEncoding {}

    public SignalingDataInfo(
            @NonNull String table,
            @NonNull String signalingDataType,
            int version,
            int group) {
        this.mTable = table;
        com.android.internal.util.AnnotationValidations.validate(NonNull.class, null, mTable);
        this.mSignalingDataType = signalingDataType;
        this.mVersion = version;
        this.mGroup = group;
        this.mEncoding = CONTENT_ENCODING_UTF_8;
    }

    public SignalingDataInfo(
            @NonNull String table,
            @NonNull String signalingDataType,
            int version,
            int group,
            @NonNull String encoding) {
        this.mTable = table;
        com.android.internal.util.AnnotationValidations.validate(NonNull.class, null, mTable);
        this.mSignalingDataType = signalingDataType;
        this.mVersion = version;
        this.mGroup = group;
        this.mEncoding = encoding;
        com.android.internal.util.AnnotationValidations.validate(NonNull.class, null, mEncoding);
    }

    /**
     * The signaling table data, represented as a XML, JSON or BASE64 string.
     *
     * <p> For more details on how this data is formatted refer to the ATSC standard
     * A/344:2023-5 9.2.10 - Query Signaling Data API.
     *
     * @return The signaling table data.
     */
    @NonNull
    public String getTable() {
        return mTable;
    }

    /**
     * Gets the signaling data type contained in this metadata object. This may be either a
     * LLS Metadata Object or a SLS Metadata Object name.
     *
     * <p>For more details on each type of metadata that can be requested, refer to the ATSC
     * standard A/344:2023-5 9.2.10 - Query Signaling Data API.
     *
     * @return the type of metadata in this metadata object
     */
    @NonNull
    public @SignalingDataRequest.SignalingMetadata String getSignalingDataType() {
        return mSignalingDataType;
    }

    /**
     * Gets the version of the signalling element. For LLS, this should be the
     * LLS_table_version. For SLS Metadata Objects, this should be metadataEnvelope@version.
     *
     * For more details on where this version comes from, refer to the ATSC 3.0
     * standard A/344:2023-5 9.2.10 - Query Signaling Data API.
     *
     * @return The version of the signalling element.
     */
    public int getVersion() {
        return mVersion;
    }

    /**
     * Gets the LLS group ID. Required for LLS Tables. For SLS Metadata Objects, this should be
     * {@link #LLS_NO_GROUP_ID}.
     *
     * @return the LLS group ID.
     */
    public int getGroup() {
        return mGroup;
    }

    /**
     * The content encoding of the data. This value defaults to {@link #CONTENT_ENCODING_UTF_8}.
     *
     * <p> Can be either {@link #CONTENT_ENCODING_BASE64} or {@link #CONTENT_ENCODING_UTF_8}.
     * @return The content encoding of the data.
     */
    @NonNull
    public @ContentEncoding String getEncoding() {
        return mEncoding;
    }

    @Override
    public void writeToParcel(@NonNull android.os.Parcel dest, int flags) {
        dest.writeString(mTable);
        dest.writeString(mSignalingDataType);
        dest.writeInt(mVersion);
        dest.writeInt(mGroup);
        dest.writeString(mEncoding);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    SignalingDataInfo(@NonNull android.os.Parcel in) {
        String table = in.readString();
        String metadataType = in.readString();
        int version = in.readInt();
        int group = in.readInt();
        String encoding = in.readString();

        this.mTable = table;
        com.android.internal.util.AnnotationValidations.validate(NonNull.class, null, mTable);
        this.mSignalingDataType = metadataType;
        com.android.internal.util.AnnotationValidations
                .validate(NonNull.class, null, mSignalingDataType);
        this.mVersion = version;
        this.mGroup = group;
        this.mEncoding = encoding;
        com.android.internal.util.AnnotationValidations.validate(NonNull.class, null, mEncoding);
    }
}
