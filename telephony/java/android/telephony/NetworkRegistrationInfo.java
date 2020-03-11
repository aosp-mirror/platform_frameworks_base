/*
 * Copyright 2017 The Android Open Source Project
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
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.AccessNetworkConstants.TransportType;
import android.telephony.Annotation.NetworkType;
import android.text.TextUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Description of a mobile network registration info
 */
public final class NetworkRegistrationInfo implements Parcelable {
    /**
     * Network domain
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "DOMAIN_", value = {DOMAIN_UNKNOWN, DOMAIN_CS, DOMAIN_PS, DOMAIN_CS_PS})
    public @interface Domain {}

    /** Unknown / Unspecified domain */
    public static final int DOMAIN_UNKNOWN = 0;
    /** Circuit switched domain */
    public static final int DOMAIN_CS = android.hardware.radio.V1_5.Domain.CS;
    /** Packet switched domain */
    public static final int DOMAIN_PS = android.hardware.radio.V1_5.Domain.PS;
    /** Applicable to both CS and PS Domain */
    public static final int DOMAIN_CS_PS = DOMAIN_CS | DOMAIN_PS;

    /**
     * Network registration state
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "REGISTRATION_STATE_",
            value = {REGISTRATION_STATE_NOT_REGISTERED_OR_SEARCHING, REGISTRATION_STATE_HOME,
                    REGISTRATION_STATE_NOT_REGISTERED_SEARCHING, REGISTRATION_STATE_DENIED,
                    REGISTRATION_STATE_UNKNOWN, REGISTRATION_STATE_ROAMING})
    public @interface RegistrationState {}

    /**
     * Not registered. The device is not currently searching a new operator to register.
     * @hide
     */
    @SystemApi @TestApi
    public static final int REGISTRATION_STATE_NOT_REGISTERED_OR_SEARCHING = 0;
    /**
     * Registered on home network.
     * @hide
     */
    @SystemApi @TestApi
    public static final int REGISTRATION_STATE_HOME = 1;
    /**
     * Not registered. The device is currently searching a new operator to register.
     * @hide
     */
    @SystemApi @TestApi
    public static final int REGISTRATION_STATE_NOT_REGISTERED_SEARCHING = 2;
    /**
     * Registration denied.
     * @hide
     */
    @SystemApi @TestApi
    public static final int REGISTRATION_STATE_DENIED = 3;
    /**
     * Registration state is unknown.
     * @hide
     */
    @SystemApi @TestApi
    public static final int REGISTRATION_STATE_UNKNOWN = 4;
    /**
     * Registered on roaming network.
     * @hide
     */
    @SystemApi @TestApi
    public static final int REGISTRATION_STATE_ROAMING = 5;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "NR_STATE_",
            value = {NR_STATE_NONE, NR_STATE_RESTRICTED, NR_STATE_NOT_RESTRICTED,
                    NR_STATE_CONNECTED})
    public @interface NRState {}

    /**
     * The device isn't camped on an LTE cell or the LTE cell doesn't support E-UTRA-NR
     * Dual Connectivity(EN-DC).
     */
    public static final int NR_STATE_NONE = 0;

    /**
     * The device is camped on an LTE cell that supports E-UTRA-NR Dual Connectivity(EN-DC) but
     * either the use of dual connectivity with NR(DCNR) is restricted or NR is not supported by
     * the selected PLMN.
     */
    public static final int NR_STATE_RESTRICTED = 1;

    /**
     * The device is camped on an LTE cell that supports E-UTRA-NR Dual Connectivity(EN-DC) and both
     * the use of dual connectivity with NR(DCNR) is not restricted and NR is supported by the
     * selected PLMN.
     */
    public static final int NR_STATE_NOT_RESTRICTED = 2;

    /**
     * The device is camped on an LTE cell that supports E-UTRA-NR Dual Connectivity(EN-DC) and
     * also connected to at least one 5G cell as a secondary serving cell.
     */
    public static final int NR_STATE_CONNECTED = 3;

    /**
     * Supported service type
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "SERVICE_TYPE_",
            value = {SERVICE_TYPE_UNKNOWN, SERVICE_TYPE_VOICE, SERVICE_TYPE_DATA, SERVICE_TYPE_SMS,
                    SERVICE_TYPE_VIDEO, SERVICE_TYPE_EMERGENCY})
    public @interface ServiceType {}

    /**
     * Unknown service
     */
    public static final int SERVICE_TYPE_UNKNOWN    = 0;

    /**
     * Voice service
     */
    public static final int SERVICE_TYPE_VOICE      = 1;

    /**
     * Data service
     */
    public static final int SERVICE_TYPE_DATA       = 2;

    /**
     * SMS service
     */
    public static final int SERVICE_TYPE_SMS        = 3;

    /**
     * Video service
     */
    public static final int SERVICE_TYPE_VIDEO      = 4;

    /**
     * Emergency service
     */
    public static final int SERVICE_TYPE_EMERGENCY  = 5;

    @Domain
    private final int mDomain;

    @TransportType
    private final int mTransportType;

    @RegistrationState
    private final int mRegistrationState;

    /**
     * Save the {@link ServiceState.RoamingType roaming type}. it can be overridden roaming type
     * from resource overlay or carrier config.
     */
    @ServiceState.RoamingType
    private int mRoamingType;

    @NetworkType
    private int mAccessNetworkTechnology;

    @NRState
    private int mNrState;

    private final int mRejectCause;

    private final boolean mEmergencyOnly;

    @ServiceType
    private final ArrayList<Integer> mAvailableServices;

    @Nullable
    private CellIdentity mCellIdentity;

    @Nullable
    private VoiceSpecificRegistrationInfo mVoiceSpecificInfo;

    @Nullable
    private DataSpecificRegistrationInfo mDataSpecificInfo;

    @NonNull
    private String mRplmn;

    /**
     * @param domain Network domain. Must be a {@link Domain}. For transport type
     * {@link AccessNetworkConstants#TRANSPORT_TYPE_WLAN}, this must set to {@link #DOMAIN_PS}.
     * @param transportType Transport type.
     * @param registrationState Network registration state. For transport type
     * {@link AccessNetworkConstants#TRANSPORT_TYPE_WLAN}, only
     * {@link #REGISTRATION_STATE_HOME} and {@link #REGISTRATION_STATE_NOT_REGISTERED_OR_SEARCHING}
     * are valid states.
     * @param accessNetworkTechnology Access network technology.For transport type
     * {@link AccessNetworkConstants#TRANSPORT_TYPE_WLAN}, set to
     * {@link TelephonyManager#NETWORK_TYPE_IWLAN}.
     * @param rejectCause Reason for denial if the registration state is
     * {@link #REGISTRATION_STATE_DENIED}. Depending on {@code accessNetworkTechnology}, the values
     * are defined in 3GPP TS 24.008 10.5.3.6 for UMTS, 3GPP TS 24.301 9.9.3.9 for LTE, and 3GPP2
     * A.S0001 6.2.2.44 for CDMA. If the reject cause is not supported or unknown, set it to 0.
     * // TODO: Add IWLAN reject cause reference
     * @param emergencyOnly True if this registration is for emergency only.
     * @param availableServices The list of the supported services.
     * @param cellIdentity The identity representing a unique cell or wifi AP. Set to null if the
     * information is not available.
     * @param rplmn the registered plmn or the last plmn for attempted registration if reg failed.
     */
    private NetworkRegistrationInfo(@Domain int domain, @TransportType int transportType,
                                   @RegistrationState int registrationState,
                                   @NetworkType int accessNetworkTechnology, int rejectCause,
                                   boolean emergencyOnly,
                                   @Nullable @ServiceType List<Integer> availableServices,
                                   @Nullable CellIdentity cellIdentity, @Nullable String rplmn) {
        mDomain = domain;
        mTransportType = transportType;
        mRegistrationState = registrationState;
        mRoamingType = (registrationState == REGISTRATION_STATE_ROAMING)
                ? ServiceState.ROAMING_TYPE_UNKNOWN : ServiceState.ROAMING_TYPE_NOT_ROAMING;
        mAccessNetworkTechnology = accessNetworkTechnology;
        mRejectCause = rejectCause;
        mAvailableServices = (availableServices != null)
                ? new ArrayList<>(availableServices) : new ArrayList<>();
        mCellIdentity = cellIdentity;
        mEmergencyOnly = emergencyOnly;
        mNrState = NR_STATE_NONE;
        mRplmn = rplmn;
    }

    /**
     * Constructor for voice network registration info.
     * @hide
     */
    public NetworkRegistrationInfo(int domain, @TransportType int transportType,
                                   int registrationState, int accessNetworkTechnology,
                                   int rejectCause, boolean emergencyOnly,
                                   @Nullable List<Integer> availableServices,
                                   @Nullable CellIdentity cellIdentity, @Nullable String rplmn,
                                   boolean cssSupported, int roamingIndicator, int systemIsInPrl,
                                   int defaultRoamingIndicator) {
        this(domain, transportType, registrationState, accessNetworkTechnology, rejectCause,
                emergencyOnly, availableServices, cellIdentity, rplmn);

        mVoiceSpecificInfo = new VoiceSpecificRegistrationInfo(cssSupported, roamingIndicator,
                systemIsInPrl, defaultRoamingIndicator);
    }

    /**
     * Constructor for data network registration info.
     * @hide
     */
    public NetworkRegistrationInfo(int domain, @TransportType int transportType,
                                   int registrationState, int accessNetworkTechnology,
                                   int rejectCause, boolean emergencyOnly,
                                   @Nullable List<Integer> availableServices,
                                   @Nullable CellIdentity cellIdentity, @Nullable String rplmn,
                                   int maxDataCalls, boolean isDcNrRestricted,
                                   boolean isNrAvailable, boolean isEndcAvailable,
                                   LteVopsSupportInfo lteVopsSupportInfo,
                                   boolean isUsingCarrierAggregation) {
        this(domain, transportType, registrationState, accessNetworkTechnology, rejectCause,
                emergencyOnly, availableServices, cellIdentity, rplmn);
        mDataSpecificInfo = new DataSpecificRegistrationInfo(
                maxDataCalls, isDcNrRestricted, isNrAvailable, isEndcAvailable, lteVopsSupportInfo,
                isUsingCarrierAggregation);
        updateNrState(mDataSpecificInfo);
    }

    private NetworkRegistrationInfo(Parcel source) {
        mDomain = source.readInt();
        mTransportType = source.readInt();
        mRegistrationState = source.readInt();
        mRoamingType = source.readInt();
        mAccessNetworkTechnology = source.readInt();
        mRejectCause = source.readInt();
        mEmergencyOnly = source.readBoolean();
        mAvailableServices = new ArrayList<>();
        source.readList(mAvailableServices, Integer.class.getClassLoader());
        mCellIdentity = source.readParcelable(CellIdentity.class.getClassLoader());
        mVoiceSpecificInfo = source.readParcelable(
                VoiceSpecificRegistrationInfo.class.getClassLoader());
        mDataSpecificInfo = source.readParcelable(
                DataSpecificRegistrationInfo.class.getClassLoader());
        mNrState = source.readInt();
        mRplmn = source.readString();
    }

    /**
     * Constructor from another network registration info
     *
     * @param nri Another network registration info
     * @hide
     */
    public NetworkRegistrationInfo(NetworkRegistrationInfo nri) {
        mDomain = nri.mDomain;
        mTransportType = nri.mTransportType;
        mRegistrationState = nri.mRegistrationState;
        mRoamingType = nri.mRoamingType;
        mAccessNetworkTechnology = nri.mAccessNetworkTechnology;
        mRejectCause = nri.mRejectCause;
        mEmergencyOnly = nri.mEmergencyOnly;
        mAvailableServices = new ArrayList<>(nri.mAvailableServices);
        if (nri.mCellIdentity != null) {
            Parcel p = Parcel.obtain();
            nri.mCellIdentity.writeToParcel(p, 0);
            p.setDataPosition(0);
            // TODO: Instead of doing this, we should create a formal way for cloning cell identity.
            // Cell identity is not an immutable object so we have to deep copy it.
            mCellIdentity = CellIdentity.CREATOR.createFromParcel(p);
        }

        if (nri.mVoiceSpecificInfo != null) {
            mVoiceSpecificInfo = new VoiceSpecificRegistrationInfo(nri.mVoiceSpecificInfo);
        }
        if (nri.mDataSpecificInfo != null) {
            mDataSpecificInfo = new DataSpecificRegistrationInfo(nri.mDataSpecificInfo);
        }
        mNrState = nri.mNrState;
        mRplmn = nri.mRplmn;
    }

    /**
     * @return The transport type.
     */
    public @TransportType int getTransportType() { return mTransportType; }

    /**
     * @return The network domain.
     */
    public @Domain int getDomain() { return mDomain; }

    /**
     * Get the 5G NR connection state.
     *
     * @return the 5G NR connection state.
     * @hide
     */
    public @NRState int getNrState() {
        return mNrState;
    }

    /** @hide */
    public void setNrState(@NRState int nrState) {
        mNrState = nrState;
    }

    /**
     * @return The registration state.
     *
     * @hide
     */
    @SystemApi @TestApi
    public @RegistrationState int getRegistrationState() {
        return mRegistrationState;
    }

    /**
     * @return {@code true} if registered on roaming network, {@code false} otherwise.
     */
    public boolean isRegistered() {
        return mRegistrationState == REGISTRATION_STATE_HOME
                || mRegistrationState == REGISTRATION_STATE_ROAMING;
    }

    /**
     * @return {@code true} if registered on roaming network, {@code false} otherwise.
     */
    public boolean isSearching() {
        return mRegistrationState == REGISTRATION_STATE_NOT_REGISTERED_SEARCHING;
    }

    /**
     * Get the PLMN-ID for this Network Registration, also known as the RPLMN.
     *
     * <p>If the device is registered, this will return the registered PLMN-ID. If registration
     * has failed, then this will return the PLMN ID of the last attempted registration. If the
     * device is not registered, or if is registered to a non-3GPP radio technology, then this
     * will return null.
     *
     * <p>See 3GPP TS 23.122 for further information about the Registered PLMN.
     *
     * @return the registered PLMN-ID or null.
     */
    @Nullable public String getRegisteredPlmn() {
        return mRplmn;
    }

    /**
     * @return {@code true} if registered on roaming network, {@code false} otherwise.
     */
    public boolean isRoaming() {
        return mRoamingType != ServiceState.ROAMING_TYPE_NOT_ROAMING;
    }

    /**
     * @hide
     * @return {@code true} if in service.
     */
    public boolean isInService() {
        return mRegistrationState == REGISTRATION_STATE_HOME
                || mRegistrationState == REGISTRATION_STATE_ROAMING;
    }

    /**
     * Set {@link ServiceState.RoamingType roaming type}. This could override
     * roaming type based on resource overlay or carrier config.
     * @hide
     */
    public void setRoamingType(@ServiceState.RoamingType int roamingType) {
        mRoamingType = roamingType;
    }

    /**
     * @return the current network roaming type.
     * @hide
     */
    @SystemApi @TestApi
    public @ServiceState.RoamingType int getRoamingType() {
        return mRoamingType;
    }

    /**
     * @return Whether emergency is enabled.
     * @hide
     */
    @SystemApi @TestApi
    public boolean isEmergencyEnabled() { return mEmergencyOnly; }

    /**
     * @return List of available service types.
     */
    @NonNull
    @ServiceType
    public List<Integer> getAvailableServices() {
        return Collections.unmodifiableList(mAvailableServices);
    }

    /**
     * @return The access network technology {@link NetworkType}.
     */
    public @NetworkType int getAccessNetworkTechnology() {
        return mAccessNetworkTechnology;
    }

    /**
     * override the access network technology {@link NetworkType} e.g, rat ratchet.
     * @hide
     */
    public void setAccessNetworkTechnology(@NetworkType int tech) {
        if (tech == TelephonyManager.NETWORK_TYPE_LTE_CA) {
            // For old device backward compatibility support
            tech = TelephonyManager.NETWORK_TYPE_LTE;
            if (mDataSpecificInfo != null) {
                mDataSpecificInfo.setIsUsingCarrierAggregation(true);
            }
        }
        mAccessNetworkTechnology = tech;
    }

    /**
     * @return Reason for denial if the registration state is {@link #REGISTRATION_STATE_DENIED}.
     * Depending on {@code accessNetworkTechnology}, the values are defined in 3GPP TS 24.008
     * 10.5.3.6 for UMTS, 3GPP TS 24.301 9.9.3.9 for LTE, and 3GPP2 A.S0001 6.2.2.44 for CDMA
     * @hide
     */
    @SystemApi @TestApi
    public int getRejectCause() {
        return mRejectCause;
    }

    /**
     * @return The cell information.
     */
    @Nullable
    public CellIdentity getCellIdentity() {
        return mCellIdentity;
    }

    /**
     * @hide
     */
    @Nullable
    public VoiceSpecificRegistrationInfo getVoiceSpecificInfo() {
        return mVoiceSpecificInfo;
    }

    /**
     * @return Data registration related info
     * @hide
     */
    @Nullable
    @SystemApi @TestApi
    public DataSpecificRegistrationInfo getDataSpecificInfo() {
        return mDataSpecificInfo;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Convert service type to string
     *
     * @hide
     *
     * @param serviceType The service type
     * @return The service type in string format
     */
    public static String serviceTypeToString(@ServiceType int serviceType) {
        switch (serviceType) {
            case SERVICE_TYPE_VOICE: return "VOICE";
            case SERVICE_TYPE_DATA: return "DATA";
            case SERVICE_TYPE_SMS: return "SMS";
            case SERVICE_TYPE_VIDEO: return "VIDEO";
            case SERVICE_TYPE_EMERGENCY: return "EMERGENCY";
        }
        return "Unknown service type " + serviceType;
    }

    /**
     * Convert registration state to string
     *
     * @hide
     *
     * @param registrationState The registration state
     * @return The reg state in string
     */
    public static String registrationStateToString(@RegistrationState int registrationState) {
        switch (registrationState) {
            case REGISTRATION_STATE_NOT_REGISTERED_OR_SEARCHING: return "NOT_REG_OR_SEARCHING";
            case REGISTRATION_STATE_HOME: return "HOME";
            case REGISTRATION_STATE_NOT_REGISTERED_SEARCHING: return "NOT_REG_SEARCHING";
            case REGISTRATION_STATE_DENIED: return "DENIED";
            case REGISTRATION_STATE_UNKNOWN: return "UNKNOWN";
            case REGISTRATION_STATE_ROAMING: return "ROAMING";
        }
        return "Unknown reg state " + registrationState;
    }

    private static String nrStateToString(@NRState int nrState) {
        switch (nrState) {
            case NR_STATE_RESTRICTED:
                return "RESTRICTED";
            case NR_STATE_NOT_RESTRICTED:
                return "NOT_RESTRICTED";
            case NR_STATE_CONNECTED:
                return "CONNECTED";
            default:
                return "NONE";
        }
    }

    /** @hide */
    static @NonNull String domainToString(@Domain int domain) {
        switch (domain) {
            case DOMAIN_CS: return "CS";
            case DOMAIN_PS: return "PS";
            case DOMAIN_CS_PS: return "CS_PS";
            default: return "UNKNOWN";
        }
    }

    @NonNull
    @Override
    public String toString() {
        return new StringBuilder("NetworkRegistrationInfo{")
                .append(" domain=").append(domainToString(mDomain))
                .append(" transportType=").append(
                        AccessNetworkConstants.transportTypeToString(mTransportType))
                .append(" registrationState=").append(registrationStateToString(mRegistrationState))
                .append(" roamingType=").append(ServiceState.roamingTypeToString(mRoamingType))
                .append(" accessNetworkTechnology=")
                .append(TelephonyManager.getNetworkTypeName(mAccessNetworkTechnology))
                .append(" rejectCause=").append(mRejectCause)
                .append(" emergencyEnabled=").append(mEmergencyOnly)
                .append(" availableServices=").append("[" + (mAvailableServices != null
                        ? mAvailableServices.stream().map(type -> serviceTypeToString(type))
                        .collect(Collectors.joining(",")) : null) + "]")
                .append(" cellIdentity=").append(mCellIdentity)
                .append(" voiceSpecificInfo=").append(mVoiceSpecificInfo)
                .append(" dataSpecificInfo=").append(mDataSpecificInfo)
                .append(" nrState=").append(nrStateToString(mNrState))
                .append(" rRplmn=").append(mRplmn)
                .append("}").toString();
    }

    @Override
    public int hashCode() {
        return Objects.hash(mDomain, mTransportType, mRegistrationState, mRoamingType,
                mAccessNetworkTechnology, mRejectCause, mEmergencyOnly, mAvailableServices,
                mCellIdentity, mVoiceSpecificInfo, mDataSpecificInfo, mNrState, mRplmn);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;

        if (!(o instanceof NetworkRegistrationInfo)) {
            return false;
        }

        NetworkRegistrationInfo other = (NetworkRegistrationInfo) o;
        return mDomain == other.mDomain
                && mTransportType == other.mTransportType
                && mRegistrationState == other.mRegistrationState
                && mRoamingType == other.mRoamingType
                && mAccessNetworkTechnology == other.mAccessNetworkTechnology
                && mRejectCause == other.mRejectCause
                && mEmergencyOnly == other.mEmergencyOnly
                && mAvailableServices.equals(other.mAvailableServices)
                && Objects.equals(mCellIdentity, other.mCellIdentity)
                && Objects.equals(mVoiceSpecificInfo, other.mVoiceSpecificInfo)
                && Objects.equals(mDataSpecificInfo, other.mDataSpecificInfo)
                && TextUtils.equals(mRplmn, other.mRplmn)
                && mNrState == other.mNrState;
    }

    /**
     * @hide
     */
    @Override
    @SystemApi @TestApi
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mDomain);
        dest.writeInt(mTransportType);
        dest.writeInt(mRegistrationState);
        dest.writeInt(mRoamingType);
        dest.writeInt(mAccessNetworkTechnology);
        dest.writeInt(mRejectCause);
        dest.writeBoolean(mEmergencyOnly);
        dest.writeList(mAvailableServices);
        dest.writeParcelable(mCellIdentity, 0);
        dest.writeParcelable(mVoiceSpecificInfo, 0);
        dest.writeParcelable(mDataSpecificInfo, 0);
        dest.writeInt(mNrState);
        dest.writeString(mRplmn);
    }

    /**
     * Use the 5G NR Non-Standalone indicators from the network registration state to update the
     * NR state. There are 3 indicators in the network registration state:
     *
     * 1. if E-UTRA-NR Dual Connectivity (EN-DC) is supported by the primary serving cell.
     * 2. if NR is supported by the selected PLMN.
     * 3. if the use of dual connectivity with NR is restricted.
     *
     * The network has 5G NR capability if E-UTRA-NR Dual Connectivity is supported by the primary
     * serving cell.
     *
     * The use of NR 5G is not restricted If the network has 5G NR capability and both the use of
     * DCNR is not restricted and NR is supported by the selected PLMN. Otherwise the use of 5G
     * NR is restricted.
     *
     * @param state data specific registration state contains the 5G NR indicators.
     */
    private void updateNrState(DataSpecificRegistrationInfo state) {
        mNrState = NR_STATE_NONE;
        if (state.isEnDcAvailable) {
            if (!state.isDcNrRestricted && state.isNrAvailable) {
                mNrState = NR_STATE_NOT_RESTRICTED;
            } else {
                mNrState = NR_STATE_RESTRICTED;
            }
        }
    }

    public static final @NonNull Parcelable.Creator<NetworkRegistrationInfo> CREATOR =
            new Parcelable.Creator<NetworkRegistrationInfo>() {
                @Override
                public NetworkRegistrationInfo createFromParcel(Parcel source) {
                    return new NetworkRegistrationInfo(source);
                }

                @Override
                public NetworkRegistrationInfo[] newArray(int size) {
                    return new NetworkRegistrationInfo[size];
                }
            };

    /**
     * @hide
     */
    public NetworkRegistrationInfo sanitizeLocationInfo() {
        NetworkRegistrationInfo result = copy();
        result.mCellIdentity = null;
        return result;
    }

    private NetworkRegistrationInfo copy() {
        Parcel p = Parcel.obtain();
        this.writeToParcel(p, 0);
        p.setDataPosition(0);
        NetworkRegistrationInfo result = new NetworkRegistrationInfo(p);
        p.recycle();
        return result;
    }

    /**
     * Provides a convenient way to set the fields of a {@link NetworkRegistrationInfo} when
     * creating a new instance.
     *
     * <p>The example below shows how you might create a new {@code NetworkRegistrationInfo}:
     *
     * <pre><code>
     *
     * NetworkRegistrationInfo nri = new NetworkRegistrationInfo.Builder()
     *     .setAccessNetworkTechnology(TelephonyManager.NETWORK_TYPE_LTE)
     *     .setRegistrationState(REGISTRATION_STATE_HOME)
     *     .build();
     * </code></pre>
     * @hide
     */
    @SystemApi @TestApi
    public static final class Builder {
        @Domain
        private int mDomain;

        @TransportType
        private int mTransportType;

        @RegistrationState
        private int mRegistrationState;

        @NetworkType
        private int mAccessNetworkTechnology;

        private int mRejectCause;

        private boolean mEmergencyOnly;

        @ServiceType
        private List<Integer> mAvailableServices;

        @Nullable
        private CellIdentity mCellIdentity;

        @NonNull
        private String mRplmn = "";

        /**
         * Default constructor for Builder.
         */
        public Builder() {}

        /**
         * Set the network domain.
         *
         * @param domain Network domain.
         *
         * @return The same instance of the builder.
         */
        public @NonNull Builder setDomain(@Domain int domain) {
            mDomain = domain;
            return this;
        }

        /**
         * Set the transport type.
         *
         * @param transportType Transport type.
         *
         * @return The same instance of the builder.
         */
        public @NonNull Builder setTransportType(@TransportType int transportType) {
            mTransportType = transportType;
            return this;
        }

        /**
         * Set the registration state.
         *
         * @param registrationState The registration state.
         *
         * @return The same instance of the builder.
         */
        public @NonNull Builder setRegistrationState(@RegistrationState int registrationState) {
            mRegistrationState = registrationState;
            return this;
        }

        /**
         * Set tne access network technology.
         *
         * @return The same instance of the builder.
         *
         * @param accessNetworkTechnology The access network technology
         */
        public @NonNull Builder setAccessNetworkTechnology(
                @NetworkType int accessNetworkTechnology) {
            mAccessNetworkTechnology = accessNetworkTechnology;
            return this;
        }

        /**
         * Set the network reject cause.
         *
         * @param rejectCause Reason for denial if the registration state is
         * {@link #REGISTRATION_STATE_DENIED}.Depending on {@code accessNetworkTechnology}, the
         * values are defined in 3GPP TS 24.008 10.5.3.6 for UMTS, 3GPP TS 24.301 9.9.3.9 for LTE,
         * and 3GPP2 A.S0001 6.2.2.44 for CDMA. If the reject cause is not supported or unknown, set
         * it to 0.
         *
         * @return The same instance of the builder.
         */
        public @NonNull Builder setRejectCause(int rejectCause) {
            mRejectCause = rejectCause;
            return this;
        }

        /**
         * Set emergency only.
         *
         * @param emergencyOnly True if this network registration is for emergency use only.
         *
         * @return The same instance of the builder.
         * @hide
         */
        @SystemApi @TestApi
        public @NonNull Builder setEmergencyOnly(boolean emergencyOnly) {
            mEmergencyOnly = emergencyOnly;
            return this;
        }

        /**
         * Set the available services.
         *
         * @param availableServices Available services.
         *
         * @return The same instance of the builder.
         * @hide
         */
        @SystemApi @TestApi
        public @NonNull Builder setAvailableServices(
                @NonNull @ServiceType List<Integer> availableServices) {
            mAvailableServices = availableServices;
            return this;
        }

        /**
         * Set the cell identity.
         *
         * @param cellIdentity The cell identity.
         *
         * @return The same instance of the builder.
         * @hide
         */
        @SystemApi @TestApi
        public @NonNull Builder setCellIdentity(@Nullable CellIdentity cellIdentity) {
            mCellIdentity = cellIdentity;
            return this;
        }

        /**
         * Set the registered PLMN.
         *
         * @param rplmn the registered plmn.
         *
         * @return The same instance of the builder.
         */
        public @NonNull Builder setRegisteredPlmn(@Nullable String rplmn) {
            mRplmn = rplmn;
            return this;
        }

        /**
         * Build the NetworkRegistrationInfo.
         * @return the NetworkRegistrationInfo object.
         * @hide
         */
        @SystemApi @TestApi
        public @NonNull NetworkRegistrationInfo build() {
            return new NetworkRegistrationInfo(mDomain, mTransportType, mRegistrationState,
                    mAccessNetworkTechnology, mRejectCause, mEmergencyOnly, mAvailableServices,
                    mCellIdentity, mRplmn);
        }
    }
}
