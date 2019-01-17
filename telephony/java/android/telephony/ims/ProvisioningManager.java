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
import android.annotation.SystemApi;
import android.content.Context;
import android.os.Binder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.telephony.SubscriptionManager;
import android.telephony.ims.aidl.IImsConfigCallback;
import android.telephony.ims.stub.ImsConfigImplBase;

import com.android.internal.telephony.ITelephony;

import java.util.concurrent.Executor;

/**
 * Manages IMS provisioning and configuration parameters, as well as callbacks for apps to listen
 * to changes in these configurations.
 *
 * Note: IMS provisioning keys are defined per carrier or OEM using OMA-DM or other provisioning
 * applications and may vary.
 * @hide
 */
@SystemApi
public class ProvisioningManager {

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
        public void onProvisioningStringChanged(int item, String value) {
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
     * @param context The context that this manager will use.
     * @param subId The ID of the subscription that this ProvisioningManager will use.
     * @see android.telephony.SubscriptionManager#getActiveSubscriptionInfoList()
     * @throws IllegalArgumentException if the subscription is invalid or
     *         the subscription ID is not an active subscription.
     */
    public static ProvisioningManager createForSubscriptionId(Context context, int subId) {
        if (!SubscriptionManager.isValidSubscriptionId(subId)
                || !getSubscriptionManager(context).isActiveSubscriptionId(subId)) {
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
     * @throws IllegalStateException if the subscription associated with this callback is valid, but
     * the {@link ImsService} associated with the subscription is not available. This can happen if
     * the service crashed, for example.
     */
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public void registerProvisioningChangedCallback(@CallbackExecutor Executor executor,
            @NonNull Callback callback) {
        callback.setExecutor(executor);
        try {
            getITelephony().registerImsProvisioningChangedCallback(mSubId, callback.getBinder());
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
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
            getITelephony().unregisterImsProvisioningChangedCallback(mSubId,
                    callback.getBinder());
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Query for the integer value associated with the provided key.
     * @param key An integer that represents the provisioning key, which is defined by the OEM.
     * @return an integer value for the provided key.
     * @throws IllegalArgumentException if the key provided was invalid.
     */
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
     * @param key An integer that represents the provisioning key, which is defined by the OEM.
     * @return a String value for the provided key, or {@code null} if the key doesn't exist.
     * @throws IllegalArgumentException if the key provided was invalid.
     */
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public String getProvisioningStringValue(int key) {
        try {
            return getITelephony().getImsProvisioningString(mSubId, key);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Set the integer value associated with the provided key.
     * @param key An integer that represents the provisioning key, which is defined by the OEM.
     * @param value a integer value for the provided key.
     * @return the result of setting the configuration value.
     */
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
     * @param key An integer that represents the provisioning key, which is defined by the OEM.
     * @param value a String value for the provided key.
     * @return the result of setting the configuration value.
     */
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public @ImsConfigImplBase.SetConfigResult int setProvisioningStringValue(int key,
            String value) {
        try {
            return getITelephony().setImsProvisioningString(mSubId, key, value);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    private static SubscriptionManager getSubscriptionManager(Context context) {
        SubscriptionManager manager = context.getSystemService(SubscriptionManager.class);
        if (manager == null) {
            throw new RuntimeException("Could not find SubscriptionManager.");
        }
        return manager;
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
