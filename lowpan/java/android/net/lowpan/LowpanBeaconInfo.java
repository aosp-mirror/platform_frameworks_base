/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.net.lowpan;

import android.os.Parcel;
import android.os.Parcelable;
import com.android.internal.util.HexDump;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Describes a LoWPAN Beacon
 *
 * @hide
 */
// @SystemApi
public class LowpanBeaconInfo implements Parcelable {
    public static final int UNKNOWN_RSSI = Integer.MAX_VALUE;
    public static final int UNKNOWN_LQI = 0;

    private LowpanIdentity mIdentity;
    private int mRssi = UNKNOWN_RSSI;
    private int mLqi = UNKNOWN_LQI;
    private byte[] mBeaconAddress = null;
    private final TreeSet<Integer> mFlags = new TreeSet<>();

    public static final int FLAG_CAN_ASSIST = 1;

    /** @hide */
    public static class Builder {
        final LowpanIdentity.Builder mIdentityBuilder = new LowpanIdentity.Builder();
        final LowpanBeaconInfo mBeaconInfo = new LowpanBeaconInfo();

        public Builder setLowpanIdentity(LowpanIdentity x) {
            mIdentityBuilder.setLowpanIdentity(x);
            return this;
        }

        public Builder setName(String x) {
            mIdentityBuilder.setName(x);
            return this;
        }

        public Builder setXpanid(byte x[]) {
            mIdentityBuilder.setXpanid(x);
            return this;
        }

        public Builder setPanid(int x) {
            mIdentityBuilder.setPanid(x);
            return this;
        }

        public Builder setChannel(int x) {
            mIdentityBuilder.setChannel(x);
            return this;
        }

        public Builder setType(String x) {
            mIdentityBuilder.setType(x);
            return this;
        }

        public Builder setRssi(int x) {
            mBeaconInfo.mRssi = x;
            return this;
        }

        public Builder setLqi(int x) {
            mBeaconInfo.mLqi = x;
            return this;
        }

        public Builder setBeaconAddress(byte x[]) {
            mBeaconInfo.mBeaconAddress = (x != null ? x.clone() : null);
            return this;
        }

        public Builder setFlag(int x) {
            mBeaconInfo.mFlags.add(x);
            return this;
        }

        public Builder setFlags(Collection<Integer> x) {
            mBeaconInfo.mFlags.addAll(x);
            return this;
        }

        public LowpanBeaconInfo build() {
            mBeaconInfo.mIdentity = mIdentityBuilder.build();
            if (mBeaconInfo.mBeaconAddress == null) {
                mBeaconInfo.mBeaconAddress = new byte[0];
            }
            return mBeaconInfo;
        }
    }

    private LowpanBeaconInfo() {}

    public LowpanIdentity getLowpanIdentity() {
        return mIdentity;
    }

    public int getRssi() {
        return mRssi;
    }

    public int getLqi() {
        return mLqi;
    }

    public byte[] getBeaconAddress() {
        return mBeaconAddress.clone();
    }

    public Collection<Integer> getFlags() {
        return (Collection<Integer>) mFlags.clone();
    }

    public boolean isFlagSet(int flag) {
        return mFlags.contains(flag);
    }

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();

        sb.append(mIdentity.toString());

        if (mRssi != UNKNOWN_RSSI) {
            sb.append(", RSSI:").append(mRssi).append("dBm");
        }

        if (mLqi != UNKNOWN_LQI) {
            sb.append(", LQI:").append(mLqi);
        }

        if (mBeaconAddress.length > 0) {
            sb.append(", BeaconAddress:").append(HexDump.toHexString(mBeaconAddress));
        }

        for (Integer flag : mFlags) {
            switch (flag.intValue()) {
                case FLAG_CAN_ASSIST:
                    sb.append(", CAN_ASSIST");
                    break;
                default:
                    sb.append(", FLAG_").append(Integer.toHexString(flag));
                    break;
            }
        }

        return sb.toString();
    }

    @Override
    public int hashCode() {
        return Objects.hash(mIdentity, mRssi, mLqi, Arrays.hashCode(mBeaconAddress), mFlags);
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof LowpanBeaconInfo)) {
            return false;
        }
        LowpanBeaconInfo rhs = (LowpanBeaconInfo) obj;
        return mIdentity.equals(rhs.mIdentity)
                && Arrays.equals(mBeaconAddress, rhs.mBeaconAddress)
                && mRssi == rhs.mRssi
                && mLqi == rhs.mLqi
                && mFlags.equals(rhs.mFlags);
    }

    /** Implement the Parcelable interface. */
    @Override
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface. */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        mIdentity.writeToParcel(dest, flags);
        dest.writeInt(mRssi);
        dest.writeInt(mLqi);
        dest.writeByteArray(mBeaconAddress);

        dest.writeInt(mFlags.size());
        for (Integer val : mFlags) {
            dest.writeInt(val);
        }
    }

    /** Implement the Parcelable interface. */
    public static final @android.annotation.NonNull Creator<LowpanBeaconInfo> CREATOR =
            new Creator<LowpanBeaconInfo>() {
                public LowpanBeaconInfo createFromParcel(Parcel in) {
                    Builder builder = new Builder();

                    builder.setLowpanIdentity(LowpanIdentity.CREATOR.createFromParcel(in));

                    builder.setRssi(in.readInt());
                    builder.setLqi(in.readInt());

                    builder.setBeaconAddress(in.createByteArray());

                    for (int i = in.readInt(); i > 0; i--) {
                        builder.setFlag(in.readInt());
                    }

                    return builder.build();
                }

                public LowpanBeaconInfo[] newArray(int size) {
                    return new LowpanBeaconInfo[size];
                }
            };
}
