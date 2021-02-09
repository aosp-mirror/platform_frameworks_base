/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server;

import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_ETHERNET;
import static android.net.NetworkCapabilities.TRANSPORT_VPN;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI_AWARE;

import static com.android.server.ConnectivityServiceTestUtilsKt.transportToLegacyType;

import static junit.framework.Assert.assertTrue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkAgent;
import android.net.NetworkAgentConfig;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkProvider;
import android.net.NetworkSpecifier;
import android.net.SocketKeepalive;
import android.net.UidRange;
import android.os.ConditionVariable;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;

import com.android.server.connectivity.ConnectivityConstants;
import com.android.testutils.HandlerUtilsKt;
import com.android.testutils.TestableNetworkCallback;

import java.util.Set;

public class NetworkAgentWrapper implements TestableNetworkCallback.HasNetwork {
    private final NetworkInfo mNetworkInfo;
    private final NetworkCapabilities mNetworkCapabilities;
    private final HandlerThread mHandlerThread;
    private final Context mContext;
    private final String mLogTag;

    private final ConditionVariable mDisconnected = new ConditionVariable();
    private final ConditionVariable mPreventReconnectReceived = new ConditionVariable();
    private int mScore;
    private NetworkAgent mNetworkAgent;
    private int mStartKeepaliveError = SocketKeepalive.ERROR_UNSUPPORTED;
    private int mStopKeepaliveError = SocketKeepalive.NO_KEEPALIVE;
    private Integer mExpectedKeepaliveSlot = null;

    public NetworkAgentWrapper(int transport, LinkProperties linkProperties,
            NetworkCapabilities ncTemplate, Context context) throws Exception {
        final int type = transportToLegacyType(transport);
        final String typeName = ConnectivityManager.getNetworkTypeName(type);
        mNetworkInfo = new NetworkInfo(type, 0, typeName, "Mock");
        mNetworkCapabilities = (ncTemplate != null) ? ncTemplate : new NetworkCapabilities();
        mNetworkCapabilities.addCapability(NET_CAPABILITY_NOT_SUSPENDED);
        mNetworkCapabilities.addTransportType(transport);
        switch (transport) {
            case TRANSPORT_ETHERNET:
                mScore = 70;
                break;
            case TRANSPORT_WIFI:
                mScore = 60;
                break;
            case TRANSPORT_CELLULAR:
                mScore = 50;
                break;
            case TRANSPORT_WIFI_AWARE:
                mScore = 20;
                break;
            case TRANSPORT_VPN:
                mNetworkCapabilities.removeCapability(NET_CAPABILITY_NOT_VPN);
                // VPNs deduce the SUSPENDED capability from their underlying networks and there
                // is no public API to let VPN services set it.
                mNetworkCapabilities.removeCapability(NET_CAPABILITY_NOT_SUSPENDED);
                mScore = ConnectivityConstants.VPN_DEFAULT_SCORE;
                break;
            default:
                throw new UnsupportedOperationException("unimplemented network type");
        }
        mContext = context;
        mLogTag = "Mock-" + typeName;
        mHandlerThread = new HandlerThread(mLogTag);
        mHandlerThread.start();

        mNetworkAgent = makeNetworkAgent(linkProperties);
    }

    protected InstrumentedNetworkAgent makeNetworkAgent(LinkProperties linkProperties)
            throws Exception {
        return new InstrumentedNetworkAgent(this, linkProperties);
    }

    public static class InstrumentedNetworkAgent extends NetworkAgent {
        private final NetworkAgentWrapper mWrapper;

        public InstrumentedNetworkAgent(NetworkAgentWrapper wrapper, LinkProperties lp) {
            super(wrapper.mHandlerThread.getLooper(), wrapper.mContext, wrapper.mLogTag,
                    wrapper.mNetworkInfo, wrapper.mNetworkCapabilities, lp, wrapper.mScore,
                    new NetworkAgentConfig(), NetworkProvider.ID_NONE);
            mWrapper = wrapper;
        }

        @Override
        public void unwanted() {
            mWrapper.mDisconnected.open();
        }

        @Override
        public void startSocketKeepalive(Message msg) {
            int slot = msg.arg1;
            if (mWrapper.mExpectedKeepaliveSlot != null) {
                assertEquals((int) mWrapper.mExpectedKeepaliveSlot, slot);
            }
            onSocketKeepaliveEvent(slot, mWrapper.mStartKeepaliveError);
        }

        @Override
        public void stopSocketKeepalive(Message msg) {
            onSocketKeepaliveEvent(msg.arg1, mWrapper.mStopKeepaliveError);
        }

        @Override
        protected void preventAutomaticReconnect() {
            mWrapper.mPreventReconnectReceived.open();
        }

        @Override
        protected void addKeepalivePacketFilter(Message msg) {
            Log.i(mWrapper.mLogTag, "Add keepalive packet filter.");
        }

        @Override
        protected void removeKeepalivePacketFilter(Message msg) {
            Log.i(mWrapper.mLogTag, "Remove keepalive packet filter.");
        }
    }

    public void adjustScore(int change) {
        mScore += change;
        mNetworkAgent.sendNetworkScore(mScore);
    }

    public int getScore() {
        return mScore;
    }

    public void explicitlySelected(boolean explicitlySelected, boolean acceptUnvalidated) {
        mNetworkAgent.explicitlySelected(explicitlySelected, acceptUnvalidated);
    }

    public void addCapability(int capability) {
        mNetworkCapabilities.addCapability(capability);
        mNetworkAgent.sendNetworkCapabilities(mNetworkCapabilities);
    }

    public void removeCapability(int capability) {
        mNetworkCapabilities.removeCapability(capability);
        mNetworkAgent.sendNetworkCapabilities(mNetworkCapabilities);
    }

    public void setUids(Set<UidRange> uids) {
        mNetworkCapabilities.setUids(uids);
        mNetworkAgent.sendNetworkCapabilities(mNetworkCapabilities);
    }

    public void setSignalStrength(int signalStrength) {
        mNetworkCapabilities.setSignalStrength(signalStrength);
        mNetworkAgent.sendNetworkCapabilities(mNetworkCapabilities);
    }

    public void setNetworkSpecifier(NetworkSpecifier networkSpecifier) {
        mNetworkCapabilities.setNetworkSpecifier(networkSpecifier);
        mNetworkAgent.sendNetworkCapabilities(mNetworkCapabilities);
    }

    public void setNetworkCapabilities(NetworkCapabilities nc, boolean sendToConnectivityService) {
        mNetworkCapabilities.set(nc);
        if (sendToConnectivityService) {
            mNetworkAgent.sendNetworkCapabilities(mNetworkCapabilities);
        }
    }

    public void connect() {
        assertNotEquals("MockNetworkAgents can only be connected once",
                getNetworkInfo().getDetailedState(), NetworkInfo.DetailedState.CONNECTED);
        mNetworkInfo.setDetailedState(NetworkInfo.DetailedState.CONNECTED, null, null);
        mNetworkAgent.sendNetworkInfo(mNetworkInfo);
    }

    public void suspend() {
        removeCapability(NET_CAPABILITY_NOT_SUSPENDED);
    }

    public void resume() {
        addCapability(NET_CAPABILITY_NOT_SUSPENDED);
    }

    public void disconnect() {
        mNetworkInfo.setDetailedState(NetworkInfo.DetailedState.DISCONNECTED, null, null);
        mNetworkAgent.sendNetworkInfo(mNetworkInfo);
    }

    @Override
    public Network getNetwork() {
        return mNetworkAgent.getNetwork();
    }

    public void expectPreventReconnectReceived(long timeoutMs) {
        assertTrue(mPreventReconnectReceived.block(timeoutMs));
    }

    public void expectDisconnected(long timeoutMs) {
        assertTrue(mDisconnected.block(timeoutMs));
    }

    public void sendLinkProperties(LinkProperties lp) {
        mNetworkAgent.sendLinkProperties(lp);
    }

    public void setStartKeepaliveEvent(int reason) {
        mStartKeepaliveError = reason;
    }

    public void setStopKeepaliveEvent(int reason) {
        mStopKeepaliveError = reason;
    }

    public void setExpectedKeepaliveSlot(Integer slot) {
        mExpectedKeepaliveSlot = slot;
    }

    public NetworkAgent getNetworkAgent() {
        return mNetworkAgent;
    }

    public NetworkInfo getNetworkInfo() {
        return mNetworkInfo;
    }

    public NetworkCapabilities getNetworkCapabilities() {
        return mNetworkCapabilities;
    }

    public void waitForIdle(long timeoutMs) {
        HandlerUtilsKt.waitForIdle(mHandlerThread, timeoutMs);
    }
}
