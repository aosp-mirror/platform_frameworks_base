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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.content.Context;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.telephony.ims.ProvisioningManager;
import android.telephony.ims.RcsClientConfiguration;
import android.telephony.ims.RcsConfig;
import android.telephony.ims.aidl.IImsConfig;
import android.telephony.ims.aidl.IImsConfigCallback;
import android.telephony.ims.aidl.IRcsConfigCallback;
import android.util.Log;

import com.android.ims.ImsConfig;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.util.RemoteCallbackListExt;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.util.Arrays;
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
            if (mProvisionedStringValue.containsKey(item)) {
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
         * from which the main value is derived, and write it into local cache.
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
         * from which the main value is derived, and write it into local cache.
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

        @Override
        public void updateImsCarrierConfigs(PersistableBundle bundle) throws RemoteException {
            getImsConfigImpl().updateImsCarrierConfigs(bundle);
        }

        private ImsConfigImplBase getImsConfigImpl() throws RemoteException {
            ImsConfigImplBase ref = mImsConfigImplBaseWeakReference.get();
            if (ref == null) {
                throw new RemoteException("Fail to get ImsConfigImpl");
            } else {
                return ref;
            }
        }

        @Override
        public void notifyRcsAutoConfigurationReceived(byte[] config, boolean isCompressed)
                throws RemoteException {
            getImsConfigImpl().onNotifyRcsAutoConfigurationReceived(config, isCompressed);
        }

        @Override
        public void notifyRcsAutoConfigurationRemoved()
                throws RemoteException {
            getImsConfigImpl().onNotifyRcsAutoConfigurationRemoved();
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

        @Override
        public void addRcsConfigCallback(IRcsConfigCallback c) throws RemoteException {
            getImsConfigImpl().addRcsConfigCallback(c);
        }

        @Override
        public void removeRcsConfigCallback(IRcsConfigCallback c) throws RemoteException {
            getImsConfigImpl().removeRcsConfigCallback(c);
        }

        @Override
        public void triggerRcsReconfiguration() throws RemoteException {
            getImsConfigImpl().triggerAutoConfiguration();
        }

        @Override
        public void setRcsClientConfiguration(RcsClientConfiguration rcc) throws RemoteException {
            getImsConfigImpl().setRcsClientConfiguration(rcc);
        }

        @Override
        public void notifyIntImsConfigChanged(int item, int value) throws RemoteException {
            notifyImsConfigChanged(item, value);
        }

        @Override
        public void notifyStringImsConfigChanged(int item, String value) throws RemoteException {
            notifyImsConfigChanged(item, value);
        }
    }

    /**
     * The configuration requested resulted in an unknown result. This may happen if the
     * IMS configurations are unavailable.
     */
    public static final int CONFIG_RESULT_UNKNOWN = ProvisioningManager.PROVISIONING_RESULT_UNKNOWN;

    /**
     * Setting the configuration value completed.
     */
    public static final int CONFIG_RESULT_SUCCESS = 0;
    /**
     * Setting the configuration value failed.
     */
    public static final int CONFIG_RESULT_FAILED =  1;

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "CONFIG_RESULT_", value = {
            CONFIG_RESULT_SUCCESS,
            CONFIG_RESULT_FAILED
    })
    public @interface SetConfigResult {}

    private final RemoteCallbackListExt<IImsConfigCallback> mCallbacks =
            new RemoteCallbackListExt<>();
    private final RemoteCallbackListExt<IRcsConfigCallback> mRcsCallbacks =
            new RemoteCallbackListExt<>();
    private byte[] mRcsConfigData;
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
     * Adds a {@link android.telephony.ims.ProvisioningManager.Callback} to the list of callbacks
     * notified when a value in the configuration changes.
     * @param c callback to add.
     */
    private void addImsConfigCallback(IImsConfigCallback c) {
        mCallbacks.register(c);
    }
    /**
     * Removes a {@link android.telephony.ims.ProvisioningManager.Callback} to the list of callbacks
     * notified when a value in the configuration changes.
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
        mCallbacks.broadcastAction(c -> {
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
        mCallbacks.broadcastAction(c -> {
            try {
                c.onStringConfigChanged(item, value);
            } catch (RemoteException e) {
                Log.w(TAG, "notifyConfigChanged(string): dead binder in notify, skipping.");
            }
        });
    }

    private void addRcsConfigCallback(IRcsConfigCallback c) {
        mRcsCallbacks.register(c);
        if (mRcsConfigData != null) {
            try {
                c.onConfigurationChanged(mRcsConfigData);
            } catch (RemoteException e) {
                Log.w(TAG, "dead binder to call onConfigurationChanged, skipping.");
            }
        }
    }

    private void removeRcsConfigCallback(IRcsConfigCallback c) {
        mRcsCallbacks.unregister(c);
    }

    private void onNotifyRcsAutoConfigurationReceived(byte[] config, boolean isCompressed) {
        // cache uncompressed config
        config = isCompressed ? RcsConfig.decompressGzip(config) : config;
        if (Arrays.equals(mRcsConfigData, config)) {
            return;
        }
        mRcsConfigData = config;

        // can be null in testing
        if (mRcsCallbacks != null) {
            mRcsCallbacks.broadcastAction(c -> {
                try {
                    c.onConfigurationChanged(mRcsConfigData);
                } catch (RemoteException e) {
                    Log.w(TAG, "dead binder in notifyRcsAutoConfigurationReceived, skipping.");
                }
            });
        }
        notifyRcsAutoConfigurationReceived(config, isCompressed);
    }

    private void onNotifyRcsAutoConfigurationRemoved() {
        mRcsConfigData = null;
        if (mRcsCallbacks != null) {
            mRcsCallbacks.broadcastAction(c -> {
                try {
                    c.onConfigurationReset();
                } catch (RemoteException e) {
                    Log.w(TAG, "dead binder in notifyRcsAutoConfigurationRemoved, skipping.");
                }
            });
        }
        notifyRcsAutoConfigurationRemoved();
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
     * The framework has received an RCS autoconfiguration XML file for provisioning.
     *
     * @param config The XML file to be read, if not compressed, it should be in ASCII/UTF8 format.
     * @param isCompressed The XML file is compressed in gzip format and must be decompressed
     *         before being read.
     *
     */
    public void notifyRcsAutoConfigurationReceived(@NonNull byte[] config, boolean isCompressed) {
    }

    /**
     * The RCS autoconfiguration XML file is removed or invalid.
     */
    public void notifyRcsAutoConfigurationRemoved() {
    }

    /**
     * Sets the configuration value for this ImsService.
     *
     * @param item an integer key.
     * @param value an integer containing the configuration value.
     * @return the result of setting the configuration value.
     */
    public @SetConfigResult int setConfig(int item, int value) {
        // Base Implementation - To be overridden.
        return CONFIG_RESULT_FAILED;
    }

    /**
     * Sets the configuration value for this ImsService.
     *
     * @param item an integer key.
     * @param value a String containing the new configuration value.
     * @return Result of setting the configuration value.
     */
    public @SetConfigResult int setConfig(int item, String value) {
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

    /**
     * @hide
     */
    public void updateImsCarrierConfigs(PersistableBundle bundle) {
        // Base Implementation - Should be overridden
    }

    /**
     * Default messaging application parameters are sent to the ACS client
     * using this interface.
     * @param rcc RCS client configuration {@link RcsClientConfiguration}
     */
    public void setRcsClientConfiguration(@NonNull RcsClientConfiguration rcc) {
        // Base Implementation - Should be overridden
    }

    /**
     * Reconfiguration triggered by the RCS application. Most likely cause
     * is the 403 forbidden to a SIP/HTTP request
     */
    public void triggerAutoConfiguration() {
        // Base Implementation - Should be overridden
    }

    /**
     * Errors during autoconfiguration connection setup are notified by the
     * ACS client using this interface.
     * @param errorCode HTTP error received during connection setup.
     * @param errorString reason phrase received with the error
     */
    public final void notifyAutoConfigurationErrorReceived(int errorCode,
            @NonNull String errorString) {
        // can be null in testing
        if (mRcsCallbacks == null) {
            return;
        }
        mRcsCallbacks.broadcastAction(c -> {
            try {
                //TODO compressed by default?
                c.onAutoConfigurationErrorReceived(errorCode, errorString);
            } catch (RemoteException e) {
                Log.w(TAG, "dead binder in notifyAutoConfigurationErrorReceived, skipping.");
            }
        });
    }
}
