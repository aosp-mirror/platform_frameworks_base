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
import android.os.SharedMemory;

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
    private final byte[] mTableByteArray;
    private final SharedMemory mTableSharedMemory;

    static TableResponse createFromParcelBody(Parcel in) {
        return new TableResponse(in);
    }

    /**
     * Constructs a TableResponse with a table URI.
     *
     * @param requestId The ID is used to associate the response with the request.
     * @param sequence The sequence number which indicates the order of related responses.
     * @param responseResult The result for the response. It's one of {@link #RESPONSE_RESULT_OK},
     *                       {@link #RESPONSE_RESULT_CANCEL}, {@link #RESPONSE_RESULT_ERROR}.
     * @param tableUri The URI of the table in the database.
     * @param version The version number of requested table.
     * @param size The Size number of table in bytes.
     *
     * @deprecated use {@link Builder} instead.
     */
    @Deprecated
    public TableResponse(int requestId, int sequence, @ResponseResult int responseResult,
            @Nullable Uri tableUri, int version, int size) {
        super(RESPONSE_TYPE, requestId, sequence, responseResult);
        mVersion = version;
        mSize = size;
        mTableUri = tableUri;
        mTableByteArray = null;
        mTableSharedMemory = null;
    }

    /**
     * Constructs a TableResponse.
     *
     * @param requestId The ID is used to associate the response with the request.
     * @param sequence The sequence number which indicates the order of related responses.
     * @param responseResult The result for the response. It's one of {@link #RESPONSE_RESULT_OK},
     *                       {@link #RESPONSE_RESULT_CANCEL}, {@link #RESPONSE_RESULT_ERROR}.
     * @param tableSharedMemory The shared memory which stores the table. The table size can be
     *                          large so using a shared memory optimizes the data
     *                          communication between the table data source and the receiver. The
     *                          structure syntax of the table depends on the table name in
     *                          {@link TableRequest#getTableName()} and the corresponding standard.
     * @param version The version number of requested table.
     * @param size The Size number of table in bytes.
     * @param tableUri The URI of the table in the database.
     * @param tableByteArray The byte array which stores the table in bytes. The structure and
     *                       syntax of the table depends on the table name in
     * @param tableSharedMemory The shared memory which stores the table. The table size can be
     *                          large so using a shared memory optimizes the data
     *                          communication between the table data source and the receiver. The
     *                          structure syntax of the table depends on the table name in
     *                          {@link TableRequest#getTableName()} and the corresponding standard.
     */
    private TableResponse(int requestId, int sequence, @ResponseResult int responseResult,
            int version, int size, Uri tableUri, byte[] tableByteArray,
            SharedMemory tableSharedMemory) {
        super(RESPONSE_TYPE, requestId, sequence, responseResult);
        mVersion = version;
        mSize = size;
        mTableUri = tableUri;
        mTableByteArray = tableByteArray;
        mTableSharedMemory = tableSharedMemory;
    }

    /**
     * Builder for {@link TableResponse}.
     */
    public static final class Builder {
        private final int mRequestId;
        private final int mSequence;
        @ResponseResult
        private final int mResponseResult;
        private final int mVersion;
        private final int mSize;
        private Uri mTableUri;
        private byte[] mTableByteArray;
        private SharedMemory mTableSharedMemory;

        /**
         * Constructs a Builder object of {@link TableResponse}.
         *
         * @param requestId The ID is used to associate the response with the request.
         * @param sequence The sequence number which indicates the order of related responses.
         * @param responseResult The result for the response. It's one of
         *                       {@link #RESPONSE_RESULT_OK}, {@link #RESPONSE_RESULT_CANCEL},
         *                       {@link #RESPONSE_RESULT_ERROR}.
         * @param version The version number of requested table.
         * @param size The Size number of table in bytes.
         */
        public Builder(int requestId, int sequence, @ResponseResult int responseResult, int version,
                int size) {
            mRequestId = requestId;
            mSequence = sequence;
            mResponseResult = responseResult;
            mVersion = version;
            mSize = size;
        }

        /**
         * Sets table URI.
         *
         * <p>For a single builder instance, at most one of table URI, table byte array, and table
         * shared memory can be set. If more than one are set, only the last call takes precedence
         * and others are reset to {@code null}.
         *
         * @param uri The URI of the table.
         */
        @NonNull
        public Builder setTableUri(@NonNull Uri uri) {
            mTableUri = uri;
            mTableByteArray = null;
            mTableSharedMemory = null;
            return this;
        }

        /**
         * Sets table byte array.
         *
         * <p>For a single builder instance, at most one of table URI, table byte array, and table
         * shared memory can be set. If more than one are set, only the last call takes precedence
         * and others are reset to {@code null}.
         *
         * @param bytes The byte array which stores the table in bytes. The structure and
         *              syntax of the table depends on the table name in
         *              {@link TableRequest#getTableName()} and the corresponding standard.
         */
        @NonNull
        public Builder setTableByteArray(@NonNull byte[] bytes) {
            mTableByteArray = bytes;
            mTableUri = null;
            mTableSharedMemory = null;
            return this;
        }


        /**
         * Sets table shared memory.
         *
         * <p>For a single builder instance, at most one of table URI, table byte array, and table
         * shared memory can be set. If more than one are set, only the last call takes precedence
         * and others are reset to {@code null}.
         *
         * @param sharedMemory The shared memory which stores the table. The table size can be
         *                     large so using a shared memory optimizes the data
         *                     communication between the table data source and the receiver. The
         *                     structure syntax of the table depends on the table name in
         *                     {@link TableRequest#getTableName()} and the corresponding standard.
         */
        @NonNull
        public Builder setTableSharedMemory(@NonNull SharedMemory sharedMemory) {
            mTableSharedMemory = sharedMemory;
            mTableUri = null;
            mTableByteArray = null;
            return this;
        }

        /**
         * Builds a {@link TableResponse} object.
         */
        @NonNull
        public TableResponse build() {
            return new TableResponse(mRequestId, mSequence, mResponseResult, mVersion, mSize,
                    mTableUri, mTableByteArray, mTableSharedMemory);
        }
    }

    TableResponse(Parcel source) {
        super(RESPONSE_TYPE, source);
        String uriString = source.readString();
        mTableUri = uriString == null ? null : Uri.parse(uriString);
        mVersion = source.readInt();
        mSize = source.readInt();
        int arrayLength = source.readInt();
        if (arrayLength >= 0) {
            mTableByteArray = new byte[arrayLength];
            source.readByteArray(mTableByteArray);
        } else {
            mTableByteArray = null;
        }
        mTableSharedMemory = (SharedMemory) source.readTypedObject(SharedMemory.CREATOR);
    }

    /**
     * Gets the URI in TvProvider database.
     */
    @Nullable
    public Uri getTableUri() {
        return mTableUri;
    }

    /**
     * Gets the data of the table as a byte array.
     *
     * @return the table data as a byte array, or {@code null} if the data is not stored as a byte
     *         array.
     */
    @Nullable
    public byte[] getTableByteArray() {
        return mTableByteArray;
    }

    /**
     * Gets the data of the table as a {@link SharedMemory} object.
     *
     * <p> This data lives in a {@link SharedMemory} instance because of the potentially large
     * amount of data needed to store the table. This optimizes the data communication between the
     * table data source and the receiver.
     *
     * @return the table data as a {@link SharedMemory} object, or {@code null} if the data is not
     *         stored in shared memory.
     *
     * @see SharedMemory#map(int, int, int)
     */
    @Nullable
    public SharedMemory getTableSharedMemory() {
        return mTableSharedMemory;
    }

    /**
     * Gets the version number of requested table. If it is null, value will be -1.
     * <p>The consistency of version numbers between request and response depends on
     * {@link BroadcastInfoRequest#getOption()}. If the request has RequestOption value
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
        if (mTableByteArray != null) {
            dest.writeInt(mTableByteArray.length);
            dest.writeByteArray(mTableByteArray);
        } else {
            dest.writeInt(-1);
        }
        dest.writeTypedObject(mTableSharedMemory, flags);
    }
}
