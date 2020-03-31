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
import android.telephony.Annotation.ApnType;
import android.telephony.Annotation.DataFailureCause;
import android.telephony.Annotation.DataState;
import android.telephony.Annotation.NetworkType;
import android.telephony.data.ApnSetting;

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

    private @DataState int mState = TelephonyManager.DATA_UNKNOWN;
    private @NetworkType int mNetworkType = TelephonyManager.NETWORK_TYPE_UNKNOWN;
    private @DataFailureCause int mFailCause = DataFailCause.NONE;
    private @ApnType int mApnTypes = ApnSetting.TYPE_NONE;
    private String mApn = "";
    private LinkProperties mLinkProperties = null;
    private ApnSetting mApnSetting = null;

    /**
     * Constructor
     *
     * @deprecated this constructor has been superseded and should not be used.
     * @hide
     */
    @TestApi
    @Deprecated
    @UnsupportedAppUsage // (maxTargetSdk = Build.VERSION_CODES.Q)
    // FIXME: figure out how to remove the UnsupportedAppUsage and delete this constructor
    public PreciseDataConnectionState(@DataState int state,
                                      @NetworkType int networkType,
                                      @ApnType int apnTypes, @NonNull String apn,
                                      @Nullable LinkProperties linkProperties,
                                      @DataFailureCause int failCause) {
        this(state, networkType, apnTypes, apn, linkProperties, failCause, null);
    }


    /**
     * Constructor of PreciseDataConnectionState
     *
     * @param state the state of the data connection
     * @param networkType the access network that is/would carry this data connection
     * @param apnTypes the APN types that this data connection carries
     * @param apn the APN of this data connection
     * @param linkProperties if the data connection is connected, the properties of the connection
     * @param failCause in case a procedure related to this data connection fails, a non-zero error
     *        code indicating the cause of the failure.
     * @param apnSetting if there is a valid APN for this Data Connection, then the APN Settings;
     *        if there is no valid APN setting for the specific type, then this will be null
     * @hide
     */
    public PreciseDataConnectionState(@DataState int state,
                                      @NetworkType int networkType,
                                      @ApnType int apnTypes, @NonNull String apn,
                                      @Nullable LinkProperties linkProperties,
                                      @DataFailureCause int failCause,
                                      @Nullable ApnSetting apnSetting) {
        mState = state;
        mNetworkType = networkType;
        mApnTypes = apnTypes;
        mApn = apn;
        mLinkProperties = linkProperties;
        mFailCause = failCause;
        mApnSetting = apnSetting;
    }

    /**
     * Empty Constructor
     *
     * @hide
     */
    public PreciseDataConnectionState() {
    }

    /**
     * Construct a PreciseDataConnectionState object from the given parcel.
     *
     * @hide
     */
    private PreciseDataConnectionState(Parcel in) {
        mState = in.readInt();
        mNetworkType = in.readInt();
        mApnTypes = in.readInt();
        mApn = in.readString();
        mLinkProperties = (LinkProperties) in.readParcelable(null);
        mFailCause = in.readInt();
        mApnSetting = (ApnSetting) in.readParcelable(null);
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
     * Returns the high-level state of this data connection.
     */
    public @DataState int getState() {
        return mState;
    }

    /**
     * Returns the network type associated with this data connection.
     *
     * @deprecated use {@link getNetworkType()}
     * @hide
     * @removed Removed from the R preview SDK but was never part of the stable API surface.
     */
    @Deprecated
    @SystemApi
    public @NetworkType int getDataConnectionNetworkType() {
        return mNetworkType;
    }

    /**
     * Returns the network type associated with this data connection.
     *
     * Return the current/latest (radio) bearer technology that carries this data connection.
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
        return mApnTypes;
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
        return mApn;
    }

    /**
     * Get the properties of the network link {@link LinkProperties}.
     *
     * @deprecated use {@link #getLinkProperties()}
     * @hide
     * @removed Removed from the R preview SDK but was never part of the stable API surface.
     */
    @Deprecated
    @SystemApi
    @Nullable
    public LinkProperties getDataConnectionLinkProperties() {
        return mLinkProperties;
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
     * @return the ApnSetting that was used to configure this data connection.
     */
    public @Nullable ApnSetting getApnSetting() {
        return mApnSetting;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeInt(mState);
        out.writeInt(mNetworkType);
        out.writeInt(mApnTypes);
        out.writeString(mApn);
        out.writeParcelable(mLinkProperties, flags);
        out.writeInt(mFailCause);
        out.writeParcelable(mApnSetting, flags);
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
        return Objects.hash(mState, mNetworkType, mApnTypes, mApn, mLinkProperties,
                mFailCause, mApnSetting);
    }

    @Override
    public boolean equals(@Nullable Object obj) {

        if (!(obj instanceof PreciseDataConnectionState)) {
            return false;
        }

        PreciseDataConnectionState other = (PreciseDataConnectionState) obj;
        return Objects.equals(mApn, other.mApn) && mApnTypes == other.mApnTypes
                && mFailCause == other.mFailCause
                && Objects.equals(mLinkProperties, other.mLinkProperties)
                && mNetworkType == other.mNetworkType
                && mState == other.mState
                && Objects.equals(mApnSetting, other.mApnSetting);
    }

    @NonNull
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        sb.append("Data Connection state: " + mState);
        sb.append(", Network type: " + mNetworkType);
        sb.append(", APN types: " + ApnSetting.getApnTypesStringFromBitmask(mApnTypes));
        sb.append(", APN: " + mApn);
        sb.append(", Link properties: " + mLinkProperties);
        sb.append(", Fail cause: " + DataFailCause.toString(mFailCause));
        sb.append(", Apn Setting: " + mApnSetting);

        return sb.toString();
    }
}
