package android.telephony;

import android.annotation.IntDef;
import android.telecom.Connection;
import android.telephony.data.ApnSetting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Telephony Annotations.
 * Telephony sdk is a mainline module and others cannot reference hidden @IntDef. Moving some
 * telephony annotations to a separate class to allow others statically link to it.
 *
 * @hide
 */
public class Annotation {
    @IntDef(prefix = {"DATA_"}, value = {
            TelephonyManager.DATA_ACTIVITY_NONE,
            TelephonyManager.DATA_ACTIVITY_IN,
            TelephonyManager.DATA_ACTIVITY_OUT,
            TelephonyManager.DATA_ACTIVITY_INOUT,
            TelephonyManager.DATA_ACTIVITY_DORMANT,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DataActivityType {
    }

    @IntDef(prefix = {"DATA_"}, value = {
            TelephonyManager.DATA_UNKNOWN,
            TelephonyManager.DATA_DISCONNECTED,
            TelephonyManager.DATA_CONNECTING,
            TelephonyManager.DATA_CONNECTED,
            TelephonyManager.DATA_SUSPENDED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DataState {
    }

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"RADIO_POWER_"},
            value = {
                    TelephonyManager.RADIO_POWER_OFF,
                    TelephonyManager.RADIO_POWER_ON,
                    TelephonyManager.RADIO_POWER_UNAVAILABLE,
            })
    public @interface RadioPowerState {
    }

    @IntDef({
            TelephonyManager.SIM_ACTIVATION_STATE_UNKNOWN,
            TelephonyManager.SIM_ACTIVATION_STATE_ACTIVATING,
            TelephonyManager.SIM_ACTIVATION_STATE_ACTIVATED,
            TelephonyManager.SIM_ACTIVATION_STATE_DEACTIVATED,
            TelephonyManager.SIM_ACTIVATION_STATE_RESTRICTED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SimActivationState {
    }

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"SRVCC_STATE_"},
            value = {
                    TelephonyManager.SRVCC_STATE_HANDOVER_NONE,
                    TelephonyManager.SRVCC_STATE_HANDOVER_STARTED,
                    TelephonyManager.SRVCC_STATE_HANDOVER_COMPLETED,
                    TelephonyManager.SRVCC_STATE_HANDOVER_FAILED,
                    TelephonyManager.SRVCC_STATE_HANDOVER_CANCELED})
    public @interface SrvccState {
    }

    @IntDef(prefix = {"CALL_STATE_"}, value = {
            TelephonyManager.CALL_STATE_IDLE,
            TelephonyManager.CALL_STATE_RINGING,
            TelephonyManager.CALL_STATE_OFFHOOK
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CallState {
    }

    @IntDef({
            TelephonyManager.NETWORK_TYPE_UNKNOWN,
            TelephonyManager.NETWORK_TYPE_GPRS,
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_UMTS,
            TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A,
            TelephonyManager.NETWORK_TYPE_1xRTT,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_IDEN,
            TelephonyManager.NETWORK_TYPE_EVDO_B,
            TelephonyManager.NETWORK_TYPE_LTE,
            TelephonyManager.NETWORK_TYPE_EHRPD,
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_GSM,
            TelephonyManager.NETWORK_TYPE_TD_SCDMA,
            TelephonyManager.NETWORK_TYPE_IWLAN,

            //TODO: In order for @SystemApi methods to use this class, there cannot be any
            // public hidden members.  This network type is marked as hidden because it is not a
            // true network type and we are looking to remove it completely from the available list
            // of network types.
            //TelephonyManager.NETWORK_TYPE_LTE_CA,

            TelephonyManager.NETWORK_TYPE_NR,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface NetworkType {
    }

    @IntDef(flag = true, prefix = {"TYPE_"}, value = {
            ApnSetting.TYPE_DEFAULT,
            ApnSetting.TYPE_MMS,
            ApnSetting.TYPE_SUPL,
            ApnSetting.TYPE_DUN,
            ApnSetting.TYPE_HIPRI,
            ApnSetting.TYPE_FOTA,
            ApnSetting.TYPE_IMS,
            ApnSetting.TYPE_CBS,
            ApnSetting.TYPE_IA,
            ApnSetting.TYPE_EMERGENCY,
            ApnSetting.TYPE_MCX,
            ApnSetting.TYPE_XCAP,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ApnType {
    }

    @IntDef(value = {
            DataFailCause.NONE,
            DataFailCause.OPERATOR_BARRED,
            DataFailCause.NAS_SIGNALLING,
            DataFailCause.LLC_SNDCP,
            DataFailCause.INSUFFICIENT_RESOURCES,
            DataFailCause.MISSING_UNKNOWN_APN,
            DataFailCause.UNKNOWN_PDP_ADDRESS_TYPE,
            DataFailCause.USER_AUTHENTICATION,
            DataFailCause.ACTIVATION_REJECT_GGSN,
            DataFailCause.ACTIVATION_REJECT_UNSPECIFIED,
            DataFailCause.SERVICE_OPTION_NOT_SUPPORTED,
            DataFailCause.SERVICE_OPTION_NOT_SUBSCRIBED,
            DataFailCause.SERVICE_OPTION_OUT_OF_ORDER,
            DataFailCause.NSAPI_IN_USE,
            DataFailCause.REGULAR_DEACTIVATION,
            DataFailCause.QOS_NOT_ACCEPTED,
            DataFailCause.NETWORK_FAILURE,
            DataFailCause.UMTS_REACTIVATION_REQ,
            DataFailCause.FEATURE_NOT_SUPP,
            DataFailCause.TFT_SEMANTIC_ERROR,
            DataFailCause.TFT_SYTAX_ERROR,
            DataFailCause.UNKNOWN_PDP_CONTEXT,
            DataFailCause.FILTER_SEMANTIC_ERROR,
            DataFailCause.FILTER_SYTAX_ERROR,
            DataFailCause.PDP_WITHOUT_ACTIVE_TFT,
            DataFailCause.ACTIVATION_REJECTED_BCM_VIOLATION,
            DataFailCause.ONLY_IPV4_ALLOWED,
            DataFailCause.ONLY_IPV6_ALLOWED,
            DataFailCause.ONLY_SINGLE_BEARER_ALLOWED,
            DataFailCause.ESM_INFO_NOT_RECEIVED,
            DataFailCause.PDN_CONN_DOES_NOT_EXIST,
            DataFailCause.MULTI_CONN_TO_SAME_PDN_NOT_ALLOWED,
            DataFailCause.COLLISION_WITH_NETWORK_INITIATED_REQUEST,
            DataFailCause.ONLY_IPV4V6_ALLOWED,
            DataFailCause.ONLY_NON_IP_ALLOWED,
            DataFailCause.UNSUPPORTED_QCI_VALUE,
            DataFailCause.BEARER_HANDLING_NOT_SUPPORTED,
            DataFailCause.ACTIVE_PDP_CONTEXT_MAX_NUMBER_REACHED,
            DataFailCause.UNSUPPORTED_APN_IN_CURRENT_PLMN,
            DataFailCause.INVALID_TRANSACTION_ID,
            DataFailCause.MESSAGE_INCORRECT_SEMANTIC,
            DataFailCause.INVALID_MANDATORY_INFO,
            DataFailCause.MESSAGE_TYPE_UNSUPPORTED,
            DataFailCause.MSG_TYPE_NONCOMPATIBLE_STATE,
            DataFailCause.UNKNOWN_INFO_ELEMENT,
            DataFailCause.CONDITIONAL_IE_ERROR,
            DataFailCause.MSG_AND_PROTOCOL_STATE_UNCOMPATIBLE,
            DataFailCause.PROTOCOL_ERRORS,
            DataFailCause.APN_TYPE_CONFLICT,
            DataFailCause.INVALID_PCSCF_ADDR,
            DataFailCause.INTERNAL_CALL_PREEMPT_BY_HIGH_PRIO_APN,
            DataFailCause.EMM_ACCESS_BARRED,
            DataFailCause.EMERGENCY_IFACE_ONLY,
            DataFailCause.IFACE_MISMATCH,
            DataFailCause.COMPANION_IFACE_IN_USE,
            DataFailCause.IP_ADDRESS_MISMATCH,
            DataFailCause.IFACE_AND_POL_FAMILY_MISMATCH,
            DataFailCause.EMM_ACCESS_BARRED_INFINITE_RETRY,
            DataFailCause.AUTH_FAILURE_ON_EMERGENCY_CALL,
            DataFailCause.INVALID_DNS_ADDR,
            DataFailCause.INVALID_PCSCF_OR_DNS_ADDRESS,
            DataFailCause.CALL_PREEMPT_BY_EMERGENCY_APN,
            DataFailCause.UE_INITIATED_DETACH_OR_DISCONNECT,
            DataFailCause.MIP_FA_REASON_UNSPECIFIED,
            DataFailCause.MIP_FA_ADMIN_PROHIBITED,
            DataFailCause.MIP_FA_INSUFFICIENT_RESOURCES,
            DataFailCause.MIP_FA_MOBILE_NODE_AUTHENTICATION_FAILURE,
            DataFailCause.MIP_FA_HOME_AGENT_AUTHENTICATION_FAILURE,
            DataFailCause.MIP_FA_REQUESTED_LIFETIME_TOO_LONG,
            DataFailCause.MIP_FA_MALFORMED_REQUEST,
            DataFailCause.MIP_FA_MALFORMED_REPLY,
            DataFailCause.MIP_FA_ENCAPSULATION_UNAVAILABLE,
            DataFailCause.MIP_FA_VJ_HEADER_COMPRESSION_UNAVAILABLE,
            DataFailCause.MIP_FA_REVERSE_TUNNEL_UNAVAILABLE,
            DataFailCause.MIP_FA_REVERSE_TUNNEL_IS_MANDATORY,
            DataFailCause.MIP_FA_DELIVERY_STYLE_NOT_SUPPORTED,
            DataFailCause.MIP_FA_MISSING_NAI,
            DataFailCause.MIP_FA_MISSING_HOME_AGENT,
            DataFailCause.MIP_FA_MISSING_HOME_ADDRESS,
            DataFailCause.MIP_FA_UNKNOWN_CHALLENGE,
            DataFailCause.MIP_FA_MISSING_CHALLENGE,
            DataFailCause.MIP_FA_STALE_CHALLENGE,
            DataFailCause.MIP_HA_REASON_UNSPECIFIED,
            DataFailCause.MIP_HA_ADMIN_PROHIBITED,
            DataFailCause.MIP_HA_INSUFFICIENT_RESOURCES,
            DataFailCause.MIP_HA_MOBILE_NODE_AUTHENTICATION_FAILURE,
            DataFailCause.MIP_HA_FOREIGN_AGENT_AUTHENTICATION_FAILURE,
            DataFailCause.MIP_HA_REGISTRATION_ID_MISMATCH,
            DataFailCause.MIP_HA_MALFORMED_REQUEST,
            DataFailCause.MIP_HA_UNKNOWN_HOME_AGENT_ADDRESS,
            DataFailCause.MIP_HA_REVERSE_TUNNEL_UNAVAILABLE,
            DataFailCause.MIP_HA_REVERSE_TUNNEL_IS_MANDATORY,
            DataFailCause.MIP_HA_ENCAPSULATION_UNAVAILABLE,
            DataFailCause.CLOSE_IN_PROGRESS,
            DataFailCause.NETWORK_INITIATED_TERMINATION,
            DataFailCause.MODEM_APP_PREEMPTED,
            DataFailCause.PDN_IPV4_CALL_DISALLOWED,
            DataFailCause.PDN_IPV4_CALL_THROTTLED,
            DataFailCause.PDN_IPV6_CALL_DISALLOWED,
            DataFailCause.PDN_IPV6_CALL_THROTTLED,
            DataFailCause.MODEM_RESTART,
            DataFailCause.PDP_PPP_NOT_SUPPORTED,
            DataFailCause.UNPREFERRED_RAT,
            DataFailCause.PHYSICAL_LINK_CLOSE_IN_PROGRESS,
            DataFailCause.APN_PENDING_HANDOVER,
            DataFailCause.PROFILE_BEARER_INCOMPATIBLE,
            DataFailCause.SIM_CARD_CHANGED,
            DataFailCause.LOW_POWER_MODE_OR_POWERING_DOWN,
            DataFailCause.APN_DISABLED,
            DataFailCause.MAX_PPP_INACTIVITY_TIMER_EXPIRED,
            DataFailCause.IPV6_ADDRESS_TRANSFER_FAILED,
            DataFailCause.TRAT_SWAP_FAILED,
            DataFailCause.EHRPD_TO_HRPD_FALLBACK,
            DataFailCause.MIP_CONFIG_FAILURE,
            DataFailCause.PDN_INACTIVITY_TIMER_EXPIRED,
            DataFailCause.MAX_IPV4_CONNECTIONS,
            DataFailCause.MAX_IPV6_CONNECTIONS,
            DataFailCause.APN_MISMATCH,
            DataFailCause.IP_VERSION_MISMATCH,
            DataFailCause.DUN_CALL_DISALLOWED,
            DataFailCause.INTERNAL_EPC_NONEPC_TRANSITION,
            DataFailCause.INTERFACE_IN_USE,
            DataFailCause.APN_DISALLOWED_ON_ROAMING,
            DataFailCause.APN_PARAMETERS_CHANGED,
            DataFailCause.NULL_APN_DISALLOWED,
            DataFailCause.THERMAL_MITIGATION,
            DataFailCause.DATA_SETTINGS_DISABLED,
            DataFailCause.DATA_ROAMING_SETTINGS_DISABLED,
            DataFailCause.DDS_SWITCHED,
            DataFailCause.FORBIDDEN_APN_NAME,
            DataFailCause.DDS_SWITCH_IN_PROGRESS,
            DataFailCause.CALL_DISALLOWED_IN_ROAMING,
            DataFailCause.NON_IP_NOT_SUPPORTED,
            DataFailCause.PDN_NON_IP_CALL_THROTTLED,
            DataFailCause.PDN_NON_IP_CALL_DISALLOWED,
            DataFailCause.CDMA_LOCK,
            DataFailCause.CDMA_INTERCEPT,
            DataFailCause.CDMA_REORDER,
            DataFailCause.CDMA_RELEASE_DUE_TO_SO_REJECTION,
            DataFailCause.CDMA_INCOMING_CALL,
            DataFailCause.CDMA_ALERT_STOP,
            DataFailCause.CHANNEL_ACQUISITION_FAILURE,
            DataFailCause.MAX_ACCESS_PROBE,
            DataFailCause.CONCURRENT_SERVICE_NOT_SUPPORTED_BY_BASE_STATION,
            DataFailCause.NO_RESPONSE_FROM_BASE_STATION,
            DataFailCause.REJECTED_BY_BASE_STATION,
            DataFailCause.CONCURRENT_SERVICES_INCOMPATIBLE,
            DataFailCause.NO_CDMA_SERVICE,
            DataFailCause.RUIM_NOT_PRESENT,
            DataFailCause.CDMA_RETRY_ORDER,
            DataFailCause.ACCESS_BLOCK,
            DataFailCause.ACCESS_BLOCK_ALL,
            DataFailCause.IS707B_MAX_ACCESS_PROBES,
            DataFailCause.THERMAL_EMERGENCY,
            DataFailCause.CONCURRENT_SERVICES_NOT_ALLOWED,
            DataFailCause.INCOMING_CALL_REJECTED,
            DataFailCause.NO_SERVICE_ON_GATEWAY,
            DataFailCause.NO_GPRS_CONTEXT,
            DataFailCause.ILLEGAL_MS,
            DataFailCause.ILLEGAL_ME,
            DataFailCause.GPRS_SERVICES_AND_NON_GPRS_SERVICES_NOT_ALLOWED,
            DataFailCause.GPRS_SERVICES_NOT_ALLOWED,
            DataFailCause.MS_IDENTITY_CANNOT_BE_DERIVED_BY_THE_NETWORK,
            DataFailCause.IMPLICITLY_DETACHED,
            DataFailCause.PLMN_NOT_ALLOWED,
            DataFailCause.LOCATION_AREA_NOT_ALLOWED,
            DataFailCause.GPRS_SERVICES_NOT_ALLOWED_IN_THIS_PLMN,
            DataFailCause.PDP_DUPLICATE,
            DataFailCause.UE_RAT_CHANGE,
            DataFailCause.CONGESTION,
            DataFailCause.NO_PDP_CONTEXT_ACTIVATED,
            DataFailCause.ACCESS_CLASS_DSAC_REJECTION,
            DataFailCause.PDP_ACTIVATE_MAX_RETRY_FAILED,
            DataFailCause.RADIO_ACCESS_BEARER_FAILURE,
            DataFailCause.ESM_UNKNOWN_EPS_BEARER_CONTEXT,
            DataFailCause.DRB_RELEASED_BY_RRC,
            DataFailCause.CONNECTION_RELEASED,
            DataFailCause.EMM_DETACHED,
            DataFailCause.EMM_ATTACH_FAILED,
            DataFailCause.EMM_ATTACH_STARTED,
            DataFailCause.LTE_NAS_SERVICE_REQUEST_FAILED,
            DataFailCause.DUPLICATE_BEARER_ID,
            DataFailCause.ESM_COLLISION_SCENARIOS,
            DataFailCause.ESM_BEARER_DEACTIVATED_TO_SYNC_WITH_NETWORK,
            DataFailCause.ESM_NW_ACTIVATED_DED_BEARER_WITH_ID_OF_DEF_BEARER,
            DataFailCause.ESM_BAD_OTA_MESSAGE,
            DataFailCause.ESM_DOWNLOAD_SERVER_REJECTED_THE_CALL,
            DataFailCause.ESM_CONTEXT_TRANSFERRED_DUE_TO_IRAT,
            DataFailCause.DS_EXPLICIT_DEACTIVATION,
            DataFailCause.ESM_LOCAL_CAUSE_NONE,
            DataFailCause.LTE_THROTTLING_NOT_REQUIRED,
            DataFailCause.ACCESS_CONTROL_LIST_CHECK_FAILURE,
            DataFailCause.SERVICE_NOT_ALLOWED_ON_PLMN,
            DataFailCause.EMM_T3417_EXPIRED,
            DataFailCause.EMM_T3417_EXT_EXPIRED,
            DataFailCause.RRC_UPLINK_DATA_TRANSMISSION_FAILURE,
            DataFailCause.RRC_UPLINK_DELIVERY_FAILED_DUE_TO_HANDOVER,
            DataFailCause.RRC_UPLINK_CONNECTION_RELEASE,
            DataFailCause.RRC_UPLINK_RADIO_LINK_FAILURE,
            DataFailCause.RRC_UPLINK_ERROR_REQUEST_FROM_NAS,
            DataFailCause.RRC_CONNECTION_ACCESS_STRATUM_FAILURE,
            DataFailCause.RRC_CONNECTION_ANOTHER_PROCEDURE_IN_PROGRESS,
            DataFailCause.RRC_CONNECTION_ACCESS_BARRED,
            DataFailCause.RRC_CONNECTION_CELL_RESELECTION,
            DataFailCause.RRC_CONNECTION_CONFIG_FAILURE,
            DataFailCause.RRC_CONNECTION_TIMER_EXPIRED,
            DataFailCause.RRC_CONNECTION_LINK_FAILURE,
            DataFailCause.RRC_CONNECTION_CELL_NOT_CAMPED,
            DataFailCause.RRC_CONNECTION_SYSTEM_INTERVAL_FAILURE,
            DataFailCause.RRC_CONNECTION_REJECT_BY_NETWORK,
            DataFailCause.RRC_CONNECTION_NORMAL_RELEASE,
            DataFailCause.RRC_CONNECTION_RADIO_LINK_FAILURE,
            DataFailCause.RRC_CONNECTION_REESTABLISHMENT_FAILURE,
            DataFailCause.RRC_CONNECTION_OUT_OF_SERVICE_DURING_CELL_REGISTER,
            DataFailCause.RRC_CONNECTION_ABORT_REQUEST,
            DataFailCause.RRC_CONNECTION_SYSTEM_INFORMATION_BLOCK_READ_ERROR,
            DataFailCause.NETWORK_INITIATED_DETACH_WITH_AUTO_REATTACH,
            DataFailCause.NETWORK_INITIATED_DETACH_NO_AUTO_REATTACH,
            DataFailCause.ESM_PROCEDURE_TIME_OUT,
            DataFailCause.INVALID_CONNECTION_ID,
            DataFailCause.MAXIMIUM_NSAPIS_EXCEEDED,
            DataFailCause.INVALID_PRIMARY_NSAPI,
            DataFailCause.CANNOT_ENCODE_OTA_MESSAGE,
            DataFailCause.RADIO_ACCESS_BEARER_SETUP_FAILURE,
            DataFailCause.PDP_ESTABLISH_TIMEOUT_EXPIRED,
            DataFailCause.PDP_MODIFY_TIMEOUT_EXPIRED,
            DataFailCause.PDP_INACTIVE_TIMEOUT_EXPIRED,
            DataFailCause.PDP_LOWERLAYER_ERROR,
            DataFailCause.PDP_MODIFY_COLLISION,
            DataFailCause.MAXINUM_SIZE_OF_L2_MESSAGE_EXCEEDED,
            DataFailCause.NAS_REQUEST_REJECTED_BY_NETWORK,
            DataFailCause.RRC_CONNECTION_INVALID_REQUEST,
            DataFailCause.RRC_CONNECTION_TRACKING_AREA_ID_CHANGED,
            DataFailCause.RRC_CONNECTION_RF_UNAVAILABLE,
            DataFailCause.RRC_CONNECTION_ABORTED_DUE_TO_IRAT_CHANGE,
            DataFailCause.RRC_CONNECTION_RELEASED_SECURITY_NOT_ACTIVE,
            DataFailCause.RRC_CONNECTION_ABORTED_AFTER_HANDOVER,
            DataFailCause.RRC_CONNECTION_ABORTED_AFTER_IRAT_CELL_CHANGE,
            DataFailCause.RRC_CONNECTION_ABORTED_DURING_IRAT_CELL_CHANGE,
            DataFailCause.IMSI_UNKNOWN_IN_HOME_SUBSCRIBER_SERVER,
            DataFailCause.IMEI_NOT_ACCEPTED,
            DataFailCause.EPS_SERVICES_AND_NON_EPS_SERVICES_NOT_ALLOWED,
            DataFailCause.EPS_SERVICES_NOT_ALLOWED_IN_PLMN,
            DataFailCause.MSC_TEMPORARILY_NOT_REACHABLE,
            DataFailCause.CS_DOMAIN_NOT_AVAILABLE,
            DataFailCause.ESM_FAILURE,
            DataFailCause.MAC_FAILURE,
            DataFailCause.SYNCHRONIZATION_FAILURE,
            DataFailCause.UE_SECURITY_CAPABILITIES_MISMATCH,
            DataFailCause.SECURITY_MODE_REJECTED,
            DataFailCause.UNACCEPTABLE_NON_EPS_AUTHENTICATION,
            DataFailCause.CS_FALLBACK_CALL_ESTABLISHMENT_NOT_ALLOWED,
            DataFailCause.NO_EPS_BEARER_CONTEXT_ACTIVATED,
            DataFailCause.INVALID_EMM_STATE,
            DataFailCause.NAS_LAYER_FAILURE,
            DataFailCause.MULTIPLE_PDP_CALL_NOT_ALLOWED,
            DataFailCause.EMBMS_NOT_ENABLED,
            DataFailCause.IRAT_HANDOVER_FAILED,
            DataFailCause.EMBMS_REGULAR_DEACTIVATION,
            DataFailCause.TEST_LOOPBACK_REGULAR_DEACTIVATION,
            DataFailCause.LOWER_LAYER_REGISTRATION_FAILURE,
            DataFailCause.DATA_PLAN_EXPIRED,
            DataFailCause.UMTS_HANDOVER_TO_IWLAN,
            DataFailCause.EVDO_CONNECTION_DENY_BY_GENERAL_OR_NETWORK_BUSY,
            DataFailCause.EVDO_CONNECTION_DENY_BY_BILLING_OR_AUTHENTICATION_FAILURE,
            DataFailCause.EVDO_HDR_CHANGED,
            DataFailCause.EVDO_HDR_EXITED,
            DataFailCause.EVDO_HDR_NO_SESSION,
            DataFailCause.EVDO_USING_GPS_FIX_INSTEAD_OF_HDR_CALL,
            DataFailCause.EVDO_HDR_CONNECTION_SETUP_TIMEOUT,
            DataFailCause.FAILED_TO_ACQUIRE_COLOCATED_HDR,
            DataFailCause.OTASP_COMMIT_IN_PROGRESS,
            DataFailCause.NO_HYBRID_HDR_SERVICE,
            DataFailCause.HDR_NO_LOCK_GRANTED,
            DataFailCause.DBM_OR_SMS_IN_PROGRESS,
            DataFailCause.HDR_FADE,
            DataFailCause.HDR_ACCESS_FAILURE,
            DataFailCause.UNSUPPORTED_1X_PREV,
            DataFailCause.LOCAL_END,
            DataFailCause.NO_SERVICE,
            DataFailCause.FADE,
            DataFailCause.NORMAL_RELEASE,
            DataFailCause.ACCESS_ATTEMPT_ALREADY_IN_PROGRESS,
            DataFailCause.REDIRECTION_OR_HANDOFF_IN_PROGRESS,
            DataFailCause.EMERGENCY_MODE,
            DataFailCause.PHONE_IN_USE,
            DataFailCause.INVALID_MODE,
            DataFailCause.INVALID_SIM_STATE,
            DataFailCause.NO_COLLOCATED_HDR,
            DataFailCause.UE_IS_ENTERING_POWERSAVE_MODE,
            DataFailCause.DUAL_SWITCH,
            DataFailCause.PPP_TIMEOUT,
            DataFailCause.PPP_AUTH_FAILURE,
            DataFailCause.PPP_OPTION_MISMATCH,
            DataFailCause.PPP_PAP_FAILURE,
            DataFailCause.PPP_CHAP_FAILURE,
            DataFailCause.PPP_CLOSE_IN_PROGRESS,
            DataFailCause.LIMITED_TO_IPV4,
            DataFailCause.LIMITED_TO_IPV6,
            DataFailCause.VSNCP_TIMEOUT,
            DataFailCause.VSNCP_GEN_ERROR,
            DataFailCause.VSNCP_APN_UNAUTHORIZED,
            DataFailCause.VSNCP_PDN_LIMIT_EXCEEDED,
            DataFailCause.VSNCP_NO_PDN_GATEWAY_ADDRESS,
            DataFailCause.VSNCP_PDN_GATEWAY_UNREACHABLE,
            DataFailCause.VSNCP_PDN_GATEWAY_REJECT,
            DataFailCause.VSNCP_INSUFFICIENT_PARAMETERS,
            DataFailCause.VSNCP_RESOURCE_UNAVAILABLE,
            DataFailCause.VSNCP_ADMINISTRATIVELY_PROHIBITED,
            DataFailCause.VSNCP_PDN_ID_IN_USE,
            DataFailCause.VSNCP_SUBSCRIBER_LIMITATION,
            DataFailCause.VSNCP_PDN_EXISTS_FOR_THIS_APN,
            DataFailCause.VSNCP_RECONNECT_NOT_ALLOWED,
            DataFailCause.IPV6_PREFIX_UNAVAILABLE,
            DataFailCause.HANDOFF_PREFERENCE_CHANGED,
            DataFailCause.OEM_DCFAILCAUSE_1,
            DataFailCause.OEM_DCFAILCAUSE_2,
            DataFailCause.OEM_DCFAILCAUSE_3,
            DataFailCause.OEM_DCFAILCAUSE_4,
            DataFailCause.OEM_DCFAILCAUSE_5,
            DataFailCause.OEM_DCFAILCAUSE_6,
            DataFailCause.OEM_DCFAILCAUSE_7,
            DataFailCause.OEM_DCFAILCAUSE_8,
            DataFailCause.OEM_DCFAILCAUSE_9,
            DataFailCause.OEM_DCFAILCAUSE_10,
            DataFailCause.OEM_DCFAILCAUSE_11,
            DataFailCause.OEM_DCFAILCAUSE_12,
            DataFailCause.OEM_DCFAILCAUSE_13,
            DataFailCause.OEM_DCFAILCAUSE_14,
            DataFailCause.OEM_DCFAILCAUSE_15,
            DataFailCause.REGISTRATION_FAIL,
            DataFailCause.GPRS_REGISTRATION_FAIL,
            DataFailCause.SIGNAL_LOST,
            DataFailCause.PREF_RADIO_TECH_CHANGED,
            DataFailCause.RADIO_POWER_OFF,
            DataFailCause.TETHERED_CALL_ACTIVE,
            DataFailCause.ERROR_UNSPECIFIED,
            DataFailCause.UNKNOWN,
            DataFailCause.RADIO_NOT_AVAILABLE,
            DataFailCause.UNACCEPTABLE_NETWORK_PARAMETER,
            DataFailCause.LOST_CONNECTION,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DataFailureCause {
    }

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"PRECISE_CALL_STATE_"},
            value = {
            PreciseCallState.PRECISE_CALL_STATE_NOT_VALID,
            PreciseCallState.PRECISE_CALL_STATE_IDLE,
            PreciseCallState.PRECISE_CALL_STATE_ACTIVE,
            PreciseCallState.PRECISE_CALL_STATE_HOLDING,
            PreciseCallState.PRECISE_CALL_STATE_DIALING,
            PreciseCallState.PRECISE_CALL_STATE_ALERTING,
            PreciseCallState. PRECISE_CALL_STATE_INCOMING,
            PreciseCallState.PRECISE_CALL_STATE_WAITING,
            PreciseCallState.PRECISE_CALL_STATE_DISCONNECTED,
            PreciseCallState.PRECISE_CALL_STATE_DISCONNECTING})
    public @interface PreciseCallStates {}

    @IntDef(value = {
            DisconnectCause.NOT_VALID,
            DisconnectCause.NOT_DISCONNECTED,
            DisconnectCause.INCOMING_MISSED,
            DisconnectCause.NORMAL,
            DisconnectCause.LOCAL,
            DisconnectCause.BUSY,
            DisconnectCause.CONGESTION,
            DisconnectCause.MMI,
            DisconnectCause.INVALID_NUMBER,
            DisconnectCause.NUMBER_UNREACHABLE,
            DisconnectCause.SERVER_UNREACHABLE,
            DisconnectCause.INVALID_CREDENTIALS,
            DisconnectCause.OUT_OF_NETWORK,
            DisconnectCause.SERVER_ERROR,
            DisconnectCause.TIMED_OUT,
            DisconnectCause.LOST_SIGNAL,
            DisconnectCause.LIMIT_EXCEEDED,
            DisconnectCause.INCOMING_REJECTED,
            DisconnectCause.POWER_OFF,
            DisconnectCause.OUT_OF_SERVICE,
            DisconnectCause.ICC_ERROR,
            DisconnectCause.CALL_BARRED,
            DisconnectCause.FDN_BLOCKED,
            DisconnectCause.CS_RESTRICTED,
            DisconnectCause.CS_RESTRICTED_NORMAL,
            DisconnectCause.CS_RESTRICTED_EMERGENCY,
            DisconnectCause.UNOBTAINABLE_NUMBER,
            DisconnectCause.CDMA_LOCKED_UNTIL_POWER_CYCLE,
            DisconnectCause.CDMA_DROP,
            DisconnectCause.CDMA_INTERCEPT,
            DisconnectCause.CDMA_REORDER,
            DisconnectCause.CDMA_SO_REJECT,
            DisconnectCause.CDMA_RETRY_ORDER,
            DisconnectCause.CDMA_ACCESS_FAILURE,
            DisconnectCause.CDMA_PREEMPTED,
            DisconnectCause.CDMA_NOT_EMERGENCY,
            DisconnectCause.CDMA_ACCESS_BLOCKED,
            DisconnectCause.ERROR_UNSPECIFIED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DisconnectCauses {
    }

    @IntDef(value = {
            PreciseDisconnectCause.NOT_VALID,
            PreciseDisconnectCause.NO_DISCONNECT_CAUSE_AVAILABLE,
            PreciseDisconnectCause.UNOBTAINABLE_NUMBER,
            PreciseDisconnectCause.NORMAL,
            PreciseDisconnectCause.BUSY,
            PreciseDisconnectCause.NUMBER_CHANGED,
            PreciseDisconnectCause.STATUS_ENQUIRY,
            PreciseDisconnectCause.NORMAL_UNSPECIFIED,
            PreciseDisconnectCause.NO_CIRCUIT_AVAIL,
            PreciseDisconnectCause.TEMPORARY_FAILURE,
            PreciseDisconnectCause.SWITCHING_CONGESTION,
            PreciseDisconnectCause.CHANNEL_NOT_AVAIL,
            PreciseDisconnectCause.QOS_NOT_AVAIL,
            PreciseDisconnectCause.BEARER_NOT_AVAIL,
            PreciseDisconnectCause.ACM_LIMIT_EXCEEDED,
            PreciseDisconnectCause.CALL_BARRED,
            PreciseDisconnectCause.FDN_BLOCKED,
            PreciseDisconnectCause.IMSI_UNKNOWN_IN_VLR,
            PreciseDisconnectCause.IMEI_NOT_ACCEPTED,
            PreciseDisconnectCause.CDMA_LOCKED_UNTIL_POWER_CYCLE,
            PreciseDisconnectCause.CDMA_DROP,
            PreciseDisconnectCause.CDMA_INTERCEPT,
            PreciseDisconnectCause.CDMA_REORDER,
            PreciseDisconnectCause.CDMA_SO_REJECT,
            PreciseDisconnectCause.CDMA_RETRY_ORDER,
            PreciseDisconnectCause.CDMA_ACCESS_FAILURE,
            PreciseDisconnectCause.CDMA_PREEMPTED,
            PreciseDisconnectCause.CDMA_NOT_EMERGENCY,
            PreciseDisconnectCause.CDMA_ACCESS_BLOCKED,
            PreciseDisconnectCause.ERROR_UNSPECIFIED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PreciseDisconnectCauses {
    }

    @IntDef({
            Connection.AUDIO_CODEC_NONE,
            Connection.AUDIO_CODEC_AMR,
            Connection.AUDIO_CODEC_AMR_WB,
            Connection.AUDIO_CODEC_QCELP13K,
            Connection.AUDIO_CODEC_EVRC,
            Connection.AUDIO_CODEC_EVRC_B,
            Connection.AUDIO_CODEC_EVRC_WB,
            Connection.AUDIO_CODEC_EVRC_NW,
            Connection.AUDIO_CODEC_GSM_EFR,
            Connection.AUDIO_CODEC_GSM_FR,
            Connection.AUDIO_CODEC_G711U,
            Connection.AUDIO_CODEC_G723,
            Connection.AUDIO_CODEC_G711A,
            Connection.AUDIO_CODEC_G722,
            Connection.AUDIO_CODEC_G711AB,
            Connection.AUDIO_CODEC_G729,
            Connection.AUDIO_CODEC_EVS_NB,
            Connection.AUDIO_CODEC_EVS_WB,
            Connection.AUDIO_CODEC_EVS_SWB,
            Connection.AUDIO_CODEC_EVS_FB
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ImsAudioCodec {
    }

    /**
     * Call forwarding function status
     */
    @IntDef(prefix = { "STATUS_" }, value = {
        CallForwardingInfo.STATUS_ACTIVE,
        CallForwardingInfo.STATUS_INACTIVE,
        CallForwardingInfo.STATUS_UNKNOWN_ERROR,
        CallForwardingInfo.STATUS_NOT_SUPPORTED,
        CallForwardingInfo.STATUS_FDN_CHECK_FAILURE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CallForwardingStatus {
    }

    /**
     * Call forwarding reason types
     */
    @IntDef(flag = true, prefix = { "REASON_" }, value = {
        CallForwardingInfo.REASON_UNCONDITIONAL,
        CallForwardingInfo.REASON_BUSY,
        CallForwardingInfo.REASON_NO_REPLY,
        CallForwardingInfo.REASON_NOT_REACHABLE,
        CallForwardingInfo.REASON_ALL,
        CallForwardingInfo.REASON_ALL_CONDITIONAL
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CallForwardingReason {
    }

    /**
     * Call waiting function status
     */
    @IntDef(prefix = { "CALL_WAITING_STATUS_" }, value = {
        TelephonyManager.CALL_WAITING_STATUS_ACTIVE,
        TelephonyManager.CALL_WAITING_STATUS_INACTIVE,
        TelephonyManager.CALL_WAITING_STATUS_NOT_SUPPORTED,
        TelephonyManager.CALL_WAITING_STATUS_UNKNOWN_ERROR
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CallWaitingStatus {
    }

    /**
     * UICC SIM Application Types
     */
    @IntDef(prefix = { "APPTYPE_" }, value = {
            TelephonyManager.APPTYPE_SIM,
            TelephonyManager.APPTYPE_USIM,
            TelephonyManager.APPTYPE_RUIM,
            TelephonyManager.APPTYPE_CSIM,
            TelephonyManager.APPTYPE_ISIM
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface UiccAppType{}

    /**
     * Override network type
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "OVERRIDE_NETWORK_TYPE_", value = {
            DisplayInfo.OVERRIDE_NETWORK_TYPE_NONE,
            DisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA,
            DisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO,
            DisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA,
            DisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE})
    public @interface OverrideNetworkType {}
}
