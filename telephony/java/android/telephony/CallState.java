/*
 * Copyright (C) 2022 The Android Open Source Project
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
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.Annotation.ImsCallServiceType;
import android.telephony.Annotation.ImsCallType;
import android.telephony.Annotation.NetworkType;
import android.telephony.Annotation.PreciseCallStates;
import android.telephony.ims.ImsCallProfile;
import android.telephony.ims.ImsCallSession;

import java.util.Objects;

/**
 * Contains information about various states for a call.
 * @hide
 */
@SystemApi
public final class CallState implements Parcelable {

    /**
     * Call classifications are just used for backward compatibility of deprecated API {@link
     * TelephonyCallback#CallAttributesListener#onCallAttributesChanged}, Since these will be
     * removed when the deprecated API is removed, they should not be opened.
     */
    /**
     * Call classification is not valid. It should not be opened.
     * @hide
     */
    public static final int CALL_CLASSIFICATION_UNKNOWN = -1;

    /**
     * Call classification indicating foreground call
     * @hide
     */
    public static final int CALL_CLASSIFICATION_RINGING = 0;

    /**
     * Call classification indicating background call
     * @hide
     */
    public static final int CALL_CLASSIFICATION_FOREGROUND = 1;

    /**
     * Call classification indicating ringing call
     * @hide
     */
    public static final int CALL_CLASSIFICATION_BACKGROUND = 2;

    /**
     * Call classification Max value.
     * @hide
     */
    public static final int CALL_CLASSIFICATION_MAX = CALL_CLASSIFICATION_BACKGROUND + 1;

    @PreciseCallStates
    private final int mPreciseCallState;

    @NetworkType
    private final int mNetworkType; // TelephonyManager.NETWORK_TYPE_* ints
    private final CallQuality mCallQuality;

    private final int mCallClassification;
    /**
     * IMS call session ID. {@link ImsCallSession#getCallId()}
     */
    @Nullable
    private String mImsCallId;

    /**
     * IMS call service type of this call
     */
    @ImsCallServiceType
    private int mImsCallServiceType;

    /**
     * IMS call type of this call.
     */
    @ImsCallType
    private int mImsCallType;

    /**
     * Constructor of CallAttributes
     *
     * @param callState call state defined in {@link PreciseCallState}
     * @param networkType network type for this call attributes
     * @param callQuality call quality for this call attributes, only CallState in
     *                    {@link PreciseCallState#PRECISE_CALL_STATE_ACTIVE} will have valid call
     *                    quality.
     * @param callClassification call classification
     * @param imsCallId IMS call session ID for this call attributes
     * @param imsCallServiceType IMS call service type for this call attributes
     * @param imsCallType IMS call type for this call attributes
     */
    private CallState(@PreciseCallStates int callState, @NetworkType int networkType,
            @NonNull CallQuality callQuality, int callClassification, @Nullable String imsCallId,
            @ImsCallServiceType int imsCallServiceType, @ImsCallType int imsCallType) {
        this.mPreciseCallState = callState;
        this.mNetworkType = networkType;
        this.mCallQuality = callQuality;
        this.mCallClassification = callClassification;
        this.mImsCallId = imsCallId;
        this.mImsCallServiceType = imsCallServiceType;
        this.mImsCallType = imsCallType;
    }

    @NonNull
    @Override
    public String toString() {
        return "mPreciseCallState=" + mPreciseCallState + " mNetworkType=" + mNetworkType
                + " mCallQuality=" + mCallQuality + " mCallClassification" + mCallClassification
                + " mImsCallId=" + mImsCallId + " mImsCallServiceType=" + mImsCallServiceType
                + " mImsCallType=" + mImsCallType;
    }

    private CallState(Parcel in) {
        this.mPreciseCallState = in.readInt();
        this.mNetworkType = in.readInt();
        this.mCallQuality = in.readParcelable(
                CallQuality.class.getClassLoader(), CallQuality.class);
        this.mCallClassification = in.readInt();
        this.mImsCallId = in.readString();
        this.mImsCallServiceType = in.readInt();
        this.mImsCallType = in.readInt();
    }

    // getters
    /**
     * Returns the precise call state of the call.
     */
    @PreciseCallStates
    public int getCallState() {
        return mPreciseCallState;
    }

    /**
     * Returns the {@link TelephonyManager#NetworkType} of the call.
     *
     * @see TelephonyManager#NETWORK_TYPE_UNKNOWN
     * @see TelephonyManager#NETWORK_TYPE_GPRS
     * @see TelephonyManager#NETWORK_TYPE_EDGE
     * @see TelephonyManager#NETWORK_TYPE_UMTS
     * @see TelephonyManager#NETWORK_TYPE_CDMA
     * @see TelephonyManager#NETWORK_TYPE_EVDO_0
     * @see TelephonyManager#NETWORK_TYPE_EVDO_A
     * @see TelephonyManager#NETWORK_TYPE_1xRTT
     * @see TelephonyManager#NETWORK_TYPE_HSDPA
     * @see TelephonyManager#NETWORK_TYPE_HSUPA
     * @see TelephonyManager#NETWORK_TYPE_HSPA
     * @see TelephonyManager#NETWORK_TYPE_IDEN
     * @see TelephonyManager#NETWORK_TYPE_EVDO_B
     * @see TelephonyManager#NETWORK_TYPE_LTE
     * @see TelephonyManager#NETWORK_TYPE_EHRPD
     * @see TelephonyManager#NETWORK_TYPE_HSPAP
     * @see TelephonyManager#NETWORK_TYPE_GSM
     * @see TelephonyManager#NETWORK_TYPE_TD_SCDMA
     * @see TelephonyManager#NETWORK_TYPE_IWLAN
     * @see TelephonyManager#NETWORK_TYPE_LTE_CA
     * @see TelephonyManager#NETWORK_TYPE_NR
     */
    @NetworkType
    public int getNetworkType() {
        return mNetworkType;
    }

    /**
     * Returns the {#link CallQuality} of the call.
     * @return call quality for this call attributes, only CallState in {@link
     *         PreciseCallState#PRECISE_CALL_STATE_ACTIVE} will have valid call quality. It will be
     *         null for the call which is not in {@link PreciseCallState#PRECISE_CALL_STATE_ACTIVE}.
     */
    @Nullable
    public CallQuality getCallQuality() {
        return mCallQuality;
    }

    /**
     * Returns the call classification.
     * @hide
     */
    public int getCallClassification() {
        return mCallClassification;
    }

    /**
     * Returns the IMS call session ID.
     */
    @Nullable
    public String getImsCallSessionId() {
        return mImsCallId;
    }

    /**
     * Returns the IMS call service type.
     */
    @ImsCallServiceType
    public int getImsCallServiceType() {
        return mImsCallServiceType;
    }

    /**
     * Returns the IMS call type.
     */
    @ImsCallType
    public int getImsCallType() {
        return mImsCallType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mPreciseCallState, mNetworkType, mCallQuality, mCallClassification,
                mImsCallId, mImsCallServiceType, mImsCallType);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (o == null || !(o instanceof CallState) || hashCode() != o.hashCode()) {
            return false;
        }

        if (this == o) {
            return true;
        }

        CallState s = (CallState) o;

        return (mPreciseCallState == s.mPreciseCallState
                && mNetworkType == s.mNetworkType
                && Objects.equals(mCallQuality, s.mCallQuality)
                && mCallClassification == s.mCallClassification
                && Objects.equals(mImsCallId, s.mImsCallId)
                && mImsCallType == s.mImsCallType
                && mImsCallServiceType == s.mImsCallServiceType);
    }

    /**
     * {@link Parcelable#describeContents}
     */
    public int describeContents() {
        return 0;
    }

    /**
     * {@link Parcelable#writeToParcel}
     */
    public void writeToParcel(@Nullable Parcel dest, int flags) {
        dest.writeInt(mPreciseCallState);
        dest.writeInt(mNetworkType);
        dest.writeParcelable(mCallQuality, flags);
        dest.writeInt(mCallClassification);
        dest.writeString(mImsCallId);
        dest.writeInt(mImsCallServiceType);
        dest.writeInt(mImsCallType);
    }

    public static final @NonNull Creator<CallState> CREATOR = new Creator() {
        public CallState createFromParcel(Parcel in) {
            return new CallState(in);
        }

        public CallState[] newArray(int size) {
            return new CallState[size];
        }
    };

    /**
     * Builder of {@link CallState}
     *
     * <p>The example below shows how you might create a new {@code CallState}. A precise call state
     * {@link PreciseCallStates} is mandatory to build a CallState.
     *
     * <pre><code>
     *
     * CallState = new CallState.Builder({@link PreciseCallStates})
     *     .setNetworkType({@link TelephonyManager#NETWORK_TYPE_LTE})
     *     .setCallQuality({@link CallQuality})
     *     .setImsCallSessionId({@link String})
     *     .setImsCallServiceType({@link ImsCallProfile#SERVICE_TYPE_NORMAL})
     *     .setImsCallType({@link ImsCallProfile#CALL_TYPE_VOICE})
     *     .build();
     * </code></pre>
     */
    public static final class Builder {
        private @PreciseCallStates int mPreciseCallState;
        private @NetworkType int mNetworkType = TelephonyManager.NETWORK_TYPE_UNKNOWN;
        private CallQuality mCallQuality = null;
        private int mCallClassification = CALL_CLASSIFICATION_UNKNOWN;
        private String mImsCallId;
        private @ImsCallServiceType int mImsCallServiceType = ImsCallProfile.SERVICE_TYPE_NONE;
        private @ImsCallType int mImsCallType = ImsCallProfile.CALL_TYPE_NONE;


        /**
         * Default constructor for the Builder.
         */
        public Builder(@PreciseCallStates int preciseCallState) {
            mPreciseCallState = preciseCallState;
        }

        /**
         * Set network type of this call.
         *
         * @param networkType the transport type.
         * @return The same instance of the builder.
         */
        @NonNull
        public CallState.Builder setNetworkType(@NetworkType int networkType) {
            this.mNetworkType = networkType;
            return this;
        }

        /**
         * Set the call quality {@link CallQuality} of this call.
         *
         * @param callQuality call quality of active call.
         * @return The same instance of the builder.
         */
        @NonNull
        public CallState.Builder setCallQuality(@Nullable CallQuality callQuality) {
            this.mCallQuality = callQuality;
            return this;
        }

        /**
         * Set call classification for this call.
         *
         * @param classification call classification type defined in this class.
         * @return The same instance of the builder.
         * @hide
         */
        @NonNull
        public CallState.Builder setCallClassification(int classification) {
            this.mCallClassification = classification;
            return this;
        }

        /**
         * Set IMS call session ID of this call.
         *
         * @param imsCallId  IMS call session ID.
         * @return The same instance of the builder.
         */
        @NonNull
        public CallState.Builder setImsCallSessionId(@Nullable String imsCallId) {
            this.mImsCallId = imsCallId;
            return this;
        }

        /**
         * Set IMS call service type of this call.
         *
         * @param serviceType IMS call service type defined in {@link ImsCallProfile}.
         * @return The same instance of the builder.
         */
        @NonNull
        public CallState.Builder setImsCallServiceType(@ImsCallServiceType int serviceType) {
            this.mImsCallServiceType = serviceType;
            return this;
        }

        /**
         * Set IMS call type of this call.
         *
         * @param callType IMS call type defined in {@link ImsCallProfile}.
         * @return The same instance of the builder.
         */
        @NonNull
        public CallState.Builder setImsCallType(@ImsCallType int callType) {
            this.mImsCallType = callType;
            return this;
        }

        /**
         * Build the {@link CallState}
         *
         * @return the {@link CallState} object
         */
        @NonNull
        public CallState build() {
            return new CallState(
                    mPreciseCallState,
                    mNetworkType,
                    mCallQuality,
                    mCallClassification,
                    mImsCallId,
                    mImsCallServiceType,
                    mImsCallType);
        }
    }
}
