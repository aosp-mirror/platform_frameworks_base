/*
 * Copyright (C) 2006 The Android Open Source Project
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
import android.annotation.SystemApi;
import android.content.Context;
import android.os.PersistableBundle;

import com.android.internal.util.ArrayUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Returned as the reason for a data connection failure as defined by modem and some local errors.
 * @hide
 */
@SystemApi
public final class DataFailCause {
    /** There is no failure */
    public static final int NONE = 0;

    // This series of errors as specified by the standards
    // specified in ril.h
    /** Operator determined barring. */
    public static final int OPERATOR_BARRED = 0x08;
    /** NAS signalling. */
    public static final int NAS_SIGNALLING = 0x0E;
    /** Logical Link Control (LLC) Sub Network Dependent Convergence Protocol (SNDCP). */
    public static final int LLC_SNDCP = 0x19;
    /** Insufficient resources. */
    public static final int INSUFFICIENT_RESOURCES = 0x1A;
    /** Missing or unknown APN. */
    public static final int MISSING_UNKNOWN_APN = 0x1B;              /* no retry */
    /** Unknown Packet Data Protocol (PDP) address type. */
    public static final int UNKNOWN_PDP_ADDRESS_TYPE = 0x1C;         /* no retry */
    /** User authentication. */
    public static final int USER_AUTHENTICATION = 0x1D;              /* no retry */
    /** Activation rejected by Gateway GPRS Support Node (GGSN), Serving Gateway or PDN Gateway. */
    public static final int ACTIVATION_REJECT_GGSN = 0x1E;           /* no retry */
    /** Activation rejected, unspecified. */
    public static final int ACTIVATION_REJECT_UNSPECIFIED = 0x1F;
    /** Service option not supported. */
    public static final int SERVICE_OPTION_NOT_SUPPORTED = 0x20;     /* no retry */
    /** Requested service option not subscribed. */
    public static final int SERVICE_OPTION_NOT_SUBSCRIBED = 0x21;    /* no retry */
    /** Service option temporarily out of order. */
    public static final int SERVICE_OPTION_OUT_OF_ORDER = 0x22;
    /** The Network Service Access Point Identifier (NSAPI) is in use. */
    public static final int NSAPI_IN_USE = 0x23;                     /* no retry */
    /* possibly restart radio, based on config */
    /** Regular deactivation. */
    public static final int REGULAR_DEACTIVATION = 0x24;
    /** Quality of service (QoS) is not accepted. */
    public static final int QOS_NOT_ACCEPTED = 0x25;
    /** Network Failure. */
    public static final int NETWORK_FAILURE = 0x26;
    /** Universal Mobile Telecommunications System (UMTS) reactivation request. */
    public static final int UMTS_REACTIVATION_REQ = 0x27;
    /** Feature not supported. */
    public static final int FEATURE_NOT_SUPP = 0x28;
    /** Semantic error in the Traffic flow templates (TFT) operation. */
    public static final int TFT_SEMANTIC_ERROR = 0x29;
    /** Syntactical error in the Traffic flow templates (TFT) operation. */
    public static final int TFT_SYTAX_ERROR = 0x2A;
    /** Unknown Packet Data Protocol (PDP) context. */
    public static final int UNKNOWN_PDP_CONTEXT = 0x2B;
    /** Semantic errors in packet filter. */
    public static final int FILTER_SEMANTIC_ERROR = 0x2C;
    /** Syntactical errors in packet filter(s). */
    public static final int FILTER_SYTAX_ERROR = 0x2D;
    /** Packet Data Protocol (PDP) without active traffic flow template (TFT). */
    public static final int PDP_WITHOUT_ACTIVE_TFT = 0x2E;
    /** Packet Data Protocol (PDP) type IPv4 only allowed. */
    public static final int ONLY_IPV4_ALLOWED = 0x32;                /* no retry */
    /** Packet Data Protocol (PDP) type IPv6 only allowed. */
    public static final int ONLY_IPV6_ALLOWED = 0x33;                /* no retry */
    /** Single address bearers only allowed. */
    public static final int ONLY_SINGLE_BEARER_ALLOWED = 0x34;
    /** EPS Session Management (ESM) information is not received. */
    public static final int ESM_INFO_NOT_RECEIVED = 0x35;
    /** PDN connection does not exist. */
    public static final int PDN_CONN_DOES_NOT_EXIST = 0x36;
    /** Multiple connections to a same PDN is not allowed. */
    public static final int MULTI_CONN_TO_SAME_PDN_NOT_ALLOWED = 0x37;
    /** Max number of Packet Data Protocol (PDP) context reached. */
    public static final int ACTIVE_PDP_CONTEXT_MAX_NUMBER_REACHED = 0x41;
    /** Unsupported APN in current public land mobile network (PLMN). */
    public static final int UNSUPPORTED_APN_IN_CURRENT_PLMN = 0x42;
    /** Invalid transaction id. */
    public static final int INVALID_TRANSACTION_ID = 0x51;
    /** Incorrect message semantic. */
    public static final int MESSAGE_INCORRECT_SEMANTIC = 0x5F;
    /** Invalid mandatory information. */
    public static final int INVALID_MANDATORY_INFO = 0x60;
    /** Unsupported message type. */
    public static final int MESSAGE_TYPE_UNSUPPORTED = 0x61;
    /** Message type uncompatible. */
    public static final int MSG_TYPE_NONCOMPATIBLE_STATE = 0x62;
    /** Unknown info element. */
    public static final int UNKNOWN_INFO_ELEMENT = 0x63;
    /** Conditional Information Element (IE) error. */
    public static final int CONDITIONAL_IE_ERROR = 0x64;
    /** Message and protocol state uncompatible. */
    public static final int MSG_AND_PROTOCOL_STATE_UNCOMPATIBLE = 0x65;
    /** Protocol errors. */
    public static final int PROTOCOL_ERRORS = 0x6F;                  /* no retry */
    /** APN type conflict. */
    public static final int APN_TYPE_CONFLICT = 0x70;
    /** Invalid Proxy-Call Session Control Function (P-CSCF) address. */
    public static final int INVALID_PCSCF_ADDR = 0x71;
    /** Internal data call preempt by high priority APN. */
    public static final int INTERNAL_CALL_PREEMPT_BY_HIGH_PRIO_APN = 0x72;
    /** EPS (Evolved Packet System) Mobility Management (EMM) access barred. */
    public static final int EMM_ACCESS_BARRED = 0x73;
    /** Emergency interface only. */
    public static final int EMERGENCY_IFACE_ONLY = 0x74;
    /** Interface mismatch. */
    public static final int IFACE_MISMATCH = 0x75;
    /** Companion interface in use. */
    public static final int COMPANION_IFACE_IN_USE = 0x76;
    /** IP address mismatch. */
    public static final int IP_ADDRESS_MISMATCH = 0x77;
    public static final int IFACE_AND_POL_FAMILY_MISMATCH = 0x78;
    /** EPS (Evolved Packet System) Mobility Management (EMM) access barred infinity retry. **/
    public static final int EMM_ACCESS_BARRED_INFINITE_RETRY = 0x79;
    /** Authentication failure on emergency call. */
    public static final int AUTH_FAILURE_ON_EMERGENCY_CALL = 0x7A;

    // OEM sepecific error codes. To be used by OEMs when they don't
    // want to reveal error code which would be replaced by ERROR_UNSPECIFIED
    public static final int OEM_DCFAILCAUSE_1 = 0x1001;
    public static final int OEM_DCFAILCAUSE_2 = 0x1002;
    public static final int OEM_DCFAILCAUSE_3 = 0x1003;
    public static final int OEM_DCFAILCAUSE_4 = 0x1004;
    public static final int OEM_DCFAILCAUSE_5 = 0x1005;
    public static final int OEM_DCFAILCAUSE_6 = 0x1006;
    public static final int OEM_DCFAILCAUSE_7 = 0x1007;
    public static final int OEM_DCFAILCAUSE_8 = 0x1008;
    public static final int OEM_DCFAILCAUSE_9 = 0x1009;
    public static final int OEM_DCFAILCAUSE_10 = 0x100A;
    public static final int OEM_DCFAILCAUSE_11 = 0x100B;
    public static final int OEM_DCFAILCAUSE_12 = 0x100C;
    public static final int OEM_DCFAILCAUSE_13 = 0x100D;
    public static final int OEM_DCFAILCAUSE_14 = 0x100E;
    public static final int OEM_DCFAILCAUSE_15 = 0x100F;

    // Local errors generated by Vendor RIL
    // specified in ril.h
    /** Data fail due to registration failure. */
    public static final int REGISTRATION_FAIL = -1;
    /** Data fail due to GPRS registration failure. */
    public static final int GPRS_REGISTRATION_FAIL = -2;
    /** Data call drop due to network/modem disconnect. */
    public static final int SIGNAL_LOST = -3;                        /* no retry */
    /**
     * Preferred technology has changed, must retry with parameters appropriate for new technology.
     */
    public static final int PREF_RADIO_TECH_CHANGED = -4;
    /** data call was disconnected because radio was resetting, powered off. */
    public static final int RADIO_POWER_OFF = -5;                    /* no retry */
    /** Data call was disconnected by modem because tethered. */
    public static final int TETHERED_CALL_ACTIVE = -6;               /* no retry */
    /** Data call fail due to unspecific errors. */
    public static final int ERROR_UNSPECIFIED = 0xFFFF;

    // Errors generated by the Framework
    // specified here
    /** Unknown data failure cause. */
    public static final int UNKNOWN = 0x10000;
    /** Data fail due to radio not unavailable. */
    public static final int RADIO_NOT_AVAILABLE = 0x10001;                   /* no retry */
    /** @hide */
    public static final int UNACCEPTABLE_NETWORK_PARAMETER = 0x10002;        /* no retry */
    /** @hide */
    public static final int CONNECTION_TO_DATACONNECTIONAC_BROKEN = 0x10003;
    /** Data connection was lost. */
    public static final int LOST_CONNECTION = 0x10004;
    /** @hide */
    public static final int RESET_BY_FRAMEWORK = 0x10005;

    /** @hide */
    @IntDef(value = {
            NONE,
            OPERATOR_BARRED,
            NAS_SIGNALLING,
            LLC_SNDCP,
            INSUFFICIENT_RESOURCES,
            MISSING_UNKNOWN_APN,
            UNKNOWN_PDP_ADDRESS_TYPE,
            USER_AUTHENTICATION,
            ACTIVATION_REJECT_GGSN,
            ACTIVATION_REJECT_UNSPECIFIED,
            SERVICE_OPTION_NOT_SUPPORTED,
            SERVICE_OPTION_NOT_SUBSCRIBED,
            SERVICE_OPTION_OUT_OF_ORDER,
            NSAPI_IN_USE,
            REGULAR_DEACTIVATION,
            QOS_NOT_ACCEPTED,
            NETWORK_FAILURE,
            UMTS_REACTIVATION_REQ,
            FEATURE_NOT_SUPP,
            TFT_SEMANTIC_ERROR,
            TFT_SYTAX_ERROR,
            UNKNOWN_PDP_CONTEXT,
            FILTER_SEMANTIC_ERROR,
            FILTER_SYTAX_ERROR,
            PDP_WITHOUT_ACTIVE_TFT,
            ONLY_IPV4_ALLOWED,
            ONLY_IPV6_ALLOWED,
            ONLY_SINGLE_BEARER_ALLOWED,
            ESM_INFO_NOT_RECEIVED,
            PDN_CONN_DOES_NOT_EXIST,
            MULTI_CONN_TO_SAME_PDN_NOT_ALLOWED,
            ACTIVE_PDP_CONTEXT_MAX_NUMBER_REACHED,
            UNSUPPORTED_APN_IN_CURRENT_PLMN,
            INVALID_TRANSACTION_ID,
            MESSAGE_INCORRECT_SEMANTIC,
            INVALID_MANDATORY_INFO,
            MESSAGE_TYPE_UNSUPPORTED,
            MSG_TYPE_NONCOMPATIBLE_STATE,
            UNKNOWN_INFO_ELEMENT,
            CONDITIONAL_IE_ERROR,
            MSG_AND_PROTOCOL_STATE_UNCOMPATIBLE,
            PROTOCOL_ERRORS,                 /* no retry */
            APN_TYPE_CONFLICT,
            INVALID_PCSCF_ADDR,
            INTERNAL_CALL_PREEMPT_BY_HIGH_PRIO_APN,
            EMM_ACCESS_BARRED,
            EMERGENCY_IFACE_ONLY,
            IFACE_MISMATCH,
            COMPANION_IFACE_IN_USE,
            IP_ADDRESS_MISMATCH,
            IFACE_AND_POL_FAMILY_MISMATCH,
            EMM_ACCESS_BARRED_INFINITE_RETRY,
            AUTH_FAILURE_ON_EMERGENCY_CALL,
            OEM_DCFAILCAUSE_1,
            OEM_DCFAILCAUSE_2,
            OEM_DCFAILCAUSE_3,
            OEM_DCFAILCAUSE_4,
            OEM_DCFAILCAUSE_5,
            OEM_DCFAILCAUSE_6,
            OEM_DCFAILCAUSE_7,
            OEM_DCFAILCAUSE_8,
            OEM_DCFAILCAUSE_9,
            OEM_DCFAILCAUSE_10,
            OEM_DCFAILCAUSE_11,
            OEM_DCFAILCAUSE_12,
            OEM_DCFAILCAUSE_13,
            OEM_DCFAILCAUSE_14,
            OEM_DCFAILCAUSE_15,
            REGISTRATION_FAIL,
            GPRS_REGISTRATION_FAIL,
            SIGNAL_LOST,
            PREF_RADIO_TECH_CHANGED,
            RADIO_POWER_OFF,
            TETHERED_CALL_ACTIVE,
            ERROR_UNSPECIFIED,
            UNKNOWN,
            RADIO_NOT_AVAILABLE,
            UNACCEPTABLE_NETWORK_PARAMETER,
            CONNECTION_TO_DATACONNECTIONAC_BROKEN,
            LOST_CONNECTION,
            RESET_BY_FRAMEWORK
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface FailCause{}

    private static final Map<Integer, String> sFailCauseMap;
    static {
        sFailCauseMap = new HashMap<>();
        sFailCauseMap.put(NONE, "NONE");
        sFailCauseMap.put(OPERATOR_BARRED, "OPERATOR_BARRED");
        sFailCauseMap.put(NAS_SIGNALLING, "NAS_SIGNALLING");
        sFailCauseMap.put(LLC_SNDCP, "LLC_SNDCP");
        sFailCauseMap.put(INSUFFICIENT_RESOURCES, "INSUFFICIENT_RESOURCES");
        sFailCauseMap.put(MISSING_UNKNOWN_APN, "MISSING_UNKNOWN_APN");
        sFailCauseMap.put(UNKNOWN_PDP_ADDRESS_TYPE, "UNKNOWN_PDP_ADDRESS_TYPE");
        sFailCauseMap.put(USER_AUTHENTICATION, "USER_AUTHENTICATION");
        sFailCauseMap.put(ACTIVATION_REJECT_GGSN, "ACTIVATION_REJECT_GGSN");
        sFailCauseMap.put(ACTIVATION_REJECT_UNSPECIFIED,
                "ACTIVATION_REJECT_UNSPECIFIED");
        sFailCauseMap.put(SERVICE_OPTION_NOT_SUPPORTED,
                "SERVICE_OPTION_NOT_SUPPORTED");
        sFailCauseMap.put(SERVICE_OPTION_NOT_SUBSCRIBED,
                "SERVICE_OPTION_NOT_SUBSCRIBED");
        sFailCauseMap.put(SERVICE_OPTION_OUT_OF_ORDER, "SERVICE_OPTION_OUT_OF_ORDER");
        sFailCauseMap.put(NSAPI_IN_USE, "NSAPI_IN_USE");
        sFailCauseMap.put(REGULAR_DEACTIVATION, "REGULAR_DEACTIVATION");
        sFailCauseMap.put(QOS_NOT_ACCEPTED, "QOS_NOT_ACCEPTED");
        sFailCauseMap.put(NETWORK_FAILURE, "NETWORK_FAILURE");
        sFailCauseMap.put(UMTS_REACTIVATION_REQ, "UMTS_REACTIVATION_REQ");
        sFailCauseMap.put(FEATURE_NOT_SUPP, "FEATURE_NOT_SUPP");
        sFailCauseMap.put(TFT_SEMANTIC_ERROR, "TFT_SEMANTIC_ERROR");
        sFailCauseMap.put(TFT_SYTAX_ERROR, "TFT_SYTAX_ERROR");
        sFailCauseMap.put(UNKNOWN_PDP_CONTEXT, "UNKNOWN_PDP_CONTEXT");
        sFailCauseMap.put(FILTER_SEMANTIC_ERROR, "FILTER_SEMANTIC_ERROR");
        sFailCauseMap.put(FILTER_SYTAX_ERROR, "FILTER_SYTAX_ERROR");
        sFailCauseMap.put(PDP_WITHOUT_ACTIVE_TFT, "PDP_WITHOUT_ACTIVE_TFT");
        sFailCauseMap.put(ONLY_IPV4_ALLOWED, "ONLY_IPV4_ALLOWED");
        sFailCauseMap.put(ONLY_IPV6_ALLOWED, "ONLY_IPV6_ALLOWED");
        sFailCauseMap.put(ONLY_SINGLE_BEARER_ALLOWED, "ONLY_SINGLE_BEARER_ALLOWED");
        sFailCauseMap.put(ESM_INFO_NOT_RECEIVED, "ESM_INFO_NOT_RECEIVED");
        sFailCauseMap.put(PDN_CONN_DOES_NOT_EXIST, "PDN_CONN_DOES_NOT_EXIST");
        sFailCauseMap.put(MULTI_CONN_TO_SAME_PDN_NOT_ALLOWED,
                "MULTI_CONN_TO_SAME_PDN_NOT_ALLOWED");
        sFailCauseMap.put(ACTIVE_PDP_CONTEXT_MAX_NUMBER_REACHED,
                "ACTIVE_PDP_CONTEXT_MAX_NUMBER_REACHED");
        sFailCauseMap.put(UNSUPPORTED_APN_IN_CURRENT_PLMN,
                "UNSUPPORTED_APN_IN_CURRENT_PLMN");
        sFailCauseMap.put(INVALID_TRANSACTION_ID, "INVALID_TRANSACTION_ID");
        sFailCauseMap.put(MESSAGE_INCORRECT_SEMANTIC, "MESSAGE_INCORRECT_SEMANTIC");
        sFailCauseMap.put(INVALID_MANDATORY_INFO, "INVALID_MANDATORY_INFO");
        sFailCauseMap.put(MESSAGE_TYPE_UNSUPPORTED, "MESSAGE_TYPE_UNSUPPORTED");
        sFailCauseMap.put(MSG_TYPE_NONCOMPATIBLE_STATE, "MSG_TYPE_NONCOMPATIBLE_STATE");
        sFailCauseMap.put(UNKNOWN_INFO_ELEMENT, "UNKNOWN_INFO_ELEMENT");
        sFailCauseMap.put(CONDITIONAL_IE_ERROR, "CONDITIONAL_IE_ERROR");
        sFailCauseMap.put(MSG_AND_PROTOCOL_STATE_UNCOMPATIBLE,
                "MSG_AND_PROTOCOL_STATE_UNCOMPATIBLE");
        sFailCauseMap.put(PROTOCOL_ERRORS, "PROTOCOL_ERRORS");
        sFailCauseMap.put(APN_TYPE_CONFLICT, "APN_TYPE_CONFLICT");
        sFailCauseMap.put(INVALID_PCSCF_ADDR, "INVALID_PCSCF_ADDR");
        sFailCauseMap.put(INTERNAL_CALL_PREEMPT_BY_HIGH_PRIO_APN,
                "INTERNAL_CALL_PREEMPT_BY_HIGH_PRIO_APN");
        sFailCauseMap.put(EMM_ACCESS_BARRED, "EMM_ACCESS_BARRED");
        sFailCauseMap.put(EMERGENCY_IFACE_ONLY, "EMERGENCY_IFACE_ONLY");
        sFailCauseMap.put(IFACE_MISMATCH, "IFACE_MISMATCH");
        sFailCauseMap.put(COMPANION_IFACE_IN_USE, "COMPANION_IFACE_IN_USE");
        sFailCauseMap.put(IP_ADDRESS_MISMATCH, "IP_ADDRESS_MISMATCH");
        sFailCauseMap.put(IFACE_AND_POL_FAMILY_MISMATCH,
                "IFACE_AND_POL_FAMILY_MISMATCH");
        sFailCauseMap.put(EMM_ACCESS_BARRED_INFINITE_RETRY,
                "EMM_ACCESS_BARRED_INFINITE_RETRY");
        sFailCauseMap.put(AUTH_FAILURE_ON_EMERGENCY_CALL,
                "AUTH_FAILURE_ON_EMERGENCY_CALL");
        sFailCauseMap.put(OEM_DCFAILCAUSE_1, "OEM_DCFAILCAUSE_1");
        sFailCauseMap.put(OEM_DCFAILCAUSE_2, "OEM_DCFAILCAUSE_2");
        sFailCauseMap.put(OEM_DCFAILCAUSE_3, "OEM_DCFAILCAUSE_3");
        sFailCauseMap.put(OEM_DCFAILCAUSE_4, "OEM_DCFAILCAUSE_4");
        sFailCauseMap.put(OEM_DCFAILCAUSE_5, "OEM_DCFAILCAUSE_5");
        sFailCauseMap.put(OEM_DCFAILCAUSE_6, "OEM_DCFAILCAUSE_6");
        sFailCauseMap.put(OEM_DCFAILCAUSE_7, "OEM_DCFAILCAUSE_7");
        sFailCauseMap.put(OEM_DCFAILCAUSE_8, "OEM_DCFAILCAUSE_8");
        sFailCauseMap.put(OEM_DCFAILCAUSE_9, "OEM_DCFAILCAUSE_9");
        sFailCauseMap.put(OEM_DCFAILCAUSE_10, "OEM_DCFAILCAUSE_10");
        sFailCauseMap.put(OEM_DCFAILCAUSE_11, "OEM_DCFAILCAUSE_11");
        sFailCauseMap.put(OEM_DCFAILCAUSE_12, "OEM_DCFAILCAUSE_12");
        sFailCauseMap.put(OEM_DCFAILCAUSE_13, "OEM_DCFAILCAUSE_13");
        sFailCauseMap.put(OEM_DCFAILCAUSE_14, "OEM_DCFAILCAUSE_14");
        sFailCauseMap.put(OEM_DCFAILCAUSE_15, "OEM_DCFAILCAUSE_15");
        sFailCauseMap.put(REGISTRATION_FAIL, "REGISTRATION_FAIL");
        sFailCauseMap.put(GPRS_REGISTRATION_FAIL, "GPRS_REGISTRATION_FAIL");
        sFailCauseMap.put(SIGNAL_LOST, "SIGNAL_LOST");
        sFailCauseMap.put(PREF_RADIO_TECH_CHANGED, "PREF_RADIO_TECH_CHANGED");
        sFailCauseMap.put(RADIO_POWER_OFF, "RADIO_POWER_OFF");
        sFailCauseMap.put(TETHERED_CALL_ACTIVE, "TETHERED_CALL_ACTIVE");
        sFailCauseMap.put(ERROR_UNSPECIFIED, "ERROR_UNSPECIFIED");
        sFailCauseMap.put(UNKNOWN, "UNKNOWN");
        sFailCauseMap.put(RADIO_NOT_AVAILABLE, "RADIO_NOT_AVAILABLE");
        sFailCauseMap.put(UNACCEPTABLE_NETWORK_PARAMETER,
                "UNACCEPTABLE_NETWORK_PARAMETER");
        sFailCauseMap.put(CONNECTION_TO_DATACONNECTIONAC_BROKEN,
                "CONNECTION_TO_DATACONNECTIONAC_BROKEN");
        sFailCauseMap.put(LOST_CONNECTION, "LOST_CONNECTION");
        sFailCauseMap.put(RESET_BY_FRAMEWORK, "RESET_BY_FRAMEWORK");
    }

    private DataFailCause() {
    }

    /**
     * Map of subId -> set of data call setup permanent failure for the carrier.
     */
    private static final HashMap<Integer, Set<Integer>> sPermanentFailureCache =
            new HashMap<>();

    /**
     * Returns whether or not the fail cause is a failure that requires a modem restart
     *
     * @param context device context
     * @param cause data disconnect cause
     * @param subId subscription index
     * @return true if the fail cause code needs platform to trigger a modem restart.
     *
     * @hide
     */
    public static boolean isRadioRestartFailure(@NonNull Context context, @FailCause int cause,
                                                int subId) {
        CarrierConfigManager configManager = (CarrierConfigManager)
                context.getSystemService(Context.CARRIER_CONFIG_SERVICE);
        if (configManager != null) {
            PersistableBundle b = configManager.getConfigForSubId(subId);

            if (b != null) {
                if (cause == REGULAR_DEACTIVATION
                        && b.getBoolean(CarrierConfigManager
                        .KEY_RESTART_RADIO_ON_PDP_FAIL_REGULAR_DEACTIVATION_BOOL)) {
                    // This is for backward compatibility support. We need to continue support this
                    // old configuration until it gets removed in the future.
                    return true;
                }
                // Check the current configurations.
                int[] causeCodes = b.getIntArray(CarrierConfigManager
                        .KEY_RADIO_RESTART_FAILURE_CAUSES_INT_ARRAY);
                if (causeCodes != null) {
                    return Arrays.stream(causeCodes).anyMatch(i -> i == cause);
                }
            }
        }

        return false;
    }

    /** @hide */
    public static boolean isPermanentFailure(@NonNull Context context, @FailCause int failCause,
                                             int subId) {
        synchronized (sPermanentFailureCache) {

            Set<Integer> permanentFailureSet = sPermanentFailureCache.get(subId);

            // In case of cache miss, we need to look up the settings from carrier config.
            if (permanentFailureSet == null) {
                // Retrieve the permanent failure from carrier config
                CarrierConfigManager configManager = (CarrierConfigManager)
                        context.getSystemService(Context.CARRIER_CONFIG_SERVICE);
                if (configManager != null) {
                    PersistableBundle b = configManager.getConfigForSubId(subId);
                    if (b != null) {
                        String[] permanentFailureStrings = b.getStringArray(CarrierConfigManager.
                                KEY_CARRIER_DATA_CALL_PERMANENT_FAILURE_STRINGS);
                        if (permanentFailureStrings != null) {
                            permanentFailureSet = new HashSet<>();
                            for (Map.Entry<Integer, String> e : sFailCauseMap.entrySet()) {
                                if (ArrayUtils.contains(permanentFailureStrings, e.getValue())) {
                                    permanentFailureSet.add(e.getKey());
                                }
                            }
                        }
                    }
                }

                // If we are not able to find the configuration from carrier config, use the default
                // ones.
                if (permanentFailureSet == null) {
                    permanentFailureSet = new HashSet<Integer>() {
                        {
                            add(OPERATOR_BARRED);
                            add(MISSING_UNKNOWN_APN);
                            add(UNKNOWN_PDP_ADDRESS_TYPE);
                            add(USER_AUTHENTICATION);
                            add(ACTIVATION_REJECT_GGSN);
                            add(SERVICE_OPTION_NOT_SUPPORTED);
                            add(SERVICE_OPTION_NOT_SUBSCRIBED);
                            add(NSAPI_IN_USE);
                            add(ONLY_IPV4_ALLOWED);
                            add(ONLY_IPV6_ALLOWED);
                            add(PROTOCOL_ERRORS);
                            add(RADIO_POWER_OFF);
                            add(TETHERED_CALL_ACTIVE);
                            add(RADIO_NOT_AVAILABLE);
                            add(UNACCEPTABLE_NETWORK_PARAMETER);
                            add(SIGNAL_LOST);
                        }
                    };
                }

                sPermanentFailureCache.put(subId, permanentFailureSet);
            }

            return permanentFailureSet.contains(failCause);
        }
    }

    /** @hide */
    public static boolean isEventLoggable(@FailCause int dataFailCause) {
        return (dataFailCause == OPERATOR_BARRED) || (dataFailCause == INSUFFICIENT_RESOURCES)
                || (dataFailCause == UNKNOWN_PDP_ADDRESS_TYPE)
                || (dataFailCause == USER_AUTHENTICATION)
                || (dataFailCause == ACTIVATION_REJECT_GGSN)
                || (dataFailCause == ACTIVATION_REJECT_UNSPECIFIED)
                || (dataFailCause == SERVICE_OPTION_NOT_SUBSCRIBED)
                || (dataFailCause == SERVICE_OPTION_NOT_SUPPORTED)
                || (dataFailCause == SERVICE_OPTION_OUT_OF_ORDER)
                || (dataFailCause == NSAPI_IN_USE)
                || (dataFailCause == ONLY_IPV4_ALLOWED)
                || (dataFailCause == ONLY_IPV6_ALLOWED)
                || (dataFailCause == PROTOCOL_ERRORS)
                || (dataFailCause == SIGNAL_LOST)
                || (dataFailCause == RADIO_POWER_OFF)
                || (dataFailCause == TETHERED_CALL_ACTIVE)
                || (dataFailCause == UNACCEPTABLE_NETWORK_PARAMETER);
    }

    /** @hide */
    public static String toString(@FailCause int dataFailCause) {
        int cause = getFailCause(dataFailCause);
        return (cause == UNKNOWN) ? "UNKNOWN(" + dataFailCause + ")" : sFailCauseMap.get(cause);
    }

    /** @hide */
    public static int getFailCause(@FailCause int failCause) {
        if (sFailCauseMap.containsKey(failCause)) {
            return failCause;
        } else {
            return UNKNOWN;
        }
    }
}
