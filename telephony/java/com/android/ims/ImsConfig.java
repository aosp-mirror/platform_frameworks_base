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

import android.content.Context;
import android.os.RemoteException;
import android.telephony.Rlog;

import com.android.ims.internal.IImsConfig;

/**
 * Provides APIs to get/set the IMS service feature/capability/parameters.
 * The config items include:
 * 1) Items provisioned by the operator.
 * 2) Items configured by user. Mainly service feature class.
 *
 * @hide
 */
public class ImsConfig {
    private static final String TAG = "ImsConfig";
    private boolean DBG = true;
    private final IImsConfig miConfig;
    private Context mContext;

    /**
     * Broadcast action: the feature enable status was changed
     *
     * @hide
     */
    public static final String ACTION_IMS_FEATURE_CHANGED =
            "com.android.intent.action.IMS_FEATURE_CHANGED";

    /**
     * Broadcast action: the configuration was changed
     *
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
         */
        public static final int VOCODER_AMRMODESET = CONFIG_START;

        /**
         * Wide Band AMR CODEC Mode Value set,0-7 in comma separated sequence.
         * Value is in String format.
         */
        public static final int VOCODER_AMRWBMODESET = 1;

        /**
         * SIP Session Timer value (seconds).
         * Value is in Integer format.
         */
        public static final int SIP_SESSION_TIMER = 2;

        /**
         * Minimum SIP Session Expiration Timer in (seconds).
         * Value is in Integer format.
         */
        public static final int MIN_SE = 3;

        /**
         * SIP_INVITE cancellation time out value (in milliseconds). Integer format.
         * Value is in Integer format.
         */
        public static final int CANCELLATION_TIMER = 4;

        /**
         * Delay time when an iRAT transition from eHRPD/HRPD/1xRTT to LTE.
         * Value is in Integer format.
         */
        public static final int TDELAY = 5;

        /**
         * Silent redial status of Enabled (True), or Disabled (False).
         * Value is in Integer format.
         */
        public static final int SILENT_REDIAL_ENABLE = 6;

        /**
         * SIP T1 timer value in milliseconds. See RFC 3261 for define.
         * Value is in Integer format.
         */
        public static final int SIP_T1_TIMER = 7;

        /**
         * SIP T2 timer value in milliseconds.  See RFC 3261 for define.
         * Value is in Integer format.
         */
        public static final int SIP_T2_TIMER  = 8;

         /**
         * SIP TF timer value in milliseconds.  See RFC 3261 for define.
         * Value is in Integer format.
         */
        public static final int SIP_TF_TIMER = 9;

        /**
         * VoLTE status for VLT/s status of Enabled (1), or Disabled (0).
         * Value is in Integer format.
         */
        public static final int VLT_SETTING_ENABLED = 10;

        /**
         * VoLTE status for LVC/s status of Enabled (1), or Disabled (0).
         * Value is in Integer format.
         */
        public static final int LVC_SETTING_ENABLED = 11;
        /**
         * Domain Name for the device to populate the request URI for REGISTRATION.
         * Value is in String format.
         */
        public static final int DOMAIN_NAME = 12;
         /**
         * Device Outgoing SMS based on either 3GPP or 3GPP2 standards.
         * Value is in Integer format. 3GPP2(0), 3GPP(1)
         */
        public static final int SMS_FORMAT = 13;
         /**
         * Turns IMS ON/OFF on the device.
         * Value is in Integer format. ON (1), OFF(0).
         */
        public static final int SMS_OVER_IP = 14;
        /**
         * Requested expiration for Published Online availability.
         * Value is in Integer format.
         */
        public static final int PUBLISH_TIMER = 15;
        /**
         * Requested expiration for Published Offline availability.
         * Value is in Integer format.
         */
        public static final int PUBLISH_TIMER_EXTENDED = 16;
        /**
         *
         * Value is in Integer format.
         */
        public static final int CAPABILITY_DISCOVERY_ENABLED = 17;
        /**
         * Period of time the capability information of the  contact is cached on handset.
         * Value is in Integer format.
         */
        public static final int CAPABILITIES_CACHE_EXPIRATION = 18;
        /**
         * Peiod of time the availability information of a contact is cached on device.
         * Value is in Integer format.
         */
        public static final int AVAILABILITY_CACHE_EXPIRATION = 19;
        /**
         * Interval between successive capabilities polling.
         * Value is in Integer format.
         */
        public static final int CAPABILITIES_POLL_INTERVAL = 20;
        /**
         * Minimum time between two published messages from the device.
         * Value is in Integer format.
         */
        public static final int SOURCE_THROTTLE_PUBLISH = 21;
        /**
         * The Maximum number of MDNs contained in one Request Contained List.
         * Value is in Integer format.
         */
        public static final int MAX_NUMENTRIES_IN_RCL = 22;
        /**
         * Expiration timer for subscription of a Request Contained List, used in capability
         * polling.
         * Value is in Integer format.
         */
        public static final int CAPAB_POLL_LIST_SUB_EXP = 23;
        /**
         * Applies compression to LIST Subscription.
         * Value is in Integer format. Enable (1), Disable(0).
         */
        public static final int GZIP_FLAG = 24;
        /**
         * VOLTE Status for EAB/s status of Enabled (1), or Disabled (0).
         * Value is in Integer format.
         */
        public static final int EAB_SETTING_ENABLED = 25;
        /**
         * Wi-Fi calling roaming status.
         * Value is in Integer format. ON (1), OFF(0).
         */
        public static final int VOICE_OVER_WIFI_ROAMING = 26;
        /**
         * Wi-Fi calling modem - WfcModeFeatureValueConstants.
         * Value is in Integer format.
         */
        public static final int VOICE_OVER_WIFI_MODE = 27;
        /**
         * VOLTE Status for voice over wifi status of Enabled (1), or Disabled (0).
         * Value is in Integer format.
         */
        public static final int VOICE_OVER_WIFI_SETTING_ENABLED = 28;
        /**
         * Mobile data enabled.
         * Value is in Integer format. On (1), OFF(0).
         */
        public static final int MOBILE_DATA_ENABLED = 29;
        /**
         * VoLTE user opted in status.
         * Value is in Integer format. Opted-in (1) Opted-out (0).
         */
        public static final int VOLTE_USER_OPT_IN_STATUS = 30;
        /**
         * Proxy for Call Session Control Function(P-CSCF) address for Local-BreakOut(LBO).
         * Value is in String format.
         */
        public static final int LBO_PCSCF_ADDRESS = 31;
        /**
         * Keep Alive Enabled for SIP.
         * Value is in Integer format. On(1), OFF(0).
         */
        public static final int KEEP_ALIVE_ENABLED = 32;
        /**
         * Registration retry Base Time value in seconds.
         * Value is in Integer format.
         */
        public static final int REGISTRATION_RETRY_BASE_TIME_SEC = 33;
        /**
         * Registration retry Max Time value in seconds.
         * Value is in Integer format.
         */
        public static final int REGISTRATION_RETRY_MAX_TIME_SEC = 34;
        /**
         * Smallest RTP port for speech codec.
         * Value is in integer format.
         */
        public static final int SPEECH_START_PORT = 35;
        /**
         * Largest RTP port for speech code.
         * Value is in Integer format.
         */
        public static final int SPEECH_END_PORT = 36;
        /**
         * SIP Timer A's value in milliseconds. Timer A is the INVITE request
         * retransmit interval, for UDP only.
         * Value is in Integer format.
         */
        public static final int SIP_INVITE_REQ_RETX_INTERVAL_MSEC = 37;
        /**
         * SIP Timer B's value in milliseconds. Timer B is the wait time for
         * INVITE message to be acknowledged.
         * Value is in Integer format.
         */
        public static final int SIP_INVITE_RSP_WAIT_TIME_MSEC = 38;
        /**
         * SIP Timer D's value in milliseconds. Timer D is the wait time for
         * response retransmits of the invite client transactions.
         * Value is in Integer format.
         */
        public static final int SIP_INVITE_RSP_RETX_WAIT_TIME_MSEC = 39;
        /**
         * SIP Timer E's value in milliseconds. Timer E is the value Non-INVITE
         * request retransmit interval, for UDP only.
         * Value is in Integer format.
         */
        public static final int SIP_NON_INVITE_REQ_RETX_INTERVAL_MSEC = 40;
        /**
         * SIP Timer F's value in milliseconds. Timer F is the Non-INVITE transaction
         * timeout timer.
         * Value is in Integer format.
         */
        public static final int SIP_NON_INVITE_TXN_TIMEOUT_TIMER_MSEC = 41;
        /**
         * SIP Timer G's value in milliseconds. Timer G is the value of INVITE response
         * retransmit interval.
         * Value is in Integer format.
         */
        public static final int SIP_INVITE_RSP_RETX_INTERVAL_MSEC = 42;
        /**
         * SIP Timer H's value in milliseconds. Timer H is the value of wait time for
         * ACK receipt.
         * Value is in Integer format.
         */
        public static final int SIP_ACK_RECEIPT_WAIT_TIME_MSEC = 43;
        /**
         * SIP Timer I's value in milliseconds. Timer I is the value of wait time for
         * ACK retransmits.
         * Value is in Integer format.
         */
        public static final int SIP_ACK_RETX_WAIT_TIME_MSEC = 44;
        /**
         * SIP Timer J's value in milliseconds. Timer J is the value of wait time for
         * non-invite request retransmission.
         * Value is in Integer format.
         */
        public static final int SIP_NON_INVITE_REQ_RETX_WAIT_TIME_MSEC = 45;
        /**
         * SIP Timer K's value in milliseconds. Timer K is the value of wait time for
         * non-invite response retransmits.
         * Value is in Integer format.
         */
        public static final int SIP_NON_INVITE_RSP_RETX_WAIT_TIME_MSEC = 46;
        /**
         * AMR WB octet aligned dynamic payload type.
         * Value is in Integer format.
         */
        public static final int AMR_WB_OCTET_ALIGNED_PT = 47;
        /**
         * AMR WB bandwidth efficient payload type.
         * Value is in Integer format.
         */
        public static final int AMR_WB_BANDWIDTH_EFFICIENT_PT = 48;
        /**
         * AMR octet aligned dynamic payload type.
         * Value is in Integer format.
         */
        public static final int AMR_OCTET_ALIGNED_PT = 49;
        /**
         * AMR bandwidth efficient payload type.
         * Value is in Integer format.
         */
        public static final int AMR_BANDWIDTH_EFFICIENT_PT = 50;
        /**
         * DTMF WB payload type.
         * Value is in Integer format.
         */
        public static final int DTMF_WB_PT = 51;
        /**
         * DTMF NB payload type.
         * Value is in Integer format.
         */
        public static final int DTMF_NB_PT = 52;
        /**
         * AMR Default encoding mode.
         * Value is in Integer format.
         */
        public static final int AMR_DEFAULT_MODE = 53;
        /**
         * SMS Public Service Identity.
         * Value is in String format.
         */
        public static final int SMS_PSI = 54;
        /**
         * Video Quality - VideoQualityFeatureValuesConstants.
         * Value is in Integer format.
         */
        public static final int VIDEO_QUALITY = 55;
        /**
         * LTE threshold.
         * Handover from LTE to WiFi if LTE < THLTE1 and WiFi >= VOWT_A.
         */
        public static final int TH_LTE1 = 56;
        /**
         * LTE threshold.
         * Handover from WiFi to LTE if LTE >= THLTE3 or (WiFi < VOWT_B and LTE >= THLTE2).
         */
        public static final int TH_LTE2 = 57;
        /**
         * LTE threshold.
         * Handover from WiFi to LTE if LTE >= THLTE3 or (WiFi < VOWT_B and LTE >= THLTE2).
         */
        public static final int TH_LTE3 = 58;
        /**
         * 1x threshold.
         * Handover from 1x to WiFi if 1x < TH1x
         */
        public static final int TH_1x = 59;
        /**
         * WiFi threshold.
         * Handover from LTE to WiFi if LTE < THLTE1 and WiFi >= VOWT_A.
         */
        public static final int VOWT_A = 60;
        /**
         * WiFi threshold.
         * Handover from WiFi to LTE if LTE >= THLTE3 or (WiFi < VOWT_B and LTE >= THLTE2).
         */
        public static final int VOWT_B = 61;
        /**
         * LTE ePDG timer.
         * Device shall not handover back to LTE until the T_ePDG_LTE timer expires.
         */
        public static final int T_EPDG_LTE = 62;
        /**
         * WiFi ePDG timer.
         * Device shall not handover back to WiFi until the T_ePDG_WiFi timer expires.
         */
        public static final int T_EPDG_WIFI = 63;
        /**
         * 1x ePDG timer.
         * Device shall not re-register on 1x until the T_ePDG_1x timer expires.
         */
        public static final int T_EPDG_1X = 64;
        /**
         * MultiEndpoint status: Enabled (1), or Disabled (0).
         * Value is in Integer format.
         */
        public static final int VICE_SETTING_ENABLED = 65;

        /**
         * RTT status: Enabled (1), or Disabled (0).
         * Value is in Integer format.
         */
        public static final int RTT_SETTING_ENABLED = 66;

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

    public ImsConfig(IImsConfig iconfig, Context context) {
        if (DBG) Rlog.d(TAG, "ImsConfig creates");
        miConfig = iconfig;
        mContext = context;
    }

    /**
     * Gets the provisioned value for IMS service/capabilities parameters used by IMS stack.
     * This function should not be called from the mainthread as it could block the
     * mainthread.
     *
     * @param item, as defined in com.android.ims.ImsConfig#ConfigConstants.
     * @return the value in Integer format.
     *
     * @throws ImsException if calling the IMS service results in an error.
     */
    public int getProvisionedValue(int item) throws ImsException {
        int ret = 0;
        try {
            ret = miConfig.getProvisionedValue(item);
        }  catch (RemoteException e) {
            throw new ImsException("getValue()", e,
                    ImsReasonInfo.CODE_LOCAL_SERVICE_UNAVAILABLE);
        }
        if (DBG) Rlog.d(TAG, "getProvisionedValue(): item = " + item + ", ret =" + ret);

        return ret;
    }

    /**
     * Gets the provisioned value for IMS service/capabilities parameters used by IMS stack.
     * This function should not be called from the mainthread as it could block the
     * mainthread.
     *
     * @param item, as defined in com.android.ims.ImsConfig#ConfigConstants.
     * @return value in String format.
     *
     * @throws ImsException if calling the IMS service results in an error.
     */
    public String getProvisionedStringValue(int item) throws ImsException {
        String ret = "Unknown";
        try {
            ret = miConfig.getProvisionedStringValue(item);
        }  catch (RemoteException e) {
            throw new ImsException("getProvisionedStringValue()", e,
                    ImsReasonInfo.CODE_LOCAL_SERVICE_UNAVAILABLE);
        }
        if (DBG) Rlog.d(TAG, "getProvisionedStringValue(): item = " + item + ", ret =" + ret);

        return ret;
    }

    /**
     * Sets the value for IMS service/capabilities parameters by
     * the operator device management entity.
     * This function should not be called from main thread as it could block
     * mainthread.
     *
     * @param item, as defined in com.android.ims.ImsConfig#ConfigConstants.
     * @param value in Integer format.
     * @return as defined in com.android.ims.ImsConfig#OperationStatusConstants
     *
     * @throws ImsException if calling the IMS service results in an error.
     */
    public int setProvisionedValue(int item, int value)
            throws ImsException {
        int ret = OperationStatusConstants.UNKNOWN;
        if (DBG) {
            Rlog.d(TAG, "setProvisionedValue(): item = " + item +
                    "value = " + value);
        }
        try {
            ret = miConfig.setProvisionedValue(item, value);
        }  catch (RemoteException e) {
            throw new ImsException("setProvisionedValue()", e,
                    ImsReasonInfo.CODE_LOCAL_SERVICE_UNAVAILABLE);
        }
        if (DBG) {
            Rlog.d(TAG, "setProvisionedValue(): item = " + item +
                    " value = " + value + " ret = " + ret);
        }
        return ret;
    }

    /**
     * Sets the value for IMS service/capabilities parameters by
     * the operator device management entity.
     * This function should not be called from main thread as it could block
     * mainthread.
     *
     * @param item, as defined in com.android.ims.ImsConfig#ConfigConstants.
     * @param value in String format.
     * @return as defined in com.android.ims.ImsConfig#OperationStatusConstants
     *
     * @throws ImsException if calling the IMS service results in an error.
     */
    public int setProvisionedStringValue(int item, String value)
            throws ImsException {
        int ret = OperationStatusConstants.UNKNOWN;
        try {
            ret = miConfig.setProvisionedStringValue(item, value);
        }  catch (RemoteException e) {
            throw new ImsException("setProvisionedStringValue()", e,
                    ImsReasonInfo.CODE_LOCAL_SERVICE_UNAVAILABLE);
        }
        if (DBG) {
            Rlog.d(TAG, "setProvisionedStringValue(): item = " + item +
                    ", value =" + value);
        }
        return ret;
    }

    /**
     * Gets the value for IMS feature item for specified network type.
     *
     * @param feature, defined as in FeatureConstants.
     * @param network, defined as in android.telephony.TelephonyManager#NETWORK_TYPE_XXX.
     * @param listener, provided to be notified for the feature on/off status.
     * @return void
     *
     * @throws ImsException if calling the IMS service results in an error.
     */
    public void getFeatureValue(int feature, int network,
            ImsConfigListener listener) throws ImsException {
        if (DBG) {
            Rlog.d(TAG, "getFeatureValue: feature = " + feature + ", network =" + network +
                    ", listener =" + listener);
        }
        try {
            miConfig.getFeatureValue(feature, network, listener);
        } catch (RemoteException e) {
            throw new ImsException("getFeatureValue()", e,
                    ImsReasonInfo.CODE_LOCAL_SERVICE_UNAVAILABLE);
        }
    }

    /**
     * Sets the value for IMS feature item for specified network type.
     *
     * @param feature, as defined in FeatureConstants.
     * @param network, as defined in android.telephony.TelephonyManager#NETWORK_TYPE_XXX.
     * @param value, as defined in FeatureValueConstants.
     * @param listener, provided if caller needs to be notified for set result.
     * @return void
     *
     * @throws ImsException if calling the IMS service results in an error.
     */
    public void setFeatureValue(int feature, int network, int value,
            ImsConfigListener listener) throws ImsException {
        if (DBG) {
            Rlog.d(TAG, "setFeatureValue: feature = " + feature + ", network =" + network +
                    ", value =" + value + ", listener =" + listener);
        }
        try {
            miConfig.setFeatureValue(feature, network, value, listener);
        } catch (RemoteException e) {
            throw new ImsException("setFeatureValue()", e,
                    ImsReasonInfo.CODE_LOCAL_SERVICE_UNAVAILABLE);
        }
    }
}
