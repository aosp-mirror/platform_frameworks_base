/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.util.Slog;

/**
 * A dummy data state tracker for use when we don't have a real radio
 * connection.  useful when bringing up a board or when you have network
 * access through other means.
 *
 * {@hide}
 */
public class DummyDataStateTracker implements NetworkStateTracker {

    private static final String TAG = "DummyDataStateTracker";
    private static final boolean DBG = true;
    private static final boolean VDBG = false;

    private NetworkInfo mNetworkInfo;
    private boolean mTeardownRequested = false;
    private Handler mTarget;
    private Context mContext;
    private LinkProperties mLinkProperties;
    private LinkCapabilities mLinkCapabilities;
    private boolean mPrivateDnsRouteSet = false;
    private boolean mDefaultRouteSet = false;

    // DEFAULT and HIPRI are the same connection.  If we're one of these we need to check if
    // the other is also disconnected before we reset sockets
    private boolean mIsDefaultOrHipri = false;

    /**
     * Create a new DummyDataStateTracker
     * @param netType the ConnectivityManager network type
     * @param tag the name of this network
     */
    public DummyDataStateTracker(int netType, String tag) {
        mNetworkInfo = new NetworkInfo(netType);
    }

    /**
     * Begin monitoring data connectivity.
     *
     * @param context is the current Android context
     * @param target is the Handler to which to return the events.
     */
    public void startMonitoring(Context context, Handler target) {
        mTarget = target;
        mContext = context;
    }

    public boolean isPrivateDnsRouteSet() {
        return mPrivateDnsRouteSet;
    }

    public void privateDnsRouteSet(boolean enabled) {
        mPrivateDnsRouteSet = enabled;
    }

    public NetworkInfo getNetworkInfo() {
        return mNetworkInfo;
    }

    public boolean isDefaultRouteSet() {
        return mDefaultRouteSet;
    }

    public void defaultRouteSet(boolean enabled) {
        mDefaultRouteSet = enabled;
    }

    /**
     * This is not implemented.
     */
    public void releaseWakeLock() {
    }

    /**
     * Report whether data connectivity is possible.
     */
    public boolean isAvailable() {
        return true;
    }

    /**
     * Return the system properties name associated with the tcp buffer sizes
     * for this network.
     */
    public String getTcpBufferSizesPropName() {
        return "net.tcp.buffersize.unknown";
    }

    /**
     * Tear down mobile data connectivity, i.e., disable the ability to create
     * mobile data connections.
     * TODO - make async and return nothing?
     */
    public boolean teardown() {
        setDetailedState(NetworkInfo.DetailedState.DISCONNECTING, "disabled", null);
        setDetailedState(NetworkInfo.DetailedState.DISCONNECTED, "disabled", null);
        return true;
    }

    /**
     * Record the detailed state of a network, and if it is a
     * change from the previous state, send a notification to
     * any listeners.
     * @param state the new {@code DetailedState}
     * @param reason a {@code String} indicating a reason for the state change,
     * if one was supplied. May be {@code null}.
     * @param extraInfo optional {@code String} providing extra information about the state change
     */
    private void setDetailedState(NetworkInfo.DetailedState state, String reason,
            String extraInfo) {
        if (DBG) log("setDetailed state, old ="
                + mNetworkInfo.getDetailedState() + " and new state=" + state);
        mNetworkInfo.setDetailedState(state, reason, extraInfo);
        Message msg = mTarget.obtainMessage(EVENT_STATE_CHANGED, mNetworkInfo);
        msg.sendToTarget();
    }

    public void setTeardownRequested(boolean isRequested) {
        mTeardownRequested = isRequested;
    }

    public boolean isTeardownRequested() {
        return mTeardownRequested;
    }

    /**
     * Re-enable mobile data connectivity after a {@link #teardown()}.
     * TODO - make async and always get a notification?
     */
    public boolean reconnect() {
        setDetailedState(NetworkInfo.DetailedState.CONNECTING, "enabled", null);
        setDetailedState(NetworkInfo.DetailedState.CONNECTED, "enabled", null);
        setTeardownRequested(false);
        return true;
    }

    /**
     * Turn on or off the mobile radio. No connectivity will be possible while the
     * radio is off. The operation is a no-op if the radio is already in the desired state.
     * @param turnOn {@code true} if the radio should be turned on, {@code false} if
     */
    public boolean setRadio(boolean turnOn) {
        return true;
    }

    @Override
    public void setUserDataEnable(boolean enabled) {
        // ignored
    }

    @Override
    public void setPolicyDataEnable(boolean enabled) {
        // ignored
    }

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer("Dummy data state: none, dummy!");
        return sb.toString();
    }

    /**
     * @see android.net.NetworkStateTracker#getLinkProperties()
     */
    public LinkProperties getLinkProperties() {
        return new LinkProperties(mLinkProperties);
    }

    /**
     * @see android.net.NetworkStateTracker#getLinkCapabilities()
     */
    public LinkCapabilities getLinkCapabilities() {
        return new LinkCapabilities(mLinkCapabilities);
    }

    public void setDependencyMet(boolean met) {
        // not supported on this network
    }

    static private void log(String s) {
        Slog.d(TAG, s);
    }

    static private void loge(String s) {
        Slog.e(TAG, s);
    }
}
