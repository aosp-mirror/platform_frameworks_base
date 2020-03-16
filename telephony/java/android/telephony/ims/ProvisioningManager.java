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
 * limitations under the License.
 */

package android.telephony.ims;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.StringDef;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.annotation.WorkerThread;
import android.os.Binder;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyFrameworkInitializer;
import android.telephony.ims.aidl.IImsConfigCallback;
import android.telephony.ims.feature.MmTelFeature;
import android.telephony.ims.feature.RcsFeature;
import android.telephony.ims.stub.ImsConfigImplBase;
import android.telephony.ims.stub.ImsRegistrationImplBase;

import com.android.internal.telephony.ITelephony;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.Executor;

/**
 * Manages IMS provisioning and configuration parameters, as well as callbacks for apps to listen
 * to changes in these configurations.
 *
 * IMS provisioning keys are defined per carrier or OEM using OMA-DM or other provisioning
 * applications and may vary. It is up to the carrier and OEM applications to ensure that the
 * correct provisioning keys are being used when integrating with a vendor's ImsService.
 *
 * Note: For compatibility purposes, the integer values [0 - 99] used in
 * {@link #setProvisioningIntValue(int, int)} have been reserved for existing provisioning keys
 * previously defined in the Android framework. Please do not redefine new provisioning keys in this
 * range or it may generate collisions with existing keys. Some common constants have also been
 * defined in this class to make integrating with other system apps easier.
 * @hide
 */
@SystemApi
@TestApi
public class ProvisioningManager {

    /**@hide*/
    @StringDef(prefix = "STRING_QUERY_RESULT_ERROR_", value = {
            STRING_QUERY_RESULT_ERROR_GENERIC,
            STRING_QUERY_RESULT_ERROR_NOT_READY
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface StringResultError {}

    /**
     * The query from {@link #getProvisioningStringValue(int)} has resulted in an unspecified error.
     */
    public static final String STRING_QUERY_RESULT_ERROR_GENERIC =
            "STRING_QUERY_RESULT_ERROR_GENERIC";

    /**
     * The query from {@link #getProvisioningStringValue(int)} has resulted in an error because the
     * ImsService implementation was not ready for provisioning queries.
     */
    public static final String STRING_QUERY_RESULT_ERROR_NOT_READY =
            "STRING_QUERY_RESULT_ERROR_NOT_READY";

    /**
     * There is no existing configuration for the queried provisioning key.
     * @hide
     */
    public static final int PROVISIONING_RESULT_UNKNOWN = -1;

    /**
     * The integer result of provisioning for the queried key is disabled.
     */
    public static final int PROVISIONING_VALUE_DISABLED = 0;

    /**
     * The integer result of provisioning for the queried key is enabled.
     */
    public static final int PROVISIONING_VALUE_ENABLED = 1;


    // Inheriting values from ImsConfig for backwards compatibility.
    /**
     * AMR CODEC Mode Value set, 0-7 in comma separated sequence.
     * <p>
     * This corresponds to the {@code mode-set} parameter for the AMR codec.
     * See 3GPP TS 26.101 Table 1A for more information.
     * <p>
     * <UL>
     *     <LI>0 - AMR 4.75 kbit/s</LI>
     *     <LI>1 - AMR 5.15 kbit/s</LI>
     *     <LI>2 - AMR 5.90 kbit/s</LI>
     *     <LI>3 - AMR 6.70 kbit/s (PDC-EFR)</LI>
     *     <LI>4 - AMR 7.40 kbit/s (TDMA-EFR)</LI>
     *     <LI>5 - AMR 7.95 kbit/s</LI>
     *     <LI>6 - AMR 10.2 kbit/s</LI>
     *     <LI>7 - AMR 12.2 kbit/s (GSM-EFR)</LI>
     * </UL>
     * <p>
     * Value is in String format.
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_AMR_CODEC_MODE_SET_VALUES = 0;

    /**
     * Wide Band AMR CODEC Mode Value set,0-7 in comma separated sequence.
     * <p>
     * This corresponds to the {@code mode-set} parameter for the AMR wideband codec.
     * See 3GPP TS 26.101 Table 1A for more information.
     * <p>
     * <UL>
     *     <LI>0 - AMR 4.75 kbit/s</LI>
     *     <LI>1 - AMR 5.15 kbit/s</LI>
     *     <LI>2 - AMR 5.90 kbit/s</LI>
     *     <LI>3 - AMR 6.70 kbit/s (PDC-EFR)</LI>
     *     <LI>4 - AMR 7.40 kbit/s (TDMA-EFR)</LI>
     *     <LI>5 - AMR 7.95 kbit/s</LI>
     *     <LI>6 - AMR 10.2 kbit/s</LI>
     *     <LI>7 - AMR 12.2 kbit/s (GSM-EFR)</LI>
     * </UL>
     * <p>
     * Value is in String format.
     * @see #setProvisioningStringValue(int, String)
     * @see #getProvisioningStringValue(int)
     * @hide
     */
    public static final int KEY_AMR_WB_CODEC_MODE_SET_VALUES = 1;

    /**
     * SIP Session Timer value (seconds).
     * <p>
     * See RFC4028 for more information.
     * <p>
     * Value is in Integer format.
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_SIP_SESSION_TIMER_SEC = 2;

    /**
     * Minimum SIP Session Expiration Timer in (seconds).
     * <p>
     * See RFC4028 for more information.
     * <p>
     * Value is in Integer format.
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_MINIMUM_SIP_SESSION_EXPIRATION_TIMER_SEC = 3;

    /**
     * SIP_INVITE cancellation time out value (in milliseconds). Integer format.
     * <p>
     * See RFC4028 for more information.
     * <p>
     * Value is in Integer format.
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_SIP_INVITE_CANCELLATION_TIMER_MS = 4;

    /**
     * Delay time when an iRAT transitions from eHRPD/HRPD/1xRTT to LTE.
     * Value is in Integer format.
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_TRANSITION_TO_LTE_DELAY_MS = 5;

    /**
     * Silent redial status of Enabled (True), or Disabled (False).
     * Value is in boolean format.
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_ENABLE_SILENT_REDIAL = 6;

    /**
     * An integer key representing the SIP T1 timer value in milliseconds for the associated
     * subscription.
     * <p>
     * The SIP T1 timer is an estimate of the round-trip time and will retransmit
     * INVITE transactions that are longer than T1 milliseconds over unreliable transports, doubling
     * the time before retransmission every time there is no response. See RFC3261, section 17.1.1.1
     * for more details.
     * <p>
     * The value is an integer.
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_T1_TIMER_VALUE_MS = 7;

    /**
     * SIP T2 timer value in milliseconds.  See RFC 3261 for information.
     * <p>
     * The T2 timer is the maximum retransmit interval for non-INVITE requests and INVITE responses.
     * <p>
     * Value is in Integer format.
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_T2_TIMER_VALUE_MS = 8;

    /**
     * SIP TF timer value in milliseconds.  See RFC 3261 for information.
     * <p>
     * The TF timer is the non-INVITE transaction timeout timer.
     * <p>
     * Value is in Integer format.
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_TF_TIMER_VALUE_MS = 9;

    /**
     * An integer key representing the voice over LTE (VoLTE) provisioning status for the
     * associated subscription. Determines whether the user can register for voice services over
     * LTE.
     * <p>
     * Use {@link #PROVISIONING_VALUE_ENABLED} to enable VoLTE provisioning and
     * {@link #PROVISIONING_VALUE_DISABLED} to disable VoLTE provisioning.
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_VOLTE_PROVISIONING_STATUS = 10;

    /**
     * An integer key representing the video telephony (VT) provisioning status for the
     * associated subscription. Determines whether the user can register for video services over
     * LTE.
     * <p>
     * Use {@link #PROVISIONING_VALUE_ENABLED} to enable VT provisioning and
     * {@link #PROVISIONING_VALUE_DISABLED} to disable VT provisioning.
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_VT_PROVISIONING_STATUS = 11;

    /**
     * Domain Name for the device to populate the request URI for REGISTRATION.
     * Value is in String format.
     * @see #setProvisioningStringValue(int, String)
     * @see #getProvisioningStringValue(int)
     * @hide
     */
    public static final int KEY_REGISTRATION_DOMAIN_NAME = 12;

    /**
     * Device Outgoing SMS based on either 3GPP or 3GPP2 standards.
     * Value is in Integer format.
     * Valid values are {@link #SMS_FORMAT_3GPP} and {@link #SMS_FORMAT_3GPP2}.
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_SMS_FORMAT = 13;

    /**
     * Value used with {@link #KEY_SMS_FORMAT} to indicate 3GPP2 SMS format is used.
     * See {@link android.telephony.SmsMessage#FORMAT_3GPP2} for more information.
     * @hide
     */
    public static final int SMS_FORMAT_3GPP2 = 0;

    /**
     * Value used with {@link #KEY_SMS_FORMAT} to indicate 3GPP SMS format is used.
     * See {@link android.telephony.SmsMessage#FORMAT_3GPP} for more information.
     * @hide
     */
    public static final int SMS_FORMAT_3GPP = 1;

    /**
     * Turns SMS over IMS ON/OFF on the device.
     * Value is in Integer format. ON (1), OFF(0).
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_SMS_OVER_IP_ENABLED = 14;

    /**
     * An integer key associated with the carrier configured SIP PUBLISH timer, which dictates the
     * expiration time in seconds for published online availability in RCS presence.
     * <p>
     * Value is in Integer format.
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_RCS_PUBLISH_TIMER_SEC = 15;

    /**
     * An integer key associated with the carrier configured expiration time in seconds for
     * published offline availability in RCS presence provided, which is provided to the network.
     * <p>
     * Value is in Integer format.
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_RCS_PUBLISH_OFFLINE_AVAILABILITY_TIMER_SEC = 16;

    /**
     * An integer key associated with whether or not capability discovery is provisioned for this
     * subscription. Any capability requests will be ignored by the RCS service.
     * <p>
     * The value is an integer, either {@link #PROVISIONING_VALUE_DISABLED} if capability
     * discovery is disabled or {@link #PROVISIONING_VALUE_ENABLED} if capability discovery is
     * enabled.
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_RCS_CAPABILITY_DISCOVERY_ENABLED = 17;

    /**
     * An integer key associated with the period of time in seconds the capability information of
     * each contact is cached on the device.
     * <p>
     * Seconds are used because this is usually measured in the span of days.
     * <p>
     * Value is in Integer format.
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_RCS_CAPABILITIES_CACHE_EXPIRATION_SEC = 18;

    /**
     * An integer key associated with the period of time in seconds that the availability
     * information of a contact is cached on the device, which is based on the carrier provisioning
     * configuration from the network.
     * <p>
     * Value is in Integer format.
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_RCS_AVAILABILITY_CACHE_EXPIRATION_SEC = 19;

    /**
     * An integer key associated with the carrier configured interval in seconds expected between
     * successive capability polling attempts, which is based on the carrier provisioning
     * configuration from the network.
     * <p>
     * Value is in Integer format.
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_RCS_CAPABILITIES_POLL_INTERVAL_SEC = 20;

    /**
     * An integer key representing the minimum time allowed between two consecutive presence publish
     * messages from the device in milliseconds.
     * <p>
     * Value is in Integer format.
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_RCS_PUBLISH_SOURCE_THROTTLE_MS = 21;

    /**
     * An integer key associated with the maximum number of MDNs contained in one SIP Request
     * Contained List (RCS) used to retrieve the RCS capabilities of the contacts book.
     * <p>
     * Value is in Integer format.
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_RCS_MAX_NUM_ENTRIES_IN_RCL = 22;

    /**
     * An integer associated with the expiration timer used during the SIP subscription of a
     * Request Contained List (RCL), which is used to retrieve the RCS capabilities of the contact
     * book. This timer value is sent in seconds to the network.
     * <p>
     * Value is in Integer format.
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_RCS_CAPABILITY_POLL_LIST_SUB_EXP_SEC = 23;

    /**
     * Applies compression to LIST Subscription.
     * Value is in Integer format. Enable (1), Disable(0).
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_USE_GZIP_FOR_LIST_SUBSCRIPTION = 24;

    /**
     * An integer key representing the RCS enhanced address book (EAB) provisioning status for the
     * associated subscription. Determines whether or not SIP OPTIONS or presence will be used to
     * retrieve RCS capabilities for the user's contacts.
     * <p>
     * Use {@link #PROVISIONING_VALUE_ENABLED} to enable EAB provisioning and
     * {@link #PROVISIONING_VALUE_DISABLED} to disable EAB provisioning.
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_EAB_PROVISIONING_STATUS = 25;

    /**
     * Override the user-defined WiFi Roaming enabled setting for this subscription, defined in
     * {@link SubscriptionManager#WFC_ROAMING_ENABLED_CONTENT_URI}, for the purposes of provisioning
     * the subscription for WiFi Calling.
     *
     * @see #getProvisioningIntValue(int)
     * @see #setProvisioningIntValue(int, int)
     */
    public static final int KEY_VOICE_OVER_WIFI_ROAMING_ENABLED_OVERRIDE = 26;

    /**
     * Override the user-defined WiFi mode for this subscription, defined in
     * {@link SubscriptionManager#WFC_MODE_CONTENT_URI}, for the purposes of provisioning
     * this subscription for WiFi Calling.
     *
     * Valid values for this key are:
     * {@link ImsMmTelManager#WIFI_MODE_WIFI_ONLY},
     * {@link ImsMmTelManager#WIFI_MODE_CELLULAR_PREFERRED}, or
     * {@link ImsMmTelManager#WIFI_MODE_WIFI_PREFERRED}.
     *
     * @see #getProvisioningIntValue(int)
     * @see #setProvisioningIntValue(int, int)
     */
    public static final int KEY_VOICE_OVER_WIFI_MODE_OVERRIDE = 27;

    /**
     * Enable voice over wifi.  Enabled (1), or Disabled (0).
     * Value is in Integer format.
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_VOICE_OVER_WIFI_ENABLED_OVERRIDE = 28;

    /**
     * Mobile data enabled.
     * Value is in Integer format. On (1), OFF(0).
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_MOBILE_DATA_ENABLED = 29;

    /**
     * VoLTE user opted in status.
     * Value is in Integer format. Opted-in (1) Opted-out (0).
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_VOLTE_USER_OPT_IN_STATUS = 30;

    /**
     * Proxy for Call Session Control Function(P-CSCF) address for Local-BreakOut(LBO).
     * Value is in String format.
     * @hide
     */
    public static final int KEY_LOCAL_BREAKOUT_PCSCF_ADDRESS = 31;

    /**
     * Keep Alive Enabled for SIP.
     * Value is in Integer format.
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_SIP_KEEP_ALIVE_ENABLED = 32;

    /**
     * Registration retry Base Time value in seconds, which is based off of the carrier
     * configuration.
     * Value is in Integer format.
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_REGISTRATION_RETRY_BASE_TIME_SEC = 33;

    /**
     * Registration retry Max Time value in seconds, which is based off of the carrier
     * configuration.
     * Value is in Integer format.
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_REGISTRATION_RETRY_MAX_TIME_SEC = 34;

    /**
     * Smallest RTP port for speech codec.
     * Value is in integer format.
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */

    public static final int KEY_RTP_SPEECH_START_PORT = 35;

    /**
     * Largest RTP port for speech code.
     * Value is in Integer format.
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_RTP_SPEECH_END_PORT = 36;

    /**
     * SIP Timer A's value in milliseconds. Timer A is the INVITE request retransmit interval (in
     * milliseconds), for UDP only.
     * Value is in Integer format.
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_SIP_INVITE_REQUEST_TRANSMIT_INTERVAL_MS = 37;

    /**
     * SIP Timer B's value in milliseconds. Timer B is the wait time for INVITE message to be,
     * in milliseconds.
     * Value is in Integer format.
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_SIP_INVITE_ACK_WAIT_TIME_MS = 38;

    /**
     * SIP Timer D's value in milliseconds. Timer D is the wait time for response retransmits of
     * the invite client transactions, in milliseconds.
     * Value is in Integer format.
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_SIP_INVITE_RESPONSE_RETRANSMIT_WAIT_TIME_MS = 39;

    /**
     * SIP Timer E's value in milliseconds. Timer E is the value Non-INVITE request retransmit
     * interval (in milliseconds), for UDP only.
     * Value is in Integer format.
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_SIP_NON_INVITE_REQUEST_RETRANSMIT_INTERVAL_MS = 40;

    /**
     * SIP Timer F's value in milliseconds. Timer F is the Non-INVITE transaction timeout timer,
     * in milliseconds.
     * Value is in Integer format.
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_SIP_NON_INVITE_TRANSACTION_TIMEOUT_TIMER_MS = 41;

    /**
     * SIP Timer G's value in milliseconds. Timer G is the value of INVITE response
     * retransmit interval.
     * Value is in Integer format.
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_SIP_INVITE_RESPONSE_RETRANSMIT_INTERVAL_MS = 42;

    /**
     * SIP Timer H's value in milliseconds. Timer H is the value of wait time for
     * ACK receipt.
     * Value is in Integer format.
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_SIP_ACK_RECEIPT_WAIT_TIME_MS = 43;

    /**
     * SIP Timer I's value in milliseconds. Timer I is the value of wait time for
     * ACK retransmits.
     * Value is in Integer format.
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_SIP_ACK_RETRANSMIT_WAIT_TIME_MS = 44;

    /**
     * SIP Timer J's value in milliseconds. Timer J is the value of wait time for
     * non-invite request retransmission.
     * Value is in Integer format.
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_SIP_NON_INVITE_REQUEST_RETRANSMISSION_WAIT_TIME_MS = 45;

    /**
     * SIP Timer K's value in milliseconds. Timer K is the value of wait time for
     * non-invite response retransmits.
     * Value is in Integer format.
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_SIP_NON_INVITE_RESPONSE_RETRANSMISSION_WAIT_TIME_MS = 46;

    /**
     * AMR WB octet aligned dynamic payload type.
     * Value is in Integer format.
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_AMR_WB_OCTET_ALIGNED_PAYLOAD_TYPE = 47;

    /**
     * AMR WB bandwidth efficient payload type.
     * Value is in Integer format.
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_AMR_WB_BANDWIDTH_EFFICIENT_PAYLOAD_TYPE = 48;

    /**
     * AMR octet aligned dynamic payload type.
     * Value is in Integer format.
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_AMR_OCTET_ALIGNED_PAYLOAD_TYPE = 49;

    /**
     * AMR bandwidth efficient payload type.
     * Value is in Integer format.
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_AMR_BANDWIDTH_EFFICIENT_PAYLOAD_TYPE = 50;

    /**
     * DTMF WB payload type.
     * Value is in Integer format.
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_DTMF_WB_PAYLOAD_TYPE = 51;

    /**
     * DTMF NB payload type.
     * Value is in Integer format.
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_DTMF_NB_PAYLOAD_TYPE = 52;

    /**
     * AMR Default encoding mode.
     * Value is in Integer format.
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_AMR_DEFAULT_ENCODING_MODE = 53;

    /**
     * SMS Public Service Identity.
     * Value is in String format.
     * @hide
     */
    public static final int KEY_SMS_PUBLIC_SERVICE_IDENTITY = 54;

    /**
     * Video Quality - VideoQualityFeatureValuesConstants.
     * Valid values are: {@link #VIDEO_QUALITY_HIGH} and {@link #VIDEO_QUALITY_LOW}.
     * Value is in Integer format.
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_VIDEO_QUALITY = 55;

    /**
     * Used with {@link #KEY_VIDEO_QUALITY} to indicate low video quality.
     * @hide
     */
    public static final int VIDEO_QUALITY_LOW = 0;

    /**
     * Used with {@link #KEY_VIDEO_QUALITY} to indicate high video quality.
     * @hide
     */
    public static final int VIDEO_QUALITY_HIGH = 1;

    /**
     * LTE to WIFI handover threshold.
     * Handover from LTE to WiFi if LTE < THLTE1 and WiFi >= {@link #KEY_WIFI_THRESHOLD_A}.
     * Value is in Integer format.
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_LTE_THRESHOLD_1 = 56;

    /**
     * WIFI to LTE handover threshold.
     * Handover from WiFi to LTE if LTE >= {@link #KEY_LTE_THRESHOLD_3} or (WiFi < {@link
     * #KEY_WIFI_THRESHOLD_B} and LTE >= {@link #KEY_LTE_THRESHOLD_2}).
     * Value is in Integer format.
     *
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_LTE_THRESHOLD_2 = 57;

    /**
     * LTE to WIFI handover threshold.
     * Handover from WiFi to LTE if LTE >= {@link #KEY_LTE_THRESHOLD_3} or (WiFi < {@link
     * #KEY_WIFI_THRESHOLD_B} and LTE >= {@link #KEY_LTE_THRESHOLD_2}).
     * Value is in Integer format.
     *
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_LTE_THRESHOLD_3 = 58;

    /**
     * 1x to WIFI handover threshold.
     * Handover from 1x to WiFi if 1x < {@link #KEY_1X_THRESHOLD}.
     * Value is in Integer format.
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_1X_THRESHOLD = 59;

    /**
     * LTE to WIFI threshold A.
     * Handover from LTE to WiFi if LTE < {@link #KEY_LTE_THRESHOLD_1} and WiFi >= {@link
     * #KEY_WIFI_THRESHOLD_A}.
     * Value is in Integer format.
     *
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_WIFI_THRESHOLD_A = 60;

    /**
     * WiFi to LTRE handover threshold B.
     * Handover from WiFi to LTE if LTE >= {@link #KEY_LTE_THRESHOLD_3} or (WiFi <
     * {@link #KEY_WIFI_THRESHOLD_B} and LTE >= {@link #KEY_LTE_THRESHOLD_2}).
     * Value is in Integer format.
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_WIFI_THRESHOLD_B = 61;

    /**
     * LTE ePDG timer (in seconds).
     * Device shall not handover back to LTE until the T_ePDG_LTE timer expires.
     * Value is in Integer format.
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_LTE_EPDG_TIMER_SEC = 62;

    /**
     * WiFi ePDG timer (in seconds).
     * Device shall not handover back to WiFi until the T_ePDG_WiFi timer expires.
     * Value is in Integer format.
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_WIFI_EPDG_TIMER_SEC = 63;

    /**
     * 1x ePDG timer (in seconds).
     * Device shall not re-register on 1x until the T_ePDG_1x timer expires.
     * @hide
     */
    public static final int KEY_1X_EPDG_TIMER_SEC = 64;

    /**
     * MultiEndpoint status: Enabled (1), or Disabled (0).
     * Value is in Integer format.
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_MULTIENDPOINT_ENABLED = 65;

    /**
     * RTT status: Enabled (1), or Disabled (0).
     * Value is in Integer format.
     * @see #setProvisioningIntValue(int, int)
     * @see #getProvisioningIntValue(int)
     * @hide
     */
    public static final int KEY_RTT_ENABLED = 66;

    /**
     * Callback for IMS provisioning changes.
     */
    public static class Callback {

        private static class CallbackBinder extends IImsConfigCallback.Stub {

            private final Callback mLocalConfigurationCallback;
            private Executor mExecutor;

            private CallbackBinder(Callback localConfigurationCallback) {
                mLocalConfigurationCallback = localConfigurationCallback;
            }

            @Override
            public final void onIntConfigChanged(int item, int value) {
                long callingIdentity = Binder.clearCallingIdentity();
                try {
                    mExecutor.execute(() ->
                            mLocalConfigurationCallback.onProvisioningIntChanged(item, value));
                } finally {
                    restoreCallingIdentity(callingIdentity);
                }
            }

            @Override
            public final void onStringConfigChanged(int item, String value) {
                long callingIdentity = Binder.clearCallingIdentity();
                try {
                    mExecutor.execute(() ->
                            mLocalConfigurationCallback.onProvisioningStringChanged(item, value));
                } finally {
                    restoreCallingIdentity(callingIdentity);
                }
            }

            private void setExecutor(Executor executor) {
                mExecutor = executor;
            }
        }

        private final CallbackBinder mBinder = new CallbackBinder(this);

        /**
         * Called when a provisioning item has changed.
         * @param item the IMS provisioning key constant, as defined by the OEM.
         * @param value the new integer value of the IMS provisioning key.
         */
        public void onProvisioningIntChanged(int item, int value) {
            // Base Implementation
        }

        /**
         * Called when a provisioning item has changed.
         * @param item the IMS provisioning key constant, as defined by the OEM.
         * @param value the new String value of the IMS configuration constant.
         */
        public void onProvisioningStringChanged(int item, @NonNull String value) {
            // Base Implementation
        }

        /**@hide*/
        public final IImsConfigCallback getBinder() {
            return mBinder;
        }

        /**@hide*/
        public void setExecutor(Executor executor) {
            mBinder.setExecutor(executor);
        }
    }

    private int mSubId;

    /**
     * Create a new {@link ProvisioningManager} for the subscription specified.
     *
     * @param subId The ID of the subscription that this ProvisioningManager will use.
     * @see android.telephony.SubscriptionManager#getActiveSubscriptionInfoList()
     * @throws IllegalArgumentException if the subscription is invalid.
     */
    public static @NonNull ProvisioningManager createForSubscriptionId(int subId) {
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            throw new IllegalArgumentException("Invalid subscription ID");
        }

        return new ProvisioningManager(subId);
    }

    private ProvisioningManager(int subId) {
        mSubId = subId;
    }

    /**
     * Register a new {@link Callback} to listen to changes to changes in IMS provisioning.
     *
     * When the subscription associated with this callback is removed (SIM removed, ESIM swap,
     * etc...), this callback will automatically be removed.
     * @param executor The {@link Executor} to call the callback methods on
     * @param callback The provisioning callbackto be registered.
     * @see #unregisterProvisioningChangedCallback(Callback)
     * @see SubscriptionManager.OnSubscriptionsChangedListener
     * @throws IllegalArgumentException if the subscription associated with this callback is not
     * active (SIM is not inserted, ESIM inactive) or the subscription is invalid.
     * @throws ImsException if the subscription associated with this callback is valid, but
     * the {@link ImsService} associated with the subscription is not available. This can happen if
     * the service crashed, for example. See {@link ImsException#getCode()} for a more detailed
     * reason.
     */
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public void registerProvisioningChangedCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull Callback callback) throws ImsException {
        callback.setExecutor(executor);
        try {
            getITelephony().registerImsProvisioningChangedCallback(mSubId, callback.getBinder());
        } catch (ServiceSpecificException e) {
            throw new ImsException(e.getMessage(), e.errorCode);
        } catch (RemoteException | IllegalStateException e) {
            throw new ImsException(e.getMessage(), ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
    }

    /**
     * Unregister an existing {@link Callback}. When the subscription associated with this
     * callback is removed (SIM removed, ESIM swap, etc...), this callback will automatically be
     * removed. If this method is called for an inactive subscription, it will result in a no-op.
     * @param callback The existing {@link Callback} to be removed.
     * @see #registerProvisioningChangedCallback(Executor, Callback)
     *
     * @throws IllegalArgumentException if the subscription associated with this callback is
     * invalid.
     */
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public void unregisterProvisioningChangedCallback(@NonNull Callback callback) {
        try {
            getITelephony().unregisterImsProvisioningChangedCallback(mSubId, callback.getBinder());
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Query for the integer value associated with the provided key.
     *
     * This operation is blocking and should not be performed on the UI thread.
     *
     * @param key An integer that represents the provisioning key, which is defined by the OEM.
     * @return an integer value for the provided key, or
     * {@link ImsConfigImplBase#CONFIG_RESULT_UNKNOWN} if the key doesn't exist.
     * @throws IllegalArgumentException if the key provided was invalid.
     */
    @WorkerThread
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public int getProvisioningIntValue(int key) {
        try {
            return getITelephony().getImsProvisioningInt(mSubId, key);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Query for the String value associated with the provided key.
     *
     * This operation is blocking and should not be performed on the UI thread.
     *
     * @param key A String that represents the provisioning key, which is defined by the OEM.
     * @return a String value for the provided key, {@code null} if the key doesn't exist, or
     * {@link StringResultError} if there was an error getting the value for the provided key.
     * @throws IllegalArgumentException if the key provided was invalid.
     */
    @WorkerThread
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public @Nullable @StringResultError String getProvisioningStringValue(int key) {
        try {
            return getITelephony().getImsProvisioningString(mSubId, key);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Set the integer value associated with the provided key.
     *
     * This operation is blocking and should not be performed on the UI thread.
     *
     * Use {@link #setProvisioningStringValue(int, String)} with proper namespacing (to be defined
     * per OEM or carrier) when possible instead to avoid key collision if needed.
     * @param key An integer that represents the provisioning key, which is defined by the OEM.
     * @param value a integer value for the provided key.
     * @return the result of setting the configuration value.
     */
    @WorkerThread
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public @ImsConfigImplBase.SetConfigResult int setProvisioningIntValue(int key, int value) {
        try {
            return getITelephony().setImsProvisioningInt(mSubId, key, value);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Set the String value associated with the provided key.
     *
     * This operation is blocking and should not be performed on the UI thread.
     *
     * @param key A String that represents the provisioning key, which is defined by the OEM and
     *     should be appropriately namespaced to avoid collision.
     * @param value a String value for the provided key.
     * @return the result of setting the configuration value.
     */
    @WorkerThread
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public @ImsConfigImplBase.SetConfigResult int setProvisioningStringValue(int key,
            @NonNull String value) {
        try {
            return getITelephony().setImsProvisioningString(mSubId, key, value);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Set the provisioning status for the IMS MmTel capability using the specified subscription.
     *
     * Provisioning may or may not be required, depending on the carrier configuration. If
     * provisioning is not required for the carrier associated with this subscription or the device
     * does not support the capability/technology combination specified, this operation will be a
     * no-op.
     *
     * @see CarrierConfigManager#KEY_CARRIER_UT_PROVISIONING_REQUIRED_BOOL
     * @see CarrierConfigManager#KEY_CARRIER_VOLTE_PROVISIONING_REQUIRED_BOOL
     * @param isProvisioned true if the device is provisioned for UT over IMS, false otherwise.
     */
    @WorkerThread
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public void setProvisioningStatusForCapability(
            @MmTelFeature.MmTelCapabilities.MmTelCapability int capability,
            @ImsRegistrationImplBase.ImsRegistrationTech int tech,  boolean isProvisioned) {
        try {
            getITelephony().setImsProvisioningStatusForCapability(mSubId, capability, tech,
                    isProvisioned);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Get the provisioning status for the IMS MmTel capability specified.
     *
     * If provisioning is not required for the queried
     * {@link MmTelFeature.MmTelCapabilities.MmTelCapability} and
     * {@link ImsRegistrationImplBase.ImsRegistrationTech} combination specified, this method will
     * always return {@code true}.
     *
     * @see CarrierConfigManager#KEY_CARRIER_UT_PROVISIONING_REQUIRED_BOOL
     * @see CarrierConfigManager#KEY_CARRIER_VOLTE_PROVISIONING_REQUIRED_BOOL
     * @return true if the device is provisioned for the capability or does not require
     * provisioning, false if the capability does require provisioning and has not been
     * provisioned yet.
     */
    @WorkerThread
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public boolean getProvisioningStatusForCapability(
            @MmTelFeature.MmTelCapabilities.MmTelCapability int capability,
            @ImsRegistrationImplBase.ImsRegistrationTech int tech) {
        try {
            return getITelephony().getImsProvisioningStatusForCapability(mSubId, capability, tech);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Get the provisioning status for the IMS RCS capability specified.
     *
     * If provisioning is not required for the queried
     * {@link RcsFeature.RcsImsCapabilities.RcsImsCapabilityFlag} this method will always return
     * {@code true}.
     *
     * @see CarrierConfigManager#KEY_CARRIER_RCS_PROVISIONING_REQUIRED_BOOL
     * @return true if the device is provisioned for the capability or does not require
     * provisioning, false if the capability does require provisioning and has not been
     * provisioned yet.
     */
    @WorkerThread
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public boolean getRcsProvisioningStatusForCapability(
            @RcsFeature.RcsImsCapabilities.RcsImsCapabilityFlag int capability) {
        try {
            return getITelephony().getRcsProvisioningStatusForCapability(mSubId, capability);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Set the provisioning status for the IMS RCS capability using the specified subscription.
     *
     * Provisioning may or may not be required, depending on the carrier configuration. If
     * provisioning is not required for the carrier associated with this subscription or the device
     * does not support the capability/technology combination specified, this operation will be a
     * no-op.
     *
     * @see CarrierConfigManager#KEY_CARRIER_RCS_PROVISIONING_REQUIRED_BOOL
     * @param isProvisioned true if the device is provisioned for the RCS capability specified,
     *                      false otherwise.
     */
    @WorkerThread
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public void setRcsProvisioningStatusForCapability(
            @RcsFeature.RcsImsCapabilities.RcsImsCapabilityFlag int capability,
            boolean isProvisioned) {
        try {
            getITelephony().setRcsProvisioningStatusForCapability(mSubId, capability,
                    isProvisioned);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Notify the framework that an RCS autoconfiguration XML file has been received for
     * provisioning.
     * <p>
     * Requires Permission: Manifest.permission.MODIFY_PHONE_STATE or that the calling app has
     * carrier privileges (see {@link #hasCarrierPrivileges}).
     * @param config The XML file to be read. ASCII/UTF8 encoded text if not compressed.
     * @param isCompressed The XML file is compressed in gzip format and must be decompressed
     *         before being read.
     *
     */
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public void notifyRcsAutoConfigurationReceived(@NonNull byte[] config, boolean isCompressed) {
        if (config == null) {
            throw new IllegalArgumentException("Must include a non-null config XML file.");
        }
        try {
            getITelephony().notifyRcsAutoConfigurationReceived(mSubId, config, isCompressed);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }

    }

    private static ITelephony getITelephony() {
        ITelephony binder = ITelephony.Stub.asInterface(
                TelephonyFrameworkInitializer
                        .getTelephonyServiceManager()
                        .getTelephonyServiceRegisterer()
                        .get());
        if (binder == null) {
            throw new RuntimeException("Could not find Telephony Service.");
        }
        return binder;
    }
}
