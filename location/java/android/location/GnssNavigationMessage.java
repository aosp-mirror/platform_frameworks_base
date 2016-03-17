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

import android.annotation.TestApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.security.InvalidParameterException;

/**
 * A class containing a GNSS satellite Navigation Message.
 */
public final class GnssNavigationMessage implements Parcelable {

    private static final byte[] EMPTY_ARRAY = new byte[0];

    /** The type of the GPS Clock. */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({TYPE_UNKNOWN, TYPE_GPS_L1CA, TYPE_GPS_L2CNAV, TYPE_GPS_L5CNAV, TYPE_GPS_CNAV2,
        TYPE_GLO_L1CA, TYPE_BDS_D1, TYPE_BDS_D2, TYPE_GAL_I, TYPE_GAL_F})
    public @interface GnssNavigationMessageType {}

    // The following enumerations must be in sync with the values declared in gps.h

    /** Message type unknown */
    public static final int TYPE_UNKNOWN = 0;
    /** GPS L1 C/A message contained in the structure.  */
    public static final int TYPE_GPS_L1CA = 0x0101;
    /** GPS L2-CNAV message contained in the structure. */
    public static final int TYPE_GPS_L2CNAV = 0x0102;
    /** GPS L5-CNAV message contained in the structure. */
    public static final int TYPE_GPS_L5CNAV = 0x0103;
    /** GPS CNAV-2 message contained in the structure. */
    public static final int TYPE_GPS_CNAV2 = 0x0104;
    /** Glonass L1 CA message contained in the structure. */
    public static final int TYPE_GLO_L1CA = 0x0301;
    /** Beidou D1 message contained in the structure. */
    public static final int TYPE_BDS_D1 = 0x0501;
    /** Beidou D2 message contained in the structure. */
    public static final int TYPE_BDS_D2 = 0x0502;
    /** Galileo I/NAV message contained in the structure. */
    public static final int TYPE_GAL_I = 0x0601;
    /** Galileo F/NAV message contained in the structure. */
    public static final int TYPE_GAL_F = 0x0602;

    /**
     * The Navigation Message Status is 'unknown'.
     */
    public static final int STATUS_UNKNOWN = 0;

    /**
     * The Navigation Message was received without any parity error in its navigation words.
     */
    public static final int STATUS_PARITY_PASSED = (1<<0);

    /**
     * The Navigation Message was received with words that failed parity check, but the receiver was
     * able to correct those words.
     */
    public static final int STATUS_PARITY_REBUILT = (1<<1);

    // End enumerations in sync with gps.h

    private int mType;
    private int mSvid;
    private int mMessageId;
    private int mSubmessageId;
    private byte[] mData;
    private int mStatus;

    /**
     * @hide
     */
    @TestApi
    public GnssNavigationMessage() {
        initialize();
    }

    /**
     * Sets all contents to the values stored in the provided object.
     * @hide
     */
    @TestApi
    public void set(GnssNavigationMessage navigationMessage) {
        mType = navigationMessage.mType;
        mSvid = navigationMessage.mSvid;
        mMessageId = navigationMessage.mMessageId;
        mSubmessageId = navigationMessage.mSubmessageId;
        mData = navigationMessage.mData;
        mStatus = navigationMessage.mStatus;
    }

    /**
     * Resets all the contents to its original state.
     * @hide
     */
    @TestApi
    public void reset() {
        initialize();
    }

    /**
     * Gets the type of the navigation message contained in the object.
     */
    @GnssNavigationMessageType
    public int getType() {
        return mType;
    }

    /**
     * Sets the type of the navigation message.
     * @hide
     */
    @TestApi
    public void setType(@GnssNavigationMessageType int value) {
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
            case TYPE_GPS_L1CA:
                return "GPS L1 C/A";
            case TYPE_GPS_L2CNAV:
                return "GPS L2-CNAV";
            case TYPE_GPS_L5CNAV:
                return "GPS L5-CNAV";
            case TYPE_GPS_CNAV2:
                return "GPS CNAV2";
            case TYPE_GLO_L1CA:
                return "Glonass L1 C/A";
            case TYPE_BDS_D1:
                return "Beidou D1";
            case TYPE_BDS_D2:
                return "Beidou D2";
            case TYPE_GAL_I:
                return "Galileo I";
            case TYPE_GAL_F:
                return "Galileo F";
            default:
                return "<Invalid:" + mType + ">";
        }
    }

    /**
     * Gets the Pseudo-random number.
     * Range: [1, 32].
     */
    public int getSvid() {
        return mSvid;
    }

    /**
     * Sets the Pseud-random number.
     * @hide
     */
    @TestApi
    public void setSvid(int value) {
        mSvid = value;
    }

    /**
     * Gets the Message Identifier.
     * It provides an index so the complete Navigation Message can be assembled. i.e. for L1 C/A
     * subframe 4 and 5, this value corresponds to the 'frame id' of the navigation message.
     * Subframe 1, 2, 3 does not contain a 'frame id' and this might be reported as -1.
     */
    public int getMessageId() {
        return mMessageId;
    }

    /**
     * Sets the Message Identifier.
     * @hide
     */
    @TestApi
    public void setMessageId(int value) {
        mMessageId = value;
    }

    /**
     * Gets the Sub-message Identifier.
     * If required by {@link #getType()}, this value contains a sub-index within the current message
     * (or frame) that is being transmitted. i.e. for L1 C/A the sub-message identifier corresponds
     * to the sub-frame Id of the navigation message.
     */
    public int getSubmessageId() {
        return mSubmessageId;
    }

    /**
     * Sets the Sub-message identifier.
     * @hide
     */
    @TestApi
    public void setSubmessageId(int value) {
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
     * @hide
     */
    @TestApi
    public void setData(byte[] value) {
        if (value == null) {
            throw new InvalidParameterException("Data must be a non-null array");
        }

        mData = value;
    }

    /**
     * Gets the Status of the navigation message contained in the object.
     */
    public int getStatus() {
        return mStatus;
    }

    /**
     * Sets the status of the navigation message.
     * @hide
     */
    @TestApi
    public void setStatus(int value) {
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

    public static final Creator<GnssNavigationMessage> CREATOR =
            new Creator<GnssNavigationMessage>() {
        @Override
        public GnssNavigationMessage createFromParcel(Parcel parcel) {
            GnssNavigationMessage navigationMessage = new GnssNavigationMessage();

            navigationMessage.setType(parcel.readInt());
            navigationMessage.setSvid(parcel.readInt());
            navigationMessage.setMessageId(parcel.readInt());
            navigationMessage.setSubmessageId(parcel.readInt());
            int dataLength = parcel.readInt();
            byte[] data = new byte[dataLength];
            parcel.readByteArray(data);
            navigationMessage.setData(data);
            navigationMessage.setStatus(parcel.readInt());

            return navigationMessage;
        }

        @Override
        public GnssNavigationMessage[] newArray(int size) {
            return new GnssNavigationMessage[size];
        }
    };

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(mType);
        parcel.writeInt(mSvid);
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
        StringBuilder builder = new StringBuilder("GnssNavigationMessage:\n");

        builder.append(String.format(format, "Type", getTypeString()));
        builder.append(String.format(format, "Svid", mSvid));
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
        mSvid = 0;
        mMessageId = -1;
        mSubmessageId = -1;
        mData = EMPTY_ARRAY;
        mStatus = STATUS_UNKNOWN;
    }
}
