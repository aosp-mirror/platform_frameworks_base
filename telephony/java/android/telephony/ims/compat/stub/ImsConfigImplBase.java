/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.telephony.ims.compat.stub;

import android.content.Context;
import android.content.Intent;
import android.os.RemoteException;
import android.util.Log;

import com.android.ims.ImsConfig;
import com.android.ims.ImsConfigListener;
import com.android.ims.internal.IImsConfig;
import com.android.internal.annotations.VisibleForTesting;

import java.lang.ref.WeakReference;
import java.util.HashMap;


/**
 * Base implementation of ImsConfig.
 * Override the methods that your implementation of ImsConfig supports.
 *
 * DO NOT remove or change the existing APIs, only add new ones to this Base implementation or you
 * will break other implementations of ImsConfig maintained by other ImsServices.
 *
 * Provides APIs to get/set the IMS service feature/capability/parameters.
 * The config items include:
 * 1) Items provisioned by the operator.
 * 2) Items configured by user. Mainly service feature class.
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

public class ImsConfigImplBase {

    static final private String TAG = "ImsConfigImplBase";

    ImsConfigStub mImsConfigStub;

    public ImsConfigImplBase(Context context) {
        mImsConfigStub = new ImsConfigStub(this, context);
    }

    /**
     * Gets the value for ims service/capabilities parameters from the provisioned
     * value storage. Synchronous blocking call.
     *
     * @param item, as defined in com.android.ims.ImsConfig#ConfigConstants.
     * @return value in Integer format.
     */
    public int getProvisionedValue(int item) throws RemoteException {
        return -1;
    }

    /**
     * Gets the value for ims service/capabilities parameters from the provisioned
     * value storage. Synchronous blocking call.
     *
     * @param item, as defined in com.android.ims.ImsConfig#ConfigConstants.
     * @return value in String format.
     */
    public String getProvisionedStringValue(int item) throws RemoteException {
        return null;
    }

    /**
     * Sets the value for IMS service/capabilities parameters by the operator device
     * management entity. It sets the config item value in the provisioned storage
     * from which the master value is derived. Synchronous blocking call.
     *
     * @param item, as defined in com.android.ims.ImsConfig#ConfigConstants.
     * @param value in Integer format.
     * @return as defined in com.android.ims.ImsConfig#OperationStatusConstants.
     */
    public int setProvisionedValue(int item, int value) throws RemoteException {
        return ImsConfig.OperationStatusConstants.FAILED;
    }

    /**
     * Sets the value for IMS service/capabilities parameters by the operator device
     * management entity. It sets the config item value in the provisioned storage
     * from which the master value is derived.  Synchronous blocking call.
     *
     * @param item as defined in com.android.ims.ImsConfig#ConfigConstants.
     * @param value in String format.
     * @return as defined in com.android.ims.ImsConfig#OperationStatusConstants.
     */
    public int setProvisionedStringValue(int item, String value) throws RemoteException {
        return ImsConfig.OperationStatusConstants.FAILED;
    }

    /**
     * Gets the value of the specified IMS feature item for specified network type.
     * This operation gets the feature config value from the master storage (i.e. final
     * value). Asynchronous non-blocking call.
     *
     * @param feature as defined in com.android.ims.ImsConfig#FeatureConstants.
     * @param network as defined in android.telephony.TelephonyManager#NETWORK_TYPE_XXX.
     * @param listener feature value returned asynchronously through listener.
     */
    public void getFeatureValue(int feature, int network, ImsConfigListener listener)
            throws RemoteException {
    }

    /**
     * Sets the value for IMS feature item for specified network type.
     * This operation stores the user setting in setting db from which master db
     * is derived.
     *
     * @param feature as defined in com.android.ims.ImsConfig#FeatureConstants.
     * @param network as defined in android.telephony.TelephonyManager#NETWORK_TYPE_XXX.
     * @param value as defined in com.android.ims.ImsConfig#FeatureValueConstants.
     * @param listener, provided if caller needs to be notified for set result.
     */
    public void setFeatureValue(int feature, int network, int value, ImsConfigListener listener)
            throws RemoteException {
    }

    /**
     * Gets the value for IMS VoLTE provisioned.
     * This should be the same as the operator provisioned value if applies.
     */
    public boolean getVolteProvisioned() throws RemoteException {
        return false;
    }

    /**
     * Gets the value for IMS feature item video quality.
     *
     * @param listener Video quality value returned asynchronously through listener.
     */
    public void getVideoQuality(ImsConfigListener listener) throws RemoteException {
    }

    /**
     * Sets the value for IMS feature item video quality.
     *
     * @param quality, defines the value of video quality.
     * @param listener, provided if caller needs to be notified for set result.
     */
    public void setVideoQuality(int quality, ImsConfigListener listener) throws RemoteException {
    }

    public IImsConfig getIImsConfig() { return mImsConfigStub; }

    /**
     * Updates provisioning value and notifies the framework of the change.
     * Doesn't call #setProvisionedValue and assumes the result succeeded.
     * This should only be used by modem when they implicitly changed provisioned values.
     *
     * @param item, as defined in com.android.ims.ImsConfig#ConfigConstants.
     * @param value in Integer format.
     */
    public final void notifyProvisionedValueChanged(int item, int value) {
        mImsConfigStub.updateCachedValue(item, value, true);
    }

    /**
     * Updates provisioning value and notifies the framework of the change.
     * Doesn't call #setProvisionedValue and assumes the result succeeded.
     * This should only be used by modem when they implicitly changed provisioned values.
     *
     * @param item, as defined in com.android.ims.ImsConfig#ConfigConstants.
     * @param value in String format.
     */
    public final void notifyProvisionedValueChanged(int item, String value) {
        mImsConfigStub.updateCachedValue(item, value, true);
    }

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
        Context mContext;
        WeakReference<ImsConfigImplBase> mImsConfigImplBaseWeakReference;
        private HashMap<Integer, Integer> mProvisionedIntValue = new HashMap<>();
        private HashMap<Integer, String> mProvisionedStringValue = new HashMap<>();

        @VisibleForTesting
        public ImsConfigStub(ImsConfigImplBase imsConfigImplBase, Context context) {
            mContext = context;
            mImsConfigImplBaseWeakReference =
                    new WeakReference<ImsConfigImplBase>(imsConfigImplBase);
        }

        /**
         * Gets the value for ims service/capabilities parameters. It first checks its local cache,
         * if missed, it will call ImsConfigImplBase.getProvisionedValue.
         * Synchronous blocking call.
         *
         * @param item, as defined in com.android.ims.ImsConfig#ConfigConstants.
         * @return value in Integer format.
         */
        @Override
        public synchronized int getProvisionedValue(int item) throws RemoteException {
            if (mProvisionedIntValue.containsKey(item)) {
                return mProvisionedIntValue.get(item);
            } else {
                int retVal = getImsConfigImpl().getProvisionedValue(item);
                if (retVal != ImsConfig.OperationStatusConstants.UNKNOWN) {
                    updateCachedValue(item, retVal, false);
                }
                return retVal;
            }
        }

        /**
         * Gets the value for ims service/capabilities parameters. It first checks its local cache,
         * if missed, it will call #ImsConfigImplBase.getProvisionedValue.
         * Synchronous blocking call.
         *
         * @param item, as defined in com.android.ims.ImsConfig#ConfigConstants.
         * @return value in String format.
         */
        @Override
        public synchronized String getProvisionedStringValue(int item) throws RemoteException {
            if (mProvisionedIntValue.containsKey(item)) {
                return mProvisionedStringValue.get(item);
            } else {
                String retVal = getImsConfigImpl().getProvisionedStringValue(item);
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
         * @param item, as defined in com.android.ims.ImsConfig#ConfigConstants.
         * @param value in Integer format.
         * @return as defined in com.android.ims.ImsConfig#OperationStatusConstants.
         */
        @Override
        public synchronized int setProvisionedValue(int item, int value) throws RemoteException {
            mProvisionedIntValue.remove(item);
            int retVal = getImsConfigImpl().setProvisionedValue(item, value);
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
         * @return as defined in com.android.ims.ImsConfig#OperationStatusConstants.
         */
        @Override
        public synchronized int setProvisionedStringValue(int item, String value)
                throws RemoteException {
            mProvisionedStringValue.remove(item);
            int retVal = getImsConfigImpl().setProvisionedStringValue(item, value);
            if (retVal == ImsConfig.OperationStatusConstants.SUCCESS) {
                updateCachedValue(item, value, true);
            }

            return retVal;
        }

        /**
         * Wrapper function to call ImsConfigImplBase.getFeatureValue.
         */
        @Override
        public void getFeatureValue(int feature, int network, ImsConfigListener listener)
                throws RemoteException {
            getImsConfigImpl().getFeatureValue(feature, network, listener);
        }

        /**
         * Wrapper function to call ImsConfigImplBase.setFeatureValue.
         */
        @Override
        public void setFeatureValue(int feature, int network, int value, ImsConfigListener listener)
                throws RemoteException {
            getImsConfigImpl().setFeatureValue(feature, network, value, listener);
        }

        /**
         * Wrapper function to call ImsConfigImplBase.getVolteProvisioned.
         */
        @Override
        public boolean getVolteProvisioned() throws RemoteException {
            return getImsConfigImpl().getVolteProvisioned();
        }

        /**
         * Wrapper function to call ImsConfigImplBase.getVideoQuality.
         */
        @Override
        public void getVideoQuality(ImsConfigListener listener) throws RemoteException {
            getImsConfigImpl().getVideoQuality(listener);
        }

        /**
         * Wrapper function to call ImsConfigImplBase.setVideoQuality.
         */
        @Override
        public void setVideoQuality(int quality, ImsConfigListener listener)
                throws RemoteException {
            getImsConfigImpl().setVideoQuality(quality, listener);
        }

        private ImsConfigImplBase getImsConfigImpl() throws RemoteException {
            ImsConfigImplBase ref = mImsConfigImplBaseWeakReference.get();
            if (ref == null) {
                throw new RemoteException("Fail to get ImsConfigImpl");
            } else {
                return ref;
            }
        }

        private void sendImsConfigChangedIntent(int item, int value) {
            sendImsConfigChangedIntent(item, Integer.toString(value));
        }

        private void sendImsConfigChangedIntent(int item, String value) {
            Intent configChangedIntent = new Intent(ImsConfig.ACTION_IMS_CONFIG_CHANGED);
            configChangedIntent.putExtra(ImsConfig.EXTRA_CHANGED_ITEM, item);
            configChangedIntent.putExtra(ImsConfig.EXTRA_NEW_VALUE, value);
            if (mContext != null) {
                mContext.sendBroadcast(configChangedIntent);
            }
        }

        protected synchronized void updateCachedValue(int item, int value, boolean notifyChange) {
            mProvisionedIntValue.put(item, value);
            if (notifyChange) {
                sendImsConfigChangedIntent(item, value);
            }
        }

        protected synchronized void updateCachedValue(
                int item, String value, boolean notifyChange) {
            mProvisionedStringValue.put(item, value);
            if (notifyChange) {
                sendImsConfigChangedIntent(item, value);
            }
        }
    }
}