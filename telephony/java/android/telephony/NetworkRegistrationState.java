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
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.AccessNetworkConstants.TransportType;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Description of a mobile network registration state
 * @hide
 */
@SystemApi
public class NetworkRegistrationState implements Parcelable {
    /**
     * Network domain
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "DOMAIN_", value = {DOMAIN_CS, DOMAIN_PS})
    public @interface Domain {}

    /** Circuit switching domain */
    public static final int DOMAIN_CS = 1;
    /** Packet switching domain */
    public static final int DOMAIN_PS = 2;

    /**
     * Registration state
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "REG_STATE_",
            value = {REG_STATE_NOT_REG_NOT_SEARCHING, REG_STATE_HOME, REG_STATE_NOT_REG_SEARCHING,
                    REG_STATE_DENIED, REG_STATE_UNKNOWN, REG_STATE_ROAMING})
    public @interface RegState {}

    /** Not registered. The device is not currently searching a new operator to register */
    public static final int REG_STATE_NOT_REG_NOT_SEARCHING = 0;
    /** Registered on home network */
    public static final int REG_STATE_HOME                  = 1;
    /** Not registered. The device is currently searching a new operator to register */
    public static final int REG_STATE_NOT_REG_SEARCHING     = 2;
    /** Registration denied */
    public static final int REG_STATE_DENIED                = 3;
    /** Registration state is unknown */
    public static final int REG_STATE_UNKNOWN               = 4;
    /** Registered on roaming network */
    public static final int REG_STATE_ROAMING               = 5;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "NR_STATUS_",
            value = {NR_STATUS_NONE, NR_STATUS_RESTRICTED, NR_STATUS_NOT_RESTRICTED,
                    NR_STATUS_CONNECTED})
    public @interface NRStatus {}

    /**
     * The device isn't camped on an LTE cell or the LTE cell doesn't support E-UTRA-NR
     * Dual Connectivity(EN-DC).
     * @hide
     */
    public static final int NR_STATUS_NONE = -1;

    /**
     * The device is camped on an LTE cell that supports E-UTRA-NR Dual Connectivity(EN-DC) but
     * either the use of dual connectivity with NR(DCNR) is restricted or NR is not supported by
     * the selected PLMN.
     * @hide
     */
    public static final int NR_STATUS_RESTRICTED = 1;

    /**
     * The device is camped on an LTE cell that supports E-UTRA-NR Dual Connectivity(EN-DC) and both
     * the use of dual connectivity with NR(DCNR) is not restricted and NR is supported by the
     * selected PLMN.
     * @hide
     */
    public static final int NR_STATUS_NOT_RESTRICTED = 2;

    /**
     * The device is camped on an LTE cell that supports E-UTRA-NR Dual Connectivity(EN-DC) and
     * also connected to at least one 5G cell as a secondary serving cell.
     * @hide
     */
    public static final int NR_STATUS_CONNECTED = 3;

    /**
     * Supported service type
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "SERVICE_TYPE_",
            value = {SERVICE_TYPE_VOICE, SERVICE_TYPE_DATA, SERVICE_TYPE_SMS, SERVICE_TYPE_VIDEO,
                    SERVICE_TYPE_EMERGENCY})
    public @interface ServiceType {}

    public static final int SERVICE_TYPE_VOICE      = 1;
    public static final int SERVICE_TYPE_DATA       = 2;
    public static final int SERVICE_TYPE_SMS        = 3;
    public static final int SERVICE_TYPE_VIDEO      = 4;
    public static final int SERVICE_TYPE_EMERGENCY  = 5;

    @Domain
    private final int mDomain;

    /** {@link TransportType} */
    private final int mTransportType;

    @RegState
    private final int mRegState;

    /**
     * Save the {@link ServiceState.RoamingType roaming type}. it can be overridden roaming type
     * from resource overlay or carrier config.
     */
    @ServiceState.RoamingType
    private int mRoamingType;

    private int mAccessNetworkTechnology;

    @NRStatus
    private int mNrStatus;

    private final int mRejectCause;

    private final boolean mEmergencyOnly;

    private final int[] mAvailableServices;

    @Nullable
    private CellIdentity mCellIdentity;

    @Nullable
    private VoiceSpecificRegistrationStates mVoiceSpecificStates;

    @Nullable
    private DataSpecificRegistrationStates mDataSpecificStates;

    /**
     * @param domain Network domain. Must be a {@link Domain}. For {@link TransportType#WLAN}
     * transport, this must set to {@link #DOMAIN_PS}.
     * @param transportType Transport type. Must be one of the{@link TransportType}.
     * @param regState Network registration state. Must be one of the {@link RegState}. For
     * {@link TransportType#WLAN} transport, only {@link #REG_STATE_HOME} and
     * {@link #REG_STATE_NOT_REG_NOT_SEARCHING} are valid states.
     * @param accessNetworkTechnology Access network technology. Must be one of TelephonyManager
     * NETWORK_TYPE_XXXX. For {@link TransportType#WLAN} transport, set to
     * {@link TelephonyManager#NETWORK_TYPE_IWLAN}.
     * @param rejectCause Reason for denial if the registration state is {@link #REG_STATE_DENIED}.
     * Depending on {@code accessNetworkTechnology}, the values are defined in 3GPP TS 24.008
     * 10.5.3.6 for UMTS, 3GPP TS 24.301 9.9.3.9 for LTE, and 3GPP2 A.S0001 6.2.2.44 for CDMA. If
     * the reject cause is not supported or unknown, set it to 0.
     * // TODO: Add IWLAN reject cause reference
     * @param emergencyOnly True if this registration is for emergency only.
     * @param availableServices The list of the supported services. Each element must be one of
     * the {@link ServiceType}.
     * @param cellIdentity The identity representing a unique cell or wifi AP. Set to null if the
     * information is not available.
     */
    public NetworkRegistrationState(@Domain int domain, int transportType, @RegState int regState,
                                    int accessNetworkTechnology, int rejectCause,
                                    boolean emergencyOnly, int[] availableServices,
                                    @Nullable CellIdentity cellIdentity) {
        mDomain = domain;
        mTransportType = transportType;
        mRegState = regState;
        mRoamingType = (regState == REG_STATE_ROAMING)
                ? ServiceState.ROAMING_TYPE_UNKNOWN : ServiceState.ROAMING_TYPE_NOT_ROAMING;
        mAccessNetworkTechnology = accessNetworkTechnology;
        mRejectCause = rejectCause;
        mAvailableServices = availableServices;
        mCellIdentity = cellIdentity;
        mEmergencyOnly = emergencyOnly;
        mNrStatus = NR_STATUS_NONE;
    }

    /**
     * Constructor for voice network registration states.
     * @hide
     */
    public NetworkRegistrationState(int domain, int transportType, int regState,
            int accessNetworkTechnology, int rejectCause, boolean emergencyOnly,
            int[] availableServices, @Nullable CellIdentity cellIdentity, boolean cssSupported,
            int roamingIndicator, int systemIsInPrl, int defaultRoamingIndicator) {
        this(domain, transportType, regState, accessNetworkTechnology, rejectCause, emergencyOnly,
                availableServices, cellIdentity);

        mVoiceSpecificStates = new VoiceSpecificRegistrationStates(cssSupported, roamingIndicator,
                systemIsInPrl, defaultRoamingIndicator);
    }

    /**
     * Constructor for data network registration states.
     * @hide
     */
    public NetworkRegistrationState(int domain, int transportType, int regState,
            int accessNetworkTechnology, int rejectCause, boolean emergencyOnly,
            int[] availableServices, @Nullable CellIdentity cellIdentity, int maxDataCalls,
            boolean isDcNrRestricted, boolean isNrAvailable, boolean isEndcAvailable,
            LteVopsSupportInfo lteVopsSupportInfo) {
        this(domain, transportType, regState, accessNetworkTechnology, rejectCause, emergencyOnly,
                availableServices, cellIdentity);

        mDataSpecificStates = new DataSpecificRegistrationStates(
                maxDataCalls, isDcNrRestricted, isNrAvailable, isEndcAvailable, lteVopsSupportInfo);
        updateNrStatus(mDataSpecificStates);
    }

    protected NetworkRegistrationState(Parcel source) {
        mDomain = source.readInt();
        mTransportType = source.readInt();
        mRegState = source.readInt();
        mRoamingType = source.readInt();
        mAccessNetworkTechnology = source.readInt();
        mRejectCause = source.readInt();
        mEmergencyOnly = source.readBoolean();
        mAvailableServices = source.createIntArray();
        mCellIdentity = source.readParcelable(CellIdentity.class.getClassLoader());
        mVoiceSpecificStates = source.readParcelable(
                VoiceSpecificRegistrationStates.class.getClassLoader());
        mDataSpecificStates = source.readParcelable(
                DataSpecificRegistrationStates.class.getClassLoader());
        mNrStatus = source.readInt();
    }

    /**
     * @return The transport type.
     */
    public int getTransportType() { return mTransportType; }

    /**
     * @return The network domain.
     */
    public @Domain int getDomain() { return mDomain; }

    /**
     * @return the 5G NR connection status.
     * @hide
     */
    public @NRStatus int getNrStatus() {
        return mNrStatus;
    }

    /** @hide */
    public void setNrStatus(@NRStatus int nrStatus) {
        mNrStatus = nrStatus;
    }

    /**
     * @return The registration state.
     */
    public @RegState int getRegState() {
        return mRegState;
    }

    /**
     * @return {@code true} if registered on roaming network, {@code false} otherwise.
     */
    public boolean isRoaming() {
        return mRoamingType != ServiceState.ROAMING_TYPE_NOT_ROAMING;
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
     */

    public @ServiceState.RoamingType int getRoamingType() {
        return mRoamingType;
    }

    /**
     * @return Whether emergency is enabled.
     */
    public boolean isEmergencyEnabled() { return mEmergencyOnly; }

    /**
     * @return List of available service types.
     */
    public int[] getAvailableServices() { return mAvailableServices; }

    /**
     * @return The access network technology {@link TelephonyManager.NetworkType}.
     */
    public @TelephonyManager.NetworkType int getAccessNetworkTechnology() {
        return mAccessNetworkTechnology;
    }

    /**
     * override the access network technology {@link TelephonyManager.NetworkType} e.g, rat ratchet.
     * @hide
     */
    public void setAccessNetworkTechnology(@TelephonyManager.NetworkType int tech) {
        mAccessNetworkTechnology = tech;
    }

    /**
     * @return Network reject cause
     */
    public int getRejectCause() {
        return mRejectCause;
    }

    /**
     * @return The cell information.
     */
    public CellIdentity getCellIdentity() {
        return mCellIdentity;
    }

    /**
     * @hide
     */
    @Nullable
    public VoiceSpecificRegistrationStates getVoiceSpecificStates() {
        return mVoiceSpecificStates;
    }

    /**
     * @hide
     */
    @Nullable
    public DataSpecificRegistrationStates getDataSpecificStates() {
        return mDataSpecificStates;
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
     * @param regState The registration state
     * @return The reg state in string
     */
    public static String regStateToString(@RegState int regState) {
        switch (regState) {
            case REG_STATE_NOT_REG_NOT_SEARCHING: return "NOT_REG_NOT_SEARCHING";
            case REG_STATE_HOME: return "HOME";
            case REG_STATE_NOT_REG_SEARCHING: return "NOT_REG_SEARCHING";
            case REG_STATE_DENIED: return "DENIED";
            case REG_STATE_UNKNOWN: return "UNKNOWN";
            case REG_STATE_ROAMING: return "ROAMING";
        }
        return "Unknown reg state " + regState;
    }

    private static String nrStatusToString(@NRStatus int nrStatus) {
        switch (nrStatus) {
            case NR_STATUS_RESTRICTED:
                return "RESTRICTED";
            case NR_STATUS_NOT_RESTRICTED:
                return "NOT_RESTRICTED";
            case NR_STATUS_CONNECTED:
                return "CONNECTED";
            default:
                return "NONE";
        }
    }

    @Override
    public String toString() {
        return new StringBuilder("NetworkRegistrationState{")
                .append(" domain=").append((mDomain == DOMAIN_CS) ? "CS" : "PS")
                .append(" transportType=").append(TransportType.toString(mTransportType))
                .append(" regState=").append(regStateToString(mRegState))
                .append(" roamingType=").append(ServiceState.roamingTypeToString(mRoamingType))
                .append(" accessNetworkTechnology=")
                .append(TelephonyManager.getNetworkTypeName(mAccessNetworkTechnology))
                .append(" rejectCause=").append(mRejectCause)
                .append(" emergencyEnabled=").append(mEmergencyOnly)
                .append(" availableServices=").append("[" + (mAvailableServices != null
                        ? Arrays.stream(mAvailableServices)
                        .mapToObj(type -> serviceTypeToString(type))
                        .collect(Collectors.joining(",")) : null) + "]")
                .append(" cellIdentity=").append(mCellIdentity)
                .append(" voiceSpecificStates=").append(mVoiceSpecificStates)
                .append(" dataSpecificStates=").append(mDataSpecificStates)
                .append(" nrStatus=").append(nrStatusToString(mNrStatus))
                .append("}").toString();
    }

    @Override
    public int hashCode() {
        return Objects.hash(mDomain, mTransportType, mRegState, mRoamingType,
                mAccessNetworkTechnology, mRejectCause, mEmergencyOnly, mAvailableServices,
                mCellIdentity, mVoiceSpecificStates, mDataSpecificStates, mNrStatus);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;

        if (!(o instanceof NetworkRegistrationState)) {
            return false;
        }

        NetworkRegistrationState other = (NetworkRegistrationState) o;
        return mDomain == other.mDomain
                && mTransportType == other.mTransportType
                && mRegState == other.mRegState
                && mRoamingType == other.mRoamingType
                && mAccessNetworkTechnology == other.mAccessNetworkTechnology
                && mRejectCause == other.mRejectCause
                && mEmergencyOnly == other.mEmergencyOnly
                && Arrays.equals(mAvailableServices, other.mAvailableServices)
                && Objects.equals(mCellIdentity, other.mCellIdentity)
                && Objects.equals(mVoiceSpecificStates, other.mVoiceSpecificStates)
                && Objects.equals(mDataSpecificStates, other.mDataSpecificStates)
                && mNrStatus == other.mNrStatus;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mDomain);
        dest.writeInt(mTransportType);
        dest.writeInt(mRegState);
        dest.writeInt(mRoamingType);
        dest.writeInt(mAccessNetworkTechnology);
        dest.writeInt(mRejectCause);
        dest.writeBoolean(mEmergencyOnly);
        dest.writeIntArray(mAvailableServices);
        dest.writeParcelable(mCellIdentity, 0);
        dest.writeParcelable(mVoiceSpecificStates, 0);
        dest.writeParcelable(mDataSpecificStates, 0);
        dest.writeInt(mNrStatus);
    }

    /**
     * Use the 5G NR Non-Standalone indicators from the network registration state to update the
     * NR status. There are 3 indicators in the network registration state:
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
    private void updateNrStatus(DataSpecificRegistrationStates state) {
        mNrStatus = NR_STATUS_NONE;
        if (state.isEnDcAvailable) {
            if (!state.isDcNrRestricted && state.isNrAvailable) {
                mNrStatus = NR_STATUS_NOT_RESTRICTED;
            } else {
                mNrStatus = NR_STATUS_RESTRICTED;
            }
        }
    }

    public static final Parcelable.Creator<NetworkRegistrationState> CREATOR =
            new Parcelable.Creator<NetworkRegistrationState>() {
        @Override
        public NetworkRegistrationState createFromParcel(Parcel source) {
            return new NetworkRegistrationState(source);
        }

        @Override
        public NetworkRegistrationState[] newArray(int size) {
            return new NetworkRegistrationState[size];
        }
    };

    /**
     * @hide
     */
    public NetworkRegistrationState sanitizeLocationInfo() {
        NetworkRegistrationState result = copy();
        result.mCellIdentity = null;
        return result;
    }

    private NetworkRegistrationState copy() {
        Parcel p = Parcel.obtain();
        this.writeToParcel(p, 0);
        p.setDataPosition(0);
        NetworkRegistrationState result = new NetworkRegistrationState(p);
        p.recycle();
        return result;
    }
}
