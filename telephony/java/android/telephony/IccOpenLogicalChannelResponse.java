/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.telephony;

import android.os.Parcel;
import android.os.Parcelable;


/**
 * Response to the {@link TelephonyManager#iccOpenLogicalChannel} command.
 */
public class IccOpenLogicalChannelResponse implements Parcelable {
    /**
     * Indicates an invalid channel.
     */
    public static final int INVALID_CHANNEL = -1;

    /**
     * Possible status values returned by open channel command.
     *
     * STATUS_NO_ERROR: Open channel command returned successfully.
     * STATUS_MISSING_RESOURCE: No logical channels available.
     * STATUS_NO_SUCH_ELEMENT: AID not found on UICC.
     * STATUS_UNKNOWN_ERROR: Unknown error in open channel command.
     */
    public static final int STATUS_NO_ERROR = 1;
    public static final int STATUS_MISSING_RESOURCE = 2;
    public static final int STATUS_NO_SUCH_ELEMENT = 3;
    public static final int STATUS_UNKNOWN_ERROR = 4;

    private final int mChannel;
    private final int mStatus;
    private final byte[] mSelectResponse;

    /**
     * Constructor.
     *
     * @hide
     */
    public IccOpenLogicalChannelResponse(int channel, int status, byte[] selectResponse) {
        mChannel = channel;
        mStatus = status;
        mSelectResponse = selectResponse;
    }

    /**
     * Construct a IccOpenLogicalChannelResponse from a given parcel.
     */
    private IccOpenLogicalChannelResponse(Parcel in) {
        mChannel = in.readInt();
        mStatus = in.readInt();
        int arrayLength = in.readInt();
        if (arrayLength > 0) {
            mSelectResponse = new byte[arrayLength];
            in.readByteArray(mSelectResponse);
        } else {
            mSelectResponse = null;
        }
    }

    /**
     * @return the channel id.
     */
    public int getChannel() {
        return mChannel;
    }

    /**
     * @return the status of the command.
     */
    public int getStatus() {
        return mStatus;
    }

    /**
     * @return the select response.
     */
    public byte[] getSelectResponse() {
        return mSelectResponse;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mChannel);
        out.writeInt(mStatus);
        if (mSelectResponse != null && mSelectResponse.length > 0) {
            out.writeInt(mSelectResponse.length);
            out.writeByteArray(mSelectResponse);
        } else {
            out.writeInt(0);
        }
    }

    public static final @android.annotation.NonNull Parcelable.Creator<IccOpenLogicalChannelResponse> CREATOR
             = new Parcelable.Creator<IccOpenLogicalChannelResponse>() {

        @Override
        public IccOpenLogicalChannelResponse createFromParcel(Parcel in) {
             return new IccOpenLogicalChannelResponse(in);
         }

         public IccOpenLogicalChannelResponse[] newArray(int size) {
             return new IccOpenLogicalChannelResponse[size];
         }
     };

    @Override
    public String toString() {
        return "Channel: " + mChannel + " Status: " + mStatus;
    }
}
