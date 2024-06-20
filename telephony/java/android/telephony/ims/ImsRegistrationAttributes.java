/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.AccessNetworkConstants;
import android.telephony.ims.stub.ImsRegistrationImplBase;
import android.util.ArraySet;

import com.android.internal.telephony.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;

/**
 * Contains the attributes associated with the current IMS registration.
 */
public final class ImsRegistrationAttributes implements Parcelable {

    /**
     * Attribute to specify if an EPDG tunnel is setup over the cellular internet APN.
     * <p>
     * If IMS is registered through an EPDG tunnel is setup over the cellular internet APN then this
     * bit will be set. If IMS is registered through the IMS APN, then this bit will not be set.
     *
     */
    public static final int ATTR_EPDG_OVER_CELL_INTERNET = 1 << 0;
    /**
     * Attribute to specify if ims registration is of type normal or emergency.
     * <p>
     *     For emergency registration bit will be set.
     *     For normal registration bit will not be set.
     *     This flag is only applicable when listening to emergency IMS registration state updates
     *     via the ImsMmTelManager#registerImsEmergencyRegistrationCallback API
     * </p>
     */
    @FlaggedApi(Flags.FLAG_EMERGENCY_REGISTRATION_STATE)
    public static final int ATTR_REGISTRATION_TYPE_EMERGENCY = 1 << 1;
    /**
     * Attribute to specify if virtual registration is required.
     * <p>
     *     If emergency registration is not required for making emergency call, in such cases
     *     bit will be set and callback will represent virtual registration status update.
     *     This flag is only applicable when listening to emergency IMS registration state updates
     *     via the ImsMmTelManager#registerImsEmergencyRegistrationCallback API
     * </p>
     */
    @FlaggedApi(Flags.FLAG_EMERGENCY_REGISTRATION_STATE)
    public static final int ATTR_VIRTUAL_FOR_ANONYMOUS_EMERGENCY_CALL = 1 << 2;

    /** @hide */
    // Defines the underlying radio technology type that we have registered for IMS over.
    @IntDef(prefix = "ATTR_",
            value = {
                    ATTR_EPDG_OVER_CELL_INTERNET,
                    ATTR_REGISTRATION_TYPE_EMERGENCY,
                    ATTR_VIRTUAL_FOR_ANONYMOUS_EMERGENCY_CALL,
            },
            flag = true)
    @Retention(RetentionPolicy.SOURCE)
    public @interface ImsAttributeFlag {}

    /**
     * Builder for creating {@link ImsRegistrationAttributes} instances.
     * @hide
     */
    @SystemApi
    public static final class Builder {
        private final int mRegistrationTech;
        private Set<String> mFeatureTags = Collections.emptySet();
        private @Nullable SipDetails mSipDetails;

        private @ImsAttributeFlag int mAttributeFlags;

        /**
         * Build a new instance of {@link ImsRegistrationAttributes}.
         *
         * @param registrationTech The Radio Access Technology that IMS is registered on.
         */
        public Builder(@ImsRegistrationImplBase.ImsRegistrationTech int registrationTech) {
            mRegistrationTech = registrationTech;
            if (registrationTech == ImsRegistrationImplBase.REGISTRATION_TECH_CROSS_SIM) {
                mAttributeFlags |= ATTR_EPDG_OVER_CELL_INTERNET;
            }
        }

        /**
         * Optional IMS feature tags included in this IMS registration.
         * @param tags A set of Strings containing the MMTEL and RCS feature tags associated with
         *         the IMS registration. This information is used for services such as the UCE
         *         service to ascertain the complete IMS registration state to ensure the SIP
         *         PUBLISH is accurate. The format of the set of feature tags must be one feature
         *         tag key and value per entry. Each feature tag will contain the feature tag name
         *         and string value (if applicable), even if they have the same feature tag name.
         *         For example,
         *         {@code +g.3gpp.icsi-ref="urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.msg,
         *         urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.session", +g.gsma.callcomposer} must
         *         be split into three feature tag entries:
         *         {@code {+g.3gpp.icsi-ref="urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.msg",
         *         +g.3gpp.icsi-ref="urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.session",
         *         +g.gsma.callcomposer}}.
         */
        public @NonNull Builder setFeatureTags(@NonNull Set<String> tags) {
            if (tags == null) {
                throw new IllegalArgumentException("feature tag set must not be null");
            }
            mFeatureTags = new ArraySet<>(tags);
            return this;
        }

        /**
         * Set the SIP information.
         * @param details The SIP information related to this IMS registration.
         */
        public @NonNull Builder setSipDetails(@NonNull SipDetails details) {
            mSipDetails = details;
            return this;
        }

        /**
         * Set the attribute flag ATTR_REGISTRATION_TYPE_EMERGENCY.
         */
        @FlaggedApi(Flags.FLAG_EMERGENCY_REGISTRATION_STATE)
        public @NonNull Builder setFlagRegistrationTypeEmergency() {
            mAttributeFlags |= ATTR_REGISTRATION_TYPE_EMERGENCY;
            return this;
        }

        /**
         * Set the attribute flag ATTR_VIRTUAL_FOR_ANONYMOUS_EMERGENCY_CALL.
         */
        @FlaggedApi(Flags.FLAG_EMERGENCY_REGISTRATION_STATE)
        public @NonNull Builder setFlagVirtualRegistrationForEmergencyCall() {
            mAttributeFlags |= ATTR_VIRTUAL_FOR_ANONYMOUS_EMERGENCY_CALL;
            return this;
        }

        /**
         * @return A new instance created from this builder.
         */
        public @NonNull ImsRegistrationAttributes build() {
            return new ImsRegistrationAttributes(mRegistrationTech,
                    RegistrationManager.getAccessType(mRegistrationTech),
                    mAttributeFlags,
                    mFeatureTags, mSipDetails);
        }
    }

    private final int mRegistrationTech;
    private final int mTransportType;
    private final int mImsAttributeFlags;
    private final ArrayList<String> mFeatureTags;
    private final @Nullable SipDetails mSipDetails;
    /**
     * Create a new {@link ImsRegistrationAttributes} instance.
     * This is for backward compatibility.
     *
     * @param registrationTech The technology that IMS has been registered on.
     * @param transportType The transport type that IMS has been registered on.
     * @param imsAttributeFlags The attributes associated with the IMS registration.
     * @param featureTags The feature tags included in the IMS registration.
     * @hide
     */
    public ImsRegistrationAttributes(
            @ImsRegistrationImplBase.ImsRegistrationTech int registrationTech,
            @AccessNetworkConstants.TransportType int transportType,
            @ImsAttributeFlag int imsAttributeFlags,
            @Nullable Set<String> featureTags) {
        mRegistrationTech = registrationTech;
        mTransportType = transportType;
        mImsAttributeFlags = imsAttributeFlags;
        mFeatureTags = new ArrayList<>(featureTags);
        mSipDetails = null;
    }

    /**
     * Create a new {@link ImsRegistrationAttributes} instance.
     *
     * @param registrationTech The technology that IMS has been registered on.
     * @param transportType The transport type that IMS has been registered on.
     * @param imsAttributeFlags The attributes associated with the IMS registration.
     * @param featureTags The feature tags included in the IMS registration.
     * @param details The SIP information associated with the IMS registration.
     * @see Builder
     * @hide
     */
    public ImsRegistrationAttributes(
            @ImsRegistrationImplBase.ImsRegistrationTech int registrationTech,
            @AccessNetworkConstants.TransportType int transportType,
            @ImsAttributeFlag int imsAttributeFlags,
            @Nullable Set<String> featureTags,
            @Nullable SipDetails details) {
        mRegistrationTech = registrationTech;
        mTransportType = transportType;
        mImsAttributeFlags = imsAttributeFlags;
        mFeatureTags = new ArrayList<>(featureTags);
        mSipDetails = details;
    }

    /**@hide*/
    public ImsRegistrationAttributes(Parcel source) {
        mRegistrationTech = source.readInt();
        mTransportType = source.readInt();
        mImsAttributeFlags = source.readInt();
        mFeatureTags = new ArrayList<>();
        source.readList(mFeatureTags, null /*classloader*/, java.lang.String.class);
        mSipDetails = source.readParcelable(null /*loader*/,
                android.telephony.ims.SipDetails.class);
    }

    /**
     * @return The Radio Access Technology that the IMS registration has been registered over.
     * @hide
     */
    @SystemApi
    public @ImsRegistrationImplBase.ImsRegistrationTech int getRegistrationTechnology() {
        return mRegistrationTech;
    }

    /**
     * @return The access network transport type that IMS has been registered over.
     */
    public @AccessNetworkConstants.TransportType int  getTransportType() {
        return mTransportType;
    }

    /**
     * @return A bit-mask containing attributes associated with the IMS registration.
     */
    public @ImsAttributeFlag int getAttributeFlags() {
        return mImsAttributeFlags;
    }

    /**
     * Get the attribute flag ATTR_REGISTRATION_TYPE_EMERGENCY.
     * @return {@code true} if the ATTR_REGISTRATION_TYPE_EMERGENCY attribute has been set, or
     * {@code false} if it has not been set.
     */
    @FlaggedApi(Flags.FLAG_EMERGENCY_REGISTRATION_STATE)
    public boolean getFlagRegistrationTypeEmergency() {
        return (mImsAttributeFlags & ATTR_REGISTRATION_TYPE_EMERGENCY) != 0;
    }

    /**
     * Get the attribute flag ATTR_VIRTUAL_FOR_ANONYMOUS_EMERGENCY_CALL.
     * @return {@code true} if the ATTR_VIRTUAL_FOR_ANONYMOUS_EMERGENCY_CALL attribute has been set,
     * or {@code false} if it has not been set.
     */
    @FlaggedApi(Flags.FLAG_EMERGENCY_REGISTRATION_STATE)
    public boolean getFlagVirtualRegistrationForEmergencyCall() {
        return (mImsAttributeFlags & ATTR_VIRTUAL_FOR_ANONYMOUS_EMERGENCY_CALL) != 0;
    }

    /**
     * Gets the Set of feature tags associated with the current IMS registration, if the IMS
     * service supports supplying this information.
     * <p>
     * The format of the set of feature tags will be one feature tag key and value per entry and
     * will potentially contain MMTEL and RCS feature tags, depending the configuration of the IMS
     * service associated with the registration indications. Each feature tag will contain the
     * feature tag name and string value (if applicable), even if they have the same feature tag
     * name. For example, {@code +g.3gpp.icsi-ref="urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.msg,
     * urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.session", +g.gsma.callcomposer} will be split
     * into three feature tag  entries:
     * {@code {+g.3gpp.icsi-ref="urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.msg",
     * +g.3gpp.icsi-ref="urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.session",
     * +g.gsma.callcomposer}}.
     * @return The Set of feature tags associated with the current IMS registration.
     */
    public @NonNull Set<String> getFeatureTags() {
        if (mFeatureTags == null) {
            return Collections.emptySet();
        }
        return new ArraySet<>(mFeatureTags);
    }

    /**
     * @return The SIP information associated with the IMS registration.
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
        dest.writeInt(mRegistrationTech);
        dest.writeInt(mTransportType);
        dest.writeInt(mImsAttributeFlags);
        dest.writeList(mFeatureTags);
        dest.writeParcelable(mSipDetails, flags);
    }

    public static final @NonNull Creator<ImsRegistrationAttributes> CREATOR =
            new Creator<ImsRegistrationAttributes>() {
                @Override
                public ImsRegistrationAttributes createFromParcel(Parcel source) {
                    return new ImsRegistrationAttributes(source);
                }

                @Override
                public ImsRegistrationAttributes[] newArray(int size) {
                    return new ImsRegistrationAttributes[size];
                }
            };

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ImsRegistrationAttributes that = (ImsRegistrationAttributes) o;
        return mRegistrationTech == that.mRegistrationTech
                && mTransportType == that.mTransportType
                && mImsAttributeFlags == that.mImsAttributeFlags
                && Objects.equals(mFeatureTags, that.mFeatureTags)
                && Objects.equals(mSipDetails, that.mSipDetails);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mRegistrationTech, mTransportType, mImsAttributeFlags, mFeatureTags,
                mSipDetails);
    }

    @Override
    public String toString() {
        return "ImsRegistrationAttributes { transportType= " + mTransportType + ", attributeFlags="
                + mImsAttributeFlags + ", featureTags=[" + mFeatureTags + "]"
                + ",SipDetails=" + mSipDetails + "}";
    }
}
