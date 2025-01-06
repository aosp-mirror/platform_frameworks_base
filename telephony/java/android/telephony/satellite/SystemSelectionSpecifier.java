/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.IntArray;

import com.android.internal.telephony.flags.Flags;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * This class defines the information that modem will use to decide which satellites it should
 * attach to and how it should scan for the signal from the chosen satellites.
 * Moreover, it also provides the customized information {@code mTagIds} to provide the flexibility
 * for OEMs and vendors to define more info that they need for communicating with satellites like
 * how modem should control the power to meet the requirement of local authorities.
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_SATELLITE_SYSTEM_APIS)
public final class SystemSelectionSpecifier implements Parcelable {

    /** Network plmn associated with channel information. */
    @NonNull private String mMccMnc;

    /** The frequency bands to scan. Maximum length of the vector is 8. */
    @NonNull private int[] mBands;

    /**
     * The radio channels to scan as defined in 3GPP TS 25.101 and 36.101.
     * Maximum length of the vector is 32.
     */
    @NonNull private int[] mEarfcns;

    /* The list of satellites configured for the current location */
    @Nullable
    private List<SatelliteInfo> mSatelliteInfos;

    /* The list of tag IDs associated with the current location */
    @Nullable private int[] mTagIds;

    /**
     * @hide
     */
    public SystemSelectionSpecifier(@NonNull String mccmnc, @NonNull IntArray bands,
            @NonNull IntArray earfcns, @Nullable SatelliteInfo[] satelliteInfos,
            @Nullable IntArray tagIds) {
        mMccMnc = mccmnc;
        mBands = bands.toArray();
        mEarfcns = earfcns.toArray();
        mSatelliteInfos = Arrays.stream(satelliteInfos).toList();
        mTagIds = tagIds.toArray();
    }

    /**
     * @hide
     */
    public SystemSelectionSpecifier(Builder builder) {
        mMccMnc = builder.mMccMnc;
        mBands = builder.mBands;
        mEarfcns = builder.mEarfcns;
        mSatelliteInfos = builder.mSatelliteInfos;
        mTagIds = builder.mTagIds;
    }

    /**
     * Builder class for constructing SystemSelectionSpecifier objects
     */
    public static final class Builder {
        @NonNull private String mMccMnc;
        @NonNull private int[] mBands;
        @NonNull private int[] mEarfcns;
        private List<SatelliteInfo> mSatelliteInfos;
        @Nullable private int[] mTagIds;

        /** Set network plmn associated with the channel and return the Builder class. */
        @NonNull
        public Builder setMccMnc(@NonNull String mccMnc) {
            this.mMccMnc = mccMnc;
            return this;
        }

        /**
         * Set frequency bands to scan and return the Builder class.
         * Maximum length of the vector is 8.
         */
        @NonNull
        public Builder setBands(@NonNull int[] bands) {
            this.mBands = bands;
            return this;
        }

        /**
         * Set radio channels to scan as defined in 3GPP TS 25.101 and 36.101
         * and returns the Builder class.
         * Maximum length if the vector is 32.
         */
        @NonNull
        public Builder setEarfcns(@NonNull int[] earfcns) {
            this.mEarfcns = earfcns;
            return this;
        }

        /**
         * Set list of satellites configured for the current location and return the Builder class.
         */
        @NonNull
        public Builder setSatelliteInfos(@NonNull List<SatelliteInfo> satelliteInfos) {
            this.mSatelliteInfos = satelliteInfos;
            return this;
        }

        /**
         * Set list of tag IDs associated with the current location and return the Builder class.
         */
        @NonNull
        public Builder setTagIds(@NonNull int[] tagIds) {
            this.mTagIds = tagIds;
            return this;
        }

        /** Return SystemSelectionSpecifier object */
        @NonNull
        public SystemSelectionSpecifier build() {
            return new SystemSelectionSpecifier(this);
        }
    }

    private SystemSelectionSpecifier(Parcel in) {
        readFromParcel(in);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        mMccMnc = TextUtils.emptyIfNull(mMccMnc);
        out.writeString8(mMccMnc);

        if (mBands != null && mBands.length > 0) {
            out.writeInt(mBands.length);
            for (int i = 0; i < mBands.length; i++) {
                out.writeInt(mBands[i]);
            }
        } else {
            out.writeInt(0);
        }

        if (mEarfcns != null && mEarfcns.length > 0) {
            out.writeInt(mEarfcns.length);
            for (int i = 0; i < mEarfcns.length; i++) {
                out.writeInt(mEarfcns[i]);
            }
        } else {
            out.writeInt(0);
        }

        out.writeTypedArray(mSatelliteInfos.toArray(new SatelliteInfo[0]), flags);

        if (mTagIds != null) {
            out.writeInt(mTagIds.length);
            for (int i = 0; i < mTagIds.length; i++) {
                out.writeInt(mTagIds[i]);
            }
        } else {
            out.writeInt(0);
        }
    }

    @NonNull public static final Parcelable.Creator<SystemSelectionSpecifier> CREATOR =
            new Creator<>() {
                @Override
                public SystemSelectionSpecifier createFromParcel(Parcel in) {
                    return new SystemSelectionSpecifier(in);
                }

                @Override
                public SystemSelectionSpecifier[] newArray(int size) {
                    return new SystemSelectionSpecifier[size];
                }
            };

    @Override
    @NonNull public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("mccmnc:");
        sb.append(mMccMnc);
        sb.append(",");

        sb.append("bands:");
        if (mBands != null && mBands.length > 0) {
            for (int i = 0; i < mBands.length; i++) {
                sb.append(mBands[i]);
                sb.append(",");
            }
        } else {
            sb.append("none,");
        }

        sb.append("earfcs:");
        if (mEarfcns != null && mEarfcns.length > 0) {
            for (int i = 0; i < mEarfcns.length; i++) {
                sb.append(mEarfcns[i]);
                sb.append(",");
            }
        } else {
            sb.append("none");
        }

        sb.append("mSatelliteInfos:");
        if (mSatelliteInfos != null && mSatelliteInfos.size() > 0) {
            for (SatelliteInfo satelliteInfo : mSatelliteInfos) {
                sb.append(satelliteInfo);
                sb.append(",");
            }
        } else {
            sb.append("none");
        }

        sb.append("mTagIds:");
        if (mTagIds != null && mTagIds.length > 0) {
            for (int i = 0; i < mTagIds.length; i++) {
                sb.append(mTagIds[i]);
                sb.append(",");
            }
        } else {
            sb.append("none");
        }

        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SystemSelectionSpecifier that = (SystemSelectionSpecifier) o;
        return Objects.equals(mMccMnc, that.mMccMnc)
                && Arrays.equals(mBands, that.mBands)
                && Arrays.equals(mEarfcns, that.mEarfcns)
                && (mSatelliteInfos == null ? that.mSatelliteInfos == null :
                mSatelliteInfos.equals(that.mSatelliteInfos))
                && Arrays.equals(mTagIds, that.mTagIds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mMccMnc, Arrays.hashCode(mBands), Arrays.hashCode(mEarfcns));
    }

    /** Return network plmn associated with channel information. */
    @NonNull public String getMccMnc() {
        return mMccMnc;
    }

    /**
     * Return the frequency bands to scan.
     * Maximum length of the vector is 8.
     * Refer specification 3GPP TS 36.101 for detailed information on frequency bands.
     */
    @NonNull public int[] getBands() {
        return mBands;
    }

    /**
     * Return the radio channels to scan as defined in 3GPP TS 25.101 and 36.101.
     * Maximum length of the vector is 32.
     */
    @NonNull public int[] getEarfcns() {
        return mEarfcns;
    }

    /** Return the list of satellites configured for the current location. */
    @NonNull
    public List<SatelliteInfo> getSatelliteInfos() {
        return mSatelliteInfos;
    }

    /**
     * Return the list of tag IDs associated with the current location
     * Tag Ids are generic IDs an OEM can configure. Each tag ID can map to a region which can be
     * used by OEM to identify proprietary configuration for that region.
     */
    @NonNull
    public int[] getTagIds() {
        return mTagIds;
    }

    private void readFromParcel(Parcel in) {
        mMccMnc = in.readString();

        int numBands = in.readInt();
        mBands = new int[numBands];
        if (numBands > 0) {
            for (int i = 0; i < numBands; i++) {
                mBands[i] = in.readInt();
            }
        }

        int numEarfcns = in.readInt();
        mEarfcns = new int[numEarfcns];
        if (numEarfcns > 0) {
            for (int i = 0; i < numEarfcns; i++) {
                mEarfcns[i] = in.readInt();
            }
        }

        mSatelliteInfos = new ArrayList<>();
        in.readList(mSatelliteInfos, SatelliteInfo.class.getClassLoader(), SatelliteInfo.class);

        int numTagIds = in.readInt();
        mTagIds = new int[numTagIds];
        if (numTagIds > 0) {
            for (int i = 0; i < numTagIds; i++) {
                mTagIds[i] = in.readInt();
            }
        }
    }
}
