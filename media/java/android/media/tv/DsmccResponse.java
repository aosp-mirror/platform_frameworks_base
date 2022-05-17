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
import android.annotation.StringDef;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * A response for DSM-CC from broadcast signal.
 */
public final class DsmccResponse extends BroadcastInfoResponse implements Parcelable {
    private static final @TvInputManager.BroadcastInfoType int RESPONSE_TYPE =
            TvInputManager.BROADCAST_INFO_TYPE_DSMCC;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @StringDef(prefix = "BIOP_MESSAGE_TYPE_", value = {
            BIOP_MESSAGE_TYPE_DIRECTORY,
            BIOP_MESSAGE_TYPE_FILE,
            BIOP_MESSAGE_TYPE_STREAM,
            BIOP_MESSAGE_TYPE_SERVICE_GATEWAY,

    })
    public @interface BiopMessageType {}

    /** Broadcast Inter-ORB Protocol (BIOP) message types */
    /** BIOP directory message */
    public static final String BIOP_MESSAGE_TYPE_DIRECTORY = "directory";
    /** BIOP file message */
    public static final String BIOP_MESSAGE_TYPE_FILE = "file";
    /** BIOP stream message */
    public static final String BIOP_MESSAGE_TYPE_STREAM = "stream";
    /** BIOP service gateway message */
    public static final String BIOP_MESSAGE_TYPE_SERVICE_GATEWAY = "service_gateway";

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

    private final @BiopMessageType String mBiopMessageType;
    private final ParcelFileDescriptor mFileDescriptor;
    private final List<String> mChildList;
    private final int[] mEventIds;
    private final String[] mEventNames;

    static DsmccResponse createFromParcelBody(Parcel in) {
        return new DsmccResponse(in);
    }

    /**
     * Constructs a BIOP file message response.
     */
    public DsmccResponse(int requestId, int sequence, @ResponseResult int responseResult,
            @Nullable ParcelFileDescriptor file) {
        super(RESPONSE_TYPE, requestId, sequence, responseResult);
        mBiopMessageType = BIOP_MESSAGE_TYPE_FILE;
        mFileDescriptor = file;
        mChildList = null;
        mEventIds = null;
        mEventNames = null;
    }

    /**
     * Constructs a BIOP service gateway or directory message response.
     */
    public DsmccResponse(int requestId, int sequence, @ResponseResult int responseResult,
            boolean isServiceGateway, @Nullable List<String> childList) {
        super(RESPONSE_TYPE, requestId, sequence, responseResult);
        if (isServiceGateway) {
            mBiopMessageType = BIOP_MESSAGE_TYPE_SERVICE_GATEWAY;
        } else {
            mBiopMessageType = BIOP_MESSAGE_TYPE_DIRECTORY;
        }
        mFileDescriptor = null;
        mChildList = childList;
        mEventIds = null;
        mEventNames = null;
    }

    /**
     * Constructs a BIOP stream message response.
     *
     * <p>The current stream message response does not support other stream messages types than
     * stream event message type.
     */
    public DsmccResponse(int requestId, int sequence, @ResponseResult int responseResult,
            @Nullable int[] eventIds, @Nullable String[] eventNames) {
        super(RESPONSE_TYPE, requestId, sequence, responseResult);
        mBiopMessageType = BIOP_MESSAGE_TYPE_STREAM;
        mFileDescriptor = null;
        mChildList = null;
        if (!((eventIds != null && eventNames != null && eventIds.length == eventNames.length)
                || (eventIds == null && eventNames == null))) {
            throw new IllegalStateException("The size of eventIds and eventNames must be equal");
        }
        mEventIds = eventIds;
        mEventNames = eventNames;
    }

    private DsmccResponse(@NonNull Parcel source) {
        super(RESPONSE_TYPE, source);

        mBiopMessageType = source.readString();
        switch (mBiopMessageType) {
            case BIOP_MESSAGE_TYPE_SERVICE_GATEWAY:
            case BIOP_MESSAGE_TYPE_DIRECTORY:
                int childNum = source.readInt();
                if (childNum > 0) {
                    mChildList = new ArrayList<>();
                    for (int i = 0; i < childNum; i++) {
                        mChildList.add(source.readString());
                    }
                } else
                    mChildList = null;
                mFileDescriptor = null;
                mEventIds = null;
                mEventNames = null;
                break;
            case BIOP_MESSAGE_TYPE_FILE:
                mFileDescriptor = source.readFileDescriptor();
                mChildList = null;
                mEventIds = null;
                mEventNames = null;
                break;
            case BIOP_MESSAGE_TYPE_STREAM:
                int eventNum = source.readInt();
                if (eventNum > 0) {
                    mEventIds = new int[eventNum];
                    mEventNames = new String[eventNum];
                    for (int i = 0; i < eventNum; i++) {
                        mEventIds[i] = source.readInt();
                        mEventNames[i] = source.readString();
                    }
                } else {
                    mEventIds = null;
                    mEventNames = null;
                }
                mChildList = null;
                mFileDescriptor = null;
                break;
            default:
                throw new IllegalStateException("unexpected BIOP message type");
        }
    }

    /**
     * Returns the BIOP message type.
     */
    @NonNull
    public @BiopMessageType String getBiopMessageType() {
        return mBiopMessageType;
    }

    /**
     * Returns the file descriptor for a given file message response.
     */
    @NonNull
    public ParcelFileDescriptor getFile() {
        if (!mBiopMessageType.equals(BIOP_MESSAGE_TYPE_FILE)) {
            throw new IllegalStateException("Not file object");
        }
        return mFileDescriptor;
    }

    /**
     * Returns a list of subobject names for the given service gateway or directory message
     * response.
     */
    @NonNull
    public List<String> getChildList() {
        if (!mBiopMessageType.equals(BIOP_MESSAGE_TYPE_DIRECTORY)
                && !mBiopMessageType.equals(BIOP_MESSAGE_TYPE_SERVICE_GATEWAY)) {
            throw new IllegalStateException("Not directory object");
        }
        return mChildList != null ? new ArrayList<String>(mChildList) : new ArrayList<String>();
    }

    /**
     * Returns all event IDs carried in a given stream message response.
     */
    @NonNull
    public int[] getStreamEventIds() {
        if (!mBiopMessageType.equals(BIOP_MESSAGE_TYPE_STREAM)) {
            throw new IllegalStateException("Not stream event object");
        }
        return mEventIds != null ? mEventIds : new int[0];
    }

    /**
     * Returns all event names carried in a given stream message response.
     */
    @NonNull
    public String[] getStreamEventNames() {
        if (!mBiopMessageType.equals(BIOP_MESSAGE_TYPE_STREAM)) {
            throw new IllegalStateException("Not stream event object");
        }
        return mEventNames != null ? mEventNames : new String[0];
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeString(mBiopMessageType);
        switch (mBiopMessageType) {
            case BIOP_MESSAGE_TYPE_SERVICE_GATEWAY:
            case BIOP_MESSAGE_TYPE_DIRECTORY:
                if (mChildList != null && mChildList.size() > 0) {
                    dest.writeInt(mChildList.size());
                    for (String child : mChildList) {
                        dest.writeString(child);
                    }
                } else
                    dest.writeInt(0);
                break;
            case BIOP_MESSAGE_TYPE_FILE:
                dest.writeFileDescriptor(mFileDescriptor.getFileDescriptor());
                break;
            case BIOP_MESSAGE_TYPE_STREAM:
                if (mEventIds != null && mEventIds.length > 0) {
                    dest.writeInt(mEventIds.length);
                    for (int i = 0; i < mEventIds.length; i++) {
                        dest.writeInt(mEventIds[i]);
                        dest.writeString(mEventNames[i]);
                    }
                } else
                    dest.writeInt(0);
                break;
            default:
                throw new IllegalStateException("unexpected BIOP message type");
        }
    }
}
