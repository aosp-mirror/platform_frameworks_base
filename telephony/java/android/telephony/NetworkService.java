/*
 * Copyright 2017 The Android Open Source Project
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

package android.telephony;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.telephony.NetworkRegistrationState.Domain;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;

/**
 * Base class of network service. Services that extend NetworkService must register the service in
 * their AndroidManifest to be detected by the framework. They must be protected by the permission
 * "android.permission.BIND_TELEPHONY_NETWORK_SERVICE". The network service definition in the
 * manifest must follow the following format:
 * ...
 * <service android:name=".xxxNetworkService"
 *     android:permission="android.permission.BIND_TELEPHONY_NETWORK_SERVICE" >
 *     <intent-filter>
 *         <action android:name="android.telephony.NetworkService" />
 *     </intent-filter>
 * </service>
 * @hide
 */
@SystemApi
public abstract class NetworkService extends Service {

    private final String TAG = NetworkService.class.getSimpleName();

    public static final String NETWORK_SERVICE_INTERFACE = "android.telephony.NetworkService";

    private static final int NETWORK_SERVICE_CREATE_NETWORK_SERVICE_PROVIDER                 = 1;
    private static final int NETWORK_SERVICE_REMOVE_NETWORK_SERVICE_PROVIDER                 = 2;
    private static final int NETWORK_SERVICE_REMOVE_ALL_NETWORK_SERVICE_PROVIDERS            = 3;
    private static final int NETWORK_SERVICE_GET_REGISTRATION_STATE                          = 4;
    private static final int NETWORK_SERVICE_REGISTER_FOR_STATE_CHANGE                       = 5;
    private static final int NETWORK_SERVICE_UNREGISTER_FOR_STATE_CHANGE                     = 6;
    private static final int NETWORK_SERVICE_INDICATION_NETWORK_STATE_CHANGED                = 7;


    private final HandlerThread mHandlerThread;

    private final NetworkServiceHandler mHandler;

    private final SparseArray<NetworkServiceProvider> mServiceMap = new SparseArray<>();

    /**
     * @hide
     */
    @VisibleForTesting
    public final INetworkServiceWrapper mBinder = new INetworkServiceWrapper();

    /**
     * The abstract class of the actual network service implementation. The network service provider
     * must extend this class to support network connection. Note that each instance of network
     * service is associated with one physical SIM slot.
     */
    public abstract class NetworkServiceProvider implements AutoCloseable {
        private final int mSlotId;

        private final List<INetworkServiceCallback>
                mNetworkRegistrationStateChangedCallbacks = new ArrayList<>();

        public NetworkServiceProvider(int slotId) {
            mSlotId = slotId;
        }

        /**
         * @return SIM slot id the network service associated with.
         */
        public final int getSlotId() {
            return mSlotId;
        }

        /**
         * API to get network registration state. The result will be passed to the callback.
         * @param domain Network domain
         * @param callback The callback for reporting network registration state
         * @return SIM slot id the network service associated with.
         */
        public void getNetworkRegistrationState(@Domain int domain,
                                                @NonNull NetworkServiceCallback callback) {
            callback.onGetNetworkRegistrationStateComplete(
                    NetworkServiceCallback.RESULT_ERROR_UNSUPPORTED, null);
        }

        public final void notifyNetworkRegistrationStateChanged() {
            mHandler.obtainMessage(NETWORK_SERVICE_INDICATION_NETWORK_STATE_CHANGED,
                    mSlotId, 0, null).sendToTarget();
        }

        private void registerForStateChanged(@NonNull INetworkServiceCallback callback) {
            synchronized (mNetworkRegistrationStateChangedCallbacks) {
                mNetworkRegistrationStateChangedCallbacks.add(callback);
            }
        }

        private void unregisterForStateChanged(@NonNull INetworkServiceCallback callback) {
            synchronized (mNetworkRegistrationStateChangedCallbacks) {
                mNetworkRegistrationStateChangedCallbacks.remove(callback);
            }
        }

        private void notifyStateChangedToCallbacks() {
            for (INetworkServiceCallback callback : mNetworkRegistrationStateChangedCallbacks) {
                try {
                    callback.onNetworkStateChanged();
                } catch (RemoteException exception) {
                    // Doing nothing.
                }
            }
        }

        /**
         * Called when the instance of network service is destroyed (e.g. got unbind or binder died)
         * or when the network service provider is removed. The extended class should implement this
         * method to perform cleanup works.
         */
        @Override
        public abstract void close();
    }

    private class NetworkServiceHandler extends Handler {

        NetworkServiceHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message message) {
            final int slotId = message.arg1;
            final INetworkServiceCallback callback = (INetworkServiceCallback) message.obj;

            NetworkServiceProvider serviceProvider = mServiceMap.get(slotId);

            switch (message.what) {
                case NETWORK_SERVICE_CREATE_NETWORK_SERVICE_PROVIDER:
                    // If the service provider doesn't exist yet, we try to create it.
                    if (serviceProvider == null) {
                        mServiceMap.put(slotId, createNetworkServiceProvider(slotId));
                    }
                    break;
                case NETWORK_SERVICE_REMOVE_NETWORK_SERVICE_PROVIDER:
                    // If the service provider doesn't exist yet, we try to create it.
                    if (serviceProvider != null) {
                        serviceProvider.close();
                        mServiceMap.remove(slotId);
                    }
                    break;
                case NETWORK_SERVICE_REMOVE_ALL_NETWORK_SERVICE_PROVIDERS:
                    for (int i = 0; i < mServiceMap.size(); i++) {
                        serviceProvider = mServiceMap.get(i);
                        if (serviceProvider != null) {
                            serviceProvider.close();
                        }
                    }
                    mServiceMap.clear();
                    break;
                case NETWORK_SERVICE_GET_REGISTRATION_STATE:
                    if (serviceProvider == null) break;
                    int domainId = message.arg2;
                    serviceProvider.getNetworkRegistrationState(domainId,
                            new NetworkServiceCallback(callback));

                    break;
                case NETWORK_SERVICE_REGISTER_FOR_STATE_CHANGE:
                    if (serviceProvider == null) break;
                    serviceProvider.registerForStateChanged(callback);
                    break;
                case NETWORK_SERVICE_UNREGISTER_FOR_STATE_CHANGE:
                    if (serviceProvider == null) break;
                    serviceProvider.unregisterForStateChanged(callback);
                    break;
                case NETWORK_SERVICE_INDICATION_NETWORK_STATE_CHANGED:
                    if (serviceProvider == null) break;
                    serviceProvider.notifyStateChangedToCallbacks();
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * Default constructor.
     */
    public NetworkService() {
        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();

        mHandler = new NetworkServiceHandler(mHandlerThread.getLooper());
        log("network service created");
    }

    /**
     * Create the instance of {@link NetworkServiceProvider}. Network service provider must override
     * this method to facilitate the creation of {@link NetworkServiceProvider} instances. The system
     * will call this method after binding the network service for each active SIM slot id.
     *
     * @param slotId SIM slot id the network service associated with.
     * @return Network service object
     */
    protected abstract NetworkServiceProvider createNetworkServiceProvider(int slotId);

    /** @hide */
    @Override
    public IBinder onBind(Intent intent) {
        if (intent == null || !NETWORK_SERVICE_INTERFACE.equals(intent.getAction())) {
            loge("Unexpected intent " + intent);
            return null;
        }

        return mBinder;
    }

    /** @hide */
    @Override
    public boolean onUnbind(Intent intent) {
        mHandler.obtainMessage(NETWORK_SERVICE_REMOVE_ALL_NETWORK_SERVICE_PROVIDERS, 0,
                0, null).sendToTarget();

        return false;
    }

    /** @hide */
    @Override
    public void onDestroy() {
        mHandlerThread.quit();
    }

    /**
     * A wrapper around INetworkService that forwards calls to implementations of
     * {@link NetworkService}.
     */
    private class INetworkServiceWrapper extends INetworkService.Stub {

        @Override
        public void createNetworkServiceProvider(int slotId) {
            mHandler.obtainMessage(NETWORK_SERVICE_CREATE_NETWORK_SERVICE_PROVIDER, slotId,
                    0, null).sendToTarget();
        }

        @Override
        public void removeNetworkServiceProvider(int slotId) {
            mHandler.obtainMessage(NETWORK_SERVICE_REMOVE_NETWORK_SERVICE_PROVIDER, slotId,
                    0, null).sendToTarget();
        }

        @Override
        public void getNetworkRegistrationState(
                int slotId, int domain, INetworkServiceCallback callback) {
            mHandler.obtainMessage(NETWORK_SERVICE_GET_REGISTRATION_STATE, slotId,
                    domain, callback).sendToTarget();
        }

        @Override
        public void registerForNetworkRegistrationStateChanged(
                int slotId, INetworkServiceCallback callback) {
            mHandler.obtainMessage(NETWORK_SERVICE_REGISTER_FOR_STATE_CHANGE, slotId,
                    0, callback).sendToTarget();
        }

        @Override
        public void unregisterForNetworkRegistrationStateChanged(
                int slotId,INetworkServiceCallback callback) {
            mHandler.obtainMessage(NETWORK_SERVICE_UNREGISTER_FOR_STATE_CHANGE, slotId,
                    0, callback).sendToTarget();
        }
    }

    private final void log(String s) {
        Rlog.d(TAG, s);
    }

    private final void loge(String s) {
        Rlog.e(TAG, s);
    }
}
