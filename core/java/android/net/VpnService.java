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
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceManager;

import com.android.internal.net.VpnConfig;

import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * VpnService is a base class for applications to extend and build their
 * own VPN solutions. In general, it creates a virtual network interface,
 * configures addresses and routing rules, and returns a file descriptor
 * to the application. Each read from the descriptor retrieves an outgoing
 * packet which was routed to the interface. Each write to the descriptor
 * injects an incoming packet just like it was received from the interface.
 * The interface is running on Internet Protocol (IP), so packets are
 * always started with IP headers. The application then completes a VPN
 * connection by processing and exchanging packets with the remote server
 * over a tunnel.
 *
 * <p>Letting applications intercept packets raises huge security concerns.
 * A VPN application can easily break the network. Besides, two of them may
 * conflict with each other. The system takes several actions to address
 * these issues. Here are some key points:
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
 * {@link Builder#establish}. The former deals with user action and stops
 * the VPN connection created by another application. The latter creates
 * a VPN interface using the parameters supplied to the {@link Builder}.
 * An application must call {@link #prepare} to grant the right to use
 * other methods in this class, and the right can be revoked at any time.
 * Here are the general steps to create a VPN connection:
 * <ol>
 *   <li>When the user press the button to connect, call {@link #prepare}
 *       and launch the returned intent.</li>
 *   <li>When the application becomes prepared, start the service.</li>
 *   <li>Create a tunnel to the remote server and negotiate the network
 *       parameters for the VPN connection.</li>
 *   <li>Supply those parameters to a {@link Builder} and create a VPN
 *       interface by calling {@link Builder#establish}.</li>
 *   <li>Process and exchange packets between the tunnel and the returned
 *       file descriptor.</li>
 *   <li>When {@link #onRevoke} is invoked, close the file descriptor and
 *       shut down the tunnel gracefully.</li>
 * </ol>
 *
 * <p>Services extended this class need to be declared with appropriate
 * permission and intent filter. Their access must be secured by
 * {@link android.Manifest.permission#BIND_VPN_SERVICE} permission, and
 * their intent filter must match {@link #SERVICE_INTERFACE} action. Here
 * is an example of declaring a VPN service in {@code AndroidManifest.xml}:
 * <pre>
 * &lt;service android:name=".ExampleVpnService"
 *         android:permission="android.permission.BIND_VPN_SERVICE"&gt;
 *     &lt;intent-filter&gt;
 *         &lt;action android:name="android.net.VpnService"/&gt;
 *     &lt;/intent-filter&gt;
 * &lt;/service&gt;</pre>
 *
 * @see Builder
 */
public class VpnService extends Service {

    /**
     * The action must be matched by the intent filter of this service. It also
     * needs to require {@link android.Manifest.permission#BIND_VPN_SERVICE}
     * permission so that other applications cannot abuse it.
     */
    public static final String SERVICE_INTERFACE = VpnConfig.SERVICE_INTERFACE;

    /**
     * Use IConnectivityManager since those methods are hidden and not
     * available in ConnectivityManager.
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
     * the result will come back via its {@link Activity#onActivityResult}.
     * If the result is {@link Activity#RESULT_OK}, the application becomes
     * prepared and is granted to use other methods in this class.
     *
     * <p>Only one application can be granted at the same time. The right
     * is revoked when another application is granted. The application
     * losing the right will be notified via its {@link #onRevoke}. Unless
     * it becomes prepared again, subsequent calls to other methods in this
     * class will fail.
     *
     * @see #onRevoke
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

    /**
     * Protect a socket from VPN connections. The socket will be bound to the
     * current default network interface, so its traffic will not be forwarded
     * through VPN. This method is useful if some connections need to be kept
     * outside of VPN. For example, a VPN tunnel should protect itself if its
     * destination is covered by VPN routes. Otherwise its outgoing packets
     * will be sent back to the VPN interface and cause an infinite loop. This
     * method will fail if the application is not prepared or is revoked.
     *
     * <p class="note">The socket is NOT closed by this method.
     *
     * @return {@code true} on success.
     */
    public boolean protect(int socket) {
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
     * Convenience method to protect a {@link Socket} from VPN connections.
     *
     * @return {@code true} on success.
     * @see #protect(int)
     */
    public boolean protect(Socket socket) {
        return protect(socket.getFileDescriptor$().getInt$());
    }

    /**
     * Convenience method to protect a {@link DatagramSocket} from VPN
     * connections.
     *
     * @return {@code true} on success.
     * @see #protect(int)
     */
    public boolean protect(DatagramSocket socket) {
        return protect(socket.getFileDescriptor$().getInt$());
    }

    /**
     * Return the communication interface to the service. This method returns
     * {@code null} on {@link Intent}s other than {@link #SERVICE_INTERFACE}
     * action. Applications overriding this method must identify the intent
     * and return the corresponding interface accordingly.
     *
     * @see Service#onBind
     */
    @Override
    public IBinder onBind(Intent intent) {
        if (intent != null && SERVICE_INTERFACE.equals(intent.getAction())) {
            return new Callback();
        }
        return null;
    }

    /**
     * Invoked when the application is revoked. At this moment, the VPN
     * interface is already deactivated by the system. The application should
     * close the file descriptor and shut down gracefully. The default
     * implementation of this method is calling {@link Service#stopSelf()}.
     *
     * <p class="note">Calls to this method may not happen on the main thread
     * of the process.
     *
     * @see #prepare
     */
    public void onRevoke() {
        stopSelf();
    }

    /**
     * Use raw Binder instead of AIDL since now there is only one usage.
     */
    private class Callback extends Binder {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == IBinder.LAST_CALL_TRANSACTION) {
                onRevoke();
                return true;
            }
            return false;
        }
    }

    /**
     * Helper class to create a VPN interface. This class should be always
     * used within the scope of the outer {@link VpnService}.
     *
     * @see VpnService
     */
    public class Builder {

        private final VpnConfig mConfig = new VpnConfig();
        private final List<LinkAddress> mAddresses = new ArrayList<LinkAddress>();
        private final List<RouteInfo> mRoutes = new ArrayList<RouteInfo>();

        public Builder() {
            mConfig.user = VpnService.this.getClass().getName();
        }

        /**
         * Set the name of this session. It will be displayed in
         * system-managed dialogs and notifications. This is recommended
         * not required.
         */
        public Builder setSession(String session) {
            mConfig.session = session;
            return this;
        }

        /**
         * Set the {@link PendingIntent} to an activity for users to
         * configure the VPN connection. If it is not set, the button
         * to configure will not be shown in system-managed dialogs.
         */
        public Builder setConfigureIntent(PendingIntent intent) {
            mConfig.configureIntent = intent;
            return this;
        }

        /**
         * Set the maximum transmission unit (MTU) of the VPN interface. If
         * it is not set, the default value in the operating system will be
         * used.
         *
         * @throws IllegalArgumentException if the value is not positive.
         */
        public Builder setMtu(int mtu) {
            if (mtu <= 0) {
                throw new IllegalArgumentException("Bad mtu");
            }
            mConfig.mtu = mtu;
            return this;
        }

        /**
         * Private method to validate address and prefixLength.
         */
        private void check(InetAddress address, int prefixLength) {
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
         * Add a network address to the VPN interface. Both IPv4 and IPv6
         * addresses are supported. At least one address must be set before
         * calling {@link #establish}.
         *
         * @throws IllegalArgumentException if the address is invalid.
         */
        public Builder addAddress(InetAddress address, int prefixLength) {
            check(address, prefixLength);

            if (address.isAnyLocalAddress()) {
                throw new IllegalArgumentException("Bad address");
            }
            mAddresses.add(new LinkAddress(address, prefixLength));
            return this;
        }

        /**
         * Convenience method to add a network address to the VPN interface
         * using a numeric address string. See {@link InetAddress} for the
         * definitions of numeric address formats.
         *
         * @throws IllegalArgumentException if the address is invalid.
         * @see #addAddress(InetAddress, int)
         */
        public Builder addAddress(String address, int prefixLength) {
            return addAddress(InetAddress.parseNumericAddress(address), prefixLength);
        }

        /**
         * Add a network route to the VPN interface. Both IPv4 and IPv6
         * routes are supported.
         *
         * @throws IllegalArgumentException if the route is invalid.
         */
        public Builder addRoute(InetAddress address, int prefixLength) {
            check(address, prefixLength);

            int offset = prefixLength / 8;
            byte[] bytes = address.getAddress();
            if (offset < bytes.length) {
                for (bytes[offset] <<= prefixLength % 8; offset < bytes.length; ++offset) {
                    if (bytes[offset] != 0) {
                        throw new IllegalArgumentException("Bad address");
                    }
                }
            }
            mRoutes.add(new RouteInfo(new LinkAddress(address, prefixLength), null));
            return this;
        }

        /**
         * Convenience method to add a network route to the VPN interface
         * using a numeric address string. See {@link InetAddress} for the
         * definitions of numeric address formats.
         *
         * @throws IllegalArgumentException if the route is invalid.
         * @see #addRoute(InetAddress, int)
         */
        public Builder addRoute(String address, int prefixLength) {
            return addRoute(InetAddress.parseNumericAddress(address), prefixLength);
        }

        /**
         * Add a DNS server to the VPN connection. Both IPv4 and IPv6
         * addresses are supported. If none is set, the DNS servers of
         * the default network will be used.
         *
         * @throws IllegalArgumentException if the address is invalid.
         */
        public Builder addDnsServer(InetAddress address) {
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
         * Convenience method to add a DNS server to the VPN connection
         * using a numeric address string. See {@link InetAddress} for the
         * definitions of numeric address formats.
         *
         * @throws IllegalArgumentException if the address is invalid.
         * @see #addDnsServer(InetAddress)
         */
        public Builder addDnsServer(String address) {
            return addDnsServer(InetAddress.parseNumericAddress(address));
        }

        /**
         * Add a search domain to the DNS resolver.
         */
        public Builder addSearchDomain(String domain) {
            if (mConfig.searchDomains == null) {
                mConfig.searchDomains = new ArrayList<String>();
            }
            mConfig.searchDomains.add(domain);
            return this;
        }

        /**
         * Create a VPN interface using the parameters supplied to this
         * builder. The interface works on IP packets, and a file descriptor
         * is returned for the application to access them. Each read
         * retrieves an outgoing packet which was routed to the interface.
         * Each write injects an incoming packet just like it was received
         * from the interface. The file descriptor is put into non-blocking
         * mode by default to avoid blocking Java threads. To use the file
         * descriptor completely in native space, see
         * {@link ParcelFileDescriptor#detachFd()}. The application MUST
         * close the file descriptor when the VPN connection is terminated.
         * The VPN interface will be removed and the network will be
         * restored by the system automatically.
         *
         * <p>To avoid conflicts, there can be only one active VPN interface
         * at the same time. Usually network parameters are never changed
         * during the lifetime of a VPN connection. It is also common for an
         * application to create a new file descriptor after closing the
         * previous one. However, it is rare but not impossible to have two
         * interfaces while performing a seamless handover. In this case, the
         * old interface will be deactivated when the new one is created
         * successfully. Both file descriptors are valid but now outgoing
         * packets will be routed to the new interface. Therefore, after
         * draining the old file descriptor, the application MUST close it
         * and start using the new file descriptor. If the new interface
         * cannot be created, the existing interface and its file descriptor
         * remain untouched.
         *
         * <p>An exception will be thrown if the interface cannot be created
         * for any reason. However, this method returns {@code null} if the
         * application is not prepared or is revoked. This helps solve
         * possible race conditions between other VPN applications.
         *
         * @return {@link ParcelFileDescriptor} of the VPN interface, or
         *         {@code null} if the application is not prepared.
         * @throws IllegalArgumentException if a parameter is not accepted
         *         by the operating system.
         * @throws IllegalStateException if a parameter cannot be applied
         *         by the operating system.
         * @throws SecurityException if the service is not properly declared
         *         in {@code AndroidManifest.xml}.
         * @see VpnService
         */
        public ParcelFileDescriptor establish() {
            mConfig.addresses = mAddresses;
            mConfig.routes = mRoutes;

            try {
                return getService().establishVpn(mConfig);
            } catch (RemoteException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
