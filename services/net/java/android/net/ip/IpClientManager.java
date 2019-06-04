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

import android.annotation.NonNull;
import android.net.NattKeepalivePacketData;
import android.net.ProxyInfo;
import android.net.TcpKeepalivePacketData;
import android.net.shared.ProvisioningConfiguration;
import android.net.util.KeepalivePacketDataUtil;
import android.os.Binder;
import android.os.RemoteException;
import android.util.Log;

/**
 * A convenience wrapper for IpClient.
 *
 * Wraps IIpClient calls, making them a bit more friendly to use. Currently handles:
 * - Clearing calling identity
 * - Ignoring RemoteExceptions
 * - Converting to stable parcelables
 *
 * By design, all methods on IIpClient are asynchronous oneway IPCs and are thus void. All the
 * wrapper methods in this class return a boolean that callers can use to determine whether
 * RemoteException was thrown.
 */
public class IpClientManager {
    @NonNull private final IIpClient mIpClient;
    @NonNull private final String mTag;

    public IpClientManager(@NonNull IIpClient ipClient, @NonNull String tag) {
        mIpClient = ipClient;
        mTag = tag;
    }

    public IpClientManager(@NonNull IIpClient ipClient) {
        this(ipClient, IpClientManager.class.getSimpleName());
    }

    private void log(String s, Throwable e) {
        Log.e(mTag, s, e);
    }

    /**
     * For clients using {@link ProvisioningConfiguration.Builder#withPreDhcpAction()}, must be
     * called after {@link IIpClientCallbacks#onPreDhcpAction} to indicate that DHCP is clear to
     * proceed.
     */
    public boolean completedPreDhcpAction() {
        final long token = Binder.clearCallingIdentity();
        try {
            mIpClient.completedPreDhcpAction();
            return true;
        } catch (RemoteException e) {
            log("Error completing PreDhcpAction", e);
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Confirm the provisioning configuration.
     */
    public boolean confirmConfiguration() {
        final long token = Binder.clearCallingIdentity();
        try {
            mIpClient.confirmConfiguration();
            return true;
        } catch (RemoteException e) {
            log("Error confirming IpClient configuration", e);
            return false;
        }
    }

    /**
     * Indicate that packet filter read is complete.
     */
    public boolean readPacketFilterComplete(byte[] data) {
        final long token = Binder.clearCallingIdentity();
        try {
            mIpClient.readPacketFilterComplete(data);
            return true;
        } catch (RemoteException e) {
            log("Error notifying IpClient of packet filter read", e);
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Shut down this IpClient instance altogether.
     */
    public boolean shutdown() {
        final long token = Binder.clearCallingIdentity();
        try {
            mIpClient.shutdown();
            return true;
        } catch (RemoteException e) {
            log("Error shutting down IpClient", e);
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Start provisioning with the provided parameters.
     */
    public boolean startProvisioning(ProvisioningConfiguration prov) {
        final long token = Binder.clearCallingIdentity();
        try {
            mIpClient.startProvisioning(prov.toStableParcelable());
            return true;
        } catch (RemoteException e) {
            log("Error starting IpClient provisioning", e);
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Stop this IpClient.
     *
     * <p>This does not shut down the StateMachine itself, which is handled by {@link #shutdown()}.
     */
    public boolean stop() {
        final long token = Binder.clearCallingIdentity();
        try {
            mIpClient.stop();
            return true;
        } catch (RemoteException e) {
            log("Error stopping IpClient", e);
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Set the TCP buffer sizes to use.
     *
     * This may be called, repeatedly, at any time before or after a call to
     * #startProvisioning(). The setting is cleared upon calling #stop().
     */
    public boolean setTcpBufferSizes(String tcpBufferSizes) {
        final long token = Binder.clearCallingIdentity();
        try {
            mIpClient.setTcpBufferSizes(tcpBufferSizes);
            return true;
        } catch (RemoteException e) {
            log("Error setting IpClient TCP buffer sizes", e);
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Set the HTTP Proxy configuration to use.
     *
     * This may be called, repeatedly, at any time before or after a call to
     * #startProvisioning(). The setting is cleared upon calling #stop().
     */
    public boolean setHttpProxy(ProxyInfo proxyInfo) {
        final long token = Binder.clearCallingIdentity();
        try {
            mIpClient.setHttpProxy(proxyInfo);
            return true;
        } catch (RemoteException e) {
            log("Error setting IpClient proxy", e);
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Enable or disable the multicast filter.  Attempts to use APF to accomplish the filtering,
     * if not, Callback.setFallbackMulticastFilter() is called.
     */
    public boolean setMulticastFilter(boolean enabled) {
        final long token = Binder.clearCallingIdentity();
        try {
            mIpClient.setMulticastFilter(enabled);
            return true;
        } catch (RemoteException e) {
            log("Error setting multicast filter", e);
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Add a TCP keepalive packet filter before setting up keepalive offload.
     */
    public boolean addKeepalivePacketFilter(int slot, TcpKeepalivePacketData pkt) {
        final long token = Binder.clearCallingIdentity();
        try {
            mIpClient.addKeepalivePacketFilter(slot, pkt.toStableParcelable());
            return true;
        } catch (RemoteException e) {
            log("Error adding Keepalive Packet Filter ", e);
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Add a NAT-T keepalive packet filter before setting up keepalive offload.
     */
    public boolean addKeepalivePacketFilter(int slot, NattKeepalivePacketData pkt) {
        final long token = Binder.clearCallingIdentity();
        try {
            mIpClient.addNattKeepalivePacketFilter(
                    slot, KeepalivePacketDataUtil.toStableParcelable(pkt));
            return true;
        } catch (RemoteException e) {
            log("Error adding Keepalive Packet Filter ", e);
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Remove a keepalive packet filter after stopping keepalive offload.
     */
    public boolean removeKeepalivePacketFilter(int slot) {
        final long token = Binder.clearCallingIdentity();
        try {
            mIpClient.removeKeepalivePacketFilter(slot);
            return true;
        } catch (RemoteException e) {
            log("Error removing Keepalive Packet Filter ", e);
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Set the L2 key and group hint for storing info into the memory store.
     */
    public boolean setL2KeyAndGroupHint(String l2Key, String groupHint) {
        final long token = Binder.clearCallingIdentity();
        try {
            mIpClient.setL2KeyAndGroupHint(l2Key, groupHint);
            return true;
        } catch (RemoteException e) {
            log("Failed setL2KeyAndGroupHint", e);
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }
}
