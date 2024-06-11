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

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.IntArray;

import java.util.Objects;

/**
 * @hide
 */
public final class SystemSelectionSpecifier implements Parcelable {

    /** Network plmn associated with channel information. */
    @NonNull private String mMccMnc;

    /** The frequency bands to scan. Maximum length of the vector is 8. */
    @NonNull private IntArray mBands;

    /**
     * The radio channels to scan as defined in 3GPP TS 25.101 and 36.101.
     * Maximum length of the vector is 32.
     */
    @NonNull private IntArray mEarfcs;

    /**
     * @hide
     */
    public SystemSelectionSpecifier(@NonNull String mccmnc, @NonNull IntArray bands,
            @NonNull IntArray earfcs) {
        mMccMnc = mccmnc;
        mBands = bands;
        mEarfcs = earfcs;
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

        if (mEarfcs != null && mEarfcs.size() > 0) {
            out.writeInt(mEarfcs.size());
            for (int i = 0; i < mEarfcs.size(); i++) {
                out.writeInt(mEarfcs.get(i));
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
        if (mEarfcs != null && mEarfcs.size() > 0) {
            for (int i = 0; i < mEarfcs.size(); i++) {
                sb.append(mEarfcs.get(i));
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
                && Objects.equals(mEarfcs, that.mEarfcs);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mMccMnc, mBands, mEarfcs);
    }

    @NonNull public String getMccMnc() {
        return mMccMnc;
    }

    @NonNull public IntArray getBands() {
        return mBands;
    }

    @NonNull public IntArray getEarfcs() {
        return mEarfcs;
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

        mEarfcs = new IntArray();
        int numEarfcs = in.readInt();
        if (numEarfcs > 0) {
            for (int i = 0; i < numEarfcs; i++) {
                mEarfcs.add(in.readInt());
            }
        }
    }
}
