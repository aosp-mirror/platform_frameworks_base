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
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.WorkerThread;
import android.content.Context;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.telephony.ims.aidl.IImsConfigCallback;
import android.telephony.ims.feature.MmTelFeature;
import android.telephony.ims.stub.ImsConfigImplBase;
import android.telephony.ims.stub.ImsRegistrationImplBase;

import com.android.internal.telephony.ITelephony;

import java.util.concurrent.Executor;

/**
 * Manages IMS provisioning and configuration parameters, as well as callbacks for apps to listen
 * to changes in these configurations.
 *
 * Note: IMS provisioning keys are defined per carrier or OEM using OMA-DM or other provisioning
 * applications and may vary. For compatibility purposes, the first 100 integer values used in
 * {@link #setProvisioningIntValue(int, int)} have been reserved for existing provisioning keys
 * previously defined in the Android framework. Some common constants have been defined in this
 * class to make integrating with other system apps easier. USE WITH CARE!
 *
 * To avoid collisions, please use String based configurations when possible:
 * {@link #setProvisioningStringValue(int, String)} and {@link #getProvisioningStringValue(int)}.
 * @hide
 */
@SystemApi
public class ProvisioningManager {

    /**
     * The query from {@link #getProvisioningStringValue(int)} has resulted in an unspecified error.
     */
    public static final String STRING_QUERY_RESULT_ERROR_GENERIC =
            "STRING_QUERY_RESULT_ERROR_GENERIC";

    /**
     * The query from {@link #getProvisioningStringValue(int)} has resulted in an error because the
     * ImsService implementation was not ready for provisioning queries.
     */
    public static final String STRING_QUERY_RESULT_ERROR_NOT_READY =
            "STRING_QUERY_RESULT_ERROR_NOT_READY";

    /**
     * The integer result of provisioning for the queried key is disabled.
     */
    public static final int PROVISIONING_VALUE_DISABLED = 0;

    /**
     * The integer result of provisioning for the queried key is enabled.
     */
    public static final int PROVISIONING_VALUE_ENABLED = 1;


    /**
     * Override the user-defined WiFi Roaming enabled setting for this subscription, defined in
     * {@link SubscriptionManager#WFC_ROAMING_ENABLED_CONTENT_URI}, for the purposes of provisioning
     * the subscription for WiFi Calling.
     *
     * @see #getProvisioningIntValue(int)
     * @see #setProvisioningIntValue(int, int)
     */
    public static final int KEY_VOICE_OVER_WIFI_ROAMING_ENABLED_OVERRIDE = 26;

    /**
     * Override the user-defined WiFi mode for this subscription, defined in
     * {@link SubscriptionManager#WFC_MODE_CONTENT_URI}, for the purposes of provisioning
     * this subscription for WiFi Calling.
     *
     * Valid values for this key are:
     * {@link ImsMmTelManager#WIFI_MODE_WIFI_ONLY},
     * {@link ImsMmTelManager#WIFI_MODE_CELLULAR_PREFERRED}, or
     * {@link ImsMmTelManager#WIFI_MODE_WIFI_PREFERRED}.
     *
     * @see #getProvisioningIntValue(int)
     * @see #setProvisioningIntValue(int, int)
     */
    public static final int KEY_VOICE_OVER_WIFI_MODE_OVERRIDE = 27;

    /**
     * Callback for IMS provisioning changes.
     */
    public static class Callback {

        private static class CallbackBinder extends IImsConfigCallback.Stub {

            private final Callback mLocalConfigurationCallback;
            private Executor mExecutor;

            private CallbackBinder(Callback localConfigurationCallback) {
                mLocalConfigurationCallback = localConfigurationCallback;
            }

            @Override
            public final void onIntConfigChanged(int item, int value) {
                Binder.withCleanCallingIdentity(() ->
                        mExecutor.execute(() ->
                                mLocalConfigurationCallback.onProvisioningIntChanged(item, value)));
            }

            @Override
            public final void onStringConfigChanged(int item, String value) {
                Binder.withCleanCallingIdentity(() ->
                        mExecutor.execute(() ->
                                mLocalConfigurationCallback.onProvisioningStringChanged(item,
                                        value)));
            }

            private void setExecutor(Executor executor) {
                mExecutor = executor;
            }
        }

        private final CallbackBinder mBinder = new CallbackBinder(this);

        /**
         * Called when a provisioning item has changed.
         * @param item the IMS provisioning key constant, as defined by the OEM.
         * @param value the new integer value of the IMS provisioning key.
         */
        public void onProvisioningIntChanged(int item, int value) {
            // Base Implementation
        }

        /**
         * Called when a provisioning item has changed.
         * @param item the IMS provisioning key constant, as defined by the OEM.
         * @param value the new String value of the IMS configuration constant.
         */
        public void onProvisioningStringChanged(int item, @NonNull String value) {
            // Base Implementation
        }

        /**@hide*/
        public final IImsConfigCallback getBinder() {
            return mBinder;
        }

        /**@hide*/
        public void setExecutor(Executor executor) {
            mBinder.setExecutor(executor);
        }
    }

    private int mSubId;

    /**
     * Create a new {@link ProvisioningManager} for the subscription specified.
     *
     * @param subId The ID of the subscription that this ProvisioningManager will use.
     * @see android.telephony.SubscriptionManager#getActiveSubscriptionInfoList()
     * @throws IllegalArgumentException if the subscription is invalid.
     */
    public static @NonNull ProvisioningManager createForSubscriptionId(int subId) {
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            throw new IllegalArgumentException("Invalid subscription ID");
        }

        return new ProvisioningManager(subId);
    }

    private ProvisioningManager(int subId) {
        mSubId = subId;
    }

    /**
     * Register a new {@link Callback} to listen to changes to changes in IMS provisioning.
     *
     * When the subscription associated with this callback is removed (SIM removed, ESIM swap,
     * etc...), this callback will automatically be removed.
     * @param executor The {@link Executor} to call the callback methods on
     * @param callback The provisioning callbackto be registered.
     * @see #unregisterProvisioningChangedCallback(Callback)
     * @see SubscriptionManager.OnSubscriptionsChangedListener
     * @throws IllegalArgumentException if the subscription associated with this callback is not
     * active (SIM is not inserted, ESIM inactive) or the subscription is invalid.
     * @throws ImsException if the subscription associated with this callback is valid, but
     * the {@link ImsService} associated with the subscription is not available. This can happen if
     * the service crashed, for example. See {@link ImsException#getCode()} for a more detailed
     * reason.
     */
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public void registerProvisioningChangedCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull Callback callback) throws ImsException {
        if (!isImsAvailableOnDevice()) {
            throw new ImsException("IMS not available on device.",
                    ImsException.CODE_ERROR_UNSUPPORTED_OPERATION);
        }
        callback.setExecutor(executor);
        try {
            getITelephony().registerImsProvisioningChangedCallback(mSubId, callback.getBinder());
        } catch (RemoteException | IllegalStateException e) {
            throw new ImsException(e.getMessage(), ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
    }

    /**
     * Unregister an existing {@link Callback}. When the subscription associated with this
     * callback is removed (SIM removed, ESIM swap, etc...), this callback will automatically be
     * removed. If this method is called for an inactive subscription, it will result in a no-op.
     * @param callback The existing {@link Callback} to be removed.
     * @see #registerProvisioningChangedCallback(Executor, Callback)
     *
     * @throws IllegalArgumentException if the subscription associated with this callback is
     * invalid.
     */
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public void unregisterProvisioningChangedCallback(@NonNull Callback callback) {
        try {
            getITelephony().unregisterImsProvisioningChangedCallback(mSubId, callback.getBinder());
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Query for the integer value associated with the provided key.
     *
     * This operation is blocking and should not be performed on the UI thread.
     *
     * @param key An integer that represents the provisioning key, which is defined by the OEM.
     * @return an integer value for the provided key, or
     * {@link ImsConfigImplBase#CONFIG_RESULT_UNKNOWN} if the key doesn't exist.
     * @throws IllegalArgumentException if the key provided was invalid.
     */
    @WorkerThread
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public int getProvisioningIntValue(int key) {
        try {
            return getITelephony().getImsProvisioningInt(mSubId, key);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Query for the String value associated with the provided key.
     *
     * This operation is blocking and should not be performed on the UI thread.
     *
     * @param key A String that represents the provisioning key, which is defined by the OEM.
     * @return a String value for the provided key, {@code null} if the key doesn't exist, or one
     * of the following error codes: {@link #STRING_QUERY_RESULT_ERROR_GENERIC},
     * {@link #STRING_QUERY_RESULT_ERROR_NOT_READY}.
     * @throws IllegalArgumentException if the key provided was invalid.
     */
    @WorkerThread
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public @Nullable String getProvisioningStringValue(int key) {
        try {
            return getITelephony().getImsProvisioningString(mSubId, key);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Set the integer value associated with the provided key.
     *
     * This operation is blocking and should not be performed on the UI thread.
     *
     * Use {@link #setProvisioningStringValue(int, String)} with proper namespacing (to be defined
     * per OEM or carrier) when possible instead to avoid key collision if needed.
     * @param key An integer that represents the provisioning key, which is defined by the OEM.
     * @param value a integer value for the provided key.
     * @return the result of setting the configuration value.
     */
    @WorkerThread
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public @ImsConfigImplBase.SetConfigResult int setProvisioningIntValue(int key, int value) {
        try {
            return getITelephony().setImsProvisioningInt(mSubId, key, value);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Set the String value associated with the provided key.
     *
     * This operation is blocking and should not be performed on the UI thread.
     *
     * @param key A String that represents the provisioning key, which is defined by the OEM and
     *     should be appropriately namespaced to avoid collision.
     * @param value a String value for the provided key.
     * @return the result of setting the configuration value.
     */
    @WorkerThread
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public @ImsConfigImplBase.SetConfigResult int setProvisioningStringValue(int key,
            @NonNull String value) {
        try {
            return getITelephony().setImsProvisioningString(mSubId, key, value);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Set the provisioning status for the IMS MmTel capability using the specified subscription.
     *
     * Provisioning may or may not be required, depending on the carrier configuration. If
     * provisioning is not required for the carrier associated with this subscription or the device
     * does not support the capability/technology combination specified, this operation will be a
     * no-op.
     *
     * @see CarrierConfigManager#KEY_CARRIER_UT_PROVISIONING_REQUIRED_BOOL
     * @see CarrierConfigManager#KEY_CARRIER_VOLTE_PROVISIONING_REQUIRED_BOOL
     * @param isProvisioned true if the device is provisioned for UT over IMS, false otherwise.
     */
    @WorkerThread
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public void setProvisioningStatusForCapability(
            @MmTelFeature.MmTelCapabilities.MmTelCapability int capability,
            @ImsRegistrationImplBase.ImsRegistrationTech int tech,  boolean isProvisioned) {
        try {
            getITelephony().setImsProvisioningStatusForCapability(mSubId, capability, tech,
                    isProvisioned);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Get the provisioning status for the IMS MmTel capability specified.
     *
     * If provisioning is not required for the queried
     * {@link MmTelFeature.MmTelCapabilities.MmTelCapability} and
     * {@link ImsRegistrationImplBase.ImsRegistrationTech} combination specified, this method will
     * always return {@code true}.
     *
     * @see CarrierConfigManager#KEY_CARRIER_UT_PROVISIONING_REQUIRED_BOOL
     * @see CarrierConfigManager#KEY_CARRIER_VOLTE_PROVISIONING_REQUIRED_BOOL
     * @return true if the device is provisioned for the capability or does not require
     * provisioning, false if the capability does require provisioning and has not been
     * provisioned yet.
     */
    @WorkerThread
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public boolean getProvisioningStatusForCapability(
            @MmTelFeature.MmTelCapabilities.MmTelCapability int capability,
            @ImsRegistrationImplBase.ImsRegistrationTech int tech) {
        try {
            return getITelephony().getImsProvisioningStatusForCapability(mSubId, capability, tech);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    private static boolean isImsAvailableOnDevice() {
        IPackageManager pm = IPackageManager.Stub.asInterface(ServiceManager.getService("package"));
        if (pm == null) {
            // For some reason package manger is not available.. This will fail internally anyways,
            // so do not throw error and allow.
            return true;
        }
        try {
            return pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_IMS, 0);
        } catch (RemoteException e) {
            // For some reason package manger is not available.. This will fail internally anyways,
            // so do not throw error and allow.
        }
        return true;
    }

    private static ITelephony getITelephony() {
        ITelephony binder = ITelephony.Stub.asInterface(
                ServiceManager.getService(Context.TELEPHONY_SERVICE));
        if (binder == null) {
            throw new RuntimeException("Could not find Telephony Service.");
        }
        return binder;
    }
}
