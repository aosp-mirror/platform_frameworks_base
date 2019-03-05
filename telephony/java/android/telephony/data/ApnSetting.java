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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.content.ContentValues;
import android.database.Cursor;
import android.hardware.radio.V1_4.ApnTypes;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.provider.Telephony;
import android.provider.Telephony.Carriers;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
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
     * APN type for all APNs.
     * @hide
     */
    public static final int TYPE_ALL = ApnTypes.ALL | ApnTypes.MCX;
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

    /** @hide */
    @IntDef(flag = true, prefix = { "TYPE_" }, value = {
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
        TYPE_MCX
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ApnType {}

    // Possible values for authentication types.
    /** No authentication type. */
    public static final int AUTH_TYPE_NONE = 0;
    /** Authentication type for PAP. */
    public static final int AUTH_TYPE_PAP = 1;
    /** Authentication type for CHAP. */
    public static final int AUTH_TYPE_CHAP = 2;
    /** Authentication type for PAP or CHAP. */
    public static final int AUTH_TYPE_PAP_OR_CHAP = 3;

    /** @hide */
    @IntDef(prefix = { "AUTH_TYPE_" }, value = {
        AUTH_TYPE_NONE,
        AUTH_TYPE_PAP,
        AUTH_TYPE_CHAP,
        AUTH_TYPE_PAP_OR_CHAP,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface AuthType {}

    // Possible values for protocol which is defined in TS 27.007 section 10.1.1.
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
        MVNO_TYPE_SPN,
        MVNO_TYPE_IMSI,
        MVNO_TYPE_GID,
        MVNO_TYPE_ICCID,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface MvnoType {}

    private static final Map<String, Integer> APN_TYPE_STRING_MAP;
    private static final Map<Integer, String> APN_TYPE_INT_MAP;
    private static final Map<String, Integer> PROTOCOL_STRING_MAP;
    private static final Map<Integer, String> PROTOCOL_INT_MAP;
    private static final Map<String, Integer> MVNO_TYPE_STRING_MAP;
    private static final Map<Integer, String> MVNO_TYPE_INT_MAP;

    static {
        APN_TYPE_STRING_MAP = new ArrayMap<String, Integer>();
        APN_TYPE_STRING_MAP.put("*", TYPE_ALL);
        APN_TYPE_STRING_MAP.put("default", TYPE_DEFAULT);
        APN_TYPE_STRING_MAP.put("mms", TYPE_MMS);
        APN_TYPE_STRING_MAP.put("supl", TYPE_SUPL);
        APN_TYPE_STRING_MAP.put("dun", TYPE_DUN);
        APN_TYPE_STRING_MAP.put("hipri", TYPE_HIPRI);
        APN_TYPE_STRING_MAP.put("fota", TYPE_FOTA);
        APN_TYPE_STRING_MAP.put("ims", TYPE_IMS);
        APN_TYPE_STRING_MAP.put("cbs", TYPE_CBS);
        APN_TYPE_STRING_MAP.put("ia", TYPE_IA);
        APN_TYPE_STRING_MAP.put("emergency", TYPE_EMERGENCY);
        APN_TYPE_STRING_MAP.put("mcx", TYPE_MCX);
        APN_TYPE_INT_MAP = new ArrayMap<Integer, String>();
        APN_TYPE_INT_MAP.put(TYPE_DEFAULT, "default");
        APN_TYPE_INT_MAP.put(TYPE_MMS, "mms");
        APN_TYPE_INT_MAP.put(TYPE_SUPL, "supl");
        APN_TYPE_INT_MAP.put(TYPE_DUN, "dun");
        APN_TYPE_INT_MAP.put(TYPE_HIPRI, "hipri");
        APN_TYPE_INT_MAP.put(TYPE_FOTA, "fota");
        APN_TYPE_INT_MAP.put(TYPE_IMS, "ims");
        APN_TYPE_INT_MAP.put(TYPE_CBS, "cbs");
        APN_TYPE_INT_MAP.put(TYPE_IA, "ia");
        APN_TYPE_INT_MAP.put(TYPE_EMERGENCY, "emergency");
        APN_TYPE_INT_MAP.put(TYPE_MCX, "mcx");

        PROTOCOL_STRING_MAP = new ArrayMap<String, Integer>();
        PROTOCOL_STRING_MAP.put("IP", PROTOCOL_IP);
        PROTOCOL_STRING_MAP.put("IPV6", PROTOCOL_IPV6);
        PROTOCOL_STRING_MAP.put("IPV4V6", PROTOCOL_IPV4V6);
        PROTOCOL_STRING_MAP.put("PPP", PROTOCOL_PPP);
        PROTOCOL_STRING_MAP.put("NON-IP", PROTOCOL_NON_IP);
        PROTOCOL_STRING_MAP.put("UNSTRUCTURED", PROTOCOL_UNSTRUCTURED);
        PROTOCOL_INT_MAP = new ArrayMap<Integer, String>();
        PROTOCOL_INT_MAP.put(PROTOCOL_IP, "IP");
        PROTOCOL_INT_MAP.put(PROTOCOL_IPV6, "IPV6");
        PROTOCOL_INT_MAP.put(PROTOCOL_IPV4V6, "IPV4V6");
        PROTOCOL_INT_MAP.put(PROTOCOL_PPP, "PPP");
        PROTOCOL_INT_MAP.put(PROTOCOL_NON_IP, "NON-IP");
        PROTOCOL_INT_MAP.put(PROTOCOL_UNSTRUCTURED, "UNSTRUCTURED");

        MVNO_TYPE_STRING_MAP = new ArrayMap<String, Integer>();
        MVNO_TYPE_STRING_MAP.put("spn", MVNO_TYPE_SPN);
        MVNO_TYPE_STRING_MAP.put("imsi", MVNO_TYPE_IMSI);
        MVNO_TYPE_STRING_MAP.put("gid", MVNO_TYPE_GID);
        MVNO_TYPE_STRING_MAP.put("iccid", MVNO_TYPE_ICCID);
        MVNO_TYPE_INT_MAP = new ArrayMap<Integer, String>();
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
    private final int mMtu;

    private final boolean mCarrierEnabled;

    private final int mNetworkTypeBitmask;

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

    /**
     * Returns the MTU size of the mobile interface to which the APN connected.
     *
     * @return the MTU size of the APN
     * @hide
     */
    public int getMtu() {
        return mMtu;
    }

    /**
     * Returns the profile id to which the APN saved in modem.
     *
     * @return the profile id of the APN
     * @hide
     */
    public int getProfileId() {
        return mProfileId;
    }

    /**
     * Returns if the APN setting is persistent on the modem.
     *
     * @return is the APN setting to be set in modem
     * @hide
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
     * Returns a bitmask describing the Radio Technologies(Network Types) which this APN may use.
     *
     * NetworkType bitmask is calculated from NETWORK_TYPE defined in {@link TelephonyManager}.
     *
     * Examples of Network Types include {@link TelephonyManager#NETWORK_TYPE_UNKNOWN},
     * {@link TelephonyManager#NETWORK_TYPE_GPRS}, {@link TelephonyManager#NETWORK_TYPE_EDGE}.
     *
     * @return a bitmask describing the Radio Technologies(Network Types)
     */
    public int getNetworkTypeBitmask() {
        return mNetworkTypeBitmask;
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
    @Carriers.Skip464XlatStatus
    public int getSkip464Xlat() {
        return mSkip464Xlat;
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
        this.mAuthType = builder.mAuthType;
        this.mApnTypeBitmask = builder.mApnTypeBitmask;
        this.mId = builder.mId;
        this.mOperatorNumeric = builder.mOperatorNumeric;
        this.mProtocol = builder.mProtocol;
        this.mRoamingProtocol = builder.mRoamingProtocol;
        this.mMtu = builder.mMtu;
        this.mCarrierEnabled = builder.mCarrierEnabled;
        this.mNetworkTypeBitmask = builder.mNetworkTypeBitmask;
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
    }

    /**
     * @hide
     */
    public static ApnSetting makeApnSetting(int id, String operatorNumeric, String entryName,
            String apnName, String proxyAddress, int proxyPort, Uri mmsc,
            String mmsProxyAddress, int mmsProxyPort, String user, String password,
            int authType, int mApnTypeBitmask, int protocol, int roamingProtocol,
            boolean carrierEnabled, int networkTypeBitmask, int profileId,
            boolean modemCognitive, int maxConns, int waitTime, int maxConnsTime, int mtu,
            int mvnoType, String mvnoMatchData, int apnSetId, int carrierId, int skip464xlat) {
        return new Builder()
            .setId(id)
            .setOperatorNumeric(operatorNumeric)
            .setEntryName(entryName)
            .setApnName(apnName)
            .setProxyAddress(proxyAddress)
            .setProxyPort(proxyPort)
            .setMmsc(mmsc)
            .setMmsProxyAddress(mmsProxyAddress)
            .setMmsProxyPort(mmsProxyPort)
            .setUser(user)
            .setPassword(password)
            .setAuthType(authType)
            .setApnTypeBitmask(mApnTypeBitmask)
            .setProtocol(protocol)
            .setRoamingProtocol(roamingProtocol)
            .setCarrierEnabled(carrierEnabled)
            .setNetworkTypeBitmask(networkTypeBitmask)
            .setProfileId(profileId)
            .setModemCognitive(modemCognitive)
            .setMaxConns(maxConns)
            .setWaitTime(waitTime)
            .setMaxConnsTime(maxConnsTime)
            .setMtu(mtu)
            .setMvnoType(mvnoType)
            .setMvnoMatchData(mvnoMatchData)
            .setApnSetId(apnSetId)
            .setCarrierId(carrierId)
            .setSkip464Xlat(skip464xlat)
            .buildWithoutCheck();
    }

    /**
     * @hide
     */
    public static ApnSetting makeApnSetting(int id, String operatorNumeric, String entryName,
            String apnName, String proxyAddress, int proxyPort, Uri mmsc,
            String mmsProxyAddress, int mmsProxyPort, String user, String password,
            int authType, int mApnTypeBitmask, int protocol, int roamingProtocol,
            boolean carrierEnabled, int networkTypeBitmask, int profileId, boolean modemCognitive,
            int maxConns, int waitTime, int maxConnsTime, int mtu, int mvnoType,
            String mvnoMatchData) {
        return makeApnSetting(id, operatorNumeric, entryName, apnName, proxyAddress, proxyPort,
            mmsc, mmsProxyAddress, mmsProxyPort, user, password, authType, mApnTypeBitmask,
            protocol, roamingProtocol, carrierEnabled, networkTypeBitmask, profileId,
            modemCognitive, maxConns, waitTime, maxConnsTime, mtu, mvnoType, mvnoMatchData,
            Carriers.NO_APN_SET_ID, TelephonyManager.UNKNOWN_CARRIER_ID,
            Carriers.SKIP_464XLAT_DEFAULT);
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

        return makeApnSetting(
            cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Carriers._ID)),
            cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.NUMERIC)),
            cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.NAME)),
            cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.APN)),
            cursor.getString(
                cursor.getColumnIndexOrThrow(Telephony.Carriers.PROXY)),
            portFromString(cursor.getString(
                cursor.getColumnIndexOrThrow(Telephony.Carriers.PORT))),
            UriFromString(cursor.getString(
                cursor.getColumnIndexOrThrow(Telephony.Carriers.MMSC))),
            cursor.getString(
                cursor.getColumnIndexOrThrow(Telephony.Carriers.MMSPROXY)),
            portFromString(cursor.getString(
                cursor.getColumnIndexOrThrow(Telephony.Carriers.MMSPORT))),
            cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.USER)),
            cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.PASSWORD)),
            cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Carriers.AUTH_TYPE)),
            apnTypesBitmask,
            getProtocolIntFromString(
                cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Carriers.PROTOCOL))),
            getProtocolIntFromString(
                cursor.getString(cursor.getColumnIndexOrThrow(
                    Telephony.Carriers.ROAMING_PROTOCOL))),
            cursor.getInt(cursor.getColumnIndexOrThrow(
                Telephony.Carriers.CARRIER_ENABLED)) == 1,
            networkTypeBitmask,
            cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Carriers.PROFILE_ID)),
            cursor.getInt(cursor.getColumnIndexOrThrow(
                Telephony.Carriers.MODEM_PERSIST)) == 1,
            cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Carriers.MAX_CONNECTIONS)),
            cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Carriers.WAIT_TIME_RETRY)),
            cursor.getInt(cursor.getColumnIndexOrThrow(
                Telephony.Carriers.TIME_LIMIT_FOR_MAX_CONNECTIONS)),
            cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Carriers.MTU)),
            getMvnoTypeIntFromString(
                cursor.getString(cursor.getColumnIndexOrThrow(
                    Telephony.Carriers.MVNO_TYPE))),
            cursor.getString(cursor.getColumnIndexOrThrow(
                Telephony.Carriers.MVNO_MATCH_DATA)),
            cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Carriers.APN_SET_ID)),
            cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Carriers.CARRIER_ID)),
            cursor.getInt(cursor.getColumnIndexOrThrow(Carriers.SKIP_464XLAT)));
    }

    /**
     * @hide
     */
    public static ApnSetting makeApnSetting(ApnSetting apn) {
        return makeApnSetting(apn.mId, apn.mOperatorNumeric, apn.mEntryName, apn.mApnName,
            apn.mProxyAddress, apn.mProxyPort, apn.mMmsc, apn.mMmsProxyAddress,
            apn.mMmsProxyPort, apn.mUser, apn.mPassword, apn.mAuthType, apn.mApnTypeBitmask,
            apn.mProtocol, apn.mRoamingProtocol, apn.mCarrierEnabled, apn.mNetworkTypeBitmask,
            apn.mProfileId, apn.mPersistent, apn.mMaxConns, apn.mWaitTime,
            apn.mMaxConnsTime, apn.mMtu, apn.mMvnoType, apn.mMvnoMatchData, apn.mApnSetId,
            apn.mCarrierId, apn.mSkip464Xlat);
    }

    /**
     * Creates an ApnSetting object from a string.
     *
     * @param data the string to read.
     *
     * The string must be in one of two formats (newlines added for clarity,
     * spaces are optional):
     *
     * v1 format:
     *   <carrier>, <apn>, <proxy>, <port>, <user>, <password>, <server>,
     *   <mmsc>, <mmsproxy>, <mmsport>, <mcc>, <mnc>, <authtype>,
     *   <type>[| <type>...],
     *
     * v2 format:
     *   [ApnSettingV2] <carrier>, <apn>, <proxy>, <port>, <user>, <password>, <server>,
     *   <mmsc>, <mmsproxy>, <mmsport>, <mcc>, <mnc>, <authtype>,
     *   <type>[| <type>...], <protocol>, <roaming_protocol>, <carrierEnabled>, <bearerBitmask>,
     *
     * v3 format:
     *   [ApnSettingV3] <carrier>, <apn>, <proxy>, <port>, <user>, <password>, <server>,
     *   <mmsc>, <mmsproxy>, <mmsport>, <mcc>, <mnc>, <authtype>,
     *   <type>[| <type>...], <protocol>, <roaming_protocol>, <carrierEnabled>, <bearerBitmask>,
     *   <profileId>, <modemCognitive>, <maxConns>, <waitTime>, <maxConnsTime>, <mtu>,
     *   <mvnoType>, <mvnoMatchData>
     *
     * v4 format:
     *   [ApnSettingV4] <carrier>, <apn>, <proxy>, <port>, <user>, <password>, <server>,
     *   <mmsc>, <mmsproxy>, <mmsport>, <mcc>, <mnc>, <authtype>,
     *   <type>[| <type>...], <protocol>, <roaming_protocol>, <carrierEnabled>, <bearerBitmask>,
     *   <profileId>, <modemCognitive>, <maxConns>, <waitTime>, <maxConnsTime>, <mtu>,
     *   <mvnoType>, <mvnoMatchData>, <networkTypeBitmask>
     *
     * v5 format:
     *   [ApnSettingV5] <carrier>, <apn>, <proxy>, <port>, <user>, <password>, <server>,
     *   <mmsc>, <mmsproxy>, <mmsport>, <mcc>, <mnc>, <authtype>,
     *   <type>[| <type>...], <protocol>, <roaming_protocol>, <carrierEnabled>, <bearerBitmask>,
     *   <profileId>, <modemCognitive>, <maxConns>, <waitTime>, <maxConnsTime>, <mtu>,
     *   <mvnoType>, <mvnoMatchData>, <networkTypeBitmask>, <apnSetId>
     *
     * v6 format:
     *   [ApnSettingV6] <carrier>, <apn>, <proxy>, <port>, <user>, <password>, <server>,
     *   <mmsc>, <mmsproxy>, <mmsport>, <mcc>, <mnc>, <authtype>,
     *   <type>[| <type>...], <protocol>, <roaming_protocol>, <carrierEnabled>, <bearerBitmask>,
     *   <profileId>, <modemCognitive>, <maxConns>, <waitTime>, <maxConnsTime>, <mtu>,
     *   <mvnoType>, <mvnoMatchData>, <networkTypeBitmask>, <apnSetId>, <carrierId>
     *
     * v7 format:
     *   [ApnSettingV7] <carrier>, <apn>, <proxy>, <port>, <user>, <password>, <server>,
     *   <mmsc>, <mmsproxy>, <mmsport>, <mcc>, <mnc>, <authtype>,
     *   <type>[| <type>...], <protocol>, <roaming_protocol>, <carrierEnabled>, <bearerBitmask>,
     *   <profileId>, <modemCognitive>, <maxConns>, <waitTime>, <maxConnsTime>, <mtu>,
     *   <mvnoType>, <mvnoMatchData>, <networkTypeBitmask>, <apnSetId>, <carrierId>, <skip464xlat>
     *
     * Note that the strings generated by {@link #toString()} do not contain the username
     * and password and thus cannot be read by this method.
     *
     * This method may return {@code null} if the input string is invalid.
     *
     * @hide
     */
    public static ApnSetting fromString(String data) {
        if (data == null) return null;

        int version;
        // matches() operates on the whole string, so append .* to the regex.
        if (data.matches(V7_FORMAT_REGEX + ".*")) {
            version = 7;
            data = data.replaceFirst(V7_FORMAT_REGEX, "");
        } else if (data.matches(V6_FORMAT_REGEX + ".*")) {
            version = 6;
            data = data.replaceFirst(V6_FORMAT_REGEX, "");
        } else if (data.matches(V5_FORMAT_REGEX + ".*")) {
            version = 5;
            data = data.replaceFirst(V5_FORMAT_REGEX, "");
        } else if (data.matches(V4_FORMAT_REGEX + ".*")) {
            version = 4;
            data = data.replaceFirst(V4_FORMAT_REGEX, "");
        } else if (data.matches(V3_FORMAT_REGEX + ".*")) {
            version = 3;
            data = data.replaceFirst(V3_FORMAT_REGEX, "");
        } else if (data.matches(V2_FORMAT_REGEX + ".*")) {
            version = 2;
            data = data.replaceFirst(V2_FORMAT_REGEX, "");
        } else {
            version = 1;
        }

        String[] a = data.split("\\s*,\\s*", -1);
        if (a.length < 14) {
            return null;
        }

        int authType;
        try {
            authType = Integer.parseInt(a[12]);
        } catch (NumberFormatException e) {
            authType = 0;
        }

        String[] typeArray;
        String protocol, roamingProtocol;
        boolean carrierEnabled;
        int bearerBitmask = 0;
        int networkTypeBitmask = 0;
        int profileId = 0;
        boolean modemCognitive = false;
        int maxConns = 0;
        int waitTime = 0;
        int maxConnsTime = 0;
        int mtu = UNSET_MTU;
        String mvnoType = "";
        String mvnoMatchData = "";
        int apnSetId = Carriers.NO_APN_SET_ID;
        int carrierId = TelephonyManager.UNKNOWN_CARRIER_ID;
        int skip464xlat = Carriers.SKIP_464XLAT_DEFAULT;
        if (version == 1) {
            typeArray = new String[a.length - 13];
            System.arraycopy(a, 13, typeArray, 0, a.length - 13);
            protocol = PROTOCOL_INT_MAP.get(PROTOCOL_IP);
            roamingProtocol = PROTOCOL_INT_MAP.get(PROTOCOL_IP);
            carrierEnabled = true;
        } else {
            if (a.length < 18) {
                return null;
            }
            typeArray = a[13].split("\\s*\\|\\s*");
            protocol = a[14];
            roamingProtocol = a[15];
            carrierEnabled = Boolean.parseBoolean(a[16]);

            bearerBitmask = ServiceState.getBitmaskFromString(a[17]);

            if (a.length > 22) {
                modemCognitive = Boolean.parseBoolean(a[19]);
                try {
                    profileId = Integer.parseInt(a[18]);
                    maxConns = Integer.parseInt(a[20]);
                    waitTime = Integer.parseInt(a[21]);
                    maxConnsTime = Integer.parseInt(a[22]);
                } catch (NumberFormatException e) {
                }
            }
            if (a.length > 23) {
                try {
                    mtu = Integer.parseInt(a[23]);
                } catch (NumberFormatException e) {
                }
            }
            if (a.length > 25) {
                mvnoType = a[24];
                mvnoMatchData = a[25];
            }
            if (a.length > 26) {
                networkTypeBitmask = ServiceState.getBitmaskFromString(a[26]);
            }
            if (a.length > 27) {
                apnSetId = Integer.parseInt(a[27]);
            }
            if (a.length > 28) {
                carrierId = Integer.parseInt(a[28]);
            }
            if (a.length > 29) {
                try {
                    skip464xlat = Integer.parseInt(a[29]);
                } catch (NumberFormatException e) {
                }
            }
        }

        // If both bearerBitmask and networkTypeBitmask were specified, bearerBitmask would be
        // ignored.
        if (networkTypeBitmask == 0) {
            networkTypeBitmask =
                ServiceState.convertBearerBitmaskToNetworkTypeBitmask(bearerBitmask);
        }
        return makeApnSetting(-1, a[10] + a[11], a[0], a[1], a[2],
            portFromString(a[3]), UriFromString(a[7]), a[8],
            portFromString(a[9]), a[4], a[5], authType,
            getApnTypesBitmaskFromString(TextUtils.join(",", typeArray)),
            getProtocolIntFromString(protocol), getProtocolIntFromString(roamingProtocol),
            carrierEnabled, networkTypeBitmask, profileId, modemCognitive, maxConns, waitTime,
            maxConnsTime, mtu, getMvnoTypeIntFromString(mvnoType), mvnoMatchData, apnSetId,
            carrierId, skip464xlat);
    }

    /**
     * Creates an array of ApnSetting objects from a string.
     *
     * @param data the string to read.
     *
     * Builds on top of the same format used by fromString, but allows for multiple entries
     * separated by ";".
     *
     * @hide
     */
    public static List<ApnSetting> arrayFromString(String data) {
        List<ApnSetting> retVal = new ArrayList<ApnSetting>();
        if (TextUtils.isEmpty(data)) {
            return retVal;
        }
        String[] apnStrings = data.split("\\s*;\\s*");
        for (String apnString : apnStrings) {
            ApnSetting apn = fromString(apnString);
            if (apn != null) {
                retVal.add(apn);
            }
        }
        return retVal;
    }

    /**
     * Returns the string representation of ApnSetting.
     *
     * This method prints null for unset elements. The output doesn't contain password or user.
     * @hide
     */
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[ApnSettingV7] ")
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
        sb.append(", ").append(mMtu);
        sb.append(", ").append(MVNO_TYPE_INT_MAP.get(mMvnoType));
        sb.append(", ").append(mMvnoMatchData);
        sb.append(", ").append(mPermanentFailed);
        sb.append(", ").append(mNetworkTypeBitmask);
        sb.append(", ").append(mApnSetId);
        sb.append(", ").append(mCarrierId);
        sb.append(", ").append(mSkip464Xlat);
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
    public boolean canHandleType(@ApnType int type) {
        if (!mCarrierEnabled) {
            return false;
        }
        // DEFAULT can handle HIPRI.
        if (hasApnType(type)) {
            return true;
        }
        return false;
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

    // TODO - if we have this function we should also have hashCode.
    // Also should handle changes in type order and perhaps case-insensitivity.

    /**
     * @hide
     */
    public boolean equals(Object o) {
        if (o instanceof ApnSetting == false) {
            return false;
        }

        ApnSetting other = (ApnSetting) o;

        return mEntryName.equals(other.mEntryName)
            && Objects.equals(mId, other.mId)
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
            && Objects.equals(mProtocol, other.mProtocol)
            && Objects.equals(mRoamingProtocol, other.mRoamingProtocol)
            && Objects.equals(mCarrierEnabled, other.mCarrierEnabled)
            && Objects.equals(mProfileId, other.mProfileId)
            && Objects.equals(mPersistent, other.mPersistent)
            && Objects.equals(mMaxConns, other.mMaxConns)
            && Objects.equals(mWaitTime, other.mWaitTime)
            && Objects.equals(mMaxConnsTime, other.mMaxConnsTime)
            && Objects.equals(mMtu, other.mMtu)
            && Objects.equals(mMvnoType, other.mMvnoType)
            && Objects.equals(mMvnoMatchData, other.mMvnoMatchData)
            && Objects.equals(mNetworkTypeBitmask, other.mNetworkTypeBitmask)
            && Objects.equals(mApnSetId, other.mApnSetId)
            && Objects.equals(mCarrierId, other.mCarrierId)
            && Objects.equals(mSkip464Xlat, other.mSkip464Xlat);
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
            && (isDataRoaming || Objects.equals(mProtocol, other.mProtocol))
            && (!isDataRoaming || Objects.equals(mRoamingProtocol, other.mRoamingProtocol))
            && Objects.equals(mCarrierEnabled, other.mCarrierEnabled)
            && Objects.equals(mProfileId, other.mProfileId)
            && Objects.equals(mPersistent, other.mPersistent)
            && Objects.equals(mMaxConns, other.mMaxConns)
            && Objects.equals(mWaitTime, other.mWaitTime)
            && Objects.equals(mMaxConnsTime, other.mMaxConnsTime)
            && Objects.equals(mMtu, other.mMtu)
            && Objects.equals(mMvnoType, other.mMvnoType)
            && Objects.equals(mMvnoMatchData, other.mMvnoMatchData)
            && Objects.equals(mApnSetId, other.mApnSetId)
            && Objects.equals(mCarrierId, other.mCarrierId)
            && Objects.equals(mSkip464Xlat, other.mSkip464Xlat);
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
            && !typeSameAny(this, other)
            && xorEquals(this.mProxyAddress, other.mProxyAddress)
            && xorEqualsInt(this.mProxyPort, other.mProxyPort)
            && xorEquals(this.mProtocol, other.mProtocol)
            && xorEquals(this.mRoamingProtocol, other.mRoamingProtocol)
            && Objects.equals(this.mCarrierEnabled, other.mCarrierEnabled)
            && Objects.equals(this.mProfileId, other.mProfileId)
            && Objects.equals(this.mMvnoType, other.mMvnoType)
            && Objects.equals(this.mMvnoMatchData, other.mMvnoMatchData)
            && xorEquals(this.mMmsc, other.mMmsc)
            && xorEquals(this.mMmsProxyAddress, other.mMmsProxyAddress)
            && xorEqualsInt(this.mMmsProxyPort, other.mMmsProxyPort))
            && Objects.equals(this.mNetworkTypeBitmask, other.mNetworkTypeBitmask)
            && Objects.equals(mApnSetId, other.mApnSetId)
            && Objects.equals(mCarrierId, other.mCarrierId)
            && Objects.equals(mSkip464Xlat, other.mSkip464Xlat);
    }

    // Equal or one is null.
    private boolean xorEquals(Object first, Object second) {
        return first == null || second == null || first.equals(second);
    }

    // Equal or one is not specified.
    private boolean xorEqualsInt(int first, int second) {
        return first == UNSPECIFIED_INT || second == UNSPECIFIED_INT
            || Objects.equals(first, second);
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
        apnValue.put(Telephony.Carriers.NETWORK_TYPE_BITMASK, mNetworkTypeBitmask);
        apnValue.put(Telephony.Carriers.CARRIER_ID, mCarrierId);
        apnValue.put(Telephony.Carriers.SKIP_464XLAT, mSkip464Xlat);

        return apnValue;
    }

    /**
     * @param apnTypeBitmask bitmask of APN types.
     * @return comma delimited list of APN types.
     * @hide
     */
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
     * @param apnType APN type
     * @return APN type in string format
     * @hide
     */
    public static String getApnTypeString(int apnType) {
        String apnTypeString = APN_TYPE_INT_MAP.get(apnType);
        return apnTypeString == null ? "Unknown" : apnTypeString;
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
                Integer type = APN_TYPE_STRING_MAP.get(str.toLowerCase());
                if (type != null) {
                    result |= type;
                }
            }
            return result;
        }
    }

    /** @hide */
    public static int getMvnoTypeIntFromString(String mvnoType) {
        String mvnoTypeString = TextUtils.isEmpty(mvnoType) ? mvnoType : mvnoType.toLowerCase();
        Integer mvnoTypeInt = MVNO_TYPE_STRING_MAP.get(mvnoTypeString);
        return  mvnoTypeInt == null ? UNSPECIFIED_INT : mvnoTypeInt;
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
        dest.writeValue(mMmsc);
        dest.writeString(mMmsProxyAddress);
        dest.writeInt(mMmsProxyPort);
        dest.writeString(mUser);
        dest.writeString(mPassword);
        dest.writeInt(mAuthType);
        dest.writeInt(mApnTypeBitmask);
        dest.writeInt(mProtocol);
        dest.writeInt(mRoamingProtocol);
        dest.writeBoolean(mCarrierEnabled);
        dest.writeInt(mMvnoType);
        dest.writeInt(mNetworkTypeBitmask);
        dest.writeInt(mApnSetId);
        dest.writeInt(mCarrierId);
        dest.writeInt(mSkip464Xlat);
    }

    private static ApnSetting readFromParcel(Parcel in) {
        final int id = in.readInt();
        final String operatorNumeric = in.readString();
        final String entryName = in.readString();
        final String apnName = in.readString();
        final String proxy = in.readString();
        final int port = in.readInt();
        final Uri mmsc = (Uri) in.readValue(Uri.class.getClassLoader());
        final String mmsProxy = in.readString();
        final int mmsPort = in.readInt();
        final String user = in.readString();
        final String password = in.readString();
        final int authType = in.readInt();
        final int apnTypesBitmask = in.readInt();
        final int protocol = in.readInt();
        final int roamingProtocol = in.readInt();
        final boolean carrierEnabled = in.readBoolean();
        final int mvnoType = in.readInt();
        final int networkTypeBitmask = in.readInt();
        final int apnSetId = in.readInt();
        final int carrierId = in.readInt();
        final int skip464xlat = in.readInt();

        return makeApnSetting(id, operatorNumeric, entryName, apnName,
                proxy, port, mmsc, mmsProxy, mmsPort, user, password, authType, apnTypesBitmask,
                protocol, roamingProtocol, carrierEnabled, networkTypeBitmask, 0, false,
                0, 0, 0, 0, mvnoType, null, apnSetId, carrierId, skip464xlat);
    }

    public static final Parcelable.Creator<ApnSetting> CREATOR =
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
     * // available, so supply a dummy (0.0.0.0) IPv4 address to avoid DNS lookup.
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
        private int mAuthType;
        private int mApnTypeBitmask;
        private int mId;
        private String mOperatorNumeric;
        private int mProtocol = UNSPECIFIED_INT;
        private int mRoamingProtocol = UNSPECIFIED_INT;
        private int mMtu;
        private int mNetworkTypeBitmask;
        private boolean mCarrierEnabled;
        private int mProfileId;
        private boolean mModemCognitive;
        private int mMaxConns;
        private int mWaitTime;
        private int mMaxConnsTime;
        private int mMvnoType = UNSPECIFIED_INT;
        private String mMvnoMatchData;
        private int mApnSetId;
        private int mCarrierId = TelephonyManager.UNKNOWN_CARRIER_ID;
        private int mSkip464Xlat = Carriers.SKIP_464XLAT_DEFAULT;

        /**
         * Default constructor for Builder.
         */
        public Builder() {}

        /**
         * Sets the unique database id for this entry.
         *
         * @param id the unique database id to set for this entry
         */
        private Builder setId(int id) {
            this.mId = id;
            return this;
        }

        /**
         * Set the MTU size of the mobile interface to which the APN connected.
         *
         * @param mtu the MTU size to set for the APN
         * @hide
         */
        public Builder setMtu(int mtu) {
            this.mMtu = mtu;
            return this;
        }

        /**
         * Sets the profile id to which the APN saved in modem.
         *
         * @param profileId the profile id to set for the APN
         * @hide
         */
        public Builder setProfileId(int profileId) {
            this.mProfileId = profileId;
            return this;
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
        public Builder setMvnoMatchData(String mvnoMatchData) {
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
        public Builder setEntryName(String entryName) {
            this.mEntryName = entryName;
            return this;
        }

        /**
         * Sets the name of the APN.
         *
         * @param apnName the name to set for the APN
         */
        public Builder setApnName(String apnName) {
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
         * hostname and a dummy IP address. See {@link ApnSetting.Builder above} for an example.
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
        public Builder setProxyAddress(String proxy) {
            this.mProxyAddress = proxy;
            return this;
        }

        /**
         * Sets the proxy port of the APN.
         *
         * @param port the proxy port to set for the APN
         */
        public Builder setProxyPort(int port) {
            this.mProxyPort = port;
            return this;
        }

        /**
         * Sets the MMSC Uri of the APN.
         *
         * @param mmsc the MMSC Uri to set for the APN
         */
        public Builder setMmsc(Uri mmsc) {
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
         * hostname and a dummy IP address. See {@link ApnSetting.Builder above} for an example.
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
        public Builder setMmsProxyAddress(String mmsProxy) {
            this.mMmsProxyAddress = mmsProxy;
            return this;
        }

        /**
         * Sets the MMS proxy port of the APN.
         *
         * @param mmsPort the MMS proxy port to set for the APN
         */
        public Builder setMmsProxyPort(int mmsPort) {
            this.mMmsProxyPort = mmsPort;
            return this;
        }

        /**
         * Sets the APN username of the APN.
         *
         * @param user the APN username to set for the APN
         */
        public Builder setUser(String user) {
            this.mUser = user;
            return this;
        }

        /**
         * Sets the APN password of the APN.
         *
         * @see android.provider.Telephony.Carriers#PASSWORD
         * @param password the APN password to set for the APN
         */
        public Builder setPassword(String password) {
            this.mPassword = password;
            return this;
        }

        /**
         * Sets the authentication type of the APN.
         *
         * @param authType the authentication type to set for the APN
         */
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
        public Builder setOperatorNumeric(String operatorNumeric) {
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
        public Builder setRoamingProtocol(@ProtocolType  int roamingProtocol) {
            this.mRoamingProtocol = roamingProtocol;
            return this;
        }

        /**
         * Sets the current status for this APN.
         *
         * @param carrierEnabled the current status to set for this APN
         */
        public Builder setCarrierEnabled(boolean carrierEnabled) {
            this.mCarrierEnabled = carrierEnabled;
            return this;
        }

        /**
         * Sets Radio Technology (Network Type) info for this APN.
         *
         * @param networkTypeBitmask the Radio Technology (Network Type) info
         */
        public Builder setNetworkTypeBitmask(int networkTypeBitmask) {
            this.mNetworkTypeBitmask = networkTypeBitmask;
            return this;
        }

        /**
         * Sets the MVNO match type for this APN.
         *
         * @param mvnoType the MVNO match type to set for this APN
         */
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
        public Builder setCarrierId(int carrierId) {
            this.mCarrierId = carrierId;
            return this;
        }

        /**
         * Sets skip464xlat flag for this APN.
         *
         * @param skip464xlat skip464xlat for this APN
         * @hide
         */
        public Builder setSkip464Xlat(@Carriers.Skip464XlatStatus int skip464xlat) {
            this.mSkip464Xlat = skip464xlat;
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
            if ((mApnTypeBitmask & TYPE_ALL) == 0 || TextUtils.isEmpty(mApnName)
                || TextUtils.isEmpty(mEntryName)) {
                return null;
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
