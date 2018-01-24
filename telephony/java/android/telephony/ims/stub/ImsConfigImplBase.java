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
 * limitations under the License
 */

package android.telephony.ims.stub;

import android.annotation.SystemApi;
import android.content.Context;
import android.content.Intent;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.telephony.ims.aidl.IImsConfig;
import android.telephony.ims.aidl.IImsConfigCallback;
import android.util.Log;

import com.android.ims.ImsConfig;
import com.android.internal.annotations.VisibleForTesting;

import java.lang.ref.WeakReference;
import java.util.HashMap;

/**
 * Controls the modification of IMS specific configurations. For more information on the supported
 * IMS configuration constants, see {@link ImsConfig}.
 *
 * The inner class {@link ImsConfigStub} implements methods of IImsConfig AIDL interface.
 * The IImsConfig AIDL interface is called by ImsConfig, which may exist in many other processes.
 * ImsConfigImpl access to the configuration parameters may be arbitrarily slow, especially in
 * during initialization, or times when a lot of configuration parameters are being set/get
 * (such as during boot up or SIM card change). By providing a cache in ImsConfigStub, we can speed
 * up access to these configuration parameters, so a query to the ImsConfigImpl does not have to be
 * performed every time.
 * @hide
 */
@SystemApi
public class ImsConfigImplBase {

    private static final String TAG = "ImsConfigImplBase";

    /**
     * Implements the IImsConfig AIDL interface, which is called by potentially many processes
     * in order to get/set configuration parameters.
     *
     * It holds an object of ImsConfigImplBase class which is usually extended by ImsConfigImpl
     * with actual implementations from vendors. This class caches provisioned values from
     * ImsConfigImpl layer because queries through ImsConfigImpl can be slow. When query goes in,
     * it first checks cache layer. If missed, it will call the vendor implementation of
     * ImsConfigImplBase API.
     * and cache the return value if the set succeeds.
     *
     * Provides APIs to get/set the IMS service feature/capability/parameters.
     * The config items include:
     * 1) Items provisioned by the operator.
     * 2) Items configured by user. Mainly service feature class.
     *
     * @hide
     */
    @VisibleForTesting
    static public class ImsConfigStub extends IImsConfig.Stub {
        WeakReference<ImsConfigImplBase> mImsConfigImplBaseWeakReference;
        private HashMap<Integer, Integer> mProvisionedIntValue = new HashMap<>();
        private HashMap<Integer, String> mProvisionedStringValue = new HashMap<>();

        @VisibleForTesting
        public ImsConfigStub(ImsConfigImplBase imsConfigImplBase) {
            mImsConfigImplBaseWeakReference =
                    new WeakReference<ImsConfigImplBase>(imsConfigImplBase);
        }

        @Override
        public void addImsConfigCallback(IImsConfigCallback c) throws RemoteException {
            getImsConfigImpl().addImsConfigCallback(c);
        }

        @Override
        public void removeImsConfigCallback(IImsConfigCallback c) throws RemoteException {
            getImsConfigImpl().removeImsConfigCallback(c);
        }

        /**
         * Gets the value for ims service/capabilities parameters. It first checks its local cache,
         * if missed, it will call ImsConfigImplBase.getConfigInt.
         * Synchronous blocking call.
         *
         * @param item integer key
         * @return value in Integer format or {@link #CONFIG_RESULT_UNKNOWN} if
         * unavailable.
         */
        @Override
        public synchronized int getConfigInt(int item) throws RemoteException {
            if (mProvisionedIntValue.containsKey(item)) {
                return mProvisionedIntValue.get(item);
            } else {
                int retVal = getImsConfigImpl().getConfigInt(item);
                if (retVal != ImsConfig.OperationStatusConstants.UNKNOWN) {
                    updateCachedValue(item, retVal, false);
                }
                return retVal;
            }
        }

        /**
         * Gets the value for ims service/capabilities parameters. It first checks its local cache,
         * if missed, it will call #ImsConfigImplBase.getConfigString.
         * Synchronous blocking call.
         *
         * @param item integer key
         * @return value in String format.
         */
        @Override
        public synchronized String getConfigString(int item) throws RemoteException {
            if (mProvisionedIntValue.containsKey(item)) {
                return mProvisionedStringValue.get(item);
            } else {
                String retVal = getImsConfigImpl().getConfigString(item);
                if (retVal != null) {
                    updateCachedValue(item, retVal, false);
                }
                return retVal;
            }
        }

        /**
         * Sets the value for IMS service/capabilities parameters by the operator device
         * management entity. It sets the config item value in the provisioned storage
         * from which the master value is derived, and write it into local cache.
         * Synchronous blocking call.
         *
         * @param item integer key
         * @param value in Integer format.
         * @return the result of setting the configuration value, defined as either
         * {@link #CONFIG_RESULT_FAILED} or {@link #CONFIG_RESULT_SUCCESS}.
         */
        @Override
        public synchronized int setConfigInt(int item, int value) throws RemoteException {
            mProvisionedIntValue.remove(item);
            int retVal = getImsConfigImpl().setConfig(item, value);
            if (retVal == ImsConfig.OperationStatusConstants.SUCCESS) {
                updateCachedValue(item, value, true);
            } else {
                Log.d(TAG, "Set provision value of " + item +
                        " to " + value + " failed with error code " + retVal);
            }

            return retVal;
        }

        /**
         * Sets the value for IMS service/capabilities parameters by the operator device
         * management entity. It sets the config item value in the provisioned storage
         * from which the master value is derived, and write it into local cache.
         * Synchronous blocking call.
         *
         * @param item as defined in com.android.ims.ImsConfig#ConfigConstants.
         * @param value in String format.
         * @return the result of setting the configuration value, defined as either
         * {@link #CONFIG_RESULT_FAILED} or {@link #CONFIG_RESULT_SUCCESS}.
         */
        @Override
        public synchronized int setConfigString(int item, String value)
                throws RemoteException {
            mProvisionedStringValue.remove(item);
            int retVal = getImsConfigImpl().setConfig(item, value);
            if (retVal == ImsConfig.OperationStatusConstants.SUCCESS) {
                updateCachedValue(item, value, true);
            }

            return retVal;
        }

        private ImsConfigImplBase getImsConfigImpl() throws RemoteException {
            ImsConfigImplBase ref = mImsConfigImplBaseWeakReference.get();
            if (ref == null) {
                throw new RemoteException("Fail to get ImsConfigImpl");
            } else {
                return ref;
            }
        }

        private void notifyImsConfigChanged(int item, int value) throws RemoteException {
            getImsConfigImpl().notifyConfigChanged(item, value);
        }

        private void notifyImsConfigChanged(int item, String value) throws RemoteException {
            getImsConfigImpl().notifyConfigChanged(item, value);
        }

        protected synchronized void updateCachedValue(int item, int value, boolean notifyChange)
        throws RemoteException {
            mProvisionedIntValue.put(item, value);
            if (notifyChange) {
                notifyImsConfigChanged(item, value);
            }
        }

        protected synchronized void updateCachedValue(int item, String value,
                boolean notifyChange) throws RemoteException {
            mProvisionedStringValue.put(item, value);
            if (notifyChange) {
                notifyImsConfigChanged(item, value);
            }
        }
    }

    /**
     * Callback that the framework uses for receiving Configuration change updates.
     * {@hide}
     */
    public static class Callback extends IImsConfigCallback.Stub {

        @Override
        public final void onIntConfigChanged(int item, int value) throws RemoteException {
            onConfigChanged(item, value);
        }

        @Override
        public final void onStringConfigChanged(int item, String value) throws RemoteException {
            onConfigChanged(item, value);
        }

        /**
         * Called when the IMS configuration has changed.
         * @param item the IMS configuration key constant, as defined in ImsConfig.
         * @param value the new integer value of the IMS configuration constant.
         */
        public void onConfigChanged(int item, int value) {
            // Base Implementation
        }

        /**
         * Called when the IMS configuration has changed.
         * @param item the IMS configuration key constant, as defined in ImsConfig.
         * @param value the new String value of the IMS configuration constant.
         */
        public void onConfigChanged(int item, String value) {
            // Base Implementation
        }
    }

    /**
     * The configuration requested resulted in an unknown result. This may happen if the
     * IMS configurations are unavailable.
     */
    public static final int CONFIG_RESULT_UNKNOWN = -1;
    /**
     * Setting the configuration value completed.
     */
    public static final int CONFIG_RESULT_SUCCESS = 0;
    /**
     * Setting the configuration value failed.
     */
    public static final int CONFIG_RESULT_FAILED =  1;

    private final RemoteCallbackList<IImsConfigCallback> mCallbacks = new RemoteCallbackList<>();
    ImsConfigStub mImsConfigStub;

    /**
     * Used for compatibility between older versions of the ImsService.
     * @hide
     */
    public ImsConfigImplBase(Context context) {
        mImsConfigStub = new ImsConfigStub(this);
    }

    public ImsConfigImplBase() {
        mImsConfigStub = new ImsConfigStub(this);
    }

    /**
     * Adds a {@link Callback} to the list of callbacks notified when a value in the configuration
     * changes.
     * @param c callback to add.
     */
    private void addImsConfigCallback(IImsConfigCallback c) {
        mCallbacks.register(c);
    }
    /**
     * Removes a {@link Callback} to the list of callbacks notified when a value in the
     * configuration changes.
     *
     * @param c callback to remove.
     */
    private void removeImsConfigCallback(IImsConfigCallback c) {
        mCallbacks.unregister(c);
    }

    /**
     * @param item
     * @param value
     */
    private final void notifyConfigChanged(int item, int value) {
        // can be null in testing
        if (mCallbacks == null) {
            return;
        }
        mCallbacks.broadcast(c -> {
            try {
                c.onIntConfigChanged(item, value);
            } catch (RemoteException e) {
                Log.w(TAG, "notifyConfigChanged(int): dead binder in notify, skipping.");
            }
        });
    }

    private void notifyConfigChanged(int item, String value) {
        // can be null in testing
        if (mCallbacks == null) {
            return;
        }
        mCallbacks.broadcast(c -> {
            try {
                c.onStringConfigChanged(item, value);
            } catch (RemoteException e) {
                Log.w(TAG, "notifyConfigChanged(string): dead binder in notify, skipping.");
            }
        });
    }

    /**
     * @hide
     */
    public IImsConfig getIImsConfig() { return mImsConfigStub; }

    /**
     * Updates provisioning value and notifies the framework of the change.
     * Doesn't call {@link #setConfig(int,int)} and assumes the result succeeded.
     * This should only be used when the IMS implementer implicitly changed provisioned values.
     *
     * @param item an integer key.
     * @param value in Integer format.
     */
    public final void notifyProvisionedValueChanged(int item, int value) {
        try {
            mImsConfigStub.updateCachedValue(item, value, true);
        } catch (RemoteException e) {
            Log.w(TAG, "notifyProvisionedValueChanged(int): Framework connection is dead.");
        }
    }

    /**
     * Updates provisioning value and notifies the framework of the change.
     * Doesn't call {@link #setConfig(int,String)} and assumes the result succeeded.
     * This should only be used when the IMS implementer implicitly changed provisioned values.
     *
     * @param item an integer key.
     * @param value in String format.
     */
    public final void notifyProvisionedValueChanged(int item, String value) {
        try {
        mImsConfigStub.updateCachedValue(item, value, true);
        } catch (RemoteException e) {
            Log.w(TAG, "notifyProvisionedValueChanged(string): Framework connection is dead.");
        }
    }

    /**
     * Sets the configuration value for this ImsService.
     *
     * @param item an integer key.
     * @param value an integer containing the configuration value.
     * @return the result of setting the configuration value, defined as either
     * {@link #CONFIG_RESULT_FAILED} or {@link #CONFIG_RESULT_SUCCESS}.
     */
    public int setConfig(int item, int value) {
        // Base Implementation - To be overridden.
        return CONFIG_RESULT_FAILED;
    }

    /**
     * Sets the configuration value for this ImsService.
     *
     * @param item an integer key.
     * @param value a String containing the new configuration value.
     * @return Result of setting the configuration value, defined as either
     * {@link #CONFIG_RESULT_FAILED} or {@link #CONFIG_RESULT_SUCCESS}.
     */
    public int setConfig(int item, String value) {
        // Base Implementation - To be overridden.
        return CONFIG_RESULT_FAILED;
    }

    /**
     * Gets the currently stored value configuration value from the ImsService for {@code item}.
     *
     * @param item an integer key.
     * @return configuration value, stored in integer format or {@link #CONFIG_RESULT_UNKNOWN} if
     * unavailable.
     */
    public int getConfigInt(int item) {
        // Base Implementation - To be overridden.
        return CONFIG_RESULT_UNKNOWN;
    }

    /**
     * Gets the currently stored value configuration value from the ImsService for {@code item}.
     *
     * @param item an integer key.
     * @return configuration value, stored in String format or {@code null} if unavailable.
     */
    public String getConfigString(int item) {
        // Base Implementation - To be overridden.
        return null;
    }
}
