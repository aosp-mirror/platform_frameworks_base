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
import android.net.IpPrefix;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.util.Log;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Class for managing a specific Low-power Wireless Personal Area Network (LoWPAN) interface.
 *
 * @hide
 */
// @SystemApi
public class LowpanInterface {
    private static final String TAG = LowpanInterface.class.getSimpleName();

    /** Detached role. The interface is not currently attached to a network. */
    public static final String ROLE_DETACHED = "detached";

    /** End-device role. End devices do not route traffic for other nodes. */
    public static final String ROLE_END_DEVICE = "end-device";

    /** Router role. Routers help route traffic around the mesh network. */
    public static final String ROLE_ROUTER = "router";

    /**
     * Sleepy End-Device role.
     *
     * <p>End devices with this role are nominally asleep, waking up periodically to check in with
     * their parent to see if there are packets destined for them. Such devices are capable of
     * extraordinarilly low power consumption, but packet latency can be on the order of dozens of
     * seconds(depending on how the node is configured).
     */
    public static final String ROLE_SLEEPY_END_DEVICE = "sleepy-end-device";

    /**
     * Sleepy-router role.
     *
     * <p>Routers with this role are nominally asleep, waking up periodically to check in with other
     * routers and their children.
     */
    public static final String ROLE_SLEEPY_ROUTER = "sleepy-router";

    /** TODO: doc */
    public static final String ROLE_LEADER = "leader";

    /** TODO: doc */
    public static final String ROLE_COORDINATOR = "coordinator";

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
    public static final String STATE_OFFLINE = "offline";

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
    public static final String STATE_COMMISSIONING = "commissioning";

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
    public static final String STATE_ATTACHING = "attaching";

    /**
     * Attached state.
     *
     * <p>The interface enters this state from {@link #STATE_ATTACHING} once it is actively
     * participating on a network fragment.
     *
     * @see #STATE_ATTACHING
     * @see #getState()
     */
    public static final String STATE_ATTACHED = "attached";

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
    public static final String STATE_FAULT = "fault";

    /**
     * Network type for Thread 1.x networks.
     *
     * @see android.net.lowpan.LowpanIdentity#getType
     * @see #getLowpanIdentity
     * @hide
     */
    public static final String NETWORK_TYPE_THREAD = "org.threadgroup.thread.v1";

    /**
     * Network type for ZigBeeIP 1.x networks.
     *
     * @see android.net.lowpan.LowpanIdentity#getType
     * @see #getLowpanIdentity
     * @hide
     */
    public static final String NETWORK_TYPE_ZIGBEE_IP = "org.zigbee.zigbeeip.v1";

    private static final String NETWORK_PROPERTY_KEYS[] = {
        LowpanProperties.KEY_NETWORK_NAME.getName(),
        LowpanProperties.KEY_NETWORK_PANID.getName(),
        LowpanProperties.KEY_NETWORK_XPANID.getName(),
        LowpanProperties.KEY_CHANNEL.getName()
    };

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

        /** @hide */
        public void onPropertiesChanged(@NonNull Map properties) {}
    }

    private ILowpanInterface mBinder;
    private final HashMap<Integer, ILowpanInterfaceListener> mListenerMap = new HashMap<>();

    /** Map between IBinder identity hashes and LowpanInstance objects. */
    private static final HashMap<Integer, LowpanInterface> sInstanceMap = new HashMap<>();

    private LowpanInterface(IBinder binder) {
        mBinder = ILowpanInterface.Stub.asInterface(binder);
    }

    /**
     * Get the LowpanInterface object associated with this IBinder. Returns null if this IBinder
     * does not implement the appropriate interface.
     *
     * @hide
     */
    @NonNull
    public static final LowpanInterface from(IBinder binder) {
        Integer hashCode = Integer.valueOf(System.identityHashCode(binder));
        LowpanInterface instance;

        synchronized (sInstanceMap) {
            instance = sInstanceMap.get(hashCode);

            if (instance == null) {
                instance = new LowpanInterface(binder);
                sInstanceMap.put(hashCode, instance);
            }
        }

        return instance;
    }

    /** {@hide} */
    public static final LowpanInterface from(ILowpanInterface iface) {
        return from(iface.asBinder());
    }

    /** {@hide} */
    public static final LowpanInterface getInterfaceFromBinder(IBinder binder) {
        return from(binder);
    }

    /**
     * Returns the IBinder object associated with this interface.
     *
     * @hide
     */
    public IBinder getBinder() {
        return mBinder.asBinder();
    }

    private static void throwAsPublicException(Throwable t) throws LowpanException {
        LowpanException.throwAsPublicException(t);
    }

    // Private Property Helpers

    void setProperties(Map properties) throws LowpanException {
        try {
            mBinder.setProperties(properties);

        } catch (RemoteException x) {
            // Catch and ignore all binder exceptions
            Log.e(TAG, x.toString());

        } catch (ServiceSpecificException x) {
            throwAsPublicException(x);
        }
    }

    @NonNull
    Map<String, Object> getProperties(String keys[]) throws LowpanException {
        try {
            return mBinder.getProperties(keys);
        } catch (RemoteException x) {
            // Catch and ignore all binder exceptions
            Log.e(TAG, x.toString());
        } catch (ServiceSpecificException x) {
            throwAsPublicException(x);
        }
        return new HashMap();
    }

    /** @hide */
    public <T> void setProperty(LowpanProperty<T> key, T value) throws LowpanException {
        HashMap<String, T> prop = new HashMap<>();
        prop.put(key.getName(), value);
        setProperties(prop);
    }

    /** @hide */
    @Nullable
    public <T> T getProperty(LowpanProperty<T> key) throws LowpanException {
        Map<String, Object> map = getProperties(new String[] {key.getName()});
        if (map != null && !map.isEmpty()) {
            // We know there is only one value.
            return (T) map.values().iterator().next();
        }
        return null;
    }

    @Nullable
    <T> String getPropertyAsString(LowpanProperty<T> key) throws LowpanException {
        try {
            return mBinder.getPropertyAsString(key.getName());
        } catch (RemoteException x) {
            // Catch and ignore all binder exceptions
            Log.e(TAG, x.toString());
        } catch (ServiceSpecificException x) {
            throwAsPublicException(x);
        }
        return null;
    }

    int getPropertyAsInt(LowpanProperty<Integer> key) throws LowpanException {
        Integer value = getProperty(key);
        return (value != null) ? value : 0;
    }

    boolean getPropertyAsBoolean(LowpanProperty<Boolean> key) throws LowpanException {
        Boolean value = getProperty(key);
        return (value != null) ? value : false;
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
            Map<String, Object> parameters = new HashMap();
            provision.addToMap(parameters);
            mBinder.form(parameters);
        } catch (RemoteException x) {
            throwAsPublicException(x);
        } catch (ServiceSpecificException x) {
            throwAsPublicException(x);
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
            Map<String, Object> parameters = new HashMap();
            provision.addToMap(parameters);
            mBinder.join(parameters);
        } catch (RemoteException x) {
            throwAsPublicException(x);
        } catch (ServiceSpecificException x) {
            throwAsPublicException(x);
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
        if (ROLE_DETACHED.equals(getRole())) {
            Map<String, Object> parameters = new HashMap();
            provision.addToMap(parameters);
            setProperties(parameters);
            setUp(true);
        } else {
            throw new LowpanException(LowpanException.LOWPAN_ALREADY);
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
            throwAsPublicException(x);
        } catch (ServiceSpecificException x) {
            throwAsPublicException(x);
        }
    }

    /**
     * Start a new commissioning session. Will fail if the interface is attached to a network or if
     * the interface is disabled.
     */
    public @NonNull LowpanCommissioningSession startCommissioningSession(
            @NonNull LowpanBeaconInfo beaconInfo) throws LowpanException {

        /* TODO: Implement startCommissioningSession */
        throw new LowpanException(LowpanException.LOWPAN_FEATURE_NOT_SUPPORTED);
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
            throwAsPublicException(x);
        } catch (ServiceSpecificException x) {
            throwAsPublicException(x);
        }
    }

    // Public Getters and Setters

    /**
     * Returns the name of this network interface.
     *
     * <p>Will return empty string if this interface is no longer viable.
     */
    @NonNull
    public String getName() {
        try {
            return mBinder.getName();
        } catch (RemoteException x) {
            // Catch and ignore all binder exceptions
            // when fetching the name.
            Log.e(TAG, x.toString());
        } catch (ServiceSpecificException x) {
            // Catch and ignore all service-specific exceptions
            // when fetching the name.
            Log.e(TAG, x.toString());
        }
        return "";
    }

    /**
     * Indicates if the interface is enabled or disabled.
     *
     * @see #setEnabled
     * @see android.net.lowpan.LowpanException#LOWPAN_DISABLED
     */
    public boolean isEnabled() {
        try {
            return getPropertyAsBoolean(LowpanProperties.KEY_INTERFACE_ENABLED);
        } catch (LowpanException x) {
            return false;
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
        setProperty(LowpanProperties.KEY_INTERFACE_ENABLED, enabled);
    }

    /**
     * Indicates if the network interface is up or down.
     *
     * @hide
     */
    public boolean isUp() {
        try {
            return getPropertyAsBoolean(LowpanProperties.KEY_INTERFACE_UP);
        } catch (LowpanException x) {
            return false;
        }
    }

    /**
     * Bring up or shut down the network interface.
     *
     * <p>This method brings up or shuts down the network interface, attaching or (gracefully)
     * detaching from the currently configured LoWPAN network as appropriate.
     *
     * @hide
     */
    public void setUp(boolean interfaceUp) throws LowpanException {
        setProperty(LowpanProperties.KEY_INTERFACE_UP, interfaceUp);
    }

    /**
     * Indicates if there is at least one peer in range.
     *
     * @return <code>true</code> if we have at least one other peer in range, <code>false</code>
     *     otherwise.
     */
    public boolean isConnected() {
        try {
            return getPropertyAsBoolean(LowpanProperties.KEY_INTERFACE_CONNECTED);
        } catch (LowpanException x) {
            return false;
        }
    }

    /**
     * Indicates if this interface is currently commissioned onto an existing network. If the
     * interface is commissioned, the interface may be brought up using setUp().
     */
    public boolean isCommissioned() {
        try {
            return getPropertyAsBoolean(LowpanProperties.KEY_INTERFACE_COMMISSIONED);
        } catch (LowpanException x) {
            return false;
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
            return getProperty(LowpanProperties.KEY_INTERFACE_STATE);
        } catch (LowpanException x) {
            Log.e(TAG, x.toString());
            return STATE_FAULT;
        }
    }

    /** TODO: doc */
    public LowpanIdentity getLowpanIdentity() {
        LowpanIdentity.Builder builder = new LowpanIdentity.Builder();
        try {
            builder.updateFromMap(getProperties(NETWORK_PROPERTY_KEYS));
        } catch (LowpanException x) {
            // We ignore all LoWPAN-specitic exceptions here.
        }

        return builder.build();
    }

    /**
     * TODO: doc
     *
     * @hide
     */
    public void setLowpanIdentity(LowpanIdentity network) throws LowpanException {
        Map<String, Object> map = new HashMap();
        LowpanIdentity.addToMap(map, network);
        setProperties(map);
    }

    /** TODO: doc */
    @NonNull
    public String getRole() {
        String role = null;

        try {
            role = getProperty(LowpanProperties.KEY_NETWORK_ROLE);
        } catch (LowpanException x) {
            // We ignore all LoWPAN-specitic exceptions here.
            Log.e(TAG, x.toString());
        }

        if (role == null) {
            role = ROLE_DETACHED;
        }

        return role;
    }

    /** TODO: doc */
    @Nullable
    public LowpanCredential getLowpanCredential() {
        LowpanCredential credential = null;

        try {
            Integer keyIndex = getProperty(LowpanProperties.KEY_NETWORK_MASTER_KEY_INDEX);

            if (keyIndex == null) {
                credential =
                        LowpanCredential.createMasterKey(
                                getProperty(LowpanProperties.KEY_NETWORK_MASTER_KEY));
            } else {
                credential =
                        LowpanCredential.createMasterKey(
                                getProperty(LowpanProperties.KEY_NETWORK_MASTER_KEY),
                                keyIndex.intValue());
            }
        } catch (LowpanException x) {
            // We ignore all LoWPAN-specitic exceptions here.
            Log.e(TAG, x.toString());
        }

        return credential;
    }

    /**
     * TODO: doc
     *
     * @hide
     */
    public void setLowpanCredential(LowpanCredential networkCredential) throws LowpanException {
        Map<String, Object> map = new HashMap();
        networkCredential.addToMap(map);
        setProperties(map);
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
                    public void onPropertiesChanged(Map properties) {
                        Runnable runnable =
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        for (String key : (Set<String>) properties.keySet()) {
                                            Object value = properties.get(key);
                                            switch (key) {
                                                case ILowpanInterface.KEY_INTERFACE_ENABLED:
                                                    cb.onEnabledChanged(
                                                            ((Boolean) value).booleanValue());
                                                    break;
                                                case ILowpanInterface.KEY_INTERFACE_UP:
                                                    cb.onUpChanged(
                                                            ((Boolean) value).booleanValue());
                                                    break;
                                                case ILowpanInterface.KEY_INTERFACE_CONNECTED:
                                                    cb.onConnectedChanged(
                                                            ((Boolean) value).booleanValue());
                                                    break;
                                                case ILowpanInterface.KEY_INTERFACE_STATE:
                                                    cb.onStateChanged((String) value);
                                                    break;
                                                case ILowpanInterface.KEY_NETWORK_NAME:
                                                case ILowpanInterface.KEY_NETWORK_PANID:
                                                case ILowpanInterface.KEY_NETWORK_XPANID:
                                                case ILowpanInterface.KEY_CHANNEL:
                                                    cb.onLowpanIdentityChanged(getLowpanIdentity());
                                                    break;
                                                case ILowpanInterface.KEY_NETWORK_ROLE:
                                                    cb.onRoleChanged(value.toString());
                                                    break;
                                            }
                                        }
                                        cb.onPropertiesChanged(properties);
                                    }
                                };

                        if (handler != null) {
                            handler.post(runnable);
                        } else {
                            runnable.run();
                        }
                    }
                };
        try {
            mBinder.addListener(listenerBinder);
        } catch (RemoteException x) {
            // Log and ignore. If this happens, this interface
            // is likely dead anyway.
            Log.e(TAG, x.toString());
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
                } catch (RemoteException x) {
                    // Catch and ignore all binder exceptions
                    Log.e(TAG, x.toString());
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
     * Advertise the given IP prefix as an on-mesh prefix.
     *
     * @hide
     */
    public void addOnMeshPrefix(IpPrefix prefix, int flags) throws LowpanException {
        try {
            mBinder.addOnMeshPrefix(prefix, flags);
        } catch (RemoteException x) {
            throwAsPublicException(x);
        } catch (ServiceSpecificException x) {
            throwAsPublicException(x);
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
            // Catch and ignore all binder exceptions
            Log.e(TAG, x.toString());
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
            throwAsPublicException(x);
        } catch (ServiceSpecificException x) {
            throwAsPublicException(x);
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
            // Catch and ignore all binder exceptions
            Log.e(TAG, x.toString());
        } catch (ServiceSpecificException x) {
            // Catch and ignore all service exceptions
            Log.e(TAG, x.toString());
        }
    }
}
