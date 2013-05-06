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

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.Messenger;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;

import java.util.concurrent.CountDownLatch;

import com.android.internal.util.AsyncChannel;
import com.android.internal.util.Protocol;

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
 * <p> The API is asynchronous and responses to requests from an application are on listener
 * callbacks on a seperate thread.
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
 * <p> Once the peer application discovers the "Example" http srevice, and needs to receive data
 * from the "Example" application, it can initiate a resolve with {@link #resolveService} to
 * resolve the host and port details for the purpose of establishing a connection. A successful
 * resolve is notified on {@link ResolveListener#onServiceResolved} and a failure is notified
 * on {@link ResolveListener#onResolveFailed}.
 *
 * Applications can reserve for a service type at
 * http://www.iana.org/form/ports-service. Existing services can be found at
 * http://www.iana.org/assignments/service-names-port-numbers/service-names-port-numbers.xml
 *
 * Get an instance of this class by calling {@link android.content.Context#getSystemService(String)
 * Context.getSystemService(Context.NSD_SERVICE)}.
 *
 * {@see NsdServiceInfo}
 */
public final class NsdManager {
    private static final String TAG = "NsdManager";
    INsdManager mService;

    /**
     * Broadcast intent action to indicate whether network service discovery is
     * enabled or disabled. An extra {@link #EXTRA_NSD_STATE} provides the state
     * information as int.
     *
     * @see #EXTRA_NSD_STATE
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_NSD_STATE_CHANGED =
        "android.net.nsd.STATE_CHANGED";

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

    private Context mContext;

    private static final int INVALID_LISTENER_KEY = 0;
    private int mListenerKey = 1;
    private final SparseArray mListenerMap = new SparseArray();
    private final SparseArray<NsdServiceInfo> mServiceMap = new SparseArray<NsdServiceInfo>();
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

    private class ServiceHandler extends Handler {
        ServiceHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message message) {
            Object listener = getListener(message.arg2);
            boolean listenerRemove = true;
            switch (message.what) {
                case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED:
                    mAsyncChannel.sendMessage(AsyncChannel.CMD_CHANNEL_FULL_CONNECTION);
                    break;
                case AsyncChannel.CMD_CHANNEL_FULLY_CONNECTED:
                    mConnected.countDown();
                    break;
                case AsyncChannel.CMD_CHANNEL_DISCONNECTED:
                    Log.e(TAG, "Channel lost");
                    break;
                case DISCOVER_SERVICES_STARTED:
                    String s = ((NsdServiceInfo) message.obj).getServiceType();
                    ((DiscoveryListener) listener).onDiscoveryStarted(s);
                    // Keep listener until stop discovery
                    listenerRemove = false;
                    break;
                case DISCOVER_SERVICES_FAILED:
                    ((DiscoveryListener) listener).onStartDiscoveryFailed(
                            getNsdService(message.arg2).getServiceType(), message.arg1);
                    break;
                case SERVICE_FOUND:
                    ((DiscoveryListener) listener).onServiceFound((NsdServiceInfo) message.obj);
                    // Keep listener until stop discovery
                    listenerRemove = false;
                    break;
                case SERVICE_LOST:
                    ((DiscoveryListener) listener).onServiceLost((NsdServiceInfo) message.obj);
                    // Keep listener until stop discovery
                    listenerRemove = false;
                    break;
                case STOP_DISCOVERY_FAILED:
                    ((DiscoveryListener) listener).onStopDiscoveryFailed(
                            getNsdService(message.arg2).getServiceType(), message.arg1);
                    break;
                case STOP_DISCOVERY_SUCCEEDED:
                    ((DiscoveryListener) listener).onDiscoveryStopped(
                            getNsdService(message.arg2).getServiceType());
                    break;
                case REGISTER_SERVICE_FAILED:
                    ((RegistrationListener) listener).onRegistrationFailed(
                            getNsdService(message.arg2), message.arg1);
                    break;
                case REGISTER_SERVICE_SUCCEEDED:
                    ((RegistrationListener) listener).onServiceRegistered(
                            (NsdServiceInfo) message.obj);
                    // Keep listener until unregister
                    listenerRemove = false;
                    break;
                case UNREGISTER_SERVICE_FAILED:
                    ((RegistrationListener) listener).onUnregistrationFailed(
                            getNsdService(message.arg2), message.arg1);
                    break;
                case UNREGISTER_SERVICE_SUCCEEDED:
                    ((RegistrationListener) listener).onServiceUnregistered(
                            getNsdService(message.arg2));
                    break;
                case RESOLVE_SERVICE_FAILED:
                    ((ResolveListener) listener).onResolveFailed(
                            getNsdService(message.arg2), message.arg1);
                    break;
                case RESOLVE_SERVICE_SUCCEEDED:
                    ((ResolveListener) listener).onServiceResolved((NsdServiceInfo) message.obj);
                    break;
                default:
                    Log.d(TAG, "Ignored " + message);
                    break;
            }
            if (listenerRemove) {
                removeListener(message.arg2);
            }
        }
    }

    private int putListener(Object listener, NsdServiceInfo s) {
        if (listener == null) return INVALID_LISTENER_KEY;
        int key;
        synchronized (mMapLock) {
            do {
                key = mListenerKey++;
            } while (key == INVALID_LISTENER_KEY);
            mListenerMap.put(key, listener);
            mServiceMap.put(key, s);
        }
        return key;
    }

    private Object getListener(int key) {
        if (key == INVALID_LISTENER_KEY) return null;
        synchronized (mMapLock) {
            return mListenerMap.get(key);
        }
    }

    private NsdServiceInfo getNsdService(int key) {
        synchronized (mMapLock) {
            return mServiceMap.get(key);
        }
    }

    private void removeListener(int key) {
        if (key == INVALID_LISTENER_KEY) return;
        synchronized (mMapLock) {
            mListenerMap.remove(key);
            mServiceMap.remove(key);
        }
    }

    private int getListenerKey(Object listener) {
        synchronized (mMapLock) {
            int valueIndex = mListenerMap.indexOfValue(listener);
            if (valueIndex != -1) {
                return mListenerMap.keyAt(valueIndex);
            }
        }
        return INVALID_LISTENER_KEY;
    }


    /**
     * Initialize AsyncChannel
     */
    private void init() {
        final Messenger messenger = getMessenger();
        if (messenger == null) throw new RuntimeException("Failed to initialize");
        HandlerThread t = new HandlerThread("NsdManager");
        t.start();
        mHandler = new ServiceHandler(t.getLooper());
        mAsyncChannel.connect(mContext, mHandler, messenger);
        try {
            mConnected.await();
        } catch (InterruptedException e) {
            Log.e(TAG, "interrupted wait at init");
        }
    }

    /**
     * Register a service to be discovered by other services.
     *
     * <p> The function call immediately returns after sending a request to register service
     * to the framework. The application is notified of a success to initiate
     * discovery through the callback {@link RegistrationListener#onServiceRegistered} or a failure
     * through {@link RegistrationListener#onRegistrationFailed}.
     *
     * @param serviceInfo The service being registered
     * @param protocolType The service discovery protocol
     * @param listener The listener notifies of a successful registration and is used to
     * unregister this service through a call on {@link #unregisterService}. Cannot be null.
     */
    public void registerService(NsdServiceInfo serviceInfo, int protocolType,
            RegistrationListener listener) {
        if (TextUtils.isEmpty(serviceInfo.getServiceName()) ||
                TextUtils.isEmpty(serviceInfo.getServiceType())) {
            throw new IllegalArgumentException("Service name or type cannot be empty");
        }
        if (serviceInfo.getPort() <= 0) {
            throw new IllegalArgumentException("Invalid port number");
        }
        if (listener == null) {
            throw new IllegalArgumentException("listener cannot be null");
        }
        if (protocolType != PROTOCOL_DNS_SD) {
            throw new IllegalArgumentException("Unsupported protocol");
        }
        mAsyncChannel.sendMessage(REGISTER_SERVICE, 0, putListener(listener, serviceInfo),
                serviceInfo);
    }

    /**
     * Unregister a service registered through {@link #registerService}. A successful
     * unregister is notified to the application with a call to
     * {@link RegistrationListener#onServiceUnregistered}.
     *
     * @param listener This should be the listener object that was passed to
     * {@link #registerService}. It identifies the service that should be unregistered
     * and notifies of a successful unregistration.
     */
    public void unregisterService(RegistrationListener listener) {
        int id = getListenerKey(listener);
        if (id == INVALID_LISTENER_KEY) {
            throw new IllegalArgumentException("listener not registered");
        }
        if (listener == null) {
            throw new IllegalArgumentException("listener cannot be null");
        }
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
     * @param serviceType The service type being discovered. Examples include "_http._tcp" for
     * http services or "_ipp._tcp" for printers
     * @param protocolType The service discovery protocol
     * @param listener  The listener notifies of a successful discovery and is used
     * to stop discovery on this serviceType through a call on {@link #stopServiceDiscovery}.
     * Cannot be null.
     */
    public void discoverServices(String serviceType, int protocolType, DiscoveryListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener cannot be null");
        }
        if (TextUtils.isEmpty(serviceType)) {
            throw new IllegalArgumentException("Service type cannot be empty");
        }

        if (protocolType != PROTOCOL_DNS_SD) {
            throw new IllegalArgumentException("Unsupported protocol");
        }

        NsdServiceInfo s = new NsdServiceInfo();
        s.setServiceType(serviceType);
        mAsyncChannel.sendMessage(DISCOVER_SERVICES, 0, putListener(listener, s), s);
    }

    /**
     * Stop service discovery initiated with {@link #discoverServices}. An active service
     * discovery is notified to the application with {@link DiscoveryListener#onDiscoveryStarted}
     * and it stays active until the application invokes a stop service discovery. A successful
     * stop is notified to with a call to {@link DiscoveryListener#onDiscoveryStopped}.
     *
     * <p> Upon failure to stop service discovery, application is notified through
     * {@link DiscoveryListener#onStopDiscoveryFailed}.
     *
     * @param listener This should be the listener object that was passed to {@link #discoverServices}.
     * It identifies the discovery that should be stopped and notifies of a successful stop.
     */
    public void stopServiceDiscovery(DiscoveryListener listener) {
        int id = getListenerKey(listener);
        if (id == INVALID_LISTENER_KEY) {
            throw new IllegalArgumentException("service discovery not active on listener");
        }
        if (listener == null) {
            throw new IllegalArgumentException("listener cannot be null");
        }
        mAsyncChannel.sendMessage(STOP_DISCOVERY, 0, id);
    }

    /**
     * Resolve a discovered service. An application can resolve a service right before
     * establishing a connection to fetch the IP and port details on which to setup
     * the connection.
     *
     * @param serviceInfo service to be resolved
     * @param listener to receive callback upon success or failure. Cannot be null.
     */
    public void resolveService(NsdServiceInfo serviceInfo, ResolveListener listener) {
        if (TextUtils.isEmpty(serviceInfo.getServiceName()) ||
                TextUtils.isEmpty(serviceInfo.getServiceType())) {
            throw new IllegalArgumentException("Service name or type cannot be empty");
        }
        if (listener == null) {
            throw new IllegalArgumentException("listener cannot be null");
        }
        mAsyncChannel.sendMessage(RESOLVE_SERVICE, 0, putListener(listener, serviceInfo),
                serviceInfo);
    }

    /** Internal use only @hide */
    public void setEnabled(boolean enabled) {
        try {
            mService.setEnabled(enabled);
        } catch (RemoteException e) { }
    }

    /**
     * Get a reference to NetworkService handler. This is used to establish
     * an AsyncChannel communication with the service
     *
     * @return Messenger pointing to the NetworkService handler
     */
    private Messenger getMessenger() {
        try {
            return mService.getMessenger();
        } catch (RemoteException e) {
            return null;
        }
    }
}
