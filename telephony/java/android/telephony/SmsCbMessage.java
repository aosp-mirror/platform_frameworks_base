/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.content.ContentValues;
import android.database.Cursor;
import android.os.Parcel;
import android.os.Parcelable;
import android.provider.Telephony.CellBroadcasts;

import com.android.internal.telephony.CbGeoUtils;
import com.android.internal.telephony.CbGeoUtils.Geometry;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

/**
 * Parcelable object containing a received cell broadcast message. There are four different types
 * of Cell Broadcast messages:
 *
 * <ul>
 * <li>opt-in informational broadcasts, e.g. news, weather, stock quotes, sports scores</li>
 * <li>cell information messages, broadcast on channel 50, indicating the current cell name for
 *  roaming purposes (required to display on the idle screen in Brazil)</li>
 * <li>emergency broadcasts for the Japanese Earthquake and Tsunami Warning System (ETWS)</li>
 * <li>emergency broadcasts for the American Commercial Mobile Alert Service (CMAS)</li>
 * </ul>
 *
 * <p>There are also four different CB message formats: GSM, ETWS Primary Notification (GSM only),
 * UMTS, and CDMA. Some fields are only applicable for some message formats. Other fields were
 * unified under a common name, avoiding some names, such as "Message Identifier", that refer to
 * two completely different concepts in 3GPP and CDMA.
 *
 * <p>The GSM/UMTS Message Identifier field is available via {@link #getServiceCategory}, the name
 * of the equivalent field in CDMA. In both cases the service category is a 16-bit value, but 3GPP
 * and 3GPP2 have completely different meanings for the respective values. For ETWS and CMAS, the
 * application should
 *
 * <p>The CDMA Message Identifier field is available via {@link #getSerialNumber}, which is used
 * to detect the receipt of a duplicate message to be discarded. In CDMA, the message ID is
 * unique to the current PLMN. In GSM/UMTS, there is a 16-bit serial number containing a 2-bit
 * Geographical Scope field which indicates whether the 10-bit message code and 4-bit update number
 * are considered unique to the PLMN, to the current cell, or to the current Location Area (or
 * Service Area in UMTS). The relevant values are concatenated into a single String which will be
 * unique if the messages are not duplicates.
 *
 * <p>The SMS dispatcher does not detect duplicate messages. However, it does concatenate the
 * pages of a GSM multi-page cell broadcast into a single SmsCbMessage object.
 *
 * <p>Interested applications with {@code RECEIVE_SMS_PERMISSION} can register to receive
 * {@code SMS_CB_RECEIVED_ACTION} broadcast intents for incoming non-emergency broadcasts.
 * Only system applications such as the CellBroadcastReceiver may receive notifications for
 * emergency broadcasts (ETWS and CMAS). This is intended to prevent any potential for delays or
 * interference with the immediate display of the alert message and playing of the alert sound and
 * vibration pattern, which could be caused by poorly written or malicious non-system code.
 *
 * @hide
 */
@SystemApi
public final class SmsCbMessage implements Parcelable {

    /** @hide */
    public static final String LOG_TAG = "SMSCB";

    /** Cell wide geographical scope with immediate display (GSM/UMTS only). */
    public static final int GEOGRAPHICAL_SCOPE_CELL_WIDE_IMMEDIATE = 0;

    /** PLMN wide geographical scope (GSM/UMTS and all CDMA broadcasts). */
    public static final int GEOGRAPHICAL_SCOPE_PLMN_WIDE = 1;

    /** Location / service area wide geographical scope (GSM/UMTS only). */
    public static final int GEOGRAPHICAL_SCOPE_LOCATION_AREA_WIDE = 2;

    /** Cell wide geographical scope (GSM/UMTS only). */
    public static final int GEOGRAPHICAL_SCOPE_CELL_WIDE = 3;

    /** @hide */
    @IntDef(prefix = { "GEOGRAPHICAL_SCOPE_" }, value = {
            GEOGRAPHICAL_SCOPE_CELL_WIDE_IMMEDIATE,
            GEOGRAPHICAL_SCOPE_PLMN_WIDE,
            GEOGRAPHICAL_SCOPE_LOCATION_AREA_WIDE,
            GEOGRAPHICAL_SCOPE_CELL_WIDE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface GeographicalScope {}

    /** GSM or UMTS format cell broadcast. */
    public static final int MESSAGE_FORMAT_3GPP = 1;

    /** CDMA format cell broadcast. */
    public static final int MESSAGE_FORMAT_3GPP2 = 2;

    /** @hide */
    @IntDef(prefix = { "MESSAGE_FORMAT_" }, value = {
            MESSAGE_FORMAT_3GPP,
            MESSAGE_FORMAT_3GPP2
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface MessageFormat {}

    /** Normal message priority. */
    public static final int MESSAGE_PRIORITY_NORMAL = 0;

    /** Interactive message priority. */
    public static final int MESSAGE_PRIORITY_INTERACTIVE = 1;

    /** Urgent message priority. */
    public static final int MESSAGE_PRIORITY_URGENT = 2;

    /** Emergency message priority. */
    public static final int MESSAGE_PRIORITY_EMERGENCY = 3;

    /** @hide */
    @IntDef(prefix = { "MESSAGE_PRIORITY_" }, value = {
            MESSAGE_PRIORITY_NORMAL,
            MESSAGE_PRIORITY_INTERACTIVE,
            MESSAGE_PRIORITY_URGENT,
            MESSAGE_PRIORITY_EMERGENCY,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface MessagePriority {}

    /**
     * ATIS-0700041 Section 5.2.8 WAC Geo-Fencing Maximum Wait Time Table 12.
     * @hide
     */
    public static final int MAXIMUM_WAIT_TIME_NOT_SET = 255;

    /** Format of this message (for interpretation of service category values). */
    private final int mMessageFormat;

    /** Geographical scope of broadcast. */
    private final int mGeographicalScope;

    /**
     * Serial number of broadcast (message identifier for CDMA, geographical scope + message code +
     * update number for GSM/UMTS). The serial number plus the location code uniquely identify
     * a cell broadcast for duplicate detection.
     */
    private final int mSerialNumber;

    /**
     * Location identifier for this message. It consists of the current operator MCC/MNC as a
     * 5 or 6-digit decimal string. In addition, for GSM/UMTS, if the Geographical Scope of the
     * message is not binary 01, the Location Area is included for comparison. If the GS is
     * 00 or 11, the Cell ID is also included. LAC and Cell ID are -1 if not specified.
     */
    @NonNull
    private final SmsCbLocation mLocation;

    /**
     * 16-bit CDMA service category or GSM/UMTS message identifier. For ETWS and CMAS warnings,
     * the information provided by the category is also available via {@link #getEtwsWarningInfo()}
     * or {@link #getCmasWarningInfo()}.
     */
    private final int mServiceCategory;

    /** Message language, as a two-character string, e.g. "en". */
    @Nullable
    private final String mLanguage;

    /** Message body, as a String. */
    @Nullable
    private final String mBody;

    /** Message priority (including emergency priority). */
    private final int mPriority;

    /** ETWS warning notification information (ETWS warnings only). */
    @Nullable
    private final SmsCbEtwsInfo mEtwsWarningInfo;

    /** CMAS warning notification information (CMAS warnings only). */
    @Nullable
    private final SmsCbCmasInfo mCmasWarningInfo;

    /**
     * Geo-Fencing Maximum Wait Time in second, a device shall allow to determine its position
     * meeting operator policy. If the device is unable to determine its position meeting operator
     * policy within the GeoFencing Maximum Wait Time, it shall present the alert to the user and
     * discontinue further positioning determination for the alert.
     */
    private final int mMaximumWaitTimeSec;

    /** UNIX timestamp of when the message was received. */
    private final long mReceivedTimeMillis;

    /** CMAS warning area coordinates. */
    private final List<Geometry> mGeometries;

    /**
     * Create a new SmsCbMessage with the specified data.
     */
    public SmsCbMessage(int messageFormat, int geographicalScope, int serialNumber,
            @NonNull SmsCbLocation location, int serviceCategory, @Nullable String language,
            @Nullable String body, int priority, @Nullable SmsCbEtwsInfo etwsWarningInfo,
            @Nullable SmsCbCmasInfo cmasWarningInfo) {

        this(messageFormat, geographicalScope, serialNumber, location, serviceCategory, language,
                body, priority, etwsWarningInfo, cmasWarningInfo, 0 /* maximumWaitingTime */,
                null /* geometries */, System.currentTimeMillis());
    }

    /**
     * Create a new {@link SmsCbMessage} with the warning area coordinates information.
     * @hide
     */
    public SmsCbMessage(int messageFormat, int geographicalScope, int serialNumber,
            SmsCbLocation location, int serviceCategory, String language, String body,
            int priority, SmsCbEtwsInfo etwsWarningInfo, SmsCbCmasInfo cmasWarningInfo,
            int maximumWaitTimeSec, List<Geometry> geometries, long receivedTimeMillis) {
        mMessageFormat = messageFormat;
        mGeographicalScope = geographicalScope;
        mSerialNumber = serialNumber;
        mLocation = location;
        mServiceCategory = serviceCategory;
        mLanguage = language;
        mBody = body;
        mPriority = priority;
        mEtwsWarningInfo = etwsWarningInfo;
        mCmasWarningInfo = cmasWarningInfo;
        mReceivedTimeMillis = receivedTimeMillis;
        mGeometries = geometries;
        mMaximumWaitTimeSec = maximumWaitTimeSec;
    }

    /**
     * Create a new SmsCbMessage object from a Parcel.
     * @hide
     */
    public SmsCbMessage(@NonNull Parcel in) {
        mMessageFormat = in.readInt();
        mGeographicalScope = in.readInt();
        mSerialNumber = in.readInt();
        mLocation = new SmsCbLocation(in);
        mServiceCategory = in.readInt();
        mLanguage = in.readString();
        mBody = in.readString();
        mPriority = in.readInt();
        int type = in.readInt();
        switch (type) {
            case 'E':
                // unparcel ETWS warning information
                mEtwsWarningInfo = new SmsCbEtwsInfo(in);
                mCmasWarningInfo = null;
                break;

            case 'C':
                // unparcel CMAS warning information
                mEtwsWarningInfo = null;
                mCmasWarningInfo = new SmsCbCmasInfo(in);
                break;

            default:
                mEtwsWarningInfo = null;
                mCmasWarningInfo = null;
        }
        mReceivedTimeMillis = in.readLong();
        String geoStr = in.readString();
        mGeometries = geoStr != null ? CbGeoUtils.parseGeometriesFromString(geoStr) : null;
        mMaximumWaitTimeSec = in.readInt();
    }

    /**
     * Flatten this object into a Parcel.
     *
     * @param dest  The Parcel in which the object should be written.
     * @param flags Additional flags about how the object should be written (ignored).
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mMessageFormat);
        dest.writeInt(mGeographicalScope);
        dest.writeInt(mSerialNumber);
        mLocation.writeToParcel(dest, flags);
        dest.writeInt(mServiceCategory);
        dest.writeString(mLanguage);
        dest.writeString(mBody);
        dest.writeInt(mPriority);
        if (mEtwsWarningInfo != null) {
            // parcel ETWS warning information
            dest.writeInt('E');
            mEtwsWarningInfo.writeToParcel(dest, flags);
        } else if (mCmasWarningInfo != null) {
            // parcel CMAS warning information
            dest.writeInt('C');
            mCmasWarningInfo.writeToParcel(dest, flags);
        } else {
            // no ETWS or CMAS warning information
            dest.writeInt('0');
        }
        dest.writeLong(mReceivedTimeMillis);
        dest.writeString(
                mGeometries != null ? CbGeoUtils.encodeGeometriesToString(mGeometries) : null);
        dest.writeInt(mMaximumWaitTimeSec);
    }

    @NonNull
    public static final Parcelable.Creator<SmsCbMessage> CREATOR =
            new Parcelable.Creator<SmsCbMessage>() {
        @Override
        public SmsCbMessage createFromParcel(Parcel in) {
            return new SmsCbMessage(in);
        }

        @Override
        public SmsCbMessage[] newArray(int size) {
            return new SmsCbMessage[size];
        }
    };

    /**
     * Return the geographical scope of this message (GSM/UMTS only).
     *
     * @return Geographical scope
     */
    public @GeographicalScope int getGeographicalScope() {
        return mGeographicalScope;
    }

    /**
     * Return the broadcast serial number of broadcast (message identifier for CDMA, or
     * geographical scope + message code + update number for GSM/UMTS). The serial number plus
     * the location code uniquely identify a cell broadcast for duplicate detection.
     *
     * @return the 16-bit CDMA message identifier or GSM/UMTS serial number
     */
    public int getSerialNumber() {
        return mSerialNumber;
    }

    /**
     * Return the location identifier for this message, consisting of the MCC/MNC as a
     * 5 or 6-digit decimal string. In addition, for GSM/UMTS, if the Geographical Scope of the
     * message is not binary 01, the Location Area is included. If the GS is 00 or 11, the
     * cell ID is also included. The {@link SmsCbLocation} object includes a method to test
     * if the location is included within another location area or within a PLMN and CellLocation.
     *
     * @return the geographical location code for duplicate message detection
     */
    @NonNull
    public android.telephony.SmsCbLocation getLocation() {
        return mLocation;
    }

    /**
     * Return the 16-bit CDMA service category or GSM/UMTS message identifier. The interpretation
     * of the category is radio technology specific. For ETWS and CMAS warnings, the information
     * provided by the category is available via {@link #getEtwsWarningInfo()} or
     * {@link #getCmasWarningInfo()} in a radio technology independent format.
     *
     * @return the radio technology specific service category
     */
    public int getServiceCategory() {
        return mServiceCategory;
    }

    /**
     * Get the ISO-639-1 language code for this message, or null if unspecified
     *
     * @return Language code
     */
    @Nullable
    public String getLanguageCode() {
        return mLanguage;
    }

    /**
     * Get the body of this message, or null if no body available
     *
     * @return Body, or null
     */
    @Nullable
    public String getMessageBody() {
        return mBody;
    }

    /**
     * Get the warning area coordinates information represent by polygons and circles.
     * @return a list of geometries, {@link Nullable} means there is no coordinate information
     * associated to this message.
     * @hide
     */
    @Nullable
    public List<Geometry> getGeometries() {
        return mGeometries;
    }

    /**
     * Get the Geo-Fencing Maximum Wait Time.
     * @return the time in second.
     * @hide
     */
    public int getMaximumWaitingTime() {
        return mMaximumWaitTimeSec;
    }

    /**
     * Get the time when this message was received.
     * @return the time in millisecond
     */
    public long getReceivedTime() {
        return mReceivedTimeMillis;
    }

    /**
     * Get the message format ({@link #MESSAGE_FORMAT_3GPP} or {@link #MESSAGE_FORMAT_3GPP2}).
     * @return an integer representing 3GPP or 3GPP2 message format
     */
    public @MessageFormat int getMessageFormat() {
        return mMessageFormat;
    }

    /**
     * Get the message priority. Normal broadcasts return {@link #MESSAGE_PRIORITY_NORMAL}
     * and emergency broadcasts return {@link #MESSAGE_PRIORITY_EMERGENCY}. CDMA also may return
     * {@link #MESSAGE_PRIORITY_INTERACTIVE} or {@link #MESSAGE_PRIORITY_URGENT}.
     * @return an integer representing the message priority
     */
    public @MessagePriority int getMessagePriority() {
        return mPriority;
    }

    /**
     * If this is an ETWS warning notification then this method will return an object containing
     * the ETWS warning type, the emergency user alert flag, and the popup flag. If this is an
     * ETWS primary notification (GSM only), there will also be a 7-byte timestamp and 43-byte
     * digital signature. As of Release 10, 3GPP TS 23.041 states that the UE shall ignore the
     * ETWS primary notification timestamp and digital signature if received.
     *
     * @return an SmsCbEtwsInfo object, or null if this is not an ETWS warning notification
     */
    @Nullable
    public SmsCbEtwsInfo getEtwsWarningInfo() {
        return mEtwsWarningInfo;
    }

    /**
     * If this is a CMAS warning notification then this method will return an object containing
     * the CMAS message class, category, response type, severity, urgency and certainty.
     * The message class is always present. Severity, urgency and certainty are present for CDMA
     * warning notifications containing a type 1 elements record and for GSM and UMTS warnings
     * except for the Presidential-level alert category. Category and response type are only
     * available for CDMA notifications containing a type 1 elements record.
     *
     * @return an SmsCbCmasInfo object, or null if this is not a CMAS warning notification
     */
    @Nullable
    public SmsCbCmasInfo getCmasWarningInfo() {
        return mCmasWarningInfo;
    }

    /**
     * Return whether this message is an emergency (PWS) message type.
     * @return true if the message is an emergency notification; false otherwise
     */
    public boolean isEmergencyMessage() {
        return mPriority == MESSAGE_PRIORITY_EMERGENCY;
    }

    /**
     * Return whether this message is an ETWS warning alert.
     * @return true if the message is an ETWS warning notification; false otherwise
     */
    public boolean isEtwsMessage() {
        return mEtwsWarningInfo != null;
    }

    /**
     * Return whether this message is a CMAS warning alert.
     * @return true if the message is a CMAS warning notification; false otherwise
     */
    public boolean isCmasMessage() {
        return mCmasWarningInfo != null;
    }

    @Override
    public String toString() {
        return "SmsCbMessage{geographicalScope=" + mGeographicalScope + ", serialNumber="
                + mSerialNumber + ", location=" + mLocation + ", serviceCategory="
                + mServiceCategory + ", language=" + mLanguage + ", body=" + mBody
                + ", priority=" + mPriority
                + (mEtwsWarningInfo != null ? (", " + mEtwsWarningInfo.toString()) : "")
                + (mCmasWarningInfo != null ? (", " + mCmasWarningInfo.toString()) : "")
                + ", maximumWaitingTime = " + mMaximumWaitTimeSec
                + ", geo=" + (mGeometries != null
                ? CbGeoUtils.encodeGeometriesToString(mGeometries) : "null")
                + '}';
    }

    /**
     * Describe the kinds of special objects contained in the marshalled representation.
     * @return a bitmask indicating this Parcelable contains no special objects
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * @return the {@link ContentValues} instance that includes the cell broadcast data.
     */
    @NonNull
    public ContentValues getContentValues() {
        ContentValues cv = new ContentValues(16);
        cv.put(CellBroadcasts.GEOGRAPHICAL_SCOPE, mGeographicalScope);
        if (mLocation.getPlmn() != null) {
            cv.put(CellBroadcasts.PLMN, mLocation.getPlmn());
        }
        if (mLocation.getLac() != -1) {
            cv.put(CellBroadcasts.LAC, mLocation.getLac());
        }
        if (mLocation.getCid() != -1) {
            cv.put(CellBroadcasts.CID, mLocation.getCid());
        }
        cv.put(CellBroadcasts.SERIAL_NUMBER, getSerialNumber());
        cv.put(CellBroadcasts.SERVICE_CATEGORY, getServiceCategory());
        cv.put(CellBroadcasts.LANGUAGE_CODE, getLanguageCode());
        cv.put(CellBroadcasts.MESSAGE_BODY, getMessageBody());
        cv.put(CellBroadcasts.MESSAGE_FORMAT, getMessageFormat());
        cv.put(CellBroadcasts.MESSAGE_PRIORITY, getMessagePriority());

        SmsCbEtwsInfo etwsInfo = getEtwsWarningInfo();
        if (etwsInfo != null) {
            cv.put(CellBroadcasts.ETWS_WARNING_TYPE, etwsInfo.getWarningType());
        }

        SmsCbCmasInfo cmasInfo = getCmasWarningInfo();
        if (cmasInfo != null) {
            cv.put(CellBroadcasts.CMAS_MESSAGE_CLASS, cmasInfo.getMessageClass());
            cv.put(CellBroadcasts.CMAS_CATEGORY, cmasInfo.getCategory());
            cv.put(CellBroadcasts.CMAS_RESPONSE_TYPE, cmasInfo.getResponseType());
            cv.put(CellBroadcasts.CMAS_SEVERITY, cmasInfo.getSeverity());
            cv.put(CellBroadcasts.CMAS_URGENCY, cmasInfo.getUrgency());
            cv.put(CellBroadcasts.CMAS_CERTAINTY, cmasInfo.getCertainty());
        }

        cv.put(CellBroadcasts.RECEIVED_TIME, mReceivedTimeMillis);

        if (mGeometries != null) {
            cv.put(CellBroadcasts.GEOMETRIES, CbGeoUtils.encodeGeometriesToString(mGeometries));
        } else {
            cv.put(CellBroadcasts.GEOMETRIES, (String) null);
        }

        cv.put(CellBroadcasts.MAXIMUM_WAIT_TIME, mMaximumWaitTimeSec);

        return cv;
    }

    /**
     * Create a {@link SmsCbMessage} instance from a row in the cell broadcast database.
     * @param cursor an open SQLite cursor pointing to the row to read
     * @return a {@link SmsCbMessage} instance.
     * @throws IllegalArgumentException if one of the required columns is missing
     */
    @NonNull
    public static SmsCbMessage createFromCursor(@NonNull Cursor cursor) {
        int geoScope = cursor.getInt(
                cursor.getColumnIndexOrThrow(CellBroadcasts.GEOGRAPHICAL_SCOPE));
        int serialNum = cursor.getInt(cursor.getColumnIndexOrThrow(CellBroadcasts.SERIAL_NUMBER));
        int category = cursor.getInt(cursor.getColumnIndexOrThrow(CellBroadcasts.SERVICE_CATEGORY));
        String language = cursor.getString(
                cursor.getColumnIndexOrThrow(CellBroadcasts.LANGUAGE_CODE));
        String body = cursor.getString(cursor.getColumnIndexOrThrow(CellBroadcasts.MESSAGE_BODY));
        int format = cursor.getInt(cursor.getColumnIndexOrThrow(CellBroadcasts.MESSAGE_FORMAT));
        int priority = cursor.getInt(cursor.getColumnIndexOrThrow(CellBroadcasts.MESSAGE_PRIORITY));

        String plmn;
        int plmnColumn = cursor.getColumnIndex(CellBroadcasts.PLMN);
        if (plmnColumn != -1 && !cursor.isNull(plmnColumn)) {
            plmn = cursor.getString(plmnColumn);
        } else {
            plmn = null;
        }

        int lac;
        int lacColumn = cursor.getColumnIndex(CellBroadcasts.LAC);
        if (lacColumn != -1 && !cursor.isNull(lacColumn)) {
            lac = cursor.getInt(lacColumn);
        } else {
            lac = -1;
        }

        int cid;
        int cidColumn = cursor.getColumnIndex(CellBroadcasts.CID);
        if (cidColumn != -1 && !cursor.isNull(cidColumn)) {
            cid = cursor.getInt(cidColumn);
        } else {
            cid = -1;
        }

        SmsCbLocation location = new SmsCbLocation(plmn, lac, cid);

        SmsCbEtwsInfo etwsInfo;
        int etwsWarningTypeColumn = cursor.getColumnIndex(CellBroadcasts.ETWS_WARNING_TYPE);
        if (etwsWarningTypeColumn != -1 && !cursor.isNull(etwsWarningTypeColumn)) {
            int warningType = cursor.getInt(etwsWarningTypeColumn);
            etwsInfo = new SmsCbEtwsInfo(warningType, false, false, false, null);
        } else {
            etwsInfo = null;
        }

        SmsCbCmasInfo cmasInfo = null;
        int cmasMessageClassColumn = cursor.getColumnIndex(CellBroadcasts.CMAS_MESSAGE_CLASS);
        if (cmasMessageClassColumn != -1 && !cursor.isNull(cmasMessageClassColumn)) {
            int messageClass = cursor.getInt(cmasMessageClassColumn);

            int cmasCategory;
            int cmasCategoryColumn = cursor.getColumnIndex(CellBroadcasts.CMAS_CATEGORY);
            if (cmasCategoryColumn != -1 && !cursor.isNull(cmasCategoryColumn)) {
                cmasCategory = cursor.getInt(cmasCategoryColumn);
            } else {
                cmasCategory = SmsCbCmasInfo.CMAS_CATEGORY_UNKNOWN;
            }

            int responseType;
            int cmasResponseTypeColumn = cursor.getColumnIndex(CellBroadcasts.CMAS_RESPONSE_TYPE);
            if (cmasResponseTypeColumn != -1 && !cursor.isNull(cmasResponseTypeColumn)) {
                responseType = cursor.getInt(cmasResponseTypeColumn);
            } else {
                responseType = SmsCbCmasInfo.CMAS_RESPONSE_TYPE_UNKNOWN;
            }

            int severity;
            int cmasSeverityColumn = cursor.getColumnIndex(CellBroadcasts.CMAS_SEVERITY);
            if (cmasSeverityColumn != -1 && !cursor.isNull(cmasSeverityColumn)) {
                severity = cursor.getInt(cmasSeverityColumn);
            } else {
                severity = SmsCbCmasInfo.CMAS_SEVERITY_UNKNOWN;
            }

            int urgency;
            int cmasUrgencyColumn = cursor.getColumnIndex(CellBroadcasts.CMAS_URGENCY);
            if (cmasUrgencyColumn != -1 && !cursor.isNull(cmasUrgencyColumn)) {
                urgency = cursor.getInt(cmasUrgencyColumn);
            } else {
                urgency = SmsCbCmasInfo.CMAS_URGENCY_UNKNOWN;
            }

            int certainty;
            int cmasCertaintyColumn = cursor.getColumnIndex(CellBroadcasts.CMAS_CERTAINTY);
            if (cmasCertaintyColumn != -1 && !cursor.isNull(cmasCertaintyColumn)) {
                certainty = cursor.getInt(cmasCertaintyColumn);
            } else {
                certainty = SmsCbCmasInfo.CMAS_CERTAINTY_UNKNOWN;
            }

            cmasInfo = new SmsCbCmasInfo(messageClass, cmasCategory, responseType, severity,
                    urgency, certainty);
        }

        String geoStr = cursor.getString(cursor.getColumnIndex(CellBroadcasts.GEOMETRIES));
        List<Geometry> geometries =
                geoStr != null ? CbGeoUtils.parseGeometriesFromString(geoStr) : null;

        long receivedTimeMillis = cursor.getLong(
                cursor.getColumnIndexOrThrow(CellBroadcasts.RECEIVED_TIME));

        int maximumWaitTimeSec = cursor.getInt(
                cursor.getColumnIndexOrThrow(CellBroadcasts.MAXIMUM_WAIT_TIME));

        return new SmsCbMessage(format, geoScope, serialNum, location, category,
                language, body, priority, etwsInfo, cmasInfo, maximumWaitTimeSec, geometries,
                receivedTimeMillis);
    }

    /**
     * @return {@code True} if this message needs geo-fencing check.
     */
    public boolean needGeoFencingCheck() {
        return mMaximumWaitTimeSec > 0 && mGeometries != null;
    }
}
