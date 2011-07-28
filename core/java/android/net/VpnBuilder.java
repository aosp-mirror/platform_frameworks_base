/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceManager;

import com.android.internal.net.VpnConfig;

import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.DatagramSocket;
import java.net.Socket;
import java.util.ArrayList;

/**
 * VpnBuilder is a framework which enables applications to build their
 * own VPN solutions. In general, it creates a virtual network interface,
 * configures addresses and routing rules, and returns a file descriptor
 * to the application. Each read from the descriptor retrieves an outgoing
 * packet which was routed to the interface. Each write to the descriptor
 * injects an incoming packet just like it was received from the interface.
 * The framework is running on Internet Protocol (IP), so packets are
 * always started with IP headers. The application then completes a VPN
 * connection by processing and exchanging packets with a remote server
 * over a secured tunnel.
 *
 * <p>Letting applications intercept packets raises huge security concerns.
 * Besides, a VPN application can easily break the network, and two of them
 * may conflict with each other. The framework takes several actions to
 * address these issues. Here are some key points:
 * <ul>
 *   <li>User action is required to create a VPN connection.</li>
 *   <li>There can be only one VPN connection running at the same time. The
 *       existing interface is deactivated when a new one is created.</li>
 *   <li>A system-managed notification is shown during the lifetime of a
 *       VPN connection.</li>
 *   <li>A system-managed dialog gives the information of the current VPN
 *       connection. It also provides a button to disconnect.</li>
 *   <li>The network is restored automatically when the file descriptor is
 *       closed. It also covers the cases when a VPN application is crashed
 *       or killed by the system.</li>
 * </ul>
 *
 * <p>There are two primary methods in this class: {@link #prepare} and
 * {@link #establish}. The former deals with the user action and stops
 * the existing VPN connection created by another application. The latter
 * creates a VPN interface using the parameters supplied to this builder.
 * An application must call {@link #prepare} to grant the right to create
 * an interface, and it can be revoked at any time by another application.
 * The application got revoked is notified by an {@link #ACTION_VPN_REVOKED}
 * broadcast. Here are the general steps to create a VPN connection:
 * <ol>
 *   <li>When the user press the button to connect, call {@link #prepare}
 *       and launch the intent if necessary.</li>
 *   <li>Register a receiver for {@link #ACTION_VPN_REVOKED} broadcasts.
 *   <li>Connect to the remote server and negotiate the network parameters
 *       of the VPN connection.</li>
 *   <li>Use those parameters to configure a VpnBuilder and create a VPN
 *       interface by calling {@link #establish}.</li>
 *   <li>Start processing packets between the returned file descriptor and
 *       the VPN tunnel.</li>
 *   <li>When an {@link #ACTION_VPN_REVOKED} broadcast is received, the
 *       interface is already deactivated by the framework. Close the file
 *       descriptor and shut down the VPN tunnel gracefully.
 * </ol>
 * Methods in this class can be used in activities and services. However,
 * the intent returned from {@link #prepare} must be launched from an
 * activity. The broadcast receiver can be registered at any time, but doing
 * it before calling {@link #establish} effectively avoids race conditions.
 *
 * <p class="note">Using this class requires
 * {@link android.Manifest.permission#VPN} permission.
 */
public class VpnBuilder {

    /**
     * Broadcast intent action indicating that the VPN application has been
     * revoked. This can be only received by the target application on the
     * receiver explicitly registered using {@link Context#registerReceiver}.
     *
     * <p>This is a protected intent that can only be sent by the system.
     */
    public static final String ACTION_VPN_REVOKED = VpnConfig.ACTION_VPN_REVOKED;

    /**
     * Use IConnectivityManager instead since those methods are hidden and
     * not available in ConnectivityManager.
     */
    private static IConnectivityManager getService() {
        return IConnectivityManager.Stub.asInterface(
                ServiceManager.getService(Context.CONNECTIVITY_SERVICE));
    }

    /**
     * Prepare to establish a VPN connection. This method returns {@code null}
     * if the VPN application is already prepared. Otherwise, it returns an
     * {@link Intent} to a system activity. The application should launch the
     * activity using {@link Activity#startActivityForResult} to get itself
     * prepared. The activity may pop up a dialog to require user action, and
     * the result will come back to the application through its
     * {@link Activity#onActivityResult}. The application becomes prepared if
     * the result is {@link Activity#RESULT_OK}, and it is granted to create a
     * VPN interface by calling {@link #establish}.
     *
     * <p>Only one application can be granted at the same time. The right
     * is revoked when another application is granted. The application
     * losing the right will be notified by an {@link #ACTION_VPN_REVOKED}
     * broadcast, and its VPN interface will be deactivated by the system.
     * The application should then notify the remote server and disconnect
     * gracefully. Unless the application becomes prepared again, subsequent
     * calls to {@link #establish} will return {@code null}.
     *
     * @see #establish
     * @see #ACTION_VPN_REVOKED
     */
    public static Intent prepare(Context context) {
        try {
            if (getService().prepareVpn(context.getPackageName(), null)) {
                return null;
            }
        } catch (RemoteException e) {
            // ignore
        }
        return VpnConfig.getIntentForConfirmation();
    }

    private VpnConfig mConfig = new VpnConfig();
    private StringBuilder mAddresses = new StringBuilder();
    private StringBuilder mRoutes = new StringBuilder();

    /**
     * Set the name of this session. It will be displayed in system-managed
     * dialogs and notifications. This is recommended not required.
     */
    public VpnBuilder setSession(String session) {
        mConfig.session = session;
        return this;
    }

    /**
     * Set the {@link PendingIntent} to an activity for users to configure
     * the VPN connection. If it is not set, the button to configure will
     * not be shown in system-managed dialogs.
     */
    public VpnBuilder setConfigureIntent(PendingIntent intent) {
        mConfig.configureIntent = intent;
        return this;
    }

    /**
     * Set the maximum transmission unit (MTU) of the VPN interface. If it
     * is not set, the default value in the operating system will be used.
     *
     * @throws IllegalArgumentException if the value is not positive.
     */
    public VpnBuilder setMtu(int mtu) {
        if (mtu <= 0) {
            throw new IllegalArgumentException("Bad mtu");
        }
        mConfig.mtu = mtu;
        return this;
    }

    /**
     * Private method to validate address and prefixLength.
     */
    private static void check(InetAddress address, int prefixLength) {
        if (address.isLoopbackAddress()) {
            throw new IllegalArgumentException("Bad address");
        }
        if (address instanceof Inet4Address) {
            if (prefixLength < 0 || prefixLength > 32) {
                throw new IllegalArgumentException("Bad prefixLength");
            }
        } else if (address instanceof Inet6Address) {
            if (prefixLength < 0 || prefixLength > 128) {
                throw new IllegalArgumentException("Bad prefixLength");
            }
        } else {
            throw new IllegalArgumentException("Unsupported family");
        }
    }

    /**
     * Convenience method to add a network address to the VPN interface
     * using a numeric address string. See {@link InetAddress} for the
     * definitions of numeric address formats.
     *
     * @throws IllegalArgumentException if the address is invalid.
     * @see #addAddress(InetAddress, int)
     */
    public VpnBuilder addAddress(String address, int prefixLength) {
        return addAddress(InetAddress.parseNumericAddress(address), prefixLength);
    }

    /**
     * Add a network address to the VPN interface. Both IPv4 and IPv6
     * addresses are supported. At least one address must be set before
     * calling {@link #establish}.
     *
     * @throws IllegalArgumentException if the address is invalid.
     */
    public VpnBuilder addAddress(InetAddress address, int prefixLength) {
        check(address, prefixLength);

        if (address.isAnyLocalAddress()) {
            throw new IllegalArgumentException("Bad address");
        }

        mAddresses.append(String.format(" %s/%d", address.getHostAddress(), prefixLength));
        return this;
    }

    /**
     * Convenience method to add a network route to the VPN interface
     * using a numeric address string. See {@link InetAddress} for the
     * definitions of numeric address formats.
     *
     * @see #addRoute(InetAddress, int)
     * @throws IllegalArgumentException if the route is invalid.
     */
    public VpnBuilder addRoute(String address, int prefixLength) {
        return addRoute(InetAddress.parseNumericAddress(address), prefixLength);
    }

    /**
     * Add a network route to the VPN interface. Both IPv4 and IPv6
     * routes are supported.
     *
     * @throws IllegalArgumentException if the route is invalid.
     */
    public VpnBuilder addRoute(InetAddress address, int prefixLength) {
        check(address, prefixLength);

        int offset = prefixLength / 8;
        byte[] bytes = address.getAddress();
        if (offset < bytes.length) {
            if ((byte)(bytes[offset] << (prefixLength % 8)) != 0) {
                throw new IllegalArgumentException("Bad address");
            }
            while (++offset < bytes.length) {
                if (bytes[offset] != 0) {
                    throw new IllegalArgumentException("Bad address");
                }
            }
        }

        mRoutes.append(String.format(" %s/%d", address.getHostAddress(), prefixLength));
        return this;
    }

    /**
     * Convenience method to add a DNS server to the VPN connection
     * using a numeric address string. See {@link InetAddress} for the
     * definitions of numeric address formats.
     *
     * @throws IllegalArgumentException if the address is invalid.
     * @see #addDnsServer(InetAddress)
     */
    public VpnBuilder addDnsServer(String address) {
        return addDnsServer(InetAddress.parseNumericAddress(address));
    }

    /**
     * Add a DNS server to the VPN connection. Both IPv4 and IPv6
     * addresses are supported. If none is set, the DNS servers of
     * the default network will be used.
     *
     * @throws IllegalArgumentException if the address is invalid.
     */
    public VpnBuilder addDnsServer(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress()) {
            throw new IllegalArgumentException("Bad address");
        }
        if (mConfig.dnsServers == null) {
            mConfig.dnsServers = new ArrayList<String>();
        }
        mConfig.dnsServers.add(address.getHostAddress());
        return this;
    }

    /**
     * Add a search domain to the DNS resolver.
     */
    public VpnBuilder addSearchDomain(String domain) {
        if (mConfig.searchDomains == null) {
            mConfig.searchDomains = new ArrayList<String>();
        }
        mConfig.searchDomains.add(domain);
        return this;
    }

    /**
     * Create a VPN interface using the parameters supplied to this builder.
     * The interface works on IP packets, and a file descriptor is returned
     * for the application to access them. Each read retrieves an outgoing
     * packet which was routed to the interface. Each write injects an
     * incoming packet just like it was received from the interface. The file
     * descriptor is put into non-blocking mode by default to avoid blocking
     * Java threads. To use the file descriptor completely in native space,
     * see {@link ParcelFileDescriptor#detachFd()}. The application MUST
     * close the file descriptor when the VPN connection is terminated. The
     * VPN interface will be removed and the network will be restored by the
     * framework automatically.
     *
     * <p>To avoid conflicts, there can be only one active VPN interface at
     * the same time. Usually network parameters are never changed during the
     * lifetime of a VPN connection. It is also common for an application to
     * create a new file descriptor after closing the previous one. However,
     * it is rare but not impossible to have two interfaces while performing a
     * seamless handover. In this case, the old interface will be deactivated
     * when the new one is configured successfully. Both file descriptors are
     * valid but now outgoing packets will be routed to the new interface.
     * Therefore, after draining the old file descriptor, the application MUST
     * close it and start using the new file descriptor. If the new interface
     * cannot be created, the existing interface and its file descriptor remain
     * untouched.
     *
     * <p>An exception will be thrown if the interface cannot be created for
     * any reason. However, this method returns {@code null} if the application
     * is not prepared or is revoked by another application. This helps solve
     * possible race conditions while handling {@link #ACTION_VPN_REVOKED}
     * broadcasts.
     *
     * @return {@link ParcelFileDescriptor} of the VPN interface, or
     *         {@code null} if the application is not prepared.
     * @throws IllegalArgumentException if a parameter is not accepted by the
     *         operating system.
     * @throws IllegalStateException if a parameter cannot be applied by the
     *         operating system.
     * @see #prepare
     */
    public ParcelFileDescriptor establish() {
        mConfig.addresses = mAddresses.toString();
        mConfig.routes = mRoutes.toString();

        try {
            return getService().establishVpn(mConfig);
        } catch (RemoteException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Protect a socket from VPN connections. The socket will be bound to the
     * current default network interface, so its traffic will not be forwarded
     * through VPN. This method is useful if some connections need to be kept
     * outside of VPN. For example, a VPN tunnel should protect itself if its
     * destination is covered by VPN routes. Otherwise its outgoing packets
     * will be sent back to the VPN interface and cause an infinite loop.
     *
     * <p>The socket is NOT closed by this method.
     *
     * @return {@code true} on success.
     */
    public static boolean protect(int socket) {
        ParcelFileDescriptor dup = null;
        try {
            dup = ParcelFileDescriptor.fromFd(socket);
            return getService().protectVpn(dup);
        } catch (Exception e) {
            return false;
        } finally {
            try {
                dup.close();
            } catch (Exception e) {
                // ignore
            }
        }
    }

    /**
     * Protect a {@link Socket} from VPN connections.
     *
     * @return {@code true} on success.
     * @see #protect(int)
     */
    public static boolean protect(Socket socket) {
        return protect(socket.getFileDescriptor$().getInt$());
    }

    /**
     * Protect a {@link DatagramSocket} from VPN connections.
     *
     * @return {@code true} on success.
     * @see #protect(int)
     */
    public static boolean protect(DatagramSocket socket) {
        return protect(socket.getFileDescriptor$().getInt$());
    }
}
