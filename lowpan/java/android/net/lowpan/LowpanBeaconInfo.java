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

import com.android.internal.util.HexDump;
import java.util.Collection;
import java.util.Map;
import java.util.TreeSet;

/**
 * Describes a LoWPAN Beacon
 *
 * @hide
 */
// @SystemApi
public class LowpanBeaconInfo extends LowpanIdentity {

    private int mRssi = UNKNOWN;
    private int mLqi = UNKNOWN;
    private byte[] mBeaconAddress = null;
    private final TreeSet<Integer> mFlags = new TreeSet<>();

    public static final int FLAG_CAN_ASSIST = 1;

    static class Builder extends LowpanIdentity.Builder {
        private final LowpanBeaconInfo identity = new LowpanBeaconInfo();

        public Builder setRssi(int x) {
            identity.mRssi = x;
            return this;
        }

        public Builder setLqi(int x) {
            identity.mLqi = x;
            return this;
        }

        public Builder setBeaconAddress(byte x[]) {
            identity.mBeaconAddress = x.clone();
            return this;
        }

        public Builder setFlag(int x) {
            identity.mFlags.add(x);
            return this;
        }

        public Builder setFlags(Collection<Integer> x) {
            identity.mFlags.addAll(x);
            return this;
        }

        /** @hide */
        Builder updateFromMap(Map map) {
            if (map.containsKey(LowpanProperties.KEY_RSSI.getName())) {
                setRssi(LowpanProperties.KEY_RSSI.getFromMap(map));
            }
            if (map.containsKey(LowpanProperties.KEY_LQI.getName())) {
                setLqi(LowpanProperties.KEY_LQI.getFromMap(map));
            }
            if (map.containsKey(LowpanProperties.KEY_BEACON_ADDRESS.getName())) {
                setBeaconAddress(LowpanProperties.KEY_BEACON_ADDRESS.getFromMap(map));
            }
            identity.mFlags.clear();
            if (map.containsKey(LowpanProperties.KEY_BEACON_CAN_ASSIST.getName())
                    && LowpanProperties.KEY_BEACON_CAN_ASSIST.getFromMap(map).booleanValue()) {
                setFlag(FLAG_CAN_ASSIST);
            }
            super.updateFromMap(map);
            return this;
        }

        public LowpanBeaconInfo build() {
            return identity;
        }
    }

    private LowpanBeaconInfo() {}

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
    void addToMap(Map<String, Object> parameters) {
        super.addToMap(parameters);
    }

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();

        sb.append(super.toString());

        if (mRssi != UNKNOWN) {
            sb.append(", RSSI: ").append(mRssi);
        }

        if (mLqi != UNKNOWN) {
            sb.append(", LQI: ").append(mLqi);
        }

        if (mBeaconAddress != null) {
            sb.append(", BeaconAddress: ").append(HexDump.toHexString(mBeaconAddress));
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
}
