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

package android.telephony.ims;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.Annotation.PreciseCallStates;

import java.util.Objects;

/**
 * A Parcelable object to represent the current state of an IMS call that is being tracked
 * in the ImsService when an SRVCC begins. This information will be delivered to modem.
 * @see SrvccStartedCallback
 *
 * @hide
 */
@SystemApi
public final class SrvccCall implements Parcelable {
    private static final String TAG = "SrvccCall";

    /** The IMS call profile */
    private ImsCallProfile mImsCallProfile;

    /** The IMS call id */
    private String mCallId;

    /** The call state */
    private @PreciseCallStates int mCallState;

    private SrvccCall(Parcel in) {
        readFromParcel(in);
    }

    /**
     * Constructs an instance of SrvccCall.
     *
     * @param callId the call ID associated with the IMS call
     * @param callState the state of this IMS call
     * @param imsCallProfile the profile associated with this IMS call
     * @throws IllegalArgumentException if the callId or the imsCallProfile is null
     */
    public SrvccCall(@NonNull String callId, @PreciseCallStates int callState,
            @NonNull ImsCallProfile imsCallProfile) {
        if (callId == null) throw new IllegalArgumentException("callId is null");
        if (imsCallProfile == null) throw new IllegalArgumentException("imsCallProfile is null");

        mCallId = callId;
        mCallState = callState;
        mImsCallProfile = imsCallProfile;
    }

    /**
     * @return the {@link ImsCallProfile} associated with this IMS call,
     * which will be used to get the address, the name, and the audio direction
     * including the call in pre-alerting state.
     */
    @NonNull
    public ImsCallProfile getImsCallProfile() {
        return mImsCallProfile;
    }

    /**
     * @return the call ID associated with this IMS call.
     *
     * @see android.telephony.ims.stub.ImsCallSessionImplBase#getCallId().
     */
    @NonNull
    public String getCallId() {
        return mCallId;
    }

    /**
     * @return the call state of the associated IMS call.
     */
    public @PreciseCallStates int getPreciseCallState() {
        return mCallState;
    }

    @NonNull
    @Override
    public String toString() {
        return "{ callId=" + mCallId
                + ", callState=" + mCallState
                + ", imsCallProfile=" + mImsCallProfile
                + " }";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SrvccCall that = (SrvccCall) o;
        return mImsCallProfile.equals(that.mImsCallProfile)
                && mCallId.equals(that.mCallId)
                && mCallState == that.mCallState;
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(mImsCallProfile, mCallId);
        result = 31 * result + mCallState;
        return result;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeString(mCallId);
        out.writeInt(mCallState);
        out.writeParcelable(mImsCallProfile, 0);
    }

    private void readFromParcel(Parcel in) {
        mCallId = in.readString();
        mCallState = in.readInt();
        mImsCallProfile = in.readParcelable(ImsCallProfile.class.getClassLoader(),
                android.telephony.ims.ImsCallProfile.class);
    }

    public static final @android.annotation.NonNull Creator<SrvccCall> CREATOR =
            new Creator<SrvccCall>() {
        @Override
        public SrvccCall createFromParcel(Parcel in) {
            return new SrvccCall(in);
        }

        @Override
        public SrvccCall[] newArray(int size) {
            return new SrvccCall[size];
        }
    };
}
