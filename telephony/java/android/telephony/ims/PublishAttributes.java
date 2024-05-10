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
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.ims.RcsUceAdapter.PublishState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * This class provides detailed information related to publish state, SIP information and
 * presence tuples in publication.
 * This allows the application can check the detailed information of publication.
 * @hide
 */
@SystemApi
public final class PublishAttributes implements Parcelable {

    private final @PublishState int mPublishState;
    private List<RcsContactPresenceTuple> mPresenceTuples;
    private @Nullable SipDetails mSipDetails;

    /**
     * Builder for creating {@link Builder} instances.
     * @hide
     */
    public static final class Builder {
        private PublishAttributes mAttributes;
        /**
         * Build a new instance of {@link PublishAttributes}.
         *
         * @param publishState The current publication state {@link RcsUceAdapter.PublishState}.
         */
        public Builder(@PublishState int publishState) {
            mAttributes = new PublishAttributes(publishState);
        }

        /**
         * Sets the SIP information in received response to a publish operation.
         * @param details The {@link SipDetails} in received response.
         * @return The same instance of the builder.
         */
        public @NonNull Builder setSipDetails(@Nullable SipDetails details) {
            mAttributes.mSipDetails = details;
            return this;
        }

        /**
         * The tuple elements associated with the presence element portion of the PIDF document
         * successfully sent to the network.
         * @param tuples The list of the {@link RcsContactPresenceTuple} sent to the server.
         *               The contact URI should not be included in this tuples.
         * @return this The same instance of the builder.
         */
        public @NonNull Builder setPresenceTuples(@NonNull List<RcsContactPresenceTuple> tuples) {
            mAttributes.mPresenceTuples = tuples;
            return this;
        }

        /**
         * @return a new PublishAttributes from this Builder.
         */
        public @NonNull PublishAttributes build() {
            return mAttributes;
        }
    }

    /**
     * Generate the attributes related to the publication.
     *
     * @param publishState The current publication state.
     *                     See {@link RcsUceAdapter.PublishState}.
     */
    private PublishAttributes(@PublishState int publishState) {
        mPublishState = publishState;
    }

    /**
     * Get the current publication state when the publishing state has changed or
     * the publishing operation has done.
     * @return The current publication state. See {@link RcsUceAdapter.PublishState}.
     */
    public @PublishState int getPublishState() {
        return mPublishState;
    }

    /**
     * Get the presence tuples from the PIDF on which the publishing was successful.
     * @return The list of the {@link RcsContactPresenceTuple} sent to the server. If publish is
     *          not successful yet, the value is empty.
     */
    public @NonNull List<RcsContactPresenceTuple> getPresenceTuples() {
        if (mPresenceTuples == null) {
            return Collections.emptyList();
        }
        return mPresenceTuples;
    }

    /**
     * Get the SipDetails set in ImsService.
     * @return The {@link SipDetails} received in response. This value may be null if
     *          the device doesn't support the collection of this information.
     */
    public @Nullable SipDetails getSipDetails() {
        return mSipDetails;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mPublishState);
        dest.writeList(mPresenceTuples);
        dest.writeParcelable(mSipDetails, 0);
    }

    public static final @NonNull Creator<PublishAttributes> CREATOR =
            new Creator<PublishAttributes>() {
                @Override
                public PublishAttributes createFromParcel(Parcel source) {
                    return new PublishAttributes(source);
                }

                @Override
                public PublishAttributes[] newArray(int size) {
                    return new PublishAttributes[size];
                }
            };

    /**
     * Construct a PublishAttributes object from the given parcel.
     */
    private PublishAttributes(Parcel in) {
        mPublishState = in.readInt();
        mPresenceTuples = new ArrayList<>();
        in.readList(mPresenceTuples, null, RcsContactPresenceTuple.class);
        mSipDetails = in.readParcelable(SipDetails.class.getClassLoader(),
                android.telephony.ims.SipDetails.class);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PublishAttributes that = (PublishAttributes) o;
        return mPublishState == that.mPublishState
                && Objects.equals(mPresenceTuples, that.mPresenceTuples)
                && Objects.equals(mSipDetails, that.mSipDetails);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mPublishState, mPresenceTuples, mSipDetails);
    }

    @Override
    public String toString() {
        return "PublishAttributes { publishState= " + mPublishState
                + ", presenceTuples=[" + mPresenceTuples + "]" + "SipDetails=" + mSipDetails + "}";
    }
}

