/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.ims;

import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.ProvisioningManager;
import android.telephony.ims.aidl.IImsConfig;
import android.telephony.ims.aidl.IImsConfigCallback;

import com.android.internal.telephony.util.HandlerExecutor;
import com.android.telephony.Rlog;

import java.util.concurrent.Executor;

/**
 * Provides APIs to get/set the IMS service feature/capability/parameters.
 * The config items include:
 * 1) Items provisioned by the operator.
 * 2) Items configured by user. Mainly service feature class.
 *
 * @deprecated Use {@link  ProvisioningManager} to change these configurations in the ImsService.
 * @hide
 */
@Deprecated
public class ImsConfig {
    private static final String TAG = "ImsConfig";
    private boolean DBG = true;
    private final IImsConfig miConfig;

    /**
     * Broadcast action: the feature enable status was changed
     *
     * @hide
     */
    public static final String ACTION_IMS_FEATURE_CHANGED =
            "com.android.intent.action.IMS_FEATURE_CHANGED";

    /**
     * Broadcast action: the configuration was changed
     * @deprecated Use {@link android.telephony.ims.ProvisioningManager.Callback} instead.
     * @hide
     */
    public static final String ACTION_IMS_CONFIG_CHANGED =
            "com.android.intent.action.IMS_CONFIG_CHANGED";

    /**
     * Extra parameter "item" of intent ACTION_IMS_FEATURE_CHANGED and ACTION_IMS_CONFIG_CHANGED.
     * It is the value of FeatureConstants or ConfigConstants.
     *
     * @hide
     */
    public static final String EXTRA_CHANGED_ITEM = "item";

    /**
     * Extra parameter "value" of intent ACTION_IMS_FEATURE_CHANGED and ACTION_IMS_CONFIG_CHANGED.
     * It is the new value of "item".
     *
     * @hide
     */
    public static final String EXTRA_NEW_VALUE = "value";

    /**
    * Defines IMS service/capability feature constants.
    * @deprecated Use
     * {@link android.telephony.ims.feature.MmTelFeature.MmTelCapabilities.MmTelCapability} instead.
    */
    public static class FeatureConstants {
        public static final int FEATURE_TYPE_UNKNOWN = -1;

        /**
         * FEATURE_TYPE_VOLTE supports features defined in 3GPP and
         * GSMA IR.92 over LTE.
         */
        public static final int FEATURE_TYPE_VOICE_OVER_LTE = 0;

        /**
         * FEATURE_TYPE_LVC supports features defined in 3GPP and
         * GSMA IR.94 over LTE.
         */
        public static final int FEATURE_TYPE_VIDEO_OVER_LTE = 1;

        /**
         * FEATURE_TYPE_VOICE_OVER_WIFI supports features defined in 3GPP and
         * GSMA IR.92 over WiFi.
         */
        public static final int FEATURE_TYPE_VOICE_OVER_WIFI = 2;

        /**
         * FEATURE_TYPE_VIDEO_OVER_WIFI supports features defined in 3GPP and
         * GSMA IR.94 over WiFi.
         */
        public static final int FEATURE_TYPE_VIDEO_OVER_WIFI = 3;

        /**
         * FEATURE_TYPE_UT supports features defined in 3GPP and
         * GSMA IR.92 over LTE.
         */
        public static final int FEATURE_TYPE_UT_OVER_LTE = 4;

       /**
         * FEATURE_TYPE_UT_OVER_WIFI supports features defined in 3GPP and
         * GSMA IR.92 over WiFi.
         */
        public static final int FEATURE_TYPE_UT_OVER_WIFI = 5;
    }

    /**
    * Defines IMS service/capability parameters.
    */
    public static class ConfigConstants {

        // Define IMS config items
        public static final int CONFIG_START = 0;

        // Define operator provisioned config items
        public static final int PROVISIONED_CONFIG_START = CONFIG_START;

        /**
         * AMR CODEC Mode Value set, 0-7 in comma separated sequence.
         * Value is in String format.
         * @deprecated use {@link ProvisioningManager#KEY_AMR_CODEC_MODE_SET_VALUES} instead.
         */
        @Deprecated
        public static final int VOCODER_AMRMODESET =
                ProvisioningManager.KEY_AMR_CODEC_MODE_SET_VALUES;

        /**
         * Wide Band AMR CODEC Mode Value set,0-7 in comma separated sequence.
         * Value is in String format.
         * @deprecated use {@link ProvisioningManager#KEY_AMR_WB_CODEC_MODE_SET_VALUES} instead.
         */
        @Deprecated
        public static final int VOCODER_AMRWBMODESET =
                ProvisioningManager.KEY_AMR_WB_CODEC_MODE_SET_VALUES;

        /**
         * SIP Session Timer value (seconds).
         * Value is in Integer format.
         * @deprecated use {@link ProvisioningManager#KEY_SIP_SESSION_TIMER_SEC} instead.
         */
        @Deprecated
        public static final int SIP_SESSION_TIMER = ProvisioningManager.KEY_SIP_SESSION_TIMER_SEC;

        /**
         * Minimum SIP Session Expiration Timer in (seconds).
         * Value is in Integer format.
         * @deprecated use
         * {@link ProvisioningManager#KEY_MINIMUM_SIP_SESSION_EXPIRATION_TIMER_SEC} instead.
         */
        @Deprecated
        public static final int MIN_SE =
                ProvisioningManager.KEY_MINIMUM_SIP_SESSION_EXPIRATION_TIMER_SEC;

        /**
         * SIP_INVITE cancellation time out value (in milliseconds). Integer format.
         * Value is in Integer format.
         * @deprecated use {@link ProvisioningManager#KEY_SIP_INVITE_CANCELLATION_TIMER_MS} instead.
         */
        @Deprecated
        public static final int CANCELLATION_TIMER =
                ProvisioningManager.KEY_SIP_INVITE_CANCELLATION_TIMER_MS;

        /**
         * Delay time when an iRAT transition from eHRPD/HRPD/1xRTT to LTE.
         * Value is in Integer format.
         * @deprecated use {@link ProvisioningManager#KEY_TRANSITION_TO_LTE_DELAY_MS} instead.
         */
        @Deprecated
        public static final int TDELAY = ProvisioningManager.KEY_TRANSITION_TO_LTE_DELAY_MS;

        /**
         * Silent redial status of Enabled (True), or Disabled (False).
         * Value is in Integer format.
         * @deprecated use {@link ProvisioningManager#KEY_ENABLE_SILENT_REDIAL} instead.
         */
        @Deprecated
        public static final int SILENT_REDIAL_ENABLE = ProvisioningManager.KEY_ENABLE_SILENT_REDIAL;

        /**
         * SIP T1 timer value in milliseconds. See RFC 3261 for define.
         * Value is in Integer format.
         * @deprecated use {@link ProvisioningManager#KEY_T1_TIMER_VALUE_MS} instead.
         */
        @Deprecated
        public static final int SIP_T1_TIMER = ProvisioningManager.KEY_T1_TIMER_VALUE_MS;

        /**
         * SIP T2 timer value in milliseconds.  See RFC 3261 for define.
         * Value is in Integer format.
         * @deprecated use {@link ProvisioningManager#KEY_T2_TIMER_VALUE_MS} instead.
         */
        @Deprecated
        public static final int SIP_T2_TIMER  = ProvisioningManager.KEY_T2_TIMER_VALUE_MS;

        /**
         * SIP TF timer value in milliseconds.  See RFC 3261 for define.
         * Value is in Integer format.
         * @deprecated use {@link ProvisioningManager#KEY_TF_TIMER_VALUE_MS} instead.
         */
        @Deprecated
        public static final int SIP_TF_TIMER = ProvisioningManager.KEY_TF_TIMER_VALUE_MS;

        /**
         * VoLTE status for VLT/s status of Enabled (1), or Disabled (0).
         * Value is in Integer format.
         * @deprecated use {@link ProvisioningManager#KEY_VOLTE_PROVISIONING_STATUS} instead.
         */
        @Deprecated
        public static final int VLT_SETTING_ENABLED =
                ProvisioningManager.KEY_VOLTE_PROVISIONING_STATUS;

        /**
         * VoLTE status for LVC/s status of Enabled (1), or Disabled (0).
         * Value is in Integer format.
         * @deprecated use {@link ProvisioningManager#KEY_VT_PROVISIONING_STATUS} instead.
         */
        @Deprecated
        public static final int LVC_SETTING_ENABLED =
                ProvisioningManager.KEY_VT_PROVISIONING_STATUS;

        /**
         * Domain Name for the device to populate the request URI for REGISTRATION.
         * Value is in String format.
         * @deprecated use {@link ProvisioningManager#KEY_REGISTRATION_DOMAIN_NAME}.
         */
        @Deprecated
        public static final int DOMAIN_NAME = ProvisioningManager.KEY_REGISTRATION_DOMAIN_NAME;

         /**
         * Device Outgoing SMS based on either 3GPP or 3GPP2 standards.
         * Value is in Integer format. 3GPP2(0), 3GPP(1)
          * @deprecated use {@link ProvisioningManager#KEY_SMS_FORMAT}.
          */
         @Deprecated
         public static final int SMS_FORMAT = ProvisioningManager.KEY_SMS_FORMAT;

         /**
         * Turns IMS ON/OFF on the device.
         * Value is in Integer format. ON (1), OFF(0).
          * @deprecated use {@link ProvisioningManager#KEY_SMS_OVER_IP_ENABLED}.
          */
         @Deprecated
         public static final int SMS_OVER_IP = ProvisioningManager.KEY_SMS_OVER_IP_ENABLED;

        /**
         * Requested expiration for Published Online availability.
         * Value is in Integer format.
         * @deprecated use {@link ProvisioningManager#KEY_RCS_PUBLISH_TIMER_SEC}.
         */
        @Deprecated
        public static final int PUBLISH_TIMER = ProvisioningManager.KEY_RCS_PUBLISH_TIMER_SEC;

        /**
         * Requested expiration for Published Offline availability.
         * Value is in Integer format.
         * @deprecated use
         *     {@link ProvisioningManager#KEY_RCS_PUBLISH_OFFLINE_AVAILABILITY_TIMER_SEC}.
         */
        @Deprecated
        public static final int PUBLISH_TIMER_EXTENDED =
                ProvisioningManager.KEY_RCS_PUBLISH_OFFLINE_AVAILABILITY_TIMER_SEC;

        /**
         *
         * Value is in Integer format.
         * @deprecated use {@link ProvisioningManager#KEY_RCS_CAPABILITY_DISCOVERY_ENABLED}.
         */
        @Deprecated
        public static final int CAPABILITY_DISCOVERY_ENABLED =
                ProvisioningManager.KEY_RCS_CAPABILITY_DISCOVERY_ENABLED;

        /**
         * Period of time the capability information of the contact is cached on handset.
         * Value is in Integer format.
         * @deprecated use {@link ProvisioningManager#KEY_RCS_CAPABILITIES_CACHE_EXPIRATION_SEC}.
         */
        @Deprecated
        public static final int CAPABILITIES_CACHE_EXPIRATION =
                ProvisioningManager.KEY_RCS_CAPABILITIES_CACHE_EXPIRATION_SEC;

        /**
         * Peiod of time the availability information of a contact is cached on device.
         * Value is in Integer format.
         * @deprecated use {@link ProvisioningManager#KEY_RCS_AVAILABILITY_CACHE_EXPIRATION_SEC}.
         */
        @Deprecated
        public static final int AVAILABILITY_CACHE_EXPIRATION =
                ProvisioningManager.KEY_RCS_AVAILABILITY_CACHE_EXPIRATION_SEC;

        /**
         * Interval between successive capabilities polling.
         * Value is in Integer format.
         * @deprecated use {@link ProvisioningManager#KEY_RCS_CAPABILITIES_POLL_INTERVAL_SEC}.
         */
        @Deprecated
        public static final int CAPABILITIES_POLL_INTERVAL =
                ProvisioningManager.KEY_RCS_CAPABILITIES_POLL_INTERVAL_SEC;

        /**
         * Minimum time between two published messages from the device.
         * Value is in Integer format.
         * @deprecated use {@link ProvisioningManager#KEY_RCS_PUBLISH_SOURCE_THROTTLE_MS}.
         */
        @Deprecated
        public static final int SOURCE_THROTTLE_PUBLISH =
                ProvisioningManager.KEY_RCS_PUBLISH_SOURCE_THROTTLE_MS;

        /**
         * The Maximum number of MDNs contained in one Request Contained List.
         * Value is in Integer format.
         * @deprecated use {@link ProvisioningManager#KEY_RCS_MAX_NUM_ENTRIES_IN_RCL}.
         */
        @Deprecated
        public static final int MAX_NUMENTRIES_IN_RCL =
                ProvisioningManager.KEY_RCS_MAX_NUM_ENTRIES_IN_RCL;

        /**
         * Expiration timer for subscription of a Request Contained List, used in capability
         * polling.
         * Value is in Integer format.
         * @deprecated use {@link ProvisioningManager#KEY_RCS_CAPABILITY_POLL_LIST_SUB_EXP_SEC}.
         */
        @Deprecated
        public static final int CAPAB_POLL_LIST_SUB_EXP =
                ProvisioningManager.KEY_RCS_CAPABILITY_POLL_LIST_SUB_EXP_SEC;

        /**
         * Applies compression to LIST Subscription.
         * Value is in Integer format. Enable (1), Disable(0).
         * @deprecated use {@link ProvisioningManager#KEY_USE_GZIP_FOR_LIST_SUBSCRIPTION}.
         */
        @Deprecated
        public static final int GZIP_FLAG = ProvisioningManager.KEY_USE_GZIP_FOR_LIST_SUBSCRIPTION;

        /**
         * VOLTE Status for EAB/s status of Enabled (1), or Disabled (0).
         * Value is in Integer format.
         * @deprecated use {@link ProvisioningManager#KEY_EAB_PROVISIONING_STATUS}.
         */
        @Deprecated
        public static final int EAB_SETTING_ENABLED =
                ProvisioningManager.KEY_EAB_PROVISIONING_STATUS;

        /**
         * Wi-Fi calling roaming status.
         * Value is in Integer format. ON (1), OFF(0).
         * @deprecated use {@link ProvisioningManager#KEY_VOICE_OVER_WIFI_ROAMING_ENABLED_OVERRIDE}
         * instead.
         */
        @Deprecated
        public static final int VOICE_OVER_WIFI_ROAMING =
                ProvisioningManager.KEY_VOICE_OVER_WIFI_ROAMING_ENABLED_OVERRIDE;

        /**
         * Wi-Fi calling mode - WfcModeFeatureValueConstants.
         * Value is in Integer format.
         * @deprecated use {@link ProvisioningManager#KEY_VOICE_OVER_WIFI_MODE_OVERRIDE}
         * instead.
         */
        @Deprecated
        public static final int VOICE_OVER_WIFI_MODE =
                ProvisioningManager.KEY_VOICE_OVER_WIFI_MODE_OVERRIDE;

        /**
         * VOLTE Status for voice over wifi status of Enabled (1), or Disabled (0).
         * Value is in Integer format.
         * @deprecated use {@link ProvisioningManager#KEY_VOICE_OVER_WIFI_ENABLED_OVERRIDE}.
         */
        @Deprecated
        public static final int VOICE_OVER_WIFI_SETTING_ENABLED =
                ProvisioningManager.KEY_VOICE_OVER_WIFI_ENABLED_OVERRIDE;


        /**
         * Mobile data enabled.
         * Value is in Integer format. On (1), OFF(0).
         * @deprecated use {@link ProvisioningManager#KEY_MOBILE_DATA_ENABLED}.
         */
        @Deprecated
        public static final int MOBILE_DATA_ENABLED = ProvisioningManager.KEY_MOBILE_DATA_ENABLED;

        /**
         * VoLTE user opted in status.
         * Value is in Integer format. Opted-in (1) Opted-out (0).
         * @deprecated use {@link ProvisioningManager#KEY_VOLTE_USER_OPT_IN_STATUS}.
         */
        @Deprecated
        public static final int VOLTE_USER_OPT_IN_STATUS =
                ProvisioningManager.KEY_VOLTE_USER_OPT_IN_STATUS;

        /**
         * Proxy for Call Session Control Function(P-CSCF) address for Local-BreakOut(LBO).
         * Value is in String format.
         * @deprecated use {@link ProvisioningManager#KEY_LOCAL_BREAKOUT_PCSCF_ADDRESS}.
         */
        @Deprecated
        public static final int LBO_PCSCF_ADDRESS =
                ProvisioningManager.KEY_LOCAL_BREAKOUT_PCSCF_ADDRESS;

        /**
         * Keep Alive Enabled for SIP.
         * Value is in Integer format. On(1), OFF(0).
         * @deprecated use {@link ProvisioningManager#KEY_SIP_KEEP_ALIVE_ENABLED}.
         */
        @Deprecated
        public static final int KEEP_ALIVE_ENABLED = ProvisioningManager.KEY_SIP_KEEP_ALIVE_ENABLED;

        /**
         * Registration retry Base Time value in seconds.
         * Value is in Integer format.
         * @deprecated use {@link ProvisioningManager#KEY_REGISTRATION_RETRY_BASE_TIME_SEC}.
         */
        @Deprecated
        public static final int REGISTRATION_RETRY_BASE_TIME_SEC =
                ProvisioningManager.KEY_REGISTRATION_RETRY_BASE_TIME_SEC;

        /**
         * Registration retry Max Time value in seconds.
         * Value is in Integer format.
         * @deprecated use {@link ProvisioningManager#KEY_REGISTRATION_RETRY_MAX_TIME_SEC}.
         */
        @Deprecated
        public static final int REGISTRATION_RETRY_MAX_TIME_SEC =
                ProvisioningManager.KEY_REGISTRATION_RETRY_MAX_TIME_SEC;

        /**
         * Smallest RTP port for speech codec.
         * Value is in integer format.
         * @deprecated use {@link ProvisioningManager#KEY_RTP_SPEECH_START_PORT}.
         */
        @Deprecated
        public static final int SPEECH_START_PORT = ProvisioningManager.KEY_RTP_SPEECH_START_PORT;

        /**
         * Largest RTP port for speech code.
         * Value is in Integer format.
         * @deprecated use {@link ProvisioningManager#KEY_RTP_SPEECH_END_PORT}.
         */
        @Deprecated
        public static final int SPEECH_END_PORT = ProvisioningManager.KEY_RTP_SPEECH_END_PORT;

        /**
         * SIP Timer A's value in milliseconds. Timer A is the INVITE request
         * retransmit interval, for UDP only.
         * Value is in Integer format.
         * @deprecated use {@link ProvisioningManager#KEY_SIP_INVITE_REQUEST_TRANSMIT_INTERVAL_MS}.
         */
        @Deprecated
        public static final int SIP_INVITE_REQ_RETX_INTERVAL_MSEC =
                ProvisioningManager.KEY_SIP_INVITE_REQUEST_TRANSMIT_INTERVAL_MS;

        /**
         * SIP Timer B's value in milliseconds. Timer B is the wait time for
         * INVITE message to be acknowledged.
         * Value is in Integer format.
         * @deprecated use {@link ProvisioningManager#KEY_SIP_INVITE_ACK_WAIT_TIME_MS}.
         */
        @Deprecated
        public static final int SIP_INVITE_RSP_WAIT_TIME_MSEC =
                ProvisioningManager.KEY_SIP_INVITE_ACK_WAIT_TIME_MS;

        /**
         * SIP Timer D's value in milliseconds. Timer D is the wait time for
         * response retransmits of the invite client transactions.
         * Value is in Integer format.
         * @deprecated use
         * {@link ProvisioningManager#KEY_SIP_INVITE_RESPONSE_RETRANSMIT_WAIT_TIME_MS}.
         */
        @Deprecated
        public static final int SIP_INVITE_RSP_RETX_WAIT_TIME_MSEC =
                ProvisioningManager.KEY_SIP_INVITE_RESPONSE_RETRANSMIT_WAIT_TIME_MS;

        /**
         * SIP Timer E's value in milliseconds. Timer E is the value Non-INVITE
         * request retransmit interval, for UDP only.
         * Value is in Integer format.
         * @deprecated use
         * {@link ProvisioningManager#KEY_SIP_NON_INVITE_REQUEST_RETRANSMIT_INTERVAL_MS}.
         */
        @Deprecated
        public static final int SIP_NON_INVITE_REQ_RETX_INTERVAL_MSEC =
                ProvisioningManager.KEY_SIP_NON_INVITE_REQUEST_RETRANSMIT_INTERVAL_MS;

        /**
         * SIP Timer F's value in milliseconds. Timer F is the Non-INVITE transaction
         * timeout timer.
         * Value is in Integer format.
         * @deprecated use
         * {@link ProvisioningManager#KEY_SIP_NON_INVITE_TRANSACTION_TIMEOUT_TIMER_MS}.
         */
        @Deprecated
        public static final int SIP_NON_INVITE_TXN_TIMEOUT_TIMER_MSEC =
                ProvisioningManager.KEY_SIP_NON_INVITE_TRANSACTION_TIMEOUT_TIMER_MS;

        /**
         * SIP Timer G's value in milliseconds. Timer G is the value of INVITE response
         * retransmit interval.
         * Value is in Integer format.
         * @deprecated use
         * {@link ProvisioningManager#KEY_SIP_INVITE_RESPONSE_RETRANSMIT_INTERVAL_MS}.
         */
        @Deprecated
        public static final int SIP_INVITE_RSP_RETX_INTERVAL_MSEC =
                ProvisioningManager.KEY_SIP_INVITE_RESPONSE_RETRANSMIT_INTERVAL_MS;

        /**
         * SIP Timer H's value in milliseconds. Timer H is the value of wait time for
         * ACK receipt.
         * Value is in Integer format.
         * @deprecated use {@link ProvisioningManager#KEY_SIP_ACK_RECEIPT_WAIT_TIME_MS}.
         */
        @Deprecated
        public static final int SIP_ACK_RECEIPT_WAIT_TIME_MSEC =
                ProvisioningManager.KEY_SIP_ACK_RECEIPT_WAIT_TIME_MS;

        /**
         * SIP Timer I's value in milliseconds. Timer I is the value of wait time for
         * ACK retransmits.
         * Value is in Integer format.
         * @deprecated use {@link ProvisioningManager#KEY_SIP_ACK_RETRANSMIT_WAIT_TIME_MS}.
         */
        @Deprecated
        public static final int SIP_ACK_RETX_WAIT_TIME_MSEC =
                ProvisioningManager.KEY_SIP_ACK_RETRANSMIT_WAIT_TIME_MS;

        /**
         * SIP Timer J's value in milliseconds. Timer J is the value of wait time for
         * non-invite request retransmission.
         * Value is in Integer format.
         * @deprecated use
         * {@link ProvisioningManager#KEY_SIP_NON_INVITE_REQUEST_RETRANSMISSION_WAIT_TIME_MS}.
         */
        @Deprecated
        public static final int SIP_NON_INVITE_REQ_RETX_WAIT_TIME_MSEC =
                ProvisioningManager.KEY_SIP_NON_INVITE_REQUEST_RETRANSMISSION_WAIT_TIME_MS;

        /**
         * SIP Timer K's value in milliseconds. Timer K is the value of wait time for
         * non-invite response retransmits.
         * Value is in Integer format.
         * @deprecated use
         * {@link ProvisioningManager#KEY_SIP_NON_INVITE_RESPONSE_RETRANSMISSION_WAIT_TIME_MS}.
         */
        @Deprecated
        public static final int SIP_NON_INVITE_RSP_RETX_WAIT_TIME_MSEC =
                ProvisioningManager.KEY_SIP_NON_INVITE_RESPONSE_RETRANSMISSION_WAIT_TIME_MS;

        /**
         * AMR WB octet aligned dynamic payload type.
         * Value is in Integer format.
         * @deprecated use {@link ProvisioningManager#KEY_AMR_WB_OCTET_ALIGNED_PAYLOAD_TYPE}.
         */
        @Deprecated
        public static final int AMR_WB_OCTET_ALIGNED_PT =
                ProvisioningManager.KEY_AMR_WB_OCTET_ALIGNED_PAYLOAD_TYPE;

        /**
         * AMR WB bandwidth efficient payload type.
         * Value is in Integer format.
         * @deprecated use {@link ProvisioningManager#KEY_AMR_WB_BANDWIDTH_EFFICIENT_PAYLOAD_TYPE}.
         */
        @Deprecated
        public static final int AMR_WB_BANDWIDTH_EFFICIENT_PT =
                ProvisioningManager.KEY_AMR_WB_BANDWIDTH_EFFICIENT_PAYLOAD_TYPE;

        /**
         * AMR octet aligned dynamic payload type.
         * Value is in Integer format.
         * @deprecated use {@link ProvisioningManager#KEY_AMR_OCTET_ALIGNED_PAYLOAD_TYPE}.
         */
        @Deprecated
        public static final int AMR_OCTET_ALIGNED_PT =
                ProvisioningManager.KEY_AMR_OCTET_ALIGNED_PAYLOAD_TYPE;

        /**
         * AMR bandwidth efficient payload type.
         * Value is in Integer format.
         * @deprecated use {@link ProvisioningManager#KEY_AMR_BANDWIDTH_EFFICIENT_PAYLOAD_TYPE}.
         */
        @Deprecated
        public static final int AMR_BANDWIDTH_EFFICIENT_PT =
                ProvisioningManager.KEY_AMR_BANDWIDTH_EFFICIENT_PAYLOAD_TYPE;

        /**
         * DTMF WB payload type.
         * Value is in Integer format.
         * @deprecated use {@link ProvisioningManager#KEY_DTMF_WB_PAYLOAD_TYPE}.
         */
        @Deprecated
        public static final int DTMF_WB_PT = ProvisioningManager.KEY_DTMF_WB_PAYLOAD_TYPE;

        /**
         * DTMF NB payload type.
         * Value is in Integer format.
         * @deprecated use {@link ProvisioningManager#KEY_DTMF_NB_PAYLOAD_TYPE}.
         */
        @Deprecated
        public static final int DTMF_NB_PT = ProvisioningManager.KEY_DTMF_NB_PAYLOAD_TYPE;

        /**
         * AMR Default encoding mode.
         * Value is in Integer format.
         * @deprecated use {@link ProvisioningManager#KEY_AMR_DEFAULT_ENCODING_MODE}.
         */
        @Deprecated
        public static final int AMR_DEFAULT_MODE =
                ProvisioningManager.KEY_AMR_DEFAULT_ENCODING_MODE;

        /**
         * SMS Public Service Identity.
         * Value is in String format.
         * @deprecated use {@link ProvisioningManager#KEY_SMS_PUBLIC_SERVICE_IDENTITY}.
         */
        @Deprecated
        public static final int SMS_PSI = ProvisioningManager.KEY_SMS_PUBLIC_SERVICE_IDENTITY;

        /**
         * Video Quality - VideoQualityFeatureValuesConstants.
         * Value is in Integer format.
         * @deprecated use {@link ProvisioningManager#KEY_VIDEO_QUALITY}.
         */
        @Deprecated
        public static final int VIDEO_QUALITY = ProvisioningManager.KEY_VIDEO_QUALITY;

        /**
         * LTE threshold.
         * Handover from LTE to WiFi if LTE < THLTE1 and WiFi >= VOWT_A.
         * @deprecated use {@link ProvisioningManager#KEY_LTE_THRESHOLD_1}.
         */
        @Deprecated
        public static final int TH_LTE1 = ProvisioningManager.KEY_LTE_THRESHOLD_1;

        /**
         * LTE threshold.
         * Handover from WiFi to LTE if LTE >= THLTE3 or (WiFi < VOWT_B and LTE >= THLTE2).
         * @deprecated use {@link ProvisioningManager#KEY_LTE_THRESHOLD_2}.
         */
        @Deprecated
        public static final int TH_LTE2 = ProvisioningManager.KEY_LTE_THRESHOLD_2;

        /**
         * LTE threshold.
         * Handover from WiFi to LTE if LTE >= THLTE3 or (WiFi < VOWT_B and LTE >= THLTE2).
         * @deprecated use {@link ProvisioningManager#KEY_LTE_THRESHOLD_3}.
         */
        @Deprecated
        public static final int TH_LTE3 = ProvisioningManager.KEY_LTE_THRESHOLD_3;

        /**
         * 1x threshold.
         * Handover from 1x to WiFi if 1x < TH1x
         * @deprecated use {@link ProvisioningManager#KEY_1X_THRESHOLD}.
         */
        @Deprecated
        public static final int TH_1x = ProvisioningManager.KEY_1X_THRESHOLD;

        /**
         * WiFi threshold.
         * Handover from LTE to WiFi if LTE < THLTE1 and WiFi >= VOWT_A.
         * @deprecated use {@link ProvisioningManager#KEY_WIFI_THRESHOLD_A}.
         */
        @Deprecated
        public static final int VOWT_A = ProvisioningManager.KEY_WIFI_THRESHOLD_A;

        /**
         * WiFi threshold.
         * Handover from WiFi to LTE if LTE >= THLTE3 or (WiFi < VOWT_B and LTE >= THLTE2).
         * @deprecated use {@link ProvisioningManager#KEY_WIFI_THRESHOLD_B}.
         */
        @Deprecated
        public static final int VOWT_B = ProvisioningManager.KEY_WIFI_THRESHOLD_B;

        /**
         * LTE ePDG timer.
         * Device shall not handover back to LTE until the T_ePDG_LTE timer expires.
         * @deprecated use {@link ProvisioningManager#KEY_LTE_EPDG_TIMER_SEC}.
         */
        @Deprecated
        public static final int T_EPDG_LTE = ProvisioningManager.KEY_LTE_EPDG_TIMER_SEC;

        /**
         * WiFi ePDG timer.
         * Device shall not handover back to WiFi until the T_ePDG_WiFi timer expires.
         * @deprecated use {@link ProvisioningManager#KEY_WIFI_EPDG_TIMER_SEC}.
         */
        @Deprecated
        public static final int T_EPDG_WIFI = ProvisioningManager.KEY_WIFI_EPDG_TIMER_SEC;

        /**
         * 1x ePDG timer.
         * Device shall not re-register on 1x until the T_ePDG_1x timer expires.
         * @deprecated use {@link ProvisioningManager#KEY_1X_EPDG_TIMER_SEC}.
         */
        @Deprecated
        public static final int T_EPDG_1X = ProvisioningManager.KEY_1X_EPDG_TIMER_SEC;

        /**
         * MultiEndpoint status: Enabled (1), or Disabled (0).
         * Value is in Integer format.
         * @deprecated use {@link ProvisioningManager#KEY_MULTIENDPOINT_ENABLED}.
         */
        @Deprecated
        public static final int VICE_SETTING_ENABLED = ProvisioningManager.KEY_MULTIENDPOINT_ENABLED;

        /**
         * RTT status: Enabled (1), or Disabled (0).
         * Value is in Integer format.
         * @deprecated use {@link ProvisioningManager#KEY_RTT_ENABLED}.
         */
        @Deprecated
        public static final int RTT_SETTING_ENABLED = ProvisioningManager.KEY_RTT_ENABLED;

        // Expand the operator config items as needed here, need to change
        // PROVISIONED_CONFIG_END after that.
        public static final int PROVISIONED_CONFIG_END = RTT_SETTING_ENABLED;

        // Expand the operator config items as needed here.
    }

    /**
    * Defines IMS set operation status.
    */
    public static class OperationStatusConstants {
        public static final int UNKNOWN = -1;
        public static final int SUCCESS = 0;
        public static final int FAILED =  1;
        public static final int UNSUPPORTED_CAUSE_NONE = 2;
        public static final int UNSUPPORTED_CAUSE_RAT = 3;
        public static final int UNSUPPORTED_CAUSE_DISABLED = 4;
    }

    /**
     * Defines IMS get operation values.
     */
    public static class OperationValuesConstants {
        /**
         * Values related to Video Quality
         */
        public static final int VIDEO_QUALITY_UNKNOWN = -1;
        public static final int VIDEO_QUALITY_LOW = 0;
        public static final int VIDEO_QUALITY_HIGH = 1;
    }

    /**
     * Defines IMS video quality feature value.
     */
    public static class VideoQualityFeatureValuesConstants {
        public static final int LOW = 0;
        public static final int HIGH = 1;
    }

   /**
    * Defines IMS feature value.
    */
    public static class FeatureValueConstants {
        public static final int ERROR = -1;
        public static final int OFF = 0;
        public static final int ON = 1;
    }

    /**
     * Defines IMS feature value.
     */
    public static class WfcModeFeatureValueConstants {
        public static final int WIFI_ONLY = 0;
        public static final int CELLULAR_PREFERRED = 1;
        public static final int WIFI_PREFERRED = 2;
    }

    public ImsConfig(IImsConfig iconfig) {
        miConfig = iconfig;
    }

    /**
     * @deprecated see {@link #getConfigInt(int)} instead.
     */
    public int getProvisionedValue(int item) throws ImsException {
        return getConfigInt(item);
    }

    /**
     * Gets the configuration value for IMS service/capabilities parameters used by IMS stack.
     *
     * @param item, as defined in com.android.ims.ImsConfig#ConfigConstants.
     * @return the value in Integer format.
     * @throws ImsException if the ImsService is unavailable.
     */
    public int getConfigInt(int item) throws ImsException {
        int ret = 0;
        try {
            ret = miConfig.getConfigInt(item);
        }  catch (RemoteException e) {
            throw new ImsException("getInt()", e,
                    ImsReasonInfo.CODE_LOCAL_SERVICE_UNAVAILABLE);
        }
        if (DBG) Rlog.d(TAG, "getInt(): item = " + item + ", ret =" + ret);

        return ret;
    }

    /**
     * @deprecated see {@link #getConfigString(int)} instead
     */
    public String getProvisionedStringValue(int item) throws ImsException {
        return getConfigString(item);
    }

    /**
     * Gets the configuration value for IMS service/capabilities parameters used by IMS stack.
     *
     * @param item, as defined in com.android.ims.ImsConfig#ConfigConstants.
     * @return value in String format.
     *
     * @throws ImsException if the ImsService is unavailable.
     */
    public String getConfigString(int item) throws ImsException {
        String ret = "Unknown";
        try {
            ret = miConfig.getConfigString(item);
        }  catch (RemoteException e) {
            throw new ImsException("getConfigString()", e,
                    ImsReasonInfo.CODE_LOCAL_SERVICE_UNAVAILABLE);
        }
        if (DBG) Rlog.d(TAG, "getConfigString(): item = " + item + ", ret =" + ret);

        return ret;
    }

    /**
     * @deprecated see {@link #setConfig(int, int)} instead.
     */
    public int setProvisionedValue(int item, int value) throws ImsException {
        return setConfig(item, value);
    }

    /**
     * @deprecated see {@link #setConfig(int, String)} instead.
     */
    public int setProvisionedStringValue(int item, String value) throws ImsException {
        return setConfig(item, value);
    }

    /**
     * Sets the value for ImsService configuration item.
     *
     * @param item, as defined in com.android.ims.ImsConfig#ConfigConstants.
     * @param value in Integer format.
     * @return as defined in com.android.ims.ImsConfig#OperationStatusConstants
     *
     * @throws ImsException if the ImsService is unavailable.
     */
    public int setConfig(int item, int value) throws ImsException {
        int ret = OperationStatusConstants.UNKNOWN;
        if (DBG) {
            Rlog.d(TAG, "setConfig(): item = " + item +
                    "value = " + value);
        }
        try {
            ret = miConfig.setConfigInt(item, value);
        }  catch (RemoteException e) {
            throw new ImsException("setConfig()", e,
                    ImsReasonInfo.CODE_LOCAL_SERVICE_UNAVAILABLE);
        }
        if (DBG) {
            Rlog.d(TAG, "setConfig(): item = " + item +
                    " value = " + value + " ret = " + ret);
        }

        return ret;

    }

    /**
     * Sets the value for ImsService configuration item.
     *
     * @param item, as defined in com.android.ims.ImsConfig#ConfigConstants.
     * @param value in Integer format.
     * @return as defined in com.android.ims.ImsConfig#OperationStatusConstants
     *
     * @throws ImsException if the ImsService is unavailable.
     */
    public int setConfig(int item, String value) throws ImsException {
        int ret = OperationStatusConstants.UNKNOWN;
        if (DBG) {
            Rlog.d(TAG, "setConfig(): item = " + item +
                    "value = " + value);
        }
        try {
            ret = miConfig.setConfigString(item, value);
        }  catch (RemoteException e) {
            throw new ImsException("setConfig()", e,
                    ImsReasonInfo.CODE_LOCAL_SERVICE_UNAVAILABLE);
        }
        if (DBG) {
            Rlog.d(TAG, "setConfig(): item = " + item +
                    " value = " + value + " ret = " + ret);
        }

        return ret;
    }

    /**
     * Adds a {@link ProvisioningManager.Callback} to the ImsService to notify when a Configuration
     * item has changed.
     *
     * Make sure to call {@link #removeConfigCallback(IImsConfigCallback)} when finished
     * using this callback.
     */
    public void addConfigCallback(ProvisioningManager.Callback callback) throws ImsException {
        callback.setExecutor(getThreadExecutor());
        addConfigCallback(callback.getBinder());
    }

    /**
     * Adds a {@link IImsConfigCallback} to the ImsService to notify when a Configuration
     * item has changed.
     *
     * Make sure to call {@link #removeConfigCallback(IImsConfigCallback)} when finished
     * using this callback.
     */
    public void addConfigCallback(IImsConfigCallback callback) throws ImsException {
        if (DBG) Rlog.d(TAG, "addConfigCallback: " + callback);
        try {
            miConfig.addImsConfigCallback(callback);
        }  catch (RemoteException e) {
            throw new ImsException("addConfigCallback()", e,
                    ImsReasonInfo.CODE_LOCAL_SERVICE_UNAVAILABLE);
        }
    }

    /**
     * Removes an existing {@link IImsConfigCallback} from the ImsService.
     */
    public void removeConfigCallback(IImsConfigCallback callback) throws ImsException {
        if (DBG) Rlog.d(TAG, "removeConfigCallback: " + callback);
        try {
            miConfig.removeImsConfigCallback(callback);
        }  catch (RemoteException e) {
            throw new ImsException("removeConfigCallback()", e,
                    ImsReasonInfo.CODE_LOCAL_SERVICE_UNAVAILABLE);
        }
    }

    /**
     * @return true if the binder connection is alive, false otherwise.
     */
    public boolean isBinderAlive() {
        return miConfig.asBinder().isBinderAlive();
    }

    private Executor getThreadExecutor() {
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }
        return new HandlerExecutor(new Handler(Looper.myLooper()));
    }
}
