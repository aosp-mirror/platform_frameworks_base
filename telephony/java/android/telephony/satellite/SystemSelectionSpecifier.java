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

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_SATELLITE_SYSTEM_APIS)
public final class SystemSelectionSpecifier implements Parcelable {

    /** Network plmn associated with channel information. */
    @NonNull private String mMccMnc;

    /** The frequency bands to scan. Maximum length of the vector is 8. */
    @NonNull private IntArray mBands;

    /**
     * The radio channels to scan as defined in 3GPP TS 25.101 and 36.101.
     * Maximum length of the vector is 32.
     */
    @NonNull private IntArray mEarfcns;

    /* The list of satellites configured for the current location */
    @Nullable
    private SatelliteInfo[] mSatelliteInfos;

    /* The list of tag IDs associated with the current location */
    @Nullable private IntArray mTagIds;

    /**
     * @hide
     */
    public SystemSelectionSpecifier(@NonNull String mccmnc, @NonNull IntArray bands,
            @NonNull IntArray earfcns, @Nullable SatelliteInfo[] satelliteInfos,
            @Nullable IntArray tagIds) {
        mMccMnc = mccmnc;
        mBands = bands;
        mEarfcns = earfcns;
        mSatelliteInfos = satelliteInfos;
        mTagIds = tagIds;
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

        if (mBands != null && mBands.size() > 0) {
            out.writeInt(mBands.size());
            for (int i = 0; i < mBands.size(); i++) {
                out.writeInt(mBands.get(i));
            }
        } else {
            out.writeInt(0);
        }

        if (mEarfcns != null && mEarfcns.size() > 0) {
            out.writeInt(mEarfcns.size());
            for (int i = 0; i < mEarfcns.size(); i++) {
                out.writeInt(mEarfcns.get(i));
            }
        } else {
            out.writeInt(0);
        }

        out.writeTypedArray(mSatelliteInfos, flags);

        if (mTagIds != null) {
            out.writeInt(mTagIds.size());
            for (int i = 0; i < mTagIds.size(); i++) {
                out.writeInt(mTagIds.get(i));
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
        if (mBands != null && mBands.size() > 0) {
            for (int i = 0; i < mBands.size(); i++) {
                sb.append(mBands.get(i));
                sb.append(",");
            }
        } else {
            sb.append("none,");
        }

        sb.append("earfcs:");
        if (mEarfcns != null && mEarfcns.size() > 0) {
            for (int i = 0; i < mEarfcns.size(); i++) {
                sb.append(mEarfcns.get(i));
                sb.append(",");
            }
        } else {
            sb.append("none");
        }

        sb.append("mSatelliteInfos:");
        if (mSatelliteInfos != null && mSatelliteInfos.length > 0) {
            for (SatelliteInfo satelliteInfo : mSatelliteInfos) {
                sb.append(satelliteInfo);
                sb.append(",");
            }
        } else {
            sb.append("none");
        }

        sb.append("mTagIds:");
        if (mTagIds != null && mTagIds.size() > 0) {
            for (int i = 0; i < mTagIds.size(); i++) {
                sb.append(mTagIds.get(i));
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
                && Objects.equals(mBands, that.mBands)
                && Objects.equals(mEarfcns, that.mEarfcns)
                && (mSatelliteInfos == null ? that.mSatelliteInfos == null : Arrays.equals(
                mSatelliteInfos, that.mSatelliteInfos))
                && Objects.equals(mTagIds, that.mTagIds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mMccMnc, mBands, mEarfcns);
    }

    /** Return network plmn associated with channel information. */
    @NonNull public String getMccMnc() {
        return mMccMnc;
    }

    /**
     * Return the frequency bands to scan.
     * Maximum length of the vector is 8.
     */
    @NonNull public int[] getBands() {
        return mBands.toArray();
    }

    /**
     * Return the radio channels to scan as defined in 3GPP TS 25.101 and 36.101.
     * Maximum length of the vector is 32.
     */
    @NonNull public int[] getEarfcns() {
        return mEarfcns.toArray();
    }

    /** Return the list of satellites configured for the current location. */
    @NonNull
    public List<SatelliteInfo> getSatelliteInfos() {
        return Arrays.stream(mSatelliteInfos).toList();
    }

    /**
     * Return the list of tag IDs associated with the current location
     * Tag Ids are generic IDs an OEM can configure. Each tag ID can map to a region which can be
     * used by OEM to identify proprietary configuration for that region.
     */
    @NonNull
    public int[] getTagIds() {
        return mTagIds.toArray();
    }

    private void readFromParcel(Parcel in) {
        mMccMnc = in.readString();

        mBands = new IntArray();
        int numBands = in.readInt();
        if (numBands > 0) {
            for (int i = 0; i < numBands; i++) {
                mBands.add(in.readInt());
            }
        }

        mEarfcns = new IntArray();
        int numEarfcns = in.readInt();
        if (numEarfcns > 0) {
            for (int i = 0; i < numEarfcns; i++) {
                mEarfcns.add(in.readInt());
            }
        }

        mSatelliteInfos = in.createTypedArray(SatelliteInfo.CREATOR);

        int numTagIds = in.readInt();
        if (numTagIds > 0) {
            for (int i = 0; i < numTagIds; i++) {
                mTagIds.add(in.readInt());
            }
        }
    }
}
