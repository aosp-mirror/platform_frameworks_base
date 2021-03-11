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

package android.telephony.data;

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.Intent;
import android.net.LinkProperties;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.telephony.Rlog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * Base class of data service. Services that extend DataService must register the service in
 * their AndroidManifest to be detected by the framework. They must be protected by the permission
 * "android.permission.BIND_TELEPHONY_DATA_SERVICE". The data service definition in the manifest
 * must follow the following format:
 * ...
 * <service android:name=".xxxDataService"
 *     android:permission="android.permission.BIND_TELEPHONY_DATA_SERVICE" >
 *     <intent-filter>
 *         <action android:name="android.telephony.data.DataService" />
 *     </intent-filter>
 * </service>
 * @hide
 */
@SystemApi
public abstract class DataService extends Service {
    private static final String TAG = DataService.class.getSimpleName();

    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE = "android.telephony.data.DataService";

    /** {@hide} */
    @IntDef(prefix = "REQUEST_REASON_", value = {
            REQUEST_REASON_UNKNOWN,
            REQUEST_REASON_NORMAL,
            REQUEST_REASON_HANDOVER,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SetupDataReason {}

    /** {@hide} */
    @IntDef(prefix = "REQUEST_REASON_", value = {
            REQUEST_REASON_UNKNOWN,
            REQUEST_REASON_NORMAL,
            REQUEST_REASON_SHUTDOWN,
            REQUEST_REASON_HANDOVER,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DeactivateDataReason {}

    /** The reason of the data request is unknown */
    public static final int REQUEST_REASON_UNKNOWN = 0;

    /** The reason of the data request is normal */
    public static final int REQUEST_REASON_NORMAL = 1;

    /** The reason of the data request is device shutdown */
    public static final int REQUEST_REASON_SHUTDOWN = 2;

    /** The reason of the data request is IWLAN handover */
    public static final int REQUEST_REASON_HANDOVER = 3;

    private static final int DATA_SERVICE_CREATE_DATA_SERVICE_PROVIDER                 = 1;
    private static final int DATA_SERVICE_REMOVE_DATA_SERVICE_PROVIDER                 = 2;
    private static final int DATA_SERVICE_REMOVE_ALL_DATA_SERVICE_PROVIDERS            = 3;
    private static final int DATA_SERVICE_REQUEST_SETUP_DATA_CALL                      = 4;
    private static final int DATA_SERVICE_REQUEST_DEACTIVATE_DATA_CALL                 = 5;
    private static final int DATA_SERVICE_REQUEST_SET_INITIAL_ATTACH_APN               = 6;
    private static final int DATA_SERVICE_REQUEST_SET_DATA_PROFILE                     = 7;
    private static final int DATA_SERVICE_REQUEST_REQUEST_DATA_CALL_LIST               = 8;
    private static final int DATA_SERVICE_REQUEST_REGISTER_DATA_CALL_LIST_CHANGED      = 9;
    private static final int DATA_SERVICE_REQUEST_UNREGISTER_DATA_CALL_LIST_CHANGED    = 10;
    private static final int DATA_SERVICE_INDICATION_DATA_CALL_LIST_CHANGED            = 11;
    private static final int DATA_SERVICE_REQUEST_START_HANDOVER                       = 12;
    private static final int DATA_SERVICE_REQUEST_CANCEL_HANDOVER                      = 13;
    private static final int DATA_SERVICE_REQUEST_REGISTER_APN_UNTHROTTLED             = 14;
    private static final int DATA_SERVICE_REQUEST_UNREGISTER_APN_UNTHROTTLED           = 15;
    private static final int DATA_SERVICE_INDICATION_APN_UNTHROTTLED                   = 16;

    private final HandlerThread mHandlerThread;

    private final DataServiceHandler mHandler;

    private final SparseArray<DataServiceProvider> mServiceMap = new SparseArray<>();

    /** @hide */
    @VisibleForTesting
    public final IDataServiceWrapper mBinder = new IDataServiceWrapper();

    /**
     * The abstract class of the actual data service implementation. The data service provider
     * must extend this class to support data connection. Note that each instance of data service
     * provider is associated with one physical SIM slot.
     */
    public abstract class DataServiceProvider implements AutoCloseable {

        private final int mSlotIndex;

        private final List<IDataServiceCallback> mDataCallListChangedCallbacks = new ArrayList<>();

        private final List<IDataServiceCallback> mApnUnthrottledCallbacks = new ArrayList<>();

        /**
         * Constructor
         * @param slotIndex SIM slot index the data service provider associated with.
         */
        public DataServiceProvider(int slotIndex) {
            mSlotIndex = slotIndex;
        }

        /**
         * @return SIM slot index the data service provider associated with.
         */
        public final int getSlotIndex() {
            return mSlotIndex;
        }

        /**
         * Setup a data connection. The data service provider must implement this method to support
         * establishing a packet data connection. When completed or error, the service must invoke
         * the provided callback to notify the platform.
         *
         * @param accessNetworkType Access network type that the data call will be established on.
         *        Must be one of {@link android.telephony.AccessNetworkConstants.AccessNetworkType}.
         * @param dataProfile Data profile used for data call setup. See {@link DataProfile}
         * @param isRoaming True if the device is data roaming.
         * @param allowRoaming True if data roaming is allowed by the user.
         * @param reason The reason for data setup. Must be {@link #REQUEST_REASON_NORMAL} or
         *        {@link #REQUEST_REASON_HANDOVER}.
         * @param linkProperties If {@code reason} is {@link #REQUEST_REASON_HANDOVER}, this is the
         *        link properties of the existing data connection, otherwise null.
         * @param callback The result callback for this request.
         */
        public void setupDataCall(int accessNetworkType, @NonNull DataProfile dataProfile,
                boolean isRoaming, boolean allowRoaming,
                @SetupDataReason int reason, @Nullable LinkProperties linkProperties,
                @NonNull DataServiceCallback callback) {
            // The default implementation is to return unsupported.
            if (callback != null) {
                callback.onSetupDataCallComplete(DataServiceCallback.RESULT_ERROR_UNSUPPORTED,
                        null);
            }
        }

        /**
         * Setup a data connection. The data service provider must implement this method to support
         * establishing a packet data connection. When completed or error, the service must invoke
         * the provided callback to notify the platform.
         *
         * @param accessNetworkType Access network type that the data call will be established on.
         *        Must be one of {@link android.telephony.AccessNetworkConstants.AccessNetworkType}.
         * @param dataProfile Data profile used for data call setup. See {@link DataProfile}
         * @param isRoaming True if the device is data roaming.
         * @param allowRoaming True if data roaming is allowed by the user.
         * @param reason The reason for data setup. Must be {@link #REQUEST_REASON_NORMAL} or
         *        {@link #REQUEST_REASON_HANDOVER}.
         * @param linkProperties If {@code reason} is {@link #REQUEST_REASON_HANDOVER}, this is the
         *        link properties of the existing data connection, otherwise null.
         * @param pduSessionId The pdu session id to be used for this data call.
         *                     The standard range of values are 1-15 while 0 means no pdu session id
         *                     was attached to this call.  Reference: 3GPP TS 24.007 section
         *                     11.2.3.1b.
         * @param sliceInfo used within the data connection when a handover occurs from EPDG to 5G.
         *        The value is null unless the access network is
         *        {@link android.telephony.AccessNetworkConstants.AccessNetworkType#NGRAN} and a
         *        handover is occurring from EPDG to 5G.  If the slice passed is rejected, then
         *        {@link DataCallResponse#getCause()} is
         *        {@link android.telephony.DataFailCause#SLICE_REJECTED}.
         * @param trafficDescriptor {@link TrafficDescriptor} for which data connection needs to be
         *        established. It is used for URSP traffic matching as described in 3GPP TS 24.526
         *        Section 4.2.2. It includes an optional DNN which, if present, must be used for
         *        traffic matching; it does not specify the end point to be used for the data call.
         * @param matchAllRuleAllowed Indicates if using default match-all URSP rule for this
         *        request is allowed. If false, this request must not use the match-all URSP rule
         *        and if a non-match-all rule is not found (or if URSP rules are not available) then
         *        {@link DataCallResponse#getCause()} is
         *        {@link android.telephony.DataFailCause#MATCH_ALL_RULE_NOT_ALLOWED}. This is needed
         *        as some requests need to have a hard failure if the intention cannot be met,
         *        for example, a zero-rating slice.
         * @param callback The result callback for this request.
         */
        public void setupDataCall(int accessNetworkType, @NonNull DataProfile dataProfile,
                boolean isRoaming, boolean allowRoaming,
                @SetupDataReason int reason,
                @Nullable LinkProperties linkProperties,
                @IntRange(from = 0, to = 15) int pduSessionId, @Nullable NetworkSliceInfo sliceInfo,
                @Nullable TrafficDescriptor trafficDescriptor, boolean matchAllRuleAllowed,
                @NonNull DataServiceCallback callback) {
            /* Call the old version since the new version isn't supported */
            setupDataCall(accessNetworkType, dataProfile, isRoaming, allowRoaming, reason,
                    linkProperties, callback);
        }

        /**
         * Deactivate a data connection. The data service provider must implement this method to
         * support data connection tear down. When completed or error, the service must invoke the
         * provided callback to notify the platform.
         *
         * @param cid Call id returned in the callback of {@link DataServiceProvider#setupDataCall(
         *        int, DataProfile, boolean, boolean, int, LinkProperties, DataServiceCallback)}.
         * @param reason The reason for data deactivation. Must be {@link #REQUEST_REASON_NORMAL},
         *        {@link #REQUEST_REASON_SHUTDOWN} or {@link #REQUEST_REASON_HANDOVER}.
         * @param callback The result callback for this request. Null if the client does not care
         *        about the result.
         *
         */
        public void deactivateDataCall(int cid, @DeactivateDataReason int reason,
                                       @Nullable DataServiceCallback callback) {
            // The default implementation is to return unsupported.
            if (callback != null) {
                callback.onDeactivateDataCallComplete(DataServiceCallback.RESULT_ERROR_UNSUPPORTED);
            }
        }

        /**
         * Set an APN to initial attach network.
         *
         * @param dataProfile Data profile used for data call setup. See {@link DataProfile}.
         * @param isRoaming True if the device is data roaming.
         * @param callback The result callback for this request.
         */
        public void setInitialAttachApn(@NonNull DataProfile dataProfile, boolean isRoaming,
                                        @NonNull DataServiceCallback callback) {
            // The default implementation is to return unsupported.
            if (callback != null) {
                callback.onSetInitialAttachApnComplete(
                        DataServiceCallback.RESULT_ERROR_UNSUPPORTED);
            }
        }

        /**
         * Send current carrier's data profiles to the data service for data call setup. This is
         * only for CDMA carrier that can change the profile through OTA. The data service should
         * always uses the latest data profile sent by the framework.
         *
         * @param dps A list of data profiles.
         * @param isRoaming True if the device is data roaming.
         * @param callback The result callback for this request.
         */
        public void setDataProfile(@NonNull List<DataProfile> dps, boolean isRoaming,
                                   @NonNull DataServiceCallback callback) {
            // The default implementation is to return unsupported.
            if (callback != null) {
                callback.onSetDataProfileComplete(DataServiceCallback.RESULT_ERROR_UNSUPPORTED);
            }
        }

        /**
         * Indicates that a handover has begun.  This is called on the source transport.
         *
         * Any resources being transferred cannot be released while a
         * handover is underway.
         *
         * If a handover was unsuccessful, then the framework calls
         * {@link DataService#cancelHandover}.  The target transport retains ownership over any of
         * the resources being transferred.
         *
         * If a handover was successful, the framework calls {@link DataService#deactivateDataCall}
         * with reason {@link DataService.REQUEST_REASON_HANDOVER}. The target transport now owns
         * the transferred resources and is responsible for releasing them.
         *
         * @param cid The identifier of the data call which is provided in {@link DataCallResponse}
         * @param callback The result callback for this request.
         *
         * @hide
         */
        public void startHandover(int cid, @NonNull DataServiceCallback callback) {
            // The default implementation is to return unsupported.
            if (callback != null) {
                Log.d(TAG, "startHandover: " + cid);
                callback.onHandoverStarted(DataServiceCallback.RESULT_ERROR_UNSUPPORTED);
            } else {
                Log.e(TAG, "startHandover: " + cid + ", callback is null");
            }
        }

        /**
         * Indicates that a handover was cancelled after a call to
         * {@link DataService#startHandover}. This is called on the source transport.
         *
         * Since the handover was unsuccessful, the source transport retains ownership over any of
         * the resources being transferred and is still responsible for releasing them.
         *
         * @param cid The identifier of the data call which is provided in {@link DataCallResponse}
         * @param callback The result callback for this request.
         *
         * @hide
         */
        public void cancelHandover(int cid, @NonNull DataServiceCallback callback) {
            // The default implementation is to return unsupported.
            if (callback != null) {
                Log.d(TAG, "cancelHandover: " + cid);
                callback.onHandoverCancelled(DataServiceCallback.RESULT_ERROR_UNSUPPORTED);
            } else {
                Log.e(TAG, "cancelHandover: " + cid + ", callback is null");
            }
        }

        /**
         * Get the active data call list.
         *
         * @param callback The result callback for this request.
         */
        public void requestDataCallList(@NonNull DataServiceCallback callback) {
            // The default implementation is to return unsupported.
            callback.onRequestDataCallListComplete(DataServiceCallback.RESULT_ERROR_UNSUPPORTED,
                    null);
        }

        private void registerForDataCallListChanged(IDataServiceCallback callback) {
            synchronized (mDataCallListChangedCallbacks) {
                mDataCallListChangedCallbacks.add(callback);
            }
        }

        private void unregisterForDataCallListChanged(IDataServiceCallback callback) {
            synchronized (mDataCallListChangedCallbacks) {
                mDataCallListChangedCallbacks.remove(callback);
            }
        }

        private void registerForApnUnthrottled(IDataServiceCallback callback) {
            synchronized (mApnUnthrottledCallbacks) {
                mApnUnthrottledCallbacks.add(callback);
            }
        }

        private void unregisterForApnUnthrottled(IDataServiceCallback callback) {
            synchronized (mApnUnthrottledCallbacks) {
                mApnUnthrottledCallbacks.remove(callback);
            }
        }


        /**
         * Notify the system that current data call list changed. Data service must invoke this
         * method whenever there is any data call status changed.
         *
         * @param dataCallList List of the current active data call.
         */
        public final void notifyDataCallListChanged(List<DataCallResponse> dataCallList) {
            synchronized (mDataCallListChangedCallbacks) {
                for (IDataServiceCallback callback : mDataCallListChangedCallbacks) {
                    mHandler.obtainMessage(DATA_SERVICE_INDICATION_DATA_CALL_LIST_CHANGED,
                            mSlotIndex, 0, new DataCallListChangedIndication(dataCallList,
                                    callback)).sendToTarget();
                }
            }
        }

        /**
         * Notify the system that a given APN was unthrottled.
         *
         * @param apn Access Point Name defined by the carrier.
         */
        public final void notifyApnUnthrottled(@NonNull String apn) {
            synchronized (mApnUnthrottledCallbacks) {
                for (IDataServiceCallback callback : mApnUnthrottledCallbacks) {
                    mHandler.obtainMessage(DATA_SERVICE_INDICATION_APN_UNTHROTTLED,
                            mSlotIndex, 0, new ApnUnthrottledIndication(apn,
                                    callback)).sendToTarget();
                }
            }
        }

        /**
         * Called when the instance of data service is destroyed (e.g. got unbind or binder died)
         * or when the data service provider is removed. The extended class should implement this
         * method to perform cleanup works.
         */
        @Override
        public abstract void close();
    }

    private static final class SetupDataCallRequest {
        public final int accessNetworkType;
        public final DataProfile dataProfile;
        public final boolean isRoaming;
        public final boolean allowRoaming;
        public final int reason;
        public final LinkProperties linkProperties;
        public final int pduSessionId;
        public final NetworkSliceInfo sliceInfo;
        public final TrafficDescriptor trafficDescriptor;
        public final boolean matchAllRuleAllowed;
        public final IDataServiceCallback callback;
        SetupDataCallRequest(int accessNetworkType, DataProfile dataProfile, boolean isRoaming,
                boolean allowRoaming, int reason, LinkProperties linkProperties, int pduSessionId,
                NetworkSliceInfo sliceInfo, TrafficDescriptor trafficDescriptor,
                boolean matchAllRuleAllowed, IDataServiceCallback callback) {
            this.accessNetworkType = accessNetworkType;
            this.dataProfile = dataProfile;
            this.isRoaming = isRoaming;
            this.allowRoaming = allowRoaming;
            this.linkProperties = linkProperties;
            this.reason = reason;
            this.pduSessionId = pduSessionId;
            this.sliceInfo = sliceInfo;
            this.trafficDescriptor = trafficDescriptor;
            this.matchAllRuleAllowed = matchAllRuleAllowed;
            this.callback = callback;
        }
    }

    private static final class DeactivateDataCallRequest {
        public final int cid;
        public final int reason;
        public final IDataServiceCallback callback;
        DeactivateDataCallRequest(int cid, int reason, IDataServiceCallback callback) {
            this.cid = cid;
            this.reason = reason;
            this.callback = callback;
        }
    }

    private static final class SetInitialAttachApnRequest {
        public final DataProfile dataProfile;
        public final boolean isRoaming;
        public final IDataServiceCallback callback;
        SetInitialAttachApnRequest(DataProfile dataProfile, boolean isRoaming,
                                   IDataServiceCallback callback) {
            this.dataProfile = dataProfile;
            this.isRoaming = isRoaming;
            this.callback = callback;
        }
    }

    private static final class SetDataProfileRequest {
        public final List<DataProfile> dps;
        public final boolean isRoaming;
        public final IDataServiceCallback callback;
        SetDataProfileRequest(List<DataProfile> dps, boolean isRoaming,
                              IDataServiceCallback callback) {
            this.dps = dps;
            this.isRoaming = isRoaming;
            this.callback = callback;
        }
    }

    private static final class BeginCancelHandoverRequest {
        public final int cid;
        public final IDataServiceCallback callback;
        BeginCancelHandoverRequest(int cid,
                IDataServiceCallback callback) {
            this.cid = cid;
            this.callback = callback;
        }
    }

    private static final class DataCallListChangedIndication {
        public final List<DataCallResponse> dataCallList;
        public final IDataServiceCallback callback;
        DataCallListChangedIndication(List<DataCallResponse> dataCallList,
                                      IDataServiceCallback callback) {
            this.dataCallList = dataCallList;
            this.callback = callback;
        }
    }

    private static final class ApnUnthrottledIndication {
        public final String apn;
        public final IDataServiceCallback callback;
        ApnUnthrottledIndication(String apn,
                IDataServiceCallback callback) {
            this.apn = apn;
            this.callback = callback;
        }
    }

    private class DataServiceHandler extends Handler {

        DataServiceHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message message) {
            IDataServiceCallback callback;
            final int slotIndex = message.arg1;
            DataServiceProvider serviceProvider = mServiceMap.get(slotIndex);

            switch (message.what) {
                case DATA_SERVICE_CREATE_DATA_SERVICE_PROVIDER:
                    serviceProvider = onCreateDataServiceProvider(message.arg1);
                    if (serviceProvider != null) {
                        mServiceMap.put(slotIndex, serviceProvider);
                    }
                    break;
                case DATA_SERVICE_REMOVE_DATA_SERVICE_PROVIDER:
                    if (serviceProvider != null) {
                        serviceProvider.close();
                        mServiceMap.remove(slotIndex);
                    }
                    break;
                case DATA_SERVICE_REMOVE_ALL_DATA_SERVICE_PROVIDERS:
                    for (int i = 0; i < mServiceMap.size(); i++) {
                        serviceProvider = mServiceMap.get(i);
                        if (serviceProvider != null) {
                            serviceProvider.close();
                        }
                    }
                    mServiceMap.clear();
                    break;
                case DATA_SERVICE_REQUEST_SETUP_DATA_CALL:
                    if (serviceProvider == null) break;
                    SetupDataCallRequest setupDataCallRequest = (SetupDataCallRequest) message.obj;
                    serviceProvider.setupDataCall(setupDataCallRequest.accessNetworkType,
                            setupDataCallRequest.dataProfile, setupDataCallRequest.isRoaming,
                            setupDataCallRequest.allowRoaming, setupDataCallRequest.reason,
                            setupDataCallRequest.linkProperties, setupDataCallRequest.pduSessionId,
                            setupDataCallRequest.sliceInfo, setupDataCallRequest.trafficDescriptor,
                            setupDataCallRequest.matchAllRuleAllowed,
                            (setupDataCallRequest.callback != null)
                                    ? new DataServiceCallback(setupDataCallRequest.callback)
                                    : null);

                    break;
                case DATA_SERVICE_REQUEST_DEACTIVATE_DATA_CALL:
                    if (serviceProvider == null) break;
                    DeactivateDataCallRequest deactivateDataCallRequest =
                            (DeactivateDataCallRequest) message.obj;
                    serviceProvider.deactivateDataCall(deactivateDataCallRequest.cid,
                            deactivateDataCallRequest.reason,
                            (deactivateDataCallRequest.callback != null)
                                    ? new DataServiceCallback(deactivateDataCallRequest.callback)
                                    : null);
                    break;
                case DATA_SERVICE_REQUEST_SET_INITIAL_ATTACH_APN:
                    if (serviceProvider == null) break;
                    SetInitialAttachApnRequest setInitialAttachApnRequest =
                            (SetInitialAttachApnRequest) message.obj;
                    serviceProvider.setInitialAttachApn(setInitialAttachApnRequest.dataProfile,
                            setInitialAttachApnRequest.isRoaming,
                            (setInitialAttachApnRequest.callback != null)
                                    ? new DataServiceCallback(setInitialAttachApnRequest.callback)
                                    : null);
                    break;
                case DATA_SERVICE_REQUEST_SET_DATA_PROFILE:
                    if (serviceProvider == null) break;
                    SetDataProfileRequest setDataProfileRequest =
                            (SetDataProfileRequest) message.obj;
                    serviceProvider.setDataProfile(setDataProfileRequest.dps,
                            setDataProfileRequest.isRoaming,
                            (setDataProfileRequest.callback != null)
                                    ? new DataServiceCallback(setDataProfileRequest.callback)
                                    : null);
                    break;
                case DATA_SERVICE_REQUEST_REQUEST_DATA_CALL_LIST:
                    if (serviceProvider == null) break;

                    serviceProvider.requestDataCallList(new DataServiceCallback(
                            (IDataServiceCallback) message.obj));
                    break;
                case DATA_SERVICE_REQUEST_REGISTER_DATA_CALL_LIST_CHANGED:
                    if (serviceProvider == null) break;
                    serviceProvider.registerForDataCallListChanged((IDataServiceCallback) message.obj);
                    break;
                case DATA_SERVICE_REQUEST_UNREGISTER_DATA_CALL_LIST_CHANGED:
                    if (serviceProvider == null) break;
                    callback = (IDataServiceCallback) message.obj;
                    serviceProvider.unregisterForDataCallListChanged(callback);
                    break;
                case DATA_SERVICE_INDICATION_DATA_CALL_LIST_CHANGED:
                    if (serviceProvider == null) break;
                    DataCallListChangedIndication indication =
                            (DataCallListChangedIndication) message.obj;
                    try {
                        indication.callback.onDataCallListChanged(indication.dataCallList);
                    } catch (RemoteException e) {
                        loge("Failed to call onDataCallListChanged. " + e);
                    }
                    break;
                case DATA_SERVICE_REQUEST_START_HANDOVER:
                    if (serviceProvider == null) break;
                    BeginCancelHandoverRequest bReq = (BeginCancelHandoverRequest) message.obj;
                    serviceProvider.startHandover(bReq.cid,
                            (bReq.callback != null)
                                    ? new DataServiceCallback(bReq.callback) : null);
                    break;
                case DATA_SERVICE_REQUEST_CANCEL_HANDOVER:
                    if (serviceProvider == null) break;
                    BeginCancelHandoverRequest cReq = (BeginCancelHandoverRequest) message.obj;
                    serviceProvider.cancelHandover(cReq.cid,
                            (cReq.callback != null)
                                    ? new DataServiceCallback(cReq.callback) : null);
                    break;
                case DATA_SERVICE_REQUEST_REGISTER_APN_UNTHROTTLED:
                    if (serviceProvider == null) break;
                    serviceProvider.registerForApnUnthrottled((IDataServiceCallback) message.obj);
                    break;
                case DATA_SERVICE_REQUEST_UNREGISTER_APN_UNTHROTTLED:
                    if (serviceProvider == null) break;
                    callback = (IDataServiceCallback) message.obj;
                    serviceProvider.unregisterForApnUnthrottled(callback);
                    break;
                case DATA_SERVICE_INDICATION_APN_UNTHROTTLED:
                    if (serviceProvider == null) break;
                    ApnUnthrottledIndication apnUnthrottledIndication =
                            (ApnUnthrottledIndication) message.obj;
                    try {
                        apnUnthrottledIndication.callback
                                .onApnUnthrottled(apnUnthrottledIndication.apn);
                    } catch (RemoteException e) {
                        loge("Failed to call onApnUnthrottled. " + e);
                    }
                    break;
            }
        }
    }

    /**
     * Default constructor.
     */
    public DataService() {
        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();

        mHandler = new DataServiceHandler(mHandlerThread.getLooper());
        log("Data service created");
    }

    /**
     * Create the instance of {@link DataServiceProvider}. Data service provider must override
     * this method to facilitate the creation of {@link DataServiceProvider} instances. The system
     * will call this method after binding the data service for each active SIM slot id.
     *
     * This methead is guaranteed to be invoked in {@link DataService}'s internal handler thread
     * whose looper can be retrieved with {@link Looper.myLooper()} when override this method.
     *
     * @param slotIndex SIM slot id the data service associated with.
     * @return Data service object. Null if failed to create the provider (e.g. invalid slot index)
     */
    @Nullable
    public abstract DataServiceProvider onCreateDataServiceProvider(int slotIndex);

    @Override
    public IBinder onBind(Intent intent) {
        if (intent == null || !SERVICE_INTERFACE.equals(intent.getAction())) {
            loge("Unexpected intent " + intent);
            return null;
        }
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        mHandler.obtainMessage(DATA_SERVICE_REMOVE_ALL_DATA_SERVICE_PROVIDERS).sendToTarget();
        return false;
    }

    @Override
    public void onDestroy() {
        mHandlerThread.quit();
        super.onDestroy();
    }

    /**
     * A wrapper around IDataService that forwards calls to implementations of {@link DataService}.
     */
    private class IDataServiceWrapper extends IDataService.Stub {
        @Override
        public void createDataServiceProvider(int slotIndex) {
            mHandler.obtainMessage(DATA_SERVICE_CREATE_DATA_SERVICE_PROVIDER, slotIndex, 0)
                    .sendToTarget();
        }

        @Override
        public void removeDataServiceProvider(int slotIndex) {
            mHandler.obtainMessage(DATA_SERVICE_REMOVE_DATA_SERVICE_PROVIDER, slotIndex, 0)
                    .sendToTarget();
        }

        @Override
        public void setupDataCall(int slotIndex, int accessNetworkType, DataProfile dataProfile,
                boolean isRoaming, boolean allowRoaming, int reason,
                LinkProperties linkProperties, int pduSessionId, NetworkSliceInfo sliceInfo,
                TrafficDescriptor trafficDescriptor, boolean matchAllRuleAllowed,
                IDataServiceCallback callback) {
            mHandler.obtainMessage(DATA_SERVICE_REQUEST_SETUP_DATA_CALL, slotIndex, 0,
                    new SetupDataCallRequest(accessNetworkType, dataProfile, isRoaming,
                            allowRoaming, reason, linkProperties, pduSessionId, sliceInfo,
                            trafficDescriptor, matchAllRuleAllowed, callback))
                    .sendToTarget();
        }

        @Override
        public void deactivateDataCall(int slotIndex, int cid, int reason,
                                       IDataServiceCallback callback) {
            mHandler.obtainMessage(DATA_SERVICE_REQUEST_DEACTIVATE_DATA_CALL, slotIndex, 0,
                    new DeactivateDataCallRequest(cid, reason, callback))
                    .sendToTarget();
        }

        @Override
        public void setInitialAttachApn(int slotIndex, DataProfile dataProfile, boolean isRoaming,
                                        IDataServiceCallback callback) {
            mHandler.obtainMessage(DATA_SERVICE_REQUEST_SET_INITIAL_ATTACH_APN, slotIndex, 0,
                    new SetInitialAttachApnRequest(dataProfile, isRoaming, callback))
                    .sendToTarget();
        }

        @Override
        public void setDataProfile(int slotIndex, List<DataProfile> dps, boolean isRoaming,
                                   IDataServiceCallback callback) {
            mHandler.obtainMessage(DATA_SERVICE_REQUEST_SET_DATA_PROFILE, slotIndex, 0,
                    new SetDataProfileRequest(dps, isRoaming, callback)).sendToTarget();
        }

        @Override
        public void requestDataCallList(int slotIndex, IDataServiceCallback callback) {
            if (callback == null) {
                loge("requestDataCallList: callback is null");
                return;
            }
            mHandler.obtainMessage(DATA_SERVICE_REQUEST_REQUEST_DATA_CALL_LIST, slotIndex, 0,
                    callback).sendToTarget();
        }

        @Override
        public void registerForDataCallListChanged(int slotIndex, IDataServiceCallback callback) {
            if (callback == null) {
                loge("registerForDataCallListChanged: callback is null");
                return;
            }
            mHandler.obtainMessage(DATA_SERVICE_REQUEST_REGISTER_DATA_CALL_LIST_CHANGED, slotIndex,
                    0, callback).sendToTarget();
        }

        @Override
        public void unregisterForDataCallListChanged(int slotIndex, IDataServiceCallback callback) {
            if (callback == null) {
                loge("unregisterForDataCallListChanged: callback is null");
                return;
            }
            mHandler.obtainMessage(DATA_SERVICE_REQUEST_UNREGISTER_DATA_CALL_LIST_CHANGED,
                    slotIndex, 0, callback).sendToTarget();
        }

        @Override
        public void startHandover(int slotIndex, int cid, IDataServiceCallback callback) {
            if (callback == null) {
                loge("startHandover: callback is null");
                return;
            }
            BeginCancelHandoverRequest req = new BeginCancelHandoverRequest(cid, callback);
            mHandler.obtainMessage(DATA_SERVICE_REQUEST_START_HANDOVER,
                    slotIndex, 0, req)
                    .sendToTarget();
        }

        @Override
        public void cancelHandover(int slotIndex, int cid, IDataServiceCallback callback) {
            if (callback == null) {
                loge("cancelHandover: callback is null");
                return;
            }
            BeginCancelHandoverRequest req = new BeginCancelHandoverRequest(cid, callback);
            mHandler.obtainMessage(DATA_SERVICE_REQUEST_CANCEL_HANDOVER,
                    slotIndex, 0, req).sendToTarget();
        }

        @Override
        public void registerForUnthrottleApn(int slotIndex, IDataServiceCallback callback) {
            if (callback == null) {
                loge("registerForUnthrottleApn: callback is null");
                return;
            }
            mHandler.obtainMessage(DATA_SERVICE_REQUEST_REGISTER_APN_UNTHROTTLED, slotIndex,
                    0, callback).sendToTarget();
        }

        @Override
        public void unregisterForUnthrottleApn(int slotIndex, IDataServiceCallback callback) {
            if (callback == null) {
                loge("uregisterForUnthrottleApn: callback is null");
                return;
            }
            mHandler.obtainMessage(DATA_SERVICE_REQUEST_UNREGISTER_APN_UNTHROTTLED,
                    slotIndex, 0, callback).sendToTarget();
        }
    }

    private void log(String s) {
        Rlog.d(TAG, s);
    }

    private void loge(String s) {
        Rlog.e(TAG, s);
    }
}
