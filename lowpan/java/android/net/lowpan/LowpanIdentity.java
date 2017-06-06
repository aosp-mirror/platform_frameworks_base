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
import java.util.Map;

/**
 * Describes an instance of a LoWPAN network.
 *
 * @hide
 */
// @SystemApi
public class LowpanIdentity {

    // Constants

    /** @hide */
    public static final int TYPE_ZIGBEE = 1;

    /** @hide */
    public static final int TYPE_ZIGBEE_IP = 2;

    /** @hide */
    public static final int TYPE_THREAD = 3;

    public static final int UNKNOWN = Integer.MAX_VALUE;

    // Builder

    /** @hide */
    // @SystemApi
    public static class Builder {
        private final LowpanIdentity identity = new LowpanIdentity();

        public Builder setName(String x) {
            identity.mName = x;
            return this;
        }

        public Builder setXpanid(byte x[]) {
            identity.mXpanid = x.clone();
            return this;
        }

        public Builder setPanid(int x) {
            identity.mPanid = x;
            return this;
        }

        /** @hide */
        public Builder setType(int x) {
            identity.mType = x;
            return this;
        }

        public Builder setChannel(int x) {
            identity.mChannel = x;
            return this;
        }

        /** @hide */
        Builder updateFromMap(Map map) {
            if (map.containsKey(ILowpanInterface.KEY_NETWORK_NAME)) {
                setName(LowpanProperties.KEY_NETWORK_NAME.getFromMap(map));
            }
            if (map.containsKey(ILowpanInterface.KEY_NETWORK_PANID)) {
                setPanid(LowpanProperties.KEY_NETWORK_PANID.getFromMap(map));
            }
            if (map.containsKey(ILowpanInterface.KEY_NETWORK_XPANID)) {
                setXpanid(LowpanProperties.KEY_NETWORK_XPANID.getFromMap(map));
            }
            if (map.containsKey(ILowpanInterface.KEY_CHANNEL)) {
                setChannel(LowpanProperties.KEY_CHANNEL.getFromMap(map));
            }
            if (map.containsKey(ILowpanInterface.KEY_NETWORK_TYPE)) {
                setType(LowpanProperties.KEY_NETWORK_TYPE.getFromMap(map));
            }
            return this;
        }

        public LowpanIdentity build() {
            return identity;
        }
    }

    LowpanIdentity() {}

    // Instance Variables

    private String mName = null;
    private byte[] mXpanid = null;
    private int mType = UNKNOWN;
    private int mPanid = UNKNOWN;
    private int mChannel = UNKNOWN;

    // Public Getters and Setters

    public String getName() {
        return mName;
    }

    public byte[] getXpanid() {
        return mXpanid.clone();
    }

    public int getPanid() {
        return mPanid;
    }

    /** @hide */
    public int getType() {
        return mType;
    }

    public int getChannel() {
        return mChannel;
    }

    static void addToMap(Map<String, Object> parameters, LowpanIdentity networkInfo) {
        if (networkInfo.getName() != null) {
            LowpanProperties.KEY_NETWORK_NAME.putInMap(parameters, networkInfo.getName());
        }
        if (networkInfo.getPanid() != LowpanIdentity.UNKNOWN) {
            LowpanProperties.KEY_NETWORK_PANID.putInMap(parameters, networkInfo.getPanid());
        }
        if (networkInfo.getChannel() != LowpanIdentity.UNKNOWN) {
            LowpanProperties.KEY_CHANNEL.putInMap(parameters, networkInfo.getChannel());
        }
        if (networkInfo.getXpanid() != null) {
            LowpanProperties.KEY_NETWORK_XPANID.putInMap(parameters, networkInfo.getXpanid());
        }
    }

    void addToMap(Map<String, Object> parameters) {
        addToMap(parameters, this);
    }

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();

        sb.append("Name: ").append(mName == null ? "<none>" : mName);

        if (mXpanid != null) {
            sb.append(", XPANID: ").append(HexDump.toHexString(mXpanid));
        }

        if (mPanid != UNKNOWN) {
            sb.append(", PANID: ").append(String.format("0x%04X", mPanid));
        }

        if (mChannel != UNKNOWN) {
            sb.append(", Channel: ").append(mChannel);
        }

        return sb.toString();
    }
}
