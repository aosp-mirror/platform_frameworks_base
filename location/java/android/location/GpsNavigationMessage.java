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
 * limitations under the License
 */

package android.location;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.security.InvalidParameterException;

/**
 * A class containing a GPS satellite Navigation Message.
 *
 * @hide
 */
@SystemApi
public class GpsNavigationMessage implements Parcelable {

    private static final byte[] EMPTY_ARRAY = new byte[0];

    // The following enumerations must be in sync with the values declared in gps.h

    /**
     * The type of the navigation message is not available or unknown.
     */
    public static final byte TYPE_UNKNOWN = 0;

    /**
     * The Navigation Message is of type L1 C/A.
     */
    public static final byte TYPE_L1CA = 1;

    /**
     * The Navigation Message is of type L1-CNAV.
     */
    public static final byte TYPE_L2CNAV = 2;

    /**
     * The Navigation Message is of type L5-CNAV.
     */
    public static final byte TYPE_L5CNAV = 3;

    /**
     * The Navigation Message is of type CNAV-2.
     */
    public static final byte TYPE_CNAV2 = 4;

    /**
     * The Navigation Message Status is 'unknown'.
     */
    public static final short STATUS_UNKNOWN = 0;

    /**
     * The Navigation Message was received without any parity error in its navigation words.
     */
    public static final short STATUS_PARITY_PASSED = (1<<0);

    /**
     * The Navigation Message was received with words that failed parity check, but the receiver was
     * able to correct those words.
     */
    public static final short STATUS_PARITY_REBUILT = (1<<1);

    // End enumerations in sync with gps.h

    private byte mType;
    private byte mPrn;
    private short mMessageId;
    private short mSubmessageId;
    private byte[] mData;
    private short mStatus;

    GpsNavigationMessage() {
        initialize();
    }

    /**
     * Sets all contents to the values stored in the provided object.
     */
    public void set(GpsNavigationMessage navigationMessage) {
        mType = navigationMessage.mType;
        mPrn = navigationMessage.mPrn;
        mMessageId = navigationMessage.mMessageId;
        mSubmessageId = navigationMessage.mSubmessageId;
        mData = navigationMessage.mData;
        mStatus = navigationMessage.mStatus;
    }

    /**
     * Resets all the contents to its original state.
     */
    public void reset() {
        initialize();
    }

    /**
     * Gets the type of the navigation message contained in the object.
     */
    public byte getType() {
        return mType;
    }

    /**
     * Sets the type of the navigation message.
     */
    public void setType(byte value) {
        mType = value;
    }

    /**
     * Gets a string representation of the 'type'.
     * For internal and logging use only.
     */
    private String getTypeString() {
        switch (mType) {
            case TYPE_UNKNOWN:
                return "Unknown";
            case TYPE_L1CA:
                return "L1 C/A";
            case TYPE_L2CNAV:
                return "L2-CNAV";
            case TYPE_L5CNAV:
                return "L5-CNAV";
            case TYPE_CNAV2:
                return "CNAV-2";
            default:
                return "<Invalid:" + mType + ">";
        }
    }

    /**
     * Gets the Pseudo-random number.
     * Range: [1, 32].
     */
    public byte getPrn() {
        return mPrn;
    }

    /**
     * Sets the Pseud-random number.
     */
    public void setPrn(byte value) {
        mPrn = value;
    }

    /**
     * Gets the Message Identifier.
     * It provides an index so the complete Navigation Message can be assembled. i.e. for L1 C/A
     * subframe 4 and 5, this value corresponds to the 'frame id' of the navigation message.
     * Subframe 1, 2, 3 does not contain a 'frame id' and this might be reported as -1.
     */
    public short getMessageId() {
        return mMessageId;
    }

    /**
     * Sets the Message Identifier.
     */
    public void setMessageId(short value) {
        mMessageId = value;
    }

    /**
     * Gets the Sub-message Identifier.
     * If required by {@link #getType()}, this value contains a sub-index within the current message
     * (or frame) that is being transmitted. i.e. for L1 C/A the sub-message identifier corresponds
     * to the sub-frame Id of the navigation message.
     */
    public short getSubmessageId() {
        return mSubmessageId;
    }

    /**
     * Sets the Sub-message identifier.
     */
    public void setSubmessageId(short value) {
        mSubmessageId = value;
    }

    /**
     * Gets the data associated with the Navigation Message.
     * The bytes (or words) specified using big endian format (MSB first).
     */
    @NonNull
    public byte[] getData() {
        return mData;
    }

    /**
     * Sets the data associated with the Navigation Message.
     */
    public void setData(byte[] value) {
        if (value == null) {
            throw new InvalidParameterException("Data must be a non-null array");
        }

        mData = value;
    }

    /**
     * Gets the Status of the navigation message contained in the object.
     */
    public short getStatus() {
        return mStatus;
    }

    /**
     * Sets the status of the navigation message.
     */
    public void setStatus(short value) {
        mStatus = value;
    }

    /**
     * Gets a string representation of the 'status'.
     * For internal and logging use only.
     */
    private String getStatusString() {
        switch (mStatus) {
            case STATUS_UNKNOWN:
                return "Unknown";
            case STATUS_PARITY_PASSED:
                return "ParityPassed";
            case STATUS_PARITY_REBUILT:
                return "ParityRebuilt";
            default:
                return "<Invalid:" + mStatus + ">";
        }
    }

    public static final @android.annotation.NonNull Creator<GpsNavigationMessage> CREATOR =
            new Creator<GpsNavigationMessage>() {
        @Override
        public GpsNavigationMessage createFromParcel(Parcel parcel) {
            GpsNavigationMessage navigationMessage = new GpsNavigationMessage();

            navigationMessage.setType(parcel.readByte());
            navigationMessage.setPrn(parcel.readByte());
            navigationMessage.setMessageId((short) parcel.readInt());
            navigationMessage.setSubmessageId((short) parcel.readInt());

            int dataLength = parcel.readInt();
            byte[] data = new byte[dataLength];
            parcel.readByteArray(data);
            navigationMessage.setData(data);

            int status = parcel.readInt();
            navigationMessage.setStatus((short) status);

            return navigationMessage;
        }

        @Override
        public GpsNavigationMessage[] newArray(int size) {
            return new GpsNavigationMessage[size];
        }
    };

    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeByte(mType);
        parcel.writeByte(mPrn);
        parcel.writeInt(mMessageId);
        parcel.writeInt(mSubmessageId);
        parcel.writeInt(mData.length);
        parcel.writeByteArray(mData);
        parcel.writeInt(mStatus);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        final String format = "   %-15s = %s\n";
        StringBuilder builder = new StringBuilder("GpsNavigationMessage:\n");

        builder.append(String.format(format, "Type", getTypeString()));
        builder.append(String.format(format, "Prn", mPrn));
        builder.append(String.format(format, "Status", getStatusString()));
        builder.append(String.format(format, "MessageId", mMessageId));
        builder.append(String.format(format, "SubmessageId", mSubmessageId));

        builder.append(String.format(format, "Data", "{"));
        String prefix = "        ";
        for(byte value : mData) {
            builder.append(prefix);
            builder.append(value);
            prefix = ", ";
        }
        builder.append(" }");

        return builder.toString();
    }

    private void initialize() {
        mType = TYPE_UNKNOWN;
        mPrn = 0;
        mMessageId = -1;
        mSubmessageId = -1;
        mData = EMPTY_ARRAY;
        mStatus = STATUS_UNKNOWN;
    }
}
