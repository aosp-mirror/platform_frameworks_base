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

/**
 * Contains CMAS warning notification Type 1 elements for a {@link SmsCbMessage}.
 * Supported values for each element are defined in TIA-1149-0-1 (CMAS over CDMA) and
 * 3GPP TS 23.041 (for GSM/UMTS).
 *
 * {@hide}
 */
public class SmsCbCmasInfo implements Parcelable {

    // CMAS message class (in GSM/UMTS message identifier or CDMA service category).

    /** Presidential-level alert (Korean Public Alert System Class 0 message). */
    public static final int CMAS_CLASS_PRESIDENTIAL_LEVEL_ALERT = 0x00;

    /** Extreme threat to life and property (Korean Public Alert System Class 1 message). */
    public static final int CMAS_CLASS_EXTREME_THREAT = 0x01;

    /** Severe threat to life and property (Korean Public Alert System Class 1 message). */
    public static final int CMAS_CLASS_SEVERE_THREAT = 0x02;

    /** Child abduction emergency (AMBER Alert). */
    public static final int CMAS_CLASS_CHILD_ABDUCTION_EMERGENCY = 0x03;

    /** CMAS test message. */
    public static final int CMAS_CLASS_REQUIRED_MONTHLY_TEST = 0x04;

    /** CMAS exercise. */
    public static final int CMAS_CLASS_CMAS_EXERCISE = 0x05;

    /** CMAS category for operator defined use. */
    public static final int CMAS_CLASS_OPERATOR_DEFINED_USE = 0x06;

    /** CMAS category for warning types that are reserved for future extension. */
    public static final int CMAS_CLASS_UNKNOWN = -1;

    // CMAS alert category (in CDMA type 1 elements record).

    /** CMAS alert category: Geophysical including landslide. */
    public static final int CMAS_CATEGORY_GEO = 0x00;

    /** CMAS alert category: Meteorological including flood. */
    public static final int CMAS_CATEGORY_MET = 0x01;

    /** CMAS alert category: General emergency and public safety. */
    public static final int CMAS_CATEGORY_SAFETY = 0x02;

    /** CMAS alert category: Law enforcement, military, homeland/local/private security. */
    public static final int CMAS_CATEGORY_SECURITY = 0x03;

    /** CMAS alert category: Rescue and recovery. */
    public static final int CMAS_CATEGORY_RESCUE = 0x04;

    /** CMAS alert category: Fire suppression and rescue. */
    public static final int CMAS_CATEGORY_FIRE = 0x05;

    /** CMAS alert category: Medical and public health. */
    public static final int CMAS_CATEGORY_HEALTH = 0x06;

    /** CMAS alert category: Pollution and other environmental. */
    public static final int CMAS_CATEGORY_ENV = 0x07;

    /** CMAS alert category: Public and private transportation. */
    public static final int CMAS_CATEGORY_TRANSPORT = 0x08;

    /** CMAS alert category: Utility, telecom, other non-transport infrastructure. */
    public static final int CMAS_CATEGORY_INFRA = 0x09;

    /** CMAS alert category: Chem, bio, radiological, nuclear, high explosive threat or attack. */
    public static final int CMAS_CATEGORY_CBRNE = 0x0a;

    /** CMAS alert category: Other events. */
    public static final int CMAS_CATEGORY_OTHER = 0x0b;

    /**
     * CMAS alert category is unknown. The category is only available for CDMA broadcasts
     * containing a type 1 elements record, so GSM and UMTS broadcasts always return unknown.
     */
    public static final int CMAS_CATEGORY_UNKNOWN = -1;

    // CMAS response type (in CDMA type 1 elements record).

    /** CMAS response type: Take shelter in place. */
    public static final int CMAS_RESPONSE_TYPE_SHELTER = 0x00;

    /** CMAS response type: Evacuate (Relocate). */
    public static final int CMAS_RESPONSE_TYPE_EVACUATE = 0x01;

    /** CMAS response type: Make preparations. */
    public static final int CMAS_RESPONSE_TYPE_PREPARE = 0x02;

    /** CMAS response type: Execute a pre-planned activity. */
    public static final int CMAS_RESPONSE_TYPE_EXECUTE = 0x03;

    /** CMAS response type: Attend to information sources. */
    public static final int CMAS_RESPONSE_TYPE_MONITOR = 0x04;

    /** CMAS response type: Avoid hazard. */
    public static final int CMAS_RESPONSE_TYPE_AVOID = 0x05;

    /** CMAS response type: Evaluate the information in this message (not for public warnings). */
    public static final int CMAS_RESPONSE_TYPE_ASSESS = 0x06;

    /** CMAS response type: No action recommended. */
    public static final int CMAS_RESPONSE_TYPE_NONE = 0x07;

    /**
     * CMAS response type is unknown. The response type is only available for CDMA broadcasts
     * containing a type 1 elements record, so GSM and UMTS broadcasts always return unknown.
     */
    public static final int CMAS_RESPONSE_TYPE_UNKNOWN = -1;

    // 4-bit CMAS severity (in GSM/UMTS message identifier or CDMA type 1 elements record).

    /** CMAS severity type: Extraordinary threat to life or property. */
    public static final int CMAS_SEVERITY_EXTREME = 0x0;

    /** CMAS severity type: Significant threat to life or property. */
    public static final int CMAS_SEVERITY_SEVERE = 0x1;

    /**
     * CMAS alert severity is unknown. The severity is available for CDMA warning alerts
     * containing a type 1 elements record and for all GSM and UMTS alerts except for the
     * Presidential-level alert class (Korean Public Alert System Class 0).
     */
    public static final int CMAS_SEVERITY_UNKNOWN = -1;

    // CMAS urgency (in GSM/UMTS message identifier or CDMA type 1 elements record).

    /** CMAS urgency type: Responsive action should be taken immediately. */
    public static final int CMAS_URGENCY_IMMEDIATE = 0x0;

    /** CMAS urgency type: Responsive action should be taken within the next hour. */
    public static final int CMAS_URGENCY_EXPECTED = 0x1;

    /**
     * CMAS alert urgency is unknown. The urgency is available for CDMA warning alerts
     * containing a type 1 elements record and for all GSM and UMTS alerts except for the
     * Presidential-level alert class (Korean Public Alert System Class 0).
     */
    public static final int CMAS_URGENCY_UNKNOWN = -1;

    // CMAS certainty (in GSM/UMTS message identifier or CDMA type 1 elements record).

    /** CMAS certainty type: Determined to have occurred or to be ongoing. */
    public static final int CMAS_CERTAINTY_OBSERVED = 0x0;

    /** CMAS certainty type: Likely (probability > ~50%). */
    public static final int CMAS_CERTAINTY_LIKELY = 0x1;

    /**
     * CMAS alert certainty is unknown. The certainty is available for CDMA warning alerts
     * containing a type 1 elements record and for all GSM and UMTS alerts except for the
     * Presidential-level alert class (Korean Public Alert System Class 0).
     */
    public static final int CMAS_CERTAINTY_UNKNOWN = -1;

    /** CMAS message class. */
    private final int mMessageClass;

    /** CMAS category. */
    private final int mCategory;

    /** CMAS response type. */
    private final int mResponseType;

    /** CMAS severity. */
    private final int mSeverity;

    /** CMAS urgency. */
    private final int mUrgency;

    /** CMAS certainty. */
    private final int mCertainty;

    /** Create a new SmsCbCmasInfo object with the specified values. */
    public SmsCbCmasInfo(int messageClass, int category, int responseType, int severity,
            int urgency, int certainty) {
        mMessageClass = messageClass;
        mCategory = category;
        mResponseType = responseType;
        mSeverity = severity;
        mUrgency = urgency;
        mCertainty = certainty;
    }

    /** Create a new SmsCbCmasInfo object from a Parcel. */
    SmsCbCmasInfo(Parcel in) {
        mMessageClass = in.readInt();
        mCategory = in.readInt();
        mResponseType = in.readInt();
        mSeverity = in.readInt();
        mUrgency = in.readInt();
        mCertainty = in.readInt();
    }

    /**
     * Flatten this object into a Parcel.
     *
     * @param dest  The Parcel in which the object should be written.
     * @param flags Additional flags about how the object should be written (ignored).
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mMessageClass);
        dest.writeInt(mCategory);
        dest.writeInt(mResponseType);
        dest.writeInt(mSeverity);
        dest.writeInt(mUrgency);
        dest.writeInt(mCertainty);
    }

    /**
     * Returns the CMAS message class, e.g. {@link #CMAS_CLASS_PRESIDENTIAL_LEVEL_ALERT}.
     * @return one of the {@code CMAS_CLASS} values
     */
    public int getMessageClass() {
        return mMessageClass;
    }

    /**
     * Returns the CMAS category, e.g. {@link #CMAS_CATEGORY_GEO}.
     * @return one of the {@code CMAS_CATEGORY} values
     */
    public int getCategory() {
        return mCategory;
    }

    /**
     * Returns the CMAS response type, e.g. {@link #CMAS_RESPONSE_TYPE_SHELTER}.
     * @return one of the {@code CMAS_RESPONSE_TYPE} values
     */
    public int getResponseType() {
        return mResponseType;
    }

    /**
     * Returns the CMAS severity, e.g. {@link #CMAS_SEVERITY_EXTREME}.
     * @return one of the {@code CMAS_SEVERITY} values
     */
    public int getSeverity() {
        return mSeverity;
    }

    /**
     * Returns the CMAS urgency, e.g. {@link #CMAS_URGENCY_IMMEDIATE}.
     * @return one of the {@code CMAS_URGENCY} values
     */
    public int getUrgency() {
        return mUrgency;
    }

    /**
     * Returns the CMAS certainty, e.g. {@link #CMAS_CERTAINTY_OBSERVED}.
     * @return one of the {@code CMAS_CERTAINTY} values
     */
    public int getCertainty() {
        return mCertainty;
    }

    @Override
    public String toString() {
        return "SmsCbCmasInfo{messageClass=" + mMessageClass + ", category=" + mCategory
                + ", responseType=" + mResponseType + ", severity=" + mSeverity
                + ", urgency=" + mUrgency + ", certainty=" + mCertainty + '}';
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
    public static final Parcelable.Creator<SmsCbCmasInfo>
            CREATOR = new Parcelable.Creator<SmsCbCmasInfo>() {
        @Override
        public SmsCbCmasInfo createFromParcel(Parcel in) {
            return new SmsCbCmasInfo(in);
        }

        @Override
        public SmsCbCmasInfo[] newArray(int size) {
            return new SmsCbCmasInfo[size];
        }
    };
}
