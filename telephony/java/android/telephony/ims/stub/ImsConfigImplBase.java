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
import com.android.internal.telephony.util.TelephonyUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;


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
        private final Object mLock = new Object();
        private Executor mExecutor;

        @VisibleForTesting
        public ImsConfigStub(ImsConfigImplBase imsConfigImplBase, Executor executor) {
            mExecutor = executor;
            mImsConfigImplBaseWeakReference =
                    new WeakReference<ImsConfigImplBase>(imsConfigImplBase);
        }

        @Override
        public void addImsConfigCallback(IImsConfigCallback c) throws RemoteException {
            AtomicReference<RemoteException> exceptionRef = new AtomicReference<>();
            executeMethodAsync(()-> {
                try {
                    getImsConfigImpl().addImsConfigCallback(c);
                } catch (RemoteException e) {
                    exceptionRef.set(e);
                }
            }, "addImsConfigCallback");

            if (exceptionRef.get() != null) {
                Log.d(TAG, "ImsConfigImplBase Exception addImsConfigCallback");
                throw exceptionRef.get();
            }
        }

        @Override
        public void removeImsConfigCallback(IImsConfigCallback c) throws RemoteException {
            AtomicReference<RemoteException> exceptionRef = new AtomicReference<>();
            executeMethodAsync(()-> {
                try {
                    getImsConfigImpl().removeImsConfigCallback(c);
                } catch (RemoteException e) {
                    exceptionRef.set(e);
                }
            }, "removeImsConfigCallback");

            if (exceptionRef.get() != null) {
                Log.d(TAG, "ImsConfigImplBase Exception removeImsConfigCallback");
                throw exceptionRef.get();
            }
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
        public int getConfigInt(int item) throws RemoteException {
            AtomicReference<RemoteException> exceptionRef = new AtomicReference<>();
            int retVal = executeMethodAsyncForResult(()-> {
                int returnVal = ImsConfig.OperationStatusConstants.UNKNOWN;
                synchronized (mLock) {
                    if (mProvisionedIntValue.containsKey(item)) {
                        return mProvisionedIntValue.get(item);
                    } else {
                        try {
                            returnVal = getImsConfigImpl().getConfigInt(item);
                            if (returnVal != ImsConfig.OperationStatusConstants.UNKNOWN) {
                                mProvisionedIntValue.put(item, returnVal);
                            }
                        } catch (RemoteException e) {
                            exceptionRef.set(e);
                            return returnVal;
                        }
                    }
                }
                return returnVal;
            }, "getConfigInt");

            if (exceptionRef.get() != null) {
                Log.d(TAG, "ImsConfigImplBase Exception getConfigString");
                throw exceptionRef.get();
            }

            return retVal;
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
        public String getConfigString(int item) throws RemoteException {
            AtomicReference<RemoteException> exceptionRef = new AtomicReference<>();
            String retVal = executeMethodAsyncForResult(()-> {
                String returnVal = null;
                synchronized (mLock) {
                    if (mProvisionedStringValue.containsKey(item)) {
                        returnVal = mProvisionedStringValue.get(item);
                    } else {
                        try {
                            returnVal = getImsConfigImpl().getConfigString(item);
                            if (returnVal != null) {
                                mProvisionedStringValue.put(item, returnVal);
                            }
                        } catch (RemoteException e) {
                            exceptionRef.set(e);
                            return returnVal;
                        }
                    }
                }
                return returnVal;
            }, "getConfigString");

            if (exceptionRef.get() != null) {
                Log.d(TAG, "ImsConfigImplBase Exception getConfigString");
                throw exceptionRef.get();
            }

            return retVal;
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
        public int setConfigInt(int item, int value) throws RemoteException {
            AtomicReference<RemoteException> exceptionRef = new AtomicReference<>();
            int retVal = executeMethodAsyncForResult(()-> {
                int returnVal = ImsConfig.OperationStatusConstants.UNKNOWN;
                try {
                    synchronized (mLock) {
                        mProvisionedIntValue.remove(item);
                        returnVal = getImsConfigImpl().setConfig(item, value);
                        if (returnVal == ImsConfig.OperationStatusConstants.SUCCESS) {
                            mProvisionedIntValue.put(item, value);
                        } else {
                            Log.d(TAG, "Set provision value of " + item
                                    + " to " + value + " failed with error code " + returnVal);
                        }
                    }
                    notifyImsConfigChanged(item, value);
                    return returnVal;
                } catch (RemoteException e) {
                    exceptionRef.set(e);
                    return returnVal;
                }
            }, "setConfigInt");

            if (exceptionRef.get() != null) {
                Log.d(TAG, "ImsConfigImplBase Exception setConfigInt");
                throw exceptionRef.get();
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
        public int setConfigString(int item, String value)
                throws RemoteException {
            AtomicReference<RemoteException> exceptionRef = new AtomicReference<>();
            int retVal = executeMethodAsyncForResult(()-> {
                int returnVal = ImsConfig.OperationStatusConstants.UNKNOWN;
                try {
                    synchronized (mLock) {
                        mProvisionedStringValue.remove(item);
                        returnVal = getImsConfigImpl().setConfig(item, value);
                        if (returnVal == ImsConfig.OperationStatusConstants.SUCCESS) {
                            mProvisionedStringValue.put(item, value);
                        }
                    }
                    notifyImsConfigChanged(item, value);
                    return returnVal;
                } catch (RemoteException e) {
                    exceptionRef.set(e);
                    return returnVal;
                }
            }, "setConfigString");

            if (exceptionRef.get() != null) {
                Log.d(TAG, "ImsConfigImplBase Exception setConfigInt");
                throw exceptionRef.get();
            }

            return retVal;
        }

        @Override
        public void updateImsCarrierConfigs(PersistableBundle bundle) throws RemoteException {
            AtomicReference<RemoteException> exceptionRef = new AtomicReference<>();
            executeMethodAsync(()-> {
                try {
                    getImsConfigImpl().updateImsCarrierConfigs(bundle);
                } catch (RemoteException e) {
                    exceptionRef.set(e);
                }
            }, "updateImsCarrierConfigs");

            if (exceptionRef.get() != null) {
                Log.d(TAG, "ImsConfigImplBase Exception updateImsCarrierConfigs");
                throw exceptionRef.get();
            }
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
            AtomicReference<RemoteException> exceptionRef = new AtomicReference<>();
            executeMethodAsync(()-> {
                try {
                    getImsConfigImpl().onNotifyRcsAutoConfigurationReceived(config, isCompressed);
                } catch (RemoteException e) {
                    exceptionRef.set(e);
                }
            }, "notifyRcsAutoConfigurationReceived");

            if (exceptionRef.get() != null) {
                Log.d(TAG, "ImsConfigImplBase Exception notifyRcsAutoConfigurationReceived");
                throw exceptionRef.get();
            }
        }

        @Override
        public void notifyRcsAutoConfigurationRemoved()
                throws RemoteException {
            AtomicReference<RemoteException> exceptionRef = new AtomicReference<>();
            executeMethodAsync(()-> {
                try {
                    getImsConfigImpl().onNotifyRcsAutoConfigurationRemoved();
                } catch (RemoteException e) {
                    exceptionRef.set(e);
                }
            }, "notifyRcsAutoConfigurationRemoved");

            if (exceptionRef.get() != null) {
                Log.d(TAG, "ImsConfigImplBase Exception notifyRcsAutoConfigurationRemoved");
                throw exceptionRef.get();
            }
        }

        private void notifyImsConfigChanged(int item, int value) throws RemoteException {
            getImsConfigImpl().notifyConfigChanged(item, value);
        }

        private void notifyImsConfigChanged(int item, String value) throws RemoteException {
            getImsConfigImpl().notifyConfigChanged(item, value);
        }

        protected void updateCachedValue(int item, int value) {
            synchronized (mLock) {
                mProvisionedIntValue.put(item, value);
            }
        }

        protected void updateCachedValue(int item, String value) {
            synchronized (mLock) {
                mProvisionedStringValue.put(item, value);
            }
        }

        @Override
        public void addRcsConfigCallback(IRcsConfigCallback c) throws RemoteException {
            AtomicReference<RemoteException> exceptionRef = new AtomicReference<>();
            executeMethodAsync(()-> {
                try {
                    getImsConfigImpl().addRcsConfigCallback(c);
                } catch (RemoteException e) {
                    exceptionRef.set(e);
                }
            }, "addRcsConfigCallback");

            if (exceptionRef.get() != null) {
                Log.d(TAG, "ImsConfigImplBase Exception addRcsConfigCallback");
                throw exceptionRef.get();
            }
        }

        @Override
        public void removeRcsConfigCallback(IRcsConfigCallback c) throws RemoteException {
            AtomicReference<RemoteException> exceptionRef = new AtomicReference<>();
            executeMethodAsync(()-> {
                try {
                    getImsConfigImpl().removeRcsConfigCallback(c);
                } catch (RemoteException e) {
                    exceptionRef.set(e);
                }
            }, "removeRcsConfigCallback");

            if (exceptionRef.get() != null) {
                Log.d(TAG, "ImsConfigImplBase Exception removeRcsConfigCallback");
                throw exceptionRef.get();
            }
        }

        @Override
        public void triggerRcsReconfiguration() throws RemoteException {
            AtomicReference<RemoteException> exceptionRef = new AtomicReference<>();
            executeMethodAsync(()-> {
                try {
                    getImsConfigImpl().triggerAutoConfiguration();
                } catch (RemoteException e) {
                    exceptionRef.set(e);
                }
            }, "triggerRcsReconfiguration");

            if (exceptionRef.get() != null) {
                Log.d(TAG, "ImsConfigImplBase Exception triggerRcsReconfiguration");
                throw exceptionRef.get();
            }
        }

        @Override
        public void setRcsClientConfiguration(RcsClientConfiguration rcc) throws RemoteException {
            AtomicReference<RemoteException> exceptionRef = new AtomicReference<>();
            executeMethodAsync(()-> {
                try {
                    getImsConfigImpl().setRcsClientConfiguration(rcc);
                } catch (RemoteException e) {
                    exceptionRef.set(e);
                }
            }, "setRcsClientConfiguration");

            if (exceptionRef.get() != null) {
                Log.d(TAG, "ImsConfigImplBase Exception setRcsClientConfiguration");
                throw exceptionRef.get();
            }
        }

        @Override
        public void notifyIntImsConfigChanged(int item, int value) throws RemoteException {
            AtomicReference<RemoteException> exceptionRef = new AtomicReference<>();
            executeMethodAsync(()-> {
                try {
                    notifyImsConfigChanged(item, value);
                } catch (RemoteException e) {
                    exceptionRef.set(e);
                }
            }, "notifyIntImsConfigChanged");

            if (exceptionRef.get() != null) {
                Log.d(TAG, "ImsConfigImplBase Exception notifyIntImsConfigChanged");
                throw exceptionRef.get();
            }
        }

        @Override
        public void notifyStringImsConfigChanged(int item, String value) throws RemoteException {
            AtomicReference<RemoteException> exceptionRef = new AtomicReference<>();
            executeMethodAsync(()-> {
                try {
                    notifyImsConfigChanged(item, value);
                } catch (RemoteException e) {
                    exceptionRef.set(e);
                }
            }, "notifyStringImsConfigChanged");

            if (exceptionRef.get() != null) {
                Log.d(TAG, "ImsConfigImplBase Exception notifyStringImsConfigChanged");
                throw exceptionRef.get();
            }
        }

        /**
         * Clear cached configuration value.
         */
        public void clearCachedValue() {
            Log.i(TAG, "clearCachedValue");
            synchronized (mLock) {
                mProvisionedIntValue.clear();
                mProvisionedStringValue.clear();
            }
        }

        // Call the methods with a clean calling identity on the executor and wait indefinitely for
        // the future to return.
        private void executeMethodAsync(Runnable r, String errorLogName) throws RemoteException {
            try {
                CompletableFuture.runAsync(
                        () -> TelephonyUtils.runWithCleanCallingIdentity(r), mExecutor).join();
            } catch (CancellationException | CompletionException e) {
                Log.w(TAG, "ImsConfigImplBase Binder - " + errorLogName + " exception: "
                        + e.getMessage());
                throw new RemoteException(e.getMessage());
            }
        }

        private <T> T executeMethodAsyncForResult(Supplier<T> r,
                String errorLogName) throws RemoteException {
            CompletableFuture<T> future = CompletableFuture.supplyAsync(
                    () -> TelephonyUtils.runWithCleanCallingIdentity(r), mExecutor);
            try {
                return future.get();
            } catch (ExecutionException | InterruptedException e) {
                Log.w(TAG, "ImsConfigImplBase Binder - " + errorLogName + " exception: "
                        + e.getMessage());
                throw new RemoteException(e.getMessage());
            }
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
    private final Object mRcsConfigDataLock = new Object();
    ImsConfigStub mImsConfigStub;

    /**
     * Create a ImsConfig using the Executor specified for methods being called by the
     * framework.
     * @param executor The executor for the framework to use when executing the methods overridden
     * by the implementation of ImsConfig.
     */
    public ImsConfigImplBase(@NonNull Executor executor) {
        mImsConfigStub = new ImsConfigStub(this, executor);
    }

    /**
     * @hide
     */
    public ImsConfigImplBase(@NonNull Context context) {
        mImsConfigStub = new ImsConfigStub(this, null);
    }

    public ImsConfigImplBase() {
        mImsConfigStub = new ImsConfigStub(this, null);
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
        synchronized (mCallbacks) {
            mCallbacks.broadcastAction(c -> {
                try {
                    c.onIntConfigChanged(item, value);
                } catch (RemoteException e) {
                    Log.w(TAG, "notifyConfigChanged(int): dead binder in notify, skipping.");
                }
            });
        }
    }

    private void notifyConfigChanged(int item, String value) {
        // can be null in testing
        if (mCallbacks == null) {
            return;
        }
        synchronized (mCallbacks) {
            mCallbacks.broadcastAction(c -> {
                try {
                    c.onStringConfigChanged(item, value);
                } catch (RemoteException e) {
                    Log.w(TAG, "notifyConfigChanged(string): dead binder in notify, skipping.");
                }
            });
        }
    }

    private void addRcsConfigCallback(IRcsConfigCallback c) {
        mRcsCallbacks.register(c);

        // This is used to avoid calling the binder out of the synchronized scope.
        byte[] cloneRcsConfigData;
        synchronized (mRcsConfigDataLock) {
            if (mRcsConfigData == null) {
                return;
            }
            cloneRcsConfigData = mRcsConfigData.clone();
        }

        try {
            c.onConfigurationChanged(cloneRcsConfigData);
        } catch (RemoteException e) {
            Log.w(TAG, "dead binder to call onConfigurationChanged, skipping.");
        }
    }

    private void removeRcsConfigCallback(IRcsConfigCallback c) {
        mRcsCallbacks.unregister(c);
    }

    private void onNotifyRcsAutoConfigurationReceived(byte[] config, boolean isCompressed) {
        // cache uncompressed config
        final byte[] rcsConfigData = isCompressed ? RcsConfig.decompressGzip(config) : config;

        synchronized (mRcsConfigDataLock) {
            if (Arrays.equals(mRcsConfigData, config)) {
                return;
            }
            mRcsConfigData = rcsConfigData;
        }

        // can be null in testing
        if (mRcsCallbacks != null) {
            synchronized (mRcsCallbacks) {
                mRcsCallbacks.broadcastAction(c -> {
                    try {
                        // config is cloned here so modifications to the config passed to the
                        // vendor do not accidentally modify the cache.
                        c.onConfigurationChanged(rcsConfigData.clone());
                    } catch (RemoteException e) {
                        Log.w(TAG, "dead binder in notifyRcsAutoConfigurationReceived, skipping.");
                    }
                });
            }
        }
        notifyRcsAutoConfigurationReceived(config, isCompressed);
    }

    private void onNotifyRcsAutoConfigurationRemoved() {
        synchronized (mRcsConfigDataLock) {
            mRcsConfigData = null;
        }
        if (mRcsCallbacks != null) {
            synchronized (mRcsCallbacks) {
                mRcsCallbacks.broadcastAction(c -> {
                    try {
                        c.onConfigurationReset();
                    } catch (RemoteException e) {
                        Log.w(TAG, "dead binder in notifyRcsAutoConfigurationRemoved, skipping.");
                    }
                });
            }
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
        mImsConfigStub.updateCachedValue(item, value);

        try {
            mImsConfigStub.notifyImsConfigChanged(item, value);
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
        mImsConfigStub.updateCachedValue(item, value);

        try {
            mImsConfigStub.notifyImsConfigChanged(item, value);
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
        synchronized (mRcsCallbacks) {
            mRcsCallbacks.broadcastAction(c -> {
                try {
                    c.onAutoConfigurationErrorReceived(errorCode, errorString);
                } catch (RemoteException e) {
                    Log.w(TAG, "dead binder in notifyAutoConfigurationErrorReceived, skipping.");
                }
            });
        }
    }

    /**
     * Notifies application that pre-provisioning config is received.
     *
     * <p>Some carriers using ACS (auto configuration server) may send a carrier-specific
     * pre-provisioning configuration XML if the user has not been provisioned for RCS
     * services yet. When such provisioning XML is received, ACS client must call this
     * method to notify the application with the XML.
     *
     * @param configXml the pre-provisioning config in carrier specified format.
     */
    public final void notifyPreProvisioningReceived(@NonNull byte[] configXml) {
        // can be null in testing
        if (mRcsCallbacks == null) {
            return;
        }
        synchronized (mRcsCallbacks) {
            mRcsCallbacks.broadcastAction(c -> {
                try {
                    c.onPreProvisioningReceived(configXml);
                } catch (RemoteException e) {
                    Log.w(TAG, "dead binder in notifyPreProvisioningReceived, skipping.");
                }
            });
        }
    }

    /**
     * Set default Executor from ImsService.
     * @param executor The default executor for the framework to use when executing the methods
     * overridden by the implementation of ImsConfig.
     * @hide
     */
    public final void setDefaultExecutor(@NonNull Executor executor) {
        if (mImsConfigStub.mExecutor == null) {
            mImsConfigStub.mExecutor = executor;
        }
    }

    /**
     * Clear all cached config data. This will be called when the config data is no longer valid
     * such as when the SIM was removed.
     * @hide
     */
    public final void clearConfigurationCache() {
        mImsConfigStub.clearCachedValue();

        synchronized (mRcsConfigDataLock) {
            mRcsConfigData = null;
        }
    }
}
