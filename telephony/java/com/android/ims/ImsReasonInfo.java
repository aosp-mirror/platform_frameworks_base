/*
 * Copyright (c) 2013 The Android Open Source Project
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

package com.android.ims;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * This class enables an application to get details on why a method call failed.
 *
 * @hide
 */
public class ImsReasonInfo implements Parcelable {

    /**
     * Reason types, defines the error category.
     *    UNSPECIFIED - unknown error reason
     *    LOCAL - indicates the local/device error reason
     *    LOCAL_TIMEOUT - indicates the local error reason when a specific timer is expired
     *    STATUSCODE - indicates the interworking error reason by SIP status code received
     *        from the network
     *    MEDIA - indicates the media error reason (local resource, SDP parameter, etc.)
     *    USER - indicates the error reason by the local or remote user
     *    UT - indicates the error reason for the supplementary service configuration
     */
    public static final int TYPE_UNSPECIFIED = 0;
    public static final int TYPE_LOCAL = 1;
    public static final int TYPE_TIMEOUT = 2;
    public static final int TYPE_STATUSCODE = 3;
    public static final int TYPE_MEDIA = 4;
    public static final int TYPE_USER = 5;
    public static final int TYPE_UT = 8;

    /**
     * Specific code of each types
     */
    public static final int CODE_UNSPECIFIED = 0;

    /**
     * LOCAL
     */
    // IMS -> Telephony
    // The passed argument is an invalid
    public static final int CODE_LOCAL_ILLEGAL_ARGUMENT = 101;
    // The operation is invoked in invalid call state
    public static final int CODE_LOCAL_ILLEGAL_STATE = 102;
    // IMS service internal error
    public static final int CODE_LOCAL_INTERNAL_ERROR = 103;
    // IMS service goes down (service connection is lost)
    public static final int CODE_LOCAL_IMS_SERVICE_DOWN = 106;
    // No pending incoming call exists
    public static final int CODE_LOCAL_NO_PENDING_CALL = 107;

    // IMS -> Telephony
    // Service unavailable; by power off
    public static final int CODE_LOCAL_POWER_OFF = 111;
    // Service unavailable; by low battery
    public static final int CODE_LOCAL_LOW_BATTERY = 112;
    // Service unavailable; by out of service (data service state)
    public static final int CODE_LOCAL_NETWORK_NO_SERVICE = 121;
    // Service unavailable; by no LTE coverage
    // (VoLTE is not supported even though IMS is registered)
    public static final int CODE_LOCAL_NETWORK_NO_LTE_COVERAGE = 122;
    // Service unavailable; by located in roaming area
    public static final int CODE_LOCAL_NETWORK_ROAMING = 123;
    // Service unavailable; by IP changed
    public static final int CODE_LOCAL_NETWORK_IP_CHANGED = 124;
    // Service unavailable; other
    public static final int CODE_LOCAL_SERVICE_UNAVAILABLE = 131;
    // Service unavailable; IMS connection is lost (IMS is not registered)
    public static final int CODE_LOCAL_NOT_REGISTERED = 132;

    // IMS <-> Telephony
    // Max call exceeded
    public static final int CODE_LOCAL_CALL_EXCEEDED = 141;
    // IMS <- Telephony
    // Call busy
    public static final int CODE_LOCAL_CALL_BUSY = 142;
    // Call decline
    public static final int CODE_LOCAL_CALL_DECLINE = 143;
    // IMS -> Telephony
    // SRVCC is in progress
    public static final int CODE_LOCAL_CALL_VCC_ON_PROGRESSING = 144;
    // Resource reservation is failed (QoS precondition)
    public static final int CODE_LOCAL_CALL_RESOURCE_RESERVATION_FAILED = 145;
    // Retry CS call; VoLTE service can't be provided by the network or remote end
    // Resolve the extra code(EXTRA_CODE_CALL_RETRY_*) if the below code is set
    public static final int CODE_LOCAL_CALL_CS_RETRY_REQUIRED = 146;
    // Retry VoLTE call; VoLTE service can't be provided by the network temporarily
    public static final int CODE_LOCAL_CALL_VOLTE_RETRY_REQUIRED = 147;
    // IMS call is already terminated (in TERMINATED state)
    public static final int CODE_LOCAL_CALL_TERMINATED = 148;

    /**
     * TIMEOUT (IMS -> Telephony)
     */
    // 1xx waiting timer is expired after sending INVITE request (MO only)
    public static final int CODE_TIMEOUT_1XX_WAITING = 201;
    // User no answer during call setup operation (MO/MT)
    // MO : 200 OK to INVITE request is not received,
    // MT : No action from user after alerting the call
    public static final int CODE_TIMEOUT_NO_ANSWER = 202;
    // User no answer during call update operation (MO/MT)
    // MO : 200 OK to re-INVITE request is not received,
    // MT : No action from user after alerting the call
    public static final int CODE_TIMEOUT_NO_ANSWER_CALL_UPDATE = 203;

    /**
     * STATUSCODE (SIP response code) (IMS -> Telephony)
     */
    // 3xx responses
    // SIP request is redirected
    public static final int CODE_SIP_REDIRECTED = 321;
    // 4xx responses
    // 400 : Bad Request
    public static final int CODE_SIP_BAD_REQUEST = 331;
    // 403 : Forbidden
    public static final int CODE_SIP_FORBIDDEN = 332;
    // 404 : Not Found
    public static final int CODE_SIP_NOT_FOUND = 333;
    // 415 : Unsupported Media Type
    // 416 : Unsupported URI Scheme
    // 420 : Bad Extension
    public static final int CODE_SIP_NOT_SUPPORTED = 334;
    // 408 : Request Timeout
    public static final int CODE_SIP_REQUEST_TIMEOUT = 335;
    // 480 : Temporarily Unavailable
    public static final int CODE_SIP_TEMPRARILY_UNAVAILABLE = 336;
    // 484 : Address Incomplete
    public static final int CODE_SIP_BAD_ADDRESS = 337;
    // 486 : Busy Here
    // 600 : Busy Everywhere
    public static final int CODE_SIP_BUSY = 338;
    // 487 : Request Terminated
    public static final int CODE_SIP_REQUEST_CANCELLED = 339;
    // 406 : Not Acceptable
    // 488 : Not Acceptable Here
    // 606 : Not Acceptable
    public static final int CODE_SIP_NOT_ACCEPTABLE = 340;
    // 410 : Gone
    // 604 : Does Not Exist Anywhere
    public static final int CODE_SIP_NOT_REACHABLE = 341;
    // Others
    public static final int CODE_SIP_CLIENT_ERROR = 342;
    // 5xx responses
    // 501 : Server Internal Error
    public static final int CODE_SIP_SERVER_INTERNAL_ERROR = 351;
    // 503 : Service Unavailable
    public static final int CODE_SIP_SERVICE_UNAVAILABLE = 352;
    // 504 : Server Time-out
    public static final int CODE_SIP_SERVER_TIMEOUT = 353;
    // Others
    public static final int CODE_SIP_SERVER_ERROR = 354;
    // 6xx responses
    // 603 : Decline
    public static final int CODE_SIP_USER_REJECTED = 361;
    // Others
    public static final int CODE_SIP_GLOBAL_ERROR = 362;

    /**
     * MEDIA (IMS -> Telephony)
     */
    // Media resource initialization failed
    public static final int CODE_MEDIA_INIT_FAILED = 401;
    // RTP timeout (no audio / video traffic in the session)
    public static final int CODE_MEDIA_NO_DATA = 402;
    // Media is not supported; so dropped the call
    public static final int CODE_MEDIA_NOT_ACCEPTABLE = 403;
    // Unknown media related errors
    public static final int CODE_MEDIA_UNSPECIFIED = 404;

    /**
     * USER
     */
    // Telephony -> IMS
    // User triggers the call end
    public static final int CODE_USER_TERMINATED = 501;
    // No action while an incoming call is ringing
    public static final int CODE_USER_NOANSWER = 502;
    // User ignores an incoming call
    public static final int CODE_USER_IGNORE = 503;
    // User declines an incoming call
    public static final int CODE_USER_DECLINE = 504;
    // Device declines/ends a call due to low battery
    public static final int CODE_LOW_BATTERY = 505;
    // Device declines call due to blacklisted call ID
    public static final int CODE_BLACKLISTED_CALL_ID = 506;
    // IMS -> Telephony
    // The call is terminated by the network or remote user
    public static final int CODE_USER_TERMINATED_BY_REMOTE = 510;

    /**
     * Extra codes for the specific code value
     * This value can be referred when the code is CODE_LOCAL_CALL_CS_RETRY_REQUIRED.
     */
    // Try to connect CS call; normal
    public static final int EXTRA_CODE_CALL_RETRY_NORMAL = 1;
    // Try to connect CS call without the notification to user
    public static final int EXTRA_CODE_CALL_RETRY_SILENT_REDIAL = 2;
    // Try to connect CS call by the settings of the menu
    public static final int EXTRA_CODE_CALL_RETRY_BY_SETTINGS = 3;

    /**
     * UT
     */
    public static final int CODE_UT_NOT_SUPPORTED = 801;
    public static final int CODE_UT_SERVICE_UNAVAILABLE = 802;
    public static final int CODE_UT_OPERATION_NOT_ALLOWED = 803;
    public static final int CODE_UT_NETWORK_ERROR = 804;
    public static final int CODE_UT_CB_PASSWORD_MISMATCH = 821;

    /**
     * ECBM
     */
    public static final int CODE_ECBM_NOT_SUPPORTED = 901;

    /**
     * Network string error messages.
     * mExtraMessage may have these values.
     */
    public static final String EXTRA_MSG_SERVICE_NOT_AUTHORIZED
            = "Forbidden. Not Authorized for Service";

    // For reason type
    public int mReasonType;
    // For main reason code
    public int mCode;
    // For the extra code value; it depends on the code value.
    public int mExtraCode;
    // For the additional message of the reason info.
    public String mExtraMessage;

    public ImsReasonInfo() {
        mReasonType = TYPE_UNSPECIFIED;
        mCode = CODE_UNSPECIFIED;
        mExtraCode = CODE_UNSPECIFIED;
        mExtraMessage = null;
    }

    public ImsReasonInfo(Parcel in) {
        readFromParcel(in);
    }

    public ImsReasonInfo(int code, int extraCode) {
        mReasonType = (int) (code / 100);
        mCode = code;
        mExtraCode = extraCode;
        mExtraMessage = null;
    }

    public ImsReasonInfo(int code, int extraCode, String extraMessage) {
        mReasonType = (int) (code / 100);
        mCode = code;
        mExtraCode = extraCode;
        mExtraMessage = extraMessage;
    }

    /**
     *
     */
    public int getCode() {
        return mCode;
    }

    /**
     *
     */
    public int getExtraCode() {
        return mExtraCode;
    }

    /**
     *
     */
    public String getExtraMessage() {
        return mExtraMessage;
    }

    /**
     *
     */
    public int getReasonType() {
        return mReasonType;
    }

    /**
     * Returns the string format of {@link ImsReasonInfo}
     *
     * @return the string format of {@link ImsReasonInfo}
     */
    public String toString() {
        return "ImsReasonInfo :: {" + mReasonType + ", "
                + mCode + ", " + mExtraCode + ", " + mExtraMessage + "}";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mReasonType);
        out.writeInt(mCode);
        out.writeInt(mExtraCode);
        out.writeString(mExtraMessage);
    }

    private void readFromParcel(Parcel in) {
        mReasonType = in.readInt();
        mCode = in.readInt();
        mExtraCode = in.readInt();
        mExtraMessage = in.readString();
    }

    public static final Creator<ImsReasonInfo> CREATOR = new Creator<ImsReasonInfo>() {
        @Override
        public ImsReasonInfo createFromParcel(Parcel in) {
            return new ImsReasonInfo(in);
        }

        @Override
        public ImsReasonInfo[] newArray(int size) {
            return new ImsReasonInfo[size];
        }
    };
}
