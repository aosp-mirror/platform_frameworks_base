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

package android.net.ip;

import static android.net.shared.LinkPropertiesParcelableUtil.toStableParcelable;

import android.content.Context;
import android.net.LinkProperties;
import android.net.Network;
import android.net.ProxyInfo;
import android.net.StaticIpConfiguration;
import android.net.apf.ApfCapabilities;
import android.os.ConditionVariable;
import android.os.INetworkManagementService;
import android.os.RemoteException;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Proxy for the IpClient in the NetworkStack. To be removed once clients are migrated.
 * @hide
 */
public class IpClient {
    private static final String TAG = IpClient.class.getSimpleName();
    private static final int IPCLIENT_BLOCK_TIMEOUT_MS = 10_000;

    public static final String DUMP_ARG = "ipclient";

    private final ConditionVariable mIpClientCv;
    private final ConditionVariable mShutdownCv;

    private volatile IIpClient mIpClient;

    /**
     * @see IpClientCallbacks
     */
    public static class Callback extends IpClientCallbacks {}

    /**
     * IpClient callback that allows clients to block until provisioning is complete.
     */
    public static class WaitForProvisioningCallback extends Callback {
        private final ConditionVariable mCV = new ConditionVariable();
        private LinkProperties mCallbackLinkProperties;

        /**
         * Block until either {@link #onProvisioningSuccess(LinkProperties)} or
         * {@link #onProvisioningFailure(LinkProperties)} is called.
         */
        public LinkProperties waitForProvisioning() {
            mCV.block();
            return mCallbackLinkProperties;
        }

        @Override
        public void onProvisioningSuccess(LinkProperties newLp) {
            mCallbackLinkProperties = newLp;
            mCV.open();
        }

        @Override
        public void onProvisioningFailure(LinkProperties newLp) {
            mCallbackLinkProperties = null;
            mCV.open();
        }
    }

    private class CallbackImpl extends IpClientUtil.IpClientCallbacksProxy {
        /**
         * Create a new IpClientCallbacksProxy.
         */
        CallbackImpl(IpClientCallbacks cb) {
            super(cb);
        }

        @Override
        public void onIpClientCreated(IIpClient ipClient) {
            mIpClient = ipClient;
            mIpClientCv.open();
            super.onIpClientCreated(ipClient);
        }

        @Override
        public void onQuit() {
            mShutdownCv.open();
            super.onQuit();
        }
    }

    /**
     * Create a new IpClient.
     */
    public IpClient(Context context, String iface, Callback callback) {
        mIpClientCv = new ConditionVariable(false);
        mShutdownCv = new ConditionVariable(false);

        IpClientUtil.makeIpClient(context, iface, new CallbackImpl(callback));
    }

    /**
     * @see IpClient#IpClient(Context, String, IpClient.Callback)
     */
    public IpClient(Context context, String iface, Callback callback,
            INetworkManagementService nms) {
        this(context, iface, callback);
    }

    private interface IpClientAction {
        void useIpClient(IIpClient ipClient) throws RemoteException;
    }

    private void doWithIpClient(IpClientAction action) {
        mIpClientCv.block(IPCLIENT_BLOCK_TIMEOUT_MS);
        try {
            action.useIpClient(mIpClient);
        } catch (RemoteException e) {
            Log.e(TAG, "Error communicating with IpClient", e);
        }
    }

    /**
     * Notify IpClient that PreDhcpAction is completed.
     */
    public void completedPreDhcpAction() {
        doWithIpClient(c -> c.completedPreDhcpAction());
    }

    /**
     * Confirm the provisioning configuration.
     */
    public void confirmConfiguration() {
        doWithIpClient(c -> c.confirmConfiguration());
    }

    /**
     * Notify IpClient that packet filter read is complete.
     */
    public void readPacketFilterComplete(byte[] data) {
        doWithIpClient(c -> c.readPacketFilterComplete(data));
    }

    /**
     * Shutdown the IpClient altogether.
     */
    public void shutdown() {
        doWithIpClient(c -> c.shutdown());
    }

    /**
     * Start the IpClient provisioning.
     */
    public void startProvisioning(ProvisioningConfiguration config) {
        doWithIpClient(c -> c.startProvisioning(config.toStableParcelable()));
    }

    /**
     * Stop the IpClient.
     */
    public void stop() {
        doWithIpClient(c -> c.stop());
    }

    /**
     * Set the IpClient TCP buffer sizes.
     */
    public void setTcpBufferSizes(String tcpBufferSizes) {
        doWithIpClient(c -> c.setTcpBufferSizes(tcpBufferSizes));
    }

    /**
     * Set the IpClient HTTP proxy.
     */
    public void setHttpProxy(ProxyInfo proxyInfo) {
        doWithIpClient(c -> c.setHttpProxy(toStableParcelable(proxyInfo)));
    }

    /**
     * Set the IpClient multicast filter.
     */
    public void setMulticastFilter(boolean enabled) {
        doWithIpClient(c -> c.setMulticastFilter(enabled));
    }

    /**
     * Dump IpClient logs.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        doWithIpClient(c -> IpClientUtil.dumpIpClient(c, fd, pw, args));
    }

    /**
     * Block until IpClient shutdown.
     */
    public void awaitShutdown() {
        mShutdownCv.block(IPCLIENT_BLOCK_TIMEOUT_MS);
    }

    /**
     * Create a new ProvisioningConfiguration.
     */
    public static ProvisioningConfiguration.Builder buildProvisioningConfiguration() {
        return new ProvisioningConfiguration.Builder();
    }

    /**
     * TODO: remove after migrating clients to use the shared configuration class directly.
     * @see android.net.shared.ProvisioningConfiguration
     */
    public static class ProvisioningConfiguration
            extends android.net.shared.ProvisioningConfiguration {
        public ProvisioningConfiguration(android.net.shared.ProvisioningConfiguration other) {
            super(other);
        }

        /**
         * @see android.net.shared.ProvisioningConfiguration.Builder
         */
        public static class Builder extends android.net.shared.ProvisioningConfiguration.Builder {
            // Override all methods to have a return type matching this Builder
            @Override
            public Builder withoutIPv4() {
                super.withoutIPv4();
                return this;
            }

            @Override
            public Builder withoutIPv6() {
                super.withoutIPv6();
                return this;
            }

            @Override
            public Builder withoutMultinetworkPolicyTracker() {
                super.withoutMultinetworkPolicyTracker();
                return this;
            }

            @Override
            public Builder withoutIpReachabilityMonitor() {
                super.withoutIpReachabilityMonitor();
                return this;
            }

            @Override
            public Builder withPreDhcpAction() {
                super.withPreDhcpAction();
                return this;
            }

            @Override
            public Builder withPreDhcpAction(int dhcpActionTimeoutMs) {
                super.withPreDhcpAction(dhcpActionTimeoutMs);
                return this;
            }

            @Override
            public Builder withStaticConfiguration(StaticIpConfiguration staticConfig) {
                super.withStaticConfiguration(staticConfig);
                return this;
            }

            @Override
            public Builder withApfCapabilities(ApfCapabilities apfCapabilities) {
                super.withApfCapabilities(apfCapabilities);
                return this;
            }

            @Override
            public Builder withProvisioningTimeoutMs(int timeoutMs) {
                super.withProvisioningTimeoutMs(timeoutMs);
                return this;
            }

            @Override
            public Builder withRandomMacAddress() {
                super.withRandomMacAddress();
                return this;
            }

            @Override
            public Builder withStableMacAddress() {
                super.withStableMacAddress();
                return this;
            }

            @Override
            public Builder withNetwork(Network network) {
                super.withNetwork(network);
                return this;
            }

            @Override
            public Builder withDisplayName(String displayName) {
                super.withDisplayName(displayName);
                return this;
            }

            @Override
            public ProvisioningConfiguration build() {
                return new ProvisioningConfiguration(mConfig);
            }
        }
    }
}
