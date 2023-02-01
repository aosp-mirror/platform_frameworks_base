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

package android.telephony.satellite;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.HashSet;
import java.util.Set;

/**
 * @hide
 */
public final class SatelliteCapabilities implements Parcelable {
    /**
     * List of technologies supported by the satellite modem.
     */
    private Set<Integer> mSupportedRadioTechnologies;

    /**
     * Whether satellite mode is always on (this to indicate power impact of keeping it on is
     * very minimal).
     */
    private boolean mIsAlwaysOn;

    /**
     * Whether UE needs to point to a satellite to send and receive data.
     */
    private boolean mNeedsPointingToSatellite;

    /**
     * List of features supported by the Satellite modem.
     */
    private Set<Integer> mSupportedFeatures;

    /**
     * Whether UE needs a separate SIM profile to communicate with the Satellite network.
     */
    private boolean mNeedsSeparateSimProfile;

    /**
     * @hide
     */
    public SatelliteCapabilities(Set<Integer> supportedRadioTechnologies, boolean isAlwaysOn,
            boolean needsPointingToSatellite, Set<Integer> supportedFeatures,
            boolean needsSeparateSimProfile) {
        mSupportedRadioTechnologies = supportedRadioTechnologies;
        mIsAlwaysOn = isAlwaysOn;
        mNeedsPointingToSatellite = needsPointingToSatellite;
        mSupportedFeatures = supportedFeatures;
        mNeedsSeparateSimProfile = needsSeparateSimProfile;
    }

    private SatelliteCapabilities(Parcel in) {
        readFromParcel(in);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        if (mSupportedRadioTechnologies != null && !mSupportedRadioTechnologies.isEmpty()) {
            out.writeInt(mSupportedRadioTechnologies.size());
            for (int technology : mSupportedRadioTechnologies) {
                out.writeInt(technology);
            }
        } else {
            out.writeInt(0);
        }

        out.writeBoolean(mIsAlwaysOn);
        out.writeBoolean(mNeedsPointingToSatellite);

        if (mSupportedFeatures != null && !mSupportedFeatures.isEmpty()) {
            out.writeInt(mSupportedFeatures.size());
            for (int feature : mSupportedFeatures) {
                out.writeInt(feature);
            }
        } else {
            out.writeInt(0);
        }

        out.writeBoolean(mNeedsSeparateSimProfile);
    }

    public static final @android.annotation.NonNull Creator<SatelliteCapabilities> CREATOR =
            new Creator<SatelliteCapabilities>() {
                @Override
                public SatelliteCapabilities createFromParcel(Parcel in) {
                    return new SatelliteCapabilities(in);
                }

                @Override
                public SatelliteCapabilities[] newArray(int size) {
                    return new SatelliteCapabilities[size];
                }
            };

    @NonNull
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        sb.append("SupportedRadioTechnology:");
        if (mSupportedRadioTechnologies != null && !mSupportedRadioTechnologies.isEmpty()) {
            for (int technology : mSupportedRadioTechnologies) {
                sb.append(technology);
                sb.append(",");
            }
        } else {
            sb.append("none,");
        }

        sb.append("SupportedFeatures:");
        if (mSupportedFeatures != null && !mSupportedFeatures.isEmpty()) {
            for (int feature : mSupportedFeatures) {
                sb.append(feature);
                sb.append(",");
            }
        } else {
            sb.append("none,");
        }

        sb.append("isAlwaysOn:");
        sb.append(mIsAlwaysOn);
        sb.append(",");

        sb.append("needsPointingToSatellite:");
        sb.append(mNeedsPointingToSatellite);
        sb.append(",");

        sb.append("needsSeparateSimProfile:");
        sb.append(mNeedsSeparateSimProfile);
        return sb.toString();
    }

    @NonNull
    public Set<Integer> getSupportedRadioTechnologies() {
        return mSupportedRadioTechnologies;
    }

    public boolean isAlwaysOn() {
        return mIsAlwaysOn;
    }

    /** Get function for mNeedsPointingToSatellite */
    public boolean needsPointingToSatellite() {
        return mNeedsPointingToSatellite;
    }

    @NonNull
    public Set<Integer> getSupportedFeatures() {
        return mSupportedFeatures;
    }

    /** Get function for mNeedsSeparateSimProfile */
    public boolean needsSeparateSimProfile() {
        return mNeedsSeparateSimProfile;
    }

    private void readFromParcel(Parcel in) {
        mSupportedRadioTechnologies = new HashSet<>();
        int numSupportedRadioTechnologies = in.readInt();
        if (numSupportedRadioTechnologies > 0) {
            for (int i = 0; i < numSupportedRadioTechnologies; i++) {
                mSupportedRadioTechnologies.add(in.readInt());
            }
        }

        mIsAlwaysOn = in.readBoolean();
        mNeedsPointingToSatellite = in.readBoolean();

        mSupportedFeatures = new HashSet<>();
        int numSupportedFeatures = in.readInt();
        if (numSupportedFeatures > 0) {
            for (int i = 0; i < numSupportedFeatures; i++) {
                mSupportedFeatures.add(in.readInt());
            }
        }

        mNeedsSeparateSimProfile = in.readBoolean();
    }
}
