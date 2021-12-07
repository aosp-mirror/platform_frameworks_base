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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArraySet;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;
import java.util.Set;

/**
 * Contains the full state of the IMS feature tags associated with a SipDelegate and managed by the
 * ImsService.
 * @hide
 */
@SystemApi
public final class DelegateRegistrationState implements Parcelable {

    /**
     * This feature tag has been deregistered for an unknown reason. Outgoing out-of-dialog SIP
     * messages associated with feature tags that are not registered will fail.
     */
    public static final int DEREGISTERED_REASON_UNKNOWN = 0;

    /**
     * This feature tag has been deregistered because it is not provisioned to be used on this radio
     * access technology or PDN. Outgoing out-of-dialog SIP messages associated with feature tags
     * that are not registered will fail.
     * <p>
     * There may be new incoming SIP dialog requests on a feature that that is not provisioned. It
     * is still expected that the SipDelegateConnection responds to the request.
     */
    public static final int DEREGISTERED_REASON_NOT_PROVISIONED = 1;

    /**
     * This feature tag has been deregistered because IMS has been deregistered. All outgoing SIP
     * messages will fail until IMS registration occurs.
     */
    public static final int DEREGISTERED_REASON_NOT_REGISTERED = 2;

    /**
     * This feature tag is being deregistered because the PDN that the IMS registration is on is
     *changing.
     * All open SIP dialogs need to be closed before the PDN change can proceed using
     * {@link SipDelegateConnection#cleanupSession(String)}.
     */
    public static final int DEREGISTERING_REASON_PDN_CHANGE = 3;

    /**
     * This feature tag is being deregistered due to a provisioning change. This can be triggered by
     * many things, such as a provisioning change triggered by the carrier network, a radio access
     * technology change by the modem causing a different set of feature tags to be provisioned, or
     * a user triggered hange, such as data being enabled/disabled.
     * <p>
     * All open SIP dialogs associated with the new deprovisioned feature tag need to be closed
     * using {@link SipDelegateConnection#cleanupSession(String)} before the IMS registration
     * modification can proceed.
     */
    public static final int DEREGISTERING_REASON_PROVISIONING_CHANGE = 4;

    /**
     * This feature tag is deregistering because the SipDelegate associated with this feature tag
     * needs to change its supported feature set.
     * <p>
     * All open SIP Dialogs associated with this feature tag must be  closed
     * using {@link SipDelegateConnection#cleanupSession(String)} before this operation can proceed.
     */
    public static final int DEREGISTERING_REASON_FEATURE_TAGS_CHANGING = 5;

    /**
     * This feature tag is deregistering because the SipDelegate is in the process of being
     * destroyed.
     * <p>
     * All open SIP Dialogs associated with this feature tag must be closed
     * using {@link SipDelegateConnection#cleanupSession(String)} before this operation can proceed.
     */
    public static final int DEREGISTERING_REASON_DESTROY_PENDING = 6;

    /**
     * This feature tag is deregistering because the PDN that the IMS registration is on
     * is being torn down.
     * <p>
     * All open SIP Dialogs associated with this feature tag must be  closed
     * using {@link SipDelegateConnection#cleanupSession(String)} before this operation can proceed.
     */
    public static final int DEREGISTERING_REASON_LOSING_PDN = 7;

    /**
     * This feature tag is deregistering because of an unspecified reason.
     * <p>
     * All open SIP Dialogs associated with this feature tag must be  closed
     * using {@link SipDelegateConnection#cleanupSession(String)} before this operation can proceed.
     */
    public static final int DEREGISTERING_REASON_UNSPECIFIED = 8;

/** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "DEREGISTERED_REASON_", value = {
            DEREGISTERED_REASON_UNKNOWN,
            DEREGISTERED_REASON_NOT_PROVISIONED,
            DEREGISTERED_REASON_NOT_REGISTERED
    })
    public @interface DeregisteredReason {}


    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "DEREGISTERING_REASON_", value = {
            DEREGISTERING_REASON_PDN_CHANGE,
            DEREGISTERING_REASON_PROVISIONING_CHANGE,
            DEREGISTERING_REASON_FEATURE_TAGS_CHANGING,
            DEREGISTERING_REASON_DESTROY_PENDING,
            DEREGISTERING_REASON_LOSING_PDN,
            DEREGISTERING_REASON_UNSPECIFIED
    })
    public @interface DeregisteringReason {}

    private ArraySet<String> mRegisteringTags = new ArraySet<>();
    private ArraySet<String> mRegisteredTags = new ArraySet<>();
    private final ArraySet<FeatureTagState> mDeregisteringTags = new ArraySet<>();
    private final ArraySet<FeatureTagState> mDeregisteredTags = new ArraySet<>();

    /**
     * Builder used to create new instances of {@link DelegateRegistrationState}.
     */
    public static final class Builder {

        private final DelegateRegistrationState mState;

        /* Create a new instance of {@link Builder} */
        public Builder() {
            mState = new DelegateRegistrationState();
        }

        /**
         * Add the set of feature tags that are associated with this SipDelegate and
         * the IMS stack is actively trying to register on the carrier network.
         *
         * The feature tags will either move to the registered or deregistered state
         * depending on the result of the registration.
         * @param featureTags The IMS media feature tags that are in the progress of registering.
         * @return The in-progress Builder instance for RegistrationState. ]
         */
        public @NonNull Builder addRegisteringFeatureTags(@NonNull Set<String> featureTags) {
            mState.mRegisteringTags.addAll(featureTags);
            return this;
        }

        /**
         * Add a feature tag that is currently included in the current network IMS Registration.
         * @param featureTag The IMS media feature tag included in the current IMS registration.
         * @return The in-progress Builder instance for RegistrationState.
         */
        public @NonNull Builder addRegisteredFeatureTag(@NonNull String featureTag) {
            mState.mRegisteredTags.add(featureTag);
            return this;
        }

        /**
         * Add a list of feature tags that are currently included in the current network IMS
         * Registration.
         * @param featureTags The IMS media feature tags included in the current IMS registration.
         * @return The in-progress Builder instance for RegistrationState.
         */
        @SuppressLint("MissingGetterMatchingBuilder")
        public @NonNull Builder addRegisteredFeatureTags(@NonNull Set<String> featureTags) {
            mState.mRegisteredTags.addAll(featureTags);
            return this;
        }

        /**
         * Add a feature tag that is in the current network IMS Registration, but is in the progress
         * of being deregistered and requires action from the RCS application before the IMS
         * registration can be modified.
         *
         * See {@link DeregisteringReason} for more information regarding what is required by the
         * RCS application to proceed.
         *
         * @param featureTag The media feature tag that has limited or no availability due to its
         *         current deregistering state.
         * @param reason The reason why the media feature tag has moved to the deregistering state.
         *         The availability of the feature tag depends on the {@link DeregisteringReason}.
         * @return The in-progress Builder instance for RegistrationState.
         */
        public @NonNull Builder addDeregisteringFeatureTag(@NonNull String featureTag,
                @DeregisteringReason int reason) {
            mState.mDeregisteringTags.add(new FeatureTagState(featureTag, reason));
            return this;
        }

        /**
         * Add a feature tag that is currently not included in the network RCS registration. See
         * {@link DeregisteredReason} for more information regarding the reason for why the feature
         * tag is not registered.
         * @param featureTag The media feature tag that is not registered.
         * @param reason The reason why the media feature tag has been deregistered.
         * @return The in-progress Builder instance for RegistrationState.
         */
        public @NonNull Builder addDeregisteredFeatureTag(@NonNull String featureTag,
                @DeregisteredReason int reason) {
            mState.mDeregisteredTags.add(new FeatureTagState(featureTag, reason));
            return this;
        }

        /**
         * @return the finalized instance.
         */
        public @NonNull DelegateRegistrationState build() {
            return mState;
        }
    }

    /**
     * The builder should be used to construct a new instance of this class.
     */
    private DelegateRegistrationState() {}

    /**
     * Used for unparcelling only.
     */
    private DelegateRegistrationState(Parcel source) {
        mRegisteredTags = (ArraySet<String>) source.readArraySet(null);
        readStateFromParcel(source, mDeregisteringTags);
        readStateFromParcel(source, mDeregisteredTags);
        mRegisteringTags = (ArraySet<String>) source.readArraySet(null);
    }

    /**
     * Get the feature tags that are associated with this SipDelegate that the IMS stack is actively
     * trying to register on the carrier network.
     * @return A Set of feature tags associated with this SipDelegate that the IMS service is
     * currently trying to register on the  carrier network.
     */
    public @NonNull Set<String> getRegisteringFeatureTags() {
        return new ArraySet<>(mRegisteringTags);
    }

    /**
     * Get the feature tags that this SipDelegate is associated with that are currently part of the
     * network IMS registration. SIP Messages both in and out of a SIP Dialog may be sent and
     * received using these feature tags.
     * @return A Set of feature tags that the SipDelegate has associated with that are included in
     * the network IMS registration.
     */
    public @NonNull Set<String> getRegisteredFeatureTags() {
        return new ArraySet<>(mRegisteredTags);
    }

    /**
     * Get the feature tags that this SipDelegate is associated with that are currently part of the
     * network IMS registration but are in the process of being deregistered.
     * <p>
     * Any incoming SIP messages associated with a feature tag included in this list will still be
     * delivered. Outgoing SIP messages that are still in-dialog will be delivered to the
     * SipDelegate, but outgoing out-of-dialog SIP messages with  a feature tag that is included in
     * this list will fail.
     * <p>
     * The SipDelegate will stay in this state for a limited period of time while it waits for the
     * RCS application to perform a specific action. More details on the actions that can cause this
     * state as well as the expected response are included in the reason codes and can be found in
     * {@link DeregisteringReason}.
     * @return A Set of feature tags that the SipDelegate has associated with that are included in
     * the network IMS registration but are in the process of deregistering.
     */
    public @NonNull Set<FeatureTagState> getDeregisteringFeatureTags() {
        return new ArraySet<>(mDeregisteringTags);
    }

    /**
     * Get the list of feature tags that are associated with this SipDelegate but are not currently
     * included in the network IMS registration.
     * <p>
     * See {@link DeregisteredReason} codes for more information related to the reasons why this may
     * occur.
     * <p>
     * Due to network race conditions, there may still be onditions where an incoming out-of-dialog
     * SIP message is delivered for a feature tag that is considered deregistered. Due to this
     * condition, in-dialog outgoing SIP messages for deregistered feature tags will still be
     * allowed as long as they are in response to a dialog started by a remote party. Any outgoing
     * out-of-dialog SIP messages associated with feature tags included in this list will fail to be
     * sent.
     * @return A list of feature tags that the SipDelegate has associated with that not included in
     * the network IMS registration.
     */
    public @NonNull Set<FeatureTagState> getDeregisteredFeatureTags() {
        return new ArraySet<>(mDeregisteredTags);
    }


    public static final @NonNull Creator<DelegateRegistrationState> CREATOR =
            new Creator<DelegateRegistrationState>() {
        @Override
        public DelegateRegistrationState createFromParcel(Parcel source) {
            return new DelegateRegistrationState(source);
        }

        @Override
        public DelegateRegistrationState[] newArray(int size) {
            return new DelegateRegistrationState[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeArraySet(mRegisteredTags);
        writeStateToParcel(dest, mDeregisteringTags);
        writeStateToParcel(dest, mDeregisteredTags);
        dest.writeArraySet(mRegisteringTags);
    }

    private void writeStateToParcel(Parcel dest, Set<FeatureTagState> state) {
        dest.writeInt(state.size());
        for (FeatureTagState s : state) {
            dest.writeString(s.getFeatureTag());
            dest.writeInt(s.getState());
        }
    }

    private void readStateFromParcel(Parcel source, Set<FeatureTagState> emptyState) {
        int len = source.readInt();
        for (int i = 0; i < len; i++) {
            String ft = source.readString();
            int reason = source.readInt();

            emptyState.add(new FeatureTagState(ft, reason));
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DelegateRegistrationState that = (DelegateRegistrationState) o;
        return mRegisteringTags.equals(that.mRegisteringTags)
                && mRegisteredTags.equals(that.mRegisteredTags)
                && mDeregisteringTags.equals(that.mDeregisteringTags)
                && mDeregisteredTags.equals(that.mDeregisteredTags);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mRegisteringTags, mRegisteredTags,
                mDeregisteringTags, mDeregisteredTags);
    }

    @Override
    public String toString() {
        return "DelegateRegistrationState{ registered={" + mRegisteredTags
                + "}, registering={" + mRegisteringTags
                + "}, deregistering={" + mDeregisteringTags + "}, deregistered={"
                + mDeregisteredTags + "}}";
    }
}
