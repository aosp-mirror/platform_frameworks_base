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

package android.telephony.ims.stub;

import android.os.RemoteException;

import com.android.ims.ImsConfig;
import com.android.ims.ImsConfigListener;
import com.android.ims.internal.IImsConfig;

/**
 * Base implementation of ImsConfig, which implements stub versions of the methods
 * in the IImsConfig AIDL. Override the methods that your implementation of ImsConfig supports.
 *
 * DO NOT remove or change the existing APIs, only add new ones to this Base implementation or you
 * will break other implementations of ImsConfig maintained by other ImsServices.
 *
 * Provides APIs to get/set the IMS service feature/capability/parameters.
 * The config items include:
 * 1) Items provisioned by the operator.
 * 2) Items configured by user. Mainly service feature class.
 *
 * @hide
 */

public class ImsConfigImplBase extends IImsConfig.Stub {

    /**
     * Gets the value for ims service/capabilities parameters from the provisioned
     * value storage. Synchronous blocking call.
     *
     * @param item, as defined in com.android.ims.ImsConfig#ConfigConstants.
     * @return value in Integer format.
     */
    @Override
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
    @Override
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
    @Override
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
    @Override
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
    @Override
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
    @Override
    public void setFeatureValue(int feature, int network, int value, ImsConfigListener listener)
            throws RemoteException {
    }

    /**
     * Gets the value for IMS VoLTE provisioned.
     * This should be the same as the operator provisioned value if applies.
     */
    @Override
    public boolean getVolteProvisioned() throws RemoteException {
        return false;
    }

    /**
     * Gets the value for IMS feature item video quality.
     *
     * @param listener Video quality value returned asynchronously through listener.
     */
    @Override
    public void getVideoQuality(ImsConfigListener listener) throws RemoteException {
    }

    /**
     * Sets the value for IMS feature item video quality.
     *
     * @param quality, defines the value of video quality.
     * @param listener, provided if caller needs to be notified for set result.
     */
    @Override
    public void setVideoQuality(int quality, ImsConfigListener listener) throws RemoteException {
    }
}
