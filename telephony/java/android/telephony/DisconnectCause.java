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

import android.annotation.NonNull;
import android.compat.annotation.UnsupportedAppUsage;

/**
 * Describes the cause of a disconnected call. Those disconnect causes can be converted into a more
 * generic {@link android.telecom.DisconnectCause} object.
 *
 * Used in {@link PhoneStateListener#onCallDisconnectCauseChanged}.
 */
public final class DisconnectCause {

    /** The disconnect cause is not valid (Not received a disconnect cause) */
    public static final int NOT_VALID                      = -1;
    /** Has not yet disconnected */
    public static final int NOT_DISCONNECTED               = 0;
    /** An incoming call that was missed and never answered */
    public static final int INCOMING_MISSED                = 1;
    /** Normal; Remote hangup*/
    public static final int NORMAL                         = 2;
    /** Normal; Local hangup */
    public static final int LOCAL                          = 3;
    /** Outgoing call to busy line */
    public static final int BUSY                           = 4;
    /** Outgoing call to congested network */
    public static final int CONGESTION                     = 5;
    /** Not presently used */
    public static final int MMI                            = 6;
    /** Invalid dial string */
    public static final int INVALID_NUMBER                 = 7;
    /** Cannot reach the peer */
    public static final int NUMBER_UNREACHABLE             = 8;
    /** Cannot reach the server */
    public static final int SERVER_UNREACHABLE             = 9;
    /** Invalid credentials */
    public static final int INVALID_CREDENTIALS            = 10;
    /** Calling from out of network is not allowed */
    public static final int OUT_OF_NETWORK                 = 11;
    /** Server error */
    public static final int SERVER_ERROR                   = 12;
    /** Client timed out */
    public static final int TIMED_OUT                      = 13;
    /** Client went out of network range */
    public static final int LOST_SIGNAL                    = 14;
    /** GSM or CDMA ACM limit exceeded */
    public static final int LIMIT_EXCEEDED                 = 15;
    /** An incoming call that was rejected */
    public static final int INCOMING_REJECTED              = 16;
    /** Radio is turned off explicitly */
    public static final int POWER_OFF                      = 17;
    /** Out of service */
    public static final int OUT_OF_SERVICE                 = 18;
    /** No ICC, ICC locked, or other ICC error */
    public static final int ICC_ERROR                      = 19;
    /** Call was blocked by call barring */
    public static final int CALL_BARRED                    = 20;
    /** Call was blocked by fixed dial number */
    public static final int FDN_BLOCKED                    = 21;
    /** Call was blocked by restricted all voice access */
    public static final int CS_RESTRICTED                  = 22;
    /** Call was blocked by restricted normal voice access */
    public static final int CS_RESTRICTED_NORMAL           = 23;
    /** Call was blocked by restricted emergency voice access */
    public static final int CS_RESTRICTED_EMERGENCY        = 24;
    /** Unassigned number */
    public static final int UNOBTAINABLE_NUMBER            = 25;
    /** MS is locked until next power cycle */
    public static final int CDMA_LOCKED_UNTIL_POWER_CYCLE  = 26;
    /** Drop call*/
    public static final int CDMA_DROP                      = 27;
    /** INTERCEPT order received, MS state idle entered */
    public static final int CDMA_INTERCEPT                 = 28;
    /** MS has been redirected, call is cancelled */
    public static final int CDMA_REORDER                   = 29;
    /** Service option rejection */
    public static final int CDMA_SO_REJECT                 = 30;
    /** Requested service is rejected, retry delay is set */
    public static final int CDMA_RETRY_ORDER               = 31;
    /** Unable to obtain access to the CDMA system */
    public static final int CDMA_ACCESS_FAILURE            = 32;
    /** Not a preempted call */
    public static final int CDMA_PREEMPTED                 = 33;
    /** Not an emergency call */
    public static final int CDMA_NOT_EMERGENCY             = 34;
    /** Access Blocked by CDMA network */
    public static final int CDMA_ACCESS_BLOCKED            = 35;
    /** Unknown error or not specified */
    public static final int ERROR_UNSPECIFIED              = 36;
    /**
     * Only emergency numbers are allowed, but we tried to dial a non-emergency number.
     * @hide
     */
    // TODO: This should be the same as NOT_EMERGENCY
    public static final int EMERGENCY_ONLY                 = 37;
    /**
     * The supplied CALL Intent didn't contain a valid phone number.
     */
    public static final int NO_PHONE_NUMBER_SUPPLIED       = 38;
    /**
     * Our initial phone number was actually an MMI sequence.
     */
    public static final int DIALED_MMI                     = 39;
    /**
     * We tried to call a voicemail: URI but the device has no voicemail number configured.
     */
    public static final int VOICEMAIL_NUMBER_MISSING       = 40;
    /**
     * This status indicates that InCallScreen should display the
     * CDMA-specific "call lost" dialog.  (If an outgoing call fails,
     * and the CDMA "auto-retry" feature is enabled, *and* the retried
     * call fails too, we display this specific dialog.)
     *
     * TODO: this is currently unused, since the "call lost" dialog
     * needs to be triggered by a *disconnect* event, rather than when
     * the InCallScreen first comes to the foreground.  For now we use
     * the needToShowCallLostDialog field for this (see below.)
     *
     * @hide
     */
    public static final int CDMA_CALL_LOST                 = 41;
    /**
     * This status indicates that the call was placed successfully,
     * but additionally, the InCallScreen needs to display the
     * "Exiting ECM" dialog.
     *
     * (Details: "Emergency callback mode" is a CDMA-specific concept
     * where the phone disallows data connections over the cell
     * network for some period of time after you make an emergency
     * call.  If the phone is in ECM and you dial a non-emergency
     * number, that automatically *cancels* ECM, but we additionally
     * need to warn the user that ECM has been canceled (see bug
     * 4207607.))
     *
     * TODO: Rethink where the best place to put this is. It is not a notification
     * of a failure of the connection -- it is an additional message that accompanies
     * a successful connection giving the user important information about what happened.
     *
     * {@hide}
     */
    public static final int EXITED_ECM                     = 42;

    /**
     * The outgoing call failed with an unknown cause.
     */
    public static final int OUTGOING_FAILURE               = 43;

    /**
     * The outgoing call was canceled by the {@link android.telecom.ConnectionService}.
     */
    public static final int OUTGOING_CANCELED              = 44;

    /**
     * The call, which was an IMS call, disconnected because it merged with another call.
     */
    public static final int IMS_MERGED_SUCCESSFULLY        = 45;

    /**
     * Stk Call Control modified DIAL request to USSD request.
     */
    public static final int DIAL_MODIFIED_TO_USSD          = 46;
    /**
     * Stk Call Control modified DIAL request to SS request.
     */
    public static final int DIAL_MODIFIED_TO_SS            = 47;
    /**
     * Stk Call Control modified DIAL request to DIAL with modified data.
     */
    public static final int DIAL_MODIFIED_TO_DIAL          = 48;

    /**
     * The call was terminated because CDMA phone service and roaming have already been activated.
     */
    public static final int CDMA_ALREADY_ACTIVATED         = 49;

    /**
     * The call was terminated because it is not possible to place a video call while TTY is
     * enabled.
     */
    public static final int VIDEO_CALL_NOT_ALLOWED_WHILE_TTY_ENABLED = 50;

    /**
     * The call was terminated because it was pulled to another device.
     */
    public static final int CALL_PULLED = 51;

    /**
     * The call was terminated because it was answered on another device.
     */
    public static final int ANSWERED_ELSEWHERE = 52;

    /**
     * The call was terminated because the maximum allowable number of calls has been reached.
     */
    public static final int MAXIMUM_NUMBER_OF_CALLS_REACHED = 53;

    /**
     * The call was terminated because cellular data has been disabled.
     * Used when in a video call and the user disables cellular data via the settings.
     */
    public static final int DATA_DISABLED = 54;

    /**
     * The call was terminated because the data policy has disabled cellular data.
     * Used when in a video call and the user has exceeded the device data limit.
     */
    public static final int DATA_LIMIT_REACHED = 55;

    /**
     * The call being placed was detected as a call forwarding number and was being dialed while
     * roaming on a carrier that does not allow this.
     */
    public static final int DIALED_CALL_FORWARDING_WHILE_ROAMING = 57;

    /**
     * The network does not accept the emergency call request because IMEI was used as
     * identification and this cability is not supported by the network.
     */
    public static final int IMEI_NOT_ACCEPTED = 58;

    /**
     * A call over WIFI was disconnected because the WIFI signal was lost or became too degraded to
     * continue the call.
     */
    public static final int WIFI_LOST = 59;

    /**
     * The call has failed because of access class barring.
     */
    public static final int IMS_ACCESS_BLOCKED = 60;

    /**
     * The call has ended (mid-call) because the device's battery is too low.
     */
    public static final int LOW_BATTERY = 61;

    /**
     * A call was not dialed because the device's battery is too low.
     */
    public static final int DIAL_LOW_BATTERY = 62;

    /**
     * Emergency call failed with a temporary fail cause and can be redialed on this slot.
     */
    public static final int EMERGENCY_TEMP_FAILURE = 63;

    /**
     * Emergency call failed with a permanent fail cause and should not be redialed on this
     * slot.
     */
    public static final int EMERGENCY_PERM_FAILURE = 64;

    /**
     * This cause is used to report a normal event only when no other cause in the normal class
     * applies.
     */
    public static final int NORMAL_UNSPECIFIED = 65;

    /**
     * Stk Call Control modified DIAL request to video DIAL request.
     */
    public static final int DIAL_MODIFIED_TO_DIAL_VIDEO = 66;

    /**
     * Stk Call Control modified Video DIAL request to SS request.
     */
    public static final int DIAL_VIDEO_MODIFIED_TO_SS = 67;

    /**
     * Stk Call Control modified Video DIAL request to USSD request.
     */
    public static final int DIAL_VIDEO_MODIFIED_TO_USSD = 68;

    /**
     * Stk Call Control modified Video DIAL request to DIAL request.
     */
    public static final int DIAL_VIDEO_MODIFIED_TO_DIAL = 69;

    /**
     * Stk Call Control modified Video DIAL request to Video DIAL request.
     */
    public static final int DIAL_VIDEO_MODIFIED_TO_DIAL_VIDEO = 70;

    /**
     * The network has reported that an alternative emergency number has been dialed, but the user
     * must exit airplane mode to place the call.
     */
    public static final int IMS_SIP_ALTERNATE_EMERGENCY_CALL = 71;

    /**
     * Indicates that a new outgoing call cannot be placed because there is already an outgoing
     * call dialing out.
     */
    public static final int ALREADY_DIALING = 72;

    /**
     * Indicates that a new outgoing call cannot be placed while there is a ringing call.
     */
    public static final int CANT_CALL_WHILE_RINGING = 73;

    /**
     * Indicates that a new outgoing call cannot be placed because calling has been disabled using
     * the ro.telephony.disable-call system property.
     */
    public static final int CALLING_DISABLED = 74;

    /**
     * Indicates that a new outgoing call cannot be placed because there is currently an ongoing
     * foreground and background call.
     */
    public static final int TOO_MANY_ONGOING_CALLS = 75;

    /**
     * Indicates that a new outgoing call cannot be placed because OTASP provisioning is currently
     * in process.
     */
    public static final int OTASP_PROVISIONING_IN_PROCESS = 76;

    /**
     * Indicates that the call is dropped due to RTCP inactivity, primarily due to media path
     * disruption.
     */
    public static final int MEDIA_TIMEOUT = 77;

    /**
     * Indicates that an emergency call cannot be placed over WFC because the service is not
     * available in the current location.
     */
    public static final int EMERGENCY_CALL_OVER_WFC_NOT_AVAILABLE = 78;

    /**
     * Indicates that WiFi calling service is not available in the current location.
     */
    public static final int WFC_SERVICE_NOT_AVAILABLE_IN_THIS_LOCATION = 79;

    /**
     * Indicates that an emergency call was placed, which caused the existing connection to be
     * hung up.
     */
    public static final int OUTGOING_EMERGENCY_CALL_PLACED = 80;

    /**
     * Indicates that incoming call was rejected by the modem before the call went in ringing
     */
    public static final int INCOMING_AUTO_REJECTED = 81;


    //*********************************************************************************************
    // When adding a disconnect type:
    // 1) Update toString() with the newly added disconnect type.
    // 2) Update android.telecom.DisconnectCauseUtil with any mappings to a telecom.DisconnectCause.
    //*********************************************************************************************

    /** Private constructor to avoid class instantiation. */
    private DisconnectCause() {
        // Do nothing.
    }

    /**
     * Returns descriptive string for the specified disconnect cause.
     * @hide
     */
    @UnsupportedAppUsage
    public static @NonNull String toString(int cause) {
        switch (cause) {
        case NOT_DISCONNECTED:
            return "NOT_DISCONNECTED";
        case INCOMING_MISSED:
            return "INCOMING_MISSED";
        case NORMAL:
            return "NORMAL";
        case LOCAL:
            return "LOCAL";
        case BUSY:
            return "BUSY";
        case CONGESTION:
            return "CONGESTION";
        case INVALID_NUMBER:
            return "INVALID_NUMBER";
        case NUMBER_UNREACHABLE:
            return "NUMBER_UNREACHABLE";
        case SERVER_UNREACHABLE:
            return "SERVER_UNREACHABLE";
        case INVALID_CREDENTIALS:
            return "INVALID_CREDENTIALS";
        case OUT_OF_NETWORK:
            return "OUT_OF_NETWORK";
        case SERVER_ERROR:
            return "SERVER_ERROR";
        case TIMED_OUT:
            return "TIMED_OUT";
        case LOST_SIGNAL:
            return "LOST_SIGNAL";
        case LIMIT_EXCEEDED:
            return "LIMIT_EXCEEDED";
        case INCOMING_REJECTED:
            return "INCOMING_REJECTED";
        case POWER_OFF:
            return "POWER_OFF";
        case OUT_OF_SERVICE:
            return "OUT_OF_SERVICE";
        case ICC_ERROR:
            return "ICC_ERROR";
        case CALL_BARRED:
            return "CALL_BARRED";
        case FDN_BLOCKED:
            return "FDN_BLOCKED";
        case CS_RESTRICTED:
            return "CS_RESTRICTED";
        case CS_RESTRICTED_NORMAL:
            return "CS_RESTRICTED_NORMAL";
        case CS_RESTRICTED_EMERGENCY:
            return "CS_RESTRICTED_EMERGENCY";
        case UNOBTAINABLE_NUMBER:
            return "UNOBTAINABLE_NUMBER";
        case CDMA_LOCKED_UNTIL_POWER_CYCLE:
            return "CDMA_LOCKED_UNTIL_POWER_CYCLE";
        case CDMA_DROP:
            return "CDMA_DROP";
        case CDMA_INTERCEPT:
            return "CDMA_INTERCEPT";
        case CDMA_REORDER:
            return "CDMA_REORDER";
        case CDMA_SO_REJECT:
            return "CDMA_SO_REJECT";
        case CDMA_RETRY_ORDER:
            return "CDMA_RETRY_ORDER";
        case CDMA_ACCESS_FAILURE:
            return "CDMA_ACCESS_FAILURE";
        case CDMA_PREEMPTED:
            return "CDMA_PREEMPTED";
        case CDMA_NOT_EMERGENCY:
            return "CDMA_NOT_EMERGENCY";
        case CDMA_ACCESS_BLOCKED:
            return "CDMA_ACCESS_BLOCKED";
        case EMERGENCY_ONLY:
            return "EMERGENCY_ONLY";
        case NO_PHONE_NUMBER_SUPPLIED:
            return "NO_PHONE_NUMBER_SUPPLIED";
        case DIALED_MMI:
            return "DIALED_MMI";
        case VOICEMAIL_NUMBER_MISSING:
            return "VOICEMAIL_NUMBER_MISSING";
        case CDMA_CALL_LOST:
            return "CDMA_CALL_LOST";
        case EXITED_ECM:
            return "EXITED_ECM";
        case DIAL_MODIFIED_TO_USSD:
            return "DIAL_MODIFIED_TO_USSD";
        case DIAL_MODIFIED_TO_SS:
            return "DIAL_MODIFIED_TO_SS";
        case DIAL_MODIFIED_TO_DIAL:
            return "DIAL_MODIFIED_TO_DIAL";
        case DIAL_MODIFIED_TO_DIAL_VIDEO:
            return "DIAL_MODIFIED_TO_DIAL_VIDEO";
        case DIAL_VIDEO_MODIFIED_TO_SS:
            return "DIAL_VIDEO_MODIFIED_TO_SS";
        case DIAL_VIDEO_MODIFIED_TO_USSD:
            return "DIAL_VIDEO_MODIFIED_TO_USSD";
        case DIAL_VIDEO_MODIFIED_TO_DIAL:
            return "DIAL_VIDEO_MODIFIED_TO_DIAL";
        case DIAL_VIDEO_MODIFIED_TO_DIAL_VIDEO:
            return "DIAL_VIDEO_MODIFIED_TO_DIAL_VIDEO";
        case ERROR_UNSPECIFIED:
            return "ERROR_UNSPECIFIED";
        case OUTGOING_FAILURE:
            return "OUTGOING_FAILURE";
        case OUTGOING_CANCELED:
            return "OUTGOING_CANCELED";
        case IMS_MERGED_SUCCESSFULLY:
            return "IMS_MERGED_SUCCESSFULLY";
        case CDMA_ALREADY_ACTIVATED:
            return "CDMA_ALREADY_ACTIVATED";
        case VIDEO_CALL_NOT_ALLOWED_WHILE_TTY_ENABLED:
            return "VIDEO_CALL_NOT_ALLOWED_WHILE_TTY_ENABLED";
        case CALL_PULLED:
            return "CALL_PULLED";
        case ANSWERED_ELSEWHERE:
            return "ANSWERED_ELSEWHERE";
        case MAXIMUM_NUMBER_OF_CALLS_REACHED:
            return "MAXIMUM_NUMER_OF_CALLS_REACHED";
        case DATA_DISABLED:
            return "DATA_DISABLED";
        case DATA_LIMIT_REACHED:
            return "DATA_LIMIT_REACHED";
        case DIALED_CALL_FORWARDING_WHILE_ROAMING:
            return "DIALED_CALL_FORWARDING_WHILE_ROAMING";
        case IMEI_NOT_ACCEPTED:
            return "IMEI_NOT_ACCEPTED";
        case WIFI_LOST:
            return "WIFI_LOST";
        case IMS_ACCESS_BLOCKED:
            return "IMS_ACCESS_BLOCKED";
        case LOW_BATTERY:
            return "LOW_BATTERY";
        case DIAL_LOW_BATTERY:
            return "DIAL_LOW_BATTERY";
        case EMERGENCY_TEMP_FAILURE:
            return "EMERGENCY_TEMP_FAILURE";
        case EMERGENCY_PERM_FAILURE:
            return "EMERGENCY_PERM_FAILURE";
        case NORMAL_UNSPECIFIED:
            return "NORMAL_UNSPECIFIED";
        case IMS_SIP_ALTERNATE_EMERGENCY_CALL:
            return "IMS_SIP_ALTERNATE_EMERGENCY_CALL";
        case ALREADY_DIALING:
            return "ALREADY_DIALING";
        case CANT_CALL_WHILE_RINGING:
            return "CANT_CALL_WHILE_RINGING";
        case CALLING_DISABLED:
            return "CALLING_DISABLED";
        case TOO_MANY_ONGOING_CALLS:
            return "TOO_MANY_ONGOING_CALLS";
        case OTASP_PROVISIONING_IN_PROCESS:
            return "OTASP_PROVISIONING_IN_PROCESS";
        case MEDIA_TIMEOUT:
            return "MEDIA_TIMEOUT";
        case EMERGENCY_CALL_OVER_WFC_NOT_AVAILABLE:
            return "EMERGENCY_CALL_OVER_WFC_NOT_AVAILABLE";
        case WFC_SERVICE_NOT_AVAILABLE_IN_THIS_LOCATION:
            return "WFC_SERVICE_NOT_AVAILABLE_IN_THIS_LOCATION";
        case OUTGOING_EMERGENCY_CALL_PLACED:
            return "OUTGOING_EMERGENCY_CALL_PLACED";
            case INCOMING_AUTO_REJECTED:
                return "INCOMING_AUTO_REJECTED";
        default:
            return "INVALID: " + cause;
        }
    }
}
