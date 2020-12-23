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
import android.annotation.Nullable;
import android.annotation.StringDef;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents a PIDF tuple element that is part of the presence element returned from the carrier
 * network during a SUBSCRIBE request. See RFC3863 for more information.
 * @hide
 */
public class RcsContactPresenceTuple implements Parcelable {

    /** The service id of the MMTEL */
    public static final String SERVICE_ID_MMTEL = "org.3gpp.urn:urn-7:3gpp-service.ims.icsi.mmtel";

    /** The service id of the Call Composer */
    public static final String SERVICE_ID_CALL_COMPOSER =
            "org.3gpp.urn:urn-7:3gppservice.ims.icsi.gsma.callcomposer";

    /** The service capabilities is available. */
    public static final String TUPLE_BASIC_STATUS_OPEN = "open";

    /** The service capabilities is unavailable. */
    public static final String TUPLE_BASIC_STATUS_CLOSED = "closed";

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @StringDef(prefix = "TUPLE_BASIC_STATUS_", value = {
        TUPLE_BASIC_STATUS_OPEN,
        TUPLE_BASIC_STATUS_CLOSED
    })
    public @interface BasicStatus {}

    /**
     * An optional addition to the PIDF Presence Tuple containing service capabilities, which is
     * defined in the servcaps element. See RFC5196, section 3.2.1.
     */
    public static class ServiceCapabilities implements Parcelable {

        /** The service can simultaneously send and receive data. */
        public static final String DUPLEX_MODE_FULL = "full";

        /** The service can alternate between sending and receiving data.*/
        public static final String DUPLEX_MODE_HALF = "half";

        /** The service can only receive data. */
        public static final String DUPLEX_MODE_RECEIVE_ONLY = "receive-only";

        /** The service can only send data. */
        public static final String DUPLEX_MODE_SEND_ONLY = "send-only";

        /** @hide */
        @Retention(RetentionPolicy.SOURCE)
        @StringDef(prefix = "DUPLEX_MODE_", value = {
            DUPLEX_MODE_FULL,
            DUPLEX_MODE_HALF,
            DUPLEX_MODE_RECEIVE_ONLY,
            DUPLEX_MODE_SEND_ONLY
        })
        public @interface DuplexMode {}

        /**
         * Builder to help construct {@link ServiceCapabilities} instances.
         */
        public static class Builder {

            private ServiceCapabilities mCapabilities;

            /**
             * Create the ServiceCapabilities builder, which can be used to set service capabilities
             * as well as custom capability extensions.
             * @param isAudioCapable Whether the audio is capable or not.
             * @param isVideoCapable Whether the video is capable or not.
             */
            public Builder(boolean isAudioCapable, boolean isVideoCapable) {
                mCapabilities = new ServiceCapabilities(isAudioCapable, isVideoCapable);
            }

            /**
             * Add the supported duplex mode.
             * @param mode The supported duplex mode
             */
            public Builder addSupportedDuplexMode(@NonNull @DuplexMode String mode) {
                mCapabilities.mSupportedDuplexModeList.add(mode);
                return this;
            }

            /**
             * Add the unsupported duplex mode.
             * @param mode The unsupported duplex mode
             */
            public Builder addUnsupportedDuplexMode(@NonNull @DuplexMode String mode) {
                mCapabilities.mUnsupportedDuplexModeList.add(mode);
                return this;
            }

            /**
             * @return the ServiceCapabilities instance.
             */
            public ServiceCapabilities build() {
                return mCapabilities;
            }
        }

        private final boolean mIsAudioCapable;
        private final boolean mIsVideoCapable;
        private final @DuplexMode List<String> mSupportedDuplexModeList = new ArrayList<>();
        private final @DuplexMode List<String> mUnsupportedDuplexModeList = new ArrayList<>();

        /**
         * Use {@link Builder} to build an instance of this interface.
         * @param isAudioCapable Whether the audio is capable.
         * @param isVideoCapable Whether the video is capable.
         */
        ServiceCapabilities(boolean isAudioCapable, boolean isVideoCapable) {
            mIsAudioCapable = isAudioCapable;
            mIsVideoCapable = isVideoCapable;
        }

        private ServiceCapabilities(Parcel in) {
            mIsAudioCapable = in.readBoolean();
            mIsVideoCapable = in.readBoolean();
            in.readStringList(mSupportedDuplexModeList);
            in.readStringList(mUnsupportedDuplexModeList);
        }
        @Override
        public void writeToParcel(@NonNull Parcel out, int flags) {
            out.writeBoolean(mIsAudioCapable);
            out.writeBoolean(mIsVideoCapable);
            out.writeStringList(mSupportedDuplexModeList);
            out.writeStringList(mUnsupportedDuplexModeList);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public static final @NonNull Creator<ServiceCapabilities> CREATOR =
                new Creator<ServiceCapabilities>() {
                    @Override
                    public ServiceCapabilities createFromParcel(Parcel in) {
                        return new ServiceCapabilities(in);
                    }

                    @Override
                    public ServiceCapabilities[] newArray(int size) {
                        return new ServiceCapabilities[size];
                    }
                };

        /**
         * Query the audio capable.
         * @return true if the audio is capable, false otherwise.
         */
        public boolean isAudioCapable() {
            return mIsAudioCapable;
        }

        /**
         * Query the video capable.
         * @return true if the video is capable, false otherwise.
         */
        public boolean isVideoCapable() {
            return mIsVideoCapable;
        }

        /**
         * Get the supported duplex mode list.
         * @return The list of supported duplex mode
         */
        public @NonNull @DuplexMode List<String> getSupportedDuplexModes() {
            return Collections.unmodifiableList(mSupportedDuplexModeList);
        }

        /**
         * Get the unsupported duplex mode list.
         * @return The list of unsupported duplex mode
         */
        public @NonNull @DuplexMode List<String> getUnsupportedDuplexModes() {
            return Collections.unmodifiableList(mUnsupportedDuplexModeList);
        }
    }

    /**
     * Builder to help construct {@link RcsContactPresenceTuple} instances.
     */
    public static class Builder {

        private RcsContactPresenceTuple mPresenceTuple;

        /**
         * Builds a RcsContactPresenceTuple instance.
         * @param serviceId The OMA Presence service-id associated with this capability. See the
         * OMA Presence SIMPLE specification v1.1, section 10.5.1.
         * @param serviceVersion The OMA Presence version associated with the service capability.
         * See the OMA Presence SIMPLE specification v1.1, section 10.5.1.
         */
        public Builder(@NonNull @BasicStatus String status, @NonNull String serviceId,
                @NonNull String serviceVersion) {
            mPresenceTuple = new RcsContactPresenceTuple(status, serviceId, serviceVersion);
        }

        /**
         * The optional SIP Contact URI associated with the PIDF tuple element.
         */
        public Builder addContactUri(@NonNull Uri contactUri) {
            mPresenceTuple.mContactUri = contactUri;
            return this;
        }

        /**
         * The optional timestamp indicating the data and time of the status change of this tuple.
         * See RFC3863, section 4.1.7 for more information on the expected format.
         */
        public Builder addTimeStamp(@NonNull String timestamp) {
            mPresenceTuple.mTimestamp = timestamp;
            return this;
        }

        /**
         * An optional parameter containing the description element of the service-description. See
         * OMA Presence SIMPLE specification v1.1
         */
        public Builder addDescription(@NonNull String description) {
            mPresenceTuple.mServiceDescription = description;
            return this;
        }

        /**
         * An optional parameter containing the service capabilities of the presence tuple if they
         * are present in the servcaps element.
         */
        public Builder addServiceCapabilities(@NonNull ServiceCapabilities caps) {
            mPresenceTuple.mServiceCapabilities = caps;
            return this;
        }

        /**
         * @return the constructed instance.
         */
        public RcsContactPresenceTuple build() {
            return mPresenceTuple;
        }
    }

    private Uri mContactUri;
    private String mTimestamp;
    private @BasicStatus String mStatus;

    // The service information in the service-description element.
    private String mServiceId;
    private String mServiceVersion;
    private String mServiceDescription;

    private ServiceCapabilities mServiceCapabilities;

    private RcsContactPresenceTuple(@NonNull @BasicStatus String status, @NonNull String serviceId,
            @NonNull String serviceVersion) {
        mStatus = status;
        mServiceId = serviceId;
        mServiceVersion = serviceVersion;
    }

    private RcsContactPresenceTuple(Parcel in) {
        mContactUri = in.readParcelable(Uri.class.getClassLoader());
        mTimestamp = in.readString();
        mStatus = in.readString();
        mServiceId = in.readString();
        mServiceVersion = in.readString();
        mServiceDescription = in.readString();
        mServiceCapabilities = in.readParcelable(ServiceCapabilities.class.getClassLoader());
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeParcelable(mContactUri, flags);
        out.writeString(mTimestamp);
        out.writeString(mStatus);
        out.writeString(mServiceId);
        out.writeString(mServiceVersion);
        out.writeString(mServiceDescription);
        out.writeParcelable(mServiceCapabilities, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @NonNull Creator<RcsContactPresenceTuple> CREATOR =
            new Creator<RcsContactPresenceTuple>() {
                @Override
                public RcsContactPresenceTuple createFromParcel(Parcel in) {
                    return new RcsContactPresenceTuple(in);
                }

                @Override
                public RcsContactPresenceTuple[] newArray(int size) {
                    return new RcsContactPresenceTuple[size];
                }
            };

    /** @return the status of the tuple element. */
    public @NonNull @BasicStatus String getStatus() {
        return mStatus;
    }

    /** @return the service-id element of the service-description */
    public @NonNull String getServiceId() {
        return mServiceId;
    }

    /** @return the version element of the service-description */
    public @NonNull String getServiceVersion() {
        return mServiceVersion;
    }

    /** @return the SIP URI contained in the contact element of the tuple if it exists. */
    public @Nullable Uri getContactUri() {
        return mContactUri;
    }

    /** @return the timestamp element contained in the tuple if it exists */
    public @Nullable String getTimestamp() {
        return mTimestamp;
    }

    /** @return the description element contained in the service-description if it exists */
    public @Nullable String getServiceDescription() {
        return mServiceDescription;
    }

    /** @return the {@link ServiceCapabilities} of the tuple if it exists. */
    public @Nullable ServiceCapabilities getServiceCapabilities() {
        return mServiceCapabilities;
    }
}
