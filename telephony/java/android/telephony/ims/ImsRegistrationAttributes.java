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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.AccessNetworkConstants;
import android.telephony.ims.stub.ImsRegistrationImplBase;
import android.util.ArraySet;

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

    /** @hide */
    // Defines the underlying radio technology type that we have registered for IMS over.
    @IntDef(prefix = "ATTR_",
            value = {
                    ATTR_EPDG_OVER_CELL_INTERNET,
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

        /**
         * Build a new instance of {@link ImsRegistrationAttributes}.
         *
         * @param registrationTech The Radio Access Technology that IMS is registered on.
         */
        public Builder(@ImsRegistrationImplBase.ImsRegistrationTech int registrationTech) {
            mRegistrationTech = registrationTech;
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
         * @return A new instance created from this builder.
         */
        public @NonNull ImsRegistrationAttributes build() {
            return new ImsRegistrationAttributes(mRegistrationTech,
                    RegistrationManager.getAccessType(mRegistrationTech),
                    getAttributeFlags(mRegistrationTech),
                    mFeatureTags);
        }

        /**
         * @return attribute flags from the registration technology.
         */
        private static int getAttributeFlags(int imsRadioTech) {
            int attributes = 0;
            if (imsRadioTech == ImsRegistrationImplBase.REGISTRATION_TECH_CROSS_SIM) {
                attributes |= ATTR_EPDG_OVER_CELL_INTERNET;
            }
            return attributes;
        }
    }

    private final int mRegistrationTech;
    private final int mTransportType;
    private final int mImsAttributeFlags;
    private final ArrayList<String> mFeatureTags;

    /**
     * Create a new {@link ImsRegistrationAttributes} instance.
     *
     * @param registrationTech The technology that IMS has been registered on.
     * @param transportType The transport type that IMS has been registered on.
     * @param imsAttributeFlags The attributes associated with the IMS registration.
     * @param featureTags The feature tags included in the IMS registration.
     * @see Builder
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
    }

    /**@hide*/
    public ImsRegistrationAttributes(Parcel source) {
        mRegistrationTech = source.readInt();
        mTransportType = source.readInt();
        mImsAttributeFlags = source.readInt();
        mFeatureTags = new ArrayList<>();
        source.readList(mFeatureTags, null /*classloader*/);
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
                && Objects.equals(mFeatureTags, that.mFeatureTags);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mRegistrationTech, mTransportType, mImsAttributeFlags, mFeatureTags);
    }

    @Override
    public String toString() {
        return "ImsRegistrationAttributes { transportType= " + mTransportType + ", attributeFlags="
                + mImsAttributeFlags + ", featureTags=[" + mFeatureTags + "]}";
    }
}
