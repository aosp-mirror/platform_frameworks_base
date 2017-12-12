/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.net.lowpan;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.util.Log;
import java.util.HashMap;

/**
 * Class for managing a specific Low-power Wireless Personal Area Network (LoWPAN) interface.
 *
 * @hide
 */
// @SystemApi
public class LowpanInterface {
    private static final String TAG = LowpanInterface.class.getSimpleName();

    /** Detached role. The interface is not currently attached to a network. */
    public static final String ROLE_DETACHED = ILowpanInterface.ROLE_DETACHED;

    /** End-device role. End devices do not route traffic for other nodes. */
    public static final String ROLE_END_DEVICE = ILowpanInterface.ROLE_END_DEVICE;

    /** Router role. Routers help route traffic around the mesh network. */
    public static final String ROLE_ROUTER = ILowpanInterface.ROLE_ROUTER;

    /**
     * Sleepy End-Device role.
     *
     * <p>End devices with this role are nominally asleep, waking up periodically to check in with
     * their parent to see if there are packets destined for them. Such devices are capable of
     * extraordinarilly low power consumption, but packet latency can be on the order of dozens of
     * seconds(depending on how the node is configured).
     */
    public static final String ROLE_SLEEPY_END_DEVICE = ILowpanInterface.ROLE_SLEEPY_END_DEVICE;

    /**
     * Sleepy-router role.
     *
     * <p>Routers with this role are nominally asleep, waking up periodically to check in with other
     * routers and their children.
     */
    public static final String ROLE_SLEEPY_ROUTER = ILowpanInterface.ROLE_SLEEPY_ROUTER;

    /** TODO: doc */
    public static final String ROLE_LEADER = ILowpanInterface.ROLE_LEADER;

    /** TODO: doc */
    public static final String ROLE_COORDINATOR = ILowpanInterface.ROLE_COORDINATOR;

    /**
     * Offline state.
     *
     * <p>This is the initial state of the LoWPAN interface when the underlying driver starts. In
     * this state the NCP is idle and not connected to any network.
     *
     * <p>This state can be explicitly entered by calling {@link #reset()}, {@link #leave()}, or
     * <code>setUp(false)</code>, with the later two only working if we were not previously in the
     * {@link #STATE_FAULT} state.
     *
     * @see #getState()
     * @see #STATE_FAULT
     */
    public static final String STATE_OFFLINE = ILowpanInterface.STATE_OFFLINE;

    /**
     * Commissioning state.
     *
     * <p>The interface enters this state after a call to {@link #startCommissioningSession()}. This
     * state may only be entered directly from the {@link #STATE_OFFLINE} state.
     *
     * @see #startCommissioningSession()
     * @see #getState()
     * @hide
     */
    public static final String STATE_COMMISSIONING = ILowpanInterface.STATE_COMMISSIONING;

    /**
     * Attaching state.
     *
     * <p>The interface enters this state when it starts the process of trying to find other nodes
     * so that it can attach to any pre-existing network fragment, or when it is in the process of
     * calculating the optimal values for unspecified parameters when forming a new network.
     *
     * <p>The interface may stay in this state for a prolonged period of time (or may spontaneously
     * enter this state from {@link #STATE_ATTACHED}) if the underlying network technology is
     * heirarchical (like ZigBeeIP) or if the device role is that of an "end-device" ({@link
     * #ROLE_END_DEVICE} or {@link #ROLE_SLEEPY_END_DEVICE}). This is because such roles cannot
     * create their own network fragments.
     *
     * @see #STATE_ATTACHED
     * @see #getState()
     */
    public static final String STATE_ATTACHING = ILowpanInterface.STATE_ATTACHING;

    /**
     * Attached state.
     *
     * <p>The interface enters this state from {@link #STATE_ATTACHING} once it is actively
     * participating on a network fragment.
     *
     * @see #STATE_ATTACHING
     * @see #getState()
     */
    public static final String STATE_ATTACHED = ILowpanInterface.STATE_ATTACHED;

    /**
     * Fault state.
     *
     * <p>The interface will enter this state when the driver has detected some sort of problem from
     * which it was not immediately able to recover.
     *
     * <p>This state can be entered spontaneously from any other state. Calling {@link #reset} will
     * cause the device to return to the {@link #STATE_OFFLINE} state.
     *
     * @see #getState
     * @see #STATE_OFFLINE
     */
    public static final String STATE_FAULT = ILowpanInterface.STATE_FAULT;

    /**
     * Network type for Thread 1.x networks.
     *
     * @see android.net.lowpan.LowpanIdentity#getType
     * @see #getLowpanIdentity
     * @hide
     */
    public static final String NETWORK_TYPE_THREAD_V1 = ILowpanInterface.NETWORK_TYPE_THREAD_V1;

    public static final String EMPTY_PARTITION_ID = "";

    /**
     * Callback base class for LowpanInterface
     *
     * @hide
     */
    // @SystemApi
    public abstract static class Callback {
        public void onConnectedChanged(boolean value) {}

        public void onEnabledChanged(boolean value) {}

        public void onUpChanged(boolean value) {}

        public void onRoleChanged(@NonNull String value) {}

        public void onStateChanged(@NonNull String state) {}

        public void onLowpanIdentityChanged(@NonNull LowpanIdentity value) {}

        public void onLinkNetworkAdded(IpPrefix prefix) {}

        public void onLinkNetworkRemoved(IpPrefix prefix) {}

        public void onLinkAddressAdded(LinkAddress address) {}

        public void onLinkAddressRemoved(LinkAddress address) {}
    }

    private final ILowpanInterface mBinder;
    private final Looper mLooper;
    private final HashMap<Integer, ILowpanInterfaceListener> mListenerMap = new HashMap<>();

    /**
     * Create a new LowpanInterface instance. Applications will almost always want to use {@link
     * LowpanManager#getInterface LowpanManager.getInterface()} instead of this.
     *
     * @param context the application context
     * @param service the Binder interface
     * @param looper the Binder interface
     * @hide
     */
    public LowpanInterface(Context context, ILowpanInterface service, Looper looper) {
        /* We aren't currently using the context, but if we need
         * it later on we can easily add it to the class.
         */

        mBinder = service;
        mLooper = looper;
    }

    /**
     * Returns the ILowpanInterface object associated with this interface.
     *
     * @hide
     */
    public ILowpanInterface getService() {
        return mBinder;
    }

    // Public Actions

    /**
     * Form a new network with the given network information optional credential. Unspecified fields
     * in the network information will be filled in with reasonable values. If the network
     * credential is unspecified, one will be generated automatically.
     *
     * <p>This method will block until either the network was successfully formed or an error
     * prevents the network form being formed.
     *
     * <p>Upon success, the interface will be up and attached to the newly formed network.
     *
     * @see #join(LowpanProvision)
     */
    public void form(@NonNull LowpanProvision provision) throws LowpanException {
        try {
            mBinder.form(provision);

        } catch (RemoteException x) {
            throw x.rethrowAsRuntimeException();

        } catch (ServiceSpecificException x) {
            throw LowpanException.rethrowFromServiceSpecificException(x);
        }
    }

    /**
     * Attempts to join a new network with the given network information. This method will block
     * until either the network was successfully joined or an error prevented the network from being
     * formed. Upon success, the interface will be up and attached to the newly joined network.
     *
     * <p>Note that “joining” is distinct from “attaching”: Joining requires at least one other peer
     * device to be present in order for the operation to complete successfully.
     */
    public void join(@NonNull LowpanProvision provision) throws LowpanException {
        try {
            mBinder.join(provision);

        } catch (RemoteException x) {
            throw x.rethrowAsRuntimeException();

        } catch (ServiceSpecificException x) {
            throw LowpanException.rethrowFromServiceSpecificException(x);
        }
    }

    /**
     * Attaches to the network described by identity and credential. This is similar to {@link
     * #join}, except that (assuming the identity and credential are valid) it will always succeed
     * and provision the interface, even if there are no peers nearby.
     *
     * <p>This method will block execution until the operation has completed.
     */
    public void attach(@NonNull LowpanProvision provision) throws LowpanException {
        try {
            mBinder.attach(provision);

        } catch (RemoteException x) {
            throw x.rethrowAsRuntimeException();

        } catch (ServiceSpecificException x) {
            throw LowpanException.rethrowFromServiceSpecificException(x);
        }
    }

    /**
     * Bring down the network interface and forget all non-volatile details about the current
     * network.
     *
     * <p>This method will block execution until the operation has completed.
     */
    public void leave() throws LowpanException {
        try {
            mBinder.leave();

        } catch (RemoteException x) {
            throw x.rethrowAsRuntimeException();

        } catch (ServiceSpecificException x) {
            throw LowpanException.rethrowFromServiceSpecificException(x);
        }
    }

    /**
     * Start a new commissioning session. Will fail if the interface is attached to a network or if
     * the interface is disabled.
     */
    public @NonNull LowpanCommissioningSession startCommissioningSession(
            @NonNull LowpanBeaconInfo beaconInfo) throws LowpanException {
        try {
            mBinder.startCommissioningSession(beaconInfo);

            return new LowpanCommissioningSession(mBinder, beaconInfo, mLooper);

        } catch (RemoteException x) {
            throw x.rethrowAsRuntimeException();

        } catch (ServiceSpecificException x) {
            throw LowpanException.rethrowFromServiceSpecificException(x);
        }
    }

    /**
     * Reset this network interface as if it has been power cycled. Will bring the network interface
     * down if it was previously up. Will not erase any non-volatile settings.
     *
     * <p>This method will block execution until the operation has completed.
     *
     * @hide
     */
    public void reset() throws LowpanException {
        try {
            mBinder.reset();

        } catch (RemoteException x) {
            throw x.rethrowAsRuntimeException();

        } catch (ServiceSpecificException x) {
            throw LowpanException.rethrowFromServiceSpecificException(x);
        }
    }

    // Public Getters and Setters

    /** Returns the name of this network interface. */
    @NonNull
    public String getName() {
        try {
            return mBinder.getName();

        } catch (DeadObjectException x) {
            return "";

        } catch (RemoteException x) {
            throw x.rethrowAsRuntimeException();
        }
    }

    /**
     * Indicates if the interface is enabled or disabled.
     *
     * @see #setEnabled
     * @see android.net.lowpan.LowpanException#LOWPAN_DISABLED
     */
    public boolean isEnabled() {
        try {
            return mBinder.isEnabled();

        } catch (DeadObjectException x) {
            return false;

        } catch (RemoteException x) {
            throw x.rethrowAsRuntimeException();
        }
    }

    /**
     * Enables or disables the LoWPAN interface. When disabled, the interface is put into a
     * low-power state and all commands that require the NCP to be queried will fail with {@link
     * android.net.lowpan.LowpanException#LOWPAN_DISABLED}.
     *
     * @see #isEnabled
     * @see android.net.lowpan.LowpanException#LOWPAN_DISABLED
     * @hide
     */
    public void setEnabled(boolean enabled) throws LowpanException {
        try {
            mBinder.setEnabled(enabled);

        } catch (RemoteException x) {
            throw x.rethrowAsRuntimeException();

        } catch (ServiceSpecificException x) {
            throw LowpanException.rethrowFromServiceSpecificException(x);
        }
    }

    /**
     * Indicates if the network interface is up or down.
     *
     * @hide
     */
    public boolean isUp() {
        try {
            return mBinder.isUp();

        } catch (DeadObjectException x) {
            return false;

        } catch (RemoteException x) {
            throw x.rethrowAsRuntimeException();
        }
    }

    /**
     * Indicates if there is at least one peer in range.
     *
     * @return <code>true</code> if we have at least one other peer in range, <code>false</code>
     *     otherwise.
     */
    public boolean isConnected() {
        try {
            return mBinder.isConnected();

        } catch (DeadObjectException x) {
            return false;

        } catch (RemoteException x) {
            throw x.rethrowAsRuntimeException();
        }
    }

    /**
     * Indicates if this interface is currently commissioned onto an existing network. If the
     * interface is commissioned, the interface may be brought up using setUp().
     */
    public boolean isCommissioned() {
        try {
            return mBinder.isCommissioned();

        } catch (DeadObjectException x) {
            return false;

        } catch (RemoteException x) {
            throw x.rethrowAsRuntimeException();
        }
    }

    /**
     * Get interface state
     *
     * <h3>State Diagram</h3>
     *
     * <img src="LowpanInterface-1.png" />
     *
     * @return The current state of the interface.
     * @see #STATE_OFFLINE
     * @see #STATE_COMMISSIONING
     * @see #STATE_ATTACHING
     * @see #STATE_ATTACHED
     * @see #STATE_FAULT
     */
    public String getState() {
        try {
            return mBinder.getState();

        } catch (DeadObjectException x) {
            return STATE_FAULT;

        } catch (RemoteException x) {
            throw x.rethrowAsRuntimeException();
        }
    }

    /** Get network partition/fragment identifier. */
    public String getPartitionId() {
        try {
            return mBinder.getPartitionId();

        } catch (DeadObjectException x) {
            return EMPTY_PARTITION_ID;

        } catch (RemoteException x) {
            throw x.rethrowAsRuntimeException();
        }
    }

    /** TODO: doc */
    public LowpanIdentity getLowpanIdentity() {
        try {
            return mBinder.getLowpanIdentity();

        } catch (DeadObjectException x) {
            return new LowpanIdentity();

        } catch (RemoteException x) {
            throw x.rethrowAsRuntimeException();
        }
    }

    /** TODO: doc */
    @NonNull
    public String getRole() {
        try {
            return mBinder.getRole();

        } catch (DeadObjectException x) {
            return ROLE_DETACHED;

        } catch (RemoteException x) {
            throw x.rethrowAsRuntimeException();
        }
    }

    /** TODO: doc */
    @Nullable
    public LowpanCredential getLowpanCredential() {
        try {
            return mBinder.getLowpanCredential();

        } catch (RemoteException x) {
            throw x.rethrowAsRuntimeException();
        }
    }

    public @NonNull String[] getSupportedNetworkTypes() throws LowpanException {
        try {
            return mBinder.getSupportedNetworkTypes();

        } catch (RemoteException x) {
            throw x.rethrowAsRuntimeException();

        } catch (ServiceSpecificException x) {
            throw LowpanException.rethrowFromServiceSpecificException(x);
        }
    }

    public @NonNull LowpanChannelInfo[] getSupportedChannels() throws LowpanException {
        try {
            return mBinder.getSupportedChannels();

        } catch (RemoteException x) {
            throw x.rethrowAsRuntimeException();

        } catch (ServiceSpecificException x) {
            throw LowpanException.rethrowFromServiceSpecificException(x);
        }
    }

    // Listener Support

    /**
     * Registers a subclass of {@link LowpanInterface.Callback} to receive events.
     *
     * @param cb Subclass of {@link LowpanInterface.Callback} which will receive events.
     * @param handler If not <code>null</code>, events will be dispatched via the given handler
     *     object. If <code>null</code>, the thread upon which events will be dispatched is
     *     unspecified.
     * @see #registerCallback(Callback)
     * @see #unregisterCallback(Callback)
     */
    public void registerCallback(@NonNull Callback cb, @Nullable Handler handler) {
        ILowpanInterfaceListener.Stub listenerBinder =
                new ILowpanInterfaceListener.Stub() {
                    private Handler mHandler;

                    {
                        if (handler != null) {
                            mHandler = handler;
                        } else if (mLooper != null) {
                            mHandler = new Handler(mLooper);
                        } else {
                            mHandler = new Handler();
                        }
                    }

                    @Override
                    public void onEnabledChanged(boolean value) {
                        mHandler.post(() -> cb.onEnabledChanged(value));
                    }

                    @Override
                    public void onConnectedChanged(boolean value) {
                        mHandler.post(() -> cb.onConnectedChanged(value));
                    }

                    @Override
                    public void onUpChanged(boolean value) {
                        mHandler.post(() -> cb.onUpChanged(value));
                    }

                    @Override
                    public void onRoleChanged(String value) {
                        mHandler.post(() -> cb.onRoleChanged(value));
                    }

                    @Override
                    public void onStateChanged(String value) {
                        mHandler.post(() -> cb.onStateChanged(value));
                    }

                    @Override
                    public void onLowpanIdentityChanged(LowpanIdentity value) {
                        mHandler.post(() -> cb.onLowpanIdentityChanged(value));
                    }

                    @Override
                    public void onLinkNetworkAdded(IpPrefix value) {
                        mHandler.post(() -> cb.onLinkNetworkAdded(value));
                    }

                    @Override
                    public void onLinkNetworkRemoved(IpPrefix value) {
                        mHandler.post(() -> cb.onLinkNetworkRemoved(value));
                    }

                    @Override
                    public void onLinkAddressAdded(String value) {
                        LinkAddress la;
                        try {
                            la = new LinkAddress(value);
                        } catch (IllegalArgumentException x) {
                            Log.e(
                                    TAG,
                                    "onLinkAddressAdded: Bad LinkAddress \"" + value + "\", " + x);
                            return;
                        }
                        mHandler.post(() -> cb.onLinkAddressAdded(la));
                    }

                    @Override
                    public void onLinkAddressRemoved(String value) {
                        LinkAddress la;
                        try {
                            la = new LinkAddress(value);
                        } catch (IllegalArgumentException x) {
                            Log.e(
                                    TAG,
                                    "onLinkAddressRemoved: Bad LinkAddress \""
                                            + value
                                            + "\", "
                                            + x);
                            return;
                        }
                        mHandler.post(() -> cb.onLinkAddressRemoved(la));
                    }

                    @Override
                    public void onReceiveFromCommissioner(byte[] packet) {
                        // This is only used by the LowpanCommissioningSession.
                    }
                };
        try {
            mBinder.addListener(listenerBinder);
        } catch (RemoteException x) {
            throw x.rethrowAsRuntimeException();
        }

        synchronized (mListenerMap) {
            mListenerMap.put(System.identityHashCode(cb), listenerBinder);
        }
    }

    /**
     * Registers a subclass of {@link LowpanInterface.Callback} to receive events.
     *
     * <p>The thread upon which events will be dispatched is unspecified.
     *
     * @param cb Subclass of {@link LowpanInterface.Callback} which will receive events.
     * @see #registerCallback(Callback, Handler)
     * @see #unregisterCallback(Callback)
     */
    public void registerCallback(Callback cb) {
        registerCallback(cb, null);
    }

    /**
     * Unregisters a previously registered callback class.
     *
     * @param cb Subclass of {@link LowpanInterface.Callback} which was previously registered to
     *     receive events.
     * @see #registerCallback(Callback, Handler)
     * @see #registerCallback(Callback)
     */
    public void unregisterCallback(Callback cb) {
        int hashCode = System.identityHashCode(cb);
        synchronized (mListenerMap) {
            ILowpanInterfaceListener listenerBinder = mListenerMap.get(hashCode);

            if (listenerBinder != null) {
                mListenerMap.remove(hashCode);

                try {
                    mBinder.removeListener(listenerBinder);
                } catch (DeadObjectException x) {
                    // We ignore a dead object exception because that
                    // pretty clearly means our callback isn't registered.
                } catch (RemoteException x) {
                    throw x.rethrowAsRuntimeException();
                }
            }
        }
    }

    // Active and Passive Scanning

    /**
     * Creates a new {@link android.net.lowpan.LowpanScanner} object for this interface.
     *
     * <p>This method allocates a new unique object for each call.
     *
     * @see android.net.lowpan.LowpanScanner
     */
    public @NonNull LowpanScanner createScanner() {
        return new LowpanScanner(mBinder);
    }

    // Route Management

    /**
     * Makes a copy of the internal list of LinkAddresses.
     *
     * @hide
     */
    public LinkAddress[] getLinkAddresses() throws LowpanException {
        try {
            String[] linkAddressStrings = mBinder.getLinkAddresses();
            LinkAddress[] ret = new LinkAddress[linkAddressStrings.length];
            int i = 0;
            for (String str : linkAddressStrings) {
                ret[i++] = new LinkAddress(str);
            }
            return ret;

        } catch (RemoteException x) {
            throw x.rethrowAsRuntimeException();

        } catch (ServiceSpecificException x) {
            throw LowpanException.rethrowFromServiceSpecificException(x);
        }
    }

    /**
     * Makes a copy of the internal list of networks reachable on via this link.
     *
     * @hide
     */
    public IpPrefix[] getLinkNetworks() throws LowpanException {
        try {
            return mBinder.getLinkNetworks();

        } catch (RemoteException x) {
            throw x.rethrowAsRuntimeException();

        } catch (ServiceSpecificException x) {
            throw LowpanException.rethrowFromServiceSpecificException(x);
        }
    }

    /**
     * Advertise the given IP prefix as an on-mesh prefix.
     *
     * @hide
     */
    public void addOnMeshPrefix(IpPrefix prefix, int flags) throws LowpanException {
        try {
            mBinder.addOnMeshPrefix(prefix, flags);

        } catch (RemoteException x) {
            throw x.rethrowAsRuntimeException();

        } catch (ServiceSpecificException x) {
            throw LowpanException.rethrowFromServiceSpecificException(x);
        }
    }

    /**
     * Remove an IP prefix previously advertised by this device from the list of advertised on-mesh
     * prefixes.
     *
     * @hide
     */
    public void removeOnMeshPrefix(IpPrefix prefix) {
        try {
            mBinder.removeOnMeshPrefix(prefix);

        } catch (RemoteException x) {
            throw x.rethrowAsRuntimeException();

        } catch (ServiceSpecificException x) {
            // Catch and ignore all service exceptions
            Log.e(TAG, x.toString());
        }
    }

    /**
     * Advertise this device to other devices on the mesh network as having a specific route to the
     * given network. This device will then receive forwarded traffic for that network.
     *
     * @hide
     */
    public void addExternalRoute(IpPrefix prefix, int flags) throws LowpanException {
        try {
            mBinder.addExternalRoute(prefix, flags);

        } catch (RemoteException x) {
            throw x.rethrowAsRuntimeException();

        } catch (ServiceSpecificException x) {
            throw LowpanException.rethrowFromServiceSpecificException(x);
        }
    }

    /**
     * Revoke a previously advertised specific route to the given network.
     *
     * @hide
     */
    public void removeExternalRoute(IpPrefix prefix) {
        try {
            mBinder.removeExternalRoute(prefix);

        } catch (RemoteException x) {
            throw x.rethrowAsRuntimeException();

        } catch (ServiceSpecificException x) {
            // Catch and ignore all service exceptions
            Log.e(TAG, x.toString());
        }
    }
}
