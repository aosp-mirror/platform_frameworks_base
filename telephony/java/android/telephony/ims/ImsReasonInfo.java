/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.telephony.ims;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.UnsupportedAppUsage;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * This class enables an application to get details on why a method call failed.
 *
 * @hide
 */
@SystemApi
public final class ImsReasonInfo implements Parcelable {

    /**
     * The Reason is unspecified.
     */
    public static final int CODE_UNSPECIFIED = 0;


    // LOCAL

    // IMS -> Telephony
    /**
     * The passed argument is invalid.
     */
    public static final int CODE_LOCAL_ILLEGAL_ARGUMENT = 101;
    /**
     * The operation was invoked while in an invalid call state.
     */
    public static final int CODE_LOCAL_ILLEGAL_STATE = 102;
    /**
     * IMS service internal error
     */
    public static final int CODE_LOCAL_INTERNAL_ERROR = 103;
    /**
     * ImsService has crashed (service connection is lost).
     */
    public static final int CODE_LOCAL_IMS_SERVICE_DOWN = 106;
    /**
     * No pending incoming call exists
     */
    public static final int CODE_LOCAL_NO_PENDING_CALL = 107;
    /**
     * IMS Call ended during conference merge process
     */
    public static final int CODE_LOCAL_ENDED_BY_CONFERENCE_MERGE = 108;

    // IMS -> Telephony
    /**
     * Service unavailable; radio power off
     */
    public static final int CODE_LOCAL_POWER_OFF = 111;
    /**
     * Service unavailable; low battery
     */
    public static final int CODE_LOCAL_LOW_BATTERY = 112;
    /**
     * Service unavailable; out of service (data service state)
     */
    public static final int CODE_LOCAL_NETWORK_NO_SERVICE = 121;
    /**
     * Service unavailable; no LTE coverage
     * (VoLTE is not supported even though IMS is registered)
     */
    public static final int CODE_LOCAL_NETWORK_NO_LTE_COVERAGE = 122;
    /**
     * Service unavailable; located in roaming area
     */
    public static final int CODE_LOCAL_NETWORK_ROAMING = 123;
    /**
     * Service unavailable; IP changed
     */
    public static final int CODE_LOCAL_NETWORK_IP_CHANGED = 124;
    /**
     * Service unavailable; for an unspecified reason
     */
    public static final int CODE_LOCAL_SERVICE_UNAVAILABLE = 131;
    /**
     * Service unavailable; IMS is not registered
     */
    public static final int CODE_LOCAL_NOT_REGISTERED = 132;

    // IMS <-> Telephony
    /**
     * Maximum number of simultaneous calls exceeded
     */
    public static final int CODE_LOCAL_CALL_EXCEEDED = 141;
    // IMS <- Telephony
    /**
     * The call is busy.
     */
    public static final int CODE_LOCAL_CALL_BUSY = 142;
    /**
     * The Call has been declined locally on this device.
     */
    public static final int CODE_LOCAL_CALL_DECLINE = 143;
    // IMS -> Telephony
    /**
     * Can not complete call; an SRVCC is in progress.
     */
    public static final int CODE_LOCAL_CALL_VCC_ON_PROGRESSING = 144;
    /**
     * Can not complete call; resource reservation is failed (QoS precondition)
     */
    public static final int CODE_LOCAL_CALL_RESOURCE_RESERVATION_FAILED = 145;
    /**
     * VoLTE service can't be provided by the network or remote end, retry the call.
     * Resolve the extra code provided in (EXTRA_CODE_CALL_RETRY_*) if the below code is set
     */
    public static final int CODE_LOCAL_CALL_CS_RETRY_REQUIRED = 146;
    /**
     * VoLTE service can't be provided by the network temporarily, retry the call.
     */
    public static final int CODE_LOCAL_CALL_VOLTE_RETRY_REQUIRED = 147;
    /**
     * IMS call is already terminated (in TERMINATED state).
     */
    public static final int CODE_LOCAL_CALL_TERMINATED = 148;
    /**
     * Call was disconnected because a handover is not feasible due to network conditions.
     */
    public static final int CODE_LOCAL_HO_NOT_FEASIBLE = 149;
    /**
     * This device does not support IMS.
     * @hide
     */
    public static final int CODE_LOCAL_IMS_NOT_SUPPORTED_ON_DEVICE = 150;

    /*
     * TIMEOUT (IMS -> Telephony)
     */
    /**
     * 1xx waiting timer is expired after sending INVITE request (MO calls only)
     */
    public static final int CODE_TIMEOUT_1XX_WAITING = 201;
    /**
     * User didn't answer during call setup operation (MO/MT)
     * MO : 200 OK to INVITE request is not received,
     * MT : No action from user after alerting the call
     */
    public static final int CODE_TIMEOUT_NO_ANSWER = 202;
    /**
     * User no answer during call update operation (MO/MT)
     * MO : 200 OK to re-INVITE request is not received,
     * MT : No action from user after alerting the call
     */
    public static final int CODE_TIMEOUT_NO_ANSWER_CALL_UPDATE = 203;

    /**
     * The call was blocked by call barring configuration.
     */
    public static final int CODE_CALL_BARRED = 240;

    /**
     * The operation is restricted to fixed dialing numbers only.
     */
    public static final int CODE_FDN_BLOCKED = 241;

    /**
     * Network rejected the emergency call request because IMEI was used as identification
     * and this capability is not supported by the network.
     */
    public static final int CODE_IMEI_NOT_ACCEPTED = 243;

    //STK CC errors
    /**
     * Stk Call Control modified DIAL request to USSD request.
     */
    public static final int CODE_DIAL_MODIFIED_TO_USSD = 244;
    /**
     * Stk Call Control modified DIAL request to SS request.
     */
    public static final int CODE_DIAL_MODIFIED_TO_SS = 245;
    /**
     * Stk Call Control modified DIAL request to DIAL with modified data.
     */
    public static final int CODE_DIAL_MODIFIED_TO_DIAL = 246;
    /**
     * Stk Call Control modified DIAL request to Video DIAL request.
     */
    public static final int CODE_DIAL_MODIFIED_TO_DIAL_VIDEO = 247;
    /**
     * Stk Call Control modified Video DIAL request to DIAL request.
     */
    public static final int CODE_DIAL_VIDEO_MODIFIED_TO_DIAL = 248;
    /**
     * Stk Call Control modified Video DIAL request to Video DIAL request.
     */
    public static final int CODE_DIAL_VIDEO_MODIFIED_TO_DIAL_VIDEO = 249;
    /**
     * Stk Call Control modified Video DIAL request to SS request.
     */
    public static final int CODE_DIAL_VIDEO_MODIFIED_TO_SS = 250;
    /**
     * Stk Call Control modified Video DIAL request to USSD request.
     */
    public static final int CODE_DIAL_VIDEO_MODIFIED_TO_USSD = 251;

    /*
     * STATUSCODE (SIP response code) (IMS -> Telephony)
     */
    // 3xx responses
    /**
     * SIP 3xx response: SIP request is redirected
     */
    public static final int CODE_SIP_REDIRECTED = 321;
    // 4xx responses
    /**
     * Sip 400 response : Bad Request
     */
    public static final int CODE_SIP_BAD_REQUEST = 331;
    /**
     * Sip 403 response : Forbidden
     */
    public static final int CODE_SIP_FORBIDDEN = 332;
    /**
     * Sip 404 response : Not Found
     */
    public static final int CODE_SIP_NOT_FOUND = 333;
    /**
     * Not supported, because of one of the following:
     * SIP response 415 : Unsupported Media Type,
     * SIP response 416 : Unsupported URI Scheme,
     * SIP response 420 : Bad Extension
     */
    public static final int CODE_SIP_NOT_SUPPORTED = 334;
    /**
     * SIP response 408 : Request Timeout.
     */
    public static final int CODE_SIP_REQUEST_TIMEOUT = 335;
    /**
     * SIP response 480 : Temporarily Unavailable
     */
    public static final int CODE_SIP_TEMPRARILY_UNAVAILABLE = 336;
    /**
     * SIP response 484 : Address Incomplete
     */
    public static final int CODE_SIP_BAD_ADDRESS = 337;
    /**
     * Returned a busy response, may be one of the following:
     * SIP response 486 : Busy Here,
     * SIP response 600 : Busy Everywhere
     */
    public static final int CODE_SIP_BUSY = 338;
    /**
     * SIP response 487 : Request Terminated
     */
    public static final int CODE_SIP_REQUEST_CANCELLED = 339;
    /**
     * Received a not acceptable response, will be one of the following:
     * SIP response 406 : Not Acceptable
     * SIP response 488 : Not Acceptable Here
     * SIP response 606 : Not Acceptable
     */
    public static final int CODE_SIP_NOT_ACCEPTABLE = 340;
    /**
     * Received a not acceptable response, will be one of the following:
     * SIP response 410 : Gone
     * SIP response 604 : Does Not Exist Anywhere
     */
    public static final int CODE_SIP_NOT_REACHABLE = 341;
    /**
     * Received another unspecified error SIP response from the client.
     */
    public static final int CODE_SIP_CLIENT_ERROR = 342;
    /**
     * SIP response 481: Transaction Does Not Exist
     */
    public static final int CODE_SIP_TRANSACTION_DOES_NOT_EXIST = 343;
    // 5xx responses
    /**
     * SIP response 501 : Server Internal Error
     */
    public static final int CODE_SIP_SERVER_INTERNAL_ERROR = 351;
    /**
     * SIP response 503 : Service Unavailable
     */
    public static final int CODE_SIP_SERVICE_UNAVAILABLE = 352;
    /**
     * SIP response 504 : Server Time-out
     */
    public static final int CODE_SIP_SERVER_TIMEOUT = 353;
    /**
     * Received an unspecified SIP server error response.
     */
    public static final int CODE_SIP_SERVER_ERROR = 354;
    // 6xx responses
    /**
     * 603 : Decline
     */
    public static final int CODE_SIP_USER_REJECTED = 361;
    /**
     * Unspecified 6xx error.
     */
    public static final int CODE_SIP_GLOBAL_ERROR = 362;

    /**
     * Emergency call failed in the modem with a temporary fail cause and should be redialed on this
     * slot.
     */
    public static final int CODE_EMERGENCY_TEMP_FAILURE = 363;
    /**
     * Emergency call failed in the modem with a permanent fail cause and should not be redialed on
     * this slot. If there are any other slots available for emergency calling, try those.
     */
    public static final int CODE_EMERGENCY_PERM_FAILURE = 364;

    /**
     * Call failure code during hangup/reject if user marked the call as unwanted.
     *
     * Android Telephony will receive information whether ROBO call feature is supported by the
     * network from modem and propagate the same to AOSP as new ImsCallProfile members. OEMs can
     * check this information and provide an option to the user to mark the call as unwanted.
     */
    public static final int CODE_SIP_USER_MARKED_UNWANTED = 365;

    /**
     * SIP Response : 405
     * Method not allowed for the address in the Request URI
     */
    public static final int CODE_SIP_METHOD_NOT_ALLOWED = 366;

    /**
     * SIP Response : 407
     * The request requires user authentication
     */
    public static final int CODE_SIP_PROXY_AUTHENTICATION_REQUIRED = 367;

    /**
     * SIP Response : 413
     * Request body too large
     */
    public static final int CODE_SIP_REQUEST_ENTITY_TOO_LARGE = 368;

    /**
     * SIP Response : 414
     * Request-URI too large
     */
    public static final int CODE_SIP_REQUEST_URI_TOO_LARGE = 369;

    /**
     * SIP Response : 421
     * Specific extension is required, which is not present in the HEADER
     */
    public static final int CODE_SIP_EXTENSION_REQUIRED = 370;

    /**
     * SIP Response : 422
     * The session expiration field too small
     */
    public static final int CODE_SIP_INTERVAL_TOO_BRIEF = 371;

    /**
     * SIP Response : 481
     * Request received by the server does not match any dialog or transaction
     */
    public static final int CODE_SIP_CALL_OR_TRANS_DOES_NOT_EXIST = 372;

    /**
     * SIP Response : 482
     * Server has detected a loop
     */
    public static final int CODE_SIP_LOOP_DETECTED = 373;

    /**
     * SIP Response : 483
     * Max-Forwards value reached
     */
    public static final int CODE_SIP_TOO_MANY_HOPS = 374;

    /**
     * SIP Response : 485
     * Request-URI is ambiguous
     *
     */
    public static final int CODE_SIP_AMBIGUOUS = 376;

    /**
     * SIP Response : 491
     * Server has pending request for same dialog
     */
    public static final int CODE_SIP_REQUEST_PENDING = 377;

    /**
     * SIP Response : 493
     * The request cannot be decrypted by recipient
     */
    public static final int CODE_SIP_UNDECIPHERABLE = 378;

    /**
     * MEDIA (IMS -> Telephony)
     */
    /**
     * Media resource initialization failed
     */
    public static final int CODE_MEDIA_INIT_FAILED = 401;
    /**
     * RTP timeout (no audio / video traffic in the session)
     */
    public static final int CODE_MEDIA_NO_DATA = 402;
    /**
     * Media is not supported; so dropped the call
     */
    public static final int CODE_MEDIA_NOT_ACCEPTABLE = 403;
    /**
     * Unspecified media related error.
     */
    public static final int CODE_MEDIA_UNSPECIFIED = 404;

    /*
     * USER
     */
    // Telephony -> IMS
    /**
     * User triggers the call to be terminated.
     */
    public static final int CODE_USER_TERMINATED = 501;
    /**
     * No action was taken while an incoming call was ringing.
     */
    public static final int CODE_USER_NOANSWER = 502;
    /**
     * User ignored an incoming call.
     */
    public static final int CODE_USER_IGNORE = 503;
    /**
     * User declined an incoming call.
     */
    public static final int CODE_USER_DECLINE = 504;
    /**
     * Device declined/ended a call due to a low battery condition.
     */
    public static final int CODE_LOW_BATTERY = 505;
    /**
     * Device declined a call due to a blacklisted caller ID.
     */
    public static final int CODE_BLACKLISTED_CALL_ID = 506;
    // IMS -> Telephony
    /**
     * The call has been terminated by the network or remote user.
     */
    public static final int CODE_USER_TERMINATED_BY_REMOTE = 510;
    /**
    * Upgrade Downgrade request rejected by
    * Remote user if the request is MO initiated
    * Local user if the request is MT initiated
    */
    public static final int CODE_USER_REJECTED_SESSION_MODIFICATION = 511;

    /**
    * Upgrade Downgrade request cancelled by the user who initiated it
    */
    public static final int CODE_USER_CANCELLED_SESSION_MODIFICATION = 512;

    /**
     * UPGRADE DOWNGRADE operation failed
     * This can happen due to failure from SIP/RTP/SDP generation or a Call end is
     * triggered/received while Reinvite is in progress.
     */
    public static final int CODE_SESSION_MODIFICATION_FAILED = 1517;

    /*
     * UT
     */
    /**
     * UT is currently not supported on this device.
     */
    public static final int CODE_UT_NOT_SUPPORTED = 801;
    /**
     * UT services are currently not available on this device.
     */
    public static final int CODE_UT_SERVICE_UNAVAILABLE = 802;
    /**
     * The requested UT operation is not allowed.
     */
    public static final int CODE_UT_OPERATION_NOT_ALLOWED = 803;
    /**
     * The UT request resulted in a network error.
     */
    public static final int CODE_UT_NETWORK_ERROR = 804;
    /**
     * The password entered for UT operations does not match the stored password.
     */
    public static final int CODE_UT_CB_PASSWORD_MISMATCH = 821;
    //STK CC errors
    /**
     * Sim Toolkit Call Control modified the UT operation to a dial command.
     */
    public static final int CODE_UT_SS_MODIFIED_TO_DIAL = 822;
    /**
     * Sim Toolkit Call Control modified the UT operation to a USSD command.
     */
    public static final int CODE_UT_SS_MODIFIED_TO_USSD = 823;
    /**
     * Sim Toolkit Call Control modified the UT operation to another supplementary service command.
     */
    public static final int CODE_UT_SS_MODIFIED_TO_SS = 824;
    /**
     * Sim Toolkit Call Control modified the UT operation to a video call dial command.
     */
    public static final int CODE_UT_SS_MODIFIED_TO_DIAL_VIDEO = 825;

    /**@hide*/
    @IntDef(value = {
            CODE_UT_NOT_SUPPORTED,
            CODE_UT_SERVICE_UNAVAILABLE,
            CODE_UT_OPERATION_NOT_ALLOWED,
            CODE_UT_NETWORK_ERROR,
            CODE_UT_CB_PASSWORD_MISMATCH,
            CODE_UT_SS_MODIFIED_TO_DIAL,
            CODE_UT_SS_MODIFIED_TO_USSD,
            CODE_UT_SS_MODIFIED_TO_SS,
            CODE_UT_SS_MODIFIED_TO_DIAL_VIDEO
    }, prefix = "CODE_UT_")
    @Retention(RetentionPolicy.SOURCE)
    public @interface UtReason {}

    /**
     * Emergency callback mode is not supported.
     */
    public static final int CODE_ECBM_NOT_SUPPORTED = 901;

    /**
     * Fail code used to indicate that Multi-endpoint is not supported by the IMS framework.
     */
    public static final int CODE_MULTIENDPOINT_NOT_SUPPORTED = 902;

    /**
     * IMS Registration error code
     */
    public static final int CODE_REGISTRATION_ERROR = 1000;

    /*
     * CALL DROP error codes (Call could drop because of many reasons like Network not available,
     *  handover, failed, etc)
     */
    /**
     * MT call has ended due to a release from the network because the call was answered elsewhere.
     */
    public static final int CODE_ANSWERED_ELSEWHERE = 1014;

    /**
     * For MultiEndpoint - Call Pull request has failed.
     */
    public static final int CODE_CALL_PULL_OUT_OF_SYNC = 1015;

    /**
     * For MultiEndpoint - Call has been pulled from primary to secondary.
     */
    public static final int CODE_CALL_END_CAUSE_CALL_PULL = 1016;

    /**
     * CALL DROP error code for the case when a device is ePDG capable and when the user is on an
     * active wifi call and at the edge of coverage and there is no qualified LTE network available
     * to handover the call to. We get a handover NOT_TRIGERRED message from the modem. This error
     * code is received as part of the handover message.
     */
    public static final int CODE_CALL_DROP_IWLAN_TO_LTE_UNAVAILABLE = 1100;

    /**
     * For MultiEndPoint - Call was rejected elsewhere
     */
    public static final int CODE_REJECTED_ELSEWHERE = 1017;

    /**
     * Supplementary services (HOLD/RESUME) failure error codes.
     * Values for Supplemetary services failure - Failed, Cancelled and Re-Invite collision.
     */

    /**
     * Supplementary Services (HOLD/RESUME) - the command failed.
     */
    public static final int CODE_SUPP_SVC_FAILED = 1201;
    /**
     * Supplementary Services (HOLD/RESUME) - the command was cancelled.
     */
    public static final int CODE_SUPP_SVC_CANCELLED = 1202;
    /**
     * Supplementary Services (HOLD/RESUME) - the command resulted in a re-invite collision.
     */
    public static final int CODE_SUPP_SVC_REINVITE_COLLISION = 1203;

    /**
     * DPD Procedure received no response or send failed.
     */
    public static final int CODE_IWLAN_DPD_FAILURE = 1300;

    /**
     * Establishment of the ePDG Tunnel Failed.
     */
    public static final int CODE_EPDG_TUNNEL_ESTABLISH_FAILURE = 1400;

    /**
     * Re-keying of the ePDG Tunnel Failed; may not always result in teardown.
     */
    public static final int CODE_EPDG_TUNNEL_REKEY_FAILURE = 1401;

    /**
     * Connection to the packet gateway is lost.
     */
    public static final int CODE_EPDG_TUNNEL_LOST_CONNECTION = 1402;

    /**
     * The maximum number of calls allowed has been reached.  Used in a multi-endpoint scenario
     * where the number of calls across all connected devices has reached the maximum.
     */
    public static final int CODE_MAXIMUM_NUMBER_OF_CALLS_REACHED = 1403;

    /**
     * Similar to {@link #CODE_LOCAL_CALL_DECLINE}, except indicates that a remote device has
     * declined the call.  Used in a multi-endpoint scenario where a remote device declined an
     * incoming call.
     */
    public static final int CODE_REMOTE_CALL_DECLINE = 1404;

    /**
     * Indicates the call was disconnected due to the user reaching their data limit.
     */
    public static final int CODE_DATA_LIMIT_REACHED = 1405;

    /**
     * Indicates the call was disconnected due to the user disabling cellular data.
     */
    public static final int CODE_DATA_DISABLED = 1406;

    /**
     * Indicates a call was disconnected due to loss of wifi signal.
     */
    public static final int CODE_WIFI_LOST = 1407;

    /**
     * Indicates the registration attempt on IWLAN failed due to IKEv2 authetication failure
     * during tunnel establishment.
     */
    public static final int CODE_IKEV2_AUTH_FAILURE = 1408;

    /** The call cannot be established because RADIO is OFF */
    public static final int CODE_RADIO_OFF = 1500;

    /** The call cannot be established because of no valid SIM */
    public static final int CODE_NO_VALID_SIM = 1501;

    /** The failure is due internal error at modem */
    public static final int CODE_RADIO_INTERNAL_ERROR = 1502;

    /** The failure is due to UE timer expired while waiting for a response from network */
    public static final int CODE_NETWORK_RESP_TIMEOUT = 1503;

    /** The failure is due to explicit reject from network */
    public static final int CODE_NETWORK_REJECT = 1504;

    /** The failure is due to radio access failure. ex. RACH failure */
    public static final int CODE_RADIO_ACCESS_FAILURE = 1505;

    /** Call/IMS registration failed/dropped because of a RLF */
    public static final int CODE_RADIO_LINK_FAILURE = 1506;

    /** Call/IMS registration failed/dropped because of radio link lost */
    public static final int CODE_RADIO_LINK_LOST = 1507;

    /** The call Call/IMS registration failed because of a radio uplink issue */
    public static final int CODE_RADIO_UPLINK_FAILURE = 1508;

    /** Call failed because of a RRC connection setup failure */
    public static final int CODE_RADIO_SETUP_FAILURE = 1509;

    /** Call failed/dropped because of RRC connection release from NW */
    public static final int CODE_RADIO_RELEASE_NORMAL = 1510;

    /** Call failed/dropped because of RRC abnormally released by modem/network */
    public static final int CODE_RADIO_RELEASE_ABNORMAL = 1511;

    /** Call failed because of access class barring */
    public static final int CODE_ACCESS_CLASS_BLOCKED = 1512;

    /** Call/IMS registration is failed/dropped because of a network detach */
    public static final int CODE_NETWORK_DETACH = 1513;

    /**
     * Call failed due to SIP code 380 (Alternative Service response) while dialing an "undetected
     * emergency number".  This scenario is important in some regions where the carrier network will
     * identify other non-emergency help numbers (e.g. mountain rescue) when attempting to dial.
     */
    public static final int CODE_SIP_ALTERNATE_EMERGENCY_CALL = 1514;

    /**
     * Call failed because of unobtainable number
     * @hide
     */
    public static final int CODE_UNOBTAINABLE_NUMBER = 1515;

    /**
     * Call failed because WiFi call could not complete and circuit switch silent redial
     * is not allowed while roaming on another network.
     */
    public static final int CODE_NO_CSFB_IN_CS_ROAM = 1516;

    /**
     * The rejection cause is not known.
     * <p>
     * Used with implicit call rejection.
     */
    public static final int CODE_REJECT_UNKNOWN = 1600;

    /**
     * Ongoing call, and call waiting is disabled.
     * <p>
     * Used with implicit call rejection.
     */
    public static final int CODE_REJECT_ONGOING_CALL_WAITING_DISABLED = 1601;

    /**
     * A call is ongoing on another sub.
     * <p>
     * Used with implicit call rejection.
     */
    public static final int CODE_REJECT_CALL_ON_OTHER_SUB = 1602;

    /**
     * CDMA call collision.
     * <p>
     * Used with implicit call rejection.
     */
    public static final int CODE_REJECT_1X_COLLISION = 1603;

    /**
     * IMS is not registered for service yet.
     * <p>
     * Used with implicit call rejection.
     */
    public static final int CODE_REJECT_SERVICE_NOT_REGISTERED = 1604;

    /**
     * The call type is not allowed on the current RAT.
     * <p>
     * Used with implicit call rejection.
     */
    public static final int CODE_REJECT_CALL_TYPE_NOT_ALLOWED = 1605;

    /**
     * And emergency call is ongoing.
     * <p>
     * Used with implicit call rejection.
     */
    public static final int CODE_REJECT_ONGOING_E911_CALL = 1606;

    /**
     * Another call is in the process of being establilshed.
     * <p>
     * Used with implicit call rejection.
     */
    public static final int CODE_REJECT_ONGOING_CALL_SETUP = 1607;

    /**
     * Maximum number of allowed calls are already in progress.
     * <p>
     * Used with implicit call rejection.
     */
    public static final int CODE_REJECT_MAX_CALL_LIMIT_REACHED = 1608;

    /**
     * Invalid/unsupported SIP headers received.
     * <p>
     * Used with implicit call rejection.
     */
    public static final int CODE_REJECT_UNSUPPORTED_SIP_HEADERS = 1609;

    /**
     * Invalid/unsupported SDP headers received.
     * <p>
     * Used with implicit call rejection.
     */
    public static final int CODE_REJECT_UNSUPPORTED_SDP_HEADERS = 1610;

    /**
     * A call transfer is in progress.
     * <p>
     * Used with implicit call rejection.
     */
    public static final int CODE_REJECT_ONGOING_CALL_TRANSFER = 1611;

    /**
     * An internal error occured while processing the call.
     * <p>
     * Used with implicit call rejection.
     */
    public static final int CODE_REJECT_INTERNAL_ERROR = 1612;

    /**
     * Call failure due to lack of dedicated bearer.
     * <p>
     * Used with implicit call rejection.
     */
    public static final int CODE_REJECT_QOS_FAILURE = 1613;

    /**
     * A call handover is in progress.
     * <p>
     * Used with implicit call rejection.
     */
    public static final int CODE_REJECT_ONGOING_HANDOVER = 1614;

    /**
     * Video calling not supported with TTY.
     * <p>
     * Used with implicit call rejection.
     */
    public static final int CODE_REJECT_VT_TTY_NOT_ALLOWED = 1615;

    /**
     * A call upgrade is in progress.
     * <p>
     * Used with implicit call rejection.
     */
    public static final int CODE_REJECT_ONGOING_CALL_UPGRADE = 1616;

    /**
     * Call from conference server, when TTY mode is ON.
     * <p>
     * Used with implicit call rejection.
     */
    public static final int CODE_REJECT_CONFERENCE_TTY_NOT_ALLOWED = 1617;

    /**
     * A conference call is ongoing.
     * <p>
     * Used with implicit call rejection.
     */
    public static final int CODE_REJECT_ONGOING_CONFERENCE_CALL = 1618;

    /**
     * A video call with AVPF is not supported.
     * <p>
     * Used with implicit call rejection.
     */
    public static final int CODE_REJECT_VT_AVPF_NOT_ALLOWED = 1619;

    /**
     * And encrypted call is ongoing; other calls not supported.
     * <p>
     * Used with implicit call rejection.
     */
    public static final int CODE_REJECT_ONGOING_ENCRYPTED_CALL = 1620;

    /**
     * A CS call is ongoing.
     * <p>
     * Used with implicit call rejection.
     */
    public static final int CODE_REJECT_ONGOING_CS_CALL = 1621;

    /**
     * An attempt was made to place an emergency call over WFC when emergency services is not
     * currently available in the current location.
     * @hide
     */
    public static final int CODE_EMERGENCY_CALL_OVER_WFC_NOT_AVAILABLE = 1622;

    /**
     * Indicates that WiFi calling service is not available in the current location.
     * @hide
     */
    public static final int CODE_WFC_SERVICE_NOT_AVAILABLE_IN_THIS_LOCATION = 1623;

    /*
     * OEM specific error codes. To be used by OEMs when they don't want to reveal error code which
     * would be replaced by ERROR_UNSPECIFIED.
     */
    public static final int CODE_OEM_CAUSE_1 = 0xf001;
    public static final int CODE_OEM_CAUSE_2 = 0xf002;
    public static final int CODE_OEM_CAUSE_3 = 0xf003;
    public static final int CODE_OEM_CAUSE_4 = 0xf004;
    public static final int CODE_OEM_CAUSE_5 = 0xf005;
    public static final int CODE_OEM_CAUSE_6 = 0xf006;
    public static final int CODE_OEM_CAUSE_7 = 0xf007;
    public static final int CODE_OEM_CAUSE_8 = 0xf008;
    public static final int CODE_OEM_CAUSE_9 = 0xf009;
    public static final int CODE_OEM_CAUSE_10 = 0xf00a;
    public static final int CODE_OEM_CAUSE_11 = 0xf00b;
    public static final int CODE_OEM_CAUSE_12 = 0xf00c;
    public static final int CODE_OEM_CAUSE_13 = 0xf00d;
    public static final int CODE_OEM_CAUSE_14 = 0xf00e;
    public static final int CODE_OEM_CAUSE_15 = 0xf00f;

    /**
     * @hide
     */
    @IntDef(value = {
            CODE_UNSPECIFIED,
            CODE_LOCAL_ILLEGAL_ARGUMENT,
            CODE_LOCAL_ILLEGAL_STATE,
            CODE_LOCAL_INTERNAL_ERROR,
            CODE_LOCAL_IMS_SERVICE_DOWN,
            CODE_LOCAL_NO_PENDING_CALL,
            CODE_LOCAL_ENDED_BY_CONFERENCE_MERGE,
            CODE_LOCAL_POWER_OFF,
            CODE_LOCAL_LOW_BATTERY,
            CODE_LOCAL_NETWORK_NO_SERVICE,
            CODE_LOCAL_NETWORK_NO_LTE_COVERAGE,
            CODE_LOCAL_NETWORK_ROAMING,
            CODE_LOCAL_NETWORK_IP_CHANGED,
            CODE_LOCAL_SERVICE_UNAVAILABLE,
            CODE_LOCAL_NOT_REGISTERED,
            CODE_LOCAL_CALL_EXCEEDED,
            CODE_LOCAL_CALL_BUSY,
            CODE_LOCAL_CALL_DECLINE,
            CODE_LOCAL_CALL_VCC_ON_PROGRESSING,
            CODE_LOCAL_CALL_RESOURCE_RESERVATION_FAILED,
            CODE_LOCAL_CALL_CS_RETRY_REQUIRED,
            CODE_LOCAL_CALL_VOLTE_RETRY_REQUIRED,
            CODE_LOCAL_CALL_TERMINATED,
            CODE_LOCAL_HO_NOT_FEASIBLE,
            CODE_TIMEOUT_1XX_WAITING,
            CODE_TIMEOUT_NO_ANSWER,
            CODE_TIMEOUT_NO_ANSWER_CALL_UPDATE,
            CODE_CALL_BARRED,
            CODE_FDN_BLOCKED,
            CODE_IMEI_NOT_ACCEPTED,
            CODE_DIAL_MODIFIED_TO_USSD,
            CODE_DIAL_MODIFIED_TO_SS,
            CODE_DIAL_MODIFIED_TO_DIAL,
            CODE_DIAL_MODIFIED_TO_DIAL_VIDEO,
            CODE_DIAL_VIDEO_MODIFIED_TO_DIAL,
            CODE_DIAL_VIDEO_MODIFIED_TO_DIAL_VIDEO,
            CODE_DIAL_VIDEO_MODIFIED_TO_SS,
            CODE_DIAL_VIDEO_MODIFIED_TO_USSD,
            CODE_SIP_REDIRECTED,
            CODE_SIP_BAD_REQUEST,
            CODE_SIP_FORBIDDEN,
            CODE_SIP_NOT_FOUND,
            CODE_SIP_NOT_SUPPORTED,
            CODE_SIP_REQUEST_TIMEOUT,
            CODE_SIP_TEMPRARILY_UNAVAILABLE,
            CODE_SIP_BAD_ADDRESS,
            CODE_SIP_BUSY,
            CODE_SIP_REQUEST_CANCELLED,
            CODE_SIP_NOT_ACCEPTABLE,
            CODE_SIP_NOT_REACHABLE,
            CODE_SIP_CLIENT_ERROR,
            CODE_SIP_TRANSACTION_DOES_NOT_EXIST,
            CODE_SIP_SERVER_INTERNAL_ERROR,
            CODE_SIP_SERVICE_UNAVAILABLE,
            CODE_SIP_SERVER_TIMEOUT,
            CODE_SIP_SERVER_ERROR,
            CODE_SIP_USER_REJECTED,
            CODE_SIP_GLOBAL_ERROR,
            CODE_EMERGENCY_TEMP_FAILURE,
            CODE_EMERGENCY_PERM_FAILURE,
            CODE_SIP_USER_MARKED_UNWANTED,
            CODE_SIP_METHOD_NOT_ALLOWED,
            CODE_SIP_PROXY_AUTHENTICATION_REQUIRED,
            CODE_SIP_REQUEST_ENTITY_TOO_LARGE,
            CODE_SIP_REQUEST_URI_TOO_LARGE,
            CODE_SIP_EXTENSION_REQUIRED,
            CODE_SIP_INTERVAL_TOO_BRIEF,
            CODE_SIP_CALL_OR_TRANS_DOES_NOT_EXIST,
            CODE_SIP_LOOP_DETECTED,
            CODE_SIP_TOO_MANY_HOPS,
            CODE_SIP_AMBIGUOUS,
            CODE_SIP_REQUEST_PENDING,
            CODE_SIP_UNDECIPHERABLE,
            CODE_MEDIA_INIT_FAILED,
            CODE_MEDIA_NO_DATA,
            CODE_MEDIA_NOT_ACCEPTABLE,
            CODE_MEDIA_UNSPECIFIED,
            CODE_USER_TERMINATED,
            CODE_USER_NOANSWER,
            CODE_USER_IGNORE,
            CODE_USER_DECLINE,
            CODE_LOW_BATTERY,
            CODE_BLACKLISTED_CALL_ID,
            CODE_USER_TERMINATED_BY_REMOTE,
            CODE_USER_REJECTED_SESSION_MODIFICATION,
            CODE_USER_CANCELLED_SESSION_MODIFICATION,
            CODE_SESSION_MODIFICATION_FAILED,
            CODE_UT_NOT_SUPPORTED,
            CODE_UT_SERVICE_UNAVAILABLE,
            CODE_UT_OPERATION_NOT_ALLOWED,
            CODE_UT_NETWORK_ERROR,
            CODE_UT_CB_PASSWORD_MISMATCH,
            CODE_UT_SS_MODIFIED_TO_DIAL,
            CODE_UT_SS_MODIFIED_TO_USSD,
            CODE_UT_SS_MODIFIED_TO_SS,
            CODE_UT_SS_MODIFIED_TO_DIAL_VIDEO,
            CODE_ECBM_NOT_SUPPORTED,
            CODE_MULTIENDPOINT_NOT_SUPPORTED,
            CODE_REGISTRATION_ERROR,
            CODE_ANSWERED_ELSEWHERE,
            CODE_CALL_PULL_OUT_OF_SYNC,
            CODE_CALL_END_CAUSE_CALL_PULL,
            CODE_CALL_DROP_IWLAN_TO_LTE_UNAVAILABLE,
            CODE_REJECTED_ELSEWHERE,
            CODE_SUPP_SVC_FAILED,
            CODE_SUPP_SVC_CANCELLED,
            CODE_SUPP_SVC_REINVITE_COLLISION,
            CODE_IWLAN_DPD_FAILURE,
            CODE_EPDG_TUNNEL_ESTABLISH_FAILURE,
            CODE_EPDG_TUNNEL_REKEY_FAILURE,
            CODE_EPDG_TUNNEL_LOST_CONNECTION,
            CODE_MAXIMUM_NUMBER_OF_CALLS_REACHED,
            CODE_REMOTE_CALL_DECLINE,
            CODE_DATA_LIMIT_REACHED,
            CODE_DATA_DISABLED,
            CODE_WIFI_LOST,
            CODE_IKEV2_AUTH_FAILURE,
            CODE_RADIO_OFF,
            CODE_NO_VALID_SIM,
            CODE_RADIO_INTERNAL_ERROR,
            CODE_NETWORK_RESP_TIMEOUT,
            CODE_NETWORK_REJECT,
            CODE_RADIO_ACCESS_FAILURE,
            CODE_RADIO_LINK_FAILURE,
            CODE_RADIO_LINK_LOST,
            CODE_RADIO_UPLINK_FAILURE,
            CODE_RADIO_SETUP_FAILURE,
            CODE_RADIO_RELEASE_NORMAL,
            CODE_RADIO_RELEASE_ABNORMAL,
            CODE_ACCESS_CLASS_BLOCKED,
            CODE_NETWORK_DETACH,
            CODE_SIP_ALTERNATE_EMERGENCY_CALL,
            CODE_UNOBTAINABLE_NUMBER,
            CODE_NO_CSFB_IN_CS_ROAM,
            CODE_REJECT_UNKNOWN,
            CODE_REJECT_ONGOING_CALL_WAITING_DISABLED,
            CODE_REJECT_CALL_ON_OTHER_SUB,
            CODE_REJECT_1X_COLLISION,
            CODE_REJECT_SERVICE_NOT_REGISTERED,
            CODE_REJECT_CALL_TYPE_NOT_ALLOWED,
            CODE_REJECT_ONGOING_E911_CALL,
            CODE_REJECT_ONGOING_CALL_SETUP,
            CODE_REJECT_MAX_CALL_LIMIT_REACHED,
            CODE_REJECT_UNSUPPORTED_SIP_HEADERS,
            CODE_REJECT_UNSUPPORTED_SDP_HEADERS,
            CODE_REJECT_ONGOING_CALL_TRANSFER,
            CODE_REJECT_INTERNAL_ERROR,
            CODE_REJECT_QOS_FAILURE,
            CODE_REJECT_ONGOING_HANDOVER,
            CODE_REJECT_VT_TTY_NOT_ALLOWED,
            CODE_REJECT_ONGOING_CALL_UPGRADE,
            CODE_REJECT_CONFERENCE_TTY_NOT_ALLOWED,
            CODE_REJECT_ONGOING_CONFERENCE_CALL,
            CODE_REJECT_VT_AVPF_NOT_ALLOWED,
            CODE_REJECT_ONGOING_ENCRYPTED_CALL,
            CODE_REJECT_ONGOING_CS_CALL,
            CODE_OEM_CAUSE_1,
            CODE_OEM_CAUSE_2,
            CODE_OEM_CAUSE_3,
            CODE_OEM_CAUSE_4,
            CODE_OEM_CAUSE_5,
            CODE_OEM_CAUSE_6,
            CODE_OEM_CAUSE_7,
            CODE_OEM_CAUSE_8,
            CODE_OEM_CAUSE_9,
            CODE_OEM_CAUSE_10,
            CODE_OEM_CAUSE_11,
            CODE_OEM_CAUSE_12,
            CODE_OEM_CAUSE_13,
            CODE_OEM_CAUSE_14,
            CODE_OEM_CAUSE_15
    }, prefix = "CODE_")
    @Retention(RetentionPolicy.SOURCE)
    public @interface ImsCode {}

    /**
     * Network string error messages.
     * mExtraMessage may have these values.
     */
    public static final String EXTRA_MSG_SERVICE_NOT_AUTHORIZED
            = "Forbidden. Not Authorized for Service";


    /*
     * Extra codes for the specific code value
     * This value can be referred when the code is CODE_LOCAL_CALL_CS_RETRY_REQUIRED.
     */
    /**
     * An extra that may be populated when the {@link CODE_LOCAL_CALL_CS_RETRY_REQUIRED} result has
     * been returned.
     * <p>
     * Try to connect the call using CS
     */
    public static final int EXTRA_CODE_CALL_RETRY_NORMAL = 1;
    /**
     * An extra that may be populated when the {@link CODE_LOCAL_CALL_CS_RETRY_REQUIRED} result has
     * been returned.
     * <p>
     * Try to connect the call using CS and do not notify the user.
     */
    public static final int EXTRA_CODE_CALL_RETRY_SILENT_REDIAL = 2;
    /**
     * An extra that may be populated when the {@link CODE_LOCAL_CALL_CS_RETRY_REQUIRED} result has
     * been returned.
     * <p>
     * Try to connect the call using CS by using the settings.
     */
    public static final int EXTRA_CODE_CALL_RETRY_BY_SETTINGS = 3;


    // For main reason code
    /** @hide */
    @UnsupportedAppUsage
    public int mCode;
    // For the extra code value; it depends on the code value.
    /** @hide */
    @UnsupportedAppUsage
    public int mExtraCode;
    // For the additional message of the reason info.
    /** @hide */
    @UnsupportedAppUsage
    public String mExtraMessage;

    /** @hide */
    public ImsReasonInfo() {
        mCode = CODE_UNSPECIFIED;
        mExtraCode = CODE_UNSPECIFIED;
        mExtraMessage = null;
    }

    private ImsReasonInfo(Parcel in) {
        mCode = in.readInt();
        mExtraCode = in.readInt();
        mExtraMessage = in.readString();
    }

    /** @hide */
    @UnsupportedAppUsage
    public ImsReasonInfo(int code, int extraCode) {
        mCode = code;
        mExtraCode = extraCode;
        mExtraMessage = null;
    }

    public ImsReasonInfo(int code, int extraCode, String extraMessage) {
        mCode = code;
        mExtraCode = extraCode;
        mExtraMessage = extraMessage;
    }

    /**
     * @return an integer representing more information about the completion of an operation.
     */
    public @ImsCode int getCode() {
        return mCode;
    }

    /**
     * @return an optional OEM specified code that provides extra information.
     */
    public int getExtraCode() {
        return mExtraCode;
    }

    /**
     * @return an optional OEM specified string that provides extra information about the operation
     * result.
     */
    public String getExtraMessage() {
        return mExtraMessage;
    }

    /**
     * @return the string format of {@link ImsReasonInfo}
     */
    @NonNull
    @Override
    public String toString() {
        return "ImsReasonInfo :: {" + mCode + ", " + mExtraCode + ", " + mExtraMessage + "}";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mCode);
        out.writeInt(mExtraCode);
        out.writeString(mExtraMessage);
    }

    public static final @android.annotation.NonNull Creator<ImsReasonInfo> CREATOR = new Creator<ImsReasonInfo>() {
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
