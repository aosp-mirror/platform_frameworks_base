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

package com.android.ims;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.ims.ImsService;
import android.telephony.ims.aidl.IImsConfig;
import android.telephony.ims.aidl.IImsRegistration;
import android.telephony.ims.aidl.ISipTransport;
import android.telephony.ims.feature.ImsFeature;

import java.util.Objects;

/**
 * Contains an IBinder linking to the appropriate ImsFeature as well as the associated
 * interfaces.
 * @hide
 */
public final class ImsFeatureContainer implements Parcelable {
    /**
     * ImsFeature that is being tracked.
     */
    public final IBinder imsFeature;

    /**
     * IImsConfig interface that should be associated with the ImsFeature.
     */
    public final android.telephony.ims.aidl.IImsConfig imsConfig;

    /**
     * IImsRegistration interface that should be associated with this ImsFeature.
     */
    public final IImsRegistration imsRegistration;

    /**
     * An optional interface containing the SIP transport implementation from the ImsService.
     */
    public final ISipTransport sipTransport;

    /**
     * State of the feature that is being tracked.
     */
    private @ImsFeature.ImsState int mState = ImsFeature.STATE_UNAVAILABLE;

    /**
     * Capabilities of this ImsService.
     */
    private @ImsService.ImsServiceCapability long mCapabilities;
    /**
     * Contains the ImsFeature IBinder as well as the ImsService interfaces associated with
     * that feature.
     * @param iFace IBinder connection to the ImsFeature.
     * @param iConfig IImsConfig interface associated with the ImsFeature.
     * @param iReg IImsRegistration interface associated with the ImsFeature
     * @param initialCaps The initial capabilities that the ImsService supports.
     */
    public ImsFeatureContainer(@NonNull IBinder iFace, @NonNull IImsConfig iConfig,
            @NonNull IImsRegistration iReg, @Nullable ISipTransport transport, long initialCaps) {
        imsFeature = iFace;
        imsConfig = iConfig;
        imsRegistration = iReg;
        sipTransport = transport;
        mCapabilities = initialCaps;
    }

    /**
     * Create an ImsFeatureContainer from a Parcel.
     */
    private ImsFeatureContainer(Parcel in) {
        imsFeature = in.readStrongBinder();
        imsConfig = IImsConfig.Stub.asInterface(in.readStrongBinder());
        imsRegistration = IImsRegistration.Stub.asInterface(in.readStrongBinder());
        sipTransport = ISipTransport.Stub.asInterface(in.readStrongBinder());
        mState = in.readInt();
        mCapabilities = in.readLong();
    }

    /**
     * @return the capabilties that are associated with the ImsService that this ImsFeature
     * belongs to.
     */
    public @ImsService.ImsServiceCapability long getCapabilities() {
        return mCapabilities;
    }

    /**
     * Update the capabilities that are associated with the ImsService that this ImsFeature
     * belongs to.
     */
    public void setCapabilities(@ImsService.ImsServiceCapability long caps) {
        mCapabilities = caps;
    }

    /**
     * @return The state of the ImsFeature.
     */
    public @ImsFeature.ImsState int getState() {
        return mState;
    }

    /**
     * Set the state that is associated with the ImsService that this ImsFeature
     * belongs to.
     */
    public void setState(@ImsFeature.ImsState int state) {
        mState = state;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ImsFeatureContainer that = (ImsFeatureContainer) o;
        return imsFeature.equals(that.imsFeature) &&
                imsConfig.equals(that.imsConfig) &&
                imsRegistration.equals(that.imsRegistration) &&
                sipTransport.equals(that.sipTransport) &&
                mState == that.getState() &&
                mCapabilities == that.getCapabilities();
    }

    @Override
    public int hashCode() {
        return Objects.hash(imsFeature, imsConfig, imsRegistration, sipTransport, mState,
                mCapabilities);
    }

    @Override
    public String toString() {
        return "FeatureContainer{" +
                "imsFeature=" + imsFeature +
                ", imsConfig=" + imsConfig +
                ", imsRegistration=" + imsRegistration +
                ", sipTransport=" + sipTransport +
                ", state=" + ImsFeature.STATE_LOG_MAP.get(mState) +
                ", capabilities = " + ImsService.getCapabilitiesString(mCapabilities) +
                '}';
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeStrongBinder(imsFeature);
        dest.writeStrongInterface(imsConfig);
        dest.writeStrongInterface(imsRegistration);
        dest.writeStrongInterface(sipTransport);
        dest.writeInt(mState);
        dest.writeLong(mCapabilities);
    }


    public static final Creator<ImsFeatureContainer> CREATOR = new Creator<ImsFeatureContainer>() {
        @Override
        public ImsFeatureContainer createFromParcel(Parcel source) {
            return new ImsFeatureContainer(source);
        }

        @Override
        public ImsFeatureContainer[] newArray(int size) {
            return new ImsFeatureContainer[size];
        }
    };
}
