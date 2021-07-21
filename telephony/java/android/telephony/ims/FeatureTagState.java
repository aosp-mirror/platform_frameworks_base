/*
 * Copyright (C) 2020 The Android Open Source Project
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
import android.telephony.ims.stub.DelegateConnectionStateCallback;
import android.telephony.ims.stub.SipDelegate;

import java.util.List;
import java.util.Objects;

/**
 * Maps an IMS media feature tag 3gpp universal resource name (URN) previously mapped to a
 * {@link SipDelegate} in the associated {@link DelegateRequest} to its current availability
 * state as set by the ImsService managing the related IMS registration.
 *
 * This class is only used to report more information about a IMS feature tag that is not fully
 * available at this time.
 * <p>
 * Please see {@link DelegateRegistrationState}, {@link DelegateStateCallback}, and
 * {@link DelegateConnectionStateCallback} for more information about how this class is used to
 * convey the state of IMS feature tags that were requested by {@link DelegateRequest} but are not
 * currently available.
 * @hide
 */
@SystemApi
public final class FeatureTagState implements Parcelable {

    private final String mFeatureTag;
    private final int mState;

    /**
     * Associate an IMS feature tag with its current state. See {@link DelegateRegistrationState}
     * and {@link DelegateConnectionStateCallback#onFeatureTagStatusChanged(
     * DelegateRegistrationState, List)} and
     * {@link DelegateStateCallback#onCreated(SipDelegate, java.util.Set)} for examples on how and
     * when this is used.
     *
     * @param featureTag The IMS feature tag that is deregistered, in the process of
     *                   deregistering, or denied.
     * @param state The {@link DelegateRegistrationState.DeregisteredReason},
     *         {@link DelegateRegistrationState.DeregisteringReason}, or
     *         {@link SipDelegateManager.DeniedReason} associated with this feature tag.
     */
    public FeatureTagState(@NonNull String featureTag, int state) {
        mFeatureTag = featureTag;
        mState = state;
    }

    /**
     * Used for constructing instances during un-parcelling.
     */
    private FeatureTagState(Parcel source) {
        mFeatureTag = source.readString();
        mState = source.readInt();
    }

    /**
     * @return The IMS feature tag string that is in the process of deregistering,
     * deregistered, or denied.
     */
    public @NonNull String getFeatureTag() {
        return mFeatureTag;
    }

    /**
     * @return The reason for why the feature tag is currently in the process of deregistering,
     * has been deregistered, or has been denied. See {@link DelegateRegistrationState} and
     * {@link DelegateConnectionStateCallback} for more information.
     */
    public int getState() {
        return mState;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mFeatureTag);
        dest.writeInt(mState);
    }

    public static final @NonNull Creator<FeatureTagState> CREATOR = new Creator<FeatureTagState>() {
        @Override
        public FeatureTagState createFromParcel(Parcel source) {
            return new FeatureTagState(source);
        }

        @Override
        public FeatureTagState[] newArray(int size) {
            return new FeatureTagState[size];
        }
    };

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FeatureTagState that = (FeatureTagState) o;
        return mState == that.mState
                && mFeatureTag.equals(that.mFeatureTag);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mFeatureTag, mState);
    }

    @Override
    public String toString() {
        return "FeatureTagState{" + "mFeatureTag='" + mFeatureTag + ", mState=" + mState + '}';
    }
}
