/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.net.nsd;

import static com.android.internal.util.Preconditions.checkArgument;
import static com.android.internal.util.Preconditions.checkNotNull;
import static com.android.internal.util.Preconditions.checkStringNotEmpty;

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SystemService;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.AsyncChannel;
import com.android.internal.util.Protocol;

import java.util.concurrent.CountDownLatch;

/**
 * The Network Service Discovery Manager class provides the API to discover services
 * on a network. As an example, if device A and device B are connected over a Wi-Fi
 * network, a game registered on device A can be discovered by a game on device
 * B. Another example use case is an application discovering printers on the network.
 *
 * <p> The API currently supports DNS based service discovery and discovery is currently
 * limited to a local network over Multicast DNS. DNS service discovery is described at
 * http://files.dns-sd.org/draft-cheshire-dnsext-dns-sd.txt
 *
 * <p> The API is asynchronous, and responses to requests from an application are on listener
 * callbacks on a separate internal thread.
 *
 * <p> There are three main operations the API supports - registration, discovery and resolution.
 * <pre>
 *                          Application start
 *                                 |
 *                                 |
 *                                 |                  onServiceRegistered()
 *                     Register any local services  /
 *                      to be advertised with       \
 *                       registerService()            onRegistrationFailed()
 *                                 |
 *                                 |
 *                          discoverServices()
 *                                 |
 *                      Maintain a list to track
 *                        discovered services
 *                                 |
 *                                 |--------->
 *                                 |          |
 *                                 |      onServiceFound()
 *                                 |          |
 *                                 |     add service to list
 *                                 |          |
 *                                 |<----------
 *                                 |
 *                                 |--------->
 *                                 |          |
 *                                 |      onServiceLost()
 *                                 |          |
 *                                 |   remove service from list
 *                                 |          |
 *                                 |<----------
 *                                 |
 *                                 |
 *                                 | Connect to a service
 *                                 | from list ?
 *                                 |
 *                          resolveService()
 *                                 |
 *                         onServiceResolved()
 *                                 |
 *                     Establish connection to service
 *                     with the host and port information
 *
 * </pre>
 * An application that needs to advertise itself over a network for other applications to
 * discover it can do so with a call to {@link #registerService}. If Example is a http based
 * application that can provide HTML data to peer services, it can register a name "Example"
 * with service type "_http._tcp". A successful registration is notified with a callback to
 * {@link RegistrationListener#onServiceRegistered} and a failure to register is notified
 * over {@link RegistrationListener#onRegistrationFailed}
 *
 * <p> A peer application looking for http services can initiate a discovery for "_http._tcp"
 * with a call to {@link #discoverServices}. A service found is notified with a callback
 * to {@link DiscoveryListener#onServiceFound} and a service lost is notified on
 * {@link DiscoveryListener#onServiceLost}.
 *
 * <p> Once the peer application discovers the "Example" http service, and either needs to read the
 * attributes of the service or wants to receive data from the "Example" application, it can
 * initiate a resolve with {@link #resolveService} to resolve the attributes, host, and port
 * details. A successful resolve is notified on {@link ResolveListener#onServiceResolved} and a
 * failure is notified on {@link ResolveListener#onResolveFailed}.
 *
 * Applications can reserve for a service type at
 * http://www.iana.org/form/ports-service. Existing services can be found at
 * http://www.iana.org/assignments/service-names-port-numbers/service-names-port-numbers.xml
 *
 * {@see NsdServiceInfo}
 */
@SystemService(Context.NSD_SERVICE)
public final class NsdManager {
    private static final String TAG = NsdManager.class.getSimpleName();
    private static final boolean DBG = false;

    /**
     * Broadcast intent action to indicate whether network service discovery is
     * enabled or disabled. An extra {@link #EXTRA_NSD_STATE} provides the state
     * information as int.
     *
     * @see #EXTRA_NSD_STATE
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_NSD_STATE_CHANGED = "android.net.nsd.STATE_CHANGED";

    /**
     * The lookup key for an int that indicates whether network service discovery is enabled
     * or disabled. Retrieve it with {@link android.content.Intent#getIntExtra(String,int)}.
     *
     * @see #NSD_STATE_DISABLED
     * @see #NSD_STATE_ENABLED
     */
    public static final String EXTRA_NSD_STATE = "nsd_state";

    /**
     * Network service discovery is disabled
     *
     * @see #ACTION_NSD_STATE_CHANGED
     */
    public static final int NSD_STATE_DISABLED = 1;

    /**
     * Network service discovery is enabled
     *
     * @see #ACTION_NSD_STATE_CHANGED
     */
    public static final int NSD_STATE_ENABLED = 2;

    private static final int BASE = Protocol.BASE_NSD_MANAGER;

    /** @hide */
    public static final int DISCOVER_SERVICES                       = BASE + 1;
    /** @hide */
    public static final int DISCOVER_SERVICES_STARTED               = BASE + 2;
    /** @hide */
    public static final int DISCOVER_SERVICES_FAILED                = BASE + 3;
    /** @hide */
    public static final int SERVICE_FOUND                           = BASE + 4;
    /** @hide */
    public static final int SERVICE_LOST                            = BASE + 5;

    /** @hide */
    public static final int STOP_DISCOVERY                          = BASE + 6;
    /** @hide */
    public static final int STOP_DISCOVERY_FAILED                   = BASE + 7;
    /** @hide */
    public static final int STOP_DISCOVERY_SUCCEEDED                = BASE + 8;

    /** @hide */
    public static final int REGISTER_SERVICE                        = BASE + 9;
    /** @hide */
    public static final int REGISTER_SERVICE_FAILED                 = BASE + 10;
    /** @hide */
    public static final int REGISTER_SERVICE_SUCCEEDED              = BASE + 11;

    /** @hide */
    public static final int UNREGISTER_SERVICE                      = BASE + 12;
    /** @hide */
    public static final int UNREGISTER_SERVICE_FAILED               = BASE + 13;
    /** @hide */
    public static final int UNREGISTER_SERVICE_SUCCEEDED            = BASE + 14;

    /** @hide */
    public static final int RESOLVE_SERVICE                         = BASE + 18;
    /** @hide */
    public static final int RESOLVE_SERVICE_FAILED                  = BASE + 19;
    /** @hide */
    public static final int RESOLVE_SERVICE_SUCCEEDED               = BASE + 20;

    /** @hide */
    public static final int ENABLE                                  = BASE + 24;
    /** @hide */
    public static final int DISABLE                                 = BASE + 25;

    /** @hide */
    public static final int NATIVE_DAEMON_EVENT                     = BASE + 26;

    /** Dns based service discovery protocol */
    public static final int PROTOCOL_DNS_SD = 0x0001;

    private static final SparseArray<String> EVENT_NAMES = new SparseArray<>();
    static {
        EVENT_NAMES.put(DISCOVER_SERVICES, "DISCOVER_SERVICES");
        EVENT_NAMES.put(DISCOVER_SERVICES_STARTED, "DISCOVER_SERVICES_STARTED");
        EVENT_NAMES.put(DISCOVER_SERVICES_FAILED, "DISCOVER_SERVICES_FAILED");
        EVENT_NAMES.put(SERVICE_FOUND, "SERVICE_FOUND");
        EVENT_NAMES.put(SERVICE_LOST, "SERVICE_LOST");
        EVENT_NAMES.put(STOP_DISCOVERY, "STOP_DISCOVERY");
        EVENT_NAMES.put(STOP_DISCOVERY_FAILED, "STOP_DISCOVERY_FAILED");
        EVENT_NAMES.put(STOP_DISCOVERY_SUCCEEDED, "STOP_DISCOVERY_SUCCEEDED");
        EVENT_NAMES.put(REGISTER_SERVICE, "REGISTER_SERVICE");
        EVENT_NAMES.put(REGISTER_SERVICE_FAILED, "REGISTER_SERVICE_FAILED");
        EVENT_NAMES.put(REGISTER_SERVICE_SUCCEEDED, "REGISTER_SERVICE_SUCCEEDED");
        EVENT_NAMES.put(UNREGISTER_SERVICE, "UNREGISTER_SERVICE");
        EVENT_NAMES.put(UNREGISTER_SERVICE_FAILED, "UNREGISTER_SERVICE_FAILED");
        EVENT_NAMES.put(UNREGISTER_SERVICE_SUCCEEDED, "UNREGISTER_SERVICE_SUCCEEDED");
        EVENT_NAMES.put(RESOLVE_SERVICE, "RESOLVE_SERVICE");
        EVENT_NAMES.put(RESOLVE_SERVICE_FAILED, "RESOLVE_SERVICE_FAILED");
        EVENT_NAMES.put(RESOLVE_SERVICE_SUCCEEDED, "RESOLVE_SERVICE_SUCCEEDED");
        EVENT_NAMES.put(ENABLE, "ENABLE");
        EVENT_NAMES.put(DISABLE, "DISABLE");
        EVENT_NAMES.put(NATIVE_DAEMON_EVENT, "NATIVE_DAEMON_EVENT");
    }

    /** @hide */
    public static String nameOf(int event) {
        String name = EVENT_NAMES.get(event);
        if (name == null) {
            return Integer.toString(event);
        }
        return name;
    }

    private static final int FIRST_LISTENER_KEY = 1;

    private final INsdManager mService;
    private final Context mContext;

    private int mListenerKey = FIRST_LISTENER_KEY;
    private final SparseArray mListenerMap = new SparseArray();
    private final SparseArray<NsdServiceInfo> mServiceMap = new SparseArray<>();
    private final Object mMapLock = new Object();

    private final AsyncChannel mAsyncChannel = new AsyncChannel();
    private ServiceHandler mHandler;
    private final CountDownLatch mConnected = new CountDownLatch(1);

    /**
     * Create a new Nsd instance. Applications use
     * {@link android.content.Context#getSystemService Context.getSystemService()} to retrieve
     * {@link android.content.Context#NSD_SERVICE Context.NSD_SERVICE}.
     * @param service the Binder interface
     * @hide - hide this because it takes in a parameter of type INsdManager, which
     * is a system private class.
     */
    public NsdManager(Context context, INsdManager service) {
        mService = service;
        mContext = context;
        init();
    }

    /**
     * @hide
     */
    @VisibleForTesting
    public void disconnect() {
        mAsyncChannel.disconnect();
        mHandler.getLooper().quitSafely();
    }

    /**
     * Failures are passed with {@link RegistrationListener#onRegistrationFailed},
     * {@link RegistrationListener#onUnregistrationFailed},
     * {@link DiscoveryListener#onStartDiscoveryFailed},
     * {@link DiscoveryListener#onStopDiscoveryFailed} or {@link ResolveListener#onResolveFailed}.
     *
     * Indicates that the operation failed due to an internal error.
     */
    public static final int FAILURE_INTERNAL_ERROR               = 0;

    /**
     * Indicates that the operation failed because it is already active.
     */
    public static final int FAILURE_ALREADY_ACTIVE              = 3;

    /**
     * Indicates that the operation failed because the maximum outstanding
     * requests from the applications have reached.
     */
    public static final int FAILURE_MAX_LIMIT                   = 4;

    /** Interface for callback invocation for service discovery */
    public interface DiscoveryListener {

        public void onStartDiscoveryFailed(String serviceType, int errorCode);

        public void onStopDiscoveryFailed(String serviceType, int errorCode);

        public void onDiscoveryStarted(String serviceType);

        public void onDiscoveryStopped(String serviceType);

        public void onServiceFound(NsdServiceInfo serviceInfo);

        public void onServiceLost(NsdServiceInfo serviceInfo);
    }

    /** Interface for callback invocation for service registration */
    public interface RegistrationListener {

        public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode);

        public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode);

        public void onServiceRegistered(NsdServiceInfo serviceInfo);

        public void onServiceUnregistered(NsdServiceInfo serviceInfo);
    }

    /** Interface for callback invocation for service resolution */
    public interface ResolveListener {

        public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode);

        public void onServiceResolved(NsdServiceInfo serviceInfo);
    }

    @VisibleForTesting
    class ServiceHandler extends Handler {
        ServiceHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message message) {
            final int what = message.what;
            final int key = message.arg2;
            switch (what) {
                case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED:
                    mAsyncChannel.sendMessage(AsyncChannel.CMD_CHANNEL_FULL_CONNECTION);
                    return;
                case AsyncChannel.CMD_CHANNEL_FULLY_CONNECTED:
                    mConnected.countDown();
                    return;
                case AsyncChannel.CMD_CHANNEL_DISCONNECTED:
                    Log.e(TAG, "Channel lost");
                    return;
                default:
                    break;
            }
            final Object listener;
            final NsdServiceInfo ns;
            synchronized (mMapLock) {
                listener = mListenerMap.get(key);
                ns = mServiceMap.get(key);
            }
            if (listener == null) {
                Log.d(TAG, "Stale key " + message.arg2);
                return;
            }
            if (DBG) {
                Log.d(TAG, "received " + nameOf(what) + " for key " + key + ", service " + ns);
            }
            switch (what) {
                case DISCOVER_SERVICES_STARTED:
                    String s = getNsdServiceInfoType((NsdServiceInfo) message.obj);
                    ((DiscoveryListener) listener).onDiscoveryStarted(s);
                    break;
                case DISCOVER_SERVICES_FAILED:
                    removeListener(key);
                    ((DiscoveryListener) listener).onStartDiscoveryFailed(getNsdServiceInfoType(ns),
                            message.arg1);
                    break;
                case SERVICE_FOUND:
                    ((DiscoveryListener) listener).onServiceFound((NsdServiceInfo) message.obj);
                    break;
                case SERVICE_LOST:
                    ((DiscoveryListener) listener).onServiceLost((NsdServiceInfo) message.obj);
                    break;
                case STOP_DISCOVERY_FAILED:
                    // TODO: failure to stop discovery should be internal and retried internally, as
                    // the effect for the client is indistinguishable from STOP_DISCOVERY_SUCCEEDED
                    removeListener(key);
                    ((DiscoveryListener) listener).onStopDiscoveryFailed(getNsdServiceInfoType(ns),
                            message.arg1);
                    break;
                case STOP_DISCOVERY_SUCCEEDED:
                    removeListener(key);
                    ((DiscoveryListener) listener).onDiscoveryStopped(getNsdServiceInfoType(ns));
                    break;
                case REGISTER_SERVICE_FAILED:
                    removeListener(key);
                    ((RegistrationListener) listener).onRegistrationFailed(ns, message.arg1);
                    break;
                case REGISTER_SERVICE_SUCCEEDED:
                    ((RegistrationListener) listener).onServiceRegistered(
                            (NsdServiceInfo) message.obj);
                    break;
                case UNREGISTER_SERVICE_FAILED:
                    removeListener(key);
                    ((RegistrationListener) listener).onUnregistrationFailed(ns, message.arg1);
                    break;
                case UNREGISTER_SERVICE_SUCCEEDED:
                    // TODO: do not unregister listener until service is unregistered, or provide
                    // alternative way for unregistering ?
                    removeListener(message.arg2);
                    ((RegistrationListener) listener).onServiceUnregistered(ns);
                    break;
                case RESOLVE_SERVICE_FAILED:
                    removeListener(key);
                    ((ResolveListener) listener).onResolveFailed(ns, message.arg1);
                    break;
                case RESOLVE_SERVICE_SUCCEEDED:
                    removeListener(key);
                    ((ResolveListener) listener).onServiceResolved((NsdServiceInfo) message.obj);
                    break;
                default:
                    Log.d(TAG, "Ignored " + message);
                    break;
            }
        }
    }

    private int nextListenerKey() {
        // Ensure mListenerKey >= FIRST_LISTENER_KEY;
        mListenerKey = Math.max(FIRST_LISTENER_KEY, mListenerKey + 1);
        return mListenerKey;
    }

    // Assert that the listener is not in the map, then add it and returns its key
    private int putListener(Object listener, NsdServiceInfo s) {
        checkListener(listener);
        final int key;
        synchronized (mMapLock) {
            int valueIndex = mListenerMap.indexOfValue(listener);
            checkArgument(valueIndex == -1, "listener already in use");
            key = nextListenerKey();
            mListenerMap.put(key, listener);
            mServiceMap.put(key, s);
        }
        return key;
    }

    private void removeListener(int key) {
        synchronized (mMapLock) {
            mListenerMap.remove(key);
            mServiceMap.remove(key);
        }
    }

    private int getListenerKey(Object listener) {
        checkListener(listener);
        synchronized (mMapLock) {
            int valueIndex = mListenerMap.indexOfValue(listener);
            checkArgument(valueIndex != -1, "listener not registered");
            return mListenerMap.keyAt(valueIndex);
        }
    }

    private static String getNsdServiceInfoType(NsdServiceInfo s) {
        if (s == null) return "?";
        return s.getServiceType();
    }

    /**
     * Initialize AsyncChannel
     */
    private void init() {
        final Messenger messenger = getMessenger();
        if (messenger == null) {
            fatal("Failed to obtain service Messenger");
        }
        HandlerThread t = new HandlerThread("NsdManager");
        t.start();
        mHandler = new ServiceHandler(t.getLooper());
        mAsyncChannel.connect(mContext, mHandler, messenger);
        try {
            mConnected.await();
        } catch (InterruptedException e) {
            fatal("Interrupted wait at init");
        }
    }

    private static void fatal(String msg) {
        Log.e(TAG, msg);
        throw new RuntimeException(msg);
    }

    /**
     * Register a service to be discovered by other services.
     *
     * <p> The function call immediately returns after sending a request to register service
     * to the framework. The application is notified of a successful registration
     * through the callback {@link RegistrationListener#onServiceRegistered} or a failure
     * through {@link RegistrationListener#onRegistrationFailed}.
     *
     * <p> The application should call {@link #unregisterService} when the service
     * registration is no longer required, and/or whenever the application is stopped.
     *
     * @param serviceInfo The service being registered
     * @param protocolType The service discovery protocol
     * @param listener The listener notifies of a successful registration and is used to
     * unregister this service through a call on {@link #unregisterService}. Cannot be null.
     * Cannot be in use for an active service registration.
     */
    public void registerService(NsdServiceInfo serviceInfo, int protocolType,
            RegistrationListener listener) {
        checkArgument(serviceInfo.getPort() > 0, "Invalid port number");
        checkServiceInfo(serviceInfo);
        checkProtocol(protocolType);
        int key = putListener(listener, serviceInfo);
        mAsyncChannel.sendMessage(REGISTER_SERVICE, 0, key, serviceInfo);
    }

    /**
     * Unregister a service registered through {@link #registerService}. A successful
     * unregister is notified to the application with a call to
     * {@link RegistrationListener#onServiceUnregistered}.
     *
     * @param listener This should be the listener object that was passed to
     * {@link #registerService}. It identifies the service that should be unregistered
     * and notifies of a successful or unsuccessful unregistration via the listener
     * callbacks.  In API versions 20 and above, the listener object may be used for
     * another service registration once the callback has been called.  In API versions <= 19,
     * there is no entirely reliable way to know when a listener may be re-used, and a new
     * listener should be created for each service registration request.
     */
    public void unregisterService(RegistrationListener listener) {
        int id = getListenerKey(listener);
        mAsyncChannel.sendMessage(UNREGISTER_SERVICE, 0, id);
    }

    /**
     * Initiate service discovery to browse for instances of a service type. Service discovery
     * consumes network bandwidth and will continue until the application calls
     * {@link #stopServiceDiscovery}.
     *
     * <p> The function call immediately returns after sending a request to start service
     * discovery to the framework. The application is notified of a success to initiate
     * discovery through the callback {@link DiscoveryListener#onDiscoveryStarted} or a failure
     * through {@link DiscoveryListener#onStartDiscoveryFailed}.
     *
     * <p> Upon successful start, application is notified when a service is found with
     * {@link DiscoveryListener#onServiceFound} or when a service is lost with
     * {@link DiscoveryListener#onServiceLost}.
     *
     * <p> Upon failure to start, service discovery is not active and application does
     * not need to invoke {@link #stopServiceDiscovery}
     *
     * <p> The application should call {@link #stopServiceDiscovery} when discovery of this
     * service type is no longer required, and/or whenever the application is paused or
     * stopped.
     *
     * @param serviceType The service type being discovered. Examples include "_http._tcp" for
     * http services or "_ipp._tcp" for printers
     * @param protocolType The service discovery protocol
     * @param listener  The listener notifies of a successful discovery and is used
     * to stop discovery on this serviceType through a call on {@link #stopServiceDiscovery}.
     * Cannot be null. Cannot be in use for an active service discovery.
     */
    public void discoverServices(String serviceType, int protocolType, DiscoveryListener listener) {
        checkStringNotEmpty(serviceType, "Service type cannot be empty");
        checkProtocol(protocolType);

        NsdServiceInfo s = new NsdServiceInfo();
        s.setServiceType(serviceType);

        int key = putListener(listener, s);
        mAsyncChannel.sendMessage(DISCOVER_SERVICES, 0, key, s);
    }

    /**
     * Stop service discovery initiated with {@link #discoverServices}.  An active service
     * discovery is notified to the application with {@link DiscoveryListener#onDiscoveryStarted}
     * and it stays active until the application invokes a stop service discovery. A successful
     * stop is notified to with a call to {@link DiscoveryListener#onDiscoveryStopped}.
     *
     * <p> Upon failure to stop service discovery, application is notified through
     * {@link DiscoveryListener#onStopDiscoveryFailed}.
     *
     * @param listener This should be the listener object that was passed to {@link #discoverServices}.
     * It identifies the discovery that should be stopped and notifies of a successful or
     * unsuccessful stop.  In API versions 20 and above, the listener object may be used for
     * another service discovery once the callback has been called.  In API versions <= 19,
     * there is no entirely reliable way to know when a listener may be re-used, and a new
     * listener should be created for each service discovery request.
     */
    public void stopServiceDiscovery(DiscoveryListener listener) {
        int id = getListenerKey(listener);
        mAsyncChannel.sendMessage(STOP_DISCOVERY, 0, id);
    }

    /**
     * Resolve a discovered service. An application can resolve a service right before
     * establishing a connection to fetch the IP and port details on which to setup
     * the connection.
     *
     * @param serviceInfo service to be resolved
     * @param listener to receive callback upon success or failure. Cannot be null.
     * Cannot be in use for an active service resolution.
     */
    public void resolveService(NsdServiceInfo serviceInfo, ResolveListener listener) {
        checkServiceInfo(serviceInfo);
        int key = putListener(listener, serviceInfo);
        mAsyncChannel.sendMessage(RESOLVE_SERVICE, 0, key, serviceInfo);
    }

    /** Internal use only @hide */
    public void setEnabled(boolean enabled) {
        try {
            mService.setEnabled(enabled);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get a reference to NsdService handler. This is used to establish
     * an AsyncChannel communication with the service
     *
     * @return Messenger pointing to the NsdService handler
     */
    private Messenger getMessenger() {
        try {
            return mService.getMessenger();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private static void checkListener(Object listener) {
        checkNotNull(listener, "listener cannot be null");
    }

    private static void checkProtocol(int protocolType) {
        checkArgument(protocolType == PROTOCOL_DNS_SD, "Unsupported protocol");
    }

    private static void checkServiceInfo(NsdServiceInfo serviceInfo) {
        checkNotNull(serviceInfo, "NsdServiceInfo cannot be null");
        checkStringNotEmpty(serviceInfo.getServiceName(), "Service name cannot be empty");
        checkStringNotEmpty(serviceInfo.getServiceType(), "Service type cannot be empty");
    }
}
