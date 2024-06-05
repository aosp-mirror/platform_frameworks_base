/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.compat.Compatibility;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledAfter;
import android.compat.annotation.UnsupportedAppUsage;
import android.net.LinkProperties;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.AccessNetworkConstants.TransportType;
import android.telephony.Annotation.ApnType;
import android.telephony.Annotation.DataFailureCause;
import android.telephony.Annotation.DataState;
import android.telephony.Annotation.NetworkType;
import android.telephony.data.ApnSetting;
import android.telephony.data.DataCallResponse;
import android.telephony.data.Qos;

import com.android.internal.telephony.flags.Flags;
import com.android.internal.telephony.util.TelephonyUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;


/**
 * Contains precise data connection state.
 *
 * The following data connection information is included in returned PreciseDataConnectionState:
 *
 * <ul>
 *   <li>Data connection state.
 *   <li>Network type of the connection.
 *   <li>APN types.
 *   <li>APN.
 *   <li>The properties of the network link.
 *   <li>Data connection fail cause.
 * </ul>
 *
 */
public final class PreciseDataConnectionState implements Parcelable {
    private final @TransportType int mTransportType;
    private final int mId;
    private final int mNetId;
    private final @DataState int mState;
    private final @NetworkType int mNetworkType;
    private final @DataFailureCause int mFailCause;
    private final LinkProperties mLinkProperties;
    private final ApnSetting mApnSetting;
    private final Qos mDefaultQos;
    private final @NetworkValidationStatus int mNetworkValidationStatus;

    /** @hide */
    @IntDef(prefix = "NETWORK_VALIDATION_", value = {
            NETWORK_VALIDATION_UNSUPPORTED,
            NETWORK_VALIDATION_NOT_REQUESTED,
            NETWORK_VALIDATION_IN_PROGRESS,
            NETWORK_VALIDATION_SUCCESS,
            NETWORK_VALIDATION_FAILURE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface NetworkValidationStatus {}

    /**
     * Unsupported. The unsupported state is used when the data network cannot support the network
     * validation function for the current data connection state.
     */
    @FlaggedApi(Flags.FLAG_NETWORK_VALIDATION)
    public static final int NETWORK_VALIDATION_UNSUPPORTED = 0;

    /**
     * Not Requested. The not requested status is used when the data network supports the network
     * validation function, but no network validation is being performed yet.
     */
    @FlaggedApi(Flags.FLAG_NETWORK_VALIDATION)
    public static final int NETWORK_VALIDATION_NOT_REQUESTED = 1;

    /**
     * In progress. The in progress state is used when the network validation process for the data
     * network is in progress. This state is followed by either success or failure.
     */
    @FlaggedApi(Flags.FLAG_NETWORK_VALIDATION)
    public static final int NETWORK_VALIDATION_IN_PROGRESS = 2;

    /**
     * Success. The Success status is used when network validation has been completed for the data
     * network and the result is successful.
     */
    @FlaggedApi(Flags.FLAG_NETWORK_VALIDATION)
    public static final int NETWORK_VALIDATION_SUCCESS = 3;

    /**
     * Failure. The Failure status is used when network validation has been completed for the data
     * network and the result is failure.
     */
    @FlaggedApi(Flags.FLAG_NETWORK_VALIDATION)
    public static final int NETWORK_VALIDATION_FAILURE = 4;

    /**
     * Constructor
     *
     * @deprecated this constructor has been superseded and should not be used.
     * @hide
     */
    @TestApi
    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553) // (maxTargetSdk = Build.VERSION_CODES.Q)
    // FIXME: figure out how to remove the UnsupportedAppUsage and delete this constructor
    public PreciseDataConnectionState(@DataState int state,
                                      @NetworkType int networkType,
                                      @ApnType int apnTypes, @NonNull String apn,
                                      @Nullable LinkProperties linkProperties,
                                      @DataFailureCause int failCause) {
        this(AccessNetworkConstants.TRANSPORT_TYPE_INVALID, -1, -1, state, networkType,
                linkProperties, failCause, new ApnSetting.Builder()
                        .setApnTypeBitmask(apnTypes)
                        .setApnName(apn)
                        .setEntryName(apn)
                        .build(), null, NETWORK_VALIDATION_UNSUPPORTED);
    }


    /**
     * Constructor of PreciseDataConnectionState
     *
     * @param transportType The transport of the data connection
     * @param id The id of the data connection
     * @param state The state of the data connection
     * @param networkType The access network that is/would carry this data connection
     * @param linkProperties If the data connection is connected, the properties of the connection
     * @param failCause In case a procedure related to this data connection fails, a non-zero error
     *        code indicating the cause of the failure.
     * @param apnSetting If there is a valid APN for this Data Connection, then the APN Settings;
     *        if there is no valid APN setting for the specific type, then this will be null
     * @param defaultQos If there is a valid QoS for the default bearer supporting this data call,
     *        (supported for LTE and NR), then this is specified. Otherwise it should be null.
     */
    private PreciseDataConnectionState(@TransportType int transportType, int id, int netId,
            @DataState int state, @NetworkType int networkType,
            @Nullable LinkProperties linkProperties, @DataFailureCause int failCause,
            @Nullable ApnSetting apnSetting, @Nullable Qos defaultQos,
            @NetworkValidationStatus int networkValidationStatus) {
        mTransportType = transportType;
        mId = id;
        mNetId = netId;
        mState = state;
        mNetworkType = networkType;
        mLinkProperties = linkProperties;
        mFailCause = failCause;
        mApnSetting = apnSetting;
        mDefaultQos = defaultQos;
        mNetworkValidationStatus = networkValidationStatus;
    }

    /**
     * Construct a PreciseDataConnectionState object from the given parcel.
     *
     * @hide
     */
    private PreciseDataConnectionState(Parcel in) {
        mTransportType = in.readInt();
        mId = in.readInt();
        mNetId = in.readInt();
        mState = in.readInt();
        mNetworkType = in.readInt();
        mLinkProperties = in.readParcelable(
                LinkProperties.class.getClassLoader(),
                android.net.LinkProperties.class);
        mFailCause = in.readInt();
        mApnSetting = in.readParcelable(
                ApnSetting.class.getClassLoader(),
                android.telephony.data.ApnSetting.class);
        mDefaultQos = in.readParcelable(
                Qos.class.getClassLoader(),
                android.telephony.data.Qos.class);
        mNetworkValidationStatus = in.readInt();
    }

    /**
     * Used for checking if the SDK version for
     * {@code PreciseDataConnectionState#getDataConnectionState} is above Q.
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.Q)
    private static final long GET_DATA_CONNECTION_STATE_R_VERSION = 148535736L;

    /**
     * Returns the state of data connection that supported the apn types returned by
     * {@link #getDataConnectionApnTypeBitMask()}
     *
     * @deprecated use {@link #getState()}
     * @hide
     */
    @Deprecated
    @SystemApi
    public @DataState int getDataConnectionState() {
        if (mState == TelephonyManager.DATA_DISCONNECTING
                && !Compatibility.isChangeEnabled(GET_DATA_CONNECTION_STATE_R_VERSION)) {
            return TelephonyManager.DATA_CONNECTED;
        }

        return mState;
    }

    /**
     * @return The transport type of this data connection.
     */
    public @TransportType int getTransportType() {
        return mTransportType;
    }

    /**
     * @return The unique id of the data connection
     *
     * Note this is the id assigned by the data service.
     * The id remains the same for data connection handover between
     * {@link AccessNetworkConstants#TRANSPORT_TYPE_WLAN} and
     * {@link AccessNetworkConstants#TRANSPORT_TYPE_WWAN}
     *
     */
    public int getId() {
        return mId;
    }

    /**
     * @return the current TelephonyNetworkAgent ID. {@code -1} if no network agent.
     * @hide
     */
    public int getNetId() {
        return mNetId;
    }

    /**
     * @return The high-level state of this data connection.
     */
    public @DataState int getState() {
        return mState;
    }

    /**
     * Get the network type associated with this data connection.
     *
     * @return The current/latest (radio) bearer technology that carries this data connection.
     * For a variety of reasons, the network type can change during the life of the data
     * connection, and this information is not reliable unless the physical link is currently
     * active; (there is currently no mechanism to know whether the physical link is active at
     * any given moment). Thus, this value is generally correct but may not be relied-upon to
     * represent the status of the radio bearer at any given moment.
     */
    public @NetworkType int getNetworkType() {
        return mNetworkType;
    }

    /**
     * Returns the APN types mapped to this data connection.
     *
     * @deprecated use {@link #getApnSetting()}
     * @hide
     */
    @Deprecated
    @SystemApi
    public @ApnType int getDataConnectionApnTypeBitMask() {
        return (mApnSetting != null) ? mApnSetting.getApnTypeBitmask() : ApnSetting.TYPE_NONE;
    }

    /**
     * Returns APN of this data connection.
     *
     * @deprecated use {@link #getApnSetting()}
     * @hide
     */
    @NonNull
    @SystemApi
    @Deprecated
    public String getDataConnectionApn() {
        return (mApnSetting != null) ? mApnSetting.getApnName() : "";
    }

    /**
     * Get the properties of the network link {@link LinkProperties}.
     */
    @Nullable
    public LinkProperties getLinkProperties() {
        return mLinkProperties;
    }

    /**
     * Returns the cause code generated by the most recent state change.
     *
     * @deprecated use {@link #getLastCauseCode()}
     * @hide
     */
    @Deprecated
    @SystemApi
    public int getDataConnectionFailCause() {
        return mFailCause;
    }

    /**
     * Returns the cause code generated by the most recent state change.
     *
     * Return the cause code for the most recent change in {@link #getState}. In the event of an
     * error, this cause code will be non-zero.
     */
    public @DataFailureCause int getLastCauseCode() {
        return mFailCause;
    }

    /**
     * Return the APN Settings for this data connection.
     *
     * @return the ApnSetting that was used to configure this data connection. Note that a data
     * connection cannot be established without a valid {@link ApnSetting}. The return value would
     * never be {@code null} even though it has {@link Nullable} annotation.
     */
    public @Nullable ApnSetting getApnSetting() {
        return mApnSetting;
    }

    /**
     * Return the QoS for the default bearer of this data connection.
     *
     * @return the default QoS if known or {@code null} if it is unknown. If the value is reported
     * for LTE, then it will be an {@link android.telephony.data.EpsQos EpsQos}. If the value is
     * reported for 5G, then it will be an {@link android.telehpony.data.NrQos NrQos}. Otherwise it
     * shall always be {@code null}.
     *
     * @hide
     */
    public @Nullable Qos getDefaultQos() {
        return mDefaultQos;
    }

    /**
     * Returns the network validation state.
     *
     * @return the network validation status of the data call
     */
    @FlaggedApi(Flags.FLAG_NETWORK_VALIDATION)
    public @NetworkValidationStatus int getNetworkValidationStatus() {
        return mNetworkValidationStatus;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeInt(mTransportType);
        out.writeInt(mId);
        out.writeInt(mNetId);
        out.writeInt(mState);
        out.writeInt(mNetworkType);
        out.writeParcelable(mLinkProperties, flags);
        out.writeInt(mFailCause);
        out.writeParcelable(mApnSetting, flags);
        out.writeParcelable(mDefaultQos, flags);
        out.writeInt(mNetworkValidationStatus);
    }

    public static final @NonNull Parcelable.Creator<PreciseDataConnectionState> CREATOR
            = new Parcelable.Creator<PreciseDataConnectionState>() {

        public PreciseDataConnectionState createFromParcel(Parcel in) {
            return new PreciseDataConnectionState(in);
        }

        public PreciseDataConnectionState[] newArray(int size) {
            return new PreciseDataConnectionState[size];
        }
    };

    @Override
    public int hashCode() {
        return Objects.hash(mTransportType, mId, mNetId, mState, mNetworkType, mFailCause,
                mLinkProperties, mApnSetting, mDefaultQos, mNetworkValidationStatus);
    }


    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PreciseDataConnectionState that = (PreciseDataConnectionState) o;
        return mTransportType == that.mTransportType
                && mId == that.mId
                && mNetId == that.mNetId
                && mState == that.mState
                && mNetworkType == that.mNetworkType
                && mFailCause == that.mFailCause
                && Objects.equals(mLinkProperties, that.mLinkProperties)
                && Objects.equals(mApnSetting, that.mApnSetting)
                && Objects.equals(mDefaultQos, that.mDefaultQos)
                && mNetworkValidationStatus == that.mNetworkValidationStatus;
    }

    @NonNull
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        sb.append(" state: ").append(TelephonyUtils.dataStateToString(mState));
        sb.append(", transport: ").append(
                AccessNetworkConstants.transportTypeToString(mTransportType));
        sb.append(", id: ").append(mId);
        sb.append(", netId: ").append(mNetId);
        sb.append(", network type: ").append(TelephonyManager.getNetworkTypeName(mNetworkType));
        sb.append(", APN Setting: ").append(mApnSetting);
        sb.append(", link properties: ").append(mLinkProperties);
        sb.append(", default QoS: ").append(mDefaultQos);
        sb.append(", fail cause: ").append(DataFailCause.toString(mFailCause));
        sb.append(", network validation status: ").append(
                networkValidationStatusToString(mNetworkValidationStatus));

        return sb.toString();
    }

    /**
     * Convert a network validation status to string.
     *
     * @param networkValidationStatus network validation status.
     * @return string of validation status.
     *
     * @hide
     */
    @NonNull
    public static String networkValidationStatusToString(
            @NetworkValidationStatus int networkValidationStatus) {
        switch (networkValidationStatus) {
            case NETWORK_VALIDATION_UNSUPPORTED: return "unsupported";
            case NETWORK_VALIDATION_NOT_REQUESTED: return "not requested";
            case NETWORK_VALIDATION_IN_PROGRESS: return "in progress";
            case NETWORK_VALIDATION_SUCCESS: return "success";
            case NETWORK_VALIDATION_FAILURE: return "failure";
            default: return Integer.toString(networkValidationStatus);
        }
    }

    /**
     * {@link PreciseDataConnectionState} builder
     *
     * @hide
     */
    public static final class Builder {
        /** The transport type of the data connection */
        private @TransportType int mTransportType = AccessNetworkConstants.TRANSPORT_TYPE_INVALID;

        /**
         * The unique ID of the data connection. This is the id assigned in
         * {@link DataCallResponse)}.
         */
        private int mId = -1;

        /**
         * The current TelephonyNetworkAgent ID. {@code -1} if no network agent.
         */
        private int mNetworkAgentId = -1;

        /** The state of the data connection */
        private @DataState int mState = TelephonyManager.DATA_UNKNOWN;

        /** The network type associated with this data connection */
        private @NetworkType int mNetworkType = TelephonyManager.NETWORK_TYPE_UNKNOWN;

        /** If the data connection is connected, the properties of the connection */
        private @Nullable LinkProperties mLinkProperties;

        /**
         * In case a procedure related to this data connection fails, a non-zero error code
         * indicating the cause of the failure.
         */
        private @DataFailureCause int mFailCause = DataFailCause.NONE;

        /** The APN Setting for this data connection */
        private @Nullable ApnSetting mApnSetting;

        /** The Default QoS for this EPS/5GS bearer or null otherwise */
        private @Nullable Qos mDefaultQos;

        /** The network validation status for the data connection. */
        private @NetworkValidationStatus int mNetworkValidationStatus =
                NETWORK_VALIDATION_UNSUPPORTED;

        /**
         * Set the transport type of the data connection.
         *
         * @param transportType The transport type of the data connection
         * @return The builder
         */
        public @NonNull Builder setTransportType(@TransportType int transportType) {
            mTransportType = transportType;
            return this;
        }

        /**
         * Set the id of the data connection.
         *
         * @param id The id of the data connection
         * @return The builder
         */
        public @NonNull Builder setId(int id) {
            mId = id;
            return this;
        }

        /**
         * Set the id of the data connection.
         *
         * @param agentId The id of the data connection
         * @return The builder
         */
        public @NonNull Builder setNetworkAgentId(int agentId) {
            mNetworkAgentId = agentId;
            return this;
        }

        /**
         * Set the state of the data connection.
         *
         * @param state The state of the data connection
         * @return The builder
         */
        public @NonNull Builder setState(@DataState int state) {
            mState = state;
            return this;
        }

        /**
         * Set the network type associated with this data connection.
         *
         * @param networkType The network type
         * @return The builder
         */
        public @NonNull Builder setNetworkType(@NetworkType int networkType) {
            mNetworkType = networkType;
            return this;
        }

        /**
         * Set the link properties of the connection.
         *
         * @param linkProperties Link properties
         * @return The builder
         */
        public @NonNull Builder setLinkProperties(LinkProperties linkProperties) {
            mLinkProperties = linkProperties;
            return this;
        }

        /**
         * Set the fail cause of the data connection.
         *
         * @param failCause In case a procedure related to this data connection fails, a non-zero
         * error code indicating the cause of the failure.
         * @return The builder
         */
        public @NonNull Builder setFailCause(@DataFailureCause int failCause) {
            mFailCause = failCause;
            return this;
        }

        /**
         * Set the APN Setting for this data connection.
         *
         * @param apnSetting APN setting
         * @return This builder
         */
        public @NonNull Builder setApnSetting(@NonNull ApnSetting apnSetting) {
            mApnSetting = apnSetting;
            return this;
        }

        /**
         * Set the default QoS for this data connection.
         *
         * @param qos The qos information, if any, associated with the default bearer of the
         * data connection.
         * @return The builder
         * @hide
         */
        public @NonNull Builder setDefaultQos(@Nullable Qos qos) {
            mDefaultQos = qos;
            return this;
        }

        /**
         * Set the network validation state for the data connection.
         *
         * @param networkValidationStatus the network validation status of the data call
         * @return The builder
         */
        @FlaggedApi(Flags.FLAG_NETWORK_VALIDATION)
        public @NonNull Builder setNetworkValidationStatus(
                @NetworkValidationStatus int networkValidationStatus) {
            mNetworkValidationStatus = networkValidationStatus;
            return this;
        }

        /**
         * Build the {@link PreciseDataConnectionState} instance.
         *
         * @return The {@link PreciseDataConnectionState} instance
         */
        public PreciseDataConnectionState build() {
            return new PreciseDataConnectionState(mTransportType, mId, mNetworkAgentId, mState,
                    mNetworkType, mLinkProperties, mFailCause, mApnSetting, mDefaultQos,
                    mNetworkValidationStatus);
        }
    }
}
