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

import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
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
import android.telephony.AccessNetworkConstants;
import android.telephony.AccessNetworkConstants.AccessNetworkType;
import android.telephony.Annotation.ApnType;
import android.telephony.Annotation.NetCapability;
import android.telephony.PreciseDataConnectionState;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.IIntegerConsumer;
import com.android.internal.telephony.flags.FeatureFlags;
import com.android.internal.telephony.flags.FeatureFlagsImpl;
import com.android.internal.telephony.flags.Flags;
import com.android.internal.util.FunctionalUtils;
import com.android.telephony.Rlog;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

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
    private static final int QNS_APN_THROTTLE_STATUS_CHANGED                        = 5;
    private static final int QNS_EMERGENCY_DATA_NETWORK_PREFERRED_TRANSPORT_CHANGED = 6;
    private static final int QNS_REQUEST_NETWORK_VALIDATION                         = 7;
    private static final int QNS_RECONNECT_QUALIFIED_NETWORK                        = 8;

    /** Feature flags */
    private static final FeatureFlags sFeatureFlag = new FeatureFlagsImpl();

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
         * Update the suggested qualified networks list. Network availability provider must invoke
         * this method whenever the suggested qualified networks changes. If this method is never
         * invoked for certain APN types, then frameworks uses its own logic to determine the
         * transport to setup the data network.
         *
         * For example, QNS can suggest frameworks setting up IMS data network on IWLAN by
         * specifying {@link ApnSetting#TYPE_IMS} with a list containing
         * {@link AccessNetworkType#IWLAN}.
         *
         * If QNS considers multiple access networks qualified for certain APN type, it can
         * suggest frameworks by specifying the APN type with multiple access networks in the list,
         * for example {{@link AccessNetworkType#EUTRAN}, {@link AccessNetworkType#IWLAN}}.
         * Frameworks will then first attempt to setup data on LTE network, and If the device moves
         * from LTE to UMTS, then frameworks will perform handover the data network to the second
         * preferred access network if available.
         *
         * If the {@code qualifiedNetworkTypes} list is empty, it means QNS has no suggestion to the
         * frameworks, and for that APN type frameworks will route the corresponding network
         * requests to {@link AccessNetworkConstants#TRANSPORT_TYPE_WWAN}.
         *
         * @param apnTypes APN type(s) of the qualified networks. This must be a bitmask combination
         * of {@link ApnType}. The same qualified networks will be applicable to all APN types
         * specified here.
         * @param qualifiedNetworkTypes List of access network types which are qualified for data
         * connection setup for {@code apnTypes} in the preferred order. Empty list means QNS has no
         * suggestion to the frameworks, and for that APN type frameworks will route the
         * corresponding network requests to {@link AccessNetworkConstants#TRANSPORT_TYPE_WWAN}.
         *
         * If one of the element is invalid, for example, {@link AccessNetworkType#UNKNOWN}, then
         * this operation becomes a no-op.
         */
        public final void updateQualifiedNetworkTypes(
                @ApnType int apnTypes, @NonNull List<Integer> qualifiedNetworkTypes) {
            int[] qualifiedNetworkTypesArray =
                    qualifiedNetworkTypes.stream().mapToInt(i->i).toArray();
            mHandler.obtainMessage(QNS_UPDATE_QUALIFIED_NETWORKS, mSlotIndex, apnTypes,
                    qualifiedNetworkTypesArray).sendToTarget();
        }

        /**
         * Request to make a clean initial connection instead of handover to a transport type mapped
         * to the {@code qualifiedNetworkType} for the {@code apnTypes}. This will update the
         * preferred network type like {@link #updateQualifiedNetworkTypes(int, List)}, however if
         * the data network for the {@code apnTypes} is not in the state {@link TelephonyManager
         * #DATA_CONNECTED} or it's already connected on the transport type mapped to the
         * qualified network type, forced reconnection will be ignored.
         *
         * <p>This will tear down current data network even though target transport type mapped to
         * the {@code qualifiedNetworkType} is not available, and the data network will be connected
         * to the transport type when it becomes available.
         *
         * <p>This is one shot request and does not mean further handover is not allowed to the
         * qualified network type for this APN type.
         *
         * @param apnTypes APN type(s) of the qualified networks. This must be a bitmask combination
         * of {@link ApnType}. The same qualified networks will be applicable to all APN types
         * specified here.
         * @param qualifiedNetworkType Access network types which are qualified for data connection
         * setup for {@link ApnType}. Empty list means QNS has no suggestion to the frameworks, and
         * for that APN type frameworks will route the corresponding network requests to
         * {@link AccessNetworkConstants#TRANSPORT_TYPE_WWAN}.
         *
         * <p> If one of the element is invalid, for example, {@link AccessNetworkType#UNKNOWN},
         * then this operation becomes a no-op.
         *
         * @hide
         */
        public final void reconnectQualifiedNetworkType(@ApnType int apnTypes,
                @AccessNetworkConstants.RadioAccessNetworkType int qualifiedNetworkType) {
            mHandler.obtainMessage(QNS_RECONNECT_QUALIFIED_NETWORK, mSlotIndex, apnTypes,
                    new Integer(qualifiedNetworkType)).sendToTarget();
        }

        private void onUpdateQualifiedNetworkTypes(
                @ApnType int apnTypes, int[] qualifiedNetworkTypes) {
            mQualifiedNetworkTypesList.put(apnTypes, qualifiedNetworkTypes);
            if (mCallback != null) {
                try {
                    mCallback.onQualifiedNetworkTypesChanged(apnTypes, qualifiedNetworkTypes);
                } catch (RemoteException e) {
                    loge("Failed to call onQualifiedNetworksChanged. " + e);
                }
            }
        }

        private void onReconnectQualifiedNetworkType(@ApnType int apnTypes,
                @AccessNetworkConstants.RadioAccessNetworkType int qualifiedNetworkType) {
            if (mCallback != null) {
                try {
                    mCallback.onReconnectQualifedNetworkType(apnTypes, qualifiedNetworkType);
                } catch (RemoteException e) {
                    loge("Failed to call onReconnectQualifiedNetworkType. " + e);
                }
            }
        }

        /**
         * The framework calls this method when the throttle status of an APN changes.
         *
         * This method is meant to be overridden.
         *
         * @param statuses the statuses that have changed
         */
        public void reportThrottleStatusChanged(@NonNull List<ThrottleStatus> statuses) {
            Log.d(TAG, "reportThrottleStatusChanged: statuses size=" + statuses.size());
        }

        /**
         * The framework calls this method when the preferred transport type used to set up
         * emergency data network is changed.
         *
         * This method is meant to be overridden.
         *
         * @param transportType transport type changed to be preferred
         */
        public void reportEmergencyDataNetworkPreferredTransportChanged(
                @AccessNetworkConstants.TransportType int transportType) {
            Log.d(TAG, "reportEmergencyDataNetworkPreferredTransportChanged: "
                    + AccessNetworkConstants.transportTypeToString(transportType));
        }

        /**
         * Request network validation to the connected data network for given a network capability.
         *
         * <p>This network validation can only be performed when a data network is in connected
         * state, and will not be triggered if the data network does not support network validation
         * feature or network validation is not in connected state.
         *
         * <p>See {@link DataServiceCallback.ResultCode} for the type of response that indicates
         * whether the request was successfully submitted or had an error.
         *
         * <p>If network validation is requested, monitor network validation status in {@link
         * PreciseDataConnectionState#getNetworkValidationStatus()}.
         *
         * @param networkCapability A network capability. (Note that only APN-type capabilities are
         *     supported.
         * @param executor executor The callback executor that responds whether the request has been
         *     successfully submitted or not.
         * @param resultCodeCallback A callback to determine whether the request was successfully
         *     submitted or not.
         */
        @FlaggedApi(Flags.FLAG_NETWORK_VALIDATION)
        public void requestNetworkValidation(
                @NetCapability int networkCapability,
                @NonNull @CallbackExecutor Executor executor,
                @NonNull @DataServiceCallback.ResultCode Consumer<Integer> resultCodeCallback) {
            Objects.requireNonNull(executor, "executor cannot be null");
            Objects.requireNonNull(resultCodeCallback, "resultCodeCallback cannot be null");

            if (!sFeatureFlag.networkValidation()) {
                loge("networkValidation feature is disabled");
                executor.execute(
                        () ->
                                resultCodeCallback.accept(
                                        DataServiceCallback.RESULT_ERROR_UNSUPPORTED));
                return;
            }

            IIntegerConsumer callback = new IIntegerConsumer.Stub() {
                @Override
                public void accept(int result) {
                    executor.execute(() -> resultCodeCallback.accept(result));
                }
            };

            // Move to the internal handler and process it.
            mHandler.obtainMessage(
                            QNS_REQUEST_NETWORK_VALIDATION,
                            mSlotIndex,
                            0,
                            new NetworkValidationRequestData(networkCapability, callback))
                    .sendToTarget();
        }

        /** Process a network validation request on the internal handler. */
        private void onRequestNetworkValidation(NetworkValidationRequestData data) {
            try {
                log("onRequestNetworkValidation");
                // Callback to request a network validation.
                mCallback.onNetworkValidationRequested(data.mNetworkCapability, data.mCallback);
            } catch (RemoteException | NullPointerException e) {
                loge("Failed to call onRequestNetworkValidation. " + e);
                FunctionalUtils.ignoreRemoteException(data.mCallback::accept)
                        .accept(DataServiceCallback.RESULT_ERROR_UNSUPPORTED);
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
                case QNS_APN_THROTTLE_STATUS_CHANGED:
                    if (provider != null) {
                        List<ThrottleStatus> statuses = (List<ThrottleStatus>) message.obj;
                        provider.reportThrottleStatusChanged(statuses);
                    }
                    break;

                case QNS_EMERGENCY_DATA_NETWORK_PREFERRED_TRANSPORT_CHANGED:
                    if (provider != null) {
                        int transportType = (int) message.arg2;
                        provider.reportEmergencyDataNetworkPreferredTransportChanged(transportType);
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

                case QNS_REQUEST_NETWORK_VALIDATION:
                    if (provider == null) break;
                    provider.onRequestNetworkValidation((NetworkValidationRequestData) message.obj);
                    break;

                case QNS_RECONNECT_QUALIFIED_NETWORK:
                    if (provider == null) break;
                    provider.onReconnectQualifiedNetworkType(message.arg2, (Integer) message.obj);
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

        @Override
        public void reportThrottleStatusChanged(int slotIndex,
                List<ThrottleStatus> statuses) {
            mHandler.obtainMessage(QNS_APN_THROTTLE_STATUS_CHANGED, slotIndex, 0, statuses)
                    .sendToTarget();
        }

        @Override
        public void reportEmergencyDataNetworkPreferredTransportChanged(int slotIndex,
                @AccessNetworkConstants.TransportType int transportType) {
            mHandler.obtainMessage(
                    QNS_EMERGENCY_DATA_NETWORK_PREFERRED_TRANSPORT_CHANGED,
                            slotIndex, transportType).sendToTarget();
        }
    }

    private static final class NetworkValidationRequestData {
        final @NetCapability int mNetworkCapability;
        final IIntegerConsumer mCallback;

        private NetworkValidationRequestData(@NetCapability int networkCapability,
                @NonNull IIntegerConsumer callback) {
            mNetworkCapability = networkCapability;
            mCallback = callback;
        }
    }

    private void log(String s) {
        Rlog.d(TAG, s);
    }

    private void loge(String s) {
        Rlog.e(TAG, s);
    }
}
