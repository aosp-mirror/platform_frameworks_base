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
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.telephony.AccessNetworkConstants;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.telephony.ims.aidl.IImsCapabilityCallback;
import android.telephony.ims.aidl.IImsRcsController;
import android.telephony.ims.feature.ImsFeature;
import android.telephony.ims.feature.RcsFeature;
import android.telephony.ims.stub.ImsRegistrationImplBase;
import android.util.Log;

import com.android.internal.telephony.IIntegerConsumer;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Manager for interfacing with the framework RCS services, including the User Capability Exchange
 * (UCE) service, as well as managing user settings.
 *
 * Use {@link ImsManager#getImsRcsManager(int)} to create an instance of this manager.
 */
public class ImsRcsManager implements RegistrationManager {
    private static final String TAG = "ImsRcsManager";

    /**
     * Activity Action: Show the opt-in dialog for enabling or disabling RCS contact discovery
     * using User Capability Exchange (UCE).
     * <p>
     * An application that depends on contact discovery being enabled may send this intent
     * using {@link Context#startActivity(Intent)} to ask the user to opt-in for contacts upload for
     * capability exchange if it is currently disabled. Whether or not this setting has been enabled
     * can be queried using {@link RcsUceAdapter#isUceSettingEnabled()}.
     * <p>
     * This intent should only be sent if the carrier supports RCS capability exchange, which can be
     * queried using the key {@link CarrierConfigManager#KEY_USE_RCS_PRESENCE_BOOL}. Otherwise, the
     * setting will not be present.
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
     * Receives RCS availability status updates from the ImsService.
     *
     * @see #isAvailable(int)
     * @see #registerRcsAvailabilityCallback(Executor, AvailabilityCallback)
     * @see #unregisterRcsAvailabilityCallback(AvailabilityCallback)
     * @hide
     */
    public static class AvailabilityCallback {

        private static class CapabilityBinder extends IImsCapabilityCallback.Stub {

            private final AvailabilityCallback mLocalCallback;
            private Executor mExecutor;

            CapabilityBinder(AvailabilityCallback c) {
                mLocalCallback = c;
            }

            @Override
            public void onCapabilitiesStatusChanged(int config) {
                if (mLocalCallback == null) return;

                long callingIdentity = Binder.clearCallingIdentity();
                try {
                    mExecutor.execute(() -> mLocalCallback.onAvailabilityChanged(
                            new RcsFeature.RcsImsCapabilities(config)));
                } finally {
                    restoreCallingIdentity(callingIdentity);
                }
            }

            @Override
            public void onQueryCapabilityConfiguration(int capability, int radioTech,
                    boolean isEnabled) {
                // This is not used for public interfaces.
            }

            @Override
            public void onChangeCapabilityConfigurationError(int capability, int radioTech,
                    @ImsFeature.ImsCapabilityError int reason) {
                // This is not used for public interfaces
            }

            private void setExecutor(Executor executor) {
                mExecutor = executor;
            }
        }

        private final CapabilityBinder mBinder = new CapabilityBinder(this);

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
        public void onAvailabilityChanged(@NonNull RcsFeature.RcsImsCapabilities capabilities) {
        }

        /**@hide*/
        public final IImsCapabilityCallback getBinder() {
            return mBinder;
        }

        private void setExecutor(Executor executor) {
            mBinder.setExecutor(executor);
        }
    }

    private final int mSubId;
    private final Context mContext;

    /**
     * Use {@link ImsManager#getImsRcsManager(int)} to create an instance of this class.
     * @hide
     */
    public ImsRcsManager(Context context, int subId) {
        mSubId = subId;
        mContext = context;
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
     * @hide
     */
    @Override
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public void registerImsRegistrationCallback(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull RegistrationCallback c)
            throws ImsException {
        if (c == null) {
            throw new IllegalArgumentException("Must include a non-null RegistrationCallback.");
        }
        if (executor == null) {
            throw new IllegalArgumentException("Must include a non-null Executor.");
        }

        IImsRcsController imsRcsController = getIImsRcsController();
        if (imsRcsController == null) {
            Log.e(TAG, "Register registration callback: IImsRcsController is null");
            throw new ImsException("Cannot find remote IMS service",
                    ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }

        c.setExecutor(executor);
        try {
            imsRcsController.registerImsRegistrationCallback(mSubId, c.getBinder());
        } catch (RemoteException | IllegalStateException e) {
            throw new ImsException(e.getMessage(), ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
    }

    /**
     * @hide
     */
    @Override
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public void unregisterImsRegistrationCallback(
            @NonNull RegistrationManager.RegistrationCallback c) {
        if (c == null) {
            throw new IllegalArgumentException("Must include a non-null RegistrationCallback.");
        }

        IImsRcsController imsRcsController = getIImsRcsController();
        if (imsRcsController == null) {
            Log.e(TAG, "Unregister registration callback: IImsRcsController is null");
            throw new IllegalStateException("Cannot find remote IMS service");
        }

        try {
            imsRcsController.unregisterImsRegistrationCallback(mSubId, c.getBinder());
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * @hide
     */
    @Override
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public void getRegistrationState(@NonNull @CallbackExecutor Executor executor,
            @NonNull @ImsRegistrationState Consumer<Integer> stateCallback) {
        if (stateCallback == null) {
            throw new IllegalArgumentException("Must include a non-null stateCallback.");
        }
        if (executor == null) {
            throw new IllegalArgumentException("Must include a non-null Executor.");
        }

        IImsRcsController imsRcsController = getIImsRcsController();
        if (imsRcsController == null) {
            Log.e(TAG, "Get registration state error: IImsRcsController is null");
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
     * @hide
     */
    @Override
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
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
            Log.e(TAG, "Get registration transport type error: IImsRcsController is null");
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
     * Registers an {@link AvailabilityCallback} with the system, which will provide RCS
     * availability updates for the subscription specified.
     *
     * Use {@link SubscriptionManager.OnSubscriptionsChangedListener} to listen to
     * subscription changed events and call
     * {@link #unregisterRcsAvailabilityCallback(AvailabilityCallback)} to clean up after a
     * subscription is removed.
     * <p>
     * When the callback is registered, it will initiate the callback c to be called with the
     * current capabilities.
     *
     * @param executor The executor the callback events should be run on.
     * @param c The RCS {@link AvailabilityCallback} to be registered.
     * @see #unregisterRcsAvailabilityCallback(AvailabilityCallback)
     * @throws ImsException if the subscription associated with this instance of
     * {@link ImsRcsManager} is valid, but the ImsService associated with the subscription is not
     * available. This can happen if the ImsService has crashed, for example, or if the subscription
     * becomes inactive. See {@link ImsException#getCode()} for more information on the error codes.
     * @hide
     */
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public void registerRcsAvailabilityCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull AvailabilityCallback c) throws ImsException {
        if (c == null) {
            throw new IllegalArgumentException("Must include a non-null AvailabilityCallback.");
        }
        if (executor == null) {
            throw new IllegalArgumentException("Must include a non-null Executor.");
        }

        IImsRcsController imsRcsController = getIImsRcsController();
        if (imsRcsController == null) {
            Log.e(TAG, "Register availability callback: IImsRcsController is null");
            throw new ImsException("Cannot find remote IMS service",
                    ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }

        c.setExecutor(executor);
        try {
            imsRcsController.registerRcsAvailabilityCallback(mSubId, c.getBinder());
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling IImsRcsController#registerRcsAvailabilityCallback", e);
            throw new ImsException("Remote IMS Service is not available",
                    ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
    }

    /**
     * Removes an existing RCS {@link AvailabilityCallback}.
     * <p>
     * When the subscription associated with this callback is removed (SIM removed, ESIM swap,
     * etc...), this callback will automatically be unregistered. If this method is called for an
     * inactive subscription, it will result in a no-op.
     * @param c The RCS {@link AvailabilityCallback} to be removed.
     * @see #registerRcsAvailabilityCallback(Executor, AvailabilityCallback)
     * @throws ImsException if the IMS service is not available when calling this method
     * {@link ImsRcsController#unregisterRcsAvailabilityCallback()}.
     * See {@link ImsException#getCode()} for more information on the error codes.
     * @hide
     */
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public void unregisterRcsAvailabilityCallback(@NonNull AvailabilityCallback c)
            throws ImsException {
        if (c == null) {
            throw new IllegalArgumentException("Must include a non-null AvailabilityCallback.");
        }

        IImsRcsController imsRcsController = getIImsRcsController();
        if (imsRcsController == null) {
            Log.e(TAG, "Unregister availability callback: IImsRcsController is null");
            throw new ImsException("Cannot find remote IMS service",
                    ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }

        try {
            imsRcsController.unregisterRcsAvailabilityCallback(mSubId, c.getBinder());
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling IImsRcsController#unregisterRcsAvailabilityCallback", e);
            throw new ImsException("Remote IMS Service is not available",
                    ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
    }

    /**
     * Query for the capability of an IMS RCS service provided by the framework.
     * <p>
     * This only reports the status of RCS capabilities provided by the framework, not necessarily
     * RCS capabilities provided over-the-top by applications.
     *
     * @param capability The RCS capability to query.
     * @param radioTech The radio tech that this capability failed for, defined as
     * {@link ImsRegistrationImplBase#REGISTRATION_TECH_LTE} or
     * {@link ImsRegistrationImplBase#REGISTRATION_TECH_IWLAN}.
     * @return true if the RCS capability is capable for this subscription, false otherwise. This
     * does not necessarily mean that we are registered for IMS and the capability is available, but
     * rather the subscription is capable of this service over IMS.
     * @see #isAvailable(int)
     * @see android.telephony.CarrierConfigManager#KEY_USE_RCS_PRESENCE_BOOL
     * @throws ImsException if the IMS service is not available when calling this method
     * {@link ImsRcsController#isCapable(int, int)}.
     * See {@link ImsException#getCode()} for more information on the error codes.
     * @hide
     */
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public boolean isCapable(@RcsFeature.RcsImsCapabilities.RcsImsCapabilityFlag int capability,
            @ImsRegistrationImplBase.ImsRegistrationTech int radioTech) throws ImsException {
        IImsRcsController imsRcsController = getIImsRcsController();
        if (imsRcsController == null) {
            Log.e(TAG, "isCapable: IImsRcsController is null");
            throw new ImsException("Cannot find remote IMS service",
                    ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }

        try {
            return imsRcsController.isCapable(mSubId, capability, radioTech);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling IImsRcsController#isCapable", e);
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
     * @return true if the RCS capability is currently available for the associated subscription,
     * false otherwise. If the capability is available, IMS is registered and the service is
     * currently available over IMS.
     * @see #isCapable(int)
     * @throws ImsException if the IMS service is not available when calling this method
     * {@link ImsRcsController#isAvailable(int, int)}.
     * See {@link ImsException#getCode()} for more information on the error codes.
     * @hide
     */
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public boolean isAvailable(@RcsFeature.RcsImsCapabilities.RcsImsCapabilityFlag int capability)
            throws ImsException {
        IImsRcsController imsRcsController = getIImsRcsController();
        if (imsRcsController == null) {
            Log.e(TAG, "isAvailable: IImsRcsController is null");
            throw new ImsException("Cannot find remote IMS service",
                    ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }

        try {
            return imsRcsController.isAvailable(mSubId, capability);
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling IImsRcsController#isAvailable", e);
            throw new ImsException("Remote IMS Service is not available",
                    ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
    }

    private IImsRcsController getIImsRcsController() {
        IBinder binder = ServiceManager.getService(Context.TELEPHONY_IMS_SERVICE);
        return IImsRcsController.Stub.asInterface(binder);
    }
}
