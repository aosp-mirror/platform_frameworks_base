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

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.Messenger;
import android.util.Log;

import com.android.internal.util.AsyncChannel;
import com.android.internal.util.Protocol;

/**
 * The Network Service Discovery Manager class provides the API for service
 * discovery. Service discovery enables applications to discover and connect with services
 * on a network. Example applications include a game application discovering another instance
 * of the game application or a printer application discovering other printers on a network.
 *
 * <p> The API is asynchronous and responses to requests from an application are on listener
 * callbacks provided by the application. The application needs to do an initialization with
 * {@link #initialize} before doing any operation.
 *
 * <p> Android currently supports DNS based service discovery and it is limited to a local
 * network with the use of multicast DNS. In future, this class will be
 * extended to support other service discovery mechanisms.
 *
 * Get an instance of this class by calling {@link android.content.Context#getSystemService(String)
 * Context.getSystemService(Context.NSD_SERVICE)}.
 * @hide
 *
 */
public class NsdManager {
    private static final String TAG = "NsdManager";
    INsdManager mService;

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
    public static final int UPDATE_SERVICE                          = BASE + 12;
    /** @hide */
    public static final int UPDATE_SERVICE_FAILED                   = BASE + 13;
    /** @hide */
    public static final int UPDATE_SERVICE_SUCCEEDED                = BASE + 14;

    /** @hide */
    public static final int RESOLVE_SERVICE                         = BASE + 15;
    /** @hide */
    public static final int RESOLVE_SERVICE_FAILED                  = BASE + 16;
    /** @hide */
    public static final int RESOLVE_SERVICE_SUCCEEDED               = BASE + 17;

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
     * Indicates that the operation failed due to an internal error.
     */
    public static final int ERROR               = 0;

    /**
     * Indicates that the operation failed because service discovery is unsupported on the device.
     */
    public static final int UNSUPPORTED         = 1;

    /**
     * Indicates that the operation failed because the framework is busy and
     * unable to service the request
     */
    public static final int BUSY                = 2;

    /** Interface for callback invocation when framework channel is connected or lost */
    public interface ChannelListener {
        public void onChannelConnected(Channel c);
        /**
         * The channel to the framework has been disconnected.
         * Application could try re-initializing using {@link #initialize}
         */
        public void onChannelDisconnected();
    }

    public interface ActionListener {

        public void onFailure(int errorCode);

        public void onSuccess();
    }

    public interface DnsSdDiscoveryListener {

        public void onFailure(int errorCode);

        public void onStarted(String registrationType);

        public void onServiceFound(DnsSdServiceInfo serviceInfo);

        public void onServiceLost(DnsSdServiceInfo serviceInfo);

    }

    public interface DnsSdRegisterListener {

        public void onFailure(int errorCode);

        public void onServiceRegistered(int registeredId, DnsSdServiceInfo serviceInfo);
    }

    public interface DnsSdUpdateRegistrationListener {

        public void onFailure(int errorCode);

        public void onServiceUpdated(int registeredId, DnsSdTxtRecord txtRecord);
    }

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
        private DnsSdUpdateRegistrationListener mDnsSdUpdateListener;
        private DnsSdResolveListener mDnsSdResolveListener;

        AsyncChannel mAsyncChannel;
        ServiceHandler mHandler;
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
                    default:
                        Log.d(TAG, "Ignored " + message);
                        break;
                }
            }
        }
   }

    /**
     * Registers the application with the service discovery framework. This function
     * must be the first to be called before any other operations are performed. No service
     * discovery operations must be performed until the ChannelListener callback notifies
     * that the channel is connected
     *
     * @param srcContext is the context of the source
     * @param srcLooper is the Looper on which the callbacks are receivied
     * @param listener for callback at loss of framework communication.
     */
    public void initialize(Context srcContext, Looper srcLooper, ChannelListener listener) {
        Messenger messenger = getMessenger();
        if (messenger == null) throw new RuntimeException("Failed to initialize");
        if (listener == null) throw new IllegalArgumentException("ChannelListener cannot be null");

        Channel c = new Channel(srcLooper, listener);
        c.mAsyncChannel.connect(srcContext, c.mHandler, messenger);
    }

    /**
     * Set the listener for service discovery. Can be null.
     */
    public void setDiscoveryListener(Channel c, DnsSdDiscoveryListener b) {
        if (c == null) throw new IllegalArgumentException("Channel needs to be initialized");
        c.mDnsSdDiscoveryListener = b;
    }

    /**
     * Set the listener for stop service discovery. Can be null.
     */
    public void setStopDiscoveryListener(Channel c, ActionListener a) {
        if (c == null) throw new IllegalArgumentException("Channel needs to be initialized");
        c.mDnsSdStopDiscoveryListener = a;
    }

    /**
     * Set the listener for service registration. Can be null.
     */
    public void setRegisterListener(Channel c, DnsSdRegisterListener b) {
        if (c == null) throw new IllegalArgumentException("Channel needs to be initialized");
        c.mDnsSdRegisterListener = b;
    }

    /**
     * Set the listener for service registration. Can be null.
     */
    public void setUpdateRegistrationListener(Channel c, DnsSdUpdateRegistrationListener b) {
        if (c == null) throw new IllegalArgumentException("Channel needs to be initialized");
        c.mDnsSdUpdateListener = b;
    }

    /**
     * Set the listener for service resolution. Can be null.
     */
    public void setResolveListener(Channel c, DnsSdResolveListener b) {
        if (c == null) throw new IllegalArgumentException("Channel needs to be initialized");
        c.mDnsSdResolveListener = b;
    }

    public void registerService(Channel c, DnsSdServiceInfo serviceInfo) {
        if (c == null) throw new IllegalArgumentException("Channel needs to be initialized");
        if (serviceInfo == null) throw new IllegalArgumentException("Null serviceInfo");
        c.mAsyncChannel.sendMessage(REGISTER_SERVICE, serviceInfo);
    }

    public void updateService(Channel c, int registeredId, DnsSdTxtRecord txtRecord) {
        if (c == null) throw new IllegalArgumentException("Channel needs to be initialized");
        c.mAsyncChannel.sendMessage(UPDATE_SERVICE, registeredId, 0, txtRecord);
    }

    public void discoverServices(Channel c, String serviceType) {
        if (c == null) throw new IllegalArgumentException("Channel needs to be initialized");
        if (c.mDnsSdDiscoveryListener == null) throw new
                IllegalStateException("Discovery listener needs to be set first");
        DnsSdServiceInfo s = new DnsSdServiceInfo();
        s.setServiceType(serviceType);
        c.mAsyncChannel.sendMessage(DISCOVER_SERVICES, s);
    }

    public void stopServiceDiscovery(Channel c) {
        if (c == null) throw new IllegalArgumentException("Channel needs to be initialized");
        c.mAsyncChannel.sendMessage(STOP_DISCOVERY);
    }

    public void resolveService(Channel c, DnsSdServiceInfo serviceInfo) {
        if (c == null) throw new IllegalArgumentException("Channel needs to be initialized");
        if (serviceInfo == null) throw new IllegalArgumentException("Null serviceInfo");
        if (c.mDnsSdResolveListener == null) throw new
                IllegalStateException("Resolve listener needs to be set first");
        c.mAsyncChannel.sendMessage(RESOLVE_SERVICE, serviceInfo);
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
