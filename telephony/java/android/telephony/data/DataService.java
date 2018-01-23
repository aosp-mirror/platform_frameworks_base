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

import android.annotation.CallSuper;
import android.annotation.IntDef;
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
import android.telephony.AccessNetworkConstants;
import android.telephony.Rlog;
import android.telephony.SubscriptionManager;
import android.util.SparseArray;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * Base class of data service. Services that extend DataService must register the service in
 * their AndroidManifest to be detected by the framework. They must be protected by the permission
 * "android.permission.BIND_DATA_SERVICE". The data service definition in the manifest must follow
 * the following format:
 * ...
 * <service android:name=".xxxDataService"
 *     android:permission="android.permission.BIND_DATA_SERVICE" >
 *     <intent-filter>
 *         <action android:name="android.telephony.data.DataService" />
 *     </intent-filter>
 * </service>
 * @hide
 */
@SystemApi
public abstract class DataService extends Service {
    private static final String TAG = DataService.class.getSimpleName();

    public static final String DATA_SERVICE_INTERFACE = "android.telephony.data.DataService";
    public static final String DATA_SERVICE_EXTRA_SLOT_ID = "android.telephony.data.extra.SLOT_ID";

    /** {@hide} */
    @IntDef(prefix = "REQUEST_REASON_", value = {
            REQUEST_REASON_NORMAL,
            REQUEST_REASON_HANDOVER,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SetupDataReason {}

    /** {@hide} */
    @IntDef(prefix = "REQUEST_REASON_", value = {
            REQUEST_REASON_NORMAL,
            REQUEST_REASON_SHUTDOWN,
            REQUEST_REASON_HANDOVER,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DeactivateDataReason {}


    /** The reason of the data request is normal */
    public static final int REQUEST_REASON_NORMAL = 1;

    /** The reason of the data request is device shutdown */
    public static final int REQUEST_REASON_SHUTDOWN = 2;

    /** The reason of the data request is IWLAN handover */
    public static final int REQUEST_REASON_HANDOVER = 3;

    private static final int DATA_SERVICE_INTERNAL_REQUEST_INITIALIZE_SERVICE          = 1;
    private static final int DATA_SERVICE_REQUEST_SETUP_DATA_CALL                      = 2;
    private static final int DATA_SERVICE_REQUEST_DEACTIVATE_DATA_CALL                 = 3;
    private static final int DATA_SERVICE_REQUEST_SET_INITIAL_ATTACH_APN               = 4;
    private static final int DATA_SERVICE_REQUEST_SET_DATA_PROFILE                     = 5;
    private static final int DATA_SERVICE_REQUEST_GET_DATA_CALL_LIST                   = 6;
    private static final int DATA_SERVICE_REQUEST_REGISTER_DATA_CALL_LIST_CHANGED      = 7;
    private static final int DATA_SERVICE_REQUEST_UNREGISTER_DATA_CALL_LIST_CHANGED    = 8;
    private static final int DATA_SERVICE_INDICATION_DATA_CALL_LIST_CHANGED            = 9;

    private final HandlerThread mHandlerThread;

    private final DataServiceHandler mHandler;

    private final SparseArray<DataServiceProvider> mServiceMap = new SparseArray<>();

    private final SparseArray<IDataServiceWrapper> mBinderMap = new SparseArray<>();

    /**
     * The abstract class of the actual data service implementation. The data service provider
     * must extend this class to support data connection. Note that each instance of data service
     * provider is associated with one physical SIM slot.
     */
    public class DataServiceProvider {

        private final int mSlotId;

        private final List<IDataServiceCallback> mDataCallListChangedCallbacks = new ArrayList<>();

        /**
         * Constructor
         * @param slotId SIM slot id the data service provider associated with.
         */
        public DataServiceProvider(int slotId) {
            mSlotId = slotId;
        }

        /**
         * @return SIM slot id the data service provider associated with.
         */
        public final int getSlotId() {
            return mSlotId;
        }

        /**
         * Setup a data connection. The data service provider must implement this method to support
         * establishing a packet data connection. When completed or error, the service must invoke
         * the provided callback to notify the platform.
         *
         * @param accessNetworkType Access network type that the data call will be established on.
         * Must be one of {@link AccessNetworkConstants.AccessNetworkType}.
         * @param dataProfile Data profile used for data call setup. See {@link DataProfile}
         * @param isRoaming True if the device is data roaming.
         * @param allowRoaming True if data roaming is allowed by the user.
         * @param reason The reason for data setup. Must be {@link #REQUEST_REASON_NORMAL} or
         * {@link #REQUEST_REASON_HANDOVER}.
         * @param linkProperties If {@code reason} is {@link #REQUEST_REASON_HANDOVER}, this is the
         * link properties of the existing data connection, otherwise null.
         * @param callback The result callback for this request.
         */
        public void setupDataCall(int accessNetworkType, DataProfile dataProfile, boolean isRoaming,
                                  boolean allowRoaming, @SetupDataReason int reason,
                                  LinkProperties linkProperties, DataServiceCallback callback) {
            // The default implementation is to return unsupported.
            callback.onSetupDataCallComplete(DataServiceCallback.RESULT_ERROR_UNSUPPORTED, null);
        }

        /**
         * Deactivate a data connection. The data service provider must implement this method to
         * support data connection tear down. When completed or error, the service must invoke the
         * provided callback to notify the platform.
         *
         * @param cid Call id returned in the callback of {@link DataServiceProvider#setupDataCall(
         * int, DataProfile, boolean, boolean, int, LinkProperties, DataServiceCallback)}.
         * @param reason The reason for data deactivation. Must be {@link #REQUEST_REASON_NORMAL},
         * {@link #REQUEST_REASON_SHUTDOWN} or {@link #REQUEST_REASON_HANDOVER}.
         * @param callback The result callback for this request.
         */
        public void deactivateDataCall(int cid, @DeactivateDataReason int reason,
                                       DataServiceCallback callback) {
            // The default implementation is to return unsupported.
            callback.onDeactivateDataCallComplete(DataServiceCallback.RESULT_ERROR_UNSUPPORTED);
        }

        /**
         * Set an APN to initial attach network.
         *
         * @param dataProfile Data profile used for data call setup. See {@link DataProfile}.
         * @param isRoaming True if the device is data roaming.
         * @param callback The result callback for this request.
         */
        public void setInitialAttachApn(DataProfile dataProfile, boolean isRoaming,
                                        DataServiceCallback callback) {
            // The default implementation is to return unsupported.
            callback.onSetInitialAttachApnComplete(DataServiceCallback.RESULT_ERROR_UNSUPPORTED);
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
        public void setDataProfile(List<DataProfile> dps, boolean isRoaming,
                                   DataServiceCallback callback) {
            // The default implementation is to return unsupported.
            callback.onSetDataProfileComplete(DataServiceCallback.RESULT_ERROR_UNSUPPORTED);
        }

        /**
         * Get the active data call list.
         *
         * @param callback The result callback for this request.
         */
        public void getDataCallList(DataServiceCallback callback) {
            // The default implementation is to return unsupported.
            callback.onGetDataCallListComplete(DataServiceCallback.RESULT_ERROR_UNSUPPORTED, null);
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

        /**
         * Notify the system that current data call list changed. Data service must invoke this
         * method whenever there is any data call status changed.
         *
         * @param dataCallList List of the current active data call.
         */
        public final void notifyDataCallListChanged(List<DataCallResponse> dataCallList) {
            synchronized (mDataCallListChangedCallbacks) {
                for (IDataServiceCallback callback : mDataCallListChangedCallbacks) {
                    mHandler.obtainMessage(DATA_SERVICE_INDICATION_DATA_CALL_LIST_CHANGED, mSlotId,
                            0, new DataCallListChangedIndication(dataCallList, callback))
                            .sendToTarget();
                }
            }
        }

        /**
         * Called when the instance of data service is destroyed (e.g. got unbind or binder died).
         */
        @CallSuper
        protected void onDestroy() {
            mDataCallListChangedCallbacks.clear();
        }
    }

    private static final class SetupDataCallRequest {
        public final int accessNetworkType;
        public final DataProfile dataProfile;
        public final boolean isRoaming;
        public final boolean allowRoaming;
        public final int reason;
        public final LinkProperties linkProperties;
        public final IDataServiceCallback callback;
        SetupDataCallRequest(int accessNetworkType, DataProfile dataProfile, boolean isRoaming,
                             boolean allowRoaming, int reason, LinkProperties linkProperties,
                             IDataServiceCallback callback) {
            this.accessNetworkType = accessNetworkType;
            this.dataProfile = dataProfile;
            this.isRoaming = isRoaming;
            this.allowRoaming = allowRoaming;
            this.linkProperties = linkProperties;
            this.reason = reason;
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

    private static final class DataCallListChangedIndication {
        public final List<DataCallResponse> dataCallList;
        public final IDataServiceCallback callback;
        DataCallListChangedIndication(List<DataCallResponse> dataCallList,
                                      IDataServiceCallback callback) {
            this.dataCallList = dataCallList;
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
            final int slotId = message.arg1;
            DataServiceProvider service;

            synchronized (mServiceMap) {
                service = mServiceMap.get(slotId);
            }

            switch (message.what) {
                case DATA_SERVICE_INTERNAL_REQUEST_INITIALIZE_SERVICE:
                    service = createDataServiceProvider(message.arg1);
                    if (service != null) {
                        mServiceMap.put(slotId, service);
                    }
                    break;
                case DATA_SERVICE_REQUEST_SETUP_DATA_CALL:
                    if (service == null) break;
                    SetupDataCallRequest setupDataCallRequest = (SetupDataCallRequest) message.obj;
                    service.setupDataCall(setupDataCallRequest.accessNetworkType,
                            setupDataCallRequest.dataProfile, setupDataCallRequest.isRoaming,
                            setupDataCallRequest.allowRoaming, setupDataCallRequest.reason,
                            setupDataCallRequest.linkProperties,
                            new DataServiceCallback(setupDataCallRequest.callback));

                    break;
                case DATA_SERVICE_REQUEST_DEACTIVATE_DATA_CALL:
                    if (service == null) break;
                    DeactivateDataCallRequest deactivateDataCallRequest =
                            (DeactivateDataCallRequest) message.obj;
                    service.deactivateDataCall(deactivateDataCallRequest.cid,
                            deactivateDataCallRequest.reason,
                            new DataServiceCallback(deactivateDataCallRequest.callback));
                    break;
                case DATA_SERVICE_REQUEST_SET_INITIAL_ATTACH_APN:
                    if (service == null) break;
                    SetInitialAttachApnRequest setInitialAttachApnRequest =
                            (SetInitialAttachApnRequest) message.obj;
                    service.setInitialAttachApn(setInitialAttachApnRequest.dataProfile,
                            setInitialAttachApnRequest.isRoaming,
                            new DataServiceCallback(setInitialAttachApnRequest.callback));
                    break;
                case DATA_SERVICE_REQUEST_SET_DATA_PROFILE:
                    if (service == null) break;
                    SetDataProfileRequest setDataProfileRequest =
                            (SetDataProfileRequest) message.obj;
                    service.setDataProfile(setDataProfileRequest.dps,
                            setDataProfileRequest.isRoaming,
                            new DataServiceCallback(setDataProfileRequest.callback));
                    break;
                case DATA_SERVICE_REQUEST_GET_DATA_CALL_LIST:
                    if (service == null) break;

                    service.getDataCallList(new DataServiceCallback(
                            (IDataServiceCallback) message.obj));
                    break;
                case DATA_SERVICE_REQUEST_REGISTER_DATA_CALL_LIST_CHANGED:
                    if (service == null) break;
                    service.registerForDataCallListChanged((IDataServiceCallback) message.obj);
                    break;
                case DATA_SERVICE_REQUEST_UNREGISTER_DATA_CALL_LIST_CHANGED:
                    if (service == null) break;
                    callback = (IDataServiceCallback) message.obj;
                    service.unregisterForDataCallListChanged(callback);
                    break;
                case DATA_SERVICE_INDICATION_DATA_CALL_LIST_CHANGED:
                    if (service == null) break;
                    DataCallListChangedIndication indication =
                            (DataCallListChangedIndication) message.obj;
                    try {
                        indication.callback.onDataCallListChanged(indication.dataCallList);
                    } catch (RemoteException e) {
                        loge("Failed to call onDataCallListChanged. " + e);
                    }
                    break;
            }
        }
    }

    /** @hide */
    protected DataService() {
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
     * @param slotId SIM slot id the data service associated with.
     * @return Data service object
     */
    public abstract DataServiceProvider createDataServiceProvider(int slotId);

    /** @hide */
    @Override
    public IBinder onBind(Intent intent) {
        if (intent == null || !DATA_SERVICE_INTERFACE.equals(intent.getAction())) {
            loge("Unexpected intent " + intent);
            return null;
        }

        int slotId = intent.getIntExtra(
                DATA_SERVICE_EXTRA_SLOT_ID, SubscriptionManager.INVALID_SIM_SLOT_INDEX);

        if (!SubscriptionManager.isValidSlotIndex(slotId)) {
            loge("Invalid slot id " + slotId);
            return null;
        }

        log("onBind: slot id=" + slotId);

        IDataServiceWrapper binder = mBinderMap.get(slotId);
        if (binder == null) {
            Message msg = mHandler.obtainMessage(DATA_SERVICE_INTERNAL_REQUEST_INITIALIZE_SERVICE);
            msg.arg1 = slotId;
            msg.sendToTarget();

            binder = new IDataServiceWrapper(slotId);
            mBinderMap.put(slotId, binder);
        }

        return binder;
    }

    /** @hide */
    @Override
    public boolean onUnbind(Intent intent) {
        int slotId = intent.getIntExtra(DATA_SERVICE_EXTRA_SLOT_ID,
                SubscriptionManager.INVALID_SIM_SLOT_INDEX);
        if (mBinderMap.get(slotId) != null) {
            DataServiceProvider serviceImpl;
            synchronized (mServiceMap) {
                serviceImpl = mServiceMap.get(slotId);
            }
            if (serviceImpl != null) {
                serviceImpl.onDestroy();
            }
            mBinderMap.remove(slotId);
        }

        // If all clients unbinds, quit the handler thread
        if (mBinderMap.size() == 0) {
            mHandlerThread.quit();
        }

        return false;
    }

    /** @hide */
    @Override
    public void onDestroy() {
        synchronized (mServiceMap) {
            for (int i = 0; i < mServiceMap.size(); i++) {
                DataServiceProvider serviceImpl = mServiceMap.get(i);
                if (serviceImpl != null) {
                    serviceImpl.onDestroy();
                }
            }
            mServiceMap.clear();
        }

        mHandlerThread.quit();
    }

    /**
     * A wrapper around IDataService that forwards calls to implementations of {@link DataService}.
     */
    private class IDataServiceWrapper extends IDataService.Stub {

        private final int mSlotId;

        IDataServiceWrapper(int slotId) {
            mSlotId = slotId;
        }

        @Override
        public void setupDataCall(int accessNetworkType, DataProfile dataProfile,
                                  boolean isRoaming, boolean allowRoaming, int reason,
                                  LinkProperties linkProperties, IDataServiceCallback callback) {
            mHandler.obtainMessage(DATA_SERVICE_REQUEST_SETUP_DATA_CALL, mSlotId, 0,
                    new SetupDataCallRequest(accessNetworkType, dataProfile, isRoaming,
                            allowRoaming, reason, linkProperties, callback))
                    .sendToTarget();
        }

        @Override
        public void deactivateDataCall(int cid, int reason, IDataServiceCallback callback) {
            mHandler.obtainMessage(DATA_SERVICE_REQUEST_DEACTIVATE_DATA_CALL, mSlotId, 0,
                    new DeactivateDataCallRequest(cid, reason, callback))
                    .sendToTarget();
        }

        @Override
        public void setInitialAttachApn(DataProfile dataProfile, boolean isRoaming,
                                        IDataServiceCallback callback) {
            mHandler.obtainMessage(DATA_SERVICE_REQUEST_SET_INITIAL_ATTACH_APN, mSlotId, 0,
                    new SetInitialAttachApnRequest(dataProfile, isRoaming, callback))
                    .sendToTarget();
        }

        @Override
        public void setDataProfile(List<DataProfile> dps, boolean isRoaming,
                                   IDataServiceCallback callback) {
            mHandler.obtainMessage(DATA_SERVICE_REQUEST_SET_DATA_PROFILE, mSlotId, 0,
                    new SetDataProfileRequest(dps, isRoaming, callback)).sendToTarget();
        }

        @Override
        public void getDataCallList(IDataServiceCallback callback) {
            mHandler.obtainMessage(DATA_SERVICE_REQUEST_GET_DATA_CALL_LIST, mSlotId, 0,
                    callback).sendToTarget();
        }

        @Override
        public void registerForDataCallListChanged(IDataServiceCallback callback) {
            if (callback == null) {
                loge("Callback is null");
                return;
            }
            mHandler.obtainMessage(DATA_SERVICE_REQUEST_REGISTER_DATA_CALL_LIST_CHANGED, mSlotId,
                    0, callback).sendToTarget();
        }

        @Override
        public void unregisterForDataCallListChanged(IDataServiceCallback callback) {
            if (callback == null) {
                loge("Callback is null");
                return;
            }
            mHandler.obtainMessage(DATA_SERVICE_REQUEST_UNREGISTER_DATA_CALL_LIST_CHANGED, mSlotId,
                    0, callback).sendToTarget();
        }
    }

    private void log(String s) {
        Rlog.d(TAG, s);
    }

    private void loge(String s) {
        Rlog.e(TAG, s);
    }
}
