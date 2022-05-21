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

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.content.Context;
import android.os.PersistableBundle;
import android.telephony.Annotation.DataFailureCause;

import com.android.internal.telephony.util.ArrayUtils;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * DataFailCause collects data connection failure causes code from different sources.
 */
public final class DataFailCause {
    /** There is no failure */
    public static final int NONE = 0;

    // This series of errors as specified by the standards
    // specified in ril.h
    /** Operator determined barring. (no retry) */
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
    /**
     * UE requested to modify QoS parameters or the bearer control mode, which is not compatible
     * with the selected bearer control mode.
     */
    public static final int ACTIVATION_REJECTED_BCM_VIOLATION = 0x30;
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
    /**
     * Network has already initiated the activation, modification, or deactivation of bearer
     * resources that was requested by the UE.
     */
    public static final int COLLISION_WITH_NETWORK_INITIATED_REQUEST = 0x38;
    /**
     * Network supports IPv4v6 PDP type only. Non-IP type is not allowed. In LTE mode of operation,
     * this is a PDN throttling cause code, meaning the UE may throttle further requests to the
     * same APN.
     */
    public static final int ONLY_IPV4V6_ALLOWED = 0x39;
    /**
     * Network supports non-IP PDP type only. IPv4, IPv6 and IPv4v6 is not allowed. In LTE mode of
     * operation, this is a PDN throttling cause code, meaning the UE can throttle further requests
     * to the same APN.
     */
    public static final int ONLY_NON_IP_ALLOWED = 0x3A;
    /** QCI (QoS Class Identifier) indicated in the UE request cannot be supported. */
    public static final int UNSUPPORTED_QCI_VALUE = 0x3B;
    /** Procedure requested by the UE was rejected because the bearer handling is not supported. */
    public static final int BEARER_HANDLING_NOT_SUPPORTED = 0x3C;
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
    /** Not receiving a DNS address that was mandatory. */
    public static final int INVALID_DNS_ADDR = 0x7B;
    /** Not receiving either a PCSCF or a DNS address, one of them being mandatory. */
    public static final int INVALID_PCSCF_OR_DNS_ADDRESS = 0x7C;
    /** Emergency call bring up on a different ePDG. */
    public static final int CALL_PREEMPT_BY_EMERGENCY_APN = 0x7F;
    /** UE performs a detach or disconnect PDN action based on TE requirements. */
    public static final int UE_INITIATED_DETACH_OR_DISCONNECT = 0x80;

    /** Reason unspecified for foreign agent rejected MIP (Mobile IP) registration. */
    public static final int MIP_FA_REASON_UNSPECIFIED = 0x7D0;
    /** Foreign agent administratively prohibited MIP (Mobile IP) registration. */
    public static final int MIP_FA_ADMIN_PROHIBITED = 0x7D1;
    /** Foreign agent rejected MIP (Mobile IP) registration because of insufficient resources. */
    public static final int MIP_FA_INSUFFICIENT_RESOURCES = 0x7D2;
    /**
     * Foreign agent rejected MIP (Mobile IP) registration because of MN-AAA authenticator was
     * wrong.
     */
    public static final int MIP_FA_MOBILE_NODE_AUTHENTICATION_FAILURE = 0x7D3;
    /**
     * Foreign agent rejected MIP (Mobile IP) registration because of home agent authentication
     * failure.
     */
    public static final int MIP_FA_HOME_AGENT_AUTHENTICATION_FAILURE = 0x7D4;
    /**
     * Foreign agent rejected MIP (Mobile IP) registration because of requested lifetime was too
     * long.
     */
    public static final int MIP_FA_REQUESTED_LIFETIME_TOO_LONG = 0x7D5;
    /** Foreign agent rejected MIP (Mobile IP) registration because of malformed request. */
    public static final int MIP_FA_MALFORMED_REQUEST = 0x7D6;
    /** Foreign agent rejected MIP (Mobile IP) registration because of malformed reply. */
    public static final int MIP_FA_MALFORMED_REPLY = 0x7D7;
    /**
     * Foreign agent rejected MIP (Mobile IP) registration because of requested encapsulation was
     * unavailable.
     */
    public static final int MIP_FA_ENCAPSULATION_UNAVAILABLE = 0x7D8;
    /**
     * Foreign agent rejected MIP (Mobile IP) registration of VJ Header Compression was
     * unavailable.
     */
    public static final int MIP_FA_VJ_HEADER_COMPRESSION_UNAVAILABLE = 0x7D9;
    /**
     * Foreign agent rejected MIP (Mobile IP) registration because of reverse tunnel was
     * unavailable.
     */
    public static final int MIP_FA_REVERSE_TUNNEL_UNAVAILABLE = 0x7DA;
    /**
     * Foreign agent rejected MIP (Mobile IP) registration because of reverse tunnel was mandatory
     * but not requested by device.
     */
    public static final int MIP_FA_REVERSE_TUNNEL_IS_MANDATORY = 0x7DB;
    /**
     * Foreign agent rejected MIP (Mobile IP) registration because of delivery style was not
     * supported.
     */
    public static final int MIP_FA_DELIVERY_STYLE_NOT_SUPPORTED = 0x7DC;
    /**
     * Foreign agent rejected MIP (Mobile IP) registration because of missing NAI (Network Access
     * Identifier).
     */
    public static final int MIP_FA_MISSING_NAI = 0x7DD;
    /** Foreign agent rejected MIP (Mobile IP) registration because of missing Home Agent. */
    public static final int MIP_FA_MISSING_HOME_AGENT = 0x7DE;
    /** Foreign agent rejected MIP (Mobile IP) registration because of missing Home Address. */
    public static final int MIP_FA_MISSING_HOME_ADDRESS = 0x7DF;
    /** Foreign agent rejected MIP (Mobile IP) registration because of unknown challenge. */
    public static final int MIP_FA_UNKNOWN_CHALLENGE = 0x7E0;
    /** Foreign agent rejected MIP (Mobile IP) registration because of missing challenge. */
    public static final int MIP_FA_MISSING_CHALLENGE = 0x7E1;
    /** Foreign agent rejected MIP (Mobile IP) registration because of stale challenge. */
    public static final int MIP_FA_STALE_CHALLENGE = 0x7E2;
    /** Reason unspecified for home agent rejected MIP (Mobile IP) registration. */
    public static final int MIP_HA_REASON_UNSPECIFIED = 0x7E3;
    /** Home agent administratively prohibited MIP (Mobile IP) registration. */
    public static final int MIP_HA_ADMIN_PROHIBITED = 0x7E4;
    /** Home agent rejected MIP (Mobile IP) registration because of insufficient resources. */
    public static final int MIP_HA_INSUFFICIENT_RESOURCES = 0x7E5;
    /**
     * Home agent rejected MIP (Mobile IP) registration because of MN-HA authenticator was
     * wrong.
     */
    public static final int MIP_HA_MOBILE_NODE_AUTHENTICATION_FAILURE = 0x7E6;
    /**
     * Home agent rejected MIP (Mobile IP) registration because of foreign agent authentication
     * failure.
     */
    public static final int MIP_HA_FOREIGN_AGENT_AUTHENTICATION_FAILURE = 0x7E7;
    /** Home agent rejected MIP (Mobile IP) registration because of registration id mismatch. */
    public static final int MIP_HA_REGISTRATION_ID_MISMATCH = 0x7E8;
    /** Home agent rejected MIP (Mobile IP) registration because of malformed request. */
    public static final int MIP_HA_MALFORMED_REQUEST = 0x7E9;
    /** Home agent rejected MIP (Mobile IP) registration because of unknown home agent address. */
    public static final int MIP_HA_UNKNOWN_HOME_AGENT_ADDRESS = 0x7EA;
    /**
     * Home agent rejected MIP (Mobile IP) registration because of reverse tunnel was
     * unavailable.
     */
    public static final int MIP_HA_REVERSE_TUNNEL_UNAVAILABLE = 0x7EB;
    /**
     * Home agent rejected MIP (Mobile IP) registration because of reverse tunnel is mandatory but
     * not requested by device.
     */
    public static final int MIP_HA_REVERSE_TUNNEL_IS_MANDATORY = 0x7EC;
    /** Home agent rejected MIP (Mobile IP) registration because of encapsulation unavailable. */
    public static final int MIP_HA_ENCAPSULATION_UNAVAILABLE = 0x7ED;
    /** Tearing down is in progress. */
    public static final int CLOSE_IN_PROGRESS = 0x7EE;
    /** Brought down by the network. */
    public static final int NETWORK_INITIATED_TERMINATION = 0x7EF;
    /** Another application in modem preempts the data call. */
    public static final int MODEM_APP_PREEMPTED = 0x7F0;
    /**
     * IPV4 PDN is in throttled state due to network providing only IPV6 address during the
     * previous VSNCP bringup (subs_limited_to_v6).
     */
    public static final int PDN_IPV4_CALL_DISALLOWED = 0x7F1;
    /** IPV4 PDN is in throttled state due to previous VSNCP bringup failure(s). */
    public static final int PDN_IPV4_CALL_THROTTLED = 0x7F2;
    /**
     * IPV6 PDN is in throttled state due to network providing only IPV4 address during the
     * previous VSNCP bringup (subs_limited_to_v4).
     */
    public static final int PDN_IPV6_CALL_DISALLOWED = 0x7F3;
    /** IPV6 PDN is in throttled state due to previous VSNCP bringup failure(s). */
    public static final int PDN_IPV6_CALL_THROTTLED = 0x7F4;
    /** Modem restart. */
    public static final int MODEM_RESTART = 0x7F5;
    /** PDP PPP calls are not supported. */
    public static final int PDP_PPP_NOT_SUPPORTED = 0x7F6;
    /** RAT on which the data call is attempted/connected is no longer the preferred RAT. */
    public static final int UNPREFERRED_RAT = 0x7F7;
    /** Physical link is in the process of cleanup. */
    public static final int PHYSICAL_LINK_CLOSE_IN_PROGRESS = 0x7F8;
    /** Interface bring up is attempted for an APN that is yet to be handed over to target RAT. */
    public static final int APN_PENDING_HANDOVER = 0x7F9;
    /** APN bearer type in the profile does not match preferred network mode. */
    public static final int PROFILE_BEARER_INCOMPATIBLE = 0x7FA;
    /** Card was refreshed or removed. */
    public static final int SIM_CARD_CHANGED = 0x7FB;
    /** Device is going into lower power mode or powering down. */
    public static final int LOW_POWER_MODE_OR_POWERING_DOWN = 0x7FC;
    /** APN has been disabled. */
    public static final int APN_DISABLED = 0x7FD;
    /** Maximum PPP inactivity timer expired. */
    public static final int MAX_PPP_INACTIVITY_TIMER_EXPIRED = 0x7FE;
    /** IPv6 address transfer failed. */
    public static final int IPV6_ADDRESS_TRANSFER_FAILED = 0x7FF;
    /** Target RAT swap failed. */
    public static final int TRAT_SWAP_FAILED = 0x800;
    /** Device falls back from eHRPD to HRPD. */
    public static final int EHRPD_TO_HRPD_FALLBACK = 0x801;
    /**
     * UE is in MIP-only configuration but the MIP configuration fails on call bring up due to
     * incorrect provisioning.
     */
    public static final int MIP_CONFIG_FAILURE = 0x802;
    /**
     * PDN inactivity timer expired due to no data transmission in a configurable duration of time.
     */
    public static final int PDN_INACTIVITY_TIMER_EXPIRED = 0x803;
    /**
     * IPv4 data call bring up is rejected because the UE already maintains the allotted maximum
     * number of IPv4 data connections.
     */
    public static final int MAX_IPV4_CONNECTIONS = 0x804;
    /**
     * IPv6 data call bring up is rejected because the UE already maintains the allotted maximum
     * number of IPv6 data connections.
     */
    public static final int MAX_IPV6_CONNECTIONS = 0x805;
    /**
     * New PDN bring up is rejected during interface selection because the UE has already allotted
     * the available interfaces for other PDNs.
     */
    public static final int APN_MISMATCH = 0x806;
    /**
     * New call bring up is rejected since the existing data call IP type doesn't match the
     * requested IP.
     */
    public static final int IP_VERSION_MISMATCH = 0x807;
    /** Dial up networking (DUN) call bring up is rejected since UE is in eHRPD RAT. */
    public static final int DUN_CALL_DISALLOWED = 0x808;
    /*** Rejected/Brought down since UE is transition between EPC and NONEPC RAT. */
    public static final int INTERNAL_EPC_NONEPC_TRANSITION = 0x809;
    /** The current interface is being in use. */
    public static final int INTERFACE_IN_USE = 0x80A;
    /** PDN connection to the APN is disallowed on the roaming network. */
    public static final int APN_DISALLOWED_ON_ROAMING = 0x80B;
    /** APN-related parameters are changed. */
    public static final int APN_PARAMETERS_CHANGED = 0x80C;
    /** PDN is attempted to be brought up with NULL APN but NULL APN is not supported. */
    public static final int NULL_APN_DISALLOWED = 0x80D;
    /**
     * Thermal level increases and causes calls to be torn down when normal mode of operation is
     * not allowed.
     */
    public static final int THERMAL_MITIGATION = 0x80E;
    /**
     * PDN Connection to a given APN is disallowed because data is disabled from the device user
     * interface settings.
     */
    public static final int DATA_SETTINGS_DISABLED = 0x80F;
    /**
     * PDN Connection to a given APN is disallowed because data roaming is disabled from the device
     * user interface settings and the UE is roaming.
     */
    public static final int DATA_ROAMING_SETTINGS_DISABLED = 0x810;
    /** DDS (Default data subscription) switch occurs. */
    public static final int DDS_SWITCHED = 0x811;
    /** PDN being brought up with an APN that is part of forbidden APN Name list. */
    public static final int FORBIDDEN_APN_NAME = 0x812;
    /** Default data subscription switch is in progress. */
    public static final int DDS_SWITCH_IN_PROGRESS = 0x813;
    /** Roaming is disallowed during call bring up. */
    public static final int CALL_DISALLOWED_IN_ROAMING = 0x814;
    /**
     * UE is unable to bring up a non-IP data call because the device is not camped on a NB1 cell.
     */
    public static final int NON_IP_NOT_SUPPORTED = 0x815;
    /** Non-IP PDN is in throttled state due to previous VSNCP bringup failure(s). */
    public static final int PDN_NON_IP_CALL_THROTTLED = 0x816;
    /** Non-IP PDN is in disallowed state due to the network providing only an IP address. */
    public static final int PDN_NON_IP_CALL_DISALLOWED = 0x817;
    /** Device in CDMA locked state. */
    public static final int CDMA_LOCK = 0x818;
    /** Received an intercept order from the base station. */
    public static final int CDMA_INTERCEPT = 0x819;
    /** Receiving a reorder from the base station. */
    public static final int CDMA_REORDER = 0x81A;
    /** Receiving a release from the base station with a SO (Service Option) Reject reason. */
    public static final int CDMA_RELEASE_DUE_TO_SO_REJECTION = 0x81B;
    /** Receiving an incoming call from the base station. */
    public static final int CDMA_INCOMING_CALL = 0x81C;
    /** Received an alert stop from the base station due to incoming only. */
    public static final int CDMA_ALERT_STOP = 0x81D;
    /**
     * Channel acquisition failures. This indicates that device has failed acquiring all the
     * channels in the PRL.
     */
    public static final int CHANNEL_ACQUISITION_FAILURE = 0x81E;
    /** Maximum access probes transmitted. */
    public static final int MAX_ACCESS_PROBE = 0x81F;
    /** Concurrent service is not supported by base station. */
    public static final int CONCURRENT_SERVICE_NOT_SUPPORTED_BY_BASE_STATION = 0x820;
    /** There was no response received from the base station. */
    public static final int NO_RESPONSE_FROM_BASE_STATION = 0x821;
    /** The base station rejecting the call. */
    public static final int REJECTED_BY_BASE_STATION = 0x822;
    /** The concurrent services requested were not compatible. */
    public static final int CONCURRENT_SERVICES_INCOMPATIBLE = 0x823;
    /** Device does not have CDMA service. */
    public static final int NO_CDMA_SERVICE = 0x824;
    /** RUIM not being present. */
    public static final int RUIM_NOT_PRESENT = 0x825;
    /** Receiving a retry order from the base station. */
    public static final int CDMA_RETRY_ORDER = 0x826;
    /** Access blocked by the base station. */
    public static final int ACCESS_BLOCK = 0x827;
    /** Access blocked by the base station for all mobile devices. */
    public static final int ACCESS_BLOCK_ALL = 0x828;
    /** Maximum access probes for the IS-707B call. */
    public static final int IS707B_MAX_ACCESS_PROBES = 0x829;
    /** Put device in thermal emergency. */
    public static final int THERMAL_EMERGENCY = 0x82A;
    /** In favor of a voice call or SMS when concurrent voice and data are not supported. */
    public static final int CONCURRENT_SERVICES_NOT_ALLOWED = 0x82B;
    /** The other clients rejected incoming call. */
    public static final int INCOMING_CALL_REJECTED = 0x82C;
    /** No service on the gateway. */
    public static final int NO_SERVICE_ON_GATEWAY = 0x82D;
    /** GPRS context is not available. */
    public static final int NO_GPRS_CONTEXT = 0x82E;
    /**
     * Network refuses service to the MS because either an identity of the MS is not acceptable to
     * the network or the MS does not pass the authentication check.
     */
    public static final int ILLEGAL_MS = 0x82F;
    /** ME could not be authenticated and the ME used is not acceptable to the network. */
    public static final int ILLEGAL_ME = 0x830;
    /** Not allowed to operate either GPRS or non-GPRS services. */
    public static final int GPRS_SERVICES_AND_NON_GPRS_SERVICES_NOT_ALLOWED = 0x831;
    /** MS is not allowed to operate GPRS services. */
    public static final int GPRS_SERVICES_NOT_ALLOWED = 0x832;
    /** No matching identity or context could be found in the network. */
    public static final int MS_IDENTITY_CANNOT_BE_DERIVED_BY_THE_NETWORK = 0x833;
    /**
     * Mobile reachable timer has expired, or the GMM context data related to the subscription does
     * not exist in the SGSN.
     */
    public static final int IMPLICITLY_DETACHED = 0x834;
    /**
     * UE requests GPRS service, or the network initiates a detach request in a PLMN which does not
     * offer roaming for GPRS services to that MS.
     */
    public static final int PLMN_NOT_ALLOWED = 0x835;
    /**
     * MS requests service, or the network initiates a detach request, in a location area where the
     * HPLMN determines that the MS, by subscription, is not allowed to operate.
     */
    public static final int LOCATION_AREA_NOT_ALLOWED = 0x836;
    /**
     * UE requests GPRS service or the network initiates a detach request in a PLMN that does not
     * offer roaming for GPRS services.
     */
    public static final int GPRS_SERVICES_NOT_ALLOWED_IN_THIS_PLMN = 0x837;
    /** PDP context already exists. */
    public static final int PDP_DUPLICATE = 0x838;
    /** RAT change on the UE. */
    public static final int UE_RAT_CHANGE = 0x839;
    /** Network cannot serve a request from the MS due to congestion. */
    public static final int CONGESTION = 0x83A;
    /**
     * MS requests an establishment of the radio access bearers for all active PDP contexts by
     * sending a service request message indicating data to the network, but the SGSN does not have
     * any active PDP context.
     */
    public static final int NO_PDP_CONTEXT_ACTIVATED = 0x83B;
    /** Access class blocking restrictions for the current camped cell. */
    public static final int ACCESS_CLASS_DSAC_REJECTION = 0x83C;
    /** SM attempts PDP activation for a maximum of four attempts. */
    public static final int PDP_ACTIVATE_MAX_RETRY_FAILED = 0x83D;
    /** Radio access bearer failure. */
    public static final int RADIO_ACCESS_BEARER_FAILURE = 0x83E;
    /** Invalid EPS bearer identity in the request. */
    public static final int ESM_UNKNOWN_EPS_BEARER_CONTEXT = 0x83F;
    /** Data radio bearer is released by the RRC. */
    public static final int DRB_RELEASED_BY_RRC = 0x840;
    /** Indicate the connection was released. */
    public static final int CONNECTION_RELEASED = 0x841;
    /** UE is detached. */
    public static final int EMM_DETACHED = 0x842;
    /** Attach procedure is rejected by the network. */
    public static final int EMM_ATTACH_FAILED = 0x843;
    /** Attach procedure is started for EMC purposes. */
    public static final int EMM_ATTACH_STARTED = 0x844;
    /** Service request procedure failure. */
    public static final int LTE_NAS_SERVICE_REQUEST_FAILED = 0x845;
    /** Active dedicated bearer was requested using the same default bearer ID. */
    public static final int DUPLICATE_BEARER_ID = 0x846;
    /** Collision scenarios for the UE and network-initiated procedures. */
    public static final int ESM_COLLISION_SCENARIOS = 0x847;
    /** Bearer must be deactivated to synchronize with the network. */
    public static final int ESM_BEARER_DEACTIVATED_TO_SYNC_WITH_NETWORK = 0x848;
    /** Active dedicated bearer was requested for an existing default bearer. */
    public static final int ESM_NW_ACTIVATED_DED_BEARER_WITH_ID_OF_DEF_BEARER = 0x849;
    /** Bad OTA message is received from the network. */
    public static final int ESM_BAD_OTA_MESSAGE = 0x84A;
    /** Download server rejected the call. */
    public static final int ESM_DOWNLOAD_SERVER_REJECTED_THE_CALL = 0x84B;
    /** PDN was disconnected by the downlaod server due to IRAT. */
    public static final int ESM_CONTEXT_TRANSFERRED_DUE_TO_IRAT = 0x84C;
    /** Dedicated bearer will be deactivated regardless of the network response. */
    public static final int DS_EXPLICIT_DEACTIVATION = 0x84D;
    /** No specific local cause is mentioned, usually a valid OTA cause. */
    public static final int ESM_LOCAL_CAUSE_NONE = 0x84E;
    /** Throttling is not needed for this service request failure. */
    public static final int LTE_THROTTLING_NOT_REQUIRED = 0x84F;
    /** Access control list check failure at the lower layer. */
    public static final int ACCESS_CONTROL_LIST_CHECK_FAILURE = 0x850;
    /** Service is not allowed on the requested PLMN. */
    public static final int SERVICE_NOT_ALLOWED_ON_PLMN = 0x851;
    /** T3417 timer expiration of the service request procedure. */
    public static final int EMM_T3417_EXPIRED = 0x852;
    /** Extended service request fails due to expiration of the T3417 EXT timer. */
    public static final int EMM_T3417_EXT_EXPIRED = 0x853;
    /** Transmission failure of radio resource control (RRC) uplink data. */
    public static final int RRC_UPLINK_DATA_TRANSMISSION_FAILURE = 0x854;
    /** Radio resource control (RRC) uplink data delivery failed due to a handover. */
    public static final int RRC_UPLINK_DELIVERY_FAILED_DUE_TO_HANDOVER = 0x855;
    /** Radio resource control (RRC) uplink data delivery failed due to a connection release. */
    public static final int RRC_UPLINK_CONNECTION_RELEASE = 0x856;
    /** Radio resource control (RRC) uplink data delivery failed due to a radio link failure. */
    public static final int RRC_UPLINK_RADIO_LINK_FAILURE = 0x857;
    /**
     * Radio resource control (RRC) is not connected but the non-access stratum (NAS) sends an
     * uplink data request.
     */
    public static final int RRC_UPLINK_ERROR_REQUEST_FROM_NAS = 0x858;
    /** Radio resource control (RRC) connection failure at access stratum. */
    public static final int RRC_CONNECTION_ACCESS_STRATUM_FAILURE = 0x859;
    /**
     * Radio resource control (RRC) connection establishment is aborted due to another procedure.
     */
    public static final int RRC_CONNECTION_ANOTHER_PROCEDURE_IN_PROGRESS = 0x85A;
    /** Radio resource control (RRC) connection establishment failed due to access barrred. */
    public static final int RRC_CONNECTION_ACCESS_BARRED = 0x85B;
    /**
     * Radio resource control (RRC) connection establishment failed due to cell reselection at
     * access stratum.
     */
    public static final int RRC_CONNECTION_CELL_RESELECTION = 0x85C;
    /**
     * Connection establishment failed due to configuration failure at the radio resource control
     * (RRC).
     */
    public static final int RRC_CONNECTION_CONFIG_FAILURE = 0x85D;
    /** Radio resource control (RRC) connection could not be established in the time limit. */
    public static final int RRC_CONNECTION_TIMER_EXPIRED = 0x85E;
    /**
     * Connection establishment failed due to a link failure at the radio resource control (RRC).
     */
    public static final int RRC_CONNECTION_LINK_FAILURE = 0x85F;
    /**
     * Connection establishment failed as the radio resource control (RRC) is not camped on any
     * cell.
     */
    public static final int RRC_CONNECTION_CELL_NOT_CAMPED = 0x860;
    /**
     * Connection establishment failed due to a service interval failure at the radio resource
     * control (RRC).
     */
    public static final int RRC_CONNECTION_SYSTEM_INTERVAL_FAILURE = 0x861;
    /**
     * Radio resource control (RRC) connection establishment failed due to the network rejecting
     * the UE connection request.
     */
    public static final int RRC_CONNECTION_REJECT_BY_NETWORK = 0x862;
    /** Normal radio resource control (RRC) connection release. */
    public static final int RRC_CONNECTION_NORMAL_RELEASE = 0x863;
    /**
     * Radio resource control (RRC) connection release failed due to radio link failure conditions.
     */
    public static final int RRC_CONNECTION_RADIO_LINK_FAILURE = 0x864;
    /** Radio resource control (RRC) connection re-establishment failure. */
    public static final int RRC_CONNECTION_REESTABLISHMENT_FAILURE = 0x865;
    /** UE is out of service during the call register. */
    public static final int RRC_CONNECTION_OUT_OF_SERVICE_DURING_CELL_REGISTER = 0x866;
    /**
     * Connection has been released by the radio resource control (RRC) due to an abort request.
     */
    public static final int RRC_CONNECTION_ABORT_REQUEST = 0x867;
    /**
     * Radio resource control (RRC) connection released due to a system information block read
     * error.
     */
    public static final int RRC_CONNECTION_SYSTEM_INFORMATION_BLOCK_READ_ERROR = 0x868;
    /** Network-initiated detach with reattach. */
    public static final int NETWORK_INITIATED_DETACH_WITH_AUTO_REATTACH = 0x869;
    /** Network-initiated detach without reattach. */
    public static final int NETWORK_INITIATED_DETACH_NO_AUTO_REATTACH = 0x86A;
    /** ESM procedure maximum attempt timeout failure. */
    public static final int ESM_PROCEDURE_TIME_OUT = 0x86B;
    /**
     * No PDP exists with the given connection ID while modifying or deactivating or activation for
     * an already active PDP.
     */
    public static final int INVALID_CONNECTION_ID = 0x86C;
    /** Maximum NSAPIs have been exceeded during PDP activation. */
    public static final int MAXIMIUM_NSAPIS_EXCEEDED = 0x86D;
    /** Primary context for NSAPI does not exist. */
    public static final int INVALID_PRIMARY_NSAPI = 0x86E;
    /** Unable to encode the OTA message for MT PDP or deactivate PDP. */
    public static final int CANNOT_ENCODE_OTA_MESSAGE = 0x86F;
    /**
     * Radio access bearer is not established by the lower layers during activation, modification,
     * or deactivation.
     */
    public static final int RADIO_ACCESS_BEARER_SETUP_FAILURE = 0x870;
    /** Expiration of the PDP establish timer with a maximum of five retries. */
    public static final int PDP_ESTABLISH_TIMEOUT_EXPIRED = 0x871;
    /** Expiration of the PDP modify timer with a maximum of four retries. */
    public static final int PDP_MODIFY_TIMEOUT_EXPIRED = 0x872;
    /** Expiration of the PDP deactivate timer with a maximum of four retries. */
    public static final int PDP_INACTIVE_TIMEOUT_EXPIRED = 0x873;
    /** PDP activation failed due to RRC_ABORT or a forbidden PLMN. */
    public static final int PDP_LOWERLAYER_ERROR = 0x874;
    /** MO PDP modify collision when the MT PDP is already in progress. */
    public static final int PDP_MODIFY_COLLISION = 0x875;
    /** Maximum size of the L2 message was exceeded. */
    public static final int MAXINUM_SIZE_OF_L2_MESSAGE_EXCEEDED = 0x876;
    /** Non-access stratum (NAS) request was rejected by the network. */
    public static final int NAS_REQUEST_REJECTED_BY_NETWORK = 0x877;
    /**
     * Radio resource control (RRC) connection establishment failure due to an error in the request
     * message.
     */
    public static final int RRC_CONNECTION_INVALID_REQUEST = 0x878;
    /**
     * Radio resource control (RRC) connection establishment failure due to a change in the
     * tracking area ID.
     */
    public static final int RRC_CONNECTION_TRACKING_AREA_ID_CHANGED = 0x879;
    /**
     * Radio resource control (RRC) connection establishment failure due to the RF was unavailable.
     */
    public static final int RRC_CONNECTION_RF_UNAVAILABLE = 0x87A;
    /**
     * Radio resource control (RRC) connection was aborted before deactivating the LTE stack due to
     * a successful LTE to WCDMA/GSM/TD-SCDMA IRAT change.
     */
    public static final int RRC_CONNECTION_ABORTED_DUE_TO_IRAT_CHANGE = 0x87B;
    /**
     * If the UE has an LTE radio link failure before security is established, the radio resource
     * control (RRC) connection must be released and the UE must return to idle.
     */
    public static final int RRC_CONNECTION_RELEASED_SECURITY_NOT_ACTIVE = 0x87C;
    /**
     * Radio resource control (RRC) connection was aborted by the non-access stratum (NAS) after an
     * IRAT to LTE IRAT handover.
     */
    public static final int RRC_CONNECTION_ABORTED_AFTER_HANDOVER = 0x87D;
    /**
     * Radio resource control (RRC) connection was aborted before deactivating the LTE stack after
     * a successful LTE to GSM/EDGE IRAT cell change order procedure.
     */
    public static final int RRC_CONNECTION_ABORTED_AFTER_IRAT_CELL_CHANGE = 0x87E;
    /**
     * Radio resource control (RRC) connection was aborted in the middle of a LTE to GSM IRAT cell
     * change order procedure.
     */
    public static final int RRC_CONNECTION_ABORTED_DURING_IRAT_CELL_CHANGE = 0x87F;
    /** IMSI present in the UE is unknown in the home subscriber server. */
    public static final int IMSI_UNKNOWN_IN_HOME_SUBSCRIBER_SERVER = 0x880;
    /** IMEI of the UE is not accepted by the network. */
    public static final int IMEI_NOT_ACCEPTED = 0x881;
    /** EPS and non-EPS services are not allowed by the network. */
    public static final int EPS_SERVICES_AND_NON_EPS_SERVICES_NOT_ALLOWED = 0x882;
    /** EPS services are not allowed in the PLMN. */
    public static final int EPS_SERVICES_NOT_ALLOWED_IN_PLMN = 0x883;
    /** Mobile switching center is temporarily unreachable. */
    public static final int MSC_TEMPORARILY_NOT_REACHABLE = 0x884;
    /** CS domain is not available. */
    public static final int CS_DOMAIN_NOT_AVAILABLE = 0x885;
    /** ESM level failure. */
    public static final int ESM_FAILURE = 0x886;
    /** MAC level failure. */
    public static final int MAC_FAILURE = 0x887;
    /** Synchronization failure. */
    public static final int SYNCHRONIZATION_FAILURE = 0x888;
    /** UE security capabilities mismatch. */
    public static final int UE_SECURITY_CAPABILITIES_MISMATCH = 0x889;
    /** Unspecified security mode reject. */
    public static final int SECURITY_MODE_REJECTED = 0x88A;
    /** Unacceptable non-EPS authentication. */
    public static final int UNACCEPTABLE_NON_EPS_AUTHENTICATION = 0x88B;
    /** CS fallback call establishment is not allowed. */
    public static final int CS_FALLBACK_CALL_ESTABLISHMENT_NOT_ALLOWED = 0x88C;
    /** No EPS bearer context was activated. */
    public static final int NO_EPS_BEARER_CONTEXT_ACTIVATED = 0x88D;
    /** Invalid EMM state. */
    public static final int INVALID_EMM_STATE = 0x88E;
    /** Non-Access Spectrum layer failure. */
    public static final int NAS_LAYER_FAILURE = 0x88F;
    /** Multiple PDP call feature is disabled. */
    public static final int MULTIPLE_PDP_CALL_NOT_ALLOWED = 0x890;
    /** Data call has been brought down because EMBMS is not enabled at the RRC layer. */
    public static final int EMBMS_NOT_ENABLED = 0x891;
    /** Data call was unsuccessfully transferred during the IRAT handover. */
    public static final int IRAT_HANDOVER_FAILED = 0x892;
    /** EMBMS data call has been successfully brought down. */
    public static final int EMBMS_REGULAR_DEACTIVATION = 0x893;
    /** Test loop-back data call has been successfully brought down. */
    public static final int TEST_LOOPBACK_REGULAR_DEACTIVATION = 0x894;
    /** Lower layer registration failure. */
    public static final int LOWER_LAYER_REGISTRATION_FAILURE = 0x895;
    /**
     * Network initiates a detach on LTE with error cause ""data plan has been replenished or has
     * expired.
     */
    public static final int DATA_PLAN_EXPIRED = 0x896;
    /** UMTS interface is brought down due to handover from UMTS to iWLAN. */
    public static final int UMTS_HANDOVER_TO_IWLAN = 0x897;
    /** Received a connection deny due to general or network busy on EVDO network. */
    public static final int EVDO_CONNECTION_DENY_BY_GENERAL_OR_NETWORK_BUSY = 0x898;
    /** Received a connection deny due to billing or authentication failure on EVDO network. */
    public static final int EVDO_CONNECTION_DENY_BY_BILLING_OR_AUTHENTICATION_FAILURE = 0x899;
    /** HDR system has been changed due to redirection or the PRL was not preferred. */
    public static final int EVDO_HDR_CHANGED = 0x89A;
    /** Device exited HDR due to redirection or the PRL was not preferred. */
    public static final int EVDO_HDR_EXITED = 0x89B;
    /** Device does not have an HDR session. */
    public static final int EVDO_HDR_NO_SESSION = 0x89C;
    /** It is ending an HDR call origination in favor of a GPS fix. */
    public static final int EVDO_USING_GPS_FIX_INSTEAD_OF_HDR_CALL = 0x89D;
    /** Connection setup on the HDR system was time out. */
    public static final int EVDO_HDR_CONNECTION_SETUP_TIMEOUT = 0x89E;
    /** Device failed to acquire a co-located HDR for origination. */
    public static final int FAILED_TO_ACQUIRE_COLOCATED_HDR = 0x89F;
    /** OTASP commit is in progress. */
    public static final int OTASP_COMMIT_IN_PROGRESS = 0x8A0;
    /** Device has no hybrid HDR service. */
    public static final int NO_HYBRID_HDR_SERVICE = 0x8A1;
    /** HDR module could not be obtained because of the RF locked. */
    public static final int HDR_NO_LOCK_GRANTED = 0x8A2;
    /** DBM or SMS is in progress. */
    public static final int DBM_OR_SMS_IN_PROGRESS = 0x8A3;
    /** HDR module released the call due to fade. */
    public static final int HDR_FADE = 0x8A4;
    /** HDR system access failure. */
    public static final int HDR_ACCESS_FAILURE = 0x8A5;
    /**
     * P_rev supported by 1 base station is less than 6, which is not supported for a 1X data call.
     * The UE must be in the footprint of BS which has p_rev >= 6 to support this SO33 call.
     */
    public static final int UNSUPPORTED_1X_PREV = 0x8A6;
    /** Client ended the data call. */
    public static final int LOCAL_END = 0x8A7;
    /** Device has no service. */
    public static final int NO_SERVICE = 0x8A8;
    /** Device lost the system due to fade. */
    public static final int FADE = 0x8A9;
    /** Receiving a release from the base station with no reason. */
    public static final int NORMAL_RELEASE = 0x8AA;
    /** Access attempt is already in progress. */
    public static final int ACCESS_ATTEMPT_ALREADY_IN_PROGRESS = 0x8AB;
    /** Device is in the process of redirecting or handing off to a different target system. */
    public static final int REDIRECTION_OR_HANDOFF_IN_PROGRESS = 0x8AC;
    /** Device is operating in Emergency mode. */
    public static final int EMERGENCY_MODE = 0x8AD;
    /** Device is in use (e.g., voice call). */
    public static final int PHONE_IN_USE = 0x8AE;
    /**
     * Device operational mode is different from the mode requested in the traffic channel bring up.
     */
    public static final int INVALID_MODE = 0x8AF;
    /** SIM was marked by the network as invalid for the circuit and/or packet service domain. */
    public static final int INVALID_SIM_STATE = 0x8B0;
    /** There is no co-located HDR. */
    public static final int NO_COLLOCATED_HDR = 0x8B1;
    /** UE is entering power save mode. */
    public static final int UE_IS_ENTERING_POWERSAVE_MODE = 0x8B2;
    /** Dual switch from single standby to dual standby is in progress. */
    public static final int DUAL_SWITCH = 0x8B3;
    /**
     * Data call bring up fails in the PPP setup due to a timeout.
     * (e.g., an LCP conf ack was not received from the network)
     */
    public static final int PPP_TIMEOUT = 0x8B4;
    /**
     * Data call bring up fails in the PPP setup due to an authorization failure.
     * (e.g., authorization is required, but not negotiated with the network during an LCP phase)
     */
    public static final int PPP_AUTH_FAILURE = 0x8B5;
    /** Data call bring up fails in the PPP setup due to an option mismatch. */
    public static final int PPP_OPTION_MISMATCH = 0x8B6;
    /** Data call bring up fails in the PPP setup due to a PAP failure. */
    public static final int PPP_PAP_FAILURE = 0x8B7;
    /** Data call bring up fails in the PPP setup due to a CHAP failure. */
    public static final int PPP_CHAP_FAILURE = 0x8B8;
    /**
     * Data call bring up fails in the PPP setup because the PPP is in the process of cleaning the
     * previous PPP session.
     */
    public static final int PPP_CLOSE_IN_PROGRESS = 0x8B9;
    /**
     * IPv6 interface bring up fails because the network provided only the IPv4 address for the
     * upcoming PDN permanent client can reattempt a IPv6 call bring up after the IPv4 interface is
     * also brought down. However, there is no guarantee that the network will provide a IPv6
     * address.
     */
    public static final int LIMITED_TO_IPV4 = 0x8BA;
    /**
     * IPv4 interface bring up fails because the network provided only the IPv6 address for the
     * upcoming PDN permanent client can reattempt a IPv4 call bring up after the IPv6 interface is
     * also brought down. However there is no guarantee that the network will provide a IPv4
     * address.
     */
    public static final int LIMITED_TO_IPV6 = 0x8BB;
    /** Data call bring up fails in the VSNCP phase due to a VSNCP timeout error. */
    public static final int VSNCP_TIMEOUT = 0x8BC;
    /**
     * Data call bring up fails in the VSNCP phase due to a general error. It's used when there is
     * no other specific error code available to report the failure.
     */
    public static final int VSNCP_GEN_ERROR = 0x8BD;
    /**
     * Data call bring up fails in the VSNCP phase due to a network rejection of the VSNCP
     * configuration request because the requested APN is unauthorized.
     *
     * @deprecated Use {@link #VSNCP_APN_UNAUTHORIZED} instead.
     *
     * @hide
     */
    @SystemApi
    @Deprecated
    public static final int VSNCP_APN_UNATHORIZED = 0x8BE; // NOTYPO
    /**
     * Data call bring up fails in the VSNCP phase due to a network rejection of the VSNCP
     * configuration request because the requested APN is unauthorized.
     */
    public static final int VSNCP_APN_UNAUTHORIZED = 0x8BE;
    /**
     * Data call bring up fails in the VSNCP phase due to a network rejection of the VSNCP
     * configuration request because the PDN limit has been exceeded.
     */
    public static final int VSNCP_PDN_LIMIT_EXCEEDED = 0x8BF;
    /**
     * Data call bring up fails in the VSNCP phase due to the network rejected the VSNCP
     * configuration request due to no PDN gateway address.
     */
    public static final int VSNCP_NO_PDN_GATEWAY_ADDRESS = 0x8C0;
    /**
     * Data call bring up fails in the VSNCP phase due to a network rejection of the VSNCP
     * configuration request because the PDN gateway is unreachable.
     */
    public static final int VSNCP_PDN_GATEWAY_UNREACHABLE = 0x8C1;
    /**
     * Data call bring up fails in the VSNCP phase due to a network rejection of the VSNCP
     * configuration request due to a PDN gateway reject.
     */
    public static final int VSNCP_PDN_GATEWAY_REJECT = 0x8C2;
    /**
     * Data call bring up fails in the VSNCP phase due to a network rejection of the VSNCP
     * configuration request with the reason of insufficient parameter.
     */
    public static final int VSNCP_INSUFFICIENT_PARAMETERS = 0x8C3;
    /**
     * Data call bring up fails in the VSNCP phase due to a network rejection of the VSNCP
     * configuration request with the reason of resource unavailable.
     */
    public static final int VSNCP_RESOURCE_UNAVAILABLE = 0x8C4;
    /**
     * Data call bring up fails in the VSNCP phase due to a network rejection of the VSNCP
     * configuration request with the reason of administratively prohibited at the HSGW.
     */
    public static final int VSNCP_ADMINISTRATIVELY_PROHIBITED = 0x8C5;
    /**
     * Data call bring up fails in the VSNCP phase due to a network rejection of PDN ID in use, or
     * all existing PDNs are brought down with this end reason because one of the PDN bring up was
     * rejected by the network with the reason of PDN ID in use.
     */
    public static final int VSNCP_PDN_ID_IN_USE = 0x8C6;
    /**
     * Data call bring up fails in the VSNCP phase due to a network rejection of the VSNCP
     * configuration request for the reason of subscriber limitation.
     */
    public static final int VSNCP_SUBSCRIBER_LIMITATION = 0x8C7;
    /**
     * Data call bring up fails in the VSNCP phase due to a network rejection of the VSNCP
     * configuration request because the PDN exists for this APN.
     */
    public static final int VSNCP_PDN_EXISTS_FOR_THIS_APN = 0x8C8;
    /**
     * Data call bring up fails in the VSNCP phase due to a network rejection of the VSNCP
     * configuration request with reconnect to this PDN not allowed, or an active data call is
     * terminated by the network because reconnection to this PDN is not allowed. Upon receiving
     * this error code from the network, the modem infinitely throttles the PDN until the next
     * power cycle.
     */
    public static final int VSNCP_RECONNECT_NOT_ALLOWED = 0x8C9;
    /** Device failure to obtain the prefix from the network. */
    public static final int IPV6_PREFIX_UNAVAILABLE = 0x8CA;
    /** System preference change back to SRAT during handoff */
    public static final int HANDOFF_PREFERENCE_CHANGED = 0x8CB;
    /** Data call fail due to the slice not being allowed for the data call. */
    public static final int SLICE_REJECTED = 0x8CC;
    /** No matching rule available for the request, and match-all rule is not allowed for it. */
    public static final int MATCH_ALL_RULE_NOT_ALLOWED = 0x8CD;
    /** If connection failed for all matching URSP rules. */
    public static final int ALL_MATCHING_RULES_FAILED = 0x8CE;

    //IKE error notifications message as specified in 3GPP TS 24.302 (Section 8.1.2.2).

    /** The PDN connection corresponding to the requested APN has been rejected. */
    public static final int IWLAN_PDN_CONNECTION_REJECTION = 0x2000;
    /**
     * The PDN connection has been rejected. No additional PDN connections can be established
     * for the UE due to the network policies or capabilities.
     */
    public static final int IWLAN_MAX_CONNECTION_REACHED = 0x2001;
    /**
     * The PDN connection has been rejected due to a semantic error in TFT operation.
     */
    public static final int IWLAN_SEMANTIC_ERROR_IN_THE_TFT_OPERATION = 0x2031;
    /**
     * The PDN connection has been rejected due to a syntactic error in TFT operation.
     */
    public static final int IWLAN_SYNTACTICAL_ERROR_IN_THE_TFT_OPERATION = 0x2032;
    /**
     * The PDN connection has been rejected due to sematic errors in the packet filter.
     */
    public static final int IWLAN_SEMANTIC_ERRORS_IN_PACKET_FILTERS = 0x2034;
    /**
     * The PDN connection has been rejected due to syntactic errors in the packet filter.
     */
    public static final int IWLAN_SYNTACTICAL_ERRORS_IN_PACKET_FILTERS = 0x2035;
    /**
     * No non-3GPP subscription is associated with the IMSI.
     * The UE is not allowed to use non-3GPP access to EPC.
     */
    public static final int IWLAN_NON_3GPP_ACCESS_TO_EPC_NOT_ALLOWED = 0x2328;
    /** The user identified by the IMSI is unknown. */
    public static final int IWLAN_USER_UNKNOWN = 0x2329;
    /**
     * The requested APN is not included in the user's profile,
     * and therefore is not authorized for that user.
     */
    public static final int IWLAN_NO_APN_SUBSCRIPTION = 0x232A;
    /** The user is barred from using the non-3GPP access or the subscribed APN. */
    public static final int IWLAN_AUTHORIZATION_REJECTED = 0x232B;
    /** The Mobile Equipment used is not acceptable to the network */
    public static final int IWLAN_ILLEGAL_ME = 0x232E;
    /**
     * The network has determined that the requested procedure cannot be completed successfully
     * due to network failure.
     */
    public static final int IWLAN_NETWORK_FAILURE = 0x2904;
    /** The access type is restricted to the user. */
    public static final int IWLAN_RAT_TYPE_NOT_ALLOWED = 0x2AF9;
    /** The network does not accept emergency PDN bringup request using an IMEI */
    public static final int IWLAN_IMEI_NOT_ACCEPTED = 0x2AFD;
    /**
     * The ePDG performs PLMN filtering (based on roaming agreements) and rejects
     * the request from the UE.
     * The UE requests service in a PLMN where the UE is not allowed to operate.
     */
    public static final int IWLAN_PLMN_NOT_ALLOWED = 0x2B03;
    /** The ePDG does not support un-authenticated IMSI based emergency PDN bringup **/
    public static final int IWLAN_UNAUTHENTICATED_EMERGENCY_NOT_SUPPORTED = 0x2B2F;

    // Device is unable to establish an IPSec tunnel with the ePDG for any reason
    // e.g authentication fail or certificate validation or DNS Resolution and timeout failure.

    /** IKE configuration error resulting in failure  */
    public static final int IWLAN_IKEV2_CONFIG_FAILURE = 0x4000;
    /**
     * Sent in the response to an IKE_AUTH message when, for some reason,
     * the authentication failed.
     */
    public static final int IWLAN_IKEV2_AUTH_FAILURE = 0x4001;
    /** IKE message timeout, tunnel setup failed due to no response from EPDG */
    public static final int IWLAN_IKEV2_MSG_TIMEOUT = 0x4002;
    /** IKE Certification validation failure  */
    public static final int IWLAN_IKEV2_CERT_INVALID = 0x4003;
    /** Unable to resolve FQDN for the ePDG to an IP address */
    public static final int IWLAN_DNS_RESOLUTION_NAME_FAILURE = 0x4004;
    /** No response received from the DNS Server due to a timeout*/
    public static final int IWLAN_DNS_RESOLUTION_TIMEOUT = 0x4005;

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
    /** Data fail due to unacceptable network parameter. */
    public static final int UNACCEPTABLE_NETWORK_PARAMETER = 0x10002;        /* no retry */
    /** Data connection was lost. */
    public static final int LOST_CONNECTION = 0x10004;

    /**
     * Data handover failed.
     *
     * @hide
     */
    public static final int HANDOVER_FAILED = 0x10006;

    /**
     * Enterprise setup failure: duplicate CID in DataCallResponse.
     *
     * @hide
     */
    public static final int DUPLICATE_CID = 0x10007;

    /**
     * Enterprise setup failure: no default data connection set up yet.
     *
     * @hide
     */
    public static final int NO_DEFAULT_DATA = 0x10008;

    /**
     * Data service is temporarily unavailable.
     *
     * @hide
     */
    public static final int SERVICE_TEMPORARILY_UNAVAILABLE = 0x10009;

    /**
     * The request is not supported by the vendor.
     *
     * @hide
     */
    public static final int REQUEST_NOT_SUPPORTED = 0x1000A;

    /**
     * An internal setup data error initiated by telephony that no retry should be performed.
     *
     * @hide
     */
    public static final int NO_RETRY_FAILURE = 0x1000B;

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
        sFailCauseMap.put(ACTIVATION_REJECTED_BCM_VIOLATION, "ACTIVATION_REJECTED_BCM_VIOLATION");
        sFailCauseMap.put(ONLY_IPV4_ALLOWED, "ONLY_IPV4_ALLOWED");
        sFailCauseMap.put(ONLY_IPV6_ALLOWED, "ONLY_IPV6_ALLOWED");
        sFailCauseMap.put(ONLY_SINGLE_BEARER_ALLOWED, "ONLY_SINGLE_BEARER_ALLOWED");
        sFailCauseMap.put(ESM_INFO_NOT_RECEIVED, "ESM_INFO_NOT_RECEIVED");
        sFailCauseMap.put(PDN_CONN_DOES_NOT_EXIST, "PDN_CONN_DOES_NOT_EXIST");
        sFailCauseMap.put(MULTI_CONN_TO_SAME_PDN_NOT_ALLOWED,
                "MULTI_CONN_TO_SAME_PDN_NOT_ALLOWED");
        sFailCauseMap.put(COLLISION_WITH_NETWORK_INITIATED_REQUEST,
                "COLLISION_WITH_NETWORK_INITIATED_REQUEST");
        sFailCauseMap.put(ONLY_IPV4V6_ALLOWED, "ONLY_IPV4V6_ALLOWED");
        sFailCauseMap.put(ONLY_NON_IP_ALLOWED, "ONLY_NON_IP_ALLOWED");
        sFailCauseMap.put(UNSUPPORTED_QCI_VALUE, "UNSUPPORTED_QCI_VALUE");
        sFailCauseMap.put(BEARER_HANDLING_NOT_SUPPORTED, "BEARER_HANDLING_NOT_SUPPORTED");
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
        sFailCauseMap.put(INVALID_DNS_ADDR, "INVALID_DNS_ADDR");
        sFailCauseMap.put(INVALID_PCSCF_OR_DNS_ADDRESS, "INVALID_PCSCF_OR_DNS_ADDRESS");
        sFailCauseMap.put(CALL_PREEMPT_BY_EMERGENCY_APN, "CALL_PREEMPT_BY_EMERGENCY_APN");
        sFailCauseMap.put(UE_INITIATED_DETACH_OR_DISCONNECT, "UE_INITIATED_DETACH_OR_DISCONNECT");
        sFailCauseMap.put(MIP_FA_REASON_UNSPECIFIED, "MIP_FA_REASON_UNSPECIFIED");
        sFailCauseMap.put(MIP_FA_ADMIN_PROHIBITED, "MIP_FA_ADMIN_PROHIBITED");
        sFailCauseMap.put(MIP_FA_INSUFFICIENT_RESOURCES, "MIP_FA_INSUFFICIENT_RESOURCES");
        sFailCauseMap.put(MIP_FA_MOBILE_NODE_AUTHENTICATION_FAILURE,
                "MIP_FA_MOBILE_NODE_AUTHENTICATION_FAILURE");
        sFailCauseMap.put(MIP_FA_HOME_AGENT_AUTHENTICATION_FAILURE,
                "MIP_FA_HOME_AGENT_AUTHENTICATION_FAILURE");
        sFailCauseMap.put(MIP_FA_REQUESTED_LIFETIME_TOO_LONG, "MIP_FA_REQUESTED_LIFETIME_TOO_LONG");
        sFailCauseMap.put(MIP_FA_MALFORMED_REQUEST, "MIP_FA_MALFORMED_REQUEST");
        sFailCauseMap.put(MIP_FA_MALFORMED_REPLY, "MIP_FA_MALFORMED_REPLY");
        sFailCauseMap.put(MIP_FA_ENCAPSULATION_UNAVAILABLE, "MIP_FA_ENCAPSULATION_UNAVAILABLE");
        sFailCauseMap.put(MIP_FA_VJ_HEADER_COMPRESSION_UNAVAILABLE,
                "MIP_FA_VJ_HEADER_COMPRESSION_UNAVAILABLE");
        sFailCauseMap.put(MIP_FA_REVERSE_TUNNEL_UNAVAILABLE, "MIP_FA_REVERSE_TUNNEL_UNAVAILABLE");
        sFailCauseMap.put(MIP_FA_REVERSE_TUNNEL_IS_MANDATORY, "MIP_FA_REVERSE_TUNNEL_IS_MANDATORY");
        sFailCauseMap.put(MIP_FA_DELIVERY_STYLE_NOT_SUPPORTED,
                "MIP_FA_DELIVERY_STYLE_NOT_SUPPORTED");
        sFailCauseMap.put(MIP_FA_MISSING_NAI, "MIP_FA_MISSING_NAI");
        sFailCauseMap.put(MIP_FA_MISSING_HOME_AGENT, "MIP_FA_MISSING_HOME_AGENT");
        sFailCauseMap.put(MIP_FA_MISSING_HOME_ADDRESS, "MIP_FA_MISSING_HOME_ADDRESS");
        sFailCauseMap.put(MIP_FA_UNKNOWN_CHALLENGE, "MIP_FA_UNKNOWN_CHALLENGE");
        sFailCauseMap.put(MIP_FA_MISSING_CHALLENGE, "MIP_FA_MISSING_CHALLENGE");
        sFailCauseMap.put(MIP_FA_STALE_CHALLENGE, "MIP_FA_STALE_CHALLENGE");
        sFailCauseMap.put(MIP_HA_REASON_UNSPECIFIED, "MIP_HA_REASON_UNSPECIFIED");
        sFailCauseMap.put(MIP_HA_ADMIN_PROHIBITED, "MIP_HA_ADMIN_PROHIBITED");
        sFailCauseMap.put(MIP_HA_INSUFFICIENT_RESOURCES, "MIP_HA_INSUFFICIENT_RESOURCES");
        sFailCauseMap.put(MIP_HA_MOBILE_NODE_AUTHENTICATION_FAILURE,
                "MIP_HA_MOBILE_NODE_AUTHENTICATION_FAILURE");
        sFailCauseMap.put(MIP_HA_FOREIGN_AGENT_AUTHENTICATION_FAILURE,
                "MIP_HA_FOREIGN_AGENT_AUTHENTICATION_FAILURE");
        sFailCauseMap.put(MIP_HA_REGISTRATION_ID_MISMATCH, "MIP_HA_REGISTRATION_ID_MISMATCH");
        sFailCauseMap.put(MIP_HA_MALFORMED_REQUEST, "MIP_HA_MALFORMED_REQUEST");
        sFailCauseMap.put(MIP_HA_UNKNOWN_HOME_AGENT_ADDRESS, "MIP_HA_UNKNOWN_HOME_AGENT_ADDRESS");
        sFailCauseMap.put(MIP_HA_REVERSE_TUNNEL_UNAVAILABLE, "MIP_HA_REVERSE_TUNNEL_UNAVAILABLE");
        sFailCauseMap.put(MIP_HA_REVERSE_TUNNEL_IS_MANDATORY, "MIP_HA_REVERSE_TUNNEL_IS_MANDATORY");
        sFailCauseMap.put(MIP_HA_ENCAPSULATION_UNAVAILABLE, "MIP_HA_ENCAPSULATION_UNAVAILABLE");
        sFailCauseMap.put(CLOSE_IN_PROGRESS, "CLOSE_IN_PROGRESS");
        sFailCauseMap.put(NETWORK_INITIATED_TERMINATION, "NETWORK_INITIATED_TERMINATION");
        sFailCauseMap.put(MODEM_APP_PREEMPTED, "MODEM_APP_PREEMPTED");
        sFailCauseMap.put(PDN_IPV4_CALL_DISALLOWED, "PDN_IPV4_CALL_DISALLOWED");
        sFailCauseMap.put(PDN_IPV4_CALL_THROTTLED, "PDN_IPV4_CALL_THROTTLED");
        sFailCauseMap.put(PDN_IPV6_CALL_DISALLOWED, "PDN_IPV6_CALL_DISALLOWED");
        sFailCauseMap.put(PDN_IPV6_CALL_THROTTLED, "PDN_IPV6_CALL_THROTTLED");
        sFailCauseMap.put(MODEM_RESTART, "MODEM_RESTART");
        sFailCauseMap.put(PDP_PPP_NOT_SUPPORTED, "PDP_PPP_NOT_SUPPORTED");
        sFailCauseMap.put(UNPREFERRED_RAT, "UNPREFERRED_RAT");
        sFailCauseMap.put(PHYSICAL_LINK_CLOSE_IN_PROGRESS, "PHYSICAL_LINK_CLOSE_IN_PROGRESS");
        sFailCauseMap.put(APN_PENDING_HANDOVER, "APN_PENDING_HANDOVER");
        sFailCauseMap.put(PROFILE_BEARER_INCOMPATIBLE, "PROFILE_BEARER_INCOMPATIBLE");
        sFailCauseMap.put(SIM_CARD_CHANGED, "SIM_CARD_CHANGED");
        sFailCauseMap.put(LOW_POWER_MODE_OR_POWERING_DOWN, "LOW_POWER_MODE_OR_POWERING_DOWN");
        sFailCauseMap.put(APN_DISABLED, "APN_DISABLED");
        sFailCauseMap.put(MAX_PPP_INACTIVITY_TIMER_EXPIRED, "MAX_PPP_INACTIVITY_TIMER_EXPIRED");
        sFailCauseMap.put(IPV6_ADDRESS_TRANSFER_FAILED, "IPV6_ADDRESS_TRANSFER_FAILED");
        sFailCauseMap.put(TRAT_SWAP_FAILED, "TRAT_SWAP_FAILED");
        sFailCauseMap.put(EHRPD_TO_HRPD_FALLBACK, "EHRPD_TO_HRPD_FALLBACK");
        sFailCauseMap.put(MIP_CONFIG_FAILURE, "MIP_CONFIG_FAILURE");
        sFailCauseMap.put(PDN_INACTIVITY_TIMER_EXPIRED, "PDN_INACTIVITY_TIMER_EXPIRED");
        sFailCauseMap.put(MAX_IPV4_CONNECTIONS, "MAX_IPV4_CONNECTIONS");
        sFailCauseMap.put(MAX_IPV6_CONNECTIONS, "MAX_IPV6_CONNECTIONS");
        sFailCauseMap.put(APN_MISMATCH, "APN_MISMATCH");
        sFailCauseMap.put(IP_VERSION_MISMATCH, "IP_VERSION_MISMATCH");
        sFailCauseMap.put(DUN_CALL_DISALLOWED, "DUN_CALL_DISALLOWED");
        sFailCauseMap.put(INTERNAL_EPC_NONEPC_TRANSITION, "INTERNAL_EPC_NONEPC_TRANSITION");
        sFailCauseMap.put(INTERFACE_IN_USE, "INTERFACE_IN_USE");
        sFailCauseMap.put(APN_DISALLOWED_ON_ROAMING, "APN_DISALLOWED_ON_ROAMING");
        sFailCauseMap.put(APN_PARAMETERS_CHANGED, "APN_PARAMETERS_CHANGED");
        sFailCauseMap.put(NULL_APN_DISALLOWED, "NULL_APN_DISALLOWED");
        sFailCauseMap.put(THERMAL_MITIGATION, "THERMAL_MITIGATION");
        sFailCauseMap.put(DATA_SETTINGS_DISABLED, "DATA_SETTINGS_DISABLED");
        sFailCauseMap.put(DATA_ROAMING_SETTINGS_DISABLED, "DATA_ROAMING_SETTINGS_DISABLED");
        sFailCauseMap.put(DDS_SWITCHED, "DDS_SWITCHED");
        sFailCauseMap.put(FORBIDDEN_APN_NAME, "FORBIDDEN_APN_NAME");
        sFailCauseMap.put(DDS_SWITCH_IN_PROGRESS, "DDS_SWITCH_IN_PROGRESS");
        sFailCauseMap.put(CALL_DISALLOWED_IN_ROAMING, "CALL_DISALLOWED_IN_ROAMING");
        sFailCauseMap.put(NON_IP_NOT_SUPPORTED, "NON_IP_NOT_SUPPORTED");
        sFailCauseMap.put(PDN_NON_IP_CALL_THROTTLED, "PDN_NON_IP_CALL_THROTTLED");
        sFailCauseMap.put(PDN_NON_IP_CALL_DISALLOWED, "PDN_NON_IP_CALL_DISALLOWED");
        sFailCauseMap.put(CDMA_LOCK, "CDMA_LOCK");
        sFailCauseMap.put(CDMA_INTERCEPT, "CDMA_INTERCEPT");
        sFailCauseMap.put(CDMA_REORDER, "CDMA_REORDER");
        sFailCauseMap.put(CDMA_RELEASE_DUE_TO_SO_REJECTION, "CDMA_RELEASE_DUE_TO_SO_REJECTION");
        sFailCauseMap.put(CDMA_INCOMING_CALL, "CDMA_INCOMING_CALL");
        sFailCauseMap.put(CDMA_ALERT_STOP, "CDMA_ALERT_STOP");
        sFailCauseMap.put(CHANNEL_ACQUISITION_FAILURE, "CHANNEL_ACQUISITION_FAILURE");
        sFailCauseMap.put(MAX_ACCESS_PROBE, "MAX_ACCESS_PROBE");
        sFailCauseMap.put(CONCURRENT_SERVICE_NOT_SUPPORTED_BY_BASE_STATION,
                "CONCURRENT_SERVICE_NOT_SUPPORTED_BY_BASE_STATION");
        sFailCauseMap.put(NO_RESPONSE_FROM_BASE_STATION, "NO_RESPONSE_FROM_BASE_STATION");
        sFailCauseMap.put(REJECTED_BY_BASE_STATION, "REJECTED_BY_BASE_STATION");
        sFailCauseMap.put(CONCURRENT_SERVICES_INCOMPATIBLE, "CONCURRENT_SERVICES_INCOMPATIBLE");
        sFailCauseMap.put(NO_CDMA_SERVICE, "NO_CDMA_SERVICE");
        sFailCauseMap.put(RUIM_NOT_PRESENT, "RUIM_NOT_PRESENT");
        sFailCauseMap.put(CDMA_RETRY_ORDER, "CDMA_RETRY_ORDER");
        sFailCauseMap.put(ACCESS_BLOCK, "ACCESS_BLOCK");
        sFailCauseMap.put(ACCESS_BLOCK_ALL, "ACCESS_BLOCK_ALL");
        sFailCauseMap.put(IS707B_MAX_ACCESS_PROBES, "IS707B_MAX_ACCESS_PROBES");
        sFailCauseMap.put(THERMAL_EMERGENCY, "THERMAL_EMERGENCY");
        sFailCauseMap.put(CONCURRENT_SERVICES_NOT_ALLOWED, "CONCURRENT_SERVICES_NOT_ALLOWED");
        sFailCauseMap.put(INCOMING_CALL_REJECTED, "INCOMING_CALL_REJECTED");
        sFailCauseMap.put(NO_SERVICE_ON_GATEWAY, "NO_SERVICE_ON_GATEWAY");
        sFailCauseMap.put(NO_GPRS_CONTEXT, "NO_GPRS_CONTEXT");
        sFailCauseMap.put(ILLEGAL_MS, "ILLEGAL_MS");
        sFailCauseMap.put(ILLEGAL_ME, "ILLEGAL_ME");
        sFailCauseMap.put(GPRS_SERVICES_AND_NON_GPRS_SERVICES_NOT_ALLOWED,
                "GPRS_SERVICES_AND_NON_GPRS_SERVICES_NOT_ALLOWED");
        sFailCauseMap.put(GPRS_SERVICES_NOT_ALLOWED, "GPRS_SERVICES_NOT_ALLOWED");
        sFailCauseMap.put(MS_IDENTITY_CANNOT_BE_DERIVED_BY_THE_NETWORK,
                "MS_IDENTITY_CANNOT_BE_DERIVED_BY_THE_NETWORK");
        sFailCauseMap.put(IMPLICITLY_DETACHED, "IMPLICITLY_DETACHED");
        sFailCauseMap.put(PLMN_NOT_ALLOWED, "PLMN_NOT_ALLOWED");
        sFailCauseMap.put(LOCATION_AREA_NOT_ALLOWED, "LOCATION_AREA_NOT_ALLOWED");
        sFailCauseMap.put(GPRS_SERVICES_NOT_ALLOWED_IN_THIS_PLMN,
                "GPRS_SERVICES_NOT_ALLOWED_IN_THIS_PLMN");
        sFailCauseMap.put(PDP_DUPLICATE, "PDP_DUPLICATE");
        sFailCauseMap.put(UE_RAT_CHANGE, "UE_RAT_CHANGE");
        sFailCauseMap.put(CONGESTION, "CONGESTION");
        sFailCauseMap.put(NO_PDP_CONTEXT_ACTIVATED, "NO_PDP_CONTEXT_ACTIVATED");
        sFailCauseMap.put(ACCESS_CLASS_DSAC_REJECTION, "ACCESS_CLASS_DSAC_REJECTION");
        sFailCauseMap.put(PDP_ACTIVATE_MAX_RETRY_FAILED, "PDP_ACTIVATE_MAX_RETRY_FAILED");
        sFailCauseMap.put(RADIO_ACCESS_BEARER_FAILURE, "RADIO_ACCESS_BEARER_FAILURE");
        sFailCauseMap.put(ESM_UNKNOWN_EPS_BEARER_CONTEXT, "ESM_UNKNOWN_EPS_BEARER_CONTEXT");
        sFailCauseMap.put(DRB_RELEASED_BY_RRC, "DRB_RELEASED_BY_RRC");
        sFailCauseMap.put(CONNECTION_RELEASED, "CONNECTION_RELEASED");
        sFailCauseMap.put(EMM_DETACHED, "EMM_DETACHED");
        sFailCauseMap.put(EMM_ATTACH_FAILED, "EMM_ATTACH_FAILED");
        sFailCauseMap.put(EMM_ATTACH_STARTED, "EMM_ATTACH_STARTED");
        sFailCauseMap.put(LTE_NAS_SERVICE_REQUEST_FAILED, "LTE_NAS_SERVICE_REQUEST_FAILED");
        sFailCauseMap.put(DUPLICATE_BEARER_ID, "DUPLICATE_BEARER_ID");
        sFailCauseMap.put(ESM_COLLISION_SCENARIOS, "ESM_COLLISION_SCENARIOS");
        sFailCauseMap.put(ESM_BEARER_DEACTIVATED_TO_SYNC_WITH_NETWORK,
                "ESM_BEARER_DEACTIVATED_TO_SYNC_WITH_NETWORK");
        sFailCauseMap.put(ESM_NW_ACTIVATED_DED_BEARER_WITH_ID_OF_DEF_BEARER,
                "ESM_NW_ACTIVATED_DED_BEARER_WITH_ID_OF_DEF_BEARER");
        sFailCauseMap.put(ESM_BAD_OTA_MESSAGE, "ESM_BAD_OTA_MESSAGE");
        sFailCauseMap.put(ESM_DOWNLOAD_SERVER_REJECTED_THE_CALL,
                "ESM_DOWNLOAD_SERVER_REJECTED_THE_CALL");
        sFailCauseMap.put(ESM_CONTEXT_TRANSFERRED_DUE_TO_IRAT,
                "ESM_CONTEXT_TRANSFERRED_DUE_TO_IRAT");
        sFailCauseMap.put(DS_EXPLICIT_DEACTIVATION, "DS_EXPLICIT_DEACTIVATION");
        sFailCauseMap.put(ESM_LOCAL_CAUSE_NONE, "ESM_LOCAL_CAUSE_NONE");
        sFailCauseMap.put(LTE_THROTTLING_NOT_REQUIRED, "LTE_THROTTLING_NOT_REQUIRED");
        sFailCauseMap.put(ACCESS_CONTROL_LIST_CHECK_FAILURE,
                "ACCESS_CONTROL_LIST_CHECK_FAILURE");
        sFailCauseMap.put(SERVICE_NOT_ALLOWED_ON_PLMN, "SERVICE_NOT_ALLOWED_ON_PLMN");
        sFailCauseMap.put(EMM_T3417_EXPIRED, "EMM_T3417_EXPIRED");
        sFailCauseMap.put(EMM_T3417_EXT_EXPIRED, "EMM_T3417_EXT_EXPIRED");
        sFailCauseMap.put(RRC_UPLINK_DATA_TRANSMISSION_FAILURE,
                "RRC_UPLINK_DATA_TRANSMISSION_FAILURE");
        sFailCauseMap.put(RRC_UPLINK_DELIVERY_FAILED_DUE_TO_HANDOVER,
                "RRC_UPLINK_DELIVERY_FAILED_DUE_TO_HANDOVER");
        sFailCauseMap.put(RRC_UPLINK_CONNECTION_RELEASE, "RRC_UPLINK_CONNECTION_RELEASE");
        sFailCauseMap.put(RRC_UPLINK_RADIO_LINK_FAILURE, "RRC_UPLINK_RADIO_LINK_FAILURE");
        sFailCauseMap.put(RRC_UPLINK_ERROR_REQUEST_FROM_NAS, "RRC_UPLINK_ERROR_REQUEST_FROM_NAS");
        sFailCauseMap.put(RRC_CONNECTION_ACCESS_STRATUM_FAILURE,
                "RRC_CONNECTION_ACCESS_STRATUM_FAILURE");
        sFailCauseMap.put(RRC_CONNECTION_ANOTHER_PROCEDURE_IN_PROGRESS,
                "RRC_CONNECTION_ANOTHER_PROCEDURE_IN_PROGRESS");
        sFailCauseMap.put(RRC_CONNECTION_ACCESS_BARRED, "RRC_CONNECTION_ACCESS_BARRED");
        sFailCauseMap.put(RRC_CONNECTION_CELL_RESELECTION, "RRC_CONNECTION_CELL_RESELECTION");
        sFailCauseMap.put(RRC_CONNECTION_CONFIG_FAILURE, "RRC_CONNECTION_CONFIG_FAILURE");
        sFailCauseMap.put(RRC_CONNECTION_TIMER_EXPIRED, "RRC_CONNECTION_TIMER_EXPIRED");
        sFailCauseMap.put(RRC_CONNECTION_LINK_FAILURE, "RRC_CONNECTION_LINK_FAILURE");
        sFailCauseMap.put(RRC_CONNECTION_CELL_NOT_CAMPED, "RRC_CONNECTION_CELL_NOT_CAMPED");
        sFailCauseMap.put(RRC_CONNECTION_SYSTEM_INTERVAL_FAILURE,
                "RRC_CONNECTION_SYSTEM_INTERVAL_FAILURE");
        sFailCauseMap.put(RRC_CONNECTION_REJECT_BY_NETWORK, "RRC_CONNECTION_REJECT_BY_NETWORK");
        sFailCauseMap.put(RRC_CONNECTION_NORMAL_RELEASE, "RRC_CONNECTION_NORMAL_RELEASE");
        sFailCauseMap.put(RRC_CONNECTION_RADIO_LINK_FAILURE, "RRC_CONNECTION_RADIO_LINK_FAILURE");
        sFailCauseMap.put(RRC_CONNECTION_REESTABLISHMENT_FAILURE,
                "RRC_CONNECTION_REESTABLISHMENT_FAILURE");
        sFailCauseMap.put(RRC_CONNECTION_OUT_OF_SERVICE_DURING_CELL_REGISTER,
                "RRC_CONNECTION_OUT_OF_SERVICE_DURING_CELL_REGISTER");
        sFailCauseMap.put(RRC_CONNECTION_ABORT_REQUEST, "RRC_CONNECTION_ABORT_REQUEST");
        sFailCauseMap.put(RRC_CONNECTION_SYSTEM_INFORMATION_BLOCK_READ_ERROR,
                "RRC_CONNECTION_SYSTEM_INFORMATION_BLOCK_READ_ERROR");
        sFailCauseMap.put(NETWORK_INITIATED_DETACH_WITH_AUTO_REATTACH,
                "NETWORK_INITIATED_DETACH_WITH_AUTO_REATTACH");
        sFailCauseMap.put(NETWORK_INITIATED_DETACH_NO_AUTO_REATTACH,
                "NETWORK_INITIATED_DETACH_NO_AUTO_REATTACH");
        sFailCauseMap.put(ESM_PROCEDURE_TIME_OUT, "ESM_PROCEDURE_TIME_OUT");
        sFailCauseMap.put(INVALID_CONNECTION_ID, "INVALID_CONNECTION_ID");
        sFailCauseMap.put(MAXIMIUM_NSAPIS_EXCEEDED, "MAXIMIUM_NSAPIS_EXCEEDED");
        sFailCauseMap.put(INVALID_PRIMARY_NSAPI, "INVALID_PRIMARY_NSAPI");
        sFailCauseMap.put(CANNOT_ENCODE_OTA_MESSAGE, "CANNOT_ENCODE_OTA_MESSAGE");
        sFailCauseMap.put(RADIO_ACCESS_BEARER_SETUP_FAILURE, "RADIO_ACCESS_BEARER_SETUP_FAILURE");
        sFailCauseMap.put(PDP_ESTABLISH_TIMEOUT_EXPIRED, "PDP_ESTABLISH_TIMEOUT_EXPIRED");
        sFailCauseMap.put(PDP_MODIFY_TIMEOUT_EXPIRED, "PDP_MODIFY_TIMEOUT_EXPIRED");
        sFailCauseMap.put(PDP_INACTIVE_TIMEOUT_EXPIRED, "PDP_INACTIVE_TIMEOUT_EXPIRED");
        sFailCauseMap.put(PDP_LOWERLAYER_ERROR, "PDP_LOWERLAYER_ERROR");
        sFailCauseMap.put(PDP_MODIFY_COLLISION, "PDP_MODIFY_COLLISION");
        sFailCauseMap.put(MAXINUM_SIZE_OF_L2_MESSAGE_EXCEEDED,
                "MAXINUM_SIZE_OF_L2_MESSAGE_EXCEEDED");
        sFailCauseMap.put(NAS_REQUEST_REJECTED_BY_NETWORK, "NAS_REQUEST_REJECTED_BY_NETWORK");
        sFailCauseMap.put(RRC_CONNECTION_INVALID_REQUEST, "RRC_CONNECTION_INVALID_REQUEST");
        sFailCauseMap.put(RRC_CONNECTION_TRACKING_AREA_ID_CHANGED,
                "RRC_CONNECTION_TRACKING_AREA_ID_CHANGED");
        sFailCauseMap.put(RRC_CONNECTION_RF_UNAVAILABLE, "RRC_CONNECTION_RF_UNAVAILABLE");
        sFailCauseMap.put(RRC_CONNECTION_ABORTED_DUE_TO_IRAT_CHANGE,
                "RRC_CONNECTION_ABORTED_DUE_TO_IRAT_CHANGE");
        sFailCauseMap.put(RRC_CONNECTION_RELEASED_SECURITY_NOT_ACTIVE,
                "RRC_CONNECTION_RELEASED_SECURITY_NOT_ACTIVE");
        sFailCauseMap.put(RRC_CONNECTION_ABORTED_AFTER_HANDOVER,
                "RRC_CONNECTION_ABORTED_AFTER_HANDOVER");
        sFailCauseMap.put(RRC_CONNECTION_ABORTED_AFTER_IRAT_CELL_CHANGE,
                "RRC_CONNECTION_ABORTED_AFTER_IRAT_CELL_CHANGE");
        sFailCauseMap.put(RRC_CONNECTION_ABORTED_DURING_IRAT_CELL_CHANGE,
                "RRC_CONNECTION_ABORTED_DURING_IRAT_CELL_CHANGE");
        sFailCauseMap.put(IMSI_UNKNOWN_IN_HOME_SUBSCRIBER_SERVER,
                "IMSI_UNKNOWN_IN_HOME_SUBSCRIBER_SERVER");
        sFailCauseMap.put(IMEI_NOT_ACCEPTED, "IMEI_NOT_ACCEPTED");
        sFailCauseMap.put(EPS_SERVICES_AND_NON_EPS_SERVICES_NOT_ALLOWED,
                "EPS_SERVICES_AND_NON_EPS_SERVICES_NOT_ALLOWED");
        sFailCauseMap.put(EPS_SERVICES_NOT_ALLOWED_IN_PLMN, "EPS_SERVICES_NOT_ALLOWED_IN_PLMN");
        sFailCauseMap.put(MSC_TEMPORARILY_NOT_REACHABLE, "MSC_TEMPORARILY_NOT_REACHABLE");
        sFailCauseMap.put(CS_DOMAIN_NOT_AVAILABLE, "CS_DOMAIN_NOT_AVAILABLE");
        sFailCauseMap.put(ESM_FAILURE, "ESM_FAILURE");
        sFailCauseMap.put(MAC_FAILURE, "MAC_FAILURE");
        sFailCauseMap.put(SYNCHRONIZATION_FAILURE, "SYNCHRONIZATION_FAILURE");
        sFailCauseMap.put(UE_SECURITY_CAPABILITIES_MISMATCH, "UE_SECURITY_CAPABILITIES_MISMATCH");
        sFailCauseMap.put(SECURITY_MODE_REJECTED, "SECURITY_MODE_REJECTED");
        sFailCauseMap.put(UNACCEPTABLE_NON_EPS_AUTHENTICATION,
                "UNACCEPTABLE_NON_EPS_AUTHENTICATION");
        sFailCauseMap.put(CS_FALLBACK_CALL_ESTABLISHMENT_NOT_ALLOWED,
                "CS_FALLBACK_CALL_ESTABLISHMENT_NOT_ALLOWED");
        sFailCauseMap.put(NO_EPS_BEARER_CONTEXT_ACTIVATED, "NO_EPS_BEARER_CONTEXT_ACTIVATED");
        sFailCauseMap.put(INVALID_EMM_STATE, "INVALID_EMM_STATE");
        sFailCauseMap.put(NAS_LAYER_FAILURE, "NAS_LAYER_FAILURE");
        sFailCauseMap.put(MULTIPLE_PDP_CALL_NOT_ALLOWED, "MULTIPLE_PDP_CALL_NOT_ALLOWED");
        sFailCauseMap.put(EMBMS_NOT_ENABLED, "EMBMS_NOT_ENABLED");
        sFailCauseMap.put(IRAT_HANDOVER_FAILED, "IRAT_HANDOVER_FAILED");
        sFailCauseMap.put(EMBMS_REGULAR_DEACTIVATION, "EMBMS_REGULAR_DEACTIVATION");
        sFailCauseMap.put(TEST_LOOPBACK_REGULAR_DEACTIVATION, "TEST_LOOPBACK_REGULAR_DEACTIVATION");
        sFailCauseMap.put(LOWER_LAYER_REGISTRATION_FAILURE, "LOWER_LAYER_REGISTRATION_FAILURE");
        sFailCauseMap.put(DATA_PLAN_EXPIRED, "DATA_PLAN_EXPIRED");
        sFailCauseMap.put(UMTS_HANDOVER_TO_IWLAN, "UMTS_HANDOVER_TO_IWLAN");
        sFailCauseMap.put(EVDO_CONNECTION_DENY_BY_GENERAL_OR_NETWORK_BUSY,
                "EVDO_CONNECTION_DENY_BY_GENERAL_OR_NETWORK_BUSY");
        sFailCauseMap.put(EVDO_CONNECTION_DENY_BY_BILLING_OR_AUTHENTICATION_FAILURE,
                "EVDO_CONNECTION_DENY_BY_BILLING_OR_AUTHENTICATION_FAILURE");
        sFailCauseMap.put(EVDO_HDR_CHANGED, "EVDO_HDR_CHANGED");
        sFailCauseMap.put(EVDO_HDR_EXITED, "EVDO_HDR_EXITED");
        sFailCauseMap.put(EVDO_HDR_NO_SESSION, "EVDO_HDR_NO_SESSION");
        sFailCauseMap.put(EVDO_USING_GPS_FIX_INSTEAD_OF_HDR_CALL,
                "EVDO_USING_GPS_FIX_INSTEAD_OF_HDR_CALL");
        sFailCauseMap.put(EVDO_HDR_CONNECTION_SETUP_TIMEOUT, "EVDO_HDR_CONNECTION_SETUP_TIMEOUT");
        sFailCauseMap.put(FAILED_TO_ACQUIRE_COLOCATED_HDR, "FAILED_TO_ACQUIRE_COLOCATED_HDR");
        sFailCauseMap.put(OTASP_COMMIT_IN_PROGRESS, "OTASP_COMMIT_IN_PROGRESS");
        sFailCauseMap.put(NO_HYBRID_HDR_SERVICE, "NO_HYBRID_HDR_SERVICE");
        sFailCauseMap.put(HDR_NO_LOCK_GRANTED, "HDR_NO_LOCK_GRANTED");
        sFailCauseMap.put(DBM_OR_SMS_IN_PROGRESS, "DBM_OR_SMS_IN_PROGRESS");
        sFailCauseMap.put(HDR_FADE, "HDR_FADE");
        sFailCauseMap.put(HDR_ACCESS_FAILURE, "HDR_ACCESS_FAILURE");
        sFailCauseMap.put(UNSUPPORTED_1X_PREV, "UNSUPPORTED_1X_PREV");
        sFailCauseMap.put(LOCAL_END, "LOCAL_END");
        sFailCauseMap.put(NO_SERVICE, "NO_SERVICE");
        sFailCauseMap.put(FADE, "FADE");
        sFailCauseMap.put(NORMAL_RELEASE, "NORMAL_RELEASE");
        sFailCauseMap.put(ACCESS_ATTEMPT_ALREADY_IN_PROGRESS, "ACCESS_ATTEMPT_ALREADY_IN_PROGRESS");
        sFailCauseMap.put(REDIRECTION_OR_HANDOFF_IN_PROGRESS, "REDIRECTION_OR_HANDOFF_IN_PROGRESS");
        sFailCauseMap.put(EMERGENCY_MODE, "EMERGENCY_MODE");
        sFailCauseMap.put(PHONE_IN_USE, "PHONE_IN_USE");
        sFailCauseMap.put(INVALID_MODE, "INVALID_MODE");
        sFailCauseMap.put(INVALID_SIM_STATE, "INVALID_SIM_STATE");
        sFailCauseMap.put(NO_COLLOCATED_HDR, "NO_COLLOCATED_HDR");
        sFailCauseMap.put(UE_IS_ENTERING_POWERSAVE_MODE, "UE_IS_ENTERING_POWERSAVE_MODE");
        sFailCauseMap.put(DUAL_SWITCH, "DUAL_SWITCH");
        sFailCauseMap.put(PPP_TIMEOUT, "PPP_TIMEOUT");
        sFailCauseMap.put(PPP_AUTH_FAILURE, "PPP_AUTH_FAILURE");
        sFailCauseMap.put(PPP_OPTION_MISMATCH, "PPP_OPTION_MISMATCH");
        sFailCauseMap.put(PPP_PAP_FAILURE, "PPP_PAP_FAILURE");
        sFailCauseMap.put(PPP_CHAP_FAILURE, "PPP_CHAP_FAILURE");
        sFailCauseMap.put(PPP_CLOSE_IN_PROGRESS, "PPP_CLOSE_IN_PROGRESS");
        sFailCauseMap.put(LIMITED_TO_IPV4, "LIMITED_TO_IPV4");
        sFailCauseMap.put(LIMITED_TO_IPV6, "LIMITED_TO_IPV6");
        sFailCauseMap.put(VSNCP_TIMEOUT, "VSNCP_TIMEOUT");
        sFailCauseMap.put(VSNCP_GEN_ERROR, "VSNCP_GEN_ERROR");
        sFailCauseMap.put(VSNCP_APN_UNATHORIZED, "VSNCP_APN_UNATHORIZED");
        sFailCauseMap.put(VSNCP_APN_UNAUTHORIZED, "VSNCP_APN_UNAUTHORIZED");
        sFailCauseMap.put(VSNCP_PDN_LIMIT_EXCEEDED, "VSNCP_PDN_LIMIT_EXCEEDED");
        sFailCauseMap.put(VSNCP_NO_PDN_GATEWAY_ADDRESS, "VSNCP_NO_PDN_GATEWAY_ADDRESS");
        sFailCauseMap.put(VSNCP_PDN_GATEWAY_UNREACHABLE, "VSNCP_PDN_GATEWAY_UNREACHABLE");
        sFailCauseMap.put(VSNCP_PDN_GATEWAY_REJECT, "VSNCP_PDN_GATEWAY_REJECT");
        sFailCauseMap.put(VSNCP_INSUFFICIENT_PARAMETERS, "VSNCP_INSUFFICIENT_PARAMETERS");
        sFailCauseMap.put(VSNCP_RESOURCE_UNAVAILABLE, "VSNCP_RESOURCE_UNAVAILABLE");
        sFailCauseMap.put(VSNCP_ADMINISTRATIVELY_PROHIBITED, "VSNCP_ADMINISTRATIVELY_PROHIBITED");
        sFailCauseMap.put(VSNCP_PDN_ID_IN_USE, "VSNCP_PDN_ID_IN_USE");
        sFailCauseMap.put(VSNCP_SUBSCRIBER_LIMITATION, "VSNCP_SUBSCRIBER_LIMITATION");
        sFailCauseMap.put(VSNCP_PDN_EXISTS_FOR_THIS_APN, "VSNCP_PDN_EXISTS_FOR_THIS_APN");
        sFailCauseMap.put(VSNCP_RECONNECT_NOT_ALLOWED, "VSNCP_RECONNECT_NOT_ALLOWED");
        sFailCauseMap.put(IPV6_PREFIX_UNAVAILABLE, "IPV6_PREFIX_UNAVAILABLE");
        sFailCauseMap.put(HANDOFF_PREFERENCE_CHANGED, "HANDOFF_PREFERENCE_CHANGED");
        sFailCauseMap.put(SLICE_REJECTED, "SLICE_REJECTED");
        sFailCauseMap.put(MATCH_ALL_RULE_NOT_ALLOWED, "MATCH_ALL_RULE_NOT_ALLOWED");
        sFailCauseMap.put(ALL_MATCHING_RULES_FAILED, "ALL_MATCHING_RULES_FAILED");
        sFailCauseMap.put(IWLAN_PDN_CONNECTION_REJECTION, "IWLAN_PDN_CONNECTION_REJECTION");
        sFailCauseMap.put(IWLAN_MAX_CONNECTION_REACHED, "IWLAN_MAX_CONNECTION_REACHED");
        sFailCauseMap.put(IWLAN_SEMANTIC_ERROR_IN_THE_TFT_OPERATION,
                "IWLAN_SEMANTIC_ERROR_IN_THE_TFT_OPERATION");
        sFailCauseMap.put(IWLAN_SYNTACTICAL_ERROR_IN_THE_TFT_OPERATION,
                "IWLAN_SYNTACTICAL_ERROR_IN_THE_TFT_OPERATION");
        sFailCauseMap.put(IWLAN_SEMANTIC_ERRORS_IN_PACKET_FILTERS,
                "IWLAN_SEMANTIC_ERRORS_IN_PACKET_FILTERS");
        sFailCauseMap.put(IWLAN_SYNTACTICAL_ERRORS_IN_PACKET_FILTERS,
                "IWLAN_SYNTACTICAL_ERRORS_IN_PACKET_FILTERS");
        sFailCauseMap.put(IWLAN_NON_3GPP_ACCESS_TO_EPC_NOT_ALLOWED,
                "IWLAN_NON_3GPP_ACCESS_TO_EPC_NOT_ALLOWED");
        sFailCauseMap.put(IWLAN_USER_UNKNOWN, "IWLAN_USER_UNKNOWN");
        sFailCauseMap.put(IWLAN_NO_APN_SUBSCRIPTION, "IWLAN_NO_APN_SUBSCRIPTION");
        sFailCauseMap.put(IWLAN_AUTHORIZATION_REJECTED, "IWLAN_AUTHORIZATION_REJECTED");
        sFailCauseMap.put(IWLAN_ILLEGAL_ME, "IWLAN_ILLEGAL_ME");
        sFailCauseMap.put(IWLAN_NETWORK_FAILURE, "IWLAN_NETWORK_FAILURE");
        sFailCauseMap.put(IWLAN_RAT_TYPE_NOT_ALLOWED, "IWLAN_RAT_TYPE_NOT_ALLOWED");
        sFailCauseMap.put(IWLAN_IMEI_NOT_ACCEPTED, "IWLAN_IMEI_NOT_ACCEPTED");
        sFailCauseMap.put(IWLAN_PLMN_NOT_ALLOWED, "IWLAN_PLMN_NOT_ALLOWED");
        sFailCauseMap.put(IWLAN_UNAUTHENTICATED_EMERGENCY_NOT_SUPPORTED,
                "IWLAN_UNAUTHENTICATED_EMERGENCY_NOT_SUPPORTED");
        sFailCauseMap.put(IWLAN_IKEV2_CONFIG_FAILURE, "IWLAN_IKEV2_CONFIG_FAILURE");
        sFailCauseMap.put(IWLAN_IKEV2_AUTH_FAILURE, "IWLAN_IKEV2_AUTH_FAILURE");
        sFailCauseMap.put(IWLAN_IKEV2_MSG_TIMEOUT, "IWLAN_IKEV2_MSG_TIMEOUT");
        sFailCauseMap.put(IWLAN_IKEV2_CERT_INVALID, "IWLAN_IKEV2_CERT_INVALID");
        sFailCauseMap.put(IWLAN_DNS_RESOLUTION_NAME_FAILURE, "IWLAN_DNS_RESOLUTION_NAME_FAILURE");
        sFailCauseMap.put(IWLAN_DNS_RESOLUTION_TIMEOUT, "IWLAN_DNS_RESOLUTION_TIMEOUT");
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
        sFailCauseMap.put(LOST_CONNECTION, "LOST_CONNECTION");
        sFailCauseMap.put(HANDOVER_FAILED, "HANDOVER_FAILED");
        sFailCauseMap.put(DUPLICATE_CID, "DUPLICATE_CID");
        sFailCauseMap.put(NO_DEFAULT_DATA, "NO_DEFAULT_DATA");
        sFailCauseMap.put(SERVICE_TEMPORARILY_UNAVAILABLE, "SERVICE_TEMPORARILY_UNAVAILABLE");
        sFailCauseMap.put(REQUEST_NOT_SUPPORTED, "REQUEST_NOT_SUPPORTED");
        sFailCauseMap.put(NO_RETRY_FAILURE, "NO_RETRY_FAILURE");
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
    public static boolean isRadioRestartFailure(@NonNull Context context,
                                                @DataFailureCause int cause,
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
    // TODO: Migrated to DataConfigManager
    public static boolean isPermanentFailure(@NonNull Context context,
                                             @DataFailureCause int failCause,
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
                        String[] permanentFailureStrings = b.getStringArray(CarrierConfigManager
                                .KEY_CARRIER_DATA_CALL_PERMANENT_FAILURE_STRINGS);
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
                            add(DUPLICATE_CID);
                            add(MATCH_ALL_RULE_NOT_ALLOWED);
                            add(ALL_MATCHING_RULES_FAILED);
                        }
                    };
                }

                permanentFailureSet.add(NO_RETRY_FAILURE);
                sPermanentFailureCache.put(subId, permanentFailureSet);
            }

            return permanentFailureSet.contains(failCause);
        }
    }

    /** @hide */
    public static boolean isEventLoggable(@DataFailureCause int dataFailCause) {
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
    public static String toString(@DataFailureCause int dataFailCause) {
        return sFailCauseMap.getOrDefault(dataFailCause, "UNKNOWN") + "(0x"
                + Integer.toHexString(dataFailCause) + ")";
    }

    /** @hide */
    public static int getFailCause(@DataFailureCause int failCause) {
        if (sFailCauseMap.containsKey(failCause)) {
            return failCause;
        } else {
            return UNKNOWN;
        }
    }

    /** @hide */
    public static boolean isFailCauseExisting(@DataFailureCause int failCause) {
        return sFailCauseMap.containsKey(failCause);
    }
}
