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
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_ETHERNET;
import static android.net.NetworkCapabilities.TRANSPORT_VPN;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI_AWARE;

import static com.android.server.ConnectivityServiceTestUtils.transportToLegacyType;

import static junit.framework.Assert.assertTrue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import android.annotation.NonNull;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkAgent;
import android.net.NetworkAgentConfig;
import android.net.NetworkCapabilities;
import android.net.NetworkProvider;
import android.net.NetworkScore;
import android.net.NetworkSpecifier;
import android.net.QosFilter;
import android.net.SocketKeepalive;
import android.os.ConditionVariable;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;
import android.util.Range;

import com.android.net.module.util.ArrayTrackRecord;
import com.android.testutils.HandlerUtils;
import com.android.testutils.TestableNetworkCallback;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class NetworkAgentWrapper implements TestableNetworkCallback.HasNetwork {
    private final NetworkCapabilities mNetworkCapabilities;
    private final HandlerThread mHandlerThread;
    private final Context mContext;
    private final String mLogTag;
    private final NetworkAgentConfig mNetworkAgentConfig;

    private final ConditionVariable mDisconnected = new ConditionVariable();
    private final ConditionVariable mPreventReconnectReceived = new ConditionVariable();
    private final AtomicBoolean mConnected = new AtomicBoolean(false);
    private NetworkScore mScore;
    private NetworkAgent mNetworkAgent;
    private int mStartKeepaliveError = SocketKeepalive.ERROR_UNSUPPORTED;
    private int mStopKeepaliveError = SocketKeepalive.NO_KEEPALIVE;
    // Controls how test network agent is going to wait before responding to keepalive
    // start/stop. Useful when simulate KeepaliveTracker is waiting for response from modem.
    private long mKeepaliveResponseDelay = 0L;
    private Integer mExpectedKeepaliveSlot = null;
    private final ArrayTrackRecord<CallbackType>.ReadHead mCallbackHistory =
            new ArrayTrackRecord<CallbackType>().newReadHead();

    public NetworkAgentWrapper(int transport, LinkProperties linkProperties,
            NetworkCapabilities ncTemplate, Context context) throws Exception {
        final int type = transportToLegacyType(transport);
        final String typeName = ConnectivityManager.getNetworkTypeName(type);
        mNetworkCapabilities = (ncTemplate != null) ? ncTemplate : new NetworkCapabilities();
        mNetworkCapabilities.addCapability(NET_CAPABILITY_NOT_SUSPENDED);
        mNetworkCapabilities.addCapability(NET_CAPABILITY_NOT_VCN_MANAGED);
        mNetworkCapabilities.addTransportType(transport);
        switch (transport) {
            case TRANSPORT_ETHERNET:
                mScore = new NetworkScore.Builder().setLegacyInt(70).build();
                break;
            case TRANSPORT_WIFI:
                mScore = new NetworkScore.Builder().setLegacyInt(60).build();
                break;
            case TRANSPORT_CELLULAR:
                mScore = new NetworkScore.Builder().setLegacyInt(50).build();
                break;
            case TRANSPORT_WIFI_AWARE:
                mScore = new NetworkScore.Builder().setLegacyInt(20).build();
                break;
            case TRANSPORT_VPN:
                mNetworkCapabilities.removeCapability(NET_CAPABILITY_NOT_VPN);
                // VPNs deduce the SUSPENDED capability from their underlying networks and there
                // is no public API to let VPN services set it.
                mNetworkCapabilities.removeCapability(NET_CAPABILITY_NOT_SUSPENDED);
                mScore = new NetworkScore.Builder().setLegacyInt(101).build();
                break;
            default:
                throw new UnsupportedOperationException("unimplemented network type");
        }
        mContext = context;
        mLogTag = "Mock-" + typeName;
        mHandlerThread = new HandlerThread(mLogTag);
        mHandlerThread.start();

        // extraInfo is set to "" by default in NetworkAgentConfig.
        final String extraInfo = (transport == TRANSPORT_CELLULAR) ? "internet.apn" : "";
        mNetworkAgentConfig = new NetworkAgentConfig.Builder()
                .setLegacyType(type)
                .setLegacyTypeName(typeName)
                .setLegacyExtraInfo(extraInfo)
                .build();
        mNetworkAgent = makeNetworkAgent(linkProperties, mNetworkAgentConfig);
    }

    protected InstrumentedNetworkAgent makeNetworkAgent(LinkProperties linkProperties,
            final NetworkAgentConfig nac) throws Exception {
        return new InstrumentedNetworkAgent(this, linkProperties, nac);
    }

    public static class InstrumentedNetworkAgent extends NetworkAgent {
        private final NetworkAgentWrapper mWrapper;
        private static final String PROVIDER_NAME = "InstrumentedNetworkAgentProvider";

        public InstrumentedNetworkAgent(NetworkAgentWrapper wrapper, LinkProperties lp,
                NetworkAgentConfig nac) {
            super(wrapper.mContext, wrapper.mHandlerThread.getLooper(), wrapper.mLogTag,
                    wrapper.mNetworkCapabilities, lp, wrapper.mScore, nac,
                    new NetworkProvider(wrapper.mContext, wrapper.mHandlerThread.getLooper(),
                            PROVIDER_NAME));
            mWrapper = wrapper;
            register();
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
            mWrapper.mHandlerThread.getThreadHandler().postDelayed(
                    () -> onSocketKeepaliveEvent(slot, mWrapper.mStartKeepaliveError),
                    mWrapper.mKeepaliveResponseDelay);
        }

        @Override
        public void stopSocketKeepalive(Message msg) {
            final int slot = msg.arg1;
            mWrapper.mHandlerThread.getThreadHandler().postDelayed(
                    () -> onSocketKeepaliveEvent(slot, mWrapper.mStopKeepaliveError),
                    mWrapper.mKeepaliveResponseDelay);
        }

        @Override
        public void onQosCallbackRegistered(final int qosCallbackId,
                final @NonNull QosFilter filter) {
            Log.i(mWrapper.mLogTag, "onQosCallbackRegistered");
            mWrapper.mCallbackHistory.add(
                    new CallbackType.OnQosCallbackRegister(qosCallbackId, filter));
        }

        @Override
        public void onQosCallbackUnregistered(final int qosCallbackId) {
            Log.i(mWrapper.mLogTag, "onQosCallbackUnregistered");
            mWrapper.mCallbackHistory.add(new CallbackType.OnQosCallbackUnregister(qosCallbackId));
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

    public void setScore(@NonNull final NetworkScore score) {
        mScore = score;
        mNetworkAgent.sendNetworkScore(score);
    }

    public void adjustScore(int change) {
        final int newLegacyScore = mScore.getLegacyInt() + change;
        final NetworkScore.Builder builder = new NetworkScore.Builder()
                .setLegacyInt(newLegacyScore);
        if (mNetworkCapabilities.hasTransport(TRANSPORT_WIFI) && newLegacyScore < 50) {
            builder.setExiting(true);
        }
        mScore = builder.build();
        mNetworkAgent.sendNetworkScore(mScore);
    }

    public NetworkScore getScore() {
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

    public void setUids(Set<Range<Integer>> uids) {
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
        if (!mConnected.compareAndSet(false /* expect */, true /* update */)) {
            // compareAndSet returns false when the value couldn't be updated because it did not
            // match the expected value.
            fail("Test NetworkAgents can only be connected once");
        }
        mNetworkAgent.markConnected();
    }

    public void suspend() {
        removeCapability(NET_CAPABILITY_NOT_SUSPENDED);
    }

    public void resume() {
        addCapability(NET_CAPABILITY_NOT_SUSPENDED);
    }

    public void disconnect() {
        mNetworkAgent.unregister();
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

    public void setKeepaliveResponseDelay(long delay) {
        mKeepaliveResponseDelay = delay;
    }

    public void setExpectedKeepaliveSlot(Integer slot) {
        mExpectedKeepaliveSlot = slot;
    }

    public NetworkAgent getNetworkAgent() {
        return mNetworkAgent;
    }

    public NetworkCapabilities getNetworkCapabilities() {
        return mNetworkCapabilities;
    }

    public int getLegacyType() {
        return mNetworkAgentConfig.getLegacyType();
    }

    public String getExtraInfo() {
        return mNetworkAgentConfig.getLegacyExtraInfo();
    }

    public @NonNull ArrayTrackRecord<CallbackType>.ReadHead getCallbackHistory() {
        return mCallbackHistory;
    }

    public void waitForIdle(long timeoutMs) {
        HandlerUtils.waitForIdle(mHandlerThread, timeoutMs);
    }

    abstract static class CallbackType {
        final int mQosCallbackId;

        protected CallbackType(final int qosCallbackId) {
            mQosCallbackId = qosCallbackId;
        }

        static class OnQosCallbackRegister extends CallbackType {
            final QosFilter mFilter;
            OnQosCallbackRegister(final int qosCallbackId, final QosFilter filter) {
                super(qosCallbackId);
                mFilter = filter;
            }

            @Override
            public boolean equals(final Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                final OnQosCallbackRegister that = (OnQosCallbackRegister) o;
                return mQosCallbackId == that.mQosCallbackId
                        && Objects.equals(mFilter, that.mFilter);
            }

            @Override
            public int hashCode() {
                return Objects.hash(mQosCallbackId, mFilter);
            }
        }

        static class OnQosCallbackUnregister extends CallbackType {
            OnQosCallbackUnregister(final int qosCallbackId) {
                super(qosCallbackId);
            }

            @Override
            public boolean equals(final Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                final OnQosCallbackUnregister that = (OnQosCallbackUnregister) o;
                return mQosCallbackId == that.mQosCallbackId;
            }

            @Override
            public int hashCode() {
                return Objects.hash(mQosCallbackId);
            }
        }
    }
}
