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

package android.net.wifi.aware;

import android.net.NetworkSpecifier;
import android.net.wifi.util.HexEncoding;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.StringJoiner;

/**
 * A network specifier object used to represent the capabilities of an network agent. A collection
 * of multiple WifiAwareNetworkSpecifier objects whose matching critiera (satisfiedBy) is an OR:
 * a match on any of the network specifiers in the collection is a match.
 *
 * This class is not intended for use in network requests.
 *
 * @hide
 */
public class WifiAwareAgentNetworkSpecifier extends NetworkSpecifier implements Parcelable {
    private static final String TAG = "WifiAwareAgentNs";

    private static final boolean VDBG = false; // STOPSHIP if true

    private Set<ByteArrayWrapper> mNetworkSpecifiers = new HashSet<>();
    private MessageDigest mDigester;

    public WifiAwareAgentNetworkSpecifier() {
        initialize();
    }

    public WifiAwareAgentNetworkSpecifier(WifiAwareNetworkSpecifier ns) {
        initialize();
        mNetworkSpecifiers.add(convert(ns));
    }

    public WifiAwareAgentNetworkSpecifier(WifiAwareNetworkSpecifier[] nss) {
        initialize();
        for (WifiAwareNetworkSpecifier ns : nss) {
            mNetworkSpecifiers.add(convert(ns));
        }
    }

    public boolean isEmpty() {
        return mNetworkSpecifiers.isEmpty();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeArray(mNetworkSpecifiers.toArray());
    }

    public static final @android.annotation.NonNull Creator<WifiAwareAgentNetworkSpecifier> CREATOR =
            new Creator<WifiAwareAgentNetworkSpecifier>() {
                @Override
                public WifiAwareAgentNetworkSpecifier createFromParcel(Parcel in) {
                    WifiAwareAgentNetworkSpecifier agentNs = new WifiAwareAgentNetworkSpecifier();
                    Object[] objs = in.readArray(null);
                    for (Object obj : objs) {
                        agentNs.mNetworkSpecifiers.add((ByteArrayWrapper) obj);
                    }
                    return agentNs;
                }

                @Override
                public WifiAwareAgentNetworkSpecifier[] newArray(int size) {
                    return new WifiAwareAgentNetworkSpecifier[size];
                }
            };

    @Override
    public int hashCode() {
        return mNetworkSpecifiers.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof WifiAwareAgentNetworkSpecifier)) {
            return false;
        }
        return mNetworkSpecifiers.equals(((WifiAwareAgentNetworkSpecifier) obj).mNetworkSpecifiers);
    }

    @Override
    public String toString() {
        StringJoiner sj = new StringJoiner(",");
        for (ByteArrayWrapper baw: mNetworkSpecifiers) {
            sj.add(baw.toString());
        }
        return sj.toString();
    }

    @Override
    public boolean canBeSatisfiedBy(NetworkSpecifier other) {
        if (!(other instanceof WifiAwareAgentNetworkSpecifier)) {
            return false;
        }
        WifiAwareAgentNetworkSpecifier otherNs = (WifiAwareAgentNetworkSpecifier) other;

        // called as old.satifiedBy(new): satisfied if old contained in new
        for (ByteArrayWrapper baw: mNetworkSpecifiers) {
            if (!otherNs.mNetworkSpecifiers.contains(baw)) {
                return false;
            }
        }

        return true;
    }

    public boolean satisfiesAwareNetworkSpecifier(WifiAwareNetworkSpecifier ns) {
        if (VDBG) Log.v(TAG, "satisfiesAwareNetworkSpecifier: ns=" + ns);
        ByteArrayWrapper nsBytes = convert(ns);
        return mNetworkSpecifiers.contains(nsBytes);
    }

    @Override
    public NetworkSpecifier redact() {
        return null;
    }

    private void initialize() {
        try {
            mDigester = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            Log.e(TAG, "Can not instantiate a SHA-256 digester!? Will match nothing.");
            return;
        }
    }

    private ByteArrayWrapper convert(WifiAwareNetworkSpecifier ns) {
        if (mDigester == null) {
            return null;
        }

        Parcel parcel = Parcel.obtain();
        ns.writeToParcel(parcel, 0);
        byte[] bytes = parcel.marshall();

        mDigester.reset();
        mDigester.update(bytes);
        return new ByteArrayWrapper(mDigester.digest());
    }

    private static class ByteArrayWrapper implements Parcelable {
        private byte[] mData;

        ByteArrayWrapper(byte[] data) {
            mData = data;
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(mData);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this) {
                return true;
            }
            if (!(obj instanceof ByteArrayWrapper)) {
                return false;
            }
            return Arrays.equals(((ByteArrayWrapper) obj).mData, mData);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeByteArray(mData);
        }

        public static final @android.annotation.NonNull Creator<ByteArrayWrapper> CREATOR =
                new Creator<ByteArrayWrapper>() {
                    @Override
                    public ByteArrayWrapper createFromParcel(Parcel in) {
                        return new ByteArrayWrapper(in.createByteArray());
                    }

                    @Override
                    public ByteArrayWrapper[] newArray(int size) {
                        return new ByteArrayWrapper[size];
                    }
                };

        @Override
        public String toString() {
            return new String(HexEncoding.encode(mData));
        }
    }
}
