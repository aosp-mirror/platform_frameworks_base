/*
 * Copyright (C) 2018 The Android Open Source Project
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
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Contains the User Capability Exchange capabilities corresponding to a contact's URI.
 * @hide
 */
@SystemApi
public final class RcsContactUceCapability implements Parcelable {

    /** Contains presence information associated with the contact */
    public static final int CAPABILITY_MECHANISM_PRESENCE = 1;

    /** Contains OPTIONS information associated with the contact */
    public static final int CAPABILITY_MECHANISM_OPTIONS = 2;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "CAPABILITY_MECHANISM_", value = {
        CAPABILITY_MECHANISM_PRESENCE,
        CAPABILITY_MECHANISM_OPTIONS
    })
    public @interface CapabilityMechanism {}

    /**
     * The capabilities of this contact were requested recently enough to still be considered in
     * the availability window.
     */
    public static final int SOURCE_TYPE_NETWORK = 0;

    /**
     * The capabilities of this contact were retrieved from the cached information in the Enhanced
     * Address Book.
     */
    public static final int SOURCE_TYPE_CACHED = 1;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "SOURCE_TYPE_", value = {
        SOURCE_TYPE_NETWORK,
        SOURCE_TYPE_CACHED
    })
    public @interface SourceType {}

    /**
     * Capability information for the requested contact has expired and can not be refreshed due to
     * a temporary network error. This is a temporary error and the capabilities of the contact
     * should be queried again at a later time.
     */
    public static final int REQUEST_RESULT_UNKNOWN = 0;

    /**
     * The requested contact was found to be offline when queried. This is only applicable to
     * contact capabilities that were queried via OPTIONS requests and the network returned a
     * 408/480 response.
     */
    public static final int REQUEST_RESULT_NOT_ONLINE = 1;

    /**
     * Capability information for the requested contact was not found. The contact should not be
     * considered an RCS user.
     */
    public static final int REQUEST_RESULT_NOT_FOUND = 2;

    /**
     * Capability information for the requested contact was found successfully.
     */
    public static final int REQUEST_RESULT_FOUND = 3;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "REQUEST_RESULT_", value = {
        REQUEST_RESULT_UNKNOWN,
        REQUEST_RESULT_NOT_ONLINE,
        REQUEST_RESULT_NOT_FOUND,
        REQUEST_RESULT_FOUND
    })
    public @interface RequestResult {}

    /**
     * Builder to help construct {@link RcsContactUceCapability} instances when capabilities were
     * queried through SIP OPTIONS.
     * @hide
     */
    public static final class OptionsBuilder {

        private final RcsContactUceCapability mCapabilities;

        /**
         * Create the Builder, which can be used to set UCE capabilities as well as custom
         * capability extensions.
         * @param contact The contact URI that the capabilities are attached to.
         */
        public OptionsBuilder(@NonNull Uri contact) {
            mCapabilities = new RcsContactUceCapability(contact, CAPABILITY_MECHANISM_OPTIONS,
                    SOURCE_TYPE_NETWORK);
        }

        /**
         * Set the result of the capabilities request.
         * @param requestResult the request result
         * @return this OptionBuilder
         */
        public @NonNull OptionsBuilder setRequestResult(@RequestResult int requestResult) {
            mCapabilities.mRequestResult = requestResult;
            return this;
        }

        /**
         * Add the feature tag into the capabilities instance.
         * @param tag the supported feature tag
         * @return this OptionBuilder
         */
        public @NonNull OptionsBuilder addFeatureTag(@NonNull String tag) {
            mCapabilities.mFeatureTags.add(tag);
            return this;
        }

        /**
         * Add the list of feature tag into the capabilities instance.
         * @param tags the list of the supported feature tags
         * @return this OptionBuilder
         */
        public @NonNull OptionsBuilder addFeatureTags(@NonNull List<String> tags) {
            mCapabilities.mFeatureTags.addAll(tags);
            return this;
        }

        /**
         * @return the constructed instance.
         */
        public @NonNull RcsContactUceCapability build() {
            return mCapabilities;
        }
    }

    /**
     * Builder to help construct {@link RcsContactUceCapability} instances when capabilities were
     * queried through a presence server.
     */
    public static final class PresenceBuilder {

        private final RcsContactUceCapability mCapabilities;

        /**
         * Create the builder, which can be used to set UCE capabilities as well as custom
         * capability extensions.
         * @param contact The contact URI that the capabilities are attached to.
         * @param sourceType The type where the capabilities of this contact were retrieved from.
         * @param requestResult the request result
         */
        public PresenceBuilder(@NonNull Uri contact, @SourceType int sourceType,
                @RequestResult int requestResult) {
            mCapabilities = new RcsContactUceCapability(contact, CAPABILITY_MECHANISM_PRESENCE,
                sourceType);
            mCapabilities.mRequestResult = requestResult;
        }

        /**
         * Add the {@link RcsContactPresenceTuple} into the capabilities instance.
         * @param tuple The {@link RcsContactPresenceTuple} to be added into.
         * @return this PresenceBuilder
         */
        public @NonNull PresenceBuilder addCapabilityTuple(@NonNull RcsContactPresenceTuple tuple) {
            mCapabilities.mPresenceTuples.add(tuple);
            return this;
        }

        /**
         * Add the list of {@link RcsContactPresenceTuple} into the capabilities instance.
         * @param tuples The list of the {@link RcsContactPresenceTuple} to be added into.
         * @return this PresenceBuilder
         */
        public @NonNull PresenceBuilder addCapabilityTuples(
                @NonNull List<RcsContactPresenceTuple> tuples) {
            mCapabilities.mPresenceTuples.addAll(tuples);
            return this;
        }

        /**
         * @return the RcsContactUceCapability instance.
         */
        public @NonNull RcsContactUceCapability build() {
            return mCapabilities;
        }
    }

    private final Uri mContactUri;
    private @SourceType int mSourceType;
    private @CapabilityMechanism int mCapabilityMechanism;
    private @RequestResult int mRequestResult;

    private final List<String> mFeatureTags = new ArrayList<>();
    private final List<RcsContactPresenceTuple> mPresenceTuples = new ArrayList<>();

    private RcsContactUceCapability(@NonNull Uri contactUri, @CapabilityMechanism int mechanism,
            @SourceType int sourceType) {
        mContactUri = contactUri;
        mCapabilityMechanism = mechanism;
        mSourceType = sourceType;
    }

    private RcsContactUceCapability(Parcel in) {
        mContactUri = in.readParcelable(Uri.class.getClassLoader());
        mCapabilityMechanism = in.readInt();
        mSourceType = in.readInt();
        mRequestResult = in.readInt();
        in.readStringList(mFeatureTags);
        in.readParcelableList(mPresenceTuples, RcsContactPresenceTuple.class.getClassLoader());
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeParcelable(mContactUri, flags);
        out.writeInt(mCapabilityMechanism);
        out.writeInt(mSourceType);
        out.writeInt(mRequestResult);
        out.writeStringList(mFeatureTags);
        out.writeParcelableList(mPresenceTuples, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @NonNull Creator<RcsContactUceCapability> CREATOR =
            new Creator<RcsContactUceCapability>() {
                @Override
                public RcsContactUceCapability createFromParcel(Parcel in) {
                    return new RcsContactUceCapability(in);
                }

                @Override
                public RcsContactUceCapability[] newArray(int size) {
                    return new RcsContactUceCapability[size];
                }
            };

    /**
     * @return The mechanism used to get the capabilities.
     */
    public @CapabilityMechanism int getCapabilityMechanism() {
        return mCapabilityMechanism;
    }

    /**
     * @return The feature tags present in the OPTIONS response from the network.
     * <p>
     * Note: this is only populated if {@link #getCapabilityMechanism} is
     * {@link RcsContactUceCapability#CAPABILITY_MECHANISM_OPTIONS}
     * @hide
     */
    public @NonNull List<String> getOptionsFeatureTags() {
        if (mCapabilityMechanism != CAPABILITY_MECHANISM_OPTIONS) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(mFeatureTags);
    }

    /**
     * @return The tuple elements associated with the presence element portion of the PIDF document
     * contained in the NOTIFY response from the network.
     * <p>
     * Note: this is only populated if {@link #getCapabilityMechanism} is
     * {@link RcsContactUceCapability#CAPABILITY_MECHANISM_PRESENCE}
     */
    public @NonNull List<RcsContactPresenceTuple> getCapabilityTuples() {
        if (mCapabilityMechanism != CAPABILITY_MECHANISM_PRESENCE) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(mPresenceTuples);
    }

    /**
     * Get the RcsContactPresenceTuple associated with the given service id.
     * @param serviceId The service id to get the presence tuple.
     * @return The RcsContactPresenceTuple which has the given service id or {@code null} if the
     * service id does not exist in the list of presence tuples returned from the network.
     *
     * <p>
     * Note: this is only populated if {@link #getCapabilityMechanism} is
     * {@link RcsContactUceCapability#CAPABILITY_MECHANISM_PRESENCE}
     */
    public @Nullable RcsContactPresenceTuple getCapabilityTuple(@NonNull String serviceId) {
        if (mCapabilityMechanism != CAPABILITY_MECHANISM_PRESENCE) {
            return null;
        }
        for (RcsContactPresenceTuple tuple : mPresenceTuples) {
            if (tuple.getServiceId().equals(serviceId)) {
                return tuple;
            }
        }
        return null;
    }

    /**
     * @return the source of the data that was used to populate the capabilities of the requested
     * contact.
     */
    public @SourceType int getSourceType() {
        return mSourceType;
    }

    /**
     * @return the result of querying the capabilities of the requested contact.
     */
    public @RequestResult int getRequestResult() {
        return mRequestResult;
    }

    /**
     * Retrieve the contact URI requested by the applications.
     * @return the URI representing the contact associated with the capabilities.
     */
    public @NonNull Uri getContactUri() {
        return mContactUri;
    }
}
