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

package android.telephony;

import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.telephony.Rlog;

import java.util.ArrayList;
import java.util.List;

/**
 * CarrierInfo that is used to represent the carrier lock information details.
 *
 * @hide
 */
public final class CarrierInfo implements Parcelable {

    /**
     * Used to create a {@link CarrierInfo} from a {@link Parcel}.
     *
     * @hide
     */
    public static final @android.annotation.NonNull Creator<CarrierInfo> CREATOR =
            new Creator<CarrierInfo>() {
                /**
                 * Create a new instance of the Parcelable class, instantiating it
                 * from the given Parcel whose data had previously been written by
                 * {@link Parcelable#writeToParcel Parcelable.writeToParcel()}.
                 *
                 * @param source The Parcel to read the object's data from.
                 * @return Returns a new instance of the Parcelable class.
                 */
                @Override
                public CarrierInfo createFromParcel(Parcel source) {
                    return new CarrierInfo(source);
                }

                /**
                 * Create a new array of the Parcelable class.
                 *
                 * @param size Size of the array.
                 * @return Returns an array of the Parcelable class, with every entry
                 * initialized to null.
                 */
                @Override
                public CarrierInfo[] newArray(int size) {
                    return new CarrierInfo[size];
                }

            };
    @NonNull
    private String mMcc;
    @NonNull
    private String mMnc;
    @Nullable
    private String mSpn;
    @Nullable
    private String mGid1;
    @Nullable
    private String mGid2;
    @Nullable
    private String mImsiPrefix;
    /** Ehplmn is String combination of MCC,MNC */
    @Nullable
    private List<String> mEhplmn;
    @Nullable
    private String mIccid;
    @Nullable
    private String mImpi;

    /** @hide */
    @NonNull
    public String getMcc() {
        return mMcc;
    }

    /** @hide */
    @NonNull
    public String getMnc() {
        return mMnc;
    }

    /** @hide */
    @Nullable
    public String getSpn() {
        return mSpn;
    }

    /** @hide */
    @Nullable
    public String getGid1() {
        return mGid1;
    }

    /** @hide */
    @Nullable
    public String getGid2() {
        return mGid2;
    }

    /** @hide */
    @Nullable
    public String getImsiPrefix() {
        return mImsiPrefix;
    }

    /** @hide */
    @Nullable
    public String getIccid() {
        return mIccid;
    }

    /** @hide */
    @Nullable
    public String getImpi() {
        return mImpi;
    }

    /**
     * Returns the list of EHPLMN.
     *
     * @return List of String that represent Ehplmn.
     * @hide
     */
    @NonNull
    public List<String> getEhplmn() {
        return mEhplmn;
    }

    /** @hide */
    public CarrierInfo(@NonNull String mcc, @NonNull String mnc, @Nullable String spn,
            @Nullable String gid1, @Nullable String gid2, @Nullable String imsi,
            @Nullable String iccid, @Nullable String impi, @Nullable List<String> plmnArrayList) {
        mMcc = mcc;
        mMnc = mnc;
        mSpn = spn;
        mGid1 = gid1;
        mGid2 = gid2;
        mImsiPrefix = imsi;
        mIccid = iccid;
        mImpi = impi;
        mEhplmn = plmnArrayList;
    }

    /**
     * Describe the kinds of special objects contained in this Parcelable
     * instance's marshaled representation. For example, if the object will
     * include a file descriptor in the output of {@link #writeToParcel(Parcel, int)},
     * the return value of this method must include the
     * {@link #CONTENTS_FILE_DESCRIPTOR} bit.
     *
     * @return a bitmask indicating the set of special object types marshaled
     * by this Parcelable object instance.
     * @hide
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Flatten this object in to a Parcel.
     *
     * @param dest  The Parcel in which the object should be written.
     * @param flags Additional flags about how the object should be written.
     *              May be 0 or {@link #PARCELABLE_WRITE_RETURN_VALUE}.
     * @hide
     */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString8(mMcc);
        dest.writeString8(mMnc);
        dest.writeString8(mSpn);
        dest.writeString8(mGid1);
        dest.writeString8(mGid2);
        dest.writeString8(mImsiPrefix);
        dest.writeString8(mIccid);
        dest.writeString8(mImpi);
        dest.writeStringList(mEhplmn);
    }

    /** @hide */
    public CarrierInfo(Parcel in) {
        mEhplmn = new ArrayList<String>();
        mMcc = in.readString8();
        mMnc = in.readString8();
        mSpn = in.readString8();
        mGid1 = in.readString8();
        mGid2 = in.readString8();
        mImsiPrefix = in.readString8();
        mIccid = in.readString8();
        mImpi = in.readString8();
        in.readStringList(mEhplmn);
    }


    /** @hide */
    @android.annotation.NonNull
    @Override
    public String toString() {
        return "CarrierInfo MCC = " + mMcc + "   MNC = " + mMnc + "  SPN = " + mSpn + "   GID1 = "
                + mGid1 + "   GID2 = " + mGid2 + "   IMSI = " + getPrintableImsi() + "   ICCID = "
                + SubscriptionInfo.getPrintableId(mIccid) + "  IMPI = " + mImpi + "  EHPLMN = [ "
                + getEhplmn_toString() + " ]";
    }

    private String getEhplmn_toString() {
        return String.join("  ", mEhplmn);
    }

    private String getPrintableImsi() {
        boolean enablePiiLog = Rlog.isLoggable("CarrierInfo", Log.VERBOSE);
        return ((mImsiPrefix != null && mImsiPrefix.length() > 6) ? mImsiPrefix.substring(0, 6)
                + Rlog.pii(enablePiiLog, mImsiPrefix.substring(6)) : mImsiPrefix);
    }
}
