/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SystemService;
import android.app.compat.CompatChanges;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.Network;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.Objects;
import java.util.concurrent.Executor;

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
     * When enabled, apps targeting < Android 12 are considered legacy for
     * the NSD native daemon.
     * The platform will only keep the daemon running as long as there are
     * any legacy apps connected.
     *
     * After Android 12, directly communicate with native daemon might not
     * work since the native damon won't always stay alive.
     * Use the NSD APIs from NsdManager as the replacement is recommended.
     * An another alternative could be bundling your own mdns solutions instead of
     * depending on the system mdns native daemon.
     *
     * @hide
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = android.os.Build.VERSION_CODES.S)
    public static final long RUN_NATIVE_NSD_ONLY_IF_LEGACY_APPS = 191844585L;

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

    /** @hide */
    public static final int DISCOVER_SERVICES                       = 1;
    /** @hide */
    public static final int DISCOVER_SERVICES_STARTED               = 2;
    /** @hide */
    public static final int DISCOVER_SERVICES_FAILED                = 3;
    /** @hide */
    public static final int SERVICE_FOUND                           = 4;
    /** @hide */
    public static final int SERVICE_LOST                            = 5;

    /** @hide */
    public static final int STOP_DISCOVERY                          = 6;
    /** @hide */
    public static final int STOP_DISCOVERY_FAILED                   = 7;
    /** @hide */
    public static final int STOP_DISCOVERY_SUCCEEDED                = 8;

    /** @hide */
    public static final int REGISTER_SERVICE                        = 9;
    /** @hide */
    public static final int REGISTER_SERVICE_FAILED                 = 10;
    /** @hide */
    public static final int REGISTER_SERVICE_SUCCEEDED              = 11;

    /** @hide */
    public static final int UNREGISTER_SERVICE                      = 12;
    /** @hide */
    public static final int UNREGISTER_SERVICE_FAILED               = 13;
    /** @hide */
    public static final int UNREGISTER_SERVICE_SUCCEEDED            = 14;

    /** @hide */
    public static final int RESOLVE_SERVICE                         = 15;
    /** @hide */
    public static final int RESOLVE_SERVICE_FAILED                  = 16;
    /** @hide */
    public static final int RESOLVE_SERVICE_SUCCEEDED               = 17;

    /** @hide */
    public static final int DAEMON_CLEANUP                          = 18;

    /** @hide */
    public static final int DAEMON_STARTUP                          = 19;

    /** @hide */
    public static final int ENABLE                                  = 20;
    /** @hide */
    public static final int DISABLE                                 = 21;

    /** @hide */
    public static final int NATIVE_DAEMON_EVENT                     = 22;

    /** @hide */
    public static final int REGISTER_CLIENT                         = 23;
    /** @hide */
    public static final int UNREGISTER_CLIENT                       = 24;

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
        EVENT_NAMES.put(DAEMON_CLEANUP, "DAEMON_CLEANUP");
        EVENT_NAMES.put(DAEMON_STARTUP, "DAEMON_STARTUP");
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

    private final INsdServiceConnector mService;
    private final Context mContext;

    private int mListenerKey = FIRST_LISTENER_KEY;
    @GuardedBy("mMapLock")
    private final SparseArray mListenerMap = new SparseArray();
    @GuardedBy("mMapLock")
    private final SparseArray<NsdServiceInfo> mServiceMap = new SparseArray<>();
    @GuardedBy("mMapLock")
    private final SparseArray<Executor> mExecutorMap = new SparseArray<>();
    private final Object mMapLock = new Object();
    // Map of listener key sent by client -> per-network discovery tracker
    @GuardedBy("mPerNetworkDiscoveryMap")
    private final ArrayMap<Integer, PerNetworkDiscoveryTracker>
            mPerNetworkDiscoveryMap = new ArrayMap<>();

    private final ServiceHandler mHandler;

    private class PerNetworkDiscoveryTracker {
        final String mServiceType;
        final int mProtocolType;
        final DiscoveryListener mBaseListener;
        final Executor mBaseExecutor;
        final ArrayMap<Network, DelegatingDiscoveryListener> mPerNetworkListeners =
                new ArrayMap<>();

        final NetworkCallback mNetworkCb = new NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                final DelegatingDiscoveryListener wrappedListener = new DelegatingDiscoveryListener(
                        network, mBaseListener);
                mPerNetworkListeners.put(network, wrappedListener);
                discoverServices(mServiceType, mProtocolType, network, mBaseExecutor,
                        wrappedListener);
            }

            @Override
            public void onLost(@NonNull Network network) {
                final DelegatingDiscoveryListener listener = mPerNetworkListeners.get(network);
                if (listener == null) return;
                listener.notifyAllServicesLost();
                // Listener will be removed from map in discovery stopped callback
                stopServiceDiscovery(listener);
            }
        };

        // Accessed from mHandler
        private boolean mStopRequested;

        public void start(@NonNull NetworkRequest request) {
            final ConnectivityManager cm = mContext.getSystemService(ConnectivityManager.class);
            cm.registerNetworkCallback(request, mNetworkCb, mHandler);
            mHandler.post(() -> mBaseListener.onDiscoveryStarted(mServiceType));
        }

        /**
         * Stop discovery on all networks tracked by this class.
         *
         * This will request all underlying listeners to stop, and the last one to stop will call
         * onDiscoveryStopped or onStopDiscoveryFailed.
         *
         * Must be called on the handler thread.
         */
        public void requestStop() {
            mHandler.post(() -> {
                mStopRequested = true;
                final ConnectivityManager cm = mContext.getSystemService(ConnectivityManager.class);
                cm.unregisterNetworkCallback(mNetworkCb);
                if (mPerNetworkListeners.size() == 0) {
                    mBaseListener.onDiscoveryStopped(mServiceType);
                    return;
                }
                for (int i = 0; i < mPerNetworkListeners.size(); i++) {
                    final DelegatingDiscoveryListener listener = mPerNetworkListeners.valueAt(i);
                    stopServiceDiscovery(listener);
                }
            });
        }

        private PerNetworkDiscoveryTracker(String serviceType, int protocolType,
                Executor baseExecutor, DiscoveryListener baseListener) {
            mServiceType = serviceType;
            mProtocolType = protocolType;
            mBaseExecutor = baseExecutor;
            mBaseListener = baseListener;
        }

        /**
         * Subset of NsdServiceInfo that is tracked to generate service lost notifications when a
         * network is lost.
         *
         * Service lost notifications only contain service name, type and network, so only track
         * that information (Network is known from the listener). This also implements
         * equals/hashCode for usage in maps.
         */
        private class TrackedNsdInfo {
            private final String mServiceName;
            private final String mServiceType;
            TrackedNsdInfo(NsdServiceInfo info) {
                mServiceName = info.getServiceName();
                mServiceType = info.getServiceType();
            }

            @Override
            public int hashCode() {
                return Objects.hash(mServiceName, mServiceType);
            }

            @Override
            public boolean equals(Object obj) {
                if (!(obj instanceof TrackedNsdInfo)) return false;
                final TrackedNsdInfo other = (TrackedNsdInfo) obj;
                return Objects.equals(mServiceName, other.mServiceName)
                        && Objects.equals(mServiceType, other.mServiceType);
            }
        }

        private class DelegatingDiscoveryListener implements DiscoveryListener {
            private final Network mNetwork;
            private final DiscoveryListener mWrapped;
            private final ArraySet<TrackedNsdInfo> mFoundInfo = new ArraySet<>();

            private DelegatingDiscoveryListener(Network network, DiscoveryListener listener) {
                mNetwork = network;
                mWrapped = listener;
            }

            void notifyAllServicesLost() {
                for (int i = 0; i < mFoundInfo.size(); i++) {
                    final TrackedNsdInfo trackedInfo = mFoundInfo.valueAt(i);
                    final NsdServiceInfo serviceInfo = new NsdServiceInfo(
                            trackedInfo.mServiceName, trackedInfo.mServiceType);
                    serviceInfo.setNetwork(mNetwork);
                    mWrapped.onServiceLost(serviceInfo);
                }
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                // The delegated listener is used when NsdManager takes care of starting/stopping
                // discovery on multiple networks. Failure to start on one network is not a global
                // failure to be reported up, as other networks may succeed: just log.
                Log.e(TAG, "Failed to start discovery for " + serviceType + " on " + mNetwork
                        + " with code " + errorCode);
                mPerNetworkListeners.remove(mNetwork);
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
                // Wrapped listener was called upon registration, it is not called for discovery
                // on each network
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Failed to stop discovery for " + serviceType + " on " + mNetwork
                        + " with code " + errorCode);
                mPerNetworkListeners.remove(mNetwork);
                if (mStopRequested && mPerNetworkListeners.size() == 0) {
                    // Do not report onStopDiscoveryFailed when some underlying listeners failed:
                    // this does not mean that all listeners did, and onStopDiscoveryFailed is not
                    // actionable anyway. Just report that discovery stopped.
                    mWrapped.onDiscoveryStopped(serviceType);
                }
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                mPerNetworkListeners.remove(mNetwork);
                if (mStopRequested && mPerNetworkListeners.size() == 0) {
                    mWrapped.onDiscoveryStopped(serviceType);
                }
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                mFoundInfo.add(new TrackedNsdInfo(serviceInfo));
                mWrapped.onServiceFound(serviceInfo);
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                mFoundInfo.remove(new TrackedNsdInfo(serviceInfo));
                mWrapped.onServiceLost(serviceInfo);
            }
        }
    }

    /**
     * Create a new Nsd instance. Applications use
     * {@link android.content.Context#getSystemService Context.getSystemService()} to retrieve
     * {@link android.content.Context#NSD_SERVICE Context.NSD_SERVICE}.
     * @param service the Binder interface
     * @hide - hide this because it takes in a parameter of type INsdManager, which
     * is a system private class.
     */
    public NsdManager(Context context, INsdManager service) {
        mContext = context;

        HandlerThread t = new HandlerThread("NsdManager");
        t.start();
        mHandler = new ServiceHandler(t.getLooper());

        try {
            mService = service.connect(new NsdCallbackImpl(mHandler));
        } catch (RemoteException e) {
            throw new RuntimeException("Failed to connect to NsdService");
        }

        // Only proactively start the daemon if the target SDK < S, otherwise the internal service
        // would automatically start/stop the native daemon as needed.
        if (!CompatChanges.isChangeEnabled(RUN_NATIVE_NSD_ONLY_IF_LEGACY_APPS)) {
            try {
                mService.startDaemon();
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to proactively start daemon");
                // Continue: the daemon can still be started on-demand later
            }
        }
    }

    private static class NsdCallbackImpl extends INsdManagerCallback.Stub {
        private final Handler mServHandler;

        NsdCallbackImpl(Handler serviceHandler) {
            mServHandler = serviceHandler;
        }

        private void sendInfo(int message, int listenerKey, NsdServiceInfo info) {
            mServHandler.sendMessage(mServHandler.obtainMessage(message, 0, listenerKey, info));
        }

        private void sendError(int message, int listenerKey, int error) {
            mServHandler.sendMessage(mServHandler.obtainMessage(message, error, listenerKey));
        }

        private void sendNoArg(int message, int listenerKey) {
            mServHandler.sendMessage(mServHandler.obtainMessage(message, 0, listenerKey));
        }

        @Override
        public void onDiscoverServicesStarted(int listenerKey, NsdServiceInfo info) {
            sendInfo(DISCOVER_SERVICES_STARTED, listenerKey, info);
        }

        @Override
        public void onDiscoverServicesFailed(int listenerKey, int error) {
            sendError(DISCOVER_SERVICES_FAILED, listenerKey, error);
        }

        @Override
        public void onServiceFound(int listenerKey, NsdServiceInfo info) {
            sendInfo(SERVICE_FOUND, listenerKey, info);
        }

        @Override
        public void onServiceLost(int listenerKey, NsdServiceInfo info) {
            sendInfo(SERVICE_LOST, listenerKey, info);
        }

        @Override
        public void onStopDiscoveryFailed(int listenerKey, int error) {
            sendError(STOP_DISCOVERY_FAILED, listenerKey, error);
        }

        @Override
        public void onStopDiscoverySucceeded(int listenerKey) {
            sendNoArg(STOP_DISCOVERY_SUCCEEDED, listenerKey);
        }

        @Override
        public void onRegisterServiceFailed(int listenerKey, int error) {
            sendError(REGISTER_SERVICE_FAILED, listenerKey, error);
        }

        @Override
        public void onRegisterServiceSucceeded(int listenerKey, NsdServiceInfo info) {
            sendInfo(REGISTER_SERVICE_SUCCEEDED, listenerKey, info);
        }

        @Override
        public void onUnregisterServiceFailed(int listenerKey, int error) {
            sendError(UNREGISTER_SERVICE_FAILED, listenerKey, error);
        }

        @Override
        public void onUnregisterServiceSucceeded(int listenerKey) {
            sendNoArg(UNREGISTER_SERVICE_SUCCEEDED, listenerKey);
        }

        @Override
        public void onResolveServiceFailed(int listenerKey, int error) {
            sendError(RESOLVE_SERVICE_FAILED, listenerKey, error);
        }

        @Override
        public void onResolveServiceSucceeded(int listenerKey, NsdServiceInfo info) {
            sendInfo(RESOLVE_SERVICE_SUCCEEDED, listenerKey, info);
        }
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
            final Object listener;
            final NsdServiceInfo ns;
            final Executor executor;
            synchronized (mMapLock) {
                listener = mListenerMap.get(key);
                ns = mServiceMap.get(key);
                executor = mExecutorMap.get(key);
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
                    final String s = getNsdServiceInfoType((NsdServiceInfo) message.obj);
                    executor.execute(() -> ((DiscoveryListener) listener).onDiscoveryStarted(s));
                    break;
                case DISCOVER_SERVICES_FAILED:
                    removeListener(key);
                    executor.execute(() -> ((DiscoveryListener) listener).onStartDiscoveryFailed(
                            getNsdServiceInfoType(ns), message.arg1));
                    break;
                case SERVICE_FOUND:
                    executor.execute(() -> ((DiscoveryListener) listener).onServiceFound(
                            (NsdServiceInfo) message.obj));
                    break;
                case SERVICE_LOST:
                    executor.execute(() -> ((DiscoveryListener) listener).onServiceLost(
                            (NsdServiceInfo) message.obj));
                    break;
                case STOP_DISCOVERY_FAILED:
                    // TODO: failure to stop discovery should be internal and retried internally, as
                    // the effect for the client is indistinguishable from STOP_DISCOVERY_SUCCEEDED
                    removeListener(key);
                    executor.execute(() -> ((DiscoveryListener) listener).onStopDiscoveryFailed(
                            getNsdServiceInfoType(ns), message.arg1));
                    break;
                case STOP_DISCOVERY_SUCCEEDED:
                    removeListener(key);
                    executor.execute(() -> ((DiscoveryListener) listener).onDiscoveryStopped(
                            getNsdServiceInfoType(ns)));
                    break;
                case REGISTER_SERVICE_FAILED:
                    removeListener(key);
                    executor.execute(() -> ((RegistrationListener) listener).onRegistrationFailed(
                            ns, message.arg1));
                    break;
                case REGISTER_SERVICE_SUCCEEDED:
                    executor.execute(() -> ((RegistrationListener) listener).onServiceRegistered(
                            (NsdServiceInfo) message.obj));
                    break;
                case UNREGISTER_SERVICE_FAILED:
                    removeListener(key);
                    executor.execute(() -> ((RegistrationListener) listener).onUnregistrationFailed(
                            ns, message.arg1));
                    break;
                case UNREGISTER_SERVICE_SUCCEEDED:
                    // TODO: do not unregister listener until service is unregistered, or provide
                    // alternative way for unregistering ?
                    removeListener(message.arg2);
                    executor.execute(() -> ((RegistrationListener) listener).onServiceUnregistered(
                            ns));
                    break;
                case RESOLVE_SERVICE_FAILED:
                    removeListener(key);
                    executor.execute(() -> ((ResolveListener) listener).onResolveFailed(
                            ns, message.arg1));
                    break;
                case RESOLVE_SERVICE_SUCCEEDED:
                    removeListener(key);
                    executor.execute(() -> ((ResolveListener) listener).onServiceResolved(
                            (NsdServiceInfo) message.obj));
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
    private int putListener(Object listener, Executor e, NsdServiceInfo s) {
        checkListener(listener);
        final int key;
        synchronized (mMapLock) {
            int valueIndex = mListenerMap.indexOfValue(listener);
            if (valueIndex != -1) {
                throw new IllegalArgumentException("listener already in use");
            }
            key = nextListenerKey();
            mListenerMap.put(key, listener);
            mServiceMap.put(key, s);
            mExecutorMap.put(key, e);
        }
        return key;
    }

    private void removeListener(int key) {
        synchronized (mMapLock) {
            mListenerMap.remove(key);
            mServiceMap.remove(key);
            mExecutorMap.remove(key);
        }
    }

    private int getListenerKey(Object listener) {
        checkListener(listener);
        synchronized (mMapLock) {
            int valueIndex = mListenerMap.indexOfValue(listener);
            if (valueIndex == -1) {
                throw new IllegalArgumentException("listener not registered");
            }
            return mListenerMap.keyAt(valueIndex);
        }
    }

    private static String getNsdServiceInfoType(NsdServiceInfo s) {
        if (s == null) return "?";
        return s.getServiceType();
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
        registerService(serviceInfo, protocolType, Runnable::run, listener);
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
     * @param serviceInfo The service being registered
     * @param protocolType The service discovery protocol
     * @param executor Executor to run listener callbacks with
     * @param listener The listener notifies of a successful registration and is used to
     * unregister this service through a call on {@link #unregisterService}. Cannot be null.
     */
    public void registerService(@NonNull NsdServiceInfo serviceInfo, int protocolType,
            @NonNull Executor executor, @NonNull RegistrationListener listener) {
        if (serviceInfo.getPort() <= 0) {
            throw new IllegalArgumentException("Invalid port number");
        }
        checkServiceInfo(serviceInfo);
        checkProtocol(protocolType);
        int key = putListener(listener, executor, serviceInfo);
        try {
            mService.registerService(key, serviceInfo);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
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
        try {
            mService.unregisterService(id);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
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
        discoverServices(serviceType, protocolType, (Network) null, Runnable::run, listener);
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
     * @param serviceType The service type being discovered. Examples include "_http._tcp" for
     * http services or "_ipp._tcp" for printers
     * @param protocolType The service discovery protocol
     * @param network Network to discover services on, or null to discover on all available networks
     * @param executor Executor to run listener callbacks with
     * @param listener  The listener notifies of a successful discovery and is used
     * to stop discovery on this serviceType through a call on {@link #stopServiceDiscovery}.
     */
    public void discoverServices(@NonNull String serviceType, int protocolType,
            @Nullable Network network, @NonNull Executor executor,
            @NonNull DiscoveryListener listener) {
        if (TextUtils.isEmpty(serviceType)) {
            throw new IllegalArgumentException("Service type cannot be empty");
        }
        checkProtocol(protocolType);

        NsdServiceInfo s = new NsdServiceInfo();
        s.setServiceType(serviceType);
        s.setNetwork(network);

        int key = putListener(listener, executor, s);
        try {
            mService.discoverServices(key, s);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
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
     * <p> During discovery, new networks may connect or existing networks may disconnect - for
     * example if wifi is reconnected. When a service was found on a network that disconnects,
     * {@link DiscoveryListener#onServiceLost} will be called. If a new network connects that
     * matches the {@link NetworkRequest}, {@link DiscoveryListener#onServiceFound} will be called
     * for services found on that network. Applications that do not want to track networks
     * themselves are encouraged to use this method instead of other overloads of
     * {@code discoverServices}, as they will receive proper notifications when a service becomes
     * available or unavailable due to network changes.
     * @param serviceType The service type being discovered. Examples include "_http._tcp" for
     * http services or "_ipp._tcp" for printers
     * @param protocolType The service discovery protocol
     * @param networkRequest Request specifying networks that should be considered when discovering
     * @param executor Executor to run listener callbacks with
     * @param listener  The listener notifies of a successful discovery and is used
     * to stop discovery on this serviceType through a call on {@link #stopServiceDiscovery}.
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_NETWORK_STATE)
    public void discoverServices(@NonNull String serviceType, int protocolType,
            @NonNull NetworkRequest networkRequest, @NonNull Executor executor,
            @NonNull DiscoveryListener listener) {
        if (TextUtils.isEmpty(serviceType)) {
            throw new IllegalArgumentException("Service type cannot be empty");
        }
        Objects.requireNonNull(networkRequest, "NetworkRequest cannot be null");
        checkProtocol(protocolType);

        NsdServiceInfo s = new NsdServiceInfo();
        s.setServiceType(serviceType);

        final int baseListenerKey = putListener(listener, executor, s);

        final PerNetworkDiscoveryTracker discoveryInfo = new PerNetworkDiscoveryTracker(
                serviceType, protocolType, executor, listener);

        synchronized (mPerNetworkDiscoveryMap) {
            mPerNetworkDiscoveryMap.put(baseListenerKey, discoveryInfo);
            discoveryInfo.start(networkRequest);
        }
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
        // If this is a PerNetworkDiscovery request, handle it as such
        synchronized (mPerNetworkDiscoveryMap) {
            final PerNetworkDiscoveryTracker info = mPerNetworkDiscoveryMap.get(id);
            if (info != null) {
                info.requestStop();
                return;
            }
        }
        try {
            mService.stopDiscovery(id);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
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
        resolveService(serviceInfo, Runnable::run, listener);
    }

    /**
     * Resolve a discovered service. An application can resolve a service right before
     * establishing a connection to fetch the IP and port details on which to setup
     * the connection.
     * @param serviceInfo service to be resolved
     * @param executor Executor to run listener callbacks with
     * @param listener to receive callback upon success or failure.
     */
    public void resolveService(@NonNull NsdServiceInfo serviceInfo,
            @NonNull Executor executor, @NonNull ResolveListener listener) {
        checkServiceInfo(serviceInfo);
        int key = putListener(listener, executor, serviceInfo);
        try {
            mService.resolveService(key, serviceInfo);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    private static void checkListener(Object listener) {
        Objects.requireNonNull(listener, "listener cannot be null");
    }

    private static void checkProtocol(int protocolType) {
        if (protocolType != PROTOCOL_DNS_SD) {
            throw new IllegalArgumentException("Unsupported protocol");
        }
    }

    private static void checkServiceInfo(NsdServiceInfo serviceInfo) {
        Objects.requireNonNull(serviceInfo, "NsdServiceInfo cannot be null");
        if (TextUtils.isEmpty(serviceInfo.getServiceName())) {
            throw new IllegalArgumentException("Service name cannot be empty");
        }
        if (TextUtils.isEmpty(serviceInfo.getServiceType())) {
            throw new IllegalArgumentException("Service type cannot be empty");
        }
    }
}
