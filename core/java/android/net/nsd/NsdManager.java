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
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.Messenger;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.util.AsyncChannel;
import com.android.internal.util.Protocol;

/**
 * The Network Service Discovery Manager class provides the API to discover services
 * on a network. As an example, if device A and device B are connected over a Wi-Fi
 * network, a game registered on device A can be discovered by a game on device
 * B. Another example use case is an application discovering printers on the network.
 *
 * <p> The API currently supports DNS based service discovery and discovery is currently
 * limited to a local network over Multicast DNS. In future, it will be extended to
 * support wide area discovery and other service discovery mechanisms.
 * DNS service discovery is described at http://files.dns-sd.org/draft-cheshire-dnsext-dns-sd.txt
 *
 * <p> The API is asynchronous and responses to requests from an application are on listener
 * callbacks provided by the application. The application must invoke {@link #initialize} before
 * doing any other operation.
 *
 * <p> There are three main operations the API supports - registration, discovery and resolution.
 * <pre>
 *                          Application start
 *                                 |
 *                                 |         <----------------------------------------------
 *                             initialize()                                                 |
 *                                 |                                                        |
 *                                 | Wait until channel connects                            |
 *                                 | before doing any operation                             |
 *                                 |                                                        |
 *                           onChannelConnected()                    __________             |
 *                                 |                                           |            |
 *                                 |                                           |            |
 *                                 |                  onServiceRegistered()    |            |
 *                     Register any local services  /                          |            |
 *                      to be advertised with       \                          |            | If application needs to
 *                       registerService()            onFailure()              |            | do any further operations
 *                                 |                                           |            | again, it needs to
 *                                 |                                           |            | initialize() connection
 *                          discoverServices()                                 |            | to framework again
 *                                 |                                           |            |
 *                      Maintain a list to track                               |            |
 *                        discovered services                                  |            |
 *                                 |                                           |            |
 *                                 |--------->                                 |-> onChannelDisconnected()
 *                                 |          |                                |
 *                                 |      onServiceFound()                     |
 *                                 |          |                                |
 *                                 |     add service to list                   |
 *                                 |          |                                |
 *                                 |<----------                                |
 *                                 |                                           |
 *                                 |--------->                                 |
 *                                 |          |                                |
 *                                 |      onServiceLost()                      |
 *                                 |          |                                |
 *                                 |   remove service from list                |
 *                                 |          |                                |
 *                                 |<----------                                |
 *                                 |                                           |
 *                                 |                                           |
 *                                 | Connect to a service                      |
 *                                 | from list ?                               |
 *                                 |                                           |
 *                          resolveService()                                   |
 *                                 |                                           |
 *                         onServiceResolved()                                 |
 *                                 |                                           |
 *                     Establish connection to service                         |
 *                     with the host and port information                      |
 *                                 |                                           |
 *                                 |                                ___________|
 *                           deinitialize()
 *                    when done with all operations
 *                          or before quit
 *
 * </pre>
 * An application that needs to advertise itself over a network for other applications to
 * discover it can do so with a call to {@link #registerService}. If Example is a http based
 * application that can provide HTML data to peer services, it can register a name "Example"
 * with service type "_http._tcp". A successful registration is notified with a callback to
 * {@link DnsSdRegisterListener#onServiceRegistered} and a failure to register is notified
 * over {@link DnsSdRegisterListener#onFailure}
 *
 * <p> A peer application looking for http services can initiate a discovery for "_http._tcp"
 * with a call to {@link #discoverServices}. A service found is notified with a callback
 * to {@link DnsSdDiscoveryListener#onServiceFound} and a service lost is notified on
 * {@link DnsSdDiscoveryListener#onServiceLost}.
 *
 * <p> Once the peer application discovers the "Example" http srevice, and needs to receive data
 * from the "Example" application, it can initiate a resolve with {@link #resolveService} to
 * resolve the host and port details for the purpose of establishing a connection. A successful
 * resolve is notified on {@link DnsSdResolveListener#onServiceResolved} and a failure is notified
 * on {@link DnsSdResolveListener#onFailure}.
 *
 * Applications can reserve for a service type at
 * http://www.iana.org/form/ports-service. Existing services can be found at
 * http://www.iana.org/assignments/service-names-port-numbers/service-names-port-numbers.xml
 *
 * Get an instance of this class by calling {@link android.content.Context#getSystemService(String)
 * Context.getSystemService(Context.NSD_SERVICE)}.
 *
 * {@see DnsSdServiceInfo}
 */
public class NsdManager {
    private static final String TAG = "NsdManager";
    INsdManager mService;

    /**
     * Broadcast intent action to indicate whether network service discovery is
     * enabled or disabled. An extra {@link #EXTRA_NSD_STATE} provides the state
     * information as int.
     *
     * @see #EXTRA_NSD_STATE
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String NSD_STATE_CHANGED_ACTION =
        "android.net.nsd.STATE_CHANGED";

    /**
     * The lookup key for an int that indicates whether network service discovery is enabled
     * or disabled. Retrieve it with {@link android.content.Intent#getIntExtra(String,int)}.
     *
     * @see #NSD_STATE_DISABLED
     * @see #NSD_STATE_ENABLED
     * @hide
     */
    public static final String EXTRA_NSD_STATE = "nsd_state";

    /**
     * Network service discovery is disabled
     *
     * @see #NSD_STATE_CHANGED_ACTION
     * @hide
     */
    public static final int NSD_STATE_DISABLED = 1;

    /**
     * Network service discovery is enabled
     *
     * @see #NSD_STATE_CHANGED_ACTION
     * @hide
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
    public static final int UPDATE_SERVICE                          = BASE + 15;
    /** @hide */
    public static final int UPDATE_SERVICE_FAILED                   = BASE + 16;
    /** @hide */
    public static final int UPDATE_SERVICE_SUCCEEDED                = BASE + 17;

    /** @hide */
    public static final int RESOLVE_SERVICE                         = BASE + 18;
    /** @hide */
    public static final int RESOLVE_SERVICE_FAILED                  = BASE + 19;
    /** @hide */
    public static final int RESOLVE_SERVICE_SUCCEEDED               = BASE + 20;

    /** @hide */
    public static final int STOP_RESOLVE                            = BASE + 21;
    /** @hide */
    public static final int STOP_RESOLVE_FAILED                     = BASE + 22;
    /** @hide */
    public static final int STOP_RESOLVE_SUCCEEDED                  = BASE + 23;

    /** @hide */
    public static final int ENABLE                                  = BASE + 24;
    /** @hide */
    public static final int DISABLE                                 = BASE + 25;


    /**
     * Create a new Nsd instance. Applications use
     * {@link android.content.Context#getSystemService Context.getSystemService()} to retrieve
     * {@link android.content.Context#NSD_SERVICE Context.NSD_SERVICE}.
     * @param service the Binder interface
     * @hide - hide this because it takes in a parameter of type INsdManager, which
     * is a system private class.
     */
    public NsdManager(INsdManager service) {
        mService = service;
    }

    /**
     * Passed with onFailure() calls.
     * Indicates that the operation failed due to an internal error.
     */
    public static final int ERROR               = 0;

    /**
     * Passed with onFailure() calls.
     * Indicates that the operation failed because service discovery
     * is unsupported on the device.
     */
    public static final int UNSUPPORTED         = 1;

    /**
     * Passed with onFailure() calls.
     * Indicates that the operation failed because the framework is
     * busy and unable to service the request.
     */
    public static final int BUSY                = 2;

    /**
     * Passed with onFailure() calls.
     * Indicates that the operation failed because it is already active.
     */
    public static final int ALREADY_ACTIVE      = 3;

    /**
     * Passed with onFailure() calls.
     * Indicates that the operation failed because maximum limit on
     * service registrations has reached.
     */
    public static final int MAX_REGS_REACHED    = 4;

    /** Interface for callback invocation when framework channel is connected or lost */
    public interface ChannelListener {
       /**
         * The channel to the framework is connected.
         * Application can initiate calls into the framework using the channel instance passed.
         */
        public void onChannelConnected(Channel c);
        /**
         * The channel to the framework has been disconnected.
         * Application could try re-initializing using {@link #initialize}
         */
        public void onChannelDisconnected();
    }

    /** Generic interface for callback invocation for a success or failure */
    public interface ActionListener {

        public void onFailure(int errorCode);

        public void onSuccess();
    }

    /** Interface for callback invocation for service discovery */
    public interface DnsSdDiscoveryListener {

        public void onFailure(int errorCode);

        public void onStarted(String serviceType);

        public void onServiceFound(DnsSdServiceInfo serviceInfo);

        public void onServiceLost(DnsSdServiceInfo serviceInfo);

    }

    /** Interface for callback invocation for service registration */
    public interface DnsSdRegisterListener {

        public void onFailure(int errorCode);

        public void onServiceRegistered(int registeredId, DnsSdServiceInfo serviceInfo);
    }

    /** @hide */
    public interface DnsSdUpdateRegistrationListener {

        public void onFailure(int errorCode);

        public void onServiceUpdated(int registeredId, DnsSdTxtRecord txtRecord);
    }

    /** Interface for callback invocation for service resolution */
    public interface DnsSdResolveListener {

        public void onFailure(int errorCode);

        public void onServiceResolved(DnsSdServiceInfo serviceInfo);
    }

    /**
     * A channel that connects the application to the NetworkService framework.
     * Most service operations require a Channel as an argument. An instance of Channel is obtained
     * by doing a call on {@link #initialize}
     */
    public static class Channel {
        Channel(Looper looper, ChannelListener l) {
            mAsyncChannel = new AsyncChannel();
            mHandler = new ServiceHandler(looper);
            mChannelListener = l;
        }
        private ChannelListener mChannelListener;
        private DnsSdDiscoveryListener mDnsSdDiscoveryListener;
        private ActionListener mDnsSdStopDiscoveryListener;
        private DnsSdRegisterListener mDnsSdRegisterListener;
        private ActionListener mDnsSdUnregisterListener;
        private DnsSdUpdateRegistrationListener mDnsSdUpdateListener;
        private DnsSdResolveListener mDnsSdResolveListener;
        private ActionListener mDnsSdStopResolveListener;

        private AsyncChannel mAsyncChannel;
        private ServiceHandler mHandler;
        class ServiceHandler extends Handler {
            ServiceHandler(Looper looper) {
                super(looper);
            }

            @Override
            public void handleMessage(Message message) {
                switch (message.what) {
                    case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED:
                        mAsyncChannel.sendMessage(AsyncChannel.CMD_CHANNEL_FULL_CONNECTION);
                        break;
                    case AsyncChannel.CMD_CHANNEL_FULLY_CONNECTED:
                        if (mChannelListener != null) {
                            mChannelListener.onChannelConnected(Channel.this);
                        }
                        break;
                    case AsyncChannel.CMD_CHANNEL_DISCONNECTED:
                        if (mChannelListener != null) {
                            mChannelListener.onChannelDisconnected();
                            mChannelListener = null;
                        }
                        break;
                    case DISCOVER_SERVICES_STARTED:
                        if (mDnsSdDiscoveryListener != null) {
                            mDnsSdDiscoveryListener.onStarted((String) message.obj);
                        }
                        break;
                    case DISCOVER_SERVICES_FAILED:
                        if (mDnsSdDiscoveryListener != null) {
                            mDnsSdDiscoveryListener.onFailure(message.arg1);
                        }
                        break;
                    case SERVICE_FOUND:
                        if (mDnsSdDiscoveryListener != null) {
                            mDnsSdDiscoveryListener.onServiceFound(
                                    (DnsSdServiceInfo) message.obj);
                        }
                        break;
                    case SERVICE_LOST:
                        if (mDnsSdDiscoveryListener != null) {
                            mDnsSdDiscoveryListener.onServiceLost(
                                    (DnsSdServiceInfo) message.obj);
                        }
                        break;
                    case STOP_DISCOVERY_FAILED:
                        if (mDnsSdStopDiscoveryListener != null) {
                            mDnsSdStopDiscoveryListener.onFailure(message.arg1);
                        }
                        break;
                    case STOP_DISCOVERY_SUCCEEDED:
                        if (mDnsSdStopDiscoveryListener != null) {
                            mDnsSdStopDiscoveryListener.onSuccess();
                        }
                        break;
                    case REGISTER_SERVICE_FAILED:
                        if (mDnsSdRegisterListener != null) {
                            mDnsSdRegisterListener.onFailure(message.arg1);
                        }
                        break;
                    case REGISTER_SERVICE_SUCCEEDED:
                        if (mDnsSdRegisterListener != null) {
                            mDnsSdRegisterListener.onServiceRegistered(message.arg1,
                                    (DnsSdServiceInfo) message.obj);
                        }
                        break;
                    case UNREGISTER_SERVICE_FAILED:
                        if (mDnsSdUnregisterListener != null) {
                            mDnsSdUnregisterListener.onFailure(message.arg1);
                        }
                        break;
                    case UNREGISTER_SERVICE_SUCCEEDED:
                        if (mDnsSdUnregisterListener != null) {
                            mDnsSdUnregisterListener.onSuccess();
                        }
                        break;
                   case UPDATE_SERVICE_FAILED:
                        if (mDnsSdUpdateListener != null) {
                            mDnsSdUpdateListener.onFailure(message.arg1);
                        }
                        break;
                    case UPDATE_SERVICE_SUCCEEDED:
                        if (mDnsSdUpdateListener != null) {
                            mDnsSdUpdateListener.onServiceUpdated(message.arg1,
                                    (DnsSdTxtRecord) message.obj);
                        }
                        break;
                    case RESOLVE_SERVICE_FAILED:
                        if (mDnsSdResolveListener != null) {
                            mDnsSdResolveListener.onFailure(message.arg1);
                        }
                        break;
                    case RESOLVE_SERVICE_SUCCEEDED:
                        if (mDnsSdResolveListener != null) {
                            mDnsSdResolveListener.onServiceResolved(
                                    (DnsSdServiceInfo) message.obj);
                        }
                        break;
                    case STOP_RESOLVE_FAILED:
                        if (mDnsSdStopResolveListener!= null) {
                            mDnsSdStopResolveListener.onFailure(message.arg1);
                        }
                        break;
                    case STOP_RESOLVE_SUCCEEDED:
                        if (mDnsSdStopResolveListener != null) {
                            mDnsSdStopResolveListener.onSuccess();
                        }
                        break;
                    default:
                        Log.d(TAG, "Ignored " + message);
                        break;
                }
            }
        }
   }

    private static void checkChannel(Channel c) {
        if (c == null) throw new IllegalArgumentException("Channel needs to be initialized");
    }

    /**
     * Registers the application with the service discovery framework. This function
     * must be the first to be called before any other operations are performed. No service
     * discovery operations must be performed until the ChannelListener callback notifies
     * that the channel is connected
     *
     * @param srcContext is the context of the source
     * @param srcLooper is the Looper on which the callbacks are receivied
     * @param listener for callback at loss of framework communication. Cannot be null.
     */
    public void initialize(Context srcContext, Looper srcLooper, ChannelListener listener) {
        Messenger messenger = getMessenger();
        if (messenger == null) throw new RuntimeException("Failed to initialize");
        if (listener == null) throw new IllegalArgumentException("ChannelListener cannot be null");

        Channel c = new Channel(srcLooper, listener);
        c.mAsyncChannel.connect(srcContext, c.mHandler, messenger);
    }

    /**
     * Disconnects application from service discovery framework. No further operations
     * will succeed until a {@link #initialize} is called again.
     *
     * @param c channel initialized with {@link #initialize}
     */
    public void deinitialize(Channel c) {
        checkChannel(c);
        c.mAsyncChannel.disconnect();
    }

    /**
     * Register a service to be discovered by other services.
     *
     * <p> The function call immediately returns after sending a request to register service
     * to the framework. The application is notified of a success to initiate
     * discovery through the callback {@link DnsSdRegisterListener#onServiceRegistered} or a failure
     * through {@link DnsSdRegisterListener#onFailure}.
     *
     * @param c is the channel created at {@link #initialize}
     * @param serviceType The service type being advertised.
     * @param port on which the service is listenering for incoming connections
     * @param listener for success or failure callback. Can be null.
     */
    public void registerService(Channel c, String serviceName, String serviceType, int port,
            DnsSdRegisterListener listener) {
        checkChannel(c);
        if (TextUtils.isEmpty(serviceName) || TextUtils.isEmpty(serviceType)) {
            throw new IllegalArgumentException("Service name or type cannot be empty");
        }
        if (port <= 0) {
            throw new IllegalArgumentException("Invalid port number");
        }
        DnsSdServiceInfo serviceInfo = new DnsSdServiceInfo(serviceName, serviceType, null);
        serviceInfo.setPort(port);
        c.mDnsSdRegisterListener = listener;
        c.mAsyncChannel.sendMessage(REGISTER_SERVICE, serviceInfo);
    }

    /**
     * Unregister a service registered through {@link #registerService}
     * @param c is the channel created at {@link #initialize}
     * @param registeredId is obtained at {@link DnsSdRegisterListener#onServiceRegistered}
     * @param listener provides callbacks for success or failure. Can be null.
     */
    public void unregisterService(Channel c, int registeredId, ActionListener listener) {
        checkChannel(c);
        c.mDnsSdUnregisterListener = listener;
        c.mAsyncChannel.sendMessage(UNREGISTER_SERVICE, registeredId);
    }

    /** @hide */
    public void updateService(Channel c, int registeredId, DnsSdTxtRecord txtRecord) {
        checkChannel(c);
        c.mAsyncChannel.sendMessage(UPDATE_SERVICE, registeredId, 0, txtRecord);
    }

    /**
     * Initiate service discovery to browse for instances of a service type. Service discovery
     * consumes network bandwidth and will continue until the application calls
     * {@link #stopServiceDiscovery}.
     *
     * <p> The function call immediately returns after sending a request to start service
     * discovery to the framework. The application is notified of a success to initiate
     * discovery through the callback {@link DnsSdDiscoveryListener#onStarted} or a failure
     * through {@link DnsSdDiscoveryListener#onFailure}.
     *
     * <p> Upon successful start, application is notified when a service is found with
     * {@link DnsSdDiscoveryListener#onServiceFound} or when a service is lost with
     * {@link DnsSdDiscoveryListener#onServiceLost}.
     *
     * <p> Upon failure to start, service discovery is not active and application does
     * not need to invoke {@link #stopServiceDiscovery}
     *
     * @param c is the channel created at {@link #initialize}
     * @param serviceType The service type being discovered. Examples include "_http._tcp" for
     * http services or "_ipp._tcp" for printers
     * @param listener provides callbacks when service is found or lost. Cannot be null.
     */
    public void discoverServices(Channel c, String serviceType, DnsSdDiscoveryListener listener) {
        checkChannel(c);
        if (listener == null) {
            throw new IllegalStateException("Discovery listener needs to be set first");
        }
        if (TextUtils.isEmpty(serviceType)) {
            throw new IllegalStateException("Service type cannot be empty");
        }
        DnsSdServiceInfo s = new DnsSdServiceInfo();
        s.setServiceType(serviceType);
        c.mDnsSdDiscoveryListener = listener;
        c.mAsyncChannel.sendMessage(DISCOVER_SERVICES, s);
    }

    /**
     * Stop service discovery initiated with {@link #discoverServices}. An active service
     * discovery is notified to the application with {@link DnsSdDiscoveryListener#onStarted}
     * and it stays active until the application invokes a stop service discovery.
     *
     * <p> Upon failure to start service discovery notified through
     * {@link DnsSdDiscoveryListener#onFailure} service discovery is not active and
     * application does not need to stop it.
     *
     * @param c is the channel created at {@link #initialize}
     * @param listener notifies success or failure. Can be null.
     */
    public void stopServiceDiscovery(Channel c, ActionListener listener) {
        checkChannel(c);
        c.mDnsSdStopDiscoveryListener = listener;
        c.mAsyncChannel.sendMessage(STOP_DISCOVERY);
    }

    /**
     * Resolve a discovered service. An application can resolve a service right before
     * establishing a connection to fetch the IP and port details on which to setup
     * the connection.
     *
     * @param c is the channel created at {@link #initialize}
     * @param serviceName of the the service
     * @param serviceType of the service
     * @param listener to receive callback upon success or failure. Cannot be null.
     */
    public void resolveService(Channel c, String serviceName, String serviceType,
            DnsSdResolveListener listener) {
        checkChannel(c);
        if (TextUtils.isEmpty(serviceName) || TextUtils.isEmpty(serviceType)) {
            throw new IllegalArgumentException("Service name or type cannot be empty");
        }
        if (listener == null) throw new
                IllegalStateException("Resolve listener cannot be null");
        c.mDnsSdResolveListener = listener;
        DnsSdServiceInfo serviceInfo = new DnsSdServiceInfo(serviceName, serviceType, null);
        c.mAsyncChannel.sendMessage(RESOLVE_SERVICE, serviceInfo);
    }

    /** @hide */
    public void stopServiceResolve(Channel c) {
        checkChannel(c);
        if (c.mDnsSdResolveListener == null) throw new
                IllegalStateException("Resolve listener needs to be set first");
        c.mAsyncChannel.sendMessage(STOP_RESOLVE);
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
