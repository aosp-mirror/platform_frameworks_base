/**
 * Copyright (c) 2015, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.service.carrier;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.Rlog;
import android.telephony.TelephonyManager;

import com.android.internal.telephony.uicc.IccUtils;

import java.util.Objects;

/**
 * Used to pass info to CarrierConfigService implementations so they can decide what values to
 * return. Instead of passing mcc, mnc, gid1, gid2, spn, imsi to locate carrier information,
 * CarrierIdentifier also include carrier id {@link TelephonyManager#getSimCarrierId()},
 * a platform-wide unique identifier for each carrier. CarrierConfigService can directly use
 * carrier id as the key to look up the carrier info.
 */
public class CarrierIdentifier implements Parcelable {

    /** Used to create a {@link CarrierIdentifier} from a {@link Parcel}. */
    public static final @android.annotation.NonNull Creator<CarrierIdentifier> CREATOR = new Creator<CarrierIdentifier>() {
            @Override
        public CarrierIdentifier createFromParcel(Parcel parcel) {
            return new CarrierIdentifier(parcel);
        }

            @Override
        public CarrierIdentifier[] newArray(int i) {
            return new CarrierIdentifier[i];
        }
    };

    private String mMcc;
    private String mMnc;
    private @Nullable String mSpn;
    private @Nullable String mImsi;
    private @Nullable String mGid1;
    private @Nullable String mGid2;
    private int mCarrierId = TelephonyManager.UNKNOWN_CARRIER_ID;
    private int mSpecificCarrierId = TelephonyManager.UNKNOWN_CARRIER_ID;

    public CarrierIdentifier(String mcc, String mnc, @Nullable String spn, @Nullable String imsi,
            @Nullable String gid1, @Nullable String gid2) {
        this(mcc, mnc, spn, imsi, gid1, gid2, TelephonyManager.UNKNOWN_CARRIER_ID,
                TelephonyManager.UNKNOWN_CARRIER_ID);
    }

    /**
     * @param mcc mobile country code
     * @param mnc mobile network code
     * @param spn service provider name
     * @param imsi International Mobile Subscriber Identity {@link TelephonyManager#getSubscriberId()}
     * @param gid1 group id level 1 {@link TelephonyManager#getGroupIdLevel1()}
     * @param gid2 group id level 2
     * @param carrierid carrier unique identifier {@link TelephonyManager#getSimCarrierId()}, used
     *                  to uniquely identify the carrier and look up the carrier configurations.
     * @param specificCarrierId specific carrier identifier
     * {@link TelephonyManager#getSimSpecificCarrierId()}
     */
    public CarrierIdentifier(@NonNull String mcc, @NonNull String mnc, @Nullable String spn,
                             @Nullable String imsi, @Nullable String gid1, @Nullable String gid2,
                             int carrierid, int specificCarrierId) {
        mMcc = mcc;
        mMnc = mnc;
        mSpn = spn;
        mImsi = imsi;
        mGid1 = gid1;
        mGid2 = gid2;
        mCarrierId = carrierid;
        mSpecificCarrierId = specificCarrierId;
    }

    /**
     * Creates a carrier identifier instance.
     *
     * @param mccMnc A 3-byte array as defined by 3GPP TS 24.008.
     * @param gid1 The group identifier level 1.
     * @param gid2 The group identifier level 2.
     * @throws IllegalArgumentException If the length of {@code mccMnc} is not 3.
     */
    public CarrierIdentifier(byte[] mccMnc, @Nullable String gid1, @Nullable String gid2) {
        if (mccMnc.length != 3) {
            throw new IllegalArgumentException(
                    "MCC & MNC must be set by a 3-byte array: byte[" + mccMnc.length + "]");
        }
        String hex = IccUtils.bytesToHexString(mccMnc);
        mMcc = new String(new char[] {hex.charAt(1), hex.charAt(0), hex.charAt(3)});
        if (hex.charAt(2) == 'F') {
            mMnc = new String(new char[] {hex.charAt(5), hex.charAt(4)});
        } else {
            mMnc = new String(new char[] {hex.charAt(5), hex.charAt(4), hex.charAt(2)});
        }
        mGid1 = gid1;
        mGid2 = gid2;
        mSpn = null;
        mImsi = null;
    }

    /** @hide */
    public CarrierIdentifier(Parcel parcel) {
        readFromParcel(parcel);
    }

    /** Get the mobile country code. */
    public String getMcc() {
        return mMcc;
    }

    /** Get the mobile network code. */
    public String getMnc() {
        return mMnc;
    }

    /** Get the service provider name. */
    @Nullable
    public String getSpn() {
        return mSpn;
    }

    /** Get the international mobile subscriber identity. */
    @Nullable
    public String getImsi() {
        return mImsi;
    }

    /** Get the group identifier level 1. */
    @Nullable
    public String getGid1() {
        return mGid1;
    }

    /** Get the group identifier level 2. */
    @Nullable
    public String getGid2() {
        return mGid2;
    }

    /**
     * Returns the carrier id.
     * @see TelephonyManager#getSimCarrierId()
     */
    public int getCarrierId() {
        return mCarrierId;
    }

    /**
     * A specific carrier ID returns the fine-grained carrier ID of the current subscription.
     * It can represent the fact that a carrier may be in effect an aggregation of other carriers
     * (ie in an MVNO type scenario) where each of these specific carriers which are used to make
     * up the actual carrier service may have different carrier configurations.
     * A specific carrier ID could also be used, for example, in a scenario where a carrier requires
     * different carrier configuration for different service offering such as a prepaid plan.
     *
     * @see TelephonyManager#getSimSpecificCarrierId()
     */
    public int getSpecificCarrierId() {
        return mSpecificCarrierId;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }

        CarrierIdentifier that = (CarrierIdentifier) obj;
        return Objects.equals(mMcc, that.mMcc)
                && Objects.equals(mMnc, that.mMnc)
                && Objects.equals(mSpn, that.mSpn)
                && Objects.equals(mImsi, that.mImsi)
                && Objects.equals(mGid1, that.mGid1)
                && Objects.equals(mGid2, that.mGid2)
                && Objects.equals(mCarrierId, that.mCarrierId)
                && Objects.equals(mSpecificCarrierId, that.mSpecificCarrierId);
    }

    @Override
    public int hashCode(){
        return Objects.hash(mMcc, mMnc, mSpn, mImsi, mGid1, mGid2, mCarrierId, mSpecificCarrierId);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(mMcc);
        out.writeString(mMnc);
        out.writeString(mSpn);
        out.writeString(mImsi);
        out.writeString(mGid1);
        out.writeString(mGid2);
        out.writeInt(mCarrierId);
        out.writeInt(mSpecificCarrierId);
    }

    @Override
    public String toString() {
      return "CarrierIdentifier{"
              + "mcc=" + mMcc
              + ",mnc=" + mMnc
              + ",spn=" + mSpn
              + ",imsi=" + Rlog.pii(false, mImsi)
              + ",gid1=" + mGid1
              + ",gid2=" + mGid2
              + ",carrierid=" + mCarrierId
              + ",specificCarrierId=" + mSpecificCarrierId
              + "}";
    }

    /** @hide */
    public void readFromParcel(Parcel in) {
        mMcc = in.readString();
        mMnc = in.readString();
        mSpn = in.readString();
        mImsi = in.readString();
        mGid1 = in.readString();
        mGid2 = in.readString();
        mCarrierId = in.readInt();
        mSpecificCarrierId = in.readInt();
    }

    /** @hide */
    public interface MatchType {
        int ALL = 0;
        int SPN = 1;
        int IMSI_PREFIX = 2;
        int GID1 = 3;
        int GID2 = 4;
    }
}
