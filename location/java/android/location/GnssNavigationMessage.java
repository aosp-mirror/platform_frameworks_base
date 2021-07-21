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

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.TestApi;
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

    /**
     * The type of the GNSS Navigation Message
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({TYPE_UNKNOWN, TYPE_GPS_L1CA, TYPE_GPS_L2CNAV, TYPE_GPS_L5CNAV, TYPE_GPS_CNAV2,
            TYPE_SBS, TYPE_GLO_L1CA, TYPE_QZS_L1CA, TYPE_BDS_D1, TYPE_BDS_D2, TYPE_BDS_CNAV1,
            TYPE_BDS_CNAV2, TYPE_GAL_I, TYPE_GAL_F, TYPE_IRN_L5CA})
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
    /** SBAS message contained in the structure. */
    public static final int TYPE_SBS = 0x0201;
    /** Glonass L1 CA message contained in the structure. */
    public static final int TYPE_GLO_L1CA = 0x0301;
    /** QZSS L1 C/A message contained in the structure. */
    public static final int TYPE_QZS_L1CA = 0x0401;
    /** Beidou D1 message contained in the structure. */
    public static final int TYPE_BDS_D1 = 0x0501;
    /** Beidou D2 message contained in the structure. */
    public static final int TYPE_BDS_D2 = 0x0502;
    /** Beidou CNAV1 message contained in the structure. */
    public static final int TYPE_BDS_CNAV1 = 0x0503;
    /** Beidou CNAV2 message contained in the structure. */
    public static final int TYPE_BDS_CNAV2 = 0x0504;
    /** Galileo I/NAV message contained in the structure. */
    public static final int TYPE_GAL_I = 0x0601;
    /** Galileo F/NAV message contained in the structure. */
    public static final int TYPE_GAL_F = 0x0602;
    /** IRNSS L5 C/A message contained in the structure. */
    public static final int TYPE_IRN_L5CA = 0x0701;

    /**
     * The status of the GNSS Navigation Message
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({STATUS_UNKNOWN, STATUS_PARITY_PASSED, STATUS_PARITY_REBUILT})
    public @interface GnssNavigationMessageStatus {}

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

    /**
     * Used for receiving GNSS satellite Navigation Messages from the GNSS engine.
     *
     * <p>You can implement this interface and call
     * {@link LocationManager#registerGnssNavigationMessageCallback}.
     */
    public static abstract class Callback {
        /**
         * The status of GNSS Navigation Message event.
         * @deprecated Do not use.
         * @hide
         */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef({STATUS_NOT_SUPPORTED, STATUS_READY, STATUS_LOCATION_DISABLED})
        public @interface GnssNavigationMessageStatus {}

        /**
         * The system does not support tracking of GNSS Navigation Messages.
         *
         * This status will not change in the future.
         *
         * @deprecated Do not use.
         */
        @Deprecated
        public static final int STATUS_NOT_SUPPORTED = 0;

        /**
         * GNSS Navigation Messages are successfully being tracked, it will receive updates once
         * they are available.
         *
         * @deprecated Do not use.
         */
        @Deprecated
        public static final int STATUS_READY = 1;

        /**
         * GNSS provider or Location is disabled, updated will not be received until they are
         * enabled.
         *
         * @deprecated Do not use.
         */
        @Deprecated
        public static final int STATUS_LOCATION_DISABLED = 2;

        /**
         * Returns the latest collected GNSS Navigation Message.
         */
        public void onGnssNavigationMessageReceived(GnssNavigationMessage event) {}

        /**
         * Returns the latest status of the GNSS Navigation Messages sub-system.
         *
         * @deprecated Do not rely on this callback. From Android S onwards this callback will be
         * invoked once with {@link #STATUS_READY} in all cases for backwards compatibility, and
         * then never invoked again. Use LocationManager APIs if you need to determine if
         * GNSS navigation messages are supported or if location is off, etc...
         */
        @Deprecated
        public void onStatusChanged(@GnssNavigationMessageStatus int status) {}
    }

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
            case TYPE_SBS:
                return "SBS";
            case TYPE_GLO_L1CA:
                return "Glonass L1 C/A";
            case TYPE_QZS_L1CA:
                return "QZSS L1 C/A";
            case TYPE_BDS_D1:
                return "Beidou D1";
            case TYPE_BDS_D2:
                return "Beidou D2";
            case TYPE_BDS_CNAV1:
                return "Beidou CNAV1";
            case TYPE_BDS_CNAV2:
                return "Beidou CNAV2";
            case TYPE_GAL_I:
                return "Galileo I";
            case TYPE_GAL_F:
                return "Galileo F";
            case TYPE_IRN_L5CA:
                return "IRNSS L5 C/A";
            default:
                return "<Invalid:" + mType + ">";
        }
    }

    /**
     * Gets the satellite ID.
     *
     * <p>Range varies by constellation.  See definition at {@code GnssStatus#getSvid(int)}
     */
    @IntRange(from = 1, to = 200)
    public int getSvid() {
        return mSvid;
    }

    /**
     * Sets the satellite ID.
     * @hide
     */
    @TestApi
    public void setSvid(@IntRange(from = 1, to = 200) int value) {
        mSvid = value;
    }

    /**
     * Gets the Message identifier.
     *
     * <p>This provides an index to help with complete Navigation Message assembly. Similar
     * identifiers within the data bits themselves often supplement this information, in ways even
     * more specific to each message type; see the relevant satellite constellation ICDs for
     * details.
     *
     * <ul>
     * <li> For GPS L1 C/A subframe 4 and 5, this value corresponds to the 'frame id' of the
     * navigation message, in the range of 1-25 (Subframe 1, 2, 3 does not contain a 'frame id' and
     * this value can be set to -1.)</li>
     * <li> For Glonass L1 C/A, this refers to the frame ID, in the range of 1-5.</li>
     * <li> For BeiDou D1, this refers to the frame number in the range of 1-24</li>
     * <li> For Beidou D2, this refers to the frame number, in the range of 1-120</li>
     * <li> For Galileo F/NAV nominal frame structure, this refers to the subframe number, in the
     * range of 1-12</li>
     * <li> For Galileo I/NAV nominal frame structure, this refers to the subframe number in the
     * range of 1-24</li>
     * <li> For SBAS and Beidou CNAV2, this is unused and can be set to -1.</li>
     * <li> For QZSS L1 C/A subframe 4 and 5, this value corresponds to the 'frame id' of the
     * navigation message, in the range of 1-25 (Subframe 1, 2, 3 does not contain a 'frame id' and
     * this value can be set to -1.)</li>
     * <li> For Beidou CNAV1 this refers to the page type number in the range of 1-63.</li>
     * <li> For IRNSS L5 C/A subframe 3 and 4, this value corresponds to the Message Id of the
     * navigation message, in the range of 1-63. (Subframe 1 and 2 does not contain a message type
     * id and this value can be set to -1.)</li>
     * </ul>
     */
    @IntRange(from = -1, to = 120)
    public int getMessageId() {
        return mMessageId;
    }

    /**
     * Sets the Message Identifier.
     * @hide
     */
    @TestApi
    public void setMessageId(@IntRange(from = -1, to = 120) int value) {
        mMessageId = value;
    }

    /**
     * Gets the sub-message identifier, relevant to the {@link #getType()} of the message.
     *
     * <ul>
     * <li> For GPS L1 C/A, BeiDou D1 &amp; BeiDou D2, the submessage id corresponds to the subframe
     * number of the navigation message, in the range of 1-5.</li>
     * <li>For Glonass L1 C/A, this refers to the String number, in the range from 1-15</li>
     * <li>For Galileo F/NAV, this refers to the page type in the range 1-6</li>
     * <li>For Galileo I/NAV, this refers to the word type in the range 1-10+</li>
     * <li>For Galileo in particular, the type information embedded within the data bits may be even
     * more useful in interpretation, than the nominal page and word types provided in this
     * field.</li>
     * <li> For SBAS, the submessage id corresponds to the message type, in the range 1-63.</li>
     * <li> For Beidou CNAV1, the submessage id corresponds to the subframe number of the
     * navigation message, in the range of 1-3.</li>
     * <li> For Beidou CNAV2, the submessage id corresponds to the message type, in the range
     * 1-63.</li>
     * <li> For IRNSS L5 C/A, the submessage id corresponds to the subframe number of the
     * navigation message, in the range of 1-4.</li>
     * </ul>
     */
    @IntRange(from = 1)
    public int getSubmessageId() {
        return mSubmessageId;
    }

    /**
     * Sets the Sub-message identifier.
     * @hide
     */
    @TestApi
    public void setSubmessageId(@IntRange(from = 1) int value) {
        mSubmessageId = value;
    }

    /**
     * Gets the data of the reported GPS message.
     *
     * <p>The bytes (or words) specified using big endian format (MSB first).
     *
     * <ul>
     * <li>For GPS L1 C/A, IRNSS L5 C/A, Beidou D1 &amp; Beidou D2, each subframe contains 10
     * 30-bit words. Each word (30 bits) should be fit into the last 30 bits in a 4-byte word (skip
     * B31 and B32), with MSB first, for a total of 40 bytes, covering a time period of 6, 6, and
     * 0.6 seconds, respectively.</li>
     * <li>For Glonass L1 C/A, each string contains 85 data bits, including the checksum.  These
     * bits should be fit into 11 bytes, with MSB first (skip B86-B88), covering a time period of 2
     * seconds.</li>
     * <li>For Galileo F/NAV, each word consists of 238-bit (sync &amp; tail symbols excluded). Each
     * word should be fit into 30-bytes, with MSB first (skip B239, B240), covering a time period of
     * 10 seconds.</li>
     * <li>For Galileo I/NAV, each page contains 2 page parts, even and odd, with a total of 2x114 =
     * 228 bits, (sync &amp; tail excluded) that should be fit into 29 bytes, with MSB first (skip
     * B229-B232).</li>
     * <li>For SBAS, each block consists of 250 data bits, that should be fit into 32 bytes.  MSB
     * first (skip B251-B256).</li>
     * <li>For Beidou CNAV1, subframe #1 consists of 14 data bits, that should be fit into 2
     * bytes. MSB first (skip B15-B16).  subframe #2 consists of 600 bits that should be fit into
     * 75 bytes. subframe #3 consists of 264 data bits that should be fit into 33 bytes.</li>
     * <li>For Beidou CNAV2, each subframe consists of 288 data bits, that should be fit into 36
     * bytes.</li>
     * </ul>
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
    @GnssNavigationMessageStatus
    public int getStatus() {
        return mStatus;
    }

    /**
     * Sets the status of the navigation message.
     * @hide
     */
    @TestApi
    public void setStatus(@GnssNavigationMessageStatus int value) {
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

    public static final @android.annotation.NonNull Creator<GnssNavigationMessage> CREATOR =
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
