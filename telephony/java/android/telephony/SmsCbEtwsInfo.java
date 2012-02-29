/*
 * Copyright (C) 2012 The Android Open Source Project
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
import android.text.format.Time;

import com.android.internal.telephony.IccUtils;

import java.util.Arrays;

/**
 * Contains information elements for a GSM or UMTS ETWS warning notification.
 * Supported values for each element are defined in 3GPP TS 23.041.
 *
 * {@hide}
 */
public class SmsCbEtwsInfo implements Parcelable {

    /** ETWS warning type for earthquake. */
    public static final int ETWS_WARNING_TYPE_EARTHQUAKE = 0x00;

    /** ETWS warning type for tsunami. */
    public static final int ETWS_WARNING_TYPE_TSUNAMI = 0x01;

    /** ETWS warning type for earthquake and tsunami. */
    public static final int ETWS_WARNING_TYPE_EARTHQUAKE_AND_TSUNAMI = 0x02;

    /** ETWS warning type for test messages. */
    public static final int ETWS_WARNING_TYPE_TEST_MESSAGE = 0x03;

    /** ETWS warning type for other emergency types. */
    public static final int ETWS_WARNING_TYPE_OTHER_EMERGENCY = 0x04;

    /** Unknown ETWS warning type. */
    public static final int ETWS_WARNING_TYPE_UNKNOWN = -1;

    /** One of the ETWS warning type constants defined in this class. */
    private final int mWarningType;

    /** Whether or not to activate the emergency user alert tone and vibration. */
    private final boolean mEmergencyUserAlert;

    /** Whether or not to activate a popup alert. */
    private final boolean mActivatePopup;

    /**
     * 50-byte security information (ETWS primary notification for GSM only). As of Release 10,
     * 3GPP TS 23.041 states that the UE shall ignore the ETWS primary notification timestamp
     * and digital signature if received. Therefore it is treated as a raw byte array and
     * parceled with the broadcast intent if present, but the timestamp is only computed if an
     * application asks for the individual components.
     */
    private final byte[] mWarningSecurityInformation;

    /** Create a new SmsCbEtwsInfo object with the specified values. */
    public SmsCbEtwsInfo(int warningType, boolean emergencyUserAlert, boolean activatePopup,
            byte[] warningSecurityInformation) {
        mWarningType = warningType;
        mEmergencyUserAlert = emergencyUserAlert;
        mActivatePopup = activatePopup;
        mWarningSecurityInformation = warningSecurityInformation;
    }

    /** Create a new SmsCbEtwsInfo object from a Parcel. */
    SmsCbEtwsInfo(Parcel in) {
        mWarningType = in.readInt();
        mEmergencyUserAlert = (in.readInt() != 0);
        mActivatePopup = (in.readInt() != 0);
        mWarningSecurityInformation = in.createByteArray();
    }

    /**
     * Flatten this object into a Parcel.
     *
     * @param dest  The Parcel in which the object should be written.
     * @param flags Additional flags about how the object should be written (ignored).
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mWarningType);
        dest.writeInt(mEmergencyUserAlert ? 1 : 0);
        dest.writeInt(mActivatePopup ? 1 : 0);
        dest.writeByteArray(mWarningSecurityInformation);
    }

    /**
     * Returns the ETWS warning type.
     * @return a warning type such as {@link #ETWS_WARNING_TYPE_EARTHQUAKE}
     */
    public int getWarningType() {
        return mWarningType;
    }

    /**
     * Returns the ETWS emergency user alert flag.
     * @return true to notify terminal to activate emergency user alert; false otherwise
     */
    public boolean isEmergencyUserAlert() {
        return mEmergencyUserAlert;
    }

    /**
     * Returns the ETWS activate popup flag.
     * @return true to notify terminal to activate display popup; false otherwise
     */
    public boolean isPopupAlert() {
        return mActivatePopup;
    }

    /**
     * Returns the Warning-Security-Information timestamp (GSM primary notifications only).
     * As of Release 10, 3GPP TS 23.041 states that the UE shall ignore this value if received.
     * @return a UTC timestamp in System.currentTimeMillis() format, or 0 if not present
     */
    public long getPrimaryNotificationTimestamp() {
        if (mWarningSecurityInformation == null || mWarningSecurityInformation.length < 7) {
            return 0;
        }

        int year = IccUtils.gsmBcdByteToInt(mWarningSecurityInformation[0]);
        int month = IccUtils.gsmBcdByteToInt(mWarningSecurityInformation[1]);
        int day = IccUtils.gsmBcdByteToInt(mWarningSecurityInformation[2]);
        int hour = IccUtils.gsmBcdByteToInt(mWarningSecurityInformation[3]);
        int minute = IccUtils.gsmBcdByteToInt(mWarningSecurityInformation[4]);
        int second = IccUtils.gsmBcdByteToInt(mWarningSecurityInformation[5]);

        // For the timezone, the most significant bit of the
        // least significant nibble is the sign byte
        // (meaning the max range of this field is 79 quarter-hours,
        // which is more than enough)

        byte tzByte = mWarningSecurityInformation[6];

        // Mask out sign bit.
        int timezoneOffset = IccUtils.gsmBcdByteToInt((byte) (tzByte & (~0x08)));

        timezoneOffset = ((tzByte & 0x08) == 0) ? timezoneOffset : -timezoneOffset;

        Time time = new Time(Time.TIMEZONE_UTC);

        // We only need to support years above 2000.
        time.year = year + 2000;
        time.month = month - 1;
        time.monthDay = day;
        time.hour = hour;
        time.minute = minute;
        time.second = second;

        // Timezone offset is in quarter hours.
        return time.toMillis(true) - (long) (timezoneOffset * 15 * 60 * 1000);
    }

    /**
     * Returns the digital signature (GSM primary notifications only). As of Release 10,
     * 3GPP TS 23.041 states that the UE shall ignore this value if received.
     * @return a byte array containing a copy of the primary notification digital signature
     */
    public byte[] getPrimaryNotificationSignature() {
        if (mWarningSecurityInformation == null || mWarningSecurityInformation.length < 50) {
            return null;
        }
        return Arrays.copyOfRange(mWarningSecurityInformation, 7, 50);
    }

    @Override
    public String toString() {
        return "SmsCbEtwsInfo{warningType=" + mWarningType + ", emergencyUserAlert="
                + mEmergencyUserAlert + ", activatePopup=" + mActivatePopup + '}';
    }

    /**
     * Describe the kinds of special objects contained in the marshalled representation.
     * @return a bitmask indicating this Parcelable contains no special objects
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /** Creator for unparcelling objects. */
    public static final Creator<SmsCbEtwsInfo> CREATOR = new Creator<SmsCbEtwsInfo>() {
        public SmsCbEtwsInfo createFromParcel(Parcel in) {
            return new SmsCbEtwsInfo(in);
        }

        public SmsCbEtwsInfo[] newArray(int size) {
            return new SmsCbEtwsInfo[size];
        }
    };
}
