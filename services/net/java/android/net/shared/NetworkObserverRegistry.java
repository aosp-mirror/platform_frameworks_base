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
package android.net.shared;

import static android.Manifest.permission.NETWORK_STACK;

import android.content.Context;
import android.net.INetd;
import android.net.INetdUnsolicitedEventListener;
import android.net.INetworkManagementEventObserver;
import android.net.InetAddresses;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.RouteInfo;
import android.os.Handler;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemClock;

/**
 * A class for reporting network events to clients.
 *
 * Implements INetdUnsolicitedEventListener and registers with netd, and relays those events to
 * all INetworkManagementEventObserver objects that have registered with it.
 *
 * TODO: Make the notifyXyz methods protected once subclasses (e.g., the NetworkManagementService
 * subclass) no longer call them directly.
 *
 * TODO: change from RemoteCallbackList to direct in-process callbacks.
 */
public class NetworkObserverRegistry extends INetdUnsolicitedEventListener.Stub {

    private final Context mContext;
    private final Handler mDaemonHandler;
    private static final String TAG = "NetworkObserverRegistry";

    /**
     * Constructs a new instance and registers it with netd.
     * This method should only be called once since netd will reject multiple registrations from
     * the same process.
     */
    public NetworkObserverRegistry(Context context, Handler handler, INetd netd)
            throws RemoteException {
        mContext = context;
        mDaemonHandler = handler;
        netd.registerUnsolicitedEventListener(this);
    }

    private final RemoteCallbackList<INetworkManagementEventObserver> mObservers =
            new RemoteCallbackList<>();

    /**
     * Registers the specified observer and start sending callbacks to it.
     * This method may be called on any thread.
     */
    public void registerObserver(INetworkManagementEventObserver observer) {
        mContext.enforceCallingOrSelfPermission(NETWORK_STACK, TAG);
        mObservers.register(observer);
    }

    /**
     * Unregisters the specified observer and stop sending callbacks to it.
     * This method may be called on any thread.
     */
    public void unregisterObserver(INetworkManagementEventObserver observer) {
        mContext.enforceCallingOrSelfPermission(NETWORK_STACK, TAG);
        mObservers.unregister(observer);
    }

    @FunctionalInterface
    private interface NetworkManagementEventCallback {
        void sendCallback(INetworkManagementEventObserver o) throws RemoteException;
    }

    private void invokeForAllObservers(NetworkManagementEventCallback eventCallback) {
        final int length = mObservers.beginBroadcast();
        try {
            for (int i = 0; i < length; i++) {
                try {
                    eventCallback.sendCallback(mObservers.getBroadcastItem(i));
                } catch (RemoteException | RuntimeException e) {
                }
            }
        } finally {
            mObservers.finishBroadcast();
        }
    }

    /**
     * Notify our observers of a change in the data activity state of the interface
     */
    public void notifyInterfaceClassActivity(int type, boolean isActive, long tsNanos,
            int uid, boolean fromRadio) {
        invokeForAllObservers(o -> o.interfaceClassDataActivityChanged(
                Integer.toString(type), isActive, tsNanos));
    }

    @Override
    public void onInterfaceClassActivityChanged(boolean isActive,
            int label, long timestamp, int uid) throws RemoteException {
        final long timestampNanos;
        if (timestamp <= 0) {
            timestampNanos = SystemClock.elapsedRealtimeNanos();
        } else {
            timestampNanos = timestamp;
        }
        mDaemonHandler.post(() -> notifyInterfaceClassActivity(label, isActive,
                timestampNanos, uid, false));
    }

    /**
     * Notify our observers of a limit reached.
     */
    @Override
    public void onQuotaLimitReached(String alertName, String ifName) throws RemoteException {
        mDaemonHandler.post(() -> notifyLimitReached(alertName, ifName));
    }

    /**
     * Notify our observers of a limit reached.
     */
    public void notifyLimitReached(String limitName, String iface) {
        invokeForAllObservers(o -> o.limitReached(limitName, iface));
    }

    @Override
    public void onInterfaceDnsServerInfo(String ifName,
            long lifetime, String[] servers) throws RemoteException {
        mDaemonHandler.post(() -> notifyInterfaceDnsServerInfo(ifName, lifetime, servers));
    }

    /**
     * Notify our observers of DNS server information received.
     */
    public void notifyInterfaceDnsServerInfo(String iface, long lifetime, String[] addresses) {
        invokeForAllObservers(o -> o.interfaceDnsServerInfo(iface, lifetime, addresses));
    }

    @Override
    public void onInterfaceAddressUpdated(String addr,
            String ifName, int flags, int scope) throws RemoteException {
        final LinkAddress address = new LinkAddress(addr, flags, scope);
        mDaemonHandler.post(() -> notifyAddressUpdated(ifName, address));
    }

    /**
     * Notify our observers of a new or updated interface address.
     */
    public void notifyAddressUpdated(String iface, LinkAddress address) {
        invokeForAllObservers(o -> o.addressUpdated(iface, address));
    }

    @Override
    public void onInterfaceAddressRemoved(String addr,
            String ifName, int flags, int scope) throws RemoteException {
        final LinkAddress address = new LinkAddress(addr, flags, scope);
        mDaemonHandler.post(() -> notifyAddressRemoved(ifName, address));
    }

    /**
     * Notify our observers of a deleted interface address.
     */
    public void notifyAddressRemoved(String iface, LinkAddress address) {
        invokeForAllObservers(o -> o.addressRemoved(iface, address));
    }


    @Override
    public void onInterfaceAdded(String ifName) throws RemoteException {
        mDaemonHandler.post(() -> notifyInterfaceAdded(ifName));
    }

    /**
     * Notify our observers of an interface addition.
     */
    public void notifyInterfaceAdded(String iface) {
        invokeForAllObservers(o -> o.interfaceAdded(iface));
    }

    @Override
    public void onInterfaceRemoved(String ifName) throws RemoteException {
        mDaemonHandler.post(() -> notifyInterfaceRemoved(ifName));
    }

    /**
     * Notify our observers of an interface removal.
     */
    public void notifyInterfaceRemoved(String iface) {
        invokeForAllObservers(o -> o.interfaceRemoved(iface));
    }

    @Override
    public void onInterfaceChanged(String ifName, boolean up) throws RemoteException {
        mDaemonHandler.post(() -> notifyInterfaceStatusChanged(ifName, up));
    }

    /**
     * Notify our observers of an interface status change
     */
    public void notifyInterfaceStatusChanged(String iface, boolean up) {
        invokeForAllObservers(o -> o.interfaceStatusChanged(iface, up));
    }

    @Override
    public void onInterfaceLinkStateChanged(String ifName, boolean up) throws RemoteException {
        mDaemonHandler.post(() -> notifyInterfaceLinkStateChanged(ifName, up));
    }

    /**
     * Notify our observers of an interface link state change
     * (typically, an Ethernet cable has been plugged-in or unplugged).
     */
    public void notifyInterfaceLinkStateChanged(String iface, boolean up) {
        invokeForAllObservers(o -> o.interfaceLinkStateChanged(iface, up));
    }

    @Override
    public void onRouteChanged(boolean updated,
            String route, String gateway, String ifName) throws RemoteException {
        final RouteInfo processRoute = new RouteInfo(new IpPrefix(route),
                ("".equals(gateway)) ? null : InetAddresses.parseNumericAddress(gateway),
                ifName);
        mDaemonHandler.post(() -> notifyRouteChange(updated, processRoute));
    }

    /**
     * Notify our observers of a route change.
     */
    public void notifyRouteChange(boolean updated, RouteInfo route) {
        if (updated) {
            invokeForAllObservers(o -> o.routeUpdated(route));
        } else {
            invokeForAllObservers(o -> o.routeRemoved(route));
        }
    }

    @Override
    public void onStrictCleartextDetected(int uid, String hex) throws RemoteException {
        // Don't do anything here because this is not a method of INetworkManagementEventObserver.
        // Only the NMS subclass will implement this.
    }
}
