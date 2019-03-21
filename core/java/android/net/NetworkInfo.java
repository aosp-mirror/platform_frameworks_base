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

import android.annotation.NonNull;
import android.annotation.UnsupportedAppUsage;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.annotations.VisibleForTesting;

import java.util.EnumMap;

/**
 * Describes the status of a network interface.
 * <p>Use {@link ConnectivityManager#getActiveNetworkInfo()} to get an instance that represents
 * the current network connection.
 *
 * @deprecated Callers should instead use the {@link ConnectivityManager.NetworkCallback} API to
 *             learn about connectivity changes, or switch to use
 *             {@link ConnectivityManager#getNetworkCapabilities} or
 *             {@link ConnectivityManager#getLinkProperties} to get information synchronously. Keep
 *             in mind that while callbacks are guaranteed to be called for every event in order,
 *             synchronous calls have no such constraints, and as such it is unadvisable to use the
 *             synchronous methods inside the callbacks as they will often not offer a view of
 *             networking that is consistent (that is: they may return a past or a future state with
 *             respect to the event being processed by the callback). Instead, callers are advised
 *             to only use the arguments of the callbacks, possibly memorizing the specific bits of
 *             information they need to keep from one callback to another.
 */
@Deprecated
public class NetworkInfo implements Parcelable {

    /**
     * Coarse-grained network state. This is probably what most applications should
     * use, rather than {@link android.net.NetworkInfo.DetailedState DetailedState}.
     * The mapping between the two is as follows:
     * <br/><br/>
     * <table>
     * <tr><td><b>Detailed state</b></td><td><b>Coarse-grained state</b></td></tr>
     * <tr><td><code>IDLE</code></td><td><code>DISCONNECTED</code></td></tr>
     * <tr><td><code>SCANNING</code></td><td><code>DISCONNECTED</code></td></tr>
     * <tr><td><code>CONNECTING</code></td><td><code>CONNECTING</code></td></tr>
     * <tr><td><code>AUTHENTICATING</code></td><td><code>CONNECTING</code></td></tr>
     * <tr><td><code>OBTAINING_IPADDR</code></td><td><code>CONNECTING</code></td></tr>
     * <tr><td><code>VERIFYING_POOR_LINK</code></td><td><code>CONNECTING</code></td></tr>
     * <tr><td><code>CAPTIVE_PORTAL_CHECK</code></td><td><code>CONNECTING</code></td></tr>
     * <tr><td><code>CONNECTED</code></td><td><code>CONNECTED</code></td></tr>
     * <tr><td><code>SUSPENDED</code></td><td><code>SUSPENDED</code></td></tr>
     * <tr><td><code>DISCONNECTING</code></td><td><code>DISCONNECTING</code></td></tr>
     * <tr><td><code>DISCONNECTED</code></td><td><code>DISCONNECTED</code></td></tr>
     * <tr><td><code>FAILED</code></td><td><code>DISCONNECTED</code></td></tr>
     * <tr><td><code>BLOCKED</code></td><td><code>DISCONNECTED</code></td></tr>
     * </table>
     *
     * @deprecated See {@link NetworkInfo}.
     */
    @Deprecated
    public enum State {
        CONNECTING, CONNECTED, SUSPENDED, DISCONNECTING, DISCONNECTED, UNKNOWN
    }

    /**
     * The fine-grained state of a network connection. This level of detail
     * is probably of interest to few applications. Most should use
     * {@link android.net.NetworkInfo.State State} instead.
     *
     * @deprecated See {@link NetworkInfo}.
     */
    @Deprecated
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
        FAILED,
        /** Access to this network is blocked. */
        BLOCKED,
        /** Link has poor connectivity. */
        VERIFYING_POOR_LINK,
        /** Checking if network is a captive portal */
        CAPTIVE_PORTAL_CHECK
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
        stateMap.put(DetailedState.VERIFYING_POOR_LINK, State.CONNECTING);
        stateMap.put(DetailedState.CAPTIVE_PORTAL_CHECK, State.CONNECTING);
        stateMap.put(DetailedState.CONNECTED, State.CONNECTED);
        stateMap.put(DetailedState.SUSPENDED, State.SUSPENDED);
        stateMap.put(DetailedState.DISCONNECTING, State.DISCONNECTING);
        stateMap.put(DetailedState.DISCONNECTED, State.DISCONNECTED);
        stateMap.put(DetailedState.FAILED, State.DISCONNECTED);
        stateMap.put(DetailedState.BLOCKED, State.DISCONNECTED);
    }

    private int mNetworkType;
    private int mSubtype;
    private String mTypeName;
    private String mSubtypeName;
    @NonNull
    private State mState;
    @NonNull
    private DetailedState mDetailedState;
    private String mReason;
    private String mExtraInfo;
    private boolean mIsFailover;
    private boolean mIsAvailable;
    private boolean mIsRoaming;

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public NetworkInfo(int type, int subtype, String typeName, String subtypeName) {
        if (!ConnectivityManager.isNetworkTypeValid(type)
                && type != ConnectivityManager.TYPE_NONE) {
            throw new IllegalArgumentException("Invalid network type: " + type);
        }
        mNetworkType = type;
        mSubtype = subtype;
        mTypeName = typeName;
        mSubtypeName = subtypeName;
        setDetailedState(DetailedState.IDLE, null, null);
        mState = State.UNKNOWN;
    }

    /** {@hide} */
    @UnsupportedAppUsage
    public NetworkInfo(NetworkInfo source) {
        if (source != null) {
            synchronized (source) {
                mNetworkType = source.mNetworkType;
                mSubtype = source.mSubtype;
                mTypeName = source.mTypeName;
                mSubtypeName = source.mSubtypeName;
                mState = source.mState;
                mDetailedState = source.mDetailedState;
                mReason = source.mReason;
                mExtraInfo = source.mExtraInfo;
                mIsFailover = source.mIsFailover;
                mIsAvailable = source.mIsAvailable;
                mIsRoaming = source.mIsRoaming;
            }
        }
    }

    /**
     * Reports the type of network to which the
     * info in this {@code NetworkInfo} pertains.
     * @return one of {@link ConnectivityManager#TYPE_MOBILE}, {@link
     * ConnectivityManager#TYPE_WIFI}, {@link ConnectivityManager#TYPE_WIMAX}, {@link
     * ConnectivityManager#TYPE_ETHERNET},  {@link ConnectivityManager#TYPE_BLUETOOTH}, or other
     * types defined by {@link ConnectivityManager}.
     * @deprecated Callers should switch to checking {@link NetworkCapabilities#hasTransport}
     *             instead with one of the NetworkCapabilities#TRANSPORT_* constants :
     *             {@link #getType} and {@link #getTypeName} cannot account for networks using
     *             multiple transports. Note that generally apps should not care about transport;
     *             {@link NetworkCapabilities#NET_CAPABILITY_NOT_METERED} and
     *             {@link NetworkCapabilities#getLinkDownstreamBandwidthKbps} are calls that
     *             apps concerned with meteredness or bandwidth should be looking at, as they
     *             offer this information with much better accuracy.
     */
    @Deprecated
    public int getType() {
        synchronized (this) {
            return mNetworkType;
        }
    }

    /**
     * @deprecated Use {@link NetworkCapabilities} instead
     * @hide
     */
    @Deprecated
    public void setType(int type) {
        synchronized (this) {
            mNetworkType = type;
        }
    }

    /**
     * Return a network-type-specific integer describing the subtype
     * of the network.
     * @return the network subtype
     * @deprecated Use {@link android.telephony.TelephonyManager#getDataNetworkType} instead.
     */
    @Deprecated
    public int getSubtype() {
        synchronized (this) {
            return mSubtype;
        }
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public void setSubtype(int subtype, String subtypeName) {
        synchronized (this) {
            mSubtype = subtype;
            mSubtypeName = subtypeName;
        }
    }

    /**
     * Return a human-readable name describe the type of the network,
     * for example "WIFI" or "MOBILE".
     * @return the name of the network type
     * @deprecated Callers should switch to checking {@link NetworkCapabilities#hasTransport}
     *             instead with one of the NetworkCapabilities#TRANSPORT_* constants :
     *             {@link #getType} and {@link #getTypeName} cannot account for networks using
     *             multiple transports. Note that generally apps should not care about transport;
     *             {@link NetworkCapabilities#NET_CAPABILITY_NOT_METERED} and
     *             {@link NetworkCapabilities#getLinkDownstreamBandwidthKbps} are calls that
     *             apps concerned with meteredness or bandwidth should be looking at, as they
     *             offer this information with much better accuracy.
     */
    @Deprecated
    public String getTypeName() {
        synchronized (this) {
            return mTypeName;
        }
    }

    /**
     * Return a human-readable name describing the subtype of the network.
     * @return the name of the network subtype
     * @deprecated Use {@link android.telephony.TelephonyManager#getDataNetworkType} instead.
     */
    @Deprecated
    public String getSubtypeName() {
        synchronized (this) {
            return mSubtypeName;
        }
    }

    /**
     * Indicates whether network connectivity exists or is in the process
     * of being established. This is good for applications that need to
     * do anything related to the network other than read or write data.
     * For the latter, call {@link #isConnected()} instead, which guarantees
     * that the network is fully usable.
     * @return {@code true} if network connectivity exists or is in the process
     * of being established, {@code false} otherwise.
     * @deprecated Apps should instead use the
     *             {@link android.net.ConnectivityManager.NetworkCallback} API to
     *             learn about connectivity changes.
     *             {@link ConnectivityManager#registerDefaultNetworkCallback} and
     *             {@link ConnectivityManager#registerNetworkCallback}. These will
     *             give a more accurate picture of the connectivity state of
     *             the device and let apps react more easily and quickly to changes.
     */
    @Deprecated
    public boolean isConnectedOrConnecting() {
        synchronized (this) {
            return mState == State.CONNECTED || mState == State.CONNECTING;
        }
    }

    /**
     * Indicates whether network connectivity exists and it is possible to establish
     * connections and pass data.
     * <p>Always call this before attempting to perform data transactions.
     * @return {@code true} if network connectivity exists, {@code false} otherwise.
     * @deprecated Apps should instead use the
     *             {@link android.net.ConnectivityManager.NetworkCallback} API to
     *             learn about connectivity changes. See
     *             {@link ConnectivityManager#registerDefaultNetworkCallback} and
     *             {@link ConnectivityManager#registerNetworkCallback}. These will
     *             give a more accurate picture of the connectivity state of
     *             the device and let apps react more easily and quickly to changes.
     */
    @Deprecated
    public boolean isConnected() {
        synchronized (this) {
            return mState == State.CONNECTED;
        }
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
     * Since Android L, this always returns {@code true}, because the system only
     * returns info for available networks.
     * @return {@code true} if the network is available, {@code false} otherwise
     * @deprecated Apps should instead use the
     *             {@link android.net.ConnectivityManager.NetworkCallback} API to
     *             learn about connectivity changes.
     *             {@link ConnectivityManager#registerDefaultNetworkCallback} and
     *             {@link ConnectivityManager#registerNetworkCallback}. These will
     *             give a more accurate picture of the connectivity state of
     *             the device and let apps react more easily and quickly to changes.
     */
    @Deprecated
    public boolean isAvailable() {
        synchronized (this) {
            return mIsAvailable;
        }
    }

    /**
     * Sets if the network is available, ie, if the connectivity is possible.
     * @param isAvailable the new availability value.
     * @deprecated Use {@link NetworkCapabilities} instead
     *
     * @hide
     */
    @Deprecated
    @UnsupportedAppUsage
    public void setIsAvailable(boolean isAvailable) {
        synchronized (this) {
            mIsAvailable = isAvailable;
        }
    }

    /**
     * Indicates whether the current attempt to connect to the network
     * resulted from the ConnectivityManager trying to fail over to this
     * network following a disconnect from another network.
     * @return {@code true} if this is a failover attempt, {@code false}
     * otherwise.
     * @deprecated This field is not populated in recent Android releases,
     *             and does not make a lot of sense in a multi-network world.
     */
    @Deprecated
    public boolean isFailover() {
        synchronized (this) {
            return mIsFailover;
        }
    }

    /**
     * Set the failover boolean.
     * @param isFailover {@code true} to mark the current connection attempt
     * as a failover.
     * @deprecated This hasn't been set in any recent Android release.
     * @hide
     */
    @Deprecated
    @UnsupportedAppUsage
    public void setFailover(boolean isFailover) {
        synchronized (this) {
            mIsFailover = isFailover;
        }
    }

    /**
     * Indicates whether the device is currently roaming on this network. When
     * {@code true}, it suggests that use of data on this network may incur
     * extra costs.
     *
     * @return {@code true} if roaming is in effect, {@code false} otherwise.
     * @deprecated Callers should switch to checking
     *             {@link NetworkCapabilities#NET_CAPABILITY_NOT_ROAMING}
     *             instead, since that handles more complex situations, such as
     *             VPNs.
     */
    @Deprecated
    public boolean isRoaming() {
        synchronized (this) {
            return mIsRoaming;
        }
    }

    /**
     * @deprecated Use {@link NetworkCapabilities#NET_CAPABILITY_NOT_ROAMING} instead.
     * {@hide}
     */
    @VisibleForTesting
    @Deprecated
    @UnsupportedAppUsage
    public void setRoaming(boolean isRoaming) {
        synchronized (this) {
            mIsRoaming = isRoaming;
        }
    }

    /**
     * Reports the current coarse-grained state of the network.
     * @return the coarse-grained state
     * @deprecated Apps should instead use the
     *             {@link android.net.ConnectivityManager.NetworkCallback} API to
     *             learn about connectivity changes.
     *             {@link ConnectivityManager#registerDefaultNetworkCallback} and
     *             {@link ConnectivityManager#registerNetworkCallback}. These will
     *             give a more accurate picture of the connectivity state of
     *             the device and let apps react more easily and quickly to changes.
     */
    @Deprecated
    public State getState() {
        synchronized (this) {
            return mState;
        }
    }

    /**
     * Reports the current fine-grained state of the network.
     * @return the fine-grained state
     * @deprecated Apps should instead use the
     *             {@link android.net.ConnectivityManager.NetworkCallback} API to
     *             learn about connectivity changes. See
     *             {@link ConnectivityManager#registerDefaultNetworkCallback} and
     *             {@link ConnectivityManager#registerNetworkCallback}. These will
     *             give a more accurate picture of the connectivity state of
     *             the device and let apps react more easily and quickly to changes.
     */
    @Deprecated
    public @NonNull DetailedState getDetailedState() {
        synchronized (this) {
            return mDetailedState;
        }
    }

    /**
     * Sets the fine-grained state of the network.
     * @param detailedState the {@link DetailedState}.
     * @param reason a {@code String} indicating the reason for the state change,
     * if one was supplied. May be {@code null}.
     * @param extraInfo an optional {@code String} providing addditional network state
     * information passed up from the lower networking layers.
     * @deprecated Use {@link NetworkCapabilities} instead.
     * @hide
     */
    @Deprecated
    @UnsupportedAppUsage
    public void setDetailedState(DetailedState detailedState, String reason, String extraInfo) {
        synchronized (this) {
            this.mDetailedState = detailedState;
            this.mState = stateMap.get(detailedState);
            this.mReason = reason;
            this.mExtraInfo = extraInfo;
        }
    }

    /**
     * Set the extraInfo field.
     * @param extraInfo an optional {@code String} providing addditional network state
     * information passed up from the lower networking layers.
     * @deprecated See {@link NetworkInfo#getExtraInfo}.
     * @hide
     */
    @Deprecated
    public void setExtraInfo(String extraInfo) {
        synchronized (this) {
            this.mExtraInfo = extraInfo;
        }
    }

    /**
     * Report the reason an attempt to establish connectivity failed,
     * if one is available.
     * @return the reason for failure, or null if not available
     * @deprecated This method does not have a consistent contract that could make it useful
     *             to callers.
     */
    public String getReason() {
        synchronized (this) {
            return mReason;
        }
    }

    /**
     * Report the extra information about the network state, if any was
     * provided by the lower networking layers.
     * @return the extra information, or null if not available
     * @deprecated Use other services e.g. WifiManager to get additional information passed up from
     *             the lower networking layers.
     */
    @Deprecated
    public String getExtraInfo() {
        synchronized (this) {
            return mExtraInfo;
        }
    }

    @Override
    public String toString() {
        synchronized (this) {
            StringBuilder builder = new StringBuilder("[");
            builder.append("type: ").append(getTypeName()).append("[").append(getSubtypeName()).
            append("], state: ").append(mState).append("/").append(mDetailedState).
            append(", reason: ").append(mReason == null ? "(unspecified)" : mReason).
            append(", extra: ").append(mExtraInfo == null ? "(none)" : mExtraInfo).
            append(", failover: ").append(mIsFailover).
            append(", available: ").append(mIsAvailable).
            append(", roaming: ").append(mIsRoaming).
            append("]");
            return builder.toString();
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        synchronized (this) {
            dest.writeInt(mNetworkType);
            dest.writeInt(mSubtype);
            dest.writeString(mTypeName);
            dest.writeString(mSubtypeName);
            dest.writeString(mState.name());
            dest.writeString(mDetailedState.name());
            dest.writeInt(mIsFailover ? 1 : 0);
            dest.writeInt(mIsAvailable ? 1 : 0);
            dest.writeInt(mIsRoaming ? 1 : 0);
            dest.writeString(mReason);
            dest.writeString(mExtraInfo);
        }
    }

    public static final Creator<NetworkInfo> CREATOR = new Creator<NetworkInfo>() {
        @Override
        public NetworkInfo createFromParcel(Parcel in) {
            int netType = in.readInt();
            int subtype = in.readInt();
            String typeName = in.readString();
            String subtypeName = in.readString();
            NetworkInfo netInfo = new NetworkInfo(netType, subtype, typeName, subtypeName);
            netInfo.mState = State.valueOf(in.readString());
            netInfo.mDetailedState = DetailedState.valueOf(in.readString());
            netInfo.mIsFailover = in.readInt() != 0;
            netInfo.mIsAvailable = in.readInt() != 0;
            netInfo.mIsRoaming = in.readInt() != 0;
            netInfo.mReason = in.readString();
            netInfo.mExtraInfo = in.readString();
            return netInfo;
        }

        @Override
        public NetworkInfo[] newArray(int size) {
            return new NetworkInfo[size];
        }
    };
}
