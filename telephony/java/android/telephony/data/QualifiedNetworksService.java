/*
 * Copyright 2018 The Android Open Source Project
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

package android.telephony.data;

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
import android.telephony.AccessNetworkConstants.AccessNetworkType;
import android.telephony.Rlog;
import android.telephony.data.ApnSetting.ApnType;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;

import java.util.List;

/**
 * Base class of the qualified networks service, which is a vendor service providing up-to-date
 * qualified network information to the frameworks for data handover control. A qualified network
 * is defined as an access network that is ready for bringing up data connection for given APN
 * types.
 *
 * Services that extend QualifiedNetworksService must register the service in their AndroidManifest
 * to be detected by the framework. They must be protected by the permission
 * "android.permission.BIND_TELEPHONY_DATA_SERVICE". The qualified networks service definition in
 * the manifest must follow the following format:
 * ...
 * <service android:name=".xxxQualifiedNetworksService"
 *     android:permission="android.permission.BIND_TELEPHONY_DATA_SERVICE" >
 *     <intent-filter>
 *         <action android:name="android.telephony.data.QualifiedNetworksService" />
 *     </intent-filter>
 * </service>
 * @hide
 */
@SystemApi
public abstract class QualifiedNetworksService extends Service {
    private static final String TAG = QualifiedNetworksService.class.getSimpleName();

    public static final String QUALIFIED_NETWORKS_SERVICE_INTERFACE =
            "android.telephony.data.QualifiedNetworksService";

    private static final int QNS_CREATE_NETWORK_AVAILABILITY_PROVIDER               = 1;
    private static final int QNS_REMOVE_NETWORK_AVAILABILITY_PROVIDER               = 2;
    private static final int QNS_REMOVE_ALL_NETWORK_AVAILABILITY_PROVIDERS          = 3;
    private static final int QNS_UPDATE_QUALIFIED_NETWORKS                          = 4;

    private final HandlerThread mHandlerThread;

    private final QualifiedNetworksServiceHandler mHandler;

    private final SparseArray<NetworkAvailabilityProvider> mProviders = new SparseArray<>();

    /** @hide */
    @VisibleForTesting
    public final IQualifiedNetworksServiceWrapper mBinder = new IQualifiedNetworksServiceWrapper();

    /**
     * The abstract class of the network availability provider implementation. The vendor qualified
     * network service must extend this class to report the available networks for data
     * connection setup. Note that each instance of network availability provider is associated with
     * one physical SIM slot.
     */
    public abstract class NetworkAvailabilityProvider implements AutoCloseable {
        private final int mSlotIndex;

        private IQualifiedNetworksServiceCallback mCallback;

        /**
         * Qualified networks for each APN type. Key is the {@link ApnType}, value is the array
         * of available networks.
         */
        private SparseArray<int[]> mQualifiedNetworkTypesList = new SparseArray<>();

        /**
         * Constructor
         * @param slotIndex SIM slot index the network availability provider associated with.
         */
        public NetworkAvailabilityProvider(int slotIndex) {
            mSlotIndex = slotIndex;
        }

        /**
         * @return SIM slot index the network availability provider associated with.
         */
        public final int getSlotIndex() {
            return mSlotIndex;
        }

        private void registerForQualifiedNetworkTypesChanged(
                IQualifiedNetworksServiceCallback callback) {
            mCallback = callback;

            // Force sending the qualified networks upon registered.
            if (mCallback != null) {
                for (int i = 0; i < mQualifiedNetworkTypesList.size(); i++) {
                    try {
                        mCallback.onQualifiedNetworkTypesChanged(
                                mQualifiedNetworkTypesList.keyAt(i),
                                mQualifiedNetworkTypesList.valueAt(i));
                    } catch (RemoteException e) {
                        loge("Failed to call onQualifiedNetworksChanged. " + e);
                    }
                }
            }
        }

        /**
         * Update the qualified networks list. Network availability provider must invoke this method
         * whenever the qualified networks changes. If this method is never invoked for certain
         * APN types, then frameworks will always use the default (i.e. cellular) data and network
         * service.
         *
         * @param apnTypes APN types of the qualified networks. This must be a bitmask combination
         * of {@link ApnSetting.ApnType}.
         * @param qualifiedNetworkTypes List of network types which are qualified for data
         * connection setup for {@link @apnType} in the preferred order. Each element in the list
         * is a {@link AccessNetworkType}. An empty list indicates no networks are qualified
         * for data setup.
         */
        public final void updateQualifiedNetworkTypes(
                @ApnType int apnTypes, @NonNull List<Integer> qualifiedNetworkTypes) {
            int[] qualifiedNetworkTypesArray =
                    qualifiedNetworkTypes.stream().mapToInt(i->i).toArray();
            mHandler.obtainMessage(QNS_UPDATE_QUALIFIED_NETWORKS, mSlotIndex, apnTypes,
                    qualifiedNetworkTypesArray).sendToTarget();
        }

        private void onUpdateQualifiedNetworkTypes(@ApnType int apnTypes,
                                                   int[] qualifiedNetworkTypes) {
            mQualifiedNetworkTypesList.put(apnTypes, qualifiedNetworkTypes);
            if (mCallback != null) {
                try {
                    mCallback.onQualifiedNetworkTypesChanged(apnTypes, qualifiedNetworkTypes);
                } catch (RemoteException e) {
                    loge("Failed to call onQualifiedNetworksChanged. " + e);
                }
            }
        }

        /**
         * Called when the qualified networks provider is removed. The extended class should
         * implement this method to perform cleanup works.
         */
        @Override
        public abstract void close();
    }

    private class QualifiedNetworksServiceHandler extends Handler {
        QualifiedNetworksServiceHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message message) {
            IQualifiedNetworksServiceCallback callback;
            final int slotIndex = message.arg1;
            NetworkAvailabilityProvider provider = mProviders.get(slotIndex);

            switch (message.what) {
                case QNS_CREATE_NETWORK_AVAILABILITY_PROVIDER:
                    if (mProviders.get(slotIndex) != null) {
                        loge("Network availability provider for slot " + slotIndex
                                + " already existed.");
                        return;
                    }

                    provider = onCreateNetworkAvailabilityProvider(slotIndex);
                    if (provider != null) {
                        mProviders.put(slotIndex, provider);

                        callback = (IQualifiedNetworksServiceCallback) message.obj;
                        provider.registerForQualifiedNetworkTypesChanged(callback);
                    } else {
                        loge("Failed to create network availability provider. slot index = "
                                + slotIndex);
                    }
                    break;

                case QNS_REMOVE_NETWORK_AVAILABILITY_PROVIDER:
                    if (provider != null) {
                        provider.close();
                        mProviders.remove(slotIndex);
                    }
                    break;

                case QNS_REMOVE_ALL_NETWORK_AVAILABILITY_PROVIDERS:
                    for (int i = 0; i < mProviders.size(); i++) {
                        provider = mProviders.get(i);
                        if (provider != null) {
                            provider.close();
                        }
                    }
                    mProviders.clear();
                    break;

                case QNS_UPDATE_QUALIFIED_NETWORKS:
                    if (provider == null) break;
                    provider.onUpdateQualifiedNetworkTypes(message.arg2, (int[]) message.obj);
                    break;
            }
        }
    }

    /**
     * Default constructor.
     */
    public QualifiedNetworksService() {
        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();

        mHandler = new QualifiedNetworksServiceHandler(mHandlerThread.getLooper());
        log("Qualified networks service created");
    }

    /**
     * Create the instance of {@link NetworkAvailabilityProvider}. Vendor qualified network service
     * must override this method to facilitate the creation of {@link NetworkAvailabilityProvider}
     * instances. The system will call this method after binding the qualified networks service for
     * each active SIM slot index.
     *
     * @param slotIndex SIM slot index the qualified networks service associated with.
     * @return Qualified networks service instance
     */
    @NonNull
    public abstract NetworkAvailabilityProvider onCreateNetworkAvailabilityProvider(int slotIndex);

    /** @hide */
    @Override
    public IBinder onBind(Intent intent) {
        if (intent == null || !QUALIFIED_NETWORKS_SERVICE_INTERFACE.equals(intent.getAction())) {
            loge("Unexpected intent " + intent);
            return null;
        }
        return mBinder;
    }

    /** @hide */
    @Override
    public boolean onUnbind(Intent intent) {
        mHandler.obtainMessage(QNS_REMOVE_ALL_NETWORK_AVAILABILITY_PROVIDERS).sendToTarget();
        return false;
    }

    /** @hide */
    @Override
    public void onDestroy() {
        mHandlerThread.quit();
    }

    /**
     * A wrapper around IQualifiedNetworksService that forwards calls to implementations of
     * {@link QualifiedNetworksService}.
     */
    private class IQualifiedNetworksServiceWrapper extends IQualifiedNetworksService.Stub {
        @Override
        public void createNetworkAvailabilityProvider(int slotIndex,
                                                      IQualifiedNetworksServiceCallback callback) {
            mHandler.obtainMessage(QNS_CREATE_NETWORK_AVAILABILITY_PROVIDER, slotIndex, 0,
                    callback).sendToTarget();
        }

        @Override
        public void removeNetworkAvailabilityProvider(int slotIndex) {
            mHandler.obtainMessage(QNS_REMOVE_NETWORK_AVAILABILITY_PROVIDER, slotIndex, 0)
                    .sendToTarget();
        }
    }

    private void log(String s) {
        Rlog.d(TAG, s);
    }

    private void loge(String s) {
        Rlog.e(TAG, s);
    }
}
