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
package android.telephony.data;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringDef;
import android.annotation.SystemApi;
import android.content.ContentValues;
import android.database.Cursor;
import android.hardware.radio.data.ApnTypes;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.provider.Telephony;
import android.provider.Telephony.Carriers;
import android.telephony.Annotation.NetworkType;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.telephony.flags.Flags;
import com.android.telephony.Rlog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * An Access Point Name (APN) configuration for a carrier data connection.
 *
 * <p>The APN provides configuration to connect a cellular network device to an IP data network. A
 * carrier uses the name, type and other configuration in an {@code APNSetting} to decide which IP
 * address to assign, any security methods to apply, and how the device might be connected to
 * private networks.
 *
 * <p>Use {@link ApnSetting.Builder} to create new instances.
 */
public class ApnSetting implements Parcelable {

    private static final String LOG_TAG = "ApnSetting";
    private static final boolean VDBG = false;

    private static final String V2_FORMAT_REGEX = "^\\[ApnSettingV2\\]\\s*";
    private static final String V3_FORMAT_REGEX = "^\\[ApnSettingV3\\]\\s*";
    private static final String V4_FORMAT_REGEX = "^\\[ApnSettingV4\\]\\s*";
    private static final String V5_FORMAT_REGEX = "^\\[ApnSettingV5\\]\\s*";
    private static final String V6_FORMAT_REGEX = "^\\[ApnSettingV6\\]\\s*";
    private static final String V7_FORMAT_REGEX = "^\\[ApnSettingV7\\]\\s*";

    /**
     * Default value for mtu if it's not set. Moved from PhoneConstants.
     * @hide
     */
    public static final int UNSET_MTU = 0;
    private static final int UNSPECIFIED_INT = -1;
    private static final String UNSPECIFIED_STRING = "";

    /**
     * APN type for none. Should only be used for initialization.
     * @hide
     */
    public static final int TYPE_NONE = ApnTypes.NONE;
    /**
     * APN type for all APNs (except wild-cardable types).
     * @hide
     */
    public static final int TYPE_ALL = ApnTypes.DEFAULT | ApnTypes.HIPRI | ApnTypes.MMS
            | ApnTypes.SUPL | ApnTypes.DUN | ApnTypes.FOTA | ApnTypes.IMS | ApnTypes.CBS;
    /** APN type for default data traffic. */
    public static final int TYPE_DEFAULT = ApnTypes.DEFAULT | ApnTypes.HIPRI;
    /** APN type for MMS traffic. */
    public static final int TYPE_MMS = ApnTypes.MMS;
    /** APN type for SUPL assisted GPS. */
    public static final int TYPE_SUPL = ApnTypes.SUPL;
    /** APN type for DUN traffic. */
    public static final int TYPE_DUN = ApnTypes.DUN;
    /** APN type for HiPri traffic. */
    public static final int TYPE_HIPRI = ApnTypes.HIPRI;
    /** APN type for accessing the carrier's FOTA portal, used for over the air updates. */
    public static final int TYPE_FOTA = ApnTypes.FOTA;
    /** APN type for IMS. */
    public static final int TYPE_IMS = ApnTypes.IMS;
    /** APN type for CBS. */
    public static final int TYPE_CBS = ApnTypes.CBS;
    /** APN type for IA Initial Attach APN. */
    public static final int TYPE_IA = ApnTypes.IA;
    /**
     * APN type for Emergency PDN. This is not an IA apn, but is used
     * for access to carrier services in an emergency call situation.
     */
    public static final int TYPE_EMERGENCY = ApnTypes.EMERGENCY;
    /** APN type for MCX (Mission Critical Service) where X can be PTT/Video/Data */
    public static final int TYPE_MCX = ApnTypes.MCX;
    /** APN type for XCAP. */
    public static final int TYPE_XCAP = ApnTypes.XCAP;
    /** APN type for VSIM. */
    public static final int TYPE_VSIM = ApnTypes.VSIM;
    /** APN type for BIP. */
    public static final int TYPE_BIP = ApnTypes.BIP;
    /** APN type for ENTERPRISE. */
    public static final int TYPE_ENTERPRISE = ApnTypes.ENTERPRISE;
    /** APN type for RCS (Rich Communication Services). */
    @FlaggedApi(Flags.FLAG_CARRIER_ENABLED_SATELLITE_FLAG)
    public static final int TYPE_RCS = ApnTypes.RCS;

    /** @hide */
    @IntDef(flag = true, prefix = {"TYPE_"}, value = {
            TYPE_DEFAULT,
            TYPE_MMS,
            TYPE_SUPL,
            TYPE_DUN,
            TYPE_HIPRI,
            TYPE_FOTA,
            TYPE_IMS,
            TYPE_CBS,
            TYPE_IA,
            TYPE_EMERGENCY,
            TYPE_MCX,
            TYPE_XCAP,
            TYPE_BIP,
            TYPE_VSIM,
            TYPE_ENTERPRISE,
            TYPE_RCS
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ApnType {
    }

    // Possible values for authentication types.
    /**
     * Authentication type is unknown.
     * @hide
     */
    public static final int AUTH_TYPE_UNKNOWN = -1;
    /** Authentication is not required. */
    public static final int AUTH_TYPE_NONE = 0;
    /** Authentication type for PAP. */
    public static final int AUTH_TYPE_PAP = 1;
    /** Authentication type for CHAP. */
    public static final int AUTH_TYPE_CHAP = 2;
    /** Authentication type for PAP or CHAP. */
    public static final int AUTH_TYPE_PAP_OR_CHAP = 3;

    /** @hide */
    @IntDef({
            Telephony.Carriers.SKIP_464XLAT_DEFAULT,
            Telephony.Carriers.SKIP_464XLAT_DISABLE,
            Telephony.Carriers.SKIP_464XLAT_ENABLE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Skip464XlatStatus {}

    /** @hide */
    @StringDef(value = {
            TYPE_ALL_STRING,
            TYPE_CBS_STRING,
            TYPE_DEFAULT_STRING,
            TYPE_DUN_STRING,
            TYPE_EMERGENCY_STRING,
            TYPE_FOTA_STRING,
            TYPE_HIPRI_STRING,
            TYPE_IA_STRING,
            TYPE_IMS_STRING,
            TYPE_MCX_STRING,
            TYPE_MMS_STRING,
            TYPE_SUPL_STRING,
            TYPE_XCAP_STRING,
            TYPE_VSIM_STRING,
            TYPE_BIP_STRING,
            TYPE_ENTERPRISE_STRING,
    }, prefix = "TYPE_", suffix = "_STRING")
    @Retention(RetentionPolicy.SOURCE)
    public @interface ApnTypeString {}

    /**
     * APN types for data connections.  These are usage categories for an APN
     * entry.  One APN entry may support multiple APN types, eg, a single APN
     * may service regular internet traffic ("default") as well as MMS-specific
     * connections.<br/>
     * APN_TYPE_ALL is a special type to indicate that this APN entry can
     * service all data connections.
     *
     * @hide
     */
    @SystemApi
    public static final String TYPE_ALL_STRING = "*";

    /**
     * APN type for default data traffic
     *
     * Note: String representations of APN types are intended for system apps to communicate with
     * modem components or carriers. Non-system apps should use the integer variants instead.
     * @hide
     */
    @SystemApi
    public static final String TYPE_DEFAULT_STRING = "default";


    /**
     * APN type for MMS (Multimedia Messaging Service) traffic.
     *
     * Note: String representations of APN types are intended for system apps to communicate with
     * modem components or carriers. Non-system apps should use the integer variants instead.
     * @hide
     */
    @SystemApi
    public static final String TYPE_MMS_STRING = "mms";


    /**
     * APN type for SUPL (Secure User Plane Location) assisted GPS.
     *
     * Note: String representations of APN types are intended for system apps to communicate with
     * modem components or carriers. Non-system apps should use the integer variants instead.
     * @hide
     */
    @SystemApi
    public static final String TYPE_SUPL_STRING = "supl";

    /**
     * APN type for DUN (Dial-up networking) traffic
     *
     * Note: String representations of APN types are intended for system apps to communicate with
     * modem components or carriers. Non-system apps should use the integer variants instead.
     * @hide
     */
    @SystemApi
    public static final String TYPE_DUN_STRING = "dun";

    /**
     * APN type for high-priority traffic
     *
     * Note: String representations of APN types are intended for system apps to communicate with
     * modem components or carriers. Non-system apps should use the integer variants instead.
     * @hide
     */
    @SystemApi
    public static final String TYPE_HIPRI_STRING = "hipri";

    /**
     * APN type for FOTA (Firmware over-the-air) traffic.
     *
     * Note: String representations of APN types are intended for system apps to communicate with
     * modem components or carriers. Non-system apps should use the integer variants instead.
     * @hide
     */
    @SystemApi
    public static final String TYPE_FOTA_STRING = "fota";

    /**
     * APN type for IMS (IP Multimedia Subsystem) traffic.
     *
     * Note: String representations of APN types are intended for system apps to communicate with
     * modem components or carriers. Non-system apps should use the integer variants instead.
     * @hide
     */
    @SystemApi
    public static final String TYPE_IMS_STRING = "ims";

    /**
     * APN type for CBS (Carrier Branded Services) traffic.
     *
     * Note: String representations of APN types are intended for system apps to communicate with
     * modem components or carriers. Non-system apps should use the integer variants instead.
     * @hide
     */
    @SystemApi
    public static final String TYPE_CBS_STRING = "cbs";

    /**
     * APN type for the IA (Initial Attach) APN
     *
     * Note: String representations of APN types are intended for system apps to communicate with
     * modem components or carriers. Non-system apps should use the integer variants instead.
     * @hide
     */
    @SystemApi
    public static final String TYPE_IA_STRING = "ia";

    /**
     * APN type for Emergency PDN. This is not an IA apn, but is used
     * for access to carrier services in an emergency call situation.
     *
     * Note: String representations of APN types are intended for system apps to communicate with
     * modem components or carriers. Non-system apps should use the integer variants instead.
     * @hide
     */
    @SystemApi
    public static final String TYPE_EMERGENCY_STRING = "emergency";

    /**
     * APN type for Mission Critical Services.
     *
     * Note: String representations of APN types are intended for system apps to communicate with
     * modem components or carriers. Non-system apps should use the integer variants instead.
     * @hide
     */
    @SystemApi
    public static final String TYPE_MCX_STRING = "mcx";

    /**
     * APN type for XCAP (XML Configuration Access Protocol) traffic.
     *
     * Note: String representations of APN types are intended for system apps to communicate with
     * modem components or carriers. Non-system apps should use the integer variants instead.
     * @hide
     */
    @SystemApi
    public static final String TYPE_XCAP_STRING = "xcap";

    /**
     * APN type for Virtual SIM service.
     *
     * Note: String representations of APN types are intended for system apps to communicate with
     * modem components or carriers. Non-system apps should use the integer variants instead.
     * @hide
     */
    @SystemApi
    public static final String TYPE_VSIM_STRING = "vsim";

    /**
     * APN type for Bearer Independent Protocol.
     *
     * Note: String representations of APN types are intended for system apps to communicate with
     * modem components or carriers. Non-system apps should use the integer variants instead.
     * @hide
     */
    @SystemApi
    public static final String TYPE_BIP_STRING = "bip";

    /**
     * APN type for ENTERPRISE traffic.
     *
     * Note: String representations of APN types are intended for system apps to communicate with
     * modem components or carriers. Non-system apps should use the integer variants instead.
     * @hide
     */
    @SystemApi
    public static final String TYPE_ENTERPRISE_STRING = "enterprise";

    /**
     * APN type for RCS (Rich Communication Services)
     *
     * Note: String representations of APN types are intended for system apps to communicate with
     * modem components or carriers. Non-system apps should use the integer variants instead.
     * @hide
     */
    @FlaggedApi(Flags.FLAG_CARRIER_ENABLED_SATELLITE_FLAG)
    @SystemApi
    public static final String TYPE_RCS_STRING = "rcs";


    /** @hide */
    @IntDef(prefix = { "AUTH_TYPE_" }, value = {
        AUTH_TYPE_UNKNOWN,
        AUTH_TYPE_NONE,
        AUTH_TYPE_PAP,
        AUTH_TYPE_CHAP,
        AUTH_TYPE_PAP_OR_CHAP,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface AuthType {}

    // Possible values for protocol which is defined in TS 27.007 section 10.1.1.
    /** Unknown protocol.
     * @hide
     */
    public static final int PROTOCOL_UNKNOWN = -1;
    /** Internet protocol. */
    public static final int PROTOCOL_IP = 0;
    /** Internet protocol, version 6. */
    public static final int PROTOCOL_IPV6 = 1;
    /** Virtual PDP type introduced to handle dual IP stack UE capability. */
    public static final int PROTOCOL_IPV4V6 = 2;
    /** Point to point protocol. */
    public static final int PROTOCOL_PPP = 3;
    /** Transfer of Non-IP data to external packet data network. */
    public static final int PROTOCOL_NON_IP = 4;
    /** Transfer of Unstructured data to the Data Network via N6. */
    public static final int PROTOCOL_UNSTRUCTURED = 5;

    /** @hide */
    @IntDef(prefix = { "PROTOCOL_" }, value = {
        PROTOCOL_IP,
        PROTOCOL_IPV6,
        PROTOCOL_IPV4V6,
        PROTOCOL_PPP,
        PROTOCOL_NON_IP,
        PROTOCOL_UNSTRUCTURED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ProtocolType {}

    // Possible values for MVNO type.
    /**
     * Default value for MVNO type if it's not set.
     * @hide
     */
    public static final int MVNO_TYPE_UNKNOWN = -1;
    /** MVNO type for service provider name. */
    public static final int MVNO_TYPE_SPN = 0;
    /** MVNO type for IMSI. */
    public static final int MVNO_TYPE_IMSI = 1;
    /** MVNO type for group identifier level 1. */
    public static final int MVNO_TYPE_GID = 2;
    /** MVNO type for ICCID. */
    public static final int MVNO_TYPE_ICCID = 3;

    /** @hide */
    @IntDef(prefix = { "MVNO_TYPE_" }, value = {
            MVNO_TYPE_UNKNOWN,
            MVNO_TYPE_SPN,
            MVNO_TYPE_IMSI,
            MVNO_TYPE_GID,
            MVNO_TYPE_ICCID,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface MvnoType {}

    /**
     * Indicating this APN can be used when the device is using terrestrial cellular networks.
     * @hide
     */
    public static final int INFRASTRUCTURE_CELLULAR = 1 << 0;

    /**
     * Indicating this APN can be used when the device is attached to satellites.
     * @hide
     */
    public static final int INFRASTRUCTURE_SATELLITE = 1 << 1;

    /** @hide */
    @IntDef(flag = true, prefix = { "INFRASTRUCTURE_" }, value = {
            INFRASTRUCTURE_CELLULAR,
            INFRASTRUCTURE_SATELLITE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface InfrastructureBitmask {}

    private static final Map<String, Integer> APN_TYPE_STRING_MAP;
    private static final Map<Integer, String> APN_TYPE_INT_MAP;
    private static final Map<String, Integer> PROTOCOL_STRING_MAP;
    private static final Map<Integer, String> PROTOCOL_INT_MAP;
    private static final Map<String, Integer> MVNO_TYPE_STRING_MAP;
    private static final Map<Integer, String> MVNO_TYPE_INT_MAP;

    static {
        APN_TYPE_STRING_MAP = new ArrayMap<>();
        APN_TYPE_STRING_MAP.put(TYPE_ALL_STRING, TYPE_ALL);
        APN_TYPE_STRING_MAP.put(TYPE_DEFAULT_STRING, TYPE_DEFAULT);
        APN_TYPE_STRING_MAP.put(TYPE_MMS_STRING, TYPE_MMS);
        APN_TYPE_STRING_MAP.put(TYPE_SUPL_STRING, TYPE_SUPL);
        APN_TYPE_STRING_MAP.put(TYPE_DUN_STRING, TYPE_DUN);
        APN_TYPE_STRING_MAP.put(TYPE_HIPRI_STRING, TYPE_HIPRI);
        APN_TYPE_STRING_MAP.put(TYPE_FOTA_STRING, TYPE_FOTA);
        APN_TYPE_STRING_MAP.put(TYPE_IMS_STRING, TYPE_IMS);
        APN_TYPE_STRING_MAP.put(TYPE_CBS_STRING, TYPE_CBS);
        APN_TYPE_STRING_MAP.put(TYPE_IA_STRING, TYPE_IA);
        APN_TYPE_STRING_MAP.put(TYPE_EMERGENCY_STRING, TYPE_EMERGENCY);
        APN_TYPE_STRING_MAP.put(TYPE_MCX_STRING, TYPE_MCX);
        APN_TYPE_STRING_MAP.put(TYPE_XCAP_STRING, TYPE_XCAP);
        APN_TYPE_STRING_MAP.put(TYPE_ENTERPRISE_STRING, TYPE_ENTERPRISE);
        APN_TYPE_STRING_MAP.put(TYPE_VSIM_STRING, TYPE_VSIM);
        APN_TYPE_STRING_MAP.put(TYPE_BIP_STRING, TYPE_BIP);
        APN_TYPE_STRING_MAP.put(TYPE_RCS_STRING, TYPE_RCS);

        APN_TYPE_INT_MAP = new ArrayMap<>();
        APN_TYPE_INT_MAP.put(TYPE_DEFAULT, TYPE_DEFAULT_STRING);
        APN_TYPE_INT_MAP.put(TYPE_MMS, TYPE_MMS_STRING);
        APN_TYPE_INT_MAP.put(TYPE_SUPL, TYPE_SUPL_STRING);
        APN_TYPE_INT_MAP.put(TYPE_DUN, TYPE_DUN_STRING);
        APN_TYPE_INT_MAP.put(TYPE_HIPRI, TYPE_HIPRI_STRING);
        APN_TYPE_INT_MAP.put(TYPE_FOTA, TYPE_FOTA_STRING);
        APN_TYPE_INT_MAP.put(TYPE_IMS, TYPE_IMS_STRING);
        APN_TYPE_INT_MAP.put(TYPE_CBS, TYPE_CBS_STRING);
        APN_TYPE_INT_MAP.put(TYPE_IA, TYPE_IA_STRING);
        APN_TYPE_INT_MAP.put(TYPE_EMERGENCY, TYPE_EMERGENCY_STRING);
        APN_TYPE_INT_MAP.put(TYPE_MCX, TYPE_MCX_STRING);
        APN_TYPE_INT_MAP.put(TYPE_XCAP, TYPE_XCAP_STRING);
        APN_TYPE_INT_MAP.put(TYPE_ENTERPRISE, TYPE_ENTERPRISE_STRING);
        APN_TYPE_INT_MAP.put(TYPE_VSIM, TYPE_VSIM_STRING);
        APN_TYPE_INT_MAP.put(TYPE_BIP, TYPE_BIP_STRING);
        APN_TYPE_INT_MAP.put(TYPE_RCS, TYPE_RCS_STRING);

        PROTOCOL_STRING_MAP = new ArrayMap<>();
        PROTOCOL_STRING_MAP.put("IP", PROTOCOL_IP);
        PROTOCOL_STRING_MAP.put("IPV6", PROTOCOL_IPV6);
        PROTOCOL_STRING_MAP.put("IPV4V6", PROTOCOL_IPV4V6);
        PROTOCOL_STRING_MAP.put("PPP", PROTOCOL_PPP);
        PROTOCOL_STRING_MAP.put("NON-IP", PROTOCOL_NON_IP);
        PROTOCOL_STRING_MAP.put("UNSTRUCTURED", PROTOCOL_UNSTRUCTURED);

        PROTOCOL_INT_MAP = new ArrayMap<>();
        PROTOCOL_INT_MAP.put(PROTOCOL_IP, "IP");
        PROTOCOL_INT_MAP.put(PROTOCOL_IPV6, "IPV6");
        PROTOCOL_INT_MAP.put(PROTOCOL_IPV4V6, "IPV4V6");
        PROTOCOL_INT_MAP.put(PROTOCOL_PPP, "PPP");
        PROTOCOL_INT_MAP.put(PROTOCOL_NON_IP, "NON-IP");
        PROTOCOL_INT_MAP.put(PROTOCOL_UNSTRUCTURED, "UNSTRUCTURED");

        MVNO_TYPE_STRING_MAP = new ArrayMap<>();
        MVNO_TYPE_STRING_MAP.put("spn", MVNO_TYPE_SPN);
        MVNO_TYPE_STRING_MAP.put("imsi", MVNO_TYPE_IMSI);
        MVNO_TYPE_STRING_MAP.put("gid", MVNO_TYPE_GID);
        MVNO_TYPE_STRING_MAP.put("iccid", MVNO_TYPE_ICCID);

        MVNO_TYPE_INT_MAP = new ArrayMap<>();
        MVNO_TYPE_INT_MAP.put(MVNO_TYPE_SPN, "spn");
        MVNO_TYPE_INT_MAP.put(MVNO_TYPE_IMSI, "imsi");
        MVNO_TYPE_INT_MAP.put(MVNO_TYPE_GID, "gid");
        MVNO_TYPE_INT_MAP.put(MVNO_TYPE_ICCID, "iccid");
    }

    private final String mEntryName;
    private final String mApnName;
    private final String mProxyAddress;
    private final int mProxyPort;
    private final Uri mMmsc;
    private final String mMmsProxyAddress;
    private final int mMmsProxyPort;
    private final String mUser;
    private final String mPassword;
    private final int mAuthType;
    private final int mApnTypeBitmask;
    private final int mId;
    private final String mOperatorNumeric;
    private final int mProtocol;
    private final int mRoamingProtocol;
    private final int mMtuV4;
    private final int mMtuV6;
    private final boolean mCarrierEnabled;
    private final @TelephonyManager.NetworkTypeBitMask int mNetworkTypeBitmask;
    private final @TelephonyManager.NetworkTypeBitMask long mLingeringNetworkTypeBitmask;
    private final int mProfileId;
    private final boolean mPersistent;
    private final int mMaxConns;
    private final int mWaitTime;
    private final int mMaxConnsTime;
    private final int mMvnoType;
    private final String mMvnoMatchData;
    private final int mApnSetId;
    private boolean mPermanentFailed = false;
    private final int mCarrierId;
    private final int mSkip464Xlat;
    private final boolean mAlwaysOn;
    private final @InfrastructureBitmask int mInfrastructureBitmask;
    private final boolean mEsimBootstrapProvisioning;

    /**
     * Returns the default MTU (Maximum Transmission Unit) size in bytes of the IPv4 routes brought
     * up by this APN setting. Note this value will only be used when MTU size is not provided
     * in {@code DataCallResponse#getMtuV4()} during network bring up.
     *
     * @return the MTU size in bytes of the route.
     */
    public int getMtuV4() {
        return mMtuV4;
    }

    /**
     * Returns the MTU size of the IPv6 mobile interface to which the APN connected. Note this value
     * will only be used when MTU size is not provided in {@code DataCallResponse#getMtuV6()}
     * during network bring up.
     *
     * @return the MTU size in bytes of the route.
     */
    public int getMtuV6() {
        return mMtuV6;
    }

    /**
     * Returns the profile id to which the APN saved in modem.
     *
     * @return the profile id of the APN
     */
    public int getProfileId() {
        return mProfileId;
    }

    /**
     * Returns if the APN setting is persistent on the modem.
     *
     * @return {@code true} if the APN setting is persistent on the modem.
     */
    public boolean isPersistent() {
        return mPersistent;
    }

    /**
     * Returns the max connections of this APN.
     *
     * @return the max connections of this APN
     * @hide
     */
    public int getMaxConns() {
        return mMaxConns;
    }

    /**
     * Returns the wait time for retry of the APN.
     *
     * @return the wait time for retry of the APN
     * @hide
     */
    public int getWaitTime() {
        return mWaitTime;
    }

    /**
     * Returns the time to limit max connection for the APN.
     *
     * @return the time to limit max connection for the APN
     * @hide
     */
    public int getMaxConnsTime() {
        return mMaxConnsTime;
    }

    /**
     * Returns the MVNO data. Examples:
     *   "spn": A MOBILE, BEN NL
     *   "imsi": 302720x94, 2060188
     *   "gid": 4E, 33
     *   "iccid": 898603 etc..
     *
     * @return the mvno match data
     * @hide
     */
    public String getMvnoMatchData() {
        return mMvnoMatchData;
    }

    /**
     * Returns the APN set id.
     *
     * APNs that are part of the same set should be preferred together, e.g. if the
     * user selects a default APN with apnSetId=1, then we will prefer all APNs with apnSetId = 1.
     *
     * If the apnSetId = Carriers.NO_SET_SET(=0) then the APN is not part of a set.
     *
     * @return the APN set id
     * @hide
     */
    public int getApnSetId() {
        return mApnSetId;
    }

    /**
     * Indicates this APN setting is permanently failed and cannot be
     * retried by the retry manager anymore.
     *
     * @return if this APN setting is permanently failed
     * @hide
     */
    public boolean getPermanentFailed() {
        return mPermanentFailed;
    }

    /**
     * Sets if this APN setting is permanently failed.
     *
     * @param permanentFailed if this APN setting is permanently failed
     * @hide
     */
    public void setPermanentFailed(boolean permanentFailed) {
        mPermanentFailed = permanentFailed;
    }

    /**
     * Gets the human-readable name that describes the APN.
     *
     * @return the entry name for the APN
     */
    public String getEntryName() {
        return mEntryName;
    }

    /**
     * Returns the name of the APN.
     *
     * @return APN name
     */
    public String getApnName() {
        return mApnName;
    }

    /**
     * Gets the HTTP proxy address configured for the APN. The proxy address might be an IP address
     * or hostname. This method returns {@code null} if system networking (typically DNS) isn’t
     * available to resolve a hostname value—values set as IP addresses don’t have this restriction.
     * This is a known problem and will be addressed in a future release.
     *
     * @return the HTTP proxy address or {@code null} if DNS isn’t available to resolve a hostname
     * @deprecated use {@link #getProxyAddressAsString()} instead.
     */
    @Deprecated
    public InetAddress getProxyAddress() {
        return inetAddressFromString(mProxyAddress);
    }

    /**
     * Returns the proxy address of the APN.
     *
     * @return proxy address.
     */
    public String getProxyAddressAsString() {
        return mProxyAddress;
    }

    /**
     * Returns the proxy address of the APN.
     *
     * @return proxy address.
     */
    public int getProxyPort() {
        return mProxyPort;
    }
    /**
     * Returns the MMSC Uri of the APN.
     *
     * @return MMSC Uri.
     */
    public Uri getMmsc() {
        return mMmsc;
    }

    /**
     * Gets the MMS proxy address configured for the APN. The MMS proxy address might be an IP
     * address or hostname. This method returns {@code null} if system networking (typically DNS)
     * isn’t available to resolve a hostname value—values set as IP addresses don’t have this
     * restriction. This is a known problem and will be addressed in a future release.
     *
     * @return the MMS proxy address or {@code null} if DNS isn’t available to resolve a hostname
     * @deprecated use {@link #getMmsProxyAddressAsString()} instead.
     */
    @Deprecated
    public InetAddress getMmsProxyAddress() {
        return inetAddressFromString(mMmsProxyAddress);
    }

    /**
     * Returns the MMS proxy address of the APN.
     *
     * @return MMS proxy address.
     */
    public String getMmsProxyAddressAsString() {
        return mMmsProxyAddress;
    }

    /**
     * Returns the MMS proxy port of the APN.
     *
     * @return MMS proxy port
     */
    public int getMmsProxyPort() {
        return mMmsProxyPort;
    }

    /**
     * Returns the APN username of the APN.
     *
     * @return APN username
     */
    public String getUser() {
        return mUser;
    }

    /**
     * Returns the APN password of the APN.
     *
     * @return APN password
     */
    public String getPassword() {
        return mPassword;
    }

    /**
     * Returns the authentication type of the APN.
     *
     * @return authentication type
     */
    @AuthType
    public int getAuthType() {
        return mAuthType;
    }

    /**
     * Returns the bitmask of APN types.
     *
     * <p>Apn types are usage categories for an APN entry. One APN entry may support multiple
     * APN types, eg, a single APN may service regular internet traffic ("default") as well as
     * MMS-specific connections.
     *
     * <p>The bitmask of APN types is calculated from APN types defined in {@link ApnSetting}.
     *
     * @see Builder#setApnTypeBitmask(int)
     * @return a bitmask describing the types of the APN
     */
    public @ApnType int getApnTypeBitmask() {
        return mApnTypeBitmask;
    }

    /**
     * Returns the unique database id for this entry.
     *
     * @return the unique database id
     */
    public int getId() {
        return mId;
    }

    /**
     * Returns the numeric operator ID for the APN. Numeric operator ID is defined as
     * {@link android.provider.Telephony.Carriers#MCC} +
     * {@link android.provider.Telephony.Carriers#MNC}.
     *
     * @return the numeric operator ID
     */
    public String getOperatorNumeric() {
        return mOperatorNumeric;
    }

    /**
     * Returns the protocol to use to connect to this APN.
     *
     * <p>Protocol is one of the {@code PDP_type} values in TS 27.007 section 10.1.1.
     *
     * @see Builder#setProtocol(int)
     * @return the protocol
     */
    @ProtocolType
    public int getProtocol() {
        return mProtocol;
    }

    /**
     * Returns the protocol to use to connect to this APN while the device is roaming.
     *
     * <p>Roaming protocol is one of the {@code PDP_type} values in TS 27.007 section 10.1.1.
     *
     * @see Builder#setRoamingProtocol(int)
     * @return the roaming protocol
     */
    @ProtocolType
    public int getRoamingProtocol() {
        return mRoamingProtocol;
    }

    /**
     * Returns the current status of APN.
     *
     * {@code true} : enabled APN.
     * {@code false} : disabled APN.
     *
     * @return the current status
     */
    public boolean isEnabled() {
        return mCarrierEnabled;
    }

    /**
     * Returns a bitmask describing the Radio Technologies (Network Types) which this APN may use.
     *
     * NetworkType bitmask is calculated from NETWORK_TYPE defined in {@link TelephonyManager}.
     *
     * Examples of Network Types include {@link TelephonyManager#NETWORK_TYPE_UNKNOWN},
     * {@link TelephonyManager#NETWORK_TYPE_GPRS}, {@link TelephonyManager#NETWORK_TYPE_EDGE}.
     *
     * @return a bitmask describing the Radio Technologies (Network Types) or 0 if it is undefined.
     */
    public int getNetworkTypeBitmask() {
        return mNetworkTypeBitmask;
    }

    /**
     * Returns a bitmask describing the Radio Technologies (Network Types) that should not be torn
     * down if it exists or brought up if it already exists for this APN.
     *
     * NetworkType bitmask is calculated from NETWORK_TYPE defined in {@link TelephonyManager}.
     *
     * Examples of Network Types include {@link TelephonyManager#NETWORK_TYPE_UNKNOWN},
     * {@link TelephonyManager#NETWORK_TYPE_GPRS}, {@link TelephonyManager#NETWORK_TYPE_EDGE}.
     *
     * @return a bitmask describing the Radio Technologies (Network Types) that should linger
     *         or 0 if it is undefined.
     * @hide
     */
    public @TelephonyManager.NetworkTypeBitMask long getLingeringNetworkTypeBitmask() {
        return mLingeringNetworkTypeBitmask;
    }

    /**
     * Returns the MVNO match type for this APN.
     *
     * @see Builder#setMvnoType(int)
     * @return the MVNO match type
     */
    @MvnoType
    public int getMvnoType() {
        return mMvnoType;
    }

    /**
     * Returns the carrier id for this APN.
     *
     * @see Builder#setCarrierId(int)
     * @return the carrier id
     */
    public int getCarrierId() {
        return mCarrierId;
    }

    /**
     * Returns the skip464xlat flag for this APN.
     *
     * @return SKIP_464XLAT_DEFAULT, SKIP_464XLAT_DISABLE or SKIP_464XLAT_ENABLE
     * @hide
     */
    @Skip464XlatStatus
    public int getSkip464Xlat() {
        return mSkip464Xlat;
    }

    /**
     * Returns whether User Plane resources have to be activated during every transition from
     * CM-IDLE mode to CM-CONNECTED state for this APN
     * See 3GPP TS 23.501 section 5.6.13
     *
     * @return True if the PDU session for this APN should always be on and false otherwise
     * @hide
     */
    public boolean isAlwaysOn() {
        return mAlwaysOn;
    }

    /**
     * Check if this APN can be used when the device is using certain infrastructure(s).
     *
     * @param infrastructures The infrastructure(s) the device is using.
     *
     * @return {@code true} if this APN can be used.
     * @hide
     */
    public boolean isForInfrastructure(@InfrastructureBitmask int infrastructures) {
        return (mInfrastructureBitmask & infrastructures) != 0;
    }

    /**
     * @return The infrastructure bitmask of which the APN can be used on. For example, some APNs
     * can only be used when the device is on cellular, on satellite, or both.
     *
     * @hide
     */
    @InfrastructureBitmask
    public int getInfrastructureBitmask() {
        return mInfrastructureBitmask;
    }

    /**
     * Returns esim bootstrap provisioning flag for which the APN can be used on. For example,
     * some APNs are only allowed to bring up network, when the device esim bootstrap provisioning
     * is being activated.
     *
     * {@code true} if the APN is used for eSIM bootstrap provisioning, {@code false} otherwise.
     * @hide
     */
    public boolean isEsimBootstrapProvisioning() {
        return mEsimBootstrapProvisioning;
    }

    private ApnSetting(Builder builder) {
        this.mEntryName = builder.mEntryName;
        this.mApnName = builder.mApnName;
        this.mProxyAddress = builder.mProxyAddress;
        this.mProxyPort = builder.mProxyPort;
        this.mMmsc = builder.mMmsc;
        this.mMmsProxyAddress = builder.mMmsProxyAddress;
        this.mMmsProxyPort = builder.mMmsProxyPort;
        this.mUser = builder.mUser;
        this.mPassword = builder.mPassword;
        this.mAuthType = (builder.mAuthType != AUTH_TYPE_UNKNOWN)
                ? builder.mAuthType
                : TextUtils.isEmpty(builder.mUser)
                        ? AUTH_TYPE_NONE
                        : AUTH_TYPE_PAP_OR_CHAP;
        this.mApnTypeBitmask = builder.mApnTypeBitmask;
        this.mId = builder.mId;
        this.mOperatorNumeric = builder.mOperatorNumeric;
        this.mProtocol = builder.mProtocol;
        this.mRoamingProtocol = builder.mRoamingProtocol;
        this.mMtuV4 = builder.mMtuV4;
        this.mMtuV6 = builder.mMtuV6;
        this.mCarrierEnabled = builder.mCarrierEnabled;
        this.mNetworkTypeBitmask = builder.mNetworkTypeBitmask;
        this.mLingeringNetworkTypeBitmask = builder.mLingeringNetworkTypeBitmask;
        this.mProfileId = builder.mProfileId;
        this.mPersistent = builder.mModemCognitive;
        this.mMaxConns = builder.mMaxConns;
        this.mWaitTime = builder.mWaitTime;
        this.mMaxConnsTime = builder.mMaxConnsTime;
        this.mMvnoType = builder.mMvnoType;
        this.mMvnoMatchData = builder.mMvnoMatchData;
        this.mApnSetId = builder.mApnSetId;
        this.mCarrierId = builder.mCarrierId;
        this.mSkip464Xlat = builder.mSkip464Xlat;
        this.mAlwaysOn = builder.mAlwaysOn;
        this.mInfrastructureBitmask = builder.mInfrastructureBitmask;
        this.mEsimBootstrapProvisioning = builder.mEsimBootstrapProvisioning;
    }

    /**
     * @hide
     */
    public static ApnSetting makeApnSetting(Cursor cursor) {
        final int apnTypesBitmask = getApnTypesBitmaskFromString(
                cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.TYPE)));
        int networkTypeBitmask = cursor.getInt(
                cursor.getColumnIndexOrThrow(Telephony.Carriers.NETWORK_TYPE_BITMASK));
        if (networkTypeBitmask == 0) {
            final int bearerBitmask = cursor.getInt(cursor.getColumnIndexOrThrow(
                    Telephony.Carriers.BEARER_BITMASK));
            networkTypeBitmask =
                ServiceState.convertBearerBitmaskToNetworkTypeBitmask(bearerBitmask);
        }
        int mtuV4 = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Carriers.MTU_V4));
        if (mtuV4 == UNSET_MTU) {
            mtuV4 = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Carriers.MTU));
        }

        return new Builder()
                .setId(cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Carriers._ID)))
                .setOperatorNumeric(cursor.getString(
                        cursor.getColumnIndexOrThrow(Telephony.Carriers.NUMERIC)))
                .setEntryName(cursor.getString(
                        cursor.getColumnIndexOrThrow(Telephony.Carriers.NAME)))
                .setApnName(cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.APN)))
                .setProxyAddress(cursor.getString(
                        cursor.getColumnIndexOrThrow(Telephony.Carriers.PROXY)))
                .setProxyPort(portFromString(cursor.getString(
                        cursor.getColumnIndexOrThrow(Telephony.Carriers.PORT))))
                .setMmsc(UriFromString(cursor.getString(
                        cursor.getColumnIndexOrThrow(Telephony.Carriers.MMSC))))
                .setMmsProxyAddress(cursor.getString(
                        cursor.getColumnIndexOrThrow(Telephony.Carriers.MMSPROXY)))
                .setMmsProxyPort(portFromString(cursor.getString(
                        cursor.getColumnIndexOrThrow(Telephony.Carriers.MMSPORT))))
                .setUser(cursor.getString(
                        cursor.getColumnIndexOrThrow(Telephony.Carriers.USER)))
                .setPassword(cursor.getString(
                        cursor.getColumnIndexOrThrow(Telephony.Carriers.PASSWORD)))
                .setAuthType(cursor.getInt(
                        cursor.getColumnIndexOrThrow(Telephony.Carriers.AUTH_TYPE)))
                .setApnTypeBitmask(apnTypesBitmask)
                .setProtocol(getProtocolIntFromString(
                        cursor.getString(
                                cursor.getColumnIndexOrThrow(Telephony.Carriers.PROTOCOL))))
                .setRoamingProtocol(getProtocolIntFromString(
                        cursor.getString(cursor.getColumnIndexOrThrow(
                                Telephony.Carriers.ROAMING_PROTOCOL))))
                .setCarrierEnabled(cursor.getInt(cursor.getColumnIndexOrThrow(
                        Telephony.Carriers.CARRIER_ENABLED)) == 1)
                .setNetworkTypeBitmask(networkTypeBitmask)
                .setLingeringNetworkTypeBitmask(cursor.getInt(cursor.getColumnIndexOrThrow(
                        Carriers.LINGERING_NETWORK_TYPE_BITMASK)))
                .setProfileId(cursor.getInt(
                        cursor.getColumnIndexOrThrow(Telephony.Carriers.PROFILE_ID)))
                .setModemCognitive(cursor.getInt(cursor.getColumnIndexOrThrow(
                        Telephony.Carriers.MODEM_PERSIST)) == 1)
                .setMaxConns(cursor.getInt(
                        cursor.getColumnIndexOrThrow(Telephony.Carriers.MAX_CONNECTIONS)))
                .setWaitTime(cursor.getInt(
                        cursor.getColumnIndexOrThrow(Telephony.Carriers.WAIT_TIME_RETRY)))
                .setMaxConnsTime(cursor.getInt(cursor.getColumnIndexOrThrow(
                        Telephony.Carriers.TIME_LIMIT_FOR_MAX_CONNECTIONS)))
                .setMtuV4(mtuV4)
                .setMtuV6(cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Carriers.MTU_V6)))
                .setMvnoType(getMvnoTypeIntFromString(
                        cursor.getString(cursor.getColumnIndexOrThrow(
                                Telephony.Carriers.MVNO_TYPE))))
                .setMvnoMatchData(cursor.getString(cursor.getColumnIndexOrThrow(
                        Telephony.Carriers.MVNO_MATCH_DATA)))
                .setApnSetId(cursor.getInt(
                        cursor.getColumnIndexOrThrow(Telephony.Carriers.APN_SET_ID)))
                .setCarrierId(cursor.getInt(
                        cursor.getColumnIndexOrThrow(Telephony.Carriers.CARRIER_ID)))
                .setSkip464Xlat(cursor.getInt(cursor.getColumnIndexOrThrow(Carriers.SKIP_464XLAT)))
                .setAlwaysOn(cursor.getInt(cursor.getColumnIndexOrThrow(Carriers.ALWAYS_ON)) == 1)
                .setInfrastructureBitmask(cursor.getInt(cursor.getColumnIndexOrThrow(
                        Telephony.Carriers.INFRASTRUCTURE_BITMASK)))
                .setEsimBootstrapProvisioning(cursor.getInt(
                        cursor.getColumnIndexOrThrow(Carriers.ESIM_BOOTSTRAP_PROVISIONING)) == 1)
                .buildWithoutCheck();
    }

    /**
     * @hide
     */
    public static ApnSetting makeApnSetting(ApnSetting apn) {
        return new Builder()
                .setId(apn.mId)
                .setOperatorNumeric(apn.mOperatorNumeric)
                .setEntryName(apn.mEntryName)
                .setApnName(apn.mApnName)
                .setProxyAddress(apn.mProxyAddress)
                .setProxyPort(apn.mProxyPort)
                .setMmsc(apn.mMmsc)
                .setMmsProxyAddress(apn.mMmsProxyAddress)
                .setMmsProxyPort(apn.mMmsProxyPort)
                .setUser(apn.mUser)
                .setPassword(apn.mPassword)
                .setAuthType(apn.mAuthType)
                .setApnTypeBitmask(apn.mApnTypeBitmask)
                .setProtocol(apn.mProtocol)
                .setRoamingProtocol(apn.mRoamingProtocol)
                .setCarrierEnabled(apn.mCarrierEnabled)
                .setNetworkTypeBitmask(apn.mNetworkTypeBitmask)
                .setLingeringNetworkTypeBitmask(apn.mLingeringNetworkTypeBitmask)
                .setProfileId(apn.mProfileId)
                .setModemCognitive(apn.mPersistent)
                .setMaxConns(apn.mMaxConns)
                .setWaitTime(apn.mWaitTime)
                .setMaxConnsTime(apn.mMaxConnsTime)
                .setMtuV4(apn.mMtuV4)
                .setMtuV6(apn.mMtuV6)
                .setMvnoType(apn.mMvnoType)
                .setMvnoMatchData(apn.mMvnoMatchData)
                .setApnSetId(apn.mApnSetId)
                .setCarrierId(apn.mCarrierId)
                .setSkip464Xlat(apn.mSkip464Xlat)
                .setAlwaysOn(apn.mAlwaysOn)
                .setInfrastructureBitmask(apn.mInfrastructureBitmask)
                .setEsimBootstrapProvisioning(apn.mEsimBootstrapProvisioning)
                .buildWithoutCheck();
    }

    /**
     * Returns the string representation of ApnSetting.
     *
     * This method prints null for unset elements. The output doesn't contain password or user.
     * @hide
     */
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[ApnSetting] ")
                .append(mEntryName)
                .append(", ").append(mId)
                .append(", ").append(mOperatorNumeric)
                .append(", ").append(mApnName)
                .append(", ").append(mProxyAddress)
                .append(", ").append(UriToString(mMmsc))
                .append(", ").append(mMmsProxyAddress)
                .append(", ").append(portToString(mMmsProxyPort))
                .append(", ").append(portToString(mProxyPort))
                .append(", ").append(mAuthType).append(", ");
        final String[] types = getApnTypesStringFromBitmask(mApnTypeBitmask).split(",");
        sb.append(TextUtils.join(" | ", types));
        sb.append(", ").append(PROTOCOL_INT_MAP.get(mProtocol));
        sb.append(", ").append(PROTOCOL_INT_MAP.get(mRoamingProtocol));
        sb.append(", ").append(mCarrierEnabled);
        sb.append(", ").append(mProfileId);
        sb.append(", ").append(mPersistent);
        sb.append(", ").append(mMaxConns);
        sb.append(", ").append(mWaitTime);
        sb.append(", ").append(mMaxConnsTime);
        sb.append(", ").append(mMtuV4);
        sb.append(", ").append(mMtuV6);
        sb.append(", ").append(MVNO_TYPE_INT_MAP.get(mMvnoType));
        sb.append(", ").append(mMvnoMatchData);
        sb.append(", ").append(mPermanentFailed);
        sb.append(", ").append(TelephonyManager.convertNetworkTypeBitmaskToString(
                mNetworkTypeBitmask));
        sb.append(", ").append(TelephonyManager.convertNetworkTypeBitmaskToString(
                mLingeringNetworkTypeBitmask));
        sb.append(", ").append(mApnSetId);
        sb.append(", ").append(mCarrierId);
        sb.append(", ").append(mSkip464Xlat);
        sb.append(", ").append(mAlwaysOn);
        sb.append(", ").append(mInfrastructureBitmask);
        sb.append(", ").append(Objects.hash(mUser, mPassword));
        sb.append(", ").append(mEsimBootstrapProvisioning);
        return sb.toString();
    }

    /**
     * Returns true if there are MVNO params specified.
     * @hide
     */
    public boolean hasMvnoParams() {
        return !TextUtils.isEmpty(getMvnoTypeStringFromInt(mMvnoType))
            && !TextUtils.isEmpty(mMvnoMatchData);
    }

    private boolean hasApnType(int type) {
        return (mApnTypeBitmask & type) == type;
    }

    /** @hide */
    public boolean isEmergencyApn() {
        return hasApnType(TYPE_EMERGENCY);
    }

    /** @hide */
    public boolean canHandleType(@ApnType int type) {
        if (!mCarrierEnabled) {
            return false;
        }
        // DEFAULT can handle HIPRI.
        return hasApnType(type);
    }

    // Check whether the types of two APN same (even only one type of each APN is same).
    private boolean typeSameAny(ApnSetting first, ApnSetting second) {
        if (VDBG) {
            StringBuilder apnType1 = new StringBuilder(first.mApnName + ": ");
            apnType1.append(getApnTypesStringFromBitmask(first.mApnTypeBitmask));

            StringBuilder apnType2 = new StringBuilder(second.mApnName + ": ");
            apnType2.append(getApnTypesStringFromBitmask(second.mApnTypeBitmask));

            Rlog.d(LOG_TAG, "APN1: is " + apnType1);
            Rlog.d(LOG_TAG, "APN2: is " + apnType2);
        }

        if ((first.mApnTypeBitmask & second.mApnTypeBitmask) != 0) {
            if (VDBG) {
                Rlog.d(LOG_TAG, "typeSameAny: return true");
            }
            return true;
        }

        if (VDBG) {
            Rlog.d(LOG_TAG, "typeSameAny: return false");
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mApnName, mProxyAddress, mProxyPort, mMmsc, mMmsProxyAddress,
                mMmsProxyPort, mUser, mPassword, mAuthType, mApnTypeBitmask, mId, mOperatorNumeric,
                mProtocol, mRoamingProtocol, mMtuV4, mMtuV6, mCarrierEnabled, mNetworkTypeBitmask,
                mLingeringNetworkTypeBitmask, mProfileId, mPersistent, mMaxConns, mWaitTime,
                mMaxConnsTime, mMvnoType, mMvnoMatchData, mApnSetId, mCarrierId, mSkip464Xlat,
                mAlwaysOn, mInfrastructureBitmask, mEsimBootstrapProvisioning);
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof ApnSetting == false) {
            return false;
        }

        ApnSetting other = (ApnSetting) o;

        return mEntryName.equals(other.mEntryName)
                && mId == other.mId
                && Objects.equals(mOperatorNumeric, other.mOperatorNumeric)
                && Objects.equals(mApnName, other.mApnName)
                && Objects.equals(mProxyAddress, other.mProxyAddress)
                && Objects.equals(mMmsc, other.mMmsc)
                && Objects.equals(mMmsProxyAddress, other.mMmsProxyAddress)
                && mMmsProxyPort == other.mMmsProxyPort
                && mProxyPort == other.mProxyPort
                && Objects.equals(mUser, other.mUser)
                && Objects.equals(mPassword, other.mPassword)
                && mAuthType == other.mAuthType
                && mApnTypeBitmask == other.mApnTypeBitmask
                && mProtocol == other.mProtocol
                && mRoamingProtocol == other.mRoamingProtocol
                && mCarrierEnabled == other.mCarrierEnabled
                && mProfileId == other.mProfileId
                && mPersistent == other.mPersistent
                && mMaxConns == other.mMaxConns
                && mWaitTime == other.mWaitTime
                && mMaxConnsTime == other.mMaxConnsTime
                && mMtuV4 == other.mMtuV4
                && mMtuV6 == other.mMtuV6
                && mMvnoType == other.mMvnoType
                && Objects.equals(mMvnoMatchData, other.mMvnoMatchData)
                && mNetworkTypeBitmask == other.mNetworkTypeBitmask
                && mLingeringNetworkTypeBitmask == other.mLingeringNetworkTypeBitmask
                && mApnSetId == other.mApnSetId
                && mCarrierId == other.mCarrierId
                && mSkip464Xlat == other.mSkip464Xlat
                && mAlwaysOn == other.mAlwaysOn
                && mInfrastructureBitmask == other.mInfrastructureBitmask
                && mEsimBootstrapProvisioning == other.mEsimBootstrapProvisioning;
    }

    /**
     * Compare two APN settings
     *
     * Note: This method does not compare 'mId', 'mNetworkTypeBitmask'. We only use this for
     * determining if tearing a data call is needed when conditions change. See
     * cleanUpConnectionsOnUpdatedApns in DcTracker.
     *
     * @param o the other object to compare
     * @param isDataRoaming True if the device is on data roaming
     * @return True if the two APN settings are same
     * @hide
     */
    public boolean equals(Object o, boolean isDataRoaming) {
        if (!(o instanceof ApnSetting)) {
            return false;
        }

        ApnSetting other = (ApnSetting) o;

        return mEntryName.equals(other.mEntryName)
                && Objects.equals(mOperatorNumeric, other.mOperatorNumeric)
                && Objects.equals(mApnName, other.mApnName)
                && Objects.equals(mProxyAddress, other.mProxyAddress)
                && Objects.equals(mMmsc, other.mMmsc)
                && Objects.equals(mMmsProxyAddress, other.mMmsProxyAddress)
                && Objects.equals(mMmsProxyPort, other.mMmsProxyPort)
                && Objects.equals(mProxyPort, other.mProxyPort)
                && Objects.equals(mUser, other.mUser)
                && Objects.equals(mPassword, other.mPassword)
                && Objects.equals(mAuthType, other.mAuthType)
                && Objects.equals(mApnTypeBitmask, other.mApnTypeBitmask)
                && Objects.equals(mLingeringNetworkTypeBitmask, other.mLingeringNetworkTypeBitmask)
                && (isDataRoaming || Objects.equals(mProtocol, other.mProtocol))
                && (!isDataRoaming || Objects.equals(mRoamingProtocol, other.mRoamingProtocol))
                && Objects.equals(mCarrierEnabled, other.mCarrierEnabled)
                && Objects.equals(mProfileId, other.mProfileId)
                && Objects.equals(mPersistent, other.mPersistent)
                && Objects.equals(mMaxConns, other.mMaxConns)
                && Objects.equals(mWaitTime, other.mWaitTime)
                && Objects.equals(mMaxConnsTime, other.mMaxConnsTime)
                && Objects.equals(mMtuV4, other.mMtuV4)
                && Objects.equals(mMtuV6, other.mMtuV6)
                && Objects.equals(mMvnoType, other.mMvnoType)
                && Objects.equals(mMvnoMatchData, other.mMvnoMatchData)
                && Objects.equals(mApnSetId, other.mApnSetId)
                && Objects.equals(mCarrierId, other.mCarrierId)
                && Objects.equals(mSkip464Xlat, other.mSkip464Xlat)
                && Objects.equals(mAlwaysOn, other.mAlwaysOn)
                && Objects.equals(mInfrastructureBitmask, other.mInfrastructureBitmask)
                && Objects.equals(mEsimBootstrapProvisioning, other.mEsimBootstrapProvisioning);
    }

    /**
     * Check if neither mention DUN and are substantially similar
     *
     * @param other The other APN settings to compare
     * @return True if two APN settings are similar
     * @hide
     */
    public boolean similar(ApnSetting other) {
        return (!this.canHandleType(TYPE_DUN)
                && !other.canHandleType(TYPE_DUN)
                && Objects.equals(this.mApnName, other.mApnName)
                && xorEqualsString(this.mProxyAddress, other.mProxyAddress)
                && xorEqualsInt(this.mProxyPort, other.mProxyPort)
                && xorEquals(this.mMmsc, other.mMmsc)
                && xorEqualsString(this.mMmsProxyAddress, other.mMmsProxyAddress)
                && xorEqualsInt(this.mMmsProxyPort, other.mMmsProxyPort))
                && xorEqualsString(this.mUser, other.mUser)
                && xorEqualsString(this.mPassword, other.mPassword)
                && Objects.equals(this.mAuthType, other.mAuthType)
                && !typeSameAny(this, other)
                && Objects.equals(this.mOperatorNumeric, other.mOperatorNumeric)
                && Objects.equals(this.mProtocol, other.mProtocol)
                && Objects.equals(this.mRoamingProtocol, other.mRoamingProtocol)
                && mtuUnsetOrEquals(this.mMtuV4, other.mMtuV4)
                && mtuUnsetOrEquals(this.mMtuV6, other.mMtuV6)
                && Objects.equals(this.mCarrierEnabled, other.mCarrierEnabled)
                && Objects.equals(this.mNetworkTypeBitmask, other.mNetworkTypeBitmask)
                && Objects.equals(this.mLingeringNetworkTypeBitmask,
                other.mLingeringNetworkTypeBitmask)
                && Objects.equals(this.mProfileId, other.mProfileId)
                && Objects.equals(this.mPersistent, other.mPersistent)
                && Objects.equals(this.mApnSetId, other.mApnSetId)
                && Objects.equals(this.mCarrierId, other.mCarrierId)
                && Objects.equals(this.mSkip464Xlat, other.mSkip464Xlat)
                && Objects.equals(this.mAlwaysOn, other.mAlwaysOn)
                && Objects.equals(this.mInfrastructureBitmask, other.mInfrastructureBitmask)
                && Objects.equals(this.mEsimBootstrapProvisioning,
                other.mEsimBootstrapProvisioning);
    }

    // Equal or one is null.
    private boolean xorEquals(Object first, Object second) {
        return first == null || second == null || first.equals(second);
    }

    // Equal or one is null.
    private boolean xorEqualsString(String first, String second) {
        return TextUtils.isEmpty(first) || TextUtils.isEmpty(second) || first.equals(second);
    }

    // Equal or one is not specified.
    private boolean xorEqualsInt(int first, int second) {
        return first == UNSPECIFIED_INT || second == UNSPECIFIED_INT
                || first == second;
    }

    // Equal or one is not specified. Specific to MTU where <= 0 indicates unset.
    private boolean mtuUnsetOrEquals(int first, int second) {
        return first <= 0 || second <= 0 || first == second;
    }

    private String nullToEmpty(String stringValue) {
        return stringValue == null ? UNSPECIFIED_STRING : stringValue;
    }

    /**
     * @hide
     * Called by {@link android.app.admin.DevicePolicyManager} to convert this APN into
     * ContentValue. If a field is not specified then we put "" instead of null.
     */
    public ContentValues toContentValues() {
        ContentValues apnValue = new ContentValues();
        apnValue.put(Telephony.Carriers.NUMERIC, nullToEmpty(mOperatorNumeric));
        // If the APN is editable, the user may be able to set an invalid numeric. The numeric must
        // always be 5 or 6 characters (depending on the length of the MNC), so skip if it is
        // potentially invalid.
        if (!TextUtils.isEmpty(mOperatorNumeric)
                && (mOperatorNumeric.length() == 5 || mOperatorNumeric.length() == 6)) {
            apnValue.put(Telephony.Carriers.MCC, mOperatorNumeric.substring(0, 3));
            apnValue.put(Telephony.Carriers.MNC, mOperatorNumeric.substring(3));
        }
        apnValue.put(Telephony.Carriers.NAME, nullToEmpty(mEntryName));
        apnValue.put(Telephony.Carriers.APN, nullToEmpty(mApnName));
        apnValue.put(Telephony.Carriers.PROXY, nullToEmpty(mProxyAddress));
        apnValue.put(Telephony.Carriers.PORT, nullToEmpty(portToString(mProxyPort)));
        apnValue.put(Telephony.Carriers.MMSC, nullToEmpty(UriToString(mMmsc)));
        apnValue.put(Telephony.Carriers.MMSPORT, nullToEmpty(portToString(mMmsProxyPort)));
        apnValue.put(Telephony.Carriers.MMSPROXY, nullToEmpty(
                mMmsProxyAddress));
        apnValue.put(Telephony.Carriers.USER, nullToEmpty(mUser));
        apnValue.put(Telephony.Carriers.PASSWORD, nullToEmpty(mPassword));
        apnValue.put(Telephony.Carriers.AUTH_TYPE, mAuthType);
        String apnType = getApnTypesStringFromBitmask(mApnTypeBitmask);
        apnValue.put(Telephony.Carriers.TYPE, nullToEmpty(apnType));
        apnValue.put(Telephony.Carriers.PROTOCOL,
                getProtocolStringFromInt(mProtocol));
        apnValue.put(Telephony.Carriers.ROAMING_PROTOCOL,
                getProtocolStringFromInt(mRoamingProtocol));
        apnValue.put(Telephony.Carriers.CARRIER_ENABLED, mCarrierEnabled);
        apnValue.put(Telephony.Carriers.MVNO_TYPE, getMvnoTypeStringFromInt(mMvnoType));
        apnValue.put(Telephony.Carriers.MVNO_MATCH_DATA, nullToEmpty(mMvnoMatchData));
        apnValue.put(Telephony.Carriers.NETWORK_TYPE_BITMASK, mNetworkTypeBitmask);
        apnValue.put(Telephony.Carriers.LINGERING_NETWORK_TYPE_BITMASK,
                mLingeringNetworkTypeBitmask);
        apnValue.put(Telephony.Carriers.MTU_V4, mMtuV4);
        apnValue.put(Telephony.Carriers.MTU_V6, mMtuV6);
        apnValue.put(Telephony.Carriers.CARRIER_ID, mCarrierId);
        apnValue.put(Telephony.Carriers.SKIP_464XLAT, mSkip464Xlat);
        apnValue.put(Telephony.Carriers.ALWAYS_ON, mAlwaysOn);
        apnValue.put(Telephony.Carriers.INFRASTRUCTURE_BITMASK, mInfrastructureBitmask);
        apnValue.put(Telephony.Carriers.ESIM_BOOTSTRAP_PROVISIONING, mEsimBootstrapProvisioning);
        return apnValue;
    }

    /**
     * Get supported APN types
     *
     * @return list of APN types
     * @hide
     */
    @ApnType
    public List<Integer> getApnTypes() {
        List<Integer> types = new ArrayList<>();
        for (Integer type : APN_TYPE_INT_MAP.keySet()) {
            if ((mApnTypeBitmask & type) == type) {
                types.add(type);
            }
        }
        return types;
    }

    /**
     * Converts the integer value of an APN type to the string version.
     * @param apnTypeBitmask bitmask of APN types.
     * @return comma delimited list of APN types.
     * @hide
     */
    @NonNull
    public static String getApnTypesStringFromBitmask(int apnTypeBitmask) {
        List<String> types = new ArrayList<>();
        for (Integer type : APN_TYPE_INT_MAP.keySet()) {
            if ((apnTypeBitmask & type) == type) {
                types.add(APN_TYPE_INT_MAP.get(type));
            }
        }
        return TextUtils.join(",", types);
    }

    /**
     * Converts the APN type bitmask to an array of all APN types
     * @param apnTypeBitmask bitmask of APN types.
     * @return int array of APN types
     * @hide
     */
    @NonNull
    public static int[] getApnTypesFromBitmask(int apnTypeBitmask) {
        return APN_TYPE_INT_MAP.keySet().stream()
                .filter(type -> ((apnTypeBitmask & type) == type))
                .mapToInt(Integer::intValue)
                .toArray();
    }

    /**
     * Converts the integer representation of APN type to its string representation.
     *
     * @param apnType APN type as an integer
     * @return String representation of the APN type, or an empty string if the provided integer is
     * not a valid APN type.
     * @hide
     */
    @SystemApi
    public static @NonNull @ApnTypeString String getApnTypeString(@ApnType int apnType) {
        if (apnType == TYPE_ALL) {
            return "*";
        }
        String apnTypeString = APN_TYPE_INT_MAP.get(apnType);
        return apnTypeString == null ? "" : apnTypeString;
    }

    /**
     * Converts the string representation of an APN type to its integer representation.
     *
     * @param apnType APN type as a string
     * @return Integer representation of the APN type, or 0 if the provided string is not a valid
     * APN type.
     * @hide
     */
    @SystemApi
    public static @ApnType int getApnTypeInt(@NonNull @ApnTypeString String apnType) {
        return APN_TYPE_STRING_MAP.getOrDefault(apnType.toLowerCase(Locale.ROOT), 0);
    }

    /**
     * @param types comma delimited list of APN types.
     * @return bitmask of APN types.
     * @hide
     */
    public static int getApnTypesBitmaskFromString(String types) {
        // If unset, set to ALL.
        if (TextUtils.isEmpty(types)) {
            return TYPE_ALL;
        } else {
            int result = 0;
            for (String str : types.split(",")) {
                Integer type = APN_TYPE_STRING_MAP.get(str.toLowerCase(Locale.ROOT));
                if (type != null) {
                    result |= type;
                }
            }
            return result;
        }
    }

    /** @hide */
    public static int getMvnoTypeIntFromString(String mvnoType) {
        String mvnoTypeString = TextUtils.isEmpty(mvnoType)
                ? mvnoType : mvnoType.toLowerCase(Locale.ROOT);
        Integer mvnoTypeInt = MVNO_TYPE_STRING_MAP.get(mvnoTypeString);
        return  mvnoTypeInt == null ? MVNO_TYPE_UNKNOWN : mvnoTypeInt;
    }

    /** @hide */
    public static String getMvnoTypeStringFromInt(int mvnoType) {
        String mvnoTypeString = MVNO_TYPE_INT_MAP.get(mvnoType);
        return  mvnoTypeString == null ? UNSPECIFIED_STRING : mvnoTypeString;
    }

    /** @hide */
    public static int getProtocolIntFromString(String protocol) {
        Integer protocolInt = PROTOCOL_STRING_MAP.get(protocol);
        return  protocolInt == null ? UNSPECIFIED_INT : protocolInt;
    }

    /** @hide */
    public static String getProtocolStringFromInt(int protocol) {
        String protocolString = PROTOCOL_INT_MAP.get(protocol);
        return  protocolString == null ? UNSPECIFIED_STRING : protocolString;
    }

    private static Uri UriFromString(String uri) {
        return TextUtils.isEmpty(uri) ? null : Uri.parse(uri);
    }

    private static String UriToString(Uri uri) {
        return uri == null ? null : uri.toString();
    }

    /** @hide */
    public static InetAddress inetAddressFromString(String inetAddress) {
        if (TextUtils.isEmpty(inetAddress)) {
            return null;
        }
        try {
            return InetAddress.getByName(inetAddress);
        } catch (UnknownHostException e) {
            Log.e(LOG_TAG, "Can't parse InetAddress from string: unknown host.");
            return null;
        }
    }

    /** @hide */
    public static String inetAddressToString(InetAddress inetAddress) {
        if (inetAddress == null) {
            return null;
        }
        final String inetAddressString = inetAddress.toString();
        if (TextUtils.isEmpty(inetAddressString)) {
            return null;
        }
        final String hostName = inetAddressString.substring(0, inetAddressString.indexOf("/"));
        final String address = inetAddressString.substring(inetAddressString.indexOf("/") + 1);
        if (TextUtils.isEmpty(hostName) && TextUtils.isEmpty(address)) {
            return null;
        }
        return TextUtils.isEmpty(hostName) ? address : hostName;
    }

    private static int portFromString(String strPort) {
        int port = UNSPECIFIED_INT;
        if (!TextUtils.isEmpty(strPort)) {
            try {
                port = Integer.parseInt(strPort);
            } catch (NumberFormatException e) {
                Log.e(LOG_TAG, "Can't parse port from String");
            }
        }
        return port;
    }

    private static String portToString(int port) {
        return port == UNSPECIFIED_INT ? null : Integer.toString(port);
    }

    /**
     * Check if this APN setting can support the given network
     *
     * @param networkType The network type
     * @return {@code true} if this APN setting can support the given network.
     *
     * @hide
     */
    public boolean canSupportNetworkType(@NetworkType int networkType) {
        // Do a special checking for GSM. In reality, GSM is a voice only network type and can never
        // be used for data. We allow it here because in some DSDS corner cases, on the non-DDS
        // sub, modem reports data rat unknown. In that case if voice is GSM and this APN supports
        // GPRS or EDGE, this APN setting should be selected.
        if (networkType == TelephonyManager.NETWORK_TYPE_GSM
                && (mNetworkTypeBitmask & (TelephonyManager.NETWORK_TYPE_BITMASK_GPRS
                | TelephonyManager.NETWORK_TYPE_BITMASK_EDGE)) != 0) {
            return true;
        }

        return ServiceState.bitmaskHasTech(mNetworkTypeBitmask, networkType);
    }

    /**
     * Check if this APN setting can support the given lingering network
     *
     * @param networkType The lingering network type
     * @return {@code true} if this APN setting can support the given lingering network.
     *
     * @hide
     */
    public boolean canSupportLingeringNetworkType(@NetworkType int networkType) {
        // For backwards compatibility, if this field is not set, we just use the existing
        // network type bitmask.
        if (mLingeringNetworkTypeBitmask == 0) {
            return canSupportNetworkType(networkType);
        }
        // Do a special checking for GSM. In reality, GSM is a voice only network type and can never
        // be used for data. We allow it here because in some DSDS corner cases, on the non-DDS
        // sub, modem reports data rat unknown. In that case if voice is GSM and this APN supports
        // GPRS or EDGE, this APN setting should be selected.
        if (networkType == TelephonyManager.NETWORK_TYPE_GSM
                && (mLingeringNetworkTypeBitmask & (TelephonyManager.NETWORK_TYPE_BITMASK_GPRS
                | TelephonyManager.NETWORK_TYPE_BITMASK_EDGE)) != 0) {
            return true;
        }

        return ServiceState.bitmaskHasTech((int) mLingeringNetworkTypeBitmask, networkType);
    }

    // Implement Parcelable.
    @Override
    /** @hide */
    public int describeContents() {
        return 0;
    }

    @Override
    /** @hide */
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mId);
        dest.writeString(mOperatorNumeric);
        dest.writeString(mEntryName);
        dest.writeString(mApnName);
        dest.writeString(mProxyAddress);
        dest.writeInt(mProxyPort);
        dest.writeParcelable(mMmsc, flags);
        dest.writeString(mMmsProxyAddress);
        dest.writeInt(mMmsProxyPort);
        dest.writeString(mUser);
        dest.writeString(mPassword);
        dest.writeInt(mAuthType);
        dest.writeInt(mApnTypeBitmask);
        dest.writeInt(mProtocol);
        dest.writeInt(mRoamingProtocol);
        dest.writeBoolean(mCarrierEnabled);
        dest.writeInt(mNetworkTypeBitmask);
        dest.writeLong(mLingeringNetworkTypeBitmask);
        dest.writeInt(mProfileId);
        dest.writeBoolean(mPersistent);
        dest.writeInt(mMaxConns);
        dest.writeInt(mWaitTime);
        dest.writeInt(mMaxConnsTime);
        dest.writeInt(mMtuV4);
        dest.writeInt(mMtuV6);
        dest.writeInt(mMvnoType);
        dest.writeString(mMvnoMatchData);
        dest.writeInt(mApnSetId);
        dest.writeInt(mCarrierId);
        dest.writeInt(mSkip464Xlat);
        dest.writeBoolean(mAlwaysOn);
        dest.writeInt(mInfrastructureBitmask);
        dest.writeBoolean(mEsimBootstrapProvisioning);
    }

    private static ApnSetting readFromParcel(Parcel in) {
        return new Builder()
                .setId(in.readInt())
                .setOperatorNumeric(in.readString())
                .setEntryName(in.readString())
                .setApnName(in.readString())
                .setProxyAddress(in.readString())
                .setProxyPort(in.readInt())
                .setMmsc(in.readParcelable(Uri.class.getClassLoader(), android.net.Uri.class))
                .setMmsProxyAddress(in.readString())
                .setMmsProxyPort(in.readInt())
                .setUser(in.readString())
                .setPassword(in.readString())
                .setAuthType(in.readInt())
                .setApnTypeBitmask(in.readInt())
                .setProtocol(in.readInt())
                .setRoamingProtocol(in.readInt())
                .setCarrierEnabled(in.readBoolean())
                .setNetworkTypeBitmask(in.readInt())
                .setLingeringNetworkTypeBitmask(in.readLong())
                .setProfileId(in.readInt())
                .setModemCognitive(in.readBoolean())
                .setMaxConns(in.readInt())
                .setWaitTime(in.readInt())
                .setMaxConnsTime(in.readInt())
                .setMtuV4(in.readInt())
                .setMtuV6(in.readInt())
                .setMvnoType(in.readInt())
                .setMvnoMatchData(in.readString())
                .setApnSetId(in.readInt())
                .setCarrierId(in.readInt())
                .setSkip464Xlat(in.readInt())
                .setAlwaysOn(in.readBoolean())
                .setInfrastructureBitmask(in.readInt())
                .setEsimBootstrapProvisioning(in.readBoolean())
                .buildWithoutCheck();
    }

    public static final @android.annotation.NonNull Parcelable.Creator<ApnSetting> CREATOR =
            new Parcelable.Creator<ApnSetting>() {
                @Override
                public ApnSetting createFromParcel(Parcel in) {
                    return readFromParcel(in);
                }

                @Override
                public ApnSetting[] newArray(int size) {
                    return new ApnSetting[size];
                }
            };

    /**
     * Provides a convenient way to set the fields of a {@link ApnSetting} when creating a new
     * instance. The following settings are required to build an {@code ApnSetting}:
     *
     * <ul><li>apnTypeBitmask</li>
     * <li>apnName</li>
     * <li>entryName</li></ul>
     *
     * <p>The example below shows how you might create a new {@code ApnSetting}:
     *
     * <pre><code>
     * // Create an MMS proxy address with a hostname. A network might not be
     * // available, so supply a placeholder (0.0.0.0) IPv4 address to avoid DNS lookup.
     * String host = "mms.example.com";
     * byte[] ipAddress = new byte[4];
     * InetAddress mmsProxy;
     * try {
     *   mmsProxy = InetAddress.getByAddress(host, ipAddress);
     * } catch (UnknownHostException e) {
     *   e.printStackTrace();
     *   return;
     * }
     *
     * ApnSetting apn = new ApnSetting.Builder()
     *     .setApnTypeBitmask(ApnSetting.TYPE_DEFAULT | ApnSetting.TYPE_MMS)
     *     .setApnName("apn.example.com")
     *     .setEntryName("Example Carrier APN")
     *     .setMmsc(Uri.parse("http://mms.example.com:8002"))
     *     .setMmsProxyAddress(mmsProxy)
     *     .setMmsProxyPort(8799)
     *     .build();
     * </code></pre>
     */
    public static class Builder{
        private String mEntryName;
        private String mApnName;
        private String mProxyAddress;
        private int mProxyPort = UNSPECIFIED_INT;
        private Uri mMmsc;
        private String mMmsProxyAddress;
        private int mMmsProxyPort = UNSPECIFIED_INT;
        private String mUser;
        private String mPassword;
        private int mAuthType = AUTH_TYPE_UNKNOWN;
        private int mApnTypeBitmask;
        private int mId;
        private String mOperatorNumeric;
        private int mProtocol = UNSPECIFIED_INT;
        private int mRoamingProtocol = UNSPECIFIED_INT;
        private int mMtuV4;
        private int mMtuV6;
        private @TelephonyManager.NetworkTypeBitMask int mNetworkTypeBitmask;
        private @TelephonyManager.NetworkTypeBitMask long mLingeringNetworkTypeBitmask;
        private boolean mCarrierEnabled;
        private int mProfileId;
        private boolean mModemCognitive;
        private int mMaxConns;
        private int mWaitTime;
        private int mMaxConnsTime;
        private int mMvnoType = MVNO_TYPE_UNKNOWN;
        private String mMvnoMatchData;
        private int mApnSetId;
        private int mCarrierId = TelephonyManager.UNKNOWN_CARRIER_ID;
        private int mSkip464Xlat = Carriers.SKIP_464XLAT_DEFAULT;
        private boolean mAlwaysOn;
        private int mInfrastructureBitmask = INFRASTRUCTURE_CELLULAR;
        private boolean mEsimBootstrapProvisioning;

        /**
         * Default constructor for Builder.
         */
        public Builder() {}

        /**
         * Sets the unique database id for this entry.
         *
         * @param id the unique database id to set for this entry
         * @hide
         */
        public Builder setId(int id) {
            this.mId = id;
            return this;
        }

        /**
         * Set the default MTU (Maximum Transmission Unit) size in bytes of the IPv4 routes brought
         * up by this APN setting. Note this value will only be used when MTU size is not provided
         * in {@code DataCallResponse#getMtuV4()} during network bring up.
         *
         * @param mtuV4 the MTU size in bytes of the route.
         */
        public @NonNull Builder setMtuV4(int mtuV4) {
            this.mMtuV4 = mtuV4;
            return this;
        }

        /**
         * Set the default MTU (Maximum Transmission Unit) size in bytes of the IPv6 routes brought
         * up by this APN setting. Note this value will only be used when MTU size is not provided
         * in {@code DataCallResponse#getMtuV6()} during network bring up.
         *
         * @param mtuV6 the MTU size in bytes of the route.
         */
        public @NonNull Builder setMtuV6(int mtuV6) {
            this.mMtuV6 = mtuV6;
            return this;
        }

        /**
         * Sets the profile id to which the APN saved in modem.
         *
         * @param profileId the profile id to set for the APN.
         */
        public @NonNull Builder setProfileId(int profileId) {
            this.mProfileId = profileId;
            return this;
        }

        /**
         * Set if the APN setting should be persistent/non-persistent in modem.
         *
         * @param isPersistent {@code true} if this APN setting should be persistent/non-persistent
         * in modem.
         * @return The same instance of the builder.
         */
        public @NonNull Builder setPersistent(boolean isPersistent) {
            return setModemCognitive(isPersistent);
        }

        /**
         * Sets if the APN setting is to be set in modem.
         *
         * @param modemCognitive if the APN setting is to be set in modem
         * @hide
         */
        public Builder setModemCognitive(boolean modemCognitive) {
            this.mModemCognitive = modemCognitive;
            return this;
        }

        /**
         * Sets the max connections of this APN.
         *
         * @param maxConns the max connections of this APN
         * @hide
         */
        public Builder setMaxConns(int maxConns) {
            this.mMaxConns = maxConns;
            return this;
        }

        /**
         * Sets the wait time for retry of the APN.
         *
         * @param waitTime the wait time for retry of the APN
         * @hide
         */
        public Builder setWaitTime(int waitTime) {
            this.mWaitTime = waitTime;
            return this;
        }

        /**
         * Sets the time to limit max connection for the APN.
         *
         * @param maxConnsTime the time to limit max connection for the APN
         * @hide
         */
        public Builder setMaxConnsTime(int maxConnsTime) {
            this.mMaxConnsTime = maxConnsTime;
            return this;
        }

        /**
         * Sets the MVNO match data for the APN.
         *
         * @param mvnoMatchData the MVNO match data for the APN
         * @hide
         */
        public Builder setMvnoMatchData(@Nullable String mvnoMatchData) {
            this.mMvnoMatchData = mvnoMatchData;
            return this;
        }

        /**
         * Sets the APN set id for the APN.
         *
         * @param apnSetId the set id for the APN
         * @hide
         */
        public Builder setApnSetId(int apnSetId) {
            this.mApnSetId = apnSetId;
            return this;
        }

        /**
         * Sets a human-readable name that describes the APN.
         *
         * @param entryName the entry name to set for the APN
         */
        @NonNull
        public Builder setEntryName(@Nullable String entryName) {
            this.mEntryName = entryName;
            return this;
        }

        /**
         * Sets the name of the APN.
         *
         * @param apnName the name to set for the APN
         */
        @NonNull
        public Builder setApnName(@Nullable String apnName) {
            this.mApnName = apnName;
            return this;
        }

        /**
         * Sets the address of an HTTP proxy for the APN. The proxy address can be an IP address or
         * hostname. If {@code proxy} contains both an IP address and hostname, this method ignores
         * the IP address.
         *
         * <p>The {@link java.net.InetAddress} methods
         * {@link java.net.InetAddress#getAllByName getAllByName()} require DNS for hostname
         * resolution. To avoid this requirement when setting a hostname, call
         * {@link java.net.InetAddress#getByAddress(java.lang.String, byte[])} with both the
         * hostname and a placeholder IP address. See {@link ApnSetting.Builder above} for an
         * example.
         *
         * @param proxy the proxy address to set for the APN
         * @deprecated use {@link #setProxyAddress(String)} instead.
         */
        @Deprecated
        public Builder setProxyAddress(InetAddress proxy) {
            this.mProxyAddress = inetAddressToString(proxy);
            return this;
        }

        /**
         * Sets the proxy address of the APN.
         *
         * @param proxy the proxy address to set for the APN
         */
        @NonNull
        public Builder setProxyAddress(@Nullable String proxy) {
            this.mProxyAddress = proxy;
            return this;
        }

        /**
         * Sets the proxy port of the APN.
         *
         * @param port the proxy port to set for the APN
         */
        @NonNull
        public Builder setProxyPort(int port) {
            this.mProxyPort = port;
            return this;
        }

        /**
         * Sets the MMSC Uri of the APN.
         *
         * @param mmsc the MMSC Uri to set for the APN
         */
        @NonNull
        public Builder setMmsc(@Nullable Uri mmsc) {
            this.mMmsc = mmsc;
            return this;
        }

        /**
         * Sets the address of an MMS proxy for the APN. The MMS proxy address can be an IP address
         * or hostname. If {@code mmsProxy} contains both an IP address and hostname, this method
         * ignores the IP address.
         *
         * <p>The {@link java.net.InetAddress} methods
         * {@link java.net.InetAddress#getByName getByName()} and
         * {@link java.net.InetAddress#getAllByName getAllByName()} require DNS for hostname
         * resolution. To avoid this requirement when setting a hostname, call
         * {@link java.net.InetAddress#getByAddress(java.lang.String, byte[])} with both the
         * hostname and a placeholder IP address. See {@link ApnSetting.Builder above} for an
         * example.
         *
         * @param mmsProxy the MMS proxy address to set for the APN
         * @deprecated use {@link #setMmsProxyAddress(String)} instead.
         */
        @Deprecated
        public Builder setMmsProxyAddress(InetAddress mmsProxy) {
            this.mMmsProxyAddress = inetAddressToString(mmsProxy);
            return this;
        }

        /**
         * Sets the MMS proxy address of the APN.
         *
         * @param mmsProxy the MMS proxy address to set for the APN
         */
        @NonNull
        public Builder setMmsProxyAddress(@Nullable String mmsProxy) {
            this.mMmsProxyAddress = mmsProxy;
            return this;
        }

        /**
         * Sets the MMS proxy port of the APN.
         *
         * @param mmsPort the MMS proxy port to set for the APN
         */
        @NonNull
        public Builder setMmsProxyPort(int mmsPort) {
            this.mMmsProxyPort = mmsPort;
            return this;
        }

        /**
         * Sets the APN username of the APN.
         *
         * @param user the APN username to set for the APN
         */
        @NonNull
        public Builder setUser(@Nullable String user) {
            this.mUser = user;
            return this;
        }

        /**
         * Sets the APN password of the APN.
         *
         * @see android.provider.Telephony.Carriers#PASSWORD
         * @param password the APN password to set for the APN
         */
        @NonNull
        public Builder setPassword(@Nullable String password) {
            this.mPassword = password;
            return this;
        }

        /**
         * Sets the authentication type of the APN.
         *
         * @param authType the authentication type to set for the APN
         */
        @NonNull
        public Builder setAuthType(@AuthType int authType) {
            this.mAuthType = authType;
            return this;
        }

        /**
         * Sets the bitmask of APN types.
         *
         * <p>Apn types are usage categories for an APN entry. One APN entry may support multiple
         * APN types, eg, a single APN may service regular internet traffic ("default") as well as
         * MMS-specific connections.
         *
         * <p>The bitmask of APN types is calculated from APN types defined in {@link ApnSetting}.
         *
         * @param apnTypeBitmask a bitmask describing the types of the APN
         */
        @NonNull
        public Builder setApnTypeBitmask(@ApnType int apnTypeBitmask) {
            this.mApnTypeBitmask = apnTypeBitmask;
            return this;
        }

        /**
         * Sets the numeric operator ID for the APN. Numeric operator ID is defined as
         * {@link android.provider.Telephony.Carriers#MCC} +
         * {@link android.provider.Telephony.Carriers#MNC}.
         *
         * @param operatorNumeric the numeric operator ID to set for this entry
         */
        @NonNull
        public Builder setOperatorNumeric(@Nullable String operatorNumeric) {
            this.mOperatorNumeric = operatorNumeric;
            return this;
        }

        /**
         * Sets the protocol to use to connect to this APN.
         *
         * <p>Protocol is one of the {@code PDP_type} values in TS 27.007 section 10.1.1.
         *
         * @param protocol the protocol to set to use to connect to this APN
         */
        @NonNull
        public Builder setProtocol(@ProtocolType int protocol) {
            this.mProtocol = protocol;
            return this;
        }

        /**
         * Sets the protocol to use to connect to this APN when the device is roaming.
         *
         * <p>Roaming protocol is one of the {@code PDP_type} values in TS 27.007 section 10.1.1.
         *
         * @param roamingProtocol the protocol to set to use to connect to this APN when roaming
         */
        @NonNull
        public Builder setRoamingProtocol(@ProtocolType  int roamingProtocol) {
            this.mRoamingProtocol = roamingProtocol;
            return this;
        }

        /**
         * Sets the current status for this APN.
         *
         * @param carrierEnabled the current status to set for this APN
         */
        @NonNull
        public Builder setCarrierEnabled(boolean carrierEnabled) {
            this.mCarrierEnabled = carrierEnabled;
            return this;
        }

        /**
         * Sets Radio Technology (Network Type) info for this APN.
         *
         * @param networkTypeBitmask the Radio Technology (Network Type) info
         */
        @NonNull
        public Builder setNetworkTypeBitmask(int networkTypeBitmask) {
            this.mNetworkTypeBitmask = networkTypeBitmask;
            return this;
        }

        /**
         * Sets lingering Radio Technology (Network Type) for this APN.
         *
         * @param lingeringNetworkTypeBitmask the Radio Technology (Network Type) that should linger
         * @hide
         */
        @NonNull
        public Builder setLingeringNetworkTypeBitmask(@TelephonyManager.NetworkTypeBitMask
                long lingeringNetworkTypeBitmask) {
            this.mLingeringNetworkTypeBitmask = lingeringNetworkTypeBitmask;
            return this;
        }

        /**
         * Sets the MVNO match type for this APN.
         *
         * @param mvnoType the MVNO match type to set for this APN
         */
        @NonNull
        public Builder setMvnoType(@MvnoType int mvnoType) {
            this.mMvnoType = mvnoType;
            return this;
        }

        /**
         * Sets the carrier id for this APN.
         *
         * See {@link TelephonyManager#getSimCarrierId()} which provides more background for what a
         * carrier ID is.
         *
         * @param carrierId the carrier id to set for this APN
         */
        @NonNull
        public Builder setCarrierId(int carrierId) {
            this.mCarrierId = carrierId;
            return this;
        }

        /**
         * Sets skip464xlat flag for this APN.
         *
         * @param skip464xlat skip464xlat for this APN.
         * @hide
         */
        public Builder setSkip464Xlat(@Skip464XlatStatus int skip464xlat) {
            this.mSkip464Xlat = skip464xlat;
            return this;
        }

        /**
         * Sets whether the PDU session brought up by this APN should always be on.
         * See 3GPP TS 23.501 section 5.6.13
         *
         * @param alwaysOn the always on status to set for this APN
         * @hide
         */
        public Builder setAlwaysOn(boolean alwaysOn) {
            this.mAlwaysOn = alwaysOn;
            return this;
        }

        /**
         * Set the infrastructure bitmask.
         *
         * @param infrastructureBitmask The infrastructure bitmask of which the APN can be used on.
         * For example, some APNs can only be used when the device is on cellular, on satellite, or
         * both.
         *
         * @return The builder.
         * @hide
         */
        @NonNull
        public Builder setInfrastructureBitmask(@InfrastructureBitmask int infrastructureBitmask) {
            this.mInfrastructureBitmask = infrastructureBitmask;
            return this;
        }

        /**
         * Sets esim bootstrap provisioning flag
         *
         * @param esimBootstrapProvisioning {@code true} if the APN is used for eSIM bootstrap
         * provisioning, {@code false} otherwise.
         * @hide
         */
        @NonNull
        public Builder setEsimBootstrapProvisioning(boolean esimBootstrapProvisioning) {
            this.mEsimBootstrapProvisioning = esimBootstrapProvisioning;
            return this;
        }

        /**
         * Builds {@link ApnSetting} from this builder.
         *
         * @return {@code null} if {@link #setApnName(String)} or {@link #setEntryName(String)}
         * is empty, or {@link #setApnTypeBitmask(int)} doesn't contain a valid bit,
         * {@link ApnSetting} built from this builder otherwise.
         */
        public ApnSetting build() {
            if ((mApnTypeBitmask & (TYPE_DEFAULT | TYPE_MMS | TYPE_SUPL | TYPE_DUN | TYPE_HIPRI
                    | TYPE_FOTA | TYPE_IMS | TYPE_CBS | TYPE_IA | TYPE_EMERGENCY | TYPE_MCX
                    | TYPE_XCAP | TYPE_VSIM | TYPE_BIP | TYPE_ENTERPRISE | TYPE_RCS)) == 0
                || TextUtils.isEmpty(mApnName) || TextUtils.isEmpty(mEntryName)) {
                return null;
            }
            if ((mApnTypeBitmask & TYPE_MMS) != 0 && !TextUtils.isEmpty(mMmsProxyAddress)
                    && mMmsProxyAddress.startsWith("http")) {
                Log.wtf(LOG_TAG,"mms proxy(" + mMmsProxyAddress
                        + ") should be a hostname, not a url");
                Uri mMmsProxyAddressUri = Uri.parse(mMmsProxyAddress);
                mMmsProxyAddress = mMmsProxyAddressUri.getHost();
            }
            return new ApnSetting(this);
        }

        /**
         * Builds {@link ApnSetting} from this builder. This function doesn't check if
         * {@link #setApnName(String)} or {@link #setEntryName(String)}, or
         * {@link #setApnTypeBitmask(int)} is empty.
         * @hide
         */
        public ApnSetting buildWithoutCheck() {
            return new ApnSetting(this);
        }
    }
}
