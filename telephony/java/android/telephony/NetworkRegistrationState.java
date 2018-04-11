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
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.Objects;

/**
 * Description of a mobile network registration state
 * @hide
 */
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
    public static final int REG_STATE_HOME = 1;
    /** Not registered. The device is currently searching a new operator to register */
    public static final int REG_STATE_NOT_REG_SEARCHING = 2;
    /** Registration denied */
    public static final int REG_STATE_DENIED = 3;
    /** Registration state is unknown */
    public static final int REG_STATE_UNKNOWN = 4;
    /** Registered on roaming network */
    public static final int REG_STATE_ROAMING = 5;

    /**
     * Supported service type
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "SERVICE_TYPE_",
            value = {SERVICE_TYPE_VOICE, SERVICE_TYPE_DATA, SERVICE_TYPE_SMS, SERVICE_TYPE_VIDEO,
                    SERVICE_TYPE_EMERGENCY})
    public @interface ServiceType {}

    public static final int SERVICE_TYPE_VOICE = 1;
    public static final int SERVICE_TYPE_DATA = 2;
    public static final int SERVICE_TYPE_SMS = 3;
    public static final int SERVICE_TYPE_VIDEO = 4;
    public static final int SERVICE_TYPE_EMERGENCY = 5;

    /** {@link AccessNetworkConstants.TransportType}*/
    private final int mTransportType;

    @Domain
    private final int mDomain;

    @RegState
    private final int mRegState;

    private final int mAccessNetworkTechnology;

    private final int mReasonForDenial;

    private final boolean mEmergencyOnly;

    private final int[] mAvailableServices;

    @Nullable
    private final CellIdentity mCellIdentity;

    @Nullable
    private VoiceSpecificRegistrationStates mVoiceSpecificStates;

    @Nullable
    private DataSpecificRegistrationStates mDataSpecificStates;

    /**
     * @param transportType Transport type. Must be {@link AccessNetworkConstants.TransportType}
     * @param domain Network domain. Must be DOMAIN_CS or DOMAIN_PS.
     * @param regState Network registration state.
     * @param accessNetworkTechnology See TelephonyManager NETWORK_TYPE_XXXX.
     * @param reasonForDenial Reason for denial if the registration state is DENIED.
     * @param availableServices The supported service.
     * @param cellIdentity The identity representing a unique cell
     */
    public NetworkRegistrationState(int transportType, int domain, int regState,
            int accessNetworkTechnology, int reasonForDenial, boolean emergencyOnly,
            int[] availableServices, @Nullable CellIdentity cellIdentity) {
        mTransportType = transportType;
        mDomain = domain;
        mRegState = regState;
        mAccessNetworkTechnology = accessNetworkTechnology;
        mReasonForDenial = reasonForDenial;
        mAvailableServices = availableServices;
        mCellIdentity = cellIdentity;
        mEmergencyOnly = emergencyOnly;
    }

    /**
     * Constructor for voice network registration states.
     * @hide
     */
    public NetworkRegistrationState(int transportType, int domain, int regState,
            int accessNetworkTechnology, int reasonForDenial, boolean emergencyOnly,
            int[] availableServices, @Nullable CellIdentity cellIdentity, boolean cssSupported,
            int roamingIndicator, int systemIsInPrl, int defaultRoamingIndicator) {
        this(transportType, domain, regState, accessNetworkTechnology,
                reasonForDenial, emergencyOnly, availableServices, cellIdentity);

        mVoiceSpecificStates = new VoiceSpecificRegistrationStates(cssSupported, roamingIndicator,
                systemIsInPrl, defaultRoamingIndicator);
    }

    /**
     * Constructor for data network registration states.
     * @hide
     */
    public NetworkRegistrationState(int transportType, int domain, int regState,
            int accessNetworkTechnology, int reasonForDenial, boolean emergencyOnly,
            int[] availableServices, @Nullable CellIdentity cellIdentity, int maxDataCalls) {
        this(transportType, domain, regState, accessNetworkTechnology,
                reasonForDenial, emergencyOnly, availableServices, cellIdentity);

        mDataSpecificStates = new DataSpecificRegistrationStates(maxDataCalls);
    }

    protected NetworkRegistrationState(Parcel source) {
        mTransportType = source.readInt();
        mDomain = source.readInt();
        mRegState = source.readInt();
        mAccessNetworkTechnology = source.readInt();
        mReasonForDenial = source.readInt();
        mEmergencyOnly = source.readBoolean();
        mAvailableServices = source.createIntArray();
        mCellIdentity = source.readParcelable(CellIdentity.class.getClassLoader());
        mVoiceSpecificStates = source.readParcelable(
                VoiceSpecificRegistrationStates.class.getClassLoader());
        mDataSpecificStates = source.readParcelable(
                DataSpecificRegistrationStates.class.getClassLoader());
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
     * @return The registration state.
     */
    public @RegState int getRegState() {
        return mRegState;
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
     * @return The access network technology. Must be one of TelephonyManager.NETWORK_TYPE_XXXX.
     */
    public int getAccessNetworkTechnology() {
        return mAccessNetworkTechnology;
    }

    /**
     * @return Reason for denial from network.
     */
    public int getReasonForDenial() {
        return mReasonForDenial;
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

    private static String regStateToString(int regState) {
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

    @Override
    public String toString() {
        return new StringBuilder("NetworkRegistrationState{")
                .append("transportType=").append(mTransportType)
                .append(" domain=").append((mDomain == DOMAIN_CS) ? "CS" : "PS")
                .append(" regState=").append(regStateToString(mRegState))
                .append(" accessNetworkTechnology=")
                .append(TelephonyManager.getNetworkTypeName(mAccessNetworkTechnology))
                .append(" reasonForDenial=").append(mReasonForDenial)
                .append(" emergencyEnabled=").append(mEmergencyOnly)
                .append(" supportedServices=").append(mAvailableServices)
                .append(" cellIdentity=").append(mCellIdentity)
                .append(" voiceSpecificStates=").append(mVoiceSpecificStates)
                .append(" dataSpecificStates=").append(mDataSpecificStates)
                .append("}").toString();
    }

    @Override
    public int hashCode() {
        return Objects.hash(mTransportType, mDomain, mRegState, mAccessNetworkTechnology,
                mReasonForDenial, mEmergencyOnly, mAvailableServices, mCellIdentity,
                mVoiceSpecificStates, mDataSpecificStates);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;

        if (o == null || !(o instanceof NetworkRegistrationState)) {
            return false;
        }

        NetworkRegistrationState other = (NetworkRegistrationState) o;
        return mTransportType == other.mTransportType
                && mDomain == other.mDomain
                && mRegState == other.mRegState
                && mAccessNetworkTechnology == other.mAccessNetworkTechnology
                && mReasonForDenial == other.mReasonForDenial
                && mEmergencyOnly == other.mEmergencyOnly
                && (mAvailableServices == other.mAvailableServices
                    || Arrays.equals(mAvailableServices, other.mAvailableServices))
                && equals(mCellIdentity, other.mCellIdentity)
                && equals(mVoiceSpecificStates, other.mVoiceSpecificStates)
                && equals(mDataSpecificStates, other.mDataSpecificStates);
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mTransportType);
        dest.writeInt(mDomain);
        dest.writeInt(mRegState);
        dest.writeInt(mAccessNetworkTechnology);
        dest.writeInt(mReasonForDenial);
        dest.writeBoolean(mEmergencyOnly);
        dest.writeIntArray(mAvailableServices);
        dest.writeParcelable(mCellIdentity, 0);
        dest.writeParcelable(mVoiceSpecificStates, 0);
        dest.writeParcelable(mDataSpecificStates, 0);
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

    private static boolean equals(Object o1, Object o2) {
        if (o1 == o2) {
            return true;
        } else if (o1 == null) {
            return false;
        } else {
            return o1.equals(o2);
        }
    }
}
