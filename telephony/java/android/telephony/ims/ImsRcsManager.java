/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.telephony.ims;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SystemApi;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.provider.Settings;
import android.telephony.AccessNetworkConstants;
import android.telephony.BinderCacheManager;
import android.telephony.TelephonyFrameworkInitializer;
import android.telephony.ims.aidl.IImsCapabilityCallback;
import android.telephony.ims.aidl.IImsRcsController;
import android.telephony.ims.feature.ImsFeature;
import android.telephony.ims.stub.ImsRegistrationImplBase;
import android.util.Log;

import com.android.internal.telephony.IIntegerConsumer;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Manager for interfacing with the framework RCS services, including the User Capability Exchange
 * (UCE) service, as well as managing user settings.
 *
 * Use {@link ImsManager#getImsRcsManager(int)} to create an instance of this manager.
 */
public class ImsRcsManager {
    private static final String TAG = "ImsRcsManager";

    /**
     * Activity Action: Show the opt-in dialog for enabling or disabling RCS contact discovery
     * using User Capability Exchange (UCE), which enables a service that periodically shares the
     * phone numbers of all of the contacts in the user's address book with the carrier to refresh
     * the RCS capabilities associated with those contacts as the local cache becomes stale.
     * <p>
     * An application that depends on RCS contact discovery being enabled must send this intent
     * using {@link Context#startActivity(Intent)} to ask the user to opt-in for contacts upload for
     * capability exchange if it is currently disabled. Whether or not RCS contact discovery has
     * been enabled by the user can be queried using {@link RcsUceAdapter#isUceSettingEnabled()}.
     * <p>
     * This intent will always be handled by the system, however the application should only send
     * this Intent if the carrier supports bulk RCS contact exchange, which will be true if either
     * key {@link android.telephony.CarrierConfigManager.Ims#KEY_RCS_BULK_CAPABILITY_EXCHANGE_BOOL}
     * or {@link android.telephony.CarrierConfigManager#KEY_USE_RCS_PRESENCE_BOOL} is set to true.
     * Otherwise, the RCS contact discovery opt-in dialog will not be shown.
     * <p>
     * Input: A mandatory {@link Settings#EXTRA_SUB_ID} extra containing the subscription that the
     * setting will be be shown for.
     * <p>
     * Output: Nothing
     * @see RcsUceAdapter
     */
    @SdkConstant(SdkConstant.SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_SHOW_CAPABILITY_DISCOVERY_OPT_IN =
            "android.telephony.ims.action.SHOW_CAPABILITY_DISCOVERY_OPT_IN";

    /**
     * An application can use {@link #addOnAvailabilityChangedListener} to register a
     * {@link OnAvailabilityChangedListener}, which will notify the user when the RCS feature
     * availability status updates from the ImsService.
     * @hide
     */
    @SystemApi
    public interface OnAvailabilityChangedListener {
        /**
         * The availability of the feature's capabilities has changed to either available or
         * unavailable.
         * <p>
         * If unavailable, the feature does not support the capability at the current time. This may
         * be due to network or subscription provisioning changes, such as the IMS registration
         * being lost, network type changing, or OMA-DM provisioning updates.
         *
         * @param capabilities The new availability of the capabilities.
         */
        void onAvailabilityChanged(@RcsUceAdapter.RcsImsCapabilityFlag int capabilities);
    }

    /**
     * Receive the availability status changed from the ImsService and pass the status change to
     * the associated {@link OnAvailabilityChangedListener}
     */
    private static class AvailabilityCallbackAdapter {

        private static class CapabilityBinder extends IImsCapabilityCallback.Stub {
            private final OnAvailabilityChangedListener mOnAvailabilityChangedListener;
            private final Executor mExecutor;

            CapabilityBinder(OnAvailabilityChangedListener listener, Executor executor) {
                mExecutor = executor;
                mOnAvailabilityChangedListener = listener;
            }

            @Override
            public void onCapabilitiesStatusChanged(int config) {
                if (mOnAvailabilityChangedListener == null) return;

                long callingIdentity = Binder.clearCallingIdentity();
                try {
                    mExecutor.execute(() ->
                            mOnAvailabilityChangedListener.onAvailabilityChanged(config));
                } finally {
                    restoreCallingIdentity(callingIdentity);
                }
            }

            @Override
            public void onQueryCapabilityConfiguration(int capability, int radioTech,
                    boolean isEnabled) {
                // This is not used.
            }

            @Override
            public void onChangeCapabilityConfigurationError(int capability, int radioTech,
                    @ImsFeature.ImsCapabilityError int reason) {
                // This is not used.
            }
        }

        private final CapabilityBinder mBinder;

        AvailabilityCallbackAdapter(@NonNull Executor executor,
                @NonNull OnAvailabilityChangedListener listener) {
            mBinder = new CapabilityBinder(listener, executor);
        }

        /**@hide*/
        public final IImsCapabilityCallback getBinder() {
            return mBinder;
        }
    }

    private final int mSubId;
    private final Context mContext;
    private final BinderCacheManager<IImsRcsController> mBinderCache;
    private final Map<OnAvailabilityChangedListener, AvailabilityCallbackAdapter>
            mAvailabilityChangedCallbacks;

    /**
     * Use {@link ImsManager#getImsRcsManager(int)} to create an instance of this class.
     * @hide
     */
    public ImsRcsManager(Context context, int subId,
            BinderCacheManager<IImsRcsController> binderCache) {
        mSubId = subId;
        mContext = context;
        mBinderCache = binderCache;
        mAvailabilityChangedCallbacks = new HashMap<>();
    }

    /**
     * @return A {@link RcsUceAdapter} used for User Capability Exchange (UCE) operations for
     * this subscription.
     */
    @NonNull
    public RcsUceAdapter getUceAdapter() {
        return new RcsUceAdapter(mContext, mSubId);
    }

    /**
     * Registers a {@link RegistrationManager.RegistrationCallback} with the system. When the
     * callback is registered, it will initiate the callback c to be called with the current
     * registration state.
     *
     * Requires Permission: {@link android.Manifest.permission#READ_PRECISE_PHONE_STATE
     * READ_PRECISE_PHONE_STATE} or that the calling app has carrier privileges
     * (see {@link android.telephony.TelephonyManager#hasCarrierPrivileges}).
     *
     * @param executor The executor the callback events should be run on.
     * @param c The {@link RegistrationManager.RegistrationCallback} to be added.
     * @see #unregisterImsRegistrationCallback(RegistrationManager.RegistrationCallback)
     * @throws ImsException if the subscription associated with this callback is valid, but
     * the {@link ImsService} associated with the subscription is not available. This can happen if
     * the service crashed, for example. See {@link ImsException#getCode()} for a more detailed
     * reason.
     */
    @RequiresPermission(Manifest.permission.READ_PRECISE_PHONE_STATE)
    public void registerImsRegistrationCallback(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull RegistrationManager.RegistrationCallback c)
            throws ImsException {
        if (c == null) {
            throw new IllegalArgumentException("Must include a non-null RegistrationCallback.");
        }
        if (executor == null) {
            throw new IllegalArgumentException("Must include a non-null Executor.");
        }

        IImsRcsController imsRcsController = getIImsRcsController();
        if (imsRcsController == null) {
            Log.w(TAG, "Register registration callback: IImsRcsController is null");
            throw new ImsException("Cannot find remote IMS service",
                    ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }

        c.setExecutor(executor);
        try {
            imsRcsController.registerImsRegistrationCallback(mSubId, c.getBinder());
        } catch (ServiceSpecificException e) {
            throw new ImsException(e.toString(), e.errorCode);
        } catch (RemoteException | IllegalStateException e) {
            throw new ImsException(e.getMessage(), ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
    }

    /**
     * Removes an existing {@link RegistrationManager.RegistrationCallback}.
     *
     * When the subscription associated with this callback is removed (SIM removed, ESIM swap,
     * etc...), this callback will automatically be removed. If this method is called for an
     * inactive subscription, it will result in a no-op.
     *
     * Requires Permission: {@link android.Manifest.permission#READ_PRECISE_PHONE_STATE
     * READ_PRECISE_PHONE_STATE} or that the calling app has carrier privileges
     * (see {@link android.telephony.TelephonyManager#hasCarrierPrivileges}).
     *
     * @param c The {@link RegistrationManager.RegistrationCallback} to be removed.
     * @see android.telephony.SubscriptionManager.OnSubscriptionsChangedListener
     * @see #registerImsRegistrationCallback(Executor, RegistrationCallback)
     */
    @RequiresPermission(Manifest.permission.READ_PRECISE_PHONE_STATE)
    public void unregisterImsRegistrationCallback(
            @NonNull RegistrationManager.RegistrationCallback c) {
        if (c == null) {
            throw new IllegalArgumentException("Must include a non-null RegistrationCallback.");
        }

        IImsRcsController imsRcsController = getIImsRcsController();
        if (imsRcsController == null) {
            Log.w(TAG, "Unregister registration callback: IImsRcsController is null");
            throw new IllegalStateException("Cannot find remote IMS service");
        }

        try {
            imsRcsController.unregisterImsRegistrationCallback(mSubId, c.getBinder());
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Gets the registration state of the IMS service.
     *
     * Requires Permission: {@link android.Manifest.permission#READ_PRECISE_PHONE_STATE
     * READ_PRECISE_PHONE_STATE} or that the calling app has carrier privileges
     * (see {@link android.telephony.TelephonyManager#hasCarrierPrivileges}).
     *
     * @param executor The {@link Executor} that will be used to call the IMS registration state
     * callback.
     * @param stateCallback A callback called on the supplied {@link Executor} that will contain the
     * registration state of the IMS service, which will be one of the
     * following: {@link RegistrationManager#REGISTRATION_STATE_NOT_REGISTERED},
     * {@link RegistrationManager#REGISTRATION_STATE_REGISTERING}, or
     * {@link RegistrationManager#REGISTRATION_STATE_REGISTERED}.
     */
    @RequiresPermission(Manifest.permission.READ_PRECISE_PHONE_STATE)
    public void getRegistrationState(@NonNull @CallbackExecutor Executor executor,
            @NonNull @RegistrationManager.ImsRegistrationState Consumer<Integer> stateCallback) {
        if (stateCallback == null) {
            throw new IllegalArgumentException("Must include a non-null stateCallback.");
        }
        if (executor == null) {
            throw new IllegalArgumentException("Must include a non-null Executor.");
        }

        IImsRcsController imsRcsController = getIImsRcsController();
        if (imsRcsController == null) {
            Log.w(TAG, "Get registration state error: IImsRcsController is null");
            throw new IllegalStateException("Cannot find remote IMS service");
        }

        try {
            imsRcsController.getImsRcsRegistrationState(mSubId, new IIntegerConsumer.Stub() {
                @Override
                public void accept(int result) {
                    executor.execute(() -> stateCallback.accept(result));
                }
            });
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Gets the Transport Type associated with the current IMS registration.
     *
     * Requires Permission: {@link android.Manifest.permission#READ_PRECISE_PHONE_STATE
     * READ_PRECISE_PHONE_STATE} or that the calling app has carrier privileges
     * (see {@link android.telephony.TelephonyManager#hasCarrierPrivileges}).
     *
     * @param executor The {@link Executor} that will be used to call the transportTypeCallback.
     * @param transportTypeCallback The transport type associated with the current IMS registration,
     * which will be one of following:
     * {@see AccessNetworkConstants#TRANSPORT_TYPE_WWAN},
     * {@see AccessNetworkConstants#TRANSPORT_TYPE_WLAN}, or
     * {@see AccessNetworkConstants#TRANSPORT_TYPE_INVALID}.
     */
    @RequiresPermission(Manifest.permission.READ_PRECISE_PHONE_STATE)
    public void getRegistrationTransportType(@NonNull @CallbackExecutor Executor executor,
            @NonNull @AccessNetworkConstants.TransportType
                    Consumer<Integer> transportTypeCallback) {
        if (transportTypeCallback == null) {
            throw new IllegalArgumentException("Must include a non-null transportTypeCallback.");
        }
        if (executor == null) {
            throw new IllegalArgumentException("Must include a non-null Executor.");
        }

        IImsRcsController imsRcsController = getIImsRcsController();
        if (imsRcsController == null) {
            Log.w(TAG, "Get registration transport type error: IImsRcsController is null");
            throw new IllegalStateException("Cannot find remote IMS service");
        }

        try {
            imsRcsController.getImsRcsRegistrationTransportType(mSubId,
                    new IIntegerConsumer.Stub() {
                        @Override
                        public void accept(int result) {
                            executor.execute(() -> transportTypeCallback.accept(result));
                        }
                    });
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Add an {@link OnAvailabilityChangedListener} with the system, which will provide RCS
     * availability updates for the subscription specified.
     *
     * Use {@link SubscriptionManager.OnSubscriptionsChangedListener} to listen to
     * subscription changed events and call
     * {@link #removeOnAvailabilityChangedListener(OnAvailabilityChangedListener)} to clean up
     * after a subscription is removed.
     * <p>
     * When the listener is registered, it will initiate the callback listener to be called with
     * the current capabilities.
     *
     * @param executor The executor the callback events should be run on.
     * @param listener The RCS {@link OnAvailabilityChangedListener} to be registered.
     * @see #removeOnAvailabilityChangedListener(OnAvailabilityChangedListener)
     * @throws ImsException if the subscription associated with this instance of
     * {@link ImsRcsManager} is valid, but the ImsService associated with the subscription is not
     * available. This can happen if the ImsService has crashed, for example, or if the subscription
     * becomes inactive. See {@link ImsException#getCode()} for more information on the error codes.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public void addOnAvailabilityChangedListener(@NonNull @CallbackExecutor Executor executor,
            @NonNull OnAvailabilityChangedListener listener) throws ImsException {
        if (listener == null) {
            throw new IllegalArgumentException("Must include a non-null"
                    + "OnAvailabilityChangedListener.");
        }
        if (executor == null) {
            throw new IllegalArgumentException("Must include a non-null Executor.");
        }

        IImsRcsController imsRcsController = getIImsRcsController();
        if (imsRcsController == null) {
            Log.w(TAG, "Add availability changed listener: IImsRcsController is null");
            throw new ImsException("Cannot find remote IMS service",
                    ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }

        AvailabilityCallbackAdapter adapter =
                addAvailabilityChangedListenerToCollection(executor, listener);
        try {
            imsRcsController.registerRcsAvailabilityCallback(mSubId, adapter.getBinder());
        } catch (ServiceSpecificException e) {
            throw new ImsException(e.toString(), e.errorCode);
        } catch (RemoteException e) {
            Log.w(TAG, "Error calling IImsRcsController#registerRcsAvailabilityCallback", e);
            throw new ImsException("Remote IMS Service is not available",
                    ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
    }

     /**
     * Removes an existing RCS {@link OnAvailabilityChangedListener}.
     * <p>
     * When the subscription associated with this callback is removed (SIM removed, ESIM swap,
     * etc...), this callback will automatically be unregistered. If this method is called for an
     * inactive subscription, it will result in a no-op.
     * @param listener The RCS {@link OnAvailabilityChangedListener} to be removed.
     * @see #addOnAvailabilityChangedListener(Executor, OnAvailabilityChangedListener)
     * @throws ImsException if the IMS service is not available when calling this method.
     * See {@link ImsException#getCode()} for more information on the error codes.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public void removeOnAvailabilityChangedListener(
            @NonNull OnAvailabilityChangedListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("Must include a non-null"
                    + "OnAvailabilityChangedListener.");
        }

        IImsRcsController imsRcsController = getIImsRcsController();
        if (imsRcsController == null) {
            Log.w(TAG, "Remove availability changed listener: IImsRcsController is null");
            return;
        }

        AvailabilityCallbackAdapter callback =
                removeAvailabilityChangedListenerFromCollection(listener);
        if (callback == null) {
            return;
        }

        try {
            imsRcsController.unregisterRcsAvailabilityCallback(mSubId, callback.getBinder());
        } catch (RemoteException e) {
            Log.w(TAG, "Error calling IImsRcsController#unregisterRcsAvailabilityCallback", e);
        }
    }

    /**
     * Query for the capability of an IMS RCS service provided by the framework.
     * <p>
     * This only reports the status of RCS capabilities provided by the framework, not necessarily
     * RCS capabilities provided over-the-top by applications.
     *
     * @param capability The RCS capability to query.
     * @param radioTech The radio technology type that we are querying.
     * @return true if the RCS capability is capable for this subscription, false otherwise. This
     * does not necessarily mean that we are registered for IMS and the capability is available, but
     * rather the subscription is capable of this service over IMS.
     * @see #isAvailable(int, int)
     * @see android.telephony.CarrierConfigManager#KEY_USE_RCS_PRESENCE_BOOL
     * @see android.telephony.CarrierConfigManager.Ims#KEY_ENABLE_PRESENCE_CAPABILITY_EXCHANGE_BOOL
     * @throws ImsException if the IMS service is not available when calling this method.
     * See {@link ImsException#getCode()} for more information on the error codes.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public boolean isCapable(@RcsUceAdapter.RcsImsCapabilityFlag int capability,
            @ImsRegistrationImplBase.ImsRegistrationTech int radioTech) throws ImsException {
        IImsRcsController imsRcsController = getIImsRcsController();
        if (imsRcsController == null) {
            Log.w(TAG, "isCapable: IImsRcsController is null");
            throw new ImsException("Cannot find remote IMS service",
                    ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }

        try {
            return imsRcsController.isCapable(mSubId, capability, radioTech);
        } catch (RemoteException e) {
            Log.w(TAG, "Error calling IImsRcsController#isCapable", e);
            throw new ImsException("Remote IMS Service is not available",
                    ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
    }

    /**
     * Query the availability of an IMS RCS capability.
     * <p>
     * This only reports the status of RCS capabilities provided by the framework, not necessarily
     * RCS capabilities provided by over-the-top by applications.
     *
     * @param capability the RCS capability to query.
     * @param radioTech The radio technology type that we are querying.
     * @return true if the RCS capability is currently available for the associated subscription,
     * false otherwise. If the capability is available, IMS is registered and the service is
     * currently available over IMS.
     * @see #isCapable(int, int)
     * @throws ImsException if the IMS service is not available when calling this method.
     * See {@link ImsException#getCode()} for more information on the error codes.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public boolean isAvailable(@RcsUceAdapter.RcsImsCapabilityFlag int capability,
            @ImsRegistrationImplBase.ImsRegistrationTech int radioTech)
            throws ImsException {
        IImsRcsController imsRcsController = getIImsRcsController();
        if (imsRcsController == null) {
            Log.w(TAG, "isAvailable: IImsRcsController is null");
            throw new ImsException("Cannot find remote IMS service",
                    ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }

        try {
            return imsRcsController.isAvailable(mSubId, capability, radioTech);
        } catch (RemoteException e) {
            Log.w(TAG, "Error calling IImsRcsController#isAvailable", e);
            throw new ImsException("Remote IMS Service is not available",
                    ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
    }

    /**
     * Add the {@link OnAvailabilityChangedListener} to collection for tracking.
     * @param executor The executor that will be used when the publish state is changed and the
     * {@link OnAvailabilityChangedListener} is called.
     * @param listener The {@link OnAvailabilityChangedListener} to call the publish state changed.
     * @return The {@link AvailabilityCallbackAdapter} to wrapper the
     * {@link OnAvailabilityChangedListener}
     */
    private AvailabilityCallbackAdapter addAvailabilityChangedListenerToCollection(
            @NonNull Executor executor, @NonNull OnAvailabilityChangedListener listener) {
        AvailabilityCallbackAdapter adapter = new AvailabilityCallbackAdapter(executor, listener);
        synchronized (mAvailabilityChangedCallbacks) {
            mAvailabilityChangedCallbacks.put(listener, adapter);
        }
        return adapter;
    }

    /**
     * Remove the existing {@link OnAvailabilityChangedListener} from the collection.
     * @param listener The {@link OnAvailabilityChangedListener} to remove from the collection.
     * @return The wrapper class {@link AvailabilityCallbackAdapter} associated with the
     * {@link OnAvailabilityChangedListener}.
     */
    private AvailabilityCallbackAdapter removeAvailabilityChangedListenerFromCollection(
            @NonNull OnAvailabilityChangedListener listener) {
        synchronized (mAvailabilityChangedCallbacks) {
            return mAvailabilityChangedCallbacks.remove(listener);
        }
    }

    private IImsRcsController getIImsRcsController() {
        IBinder binder = TelephonyFrameworkInitializer
                .getTelephonyServiceManager()
                .getTelephonyImsServiceRegisterer()
                .get();
        return IImsRcsController.Stub.asInterface(binder);
    }
}
