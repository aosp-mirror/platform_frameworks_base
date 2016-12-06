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

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Used to pass info to CarrierConfigService implementations so they can decide what values to
 * return.
 */
public class CarrierIdentifier implements Parcelable {

    /** Used to create a {@link CarrierIdentifier} from a {@link Parcel}. */
    public static final Creator<CarrierIdentifier> CREATOR = new Creator<CarrierIdentifier>() {
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
    private String mSpn;
    private String mImsi;
    private String mGid1;
    private String mGid2;

    public CarrierIdentifier(String mcc, String mnc, String spn, String imsi, String gid1,
            String gid2) {
        mMcc = mcc;
        mMnc = mnc;
        mSpn = spn;
        mImsi = imsi;
        mGid1 = gid1;
        mGid2 = gid2;
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
    public String getSpn() {
        return mSpn;
    }

    /** Get the international mobile subscriber identity. */
    public String getImsi() {
        return mImsi;
    }

    /** Get the group identifier level 1. */
    public String getGid1() {
        return mGid1;
    }

    /** Get the group identifier level 2. */
    public String getGid2() {
        return mGid2;
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
    }

    @Override
    public String toString() {
      return "CarrierIdentifier{"
          + "mcc=" + mMcc
          + ",mnc=" + mMnc
          + ",spn=" + mSpn
          + ",imsi=" + mImsi
          + ",gid1=" + mGid1
          + ",gid2=" + mGid2
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
