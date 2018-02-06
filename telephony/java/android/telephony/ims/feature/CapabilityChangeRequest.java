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
 * limitations under the License
 */

package android.telephony.ims.feature;

import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.ims.stub.ImsRegistrationImplBase;
import android.util.ArraySet;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Request to send to IMS provider, which will try to enable/disable capabilities that are added to
 * the request.
 * {@hide}
 */
@SystemApi
public final class CapabilityChangeRequest implements Parcelable {

    /**
     * Contains a feature capability, defined as
     * {@link MmTelFeature.MmTelCapabilities#CAPABILITY_TYPE_VOICE},
     * {@link MmTelFeature.MmTelCapabilities#CAPABILITY_TYPE_VIDEO},
     * {@link MmTelFeature.MmTelCapabilities#CAPABILITY_TYPE_UT}, or
     * {@link MmTelFeature.MmTelCapabilities#CAPABILITY_TYPE_SMS},
     * along with an associated technology, defined as
     * {@link ImsRegistrationImplBase#REGISTRATION_TECH_LTE} or
     * {@link ImsRegistrationImplBase#REGISTRATION_TECH_IWLAN}
     */
    public static class CapabilityPair {
        private final int mCapability;
        private final int radioTech;

        public CapabilityPair(@MmTelFeature.MmTelCapabilities.MmTelCapability int capability,
                @ImsRegistrationImplBase.ImsRegistrationTech int radioTech) {
            this.mCapability = capability;
            this.radioTech = radioTech;
        }

        /**
         * @hide
         */
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CapabilityPair)) return false;

            CapabilityPair that = (CapabilityPair) o;

            if (getCapability() != that.getCapability()) return false;
            return getRadioTech() == that.getRadioTech();
        }

        /**
         * @hide
         */
        @Override
        public int hashCode() {
            int result = getCapability();
            result = 31 * result + getRadioTech();
            return result;
        }

        /**
         * @return The stored capability, defined as
         * {@link MmTelFeature.MmTelCapabilities#CAPABILITY_TYPE_VOICE},
         * {@link MmTelFeature.MmTelCapabilities#CAPABILITY_TYPE_VIDEO},
         * {@link MmTelFeature.MmTelCapabilities#CAPABILITY_TYPE_UT}, or
         * {@link MmTelFeature.MmTelCapabilities#CAPABILITY_TYPE_SMS}
         */
        public @MmTelFeature.MmTelCapabilities.MmTelCapability int getCapability() {
            return mCapability;
        }

        /**
         * @return the stored radio technology, defined as
         * {@link ImsRegistrationImplBase#REGISTRATION_TECH_LTE} or
         * {@link ImsRegistrationImplBase#REGISTRATION_TECH_IWLAN}
         */
        public @ImsRegistrationImplBase.ImsRegistrationTech int getRadioTech() {
            return radioTech;
        }
    }

    // Pair contains <radio tech, mCapability>
    private final Set<CapabilityPair> mCapabilitiesToEnable;
    // Pair contains <radio tech, mCapability>
    private final Set<CapabilityPair> mCapabilitiesToDisable;

    /** @hide */
    public CapabilityChangeRequest() {
        mCapabilitiesToEnable = new ArraySet<>();
        mCapabilitiesToDisable = new ArraySet<>();
    }

    /**
     * Add one or many capabilities to the request to be enabled.
     *
     * @param capabilities A bitfield of capabilities to enable, valid values are defined in
     *   {@link MmTelFeature.MmTelCapabilities.MmTelCapability}.
     * @param radioTech  the radio tech that these capabilities should be enabled for, valid
     *   values are in {@link ImsRegistrationImplBase.ImsRegistrationTech}.
     */
    public void addCapabilitiesToEnableForTech(
            @MmTelFeature.MmTelCapabilities.MmTelCapability int capabilities,
            @ImsRegistrationImplBase.ImsRegistrationTech int radioTech) {
        addAllCapabilities(mCapabilitiesToEnable, capabilities, radioTech);
    }

    /**
     * Add one or many capabilities to the request to be disabled.
     * @param capabilities A bitfield of capabilities to diable, valid values are defined in
     *   {@link MmTelFeature.MmTelCapabilities.MmTelCapability}.
     * @param radioTech the radio tech that these capabilities should be disabled for, valid
     *   values are in {@link ImsRegistrationImplBase.ImsRegistrationTech}.
     */
    public void addCapabilitiesToDisableForTech(
            @MmTelFeature.MmTelCapabilities.MmTelCapability int capabilities,
            @ImsRegistrationImplBase.ImsRegistrationTech int radioTech) {
        addAllCapabilities(mCapabilitiesToDisable, capabilities, radioTech);
    }

    /**
     * @return a {@link List} of {@link CapabilityPair}s that are requesting to be enabled.
     */
    public List<CapabilityPair> getCapabilitiesToEnable() {
        return new ArrayList<>(mCapabilitiesToEnable);
    }

    /**
     * @return a {@link List} of {@link CapabilityPair}s that are requesting to be disabled.
     */
    public List<CapabilityPair> getCapabilitiesToDisable() {
        return new ArrayList<>(mCapabilitiesToDisable);
    }

    // Iterate through capabilities bitfield and add each one as a pair associated with the radio
    // technology
    private void addAllCapabilities(Set<CapabilityPair> set, int capabilities, int tech) {
        long highestCapability = Long.highestOneBit(capabilities);
        for (int i = 1; i <= highestCapability; i *= 2) {
            if ((i & capabilities) > 0) {
                set.add(new CapabilityPair(/*capability*/ i, /*radioTech*/ tech));
            }
        }
    }

    /**
     * @hide
     */
    protected CapabilityChangeRequest(Parcel in) {
        int enableSize = in.readInt();
        mCapabilitiesToEnable = new ArraySet<>(enableSize);
        for (int i = 0; i < enableSize; i++) {
            mCapabilitiesToEnable.add(new CapabilityPair(/*capability*/ in.readInt(),
                    /*radioTech*/ in.readInt()));
        }
        int disableSize = in.readInt();
        mCapabilitiesToDisable = new ArraySet<>(disableSize);
        for (int i = 0; i < disableSize; i++) {
            mCapabilitiesToDisable.add(new CapabilityPair(/*capability*/ in.readInt(),
                    /*radioTech*/ in.readInt()));
        }
    }

    public static final Creator<CapabilityChangeRequest> CREATOR =
            new Creator<CapabilityChangeRequest>() {
                @Override
                public CapabilityChangeRequest createFromParcel(Parcel in) {
                    return new CapabilityChangeRequest(in);
                }

                @Override
                public CapabilityChangeRequest[] newArray(int size) {
                    return new CapabilityChangeRequest[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mCapabilitiesToEnable.size());
        for (CapabilityPair pair : mCapabilitiesToEnable) {
            dest.writeInt(pair.getCapability());
            dest.writeInt(pair.getRadioTech());
        }
        dest.writeInt(mCapabilitiesToDisable.size());
        for (CapabilityPair pair : mCapabilitiesToDisable) {
            dest.writeInt(pair.getCapability());
            dest.writeInt(pair.getRadioTech());
        }
    }

    /**
     * @hide
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CapabilityChangeRequest)) return false;

        CapabilityChangeRequest
                that = (CapabilityChangeRequest) o;

        if (!mCapabilitiesToEnable.equals(that.mCapabilitiesToEnable)) return false;
        return mCapabilitiesToDisable.equals(that.mCapabilitiesToDisable);
    }

    /**
     * @hide
     */
    @Override
    public int hashCode() {
        int result = mCapabilitiesToEnable.hashCode();
        result = 31 * result + mCapabilitiesToDisable.hashCode();
        return result;
    }
}
