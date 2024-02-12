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

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.telecom.VideoProfile;
import android.telephony.CallState;
import android.telephony.emergency.EmergencyNumber;
import android.telephony.emergency.EmergencyNumber.EmergencyCallRouting;
import android.telephony.emergency.EmergencyNumber.EmergencyServiceCategories;
import android.telephony.ims.feature.MmTelFeature;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.util.TelephonyUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A Parcelable object to handle the IMS call profile, which provides the service, call type, and
 * additional information related to the call.
 * <p>
 * See the following specifications for more information about this class: GSMA IR.92/IR.94,
 * 3GPP TS 24.229/TS 26.114/TS26.111.
 * @hide
 */
@SystemApi
public final class ImsCallProfile implements Parcelable {
    private static final String TAG = "ImsCallProfile";

    /**
     * Service types
     */
    /**
     * It is for a special case. It helps that the application can make a call
     * without IMS connection (not registered).
     * In the moment of the call initiation, the device try to connect to the IMS network
     * and initiates the call.
     */
    public static final int SERVICE_TYPE_NONE = 0;
    /**
     * It is a default type and can be selected when the device is connected to the IMS network.
     */
    public static final int SERVICE_TYPE_NORMAL = 1;
    /**
     * It is for an emergency call.
     */
    public static final int SERVICE_TYPE_EMERGENCY = 2;

    /**
     * This value is returned if there is no valid IMS call type defined for the call. For example,
     * if an ongoing call is circuit-switched and {@link CallState#getImsCallType()} is called, this
     * value will be returned.
     */
    public static final int CALL_TYPE_NONE = 0;
    /**
     * IMSPhone to support IR.92 & IR.94 (voice + video upgrade/downgrade)
     */
    public static final int CALL_TYPE_VOICE_N_VIDEO = 1;
    /**
     * IR.92 (Voice only)
     */
    public static final int CALL_TYPE_VOICE = 2;
    /**
     * VT to support IR.92 & IR.94 (voice + video upgrade/downgrade)
     */
    public static final int CALL_TYPE_VIDEO_N_VOICE = 3;
    /**
     * Video Telephony (audio / video two way)
     */
    public static final int CALL_TYPE_VT = 4;
    /**
     * Video Telephony (audio two way / video TX one way)
     */
    public static final int CALL_TYPE_VT_TX = 5;
    /**
     * Video Telephony (audio two way / video RX one way)
     */
    public static final int CALL_TYPE_VT_RX = 6;
    /**
     * Video Telephony (audio two way / video inactive)
     */
    public static final int CALL_TYPE_VT_NODIR = 7;
    /**
     * VideoShare (video two way)
     */
    public static final int CALL_TYPE_VS = 8;
    /**
     * VideoShare (video TX one way)
     */
    public static final int CALL_TYPE_VS_TX = 9;
    /**
     * VideoShare (video RX one way)
     */
    public static final int CALL_TYPE_VS_RX = 10;

    /**
     * Extra properties for IMS call.
     */
    /**
     * Boolean extra properties - "true" / "false"
     *  conference : Indicates if the session is for the conference call or not.
     *  e_call : Indicates if the session is for the emergency call or not.
     *  vms : Indicates if the session is connected to the voice mail system or not.
     *  call_mode_changeable : Indicates if the session is able to upgrade/downgrade
     *      the video during voice call.
     *  conference_avail : Indicates if the session can be extended to the conference.
     */

    /**
     * Indicates if the session is for a conference call or not. If not defined, should be
     * considered {@code false}.
     * Boolean extra properties - {@code true} / {@code false}.
     *
     * This extra is set on an instance of {@link ImsCallProfile} via {@link #setCallExtraBoolean}.
     * @hide
     */
    @SystemApi
    public static final String EXTRA_CONFERENCE = "android.telephony.ims.extra.CONFERENCE";

    /**
     * The previous string of EXTRA_CONFERENCE. Use EXTRA_CONFERENCE whenever possible.
     * For external app or vendor code backward compatibility, we should always set value for both
     * EXTRA_CONFERENCE_DEPRECATED and EXTRA_CONFERENCE.
     *
     * @deprecated Remove when not needed anymore.
     *
     * @hide
     */
    public static final String EXTRA_CONFERENCE_DEPRECATED = "conference";

    /**
     * Boolean extra property set on an {@link ImsCallProfile} to indicate that this call is an
     * emergency call.  The {@link ImsService} sets this on a call to indicate that the network has
     * identified the call as an emergency call.
     */
    public static final String EXTRA_EMERGENCY_CALL = "e_call";

    /**
     * @hide
     */
    public static final String EXTRA_VMS = "vms";
    /**
     * @hide
     */
    public static final String EXTRA_CALL_MODE_CHANGEABLE = "call_mode_changeable";

    /**
     * Indicates if the session can be extended to a conference call. If not defined, should be
     * considered {@code false}.
     * Boolean extra properties - {@code true} / {@code false}.
     *
     * This extra is set on an instance of {@link ImsCallProfile} via {@link #setCallExtraBoolean}.
     * @hide
     */
    @SystemApi
    public static final String EXTRA_EXTENDING_TO_CONFERENCE_SUPPORTED =
            "android.telephony.ims.extra.EXTENDING_TO_CONFERENCE_SUPPORTED";

    /**
     * The previous string of EXTRA_EXTENDING_TO_CONFERENCE_SUPPORTED.
     * Use EXTRA_EXTENDING_TO_CONFERENCE_SUPPORTED whenever possible.
     * For backward compatibility, we should always set value for both
     * EXTRA_EXTENDING_TO_CONFERENCE_SUPPORTED and EXTRA_CONFERENCE_AVAIL.
     *
     * @deprecated Remove when not needed anymore.
     *
     * @hide
     */
    public static final String EXTRA_CONFERENCE_AVAIL = "conference_avail";

    /**
     * Extra key used to store a Bundle containing proprietary extras to send to the ImsService.
     * Use {@link #getProprietaryCallExtras()} instead.
     * @hide
     */
    @TestApi
    public static final String EXTRA_OEM_EXTRAS = "android.telephony.ims.extra.OEM_EXTRAS";

    /**
     * Rule for originating identity (number) presentation, MO/MT.
     *      {@link ImsCallProfile#OIR_DEFAULT}
     *      {@link ImsCallProfile#OIR_PRESENTATION_RESTRICTED}
     *      {@link ImsCallProfile#OIR_PRESENTATION_NOT_RESTRICTED}
     */
    public static final String EXTRA_OIR = "oir";
    /**
     * Rule for calling name presentation
     *      {@link ImsCallProfile#OIR_DEFAULT}
     *      {@link ImsCallProfile#OIR_PRESENTATION_RESTRICTED}
     *      {@link ImsCallProfile#OIR_PRESENTATION_NOT_RESTRICTED}
     */
    public static final String EXTRA_CNAP = "cnap";
    /**
     * To identify the Ims call type, MO
     *      {@link ImsCallProfile#DIALSTRING_NORMAL}
     *      {@link ImsCallProfile#DIALSTRING_SS_CONF}
     *      {@link ImsCallProfile#DIALSTRING_USSD}
     */
    public static final String EXTRA_DIALSTRING = "dialstring";
    /**
     * This extra holds call fail cause because of which redial is attempted.
     * see {@link android.telephony.ims.ImsReasonInfo} {@code CODE_*}
     * for possible values this extra can hold.
     *
     * @hide
     */
    public static final String EXTRA_RETRY_CALL_FAIL_REASON =
            "android.telephony.ims.extra.RETRY_CALL_FAIL_REASON";
    /**
     * This extra holds call network type on which lower layers
     * may try attempting redial.
     * See {@link TelephonyManager} {@code NETWORK_TYPE_*}
     * for possible values this extra can hold.
     *
     * @hide
     */
    public static final String EXTRA_RETRY_CALL_FAIL_NETWORKTYPE =
            "android.telephony.ims.extra.RETRY_CALL_FAIL_NETWORKTYPE";

    /**
     * Extra for the call composer call priority, either {@link ImsCallProfile#PRIORITY_NORMAL} or
     * {@link ImsCallProfile#PRIORITY_URGENT}. It can be set via
     * {@link #setCallExtraInt(String, int)}.
     *
     * Reference: RCC.20 Section 2.4.4.2
     */
    public static final String EXTRA_PRIORITY = "android.telephony.ims.extra.PRIORITY";

    // TODO(hallliu) remove the reference to the maximum length and update it later.
    /**
     * Extra for the call composer call subject, a string of maximum length 60 characters.
     * It can be set via {@link #setCallExtra(String, String)}.
     *
     * Reference: RCC.20 Section 2.4.3.2
     */
    public static final String EXTRA_CALL_SUBJECT = "android.telephony.ims.extra.CALL_SUBJECT";

    /**
     * Extra for the call composer call location, an {@Link android.location.Location} parcelable
     * class to represent the geolocation as a latitude and longitude pair. It can be set via
     * {@link #setCallExtraParcelable(String, Parcelable)}.
     *
     * Reference: RCC.20 Section 2.4.3.2
     */
    public static final String EXTRA_LOCATION = "android.telephony.ims.extra.LOCATION";

    /**
     * Extra for the call composer picture URL, a String that indicates the URL on the carrier’s
     * server infrastructure to get the picture. It can be set via
     * {@link #setCallExtra(String, String)}.
     *
     * Note that this URL is not intended to be parsed by the IMS stack -- it should be sent
     * directly to the network for consumption by the called party or forwarded directly from the
     * network to the platform for caching and download.
     *
     * Reference: RCC.20 Section 2.4.3.2
     */
    public static final String EXTRA_PICTURE_URL = "android.telephony.ims.extra.PICTURE_URL";

    /**
     * Boolean extra indicating whether the call is a business call.
     *
     * This extra will be set to {@code true} if and only if the SIP INVITE headers contain the
     * "Organization" header.
     */
    public static final String EXTRA_IS_BUSINESS_CALL =
            "android.telephony.ims.extra.IS_BUSINESS_CALL";

    /**
     * The vendor IMS stack populates this {@code string} extra; it is used to hold the display name
     * passed via the P-Asserted-Identity SIP header’s display-name field
     *
     * Reference: RFC3325
     */
    @FlaggedApi(com.android.server.telecom.flags.Flags.FLAG_BUSINESS_CALL_COMPOSER)
    public static final String EXTRA_ASSERTED_DISPLAY_NAME =
            "android.telephony.ims.extra.ASSERTED_DISPLAY_NAME";

    /**
     * Values for EXTRA_OIR / EXTRA_CNAP
     */
    /**
     * Default presentation for Originating Identity.
     */
    public static final int OIR_DEFAULT = 0;    // "user subscription default value"
    /**
     * Restricted presentation for Originating Identity.
     */
    public static final int OIR_PRESENTATION_RESTRICTED = 1;
    /**
     * Not restricted presentation for Originating Identity.
     */
    public static final int OIR_PRESENTATION_NOT_RESTRICTED = 2;
    /**
     * Presentation unknown for Originating Identity.
     */
    public static final int OIR_PRESENTATION_UNKNOWN = 3;
    /**
     * Payphone presentation for Originating Identity.
     */
    public static final int OIR_PRESENTATION_PAYPHONE = 4;
    /**
     * Unavailable presentation for Originating Identity.
     */
    public static final int OIR_PRESENTATION_UNAVAILABLE = 5;

    //Values for EXTRA_DIALSTRING
    /**
     * A default or normal normal call.
     */
    public static final int DIALSTRING_NORMAL = 0;
    /**
     * Call for SIP-based user configuration
     */
    public static final int DIALSTRING_SS_CONF = 1;
    /**
     * Call for USSD message
     */
    public static final int DIALSTRING_USSD = 2;

    // Values for EXTRA_PRIORITY
    /**
     * Indicates the call composer call priority is normal.
     *
     * Reference: RCC.20 Section 2.4.4.2
     */
    public static final int PRIORITY_NORMAL = 0;

    /**
     * Indicates the call composer call priority is urgent.
     *
     * Reference: RCC.20 Section 2.4.4.2
     */
    public static final int PRIORITY_URGENT = 1;

    /**
     * Call is not restricted on peer side and High Definition media is supported
     */
    public static final int CALL_RESTRICT_CAUSE_NONE = 0;

    /**
     * High Definition media is not supported on the peer side due to the Radio Access Technology
     * (RAT) it is are connected to.
     */
    public static final int CALL_RESTRICT_CAUSE_RAT = 1;

    /**
     * The service has been disabled on the peer side.
     */
    public static final int CALL_RESTRICT_CAUSE_DISABLED = 2;

    /**
     * High definition media is not currently supported.
     */
    public static final int CALL_RESTRICT_CAUSE_HD = 3;

    /**@hide*/
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "CALL_RESTRICT_CAUSE_", value = {
            CALL_RESTRICT_CAUSE_NONE,
            CALL_RESTRICT_CAUSE_RAT,
            CALL_RESTRICT_CAUSE_DISABLED,
            CALL_RESTRICT_CAUSE_HD
    })
    public @interface CallRestrictCause {}

    /**
     * String extra properties
     *  oi : Originating identity (number), MT only
     *  cna : Calling name
     *  ussd : For network-initiated USSD, MT only
     *  remote_uri : Connected user identity (it can be used for the conference)
     *  ChildNum: Child number info.
     *  Codec: Codec info.
     *  DisplayText: Display text for the call.
     *  AdditionalCallInfo: Additional call info.
     *  CallPull: Boolean value specifying if the call is a pulled call.
     */
    public static final String EXTRA_OI = "oi";
    public static final String EXTRA_CNA = "cna";
    public static final String EXTRA_USSD = "ussd";
    public static final String EXTRA_REMOTE_URI = "remote_uri";
    public static final String EXTRA_CHILD_NUMBER = "ChildNum";
    public static final String EXTRA_CODEC = "Codec";
    public static final String EXTRA_DISPLAY_TEXT = "DisplayText";
    public static final String EXTRA_ADDITIONAL_CALL_INFO = "AdditionalCallInfo";
    public static final String EXTRA_IS_CALL_PULL = "CallPull";

    /**
     * String extra property
     *  Containing fields from the SIP INVITE message for an IMS call
     */
    public static final String EXTRA_ADDITIONAL_SIP_INVITE_FIELDS =
                                  "android.telephony.ims.extra.ADDITIONAL_SIP_INVITE_FIELDS";

    /**
     * CallDisconnectCause: Specify call disconnect cause. This extra should be a code
     * corresponding to ImsReasonInfo and should only be populated in the case that the
     * call has already been missed
     */
    public static final String EXTRA_CALL_DISCONNECT_CAUSE =
                                 "android.telephony.ims.extra.CALL_DISCONNECT_CAUSE";

    /**
     * Extra key which the RIL can use to indicate the radio technology used for a call.
     * Valid values are:
     * {@link android.telephony.ServiceState#RIL_RADIO_TECHNOLOGY_LTE},
     * {@link android.telephony.ServiceState#RIL_RADIO_TECHNOLOGY_IWLAN}, and the other defined
     * {@code RIL_RADIO_TECHNOLOGY_*} constants.
     * Note: Despite the fact the {@link android.telephony.ServiceState} values are integer
     * constants, the values passed for the {@link #EXTRA_CALL_RAT_TYPE} should be strings (e.g.
     * "14" vs (int) 14).
     * Note: This is used by {@link com.android.internal.telephony.imsphone.ImsPhoneConnection#
     *      updateImsCallRatFromExtras(Bundle)} to determine whether to set the
     * {@link android.telecom.TelecomManager#EXTRA_CALL_NETWORK_TYPE} extra value and
     * {@link android.telecom.Connection#PROPERTY_WIFI} property on a connection.
     * @deprecated the constants associated with this extra are hidden, instead use
     * {@link #EXTRA_CALL_NETWORK_TYPE}.
     */
    @Deprecated
    public static final String EXTRA_CALL_RAT_TYPE = "CallRadioTech";

    /**
     * Extra key with an {@code int} value which can be set in {@link #setCallExtraInt(String, int)}
     * to indicate the network type used for a call.
     * <p>
     * Valid values are defined by {@code TelephonyManager.NETWORK_TYPE_*} constants. An example may
     * be {@link android.telephony.TelephonyManager#NETWORK_TYPE_LTE}.
     */
    public static final String EXTRA_CALL_NETWORK_TYPE =
            "android.telephony.ims.extra.CALL_NETWORK_TYPE";

    /**
     * Similar to {@link #EXTRA_CALL_RAT_TYPE}, except with a lowercase 'c'.  Used to ensure
     * compatibility with modems that are non-compliant with the {@link #EXTRA_CALL_RAT_TYPE}
     * extra key.  Should be removed when the non-compliant modems are fixed.
     * @hide
     * @deprecated Use {@link #EXTRA_CALL_NETWORK_TYPE} instead.
     */
    @Deprecated
    public static final String EXTRA_CALL_RAT_TYPE_ALT = "callRadioTech";

    /**
     * String extra property containing forwarded numbers associated with the current connection
     * for an IMS call. The value is string array, and it can include multiple numbers, and
     * the array values are expected E164 (e.g. +1 (650) 253-0000) format.
     */
    public static final String EXTRA_FORWARDED_NUMBER =
            "android.telephony.ims.extra.FORWARDED_NUMBER";

    /**
     * Extra key with an {@code boolean} value which can be set in
     * {@link #setCallExtraBoolean(String, boolean)} to indicate whether call is a cross sim call.
     * <p>
     * Valid values are true if call is cross sim call else false.
     */
    public static final String EXTRA_IS_CROSS_SIM_CALL =
            "android.telephony.ims.extra.IS_CROSS_SIM_CALL";

    /** @hide */
    public int mServiceType;
    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public int mCallType;
    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public @CallRestrictCause int mRestrictCause = CALL_RESTRICT_CAUSE_NONE;

    /**
     * The VERSTAT for an incoming call's phone number.
     */
    private @VerificationStatus int mCallerNumberVerificationStatus;

    /**
     * Indicates that the network could not perform verification.
     */
    public static final int VERIFICATION_STATUS_NOT_VERIFIED = 0;

    /**
     * Indicates that verification by the network passed.  This indicates there is a high likelihood
     * that the call originated from a valid source.
     */
    public static final int VERIFICATION_STATUS_PASSED = 1;

    /**
     * Indicates that verification by the network failed.  This indicates there is a high likelihood
     * that the call did not originate from a valid source.
     */
    public static final int VERIFICATION_STATUS_FAILED = 2;

    /**@hide*/
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "VERIFICATION_STATUS_", value = {
            VERIFICATION_STATUS_NOT_VERIFIED,
            VERIFICATION_STATUS_PASSED,
            VERIFICATION_STATUS_FAILED
    })
    public @interface VerificationStatus {}

    /**
     * The emergency service categories, only valid if {@link #getServiceType} returns
     * {@link #SERVICE_TYPE_EMERGENCY}
     *
     * If valid, the value is the bitwise-OR combination of the following constants:
     * <ol>
     * <li>{@link EmergencyNumber#EMERGENCY_SERVICE_CATEGORY_UNSPECIFIED} </li>
     * <li>{@link EmergencyNumber#EMERGENCY_SERVICE_CATEGORY_POLICE} </li>
     * <li>{@link EmergencyNumber#EMERGENCY_SERVICE_CATEGORY_AMBULANCE} </li>
     * <li>{@link EmergencyNumber#EMERGENCY_SERVICE_CATEGORY_FIRE_BRIGADE} </li>
     * <li>{@link EmergencyNumber#EMERGENCY_SERVICE_CATEGORY_MARINE_GUARD} </li>
     * <li>{@link EmergencyNumber#EMERGENCY_SERVICE_CATEGORY_MOUNTAIN_RESCUE} </li>
     * <li>{@link EmergencyNumber#EMERGENCY_SERVICE_CATEGORY_MIEC} </li>
     * <li>{@link EmergencyNumber#EMERGENCY_SERVICE_CATEGORY_AIEC} </li>
     * </ol>
     *
     * Reference: 3gpp 23.167, Section 6 - Functional description;
     *            3gpp 22.101, Section 10 - Emergency Calls.
     */
    private @EmergencyServiceCategories int mEmergencyServiceCategories =
            EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_UNSPECIFIED;

    /**
     * The emergency Uniform Resource Names (URN), only valid if {@link #getServiceType} returns
     * {@link #SERVICE_TYPE_EMERGENCY}.
     *
     * Reference: 3gpp 24.503, Section 5.1.6.8.1 - General;
     *            3gpp 22.101, Section 10 - Emergency Calls.
     */
    private List<String> mEmergencyUrns = new ArrayList<>();

    /**
     * The emergency call routing, only valid if {@link #getServiceType} returns
     * {@link #SERVICE_TYPE_EMERGENCY}
     *
     * If valid, the value is any of the following constants:
     * <ol>
     * <li>{@link EmergencyNumber#EMERGENCY_CALL_ROUTING_UNKNOWN} </li>
     * <li>{@link EmergencyNumber#EMERGENCY_CALL_ROUTING_NORMAL} </li>
     * <li>{@link EmergencyNumber#EMERGENCY_CALL_ROUTING_EMERGENCY} </li>
     * </ol>
     */
    private @EmergencyCallRouting int mEmergencyCallRouting =
            EmergencyNumber.EMERGENCY_CALL_ROUTING_UNKNOWN;

    /** Indicates if the call is for testing purpose */
    private boolean mEmergencyCallTesting = false;

    /** Indicates if we have known the intent of the user for the call is emergency */
    private boolean mHasKnownUserIntentEmergency = false;

    private Set<RtpHeaderExtensionType> mAcceptedRtpHeaderExtensionTypes = new ArraySet<>();

    /**
     * Extras associated with this {@link ImsCallProfile}.
     * <p>
     * Valid data types include:
     * <ul>
     *     <li>{@link Integer} (and int)</li>
     *     <li>{@link Long} (and long)</li>
     *     <li>{@link Double} (and double)</li>
     *     <li>{@link String}</li>
     *     <li>{@code int[]}</li>
     *     <li>{@code long[]}</li>
     *     <li>{@code double[]}</li>
     *     <li>{@code String[]}</li>
     *     <li>{@link android.os.PersistableBundle}</li>
     *     <li>{@link Boolean} (and boolean)</li>
     *     <li>{@code boolean[]}</li>
     *     <li>Other {@link Parcelable} classes in the {@code android.*} namespace.</li>
     * </ul>
     * <p>
     * Invalid types will be removed when the {@link ImsCallProfile} is parceled for transmit across
     * a {@link android.os.Binder}.
     */
    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public Bundle mCallExtras;
    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public ImsStreamMediaProfile mMediaProfile;

    /** @hide */
    public ImsCallProfile(Parcel in) {
        readFromParcel(in);
    }

    /**
     * Default Constructor that initializes the call profile with service type
     * {@link #SERVICE_TYPE_NORMAL} and call type {@link #CALL_TYPE_VIDEO_N_VOICE}
     */
    public ImsCallProfile() {
        mServiceType = SERVICE_TYPE_NORMAL;
        mCallType = CALL_TYPE_VOICE_N_VIDEO;
        mCallExtras = new Bundle();
        mMediaProfile = new ImsStreamMediaProfile();
    }

    /**
     * Constructor.
     *
     * @param serviceType the service type for the call. Can be one of the following:
     *                    {@link #SERVICE_TYPE_NONE},
     *                    {@link #SERVICE_TYPE_NORMAL},
     *                    {@link #SERVICE_TYPE_EMERGENCY}
     * @param callType the call type. Can be one of the following:
     *                 {@link #CALL_TYPE_VOICE_N_VIDEO},
     *                 {@link #CALL_TYPE_VOICE},
     *                 {@link #CALL_TYPE_VIDEO_N_VOICE},
     *                 {@link #CALL_TYPE_VT},
     *                 {@link #CALL_TYPE_VT_TX},
     *                 {@link #CALL_TYPE_VT_RX},
     *                 {@link #CALL_TYPE_VT_NODIR},
     *                 {@link #CALL_TYPE_VS},
     *                 {@link #CALL_TYPE_VS_TX},
     *                 {@link #CALL_TYPE_VS_RX}
     */
    public ImsCallProfile(int serviceType, int callType) {
        mServiceType = serviceType;
        mCallType = callType;
        mCallExtras = new Bundle();
        mMediaProfile = new ImsStreamMediaProfile();
    }

    /**
     * Constructor.
     *
     * @param serviceType the service type for the call. Can be one of the following:
     *                    {@link #SERVICE_TYPE_NONE},
     *                    {@link #SERVICE_TYPE_NORMAL},
     *                    {@link #SERVICE_TYPE_EMERGENCY}
     * @param callType the call type. Can be one of the following:
     *                 {@link #CALL_TYPE_VOICE_N_VIDEO},
     *                 {@link #CALL_TYPE_VOICE},
     *                 {@link #CALL_TYPE_VIDEO_N_VOICE},
     *                 {@link #CALL_TYPE_VT},
     *                 {@link #CALL_TYPE_VT_TX},
     *                 {@link #CALL_TYPE_VT_RX},
     *                 {@link #CALL_TYPE_VT_NODIR},
     *                 {@link #CALL_TYPE_VS},
     *                 {@link #CALL_TYPE_VS_TX},
     *                 {@link #CALL_TYPE_VS_RX}
     * @param callExtras A bundle with the call extras.
     * @param mediaProfile The IMS stream media profile.
     */
    public ImsCallProfile(int serviceType, int callType, Bundle callExtras,
            ImsStreamMediaProfile mediaProfile) {
        mServiceType = serviceType;
        mCallType = callType;
        mCallExtras = callExtras;
        mMediaProfile = mediaProfile;
    }

    public String getCallExtra(String name) {
        return getCallExtra(name, "");
    }

    public String getCallExtra(String name, String defaultValue) {
        if (mCallExtras == null) {
            return defaultValue;
        }

        return mCallExtras.getString(name, defaultValue);
    }

    public boolean getCallExtraBoolean(String name) {
        return getCallExtraBoolean(name, false);
    }

    public boolean getCallExtraBoolean(String name, boolean defaultValue) {
        if (mCallExtras == null) {
            return defaultValue;
        }

        return mCallExtras.getBoolean(name, defaultValue);
    }

    public int getCallExtraInt(String name) {
        return getCallExtraInt(name, -1);
    }

    public int getCallExtraInt(String name, int defaultValue) {
        if (mCallExtras == null) {
            return defaultValue;
        }

        return mCallExtras.getInt(name, defaultValue);
    }

    /**
     * Get the call extras (Parcelable), given the extra name.
     * @param name call extra name
     * @return the corresponding call extra Parcelable or null if not applicable
     */
    @Nullable
    public <T extends Parcelable> T getCallExtraParcelable(@Nullable String name) {
        if (mCallExtras != null) {
            return mCallExtras.getParcelable(name);
        }
        return null;
    }

    public void setCallExtra(String name, String value) {
        if (mCallExtras != null) {
            mCallExtras.putString(name, value);
        }
    }

    public void setCallExtraBoolean(String name, boolean value) {
        if (mCallExtras != null) {
            mCallExtras.putBoolean(name, value);
        }
    }

    public void setCallExtraInt(String name, int value) {
        if (mCallExtras != null) {
            mCallExtras.putInt(name, value);
        }
    }

    /**
     * Set the call extra value (Parcelable), given the call extra name.
     *
     * Note that the {@link Parcelable} provided must be a class defined in the Android API surface,
     * as opposed to a class defined by your app.
     *
     * @param name call extra name
     * @param parcelable call extra value
     */
    public void setCallExtraParcelable(@NonNull String name, @NonNull Parcelable parcelable) {
        if (mCallExtras != null) {
            mCallExtras.putParcelable(name, parcelable);
        }
    }

    /**
     * Set the call restrict cause, which provides the reason why a call has been restricted from
     * using High Definition media.
     */
    public void setCallRestrictCause(@CallRestrictCause int cause) {
        mRestrictCause = cause;
    }

    public void updateCallType(ImsCallProfile profile) {
        mCallType = profile.mCallType;
    }

    public void updateCallExtras(ImsCallProfile profile) {
        mCallExtras.clear();
        mCallExtras = (Bundle) profile.mCallExtras.clone();
    }

    /**
     * Updates the media profile for the call.
     *
     * @param profile Call profile with new media profile.
     */
    public void updateMediaProfile(ImsCallProfile profile) {
        mMediaProfile = profile.mMediaProfile;
    }

    /**
     * Sets the verification status for the phone number of an incoming call as identified in
     * ATIS-1000082.
     * <p>
     * The ImsService should parse the verstat information from the SIP INVITE headers for the call
     * to determine this information.  It is typically found in the P-Asserted-Identity OR From
     * header fields.
     * @param callerNumberVerificationStatus the new verification status.
     */
    public void setCallerNumberVerificationStatus(
            @VerificationStatus int callerNumberVerificationStatus) {
        mCallerNumberVerificationStatus = callerNumberVerificationStatus;
    }

    /**
     * Gets the verification status for the phone number of an incoming call as identified in
     * ATIS-1000082.
     * @return the verification status.
     */
    public @VerificationStatus int getCallerNumberVerificationStatus() {
        return mCallerNumberVerificationStatus;
    }

    @NonNull
    @Override
    public String toString() {
        return "{ serviceType=" + mServiceType
                + ", callType=" + mCallType
                + ", restrictCause=" + mRestrictCause
                + ", mediaProfile=" + (mMediaProfile != null ? mMediaProfile.toString() : "null")
                + ", emergencyServiceCategories=" + mEmergencyServiceCategories
                + ", emergencyUrns=" + mEmergencyUrns
                + ", emergencyCallRouting=" + mEmergencyCallRouting
                + ", emergencyCallTesting=" + mEmergencyCallTesting
                + ", hasKnownUserIntentEmergency=" + mHasKnownUserIntentEmergency
                + ", mRestrictCause=" + mRestrictCause
                + ", mCallerNumberVerstat= " + mCallerNumberVerificationStatus
                + ", mAcceptedRtpHeaderExtensions= " + mAcceptedRtpHeaderExtensionTypes
                + " }";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        Bundle filteredExtras = maybeCleanseExtras(mCallExtras);
        out.writeInt(mServiceType);
        out.writeInt(mCallType);
        out.writeBundle(filteredExtras);
        out.writeParcelable(mMediaProfile, 0);
        out.writeInt(mEmergencyServiceCategories);
        out.writeStringList(mEmergencyUrns);
        out.writeInt(mEmergencyCallRouting);
        out.writeBoolean(mEmergencyCallTesting);
        out.writeBoolean(mHasKnownUserIntentEmergency);
        out.writeInt(mRestrictCause);
        out.writeInt(mCallerNumberVerificationStatus);
        out.writeArray(mAcceptedRtpHeaderExtensionTypes.toArray());
    }

    private void readFromParcel(Parcel in) {
        mServiceType = in.readInt();
        mCallType = in.readInt();
        mCallExtras = in.readBundle();
        mMediaProfile = in.readParcelable(ImsStreamMediaProfile.class.getClassLoader(), android.telephony.ims.ImsStreamMediaProfile.class);
        mEmergencyServiceCategories = in.readInt();
        mEmergencyUrns = in.createStringArrayList();
        mEmergencyCallRouting = in.readInt();
        mEmergencyCallTesting = in.readBoolean();
        mHasKnownUserIntentEmergency = in.readBoolean();
        mRestrictCause = in.readInt();
        mCallerNumberVerificationStatus = in.readInt();
        Object[] accepted = in.readArray(RtpHeaderExtensionType.class.getClassLoader(),
                RtpHeaderExtensionType.class);
        mAcceptedRtpHeaderExtensionTypes = Arrays.stream(accepted)
                .map(o -> (RtpHeaderExtensionType) o).collect(Collectors.toSet());
    }

    public static final @android.annotation.NonNull Creator<ImsCallProfile> CREATOR =
            new Creator<ImsCallProfile>() {
        @Override
        public ImsCallProfile createFromParcel(Parcel in) {
            return new ImsCallProfile(in);
        }

        @Override
        public ImsCallProfile[] newArray(int size) {
            return new ImsCallProfile[size];
        }
    };

    public int getServiceType() {
        return mServiceType;
    }

    public int getCallType() {
        return mCallType;
    }

    /**
     * @return The call restrict cause, which provides the reason why a call has been restricted
     * from using High Definition media.
     */
    public @CallRestrictCause int getRestrictCause() {
        return mRestrictCause;
    }

    public Bundle getCallExtras() {
        return mCallExtras;
    }

    /**
     * Get the proprietary extras set for this ImsCallProfile.
     * @return A {@link Bundle} containing proprietary call extras that were not set by the
     * platform.
     */
    public @NonNull Bundle getProprietaryCallExtras() {
        if (mCallExtras == null) {
            return new Bundle();
        }
        Bundle proprietaryExtras = mCallExtras.getBundle(EXTRA_OEM_EXTRAS);
        if (proprietaryExtras == null) {
            return new Bundle();
        }
        // Make a copy so users do not accidentally change this copy of the extras.
        return new Bundle(proprietaryExtras);
    }

    public ImsStreamMediaProfile getMediaProfile() {
        return mMediaProfile;
    }

    /**
     * Converts from the call types defined in {@link ImsCallProfile} to the
     * video state values defined in {@link VideoProfile}.
     *
     * @param callProfile The call profile.
     * @return The video state.
     */
    public static int getVideoStateFromImsCallProfile(ImsCallProfile callProfile) {
        int videostate = getVideoStateFromCallType(callProfile.mCallType);
        if (callProfile.isVideoPaused() && !VideoProfile.isAudioOnly(videostate)) {
            videostate |= VideoProfile.STATE_PAUSED;
        } else {
            videostate &= ~VideoProfile.STATE_PAUSED;
        }
        return videostate;
    }

    /**
     * Translates a {@link ImsCallProfile} {@code CALL_TYPE_*} constant into a video state.
     * @param callType The call type.
     * @return The video state.
     */
    public static int getVideoStateFromCallType(int callType) {
        int videostate = VideoProfile.STATE_AUDIO_ONLY;
        switch (callType) {
            case CALL_TYPE_VT_TX:
                videostate = VideoProfile.STATE_TX_ENABLED;
                break;
            case CALL_TYPE_VT_RX:
                videostate = VideoProfile.STATE_RX_ENABLED;
                break;
            case CALL_TYPE_VT:
                videostate = VideoProfile.STATE_BIDIRECTIONAL;
                break;
            case CALL_TYPE_VOICE:
                videostate = VideoProfile.STATE_AUDIO_ONLY;
                break;
            default:
                videostate = VideoProfile.STATE_AUDIO_ONLY;
                break;
        }
        return videostate;
    }

    /**
     * Converts from the video state values defined in {@link VideoProfile}
     * to the call types defined in {@link ImsCallProfile}.
     *
     * @param videoState The video state.
     * @return The call type.
     */
    public static int getCallTypeFromVideoState(int videoState) {
        boolean videoTx = isVideoStateSet(videoState, VideoProfile.STATE_TX_ENABLED);
        boolean videoRx = isVideoStateSet(videoState, VideoProfile.STATE_RX_ENABLED);
        boolean isPaused = isVideoStateSet(videoState, VideoProfile.STATE_PAUSED);
        if (isPaused) {
            return ImsCallProfile.CALL_TYPE_VT_NODIR;
        } else if (videoTx && !videoRx) {
            return ImsCallProfile.CALL_TYPE_VT_TX;
        } else if (!videoTx && videoRx) {
            return ImsCallProfile.CALL_TYPE_VT_RX;
        } else if (videoTx && videoRx) {
            return ImsCallProfile.CALL_TYPE_VT;
        }
        return ImsCallProfile.CALL_TYPE_VOICE;
    }

    /**
     * Badly named old method, kept for compatibility.
     * See {@link #presentationToOir(int)}.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static int presentationToOIR(int presentation) {
        switch (presentation) {
            case PhoneConstants.PRESENTATION_RESTRICTED:
                return ImsCallProfile.OIR_PRESENTATION_RESTRICTED;
            case PhoneConstants.PRESENTATION_ALLOWED:
                return ImsCallProfile.OIR_PRESENTATION_NOT_RESTRICTED;
            case PhoneConstants.PRESENTATION_PAYPHONE:
                return ImsCallProfile.OIR_PRESENTATION_PAYPHONE;
            case PhoneConstants.PRESENTATION_UNKNOWN:
                return ImsCallProfile.OIR_PRESENTATION_UNKNOWN;
            case PhoneConstants.PRESENTATION_UNAVAILABLE:
                return ImsCallProfile.OIR_PRESENTATION_UNAVAILABLE;
            default:
                return ImsCallProfile.OIR_DEFAULT;
        }
    }

    /**
     * Translate presentation value to OIR value
     * @param presentation
     * @return OIR values
     */
    public static int presentationToOir(int presentation) {
        return presentationToOIR(presentation);
    }

    /**
     * Translate OIR value to presentation value
     * @param oir value
     * @return presentation value
     * @hide
     */
    public static int OIRToPresentation(int oir) {
        switch(oir) {
            case ImsCallProfile.OIR_PRESENTATION_RESTRICTED:
                return PhoneConstants.PRESENTATION_RESTRICTED;
            case ImsCallProfile.OIR_PRESENTATION_NOT_RESTRICTED:
                return PhoneConstants.PRESENTATION_ALLOWED;
            case ImsCallProfile.OIR_PRESENTATION_PAYPHONE:
                return PhoneConstants.PRESENTATION_PAYPHONE;
            case ImsCallProfile.OIR_PRESENTATION_UNAVAILABLE:
                return PhoneConstants.PRESENTATION_UNAVAILABLE;
            case ImsCallProfile.OIR_PRESENTATION_UNKNOWN:
                return PhoneConstants.PRESENTATION_UNKNOWN;
            default:
                return PhoneConstants.PRESENTATION_UNKNOWN;
        }
    }

    /**
     * Checks if video call is paused
     * @return true if call is video paused
     */
    public boolean isVideoPaused() {
        return mMediaProfile.mVideoDirection == ImsStreamMediaProfile.DIRECTION_INACTIVE;
    }

    /**
     * Determines if the {@link ImsCallProfile} represents a video call.
     *
     * @return {@code true} if the profile is for a video call, {@code false} otherwise.
     */
    public boolean isVideoCall() {
        return VideoProfile.isVideo(getVideoStateFromCallType(mCallType));
    }

    /**
     * Cleanses a {@link Bundle} to ensure that it contains only data of type:
     * 1. Primitive data types (e.g. int, bool, and other values determined by
     * {@link android.os.PersistableBundle#isValidType(Object)}).
     * 2. Other Bundles.
     * 3. {@link Parcelable} objects in the {@code android.*} namespace.
     * @param extras the source {@link Bundle}
     * @return where all elements are valid types the source {@link Bundle} is returned unmodified,
     *      otherwise a copy of the {@link Bundle} with the invalid elements is returned.
     */
    private Bundle maybeCleanseExtras(Bundle extras) {
        if (extras == null) {
            return null;
        }

        int startSize = extras.size();
        Bundle filtered = TelephonyUtils.filterValues(extras);
        int endSize = filtered.size();
        if (startSize != endSize) {
            Log.i(TAG, "maybeCleanseExtras: " + (startSize - endSize) + " extra values were "
                    + "removed - only primitive types and system parcelables are permitted.");
        }
        return filtered;
    }

    /**
     * Determines if a video state is set in a video state bit-mask.
     *
     * @param videoState The video state bit mask.
     * @param videoStateToCheck The particular video state to check.
     * @return True if the video state is set in the bit-mask.
     */
    private static boolean isVideoStateSet(int videoState, int videoStateToCheck) {
        return (videoState & videoStateToCheck) == videoStateToCheck;
    }

    /**
     * Set the emergency number information. The set value is valid
     * only if {@link #getServiceType} returns {@link #SERVICE_TYPE_EMERGENCY}
     *
     * Reference: 3gpp 23.167, Section 6 - Functional description;
     *            3gpp 24.503, Section 5.1.6.8.1 - General;
     *            3gpp 22.101, Section 10 - Emergency Calls.
     *
     * @hide
     */
    public void setEmergencyCallInfo(EmergencyNumber num, boolean hasKnownUserIntentEmergency) {
        setEmergencyServiceCategories(num.getEmergencyServiceCategoryBitmaskInternalDial());
        setEmergencyUrns(num.getEmergencyUrns());
        setEmergencyCallRouting(num.getEmergencyCallRouting());
        setEmergencyCallTesting(num.getEmergencyNumberSourceBitmask()
                == EmergencyNumber.EMERGENCY_NUMBER_SOURCE_TEST);
        setHasKnownUserIntentEmergency(hasKnownUserIntentEmergency);
    }

    /**
     * Set the emergency service categories. The set value is valid only if
     * {@link #getServiceType} returns {@link #SERVICE_TYPE_EMERGENCY}
     *
     * If valid, the value is the bitwise-OR combination of the following constants:
     * <ol>
     * <li>{@link EmergencyNumber#EMERGENCY_SERVICE_CATEGORY_UNSPECIFIED} </li>
     * <li>{@link EmergencyNumber#EMERGENCY_SERVICE_CATEGORY_POLICE} </li>
     * <li>{@link EmergencyNumber#EMERGENCY_SERVICE_CATEGORY_AMBULANCE} </li>
     * <li>{@link EmergencyNumber#EMERGENCY_SERVICE_CATEGORY_FIRE_BRIGADE} </li>
     * <li>{@link EmergencyNumber#EMERGENCY_SERVICE_CATEGORY_MARINE_GUARD} </li>
     * <li>{@link EmergencyNumber#EMERGENCY_SERVICE_CATEGORY_MOUNTAIN_RESCUE} </li>
     * <li>{@link EmergencyNumber#EMERGENCY_SERVICE_CATEGORY_MIEC} </li>
     * <li>{@link EmergencyNumber#EMERGENCY_SERVICE_CATEGORY_AIEC} </li>
     * </ol>
     *
     * Reference: 3gpp 23.167, Section 6 - Functional description;
     *            3gpp 22.101, Section 10 - Emergency Calls.
     */
    @VisibleForTesting
    public void setEmergencyServiceCategories(
            @EmergencyServiceCategories int emergencyServiceCategories) {
        mEmergencyServiceCategories = emergencyServiceCategories;
    }

    /**
     * Set the emergency Uniform Resource Names (URN), only valid if {@link #getServiceType}
     * returns {@link #SERVICE_TYPE_EMERGENCY}.
     *
     * Reference: 3gpp 24.503, Section 5.1.6.8.1 - General;
     *            3gpp 22.101, Section 10 - Emergency Calls.
     */
    @VisibleForTesting
    public void setEmergencyUrns(@NonNull List<String> emergencyUrns) {
        mEmergencyUrns = emergencyUrns;
    }

    /**
     * Set the emergency call routing, only valid if {@link #getServiceType} returns
     * {@link #SERVICE_TYPE_EMERGENCY}
     *
     * If valid, the value is any of the following constants:
     * <ol>
     * <li>{@link EmergencyNumber#EMERGENCY_CALL_ROUTING_UNKNOWN} </li>
     * <li>{@link EmergencyNumber#EMERGENCY_CALL_ROUTING_NORMAL} </li>
     * <li>{@link EmergencyNumber#EMERGENCY_CALL_ROUTING_EMERGENCY} </li>
     * </ol>
     */
    @VisibleForTesting
    public void setEmergencyCallRouting(@EmergencyCallRouting int emergencyCallRouting) {
        mEmergencyCallRouting = emergencyCallRouting;
    }

    /**
     * Set if this is for testing emergency call, only valid if {@link #getServiceType} returns
     * {@link #SERVICE_TYPE_EMERGENCY}.
     */
    @VisibleForTesting
    public void setEmergencyCallTesting(boolean isTesting) {
        mEmergencyCallTesting = isTesting;
    }

    /**
     * Set if we have known the user intent of the call is emergency.
     *
     * This is only used to specify when the dialed number is ambiguous when it can be identified
     * as both emergency number and any other non-emergency number; e.g. in some situation, 611
     * could be both an emergency number in a country and a non-emergency number of a carrier's
     * customer service hotline.
     */
    @VisibleForTesting
    public void setHasKnownUserIntentEmergency(boolean hasKnownUserIntentEmergency) {
        mHasKnownUserIntentEmergency = hasKnownUserIntentEmergency;
    }

    /**
     * Get the emergency service categories, only valid if {@link #getServiceType} returns
     * {@link #SERVICE_TYPE_EMERGENCY}
     *
     * @return the emergency service categories,
     *
     * If valid, the value is the bitwise-OR combination of the following constants:
     * <ol>
     * <li>{@link EmergencyNumber#EMERGENCY_SERVICE_CATEGORY_UNSPECIFIED} </li>
     * <li>{@link EmergencyNumber#EMERGENCY_SERVICE_CATEGORY_POLICE} </li>
     * <li>{@link EmergencyNumber#EMERGENCY_SERVICE_CATEGORY_AMBULANCE} </li>
     * <li>{@link EmergencyNumber#EMERGENCY_SERVICE_CATEGORY_FIRE_BRIGADE} </li>
     * <li>{@link EmergencyNumber#EMERGENCY_SERVICE_CATEGORY_MARINE_GUARD} </li>
     * <li>{@link EmergencyNumber#EMERGENCY_SERVICE_CATEGORY_MOUNTAIN_RESCUE} </li>
     * <li>{@link EmergencyNumber#EMERGENCY_SERVICE_CATEGORY_MIEC} </li>
     * <li>{@link EmergencyNumber#EMERGENCY_SERVICE_CATEGORY_AIEC} </li>
     * </ol>
     *
     * Reference: 3gpp 23.167, Section 6 - Functional description;
     *            3gpp 22.101, Section 10 - Emergency Calls.
     */
    public @EmergencyServiceCategories int getEmergencyServiceCategories() {
        return mEmergencyServiceCategories;
    }

    /**
     * Get the emergency Uniform Resource Names (URN), only valid if {@link #getServiceType}
     * returns {@link #SERVICE_TYPE_EMERGENCY}.
     *
     * Reference: 3gpp 24.503, Section 5.1.6.8.1 - General;
     *            3gpp 22.101, Section 10 - Emergency Calls.
     */
    public @NonNull List<String> getEmergencyUrns() {
        return mEmergencyUrns;
    }

    /**
     * Get the emergency call routing, only valid if {@link #getServiceType} returns
     * {@link #SERVICE_TYPE_EMERGENCY}
     *
     * If valid, the value is any of the following constants:
     * <ol>
     * <li>{@link EmergencyNumber#EMERGENCY_CALL_ROUTING_UNKNOWN} </li>
     * <li>{@link EmergencyNumber#EMERGENCY_CALL_ROUTING_NORMAL} </li>
     * <li>{@link EmergencyNumber#EMERGENCY_CALL_ROUTING_EMERGENCY} </li>
     * </ol>
     */
    public @EmergencyCallRouting int getEmergencyCallRouting() {
        return mEmergencyCallRouting;
    }

    /**
     * Get if the emergency call is for testing purpose.
     */
    public boolean isEmergencyCallTesting() {
        return mEmergencyCallTesting;
    }

    /**
     * Checks if we have known the user intent of the call is emergency.
     *
     * This is only used to specify when the dialed number is ambiguous when it can be identified
     * as both emergency number and any other non-emergency number; e.g. in some situation, 611
     * could be both an emergency number in a country and a non-emergency number of a carrier's
     * customer service hotline.
     */
    public boolean hasKnownUserIntentEmergency() {
        return mHasKnownUserIntentEmergency;
    }

    /**
     * Gets the {@link RtpHeaderExtensionType}s which have been accepted by both ends of the call.
     * <p>
     * According to RFC8285, RTP header extensions available to a call are determined using the
     * offer/accept phase of the SDP protocol (see RFC4566).
     * <p>
     * The offered header extension types supported by the framework and exposed to the
     * {@link ImsService} via {@link MmTelFeature#changeOfferedRtpHeaderExtensionTypes(Set)}.
     *
     * @return the {@link RtpHeaderExtensionType}s which were accepted by the other end of the call.
     */
    public @NonNull Set<RtpHeaderExtensionType> getAcceptedRtpHeaderExtensionTypes() {
        return mAcceptedRtpHeaderExtensionTypes;
    }

    /**
     * Sets the accepted {@link RtpHeaderExtensionType}s for this call.
     * <p>
     * According to RFC8285, RTP header extensions available to a call are determined using the
     * offer/accept phase of the SDP protocol (see RFC4566).
     *
     * @param rtpHeaderExtensions
     */
    public void setAcceptedRtpHeaderExtensionTypes(@NonNull Set<RtpHeaderExtensionType>
            rtpHeaderExtensions) {
        mAcceptedRtpHeaderExtensionTypes.clear();
        mAcceptedRtpHeaderExtensionTypes.addAll(rtpHeaderExtensions);
    }
}
