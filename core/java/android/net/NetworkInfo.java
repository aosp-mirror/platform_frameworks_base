/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.net;

import android.os.Parcelable;
import android.os.Parcel;

import java.util.EnumMap;

/**
 * Describes the status of a network interface of a given type
 * (currently either Mobile or Wifi).
 */
public class NetworkInfo implements Parcelable {

    /**
     * Coarse-grained network state. This is probably what most applications should
     * use, rather than {@link android.net.NetworkInfo.DetailedState DetailedState}.
     * The mapping between the two is as follows:
     * <br/><br/>
     * <table>
     * <tr><td><b>Detailed state</b></td><td><b>Coarse-grained state</b></td></tr>
     * <tr><td><code>IDLE</code></td><td><code>DISCONNECTED</code></td></tr>
     * <tr><td><code>SCANNING</code></td><td><code>CONNECTING</code></td></tr>
     * <tr><td><code>CONNECTING</code></td><td><code>CONNECTING</code></td></tr>
     * <tr><td><code>AUTHENTICATING</code></td><td><code>CONNECTING</code></td></tr>
     * <tr><td><code>CONNECTED</code></td><td<code>CONNECTED</code></td></tr>
     * <tr><td><code>DISCONNECTING</code></td><td><code>DISCONNECTING</code></td></tr>
     * <tr><td><code>DISCONNECTED</code></td><td><code>DISCONNECTED</code></td></tr>
     * <tr><td><code>UNAVAILABLE</code></td><td><code>DISCONNECTED</code></td></tr>
     * <tr><td><code>FAILED</code></td><td><code>DISCONNECTED</code></td></tr>
     * </table>
     */
    public enum State {
        CONNECTING, CONNECTED, SUSPENDED, DISCONNECTING, DISCONNECTED, UNKNOWN
    }

    /**
     * The fine-grained state of a network connection. This level of detail
     * is probably of interest to few applications. Most should use
     * {@link android.net.NetworkInfo.State State} instead.
     */
    public enum DetailedState {
        /** Ready to start data connection setup. */
        IDLE,
        /** Searching for an available access point. */
        SCANNING,
        /** Currently setting up data connection. */
        CONNECTING,
        /** Network link established, performing authentication. */
        AUTHENTICATING,
        /** Awaiting response from DHCP server in order to assign IP address information. */
        OBTAINING_IPADDR,
        /** IP traffic should be available. */
        CONNECTED,
        /** IP traffic is suspended */
        SUSPENDED,
        /** Currently tearing down data connection. */
        DISCONNECTING,
        /** IP traffic not available. */
        DISCONNECTED,
        /** Attempt to connect failed. */
        FAILED
    }

    /**
     * This is the map described in the Javadoc comment above. The positions
     * of the elements of the array must correspond to the ordinal values
     * of <code>DetailedState</code>.
     */
    private static final EnumMap<DetailedState, State> stateMap =
        new EnumMap<DetailedState, State>(DetailedState.class);

    static {
        stateMap.put(DetailedState.IDLE, State.DISCONNECTED);
        stateMap.put(DetailedState.SCANNING, State.DISCONNECTED);
        stateMap.put(DetailedState.CONNECTING, State.CONNECTING);
        stateMap.put(DetailedState.AUTHENTICATING, State.CONNECTING);
        stateMap.put(DetailedState.OBTAINING_IPADDR, State.CONNECTING);
        stateMap.put(DetailedState.CONNECTED, State.CONNECTED);
        stateMap.put(DetailedState.SUSPENDED, State.SUSPENDED);
        stateMap.put(DetailedState.DISCONNECTING, State.DISCONNECTING);
        stateMap.put(DetailedState.DISCONNECTED, State.DISCONNECTED);
        stateMap.put(DetailedState.FAILED, State.DISCONNECTED);
    }
    
    private int mNetworkType;
    private State mState;
    private DetailedState mDetailedState;
    private String mReason;
    private String mExtraInfo;
    private boolean mIsFailover;
    /**
     * Indicates whether network connectivity is possible:
     */
    private boolean mIsAvailable;

    public NetworkInfo(int type) {
        if (!ConnectivityManager.isNetworkTypeValid(type)) {
            throw new IllegalArgumentException("Invalid network type: " + type);
        }
        this.mNetworkType = type;
        setDetailedState(DetailedState.IDLE, null, null);
        mState = State.UNKNOWN;
        mIsAvailable = true;
    }

    /**
     * Reports the type of network (currently mobile or Wi-Fi) to which the
     * info in this object pertains.
     * @return the network type
     */
    public int getType() {
        return mNetworkType;
    }

    /**
     * Indicates whether network connectivity exists or is in the process
     * of being established. This is good for applications that need to
     * do anything related to the network other than read or write data.
     * For the latter, call {@link #isConnected()} instead, which guarantees
     * that the network is fully usable.
     * @return {@code true} if network connectivity exists or is in the process
     * of being established, {@code false} otherwise.
     */
    public boolean isConnectedOrConnecting() {
        return mState == State.CONNECTED || mState == State.CONNECTING;
    }

    /**
     * Indicates whether network connectivity exists and it is possible to establish
     * connections and pass data.
     * @return {@code true} if network connectivity exists, {@code false} otherwise.
     */
    public boolean isConnected() {
        return mState == State.CONNECTED;
    }

    /**
     * Indicates whether network connectivity is possible. A network is unavailable
     * when a persistent or semi-persistent condition prevents the possibility
     * of connecting to that network. Examples include
     * <ul>
     * <li>The device is out of the coverage area for any network of this type.</li>
     * <li>The device is on a network other than the home network (i.e., roaming), and
     * data roaming has been disabled.</li>
     * <li>The device's radio is turned off, e.g., because airplane mode is enabled.</li>
     * </ul>
     * @return {@code true} if the network is available, {@code false} otherwise
     */
    public boolean isAvailable() {
        return mIsAvailable;
    }

    /**
     * Sets if the network is available, ie, if the connectivity is possible.
     * @param isAvailable the new availability value.
     *
     * {@hide}
     */
    public void setIsAvailable(boolean isAvailable) {
        mIsAvailable = isAvailable;
    }

    /**
     * Indicates whether the current attempt to connect to the network
     * resulted from the ConnectivityManager trying to fail over to this
     * network following a disconnect from another network.
     * @return {@code true} if this is a failover attempt, {@code false}
     * otherwise.
     */
    public boolean isFailover() {
        return mIsFailover;
    }

    /** {@hide} */
    public void setFailover(boolean isFailover) {
        mIsFailover = isFailover;
    }

    /**
     * Reports the current coarse-grained state of the network.
     * @return the coarse-grained state
     */
    public State getState() {
        return mState;
    }

    /**
     * Reports the current fine-grained state of the network.
     * @return the fine-grained state
     */
    public DetailedState getDetailedState() {
        return mDetailedState;
    }

    /**
     * Sets the fine-grained state of the network.
     * @param detailedState the {@link DetailedState}.
     * @param reason a {@code String} indicating the reason for the state change,
     * if one was supplied. May be {@code null}.
     * @param extraInfo an optional {@code String} providing addditional network state
     * information passed up from the lower networking layers.
     *
     * {@hide}
     */
    void setDetailedState(DetailedState detailedState, String reason, String extraInfo) {
        this.mDetailedState = detailedState;
        this.mState = stateMap.get(detailedState);
        this.mReason = reason;
        this.mExtraInfo = extraInfo;
    }

    /**
     * Report the reason an attempt to establish connectivity failed,
     * if one is available.
     * @return the reason for failure, or null if not available
     */
    public String getReason() {
        return mReason;
    }

    /**
     * Report the extra information about the network state, if any was
     * provided by the lower networking layers.,
     * if one is available.
     * @return the extra information, or null if not available
     */
    public String getExtraInfo() {
        return mExtraInfo;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder("NetworkInfo: ");
        builder.append("type: ").append(getTypeName()).append(", state: ").append(mState).
                append("/").append(mDetailedState).
                append(", reason: ").append(mReason == null ? "(unspecified)" : mReason).
                append(", extra: ").append(mExtraInfo == null ? "(none)" : mExtraInfo).
                append(", failover: ").append(mIsFailover).
                append(", isAvailable: ").append(mIsAvailable);
        return builder.toString();
    }

    public String getTypeName() {
        switch (mNetworkType) {
            case ConnectivityManager.TYPE_WIFI:
                return "WIFI";
            case ConnectivityManager.TYPE_MOBILE:
                return "MOBILE";
            default:
                return "<invalid>";
        }
    }

    /** Implement the Parcelable interface {@hide} */
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface {@hide} */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mNetworkType);
        dest.writeString(mState.name());
        dest.writeString(mDetailedState.name());
        dest.writeInt(mIsFailover ? 1 : 0);
        dest.writeInt(mIsAvailable ? 1 : 0);
        dest.writeString(mReason);
        dest.writeString(mExtraInfo);
    }

    /** Implement the Parcelable interface {@hide} */
    public static final Creator<NetworkInfo> CREATOR =
        new Creator<NetworkInfo>() {
            public NetworkInfo createFromParcel(Parcel in) {
                int netType = in.readInt();
                NetworkInfo netInfo = new NetworkInfo(netType);
                netInfo.mState = State.valueOf(in.readString());
                netInfo.mDetailedState = DetailedState.valueOf(in.readString());
                netInfo.mIsFailover = in.readInt() != 0;
                netInfo.mIsAvailable = in.readInt() != 0;
                netInfo.mReason = in.readString();
                netInfo.mExtraInfo = in.readString();
                return netInfo;
            }

            public NetworkInfo[] newArray(int size) {
                return new NetworkInfo[size];
            }
        };
}
