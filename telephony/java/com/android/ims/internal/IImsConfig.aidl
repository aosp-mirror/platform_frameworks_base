/*
 * Copyright (c) 2013 The Android Open Source Project
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


package com.android.ims.internal;

import com.android.ims.ImsConfigListener;

/**
 * Provides APIs to get/set the IMS service capability/parameters.
 * The parameters can be configured by operator and/or user.
 * We define 4 storage locations for the IMS config items:
 * 1) Default config:For factory out device or device after factory data reset,
 * the default config is used to build the initial state of the master config value.
 * 2) Provisioned value: as the parameters provisioned by operator need to be preserved
 * across FDR(factory data reset)/BOTA(over the air software upgrade), the operator
 * provisioned items should be stored in memory location preserved across FDR/BOTA.
 * 3) Master value: as the provisioned value can override the user setting,
 * and the master config are used by IMS stack. They should be stored in the
 * storage based on IMS vendor implementations.
 * 4) User setting: For items can be changed by both user/operator, the user
 * setting should take effect in some cases. So the user setting should be stored in
 * database like setting.db.
 *
 * Priority consideration if both operator/user can config the same item:
 * 1)  For feature config items, the master value is obtained from the provisioned value
 * masks with the user setting. Specifically the provisioned values overrides
 * the user setting if feature is provisioned off. Otherwise, user setting takes
 * effect.
 * 2) For non-feature config item: to be implemented based on cases.
 * Special cases considered as below:
 * 1) Factory out device, the master configuration is built from default config.
 * 2) For Factory data reset/SW upgrade device, the master config is built by
 * taking provisioned value overriding default config.
 * {@hide}
 */
interface IImsConfig {
    /**
     * Gets the value for ims service/capabilities parameters from the provisioned
     * value storage. Synchronous blocking call.
     *
     * @param item, as defined in com.android.ims.ImsConfig#ConfigConstants.
     * @return value in Integer format.
     */
    int getProvisionedValue(int item);

    /**
     * Gets the value for ims service/capabilities parameters from the provisioned
     * value storage. Synchronous blocking call.
     *
     * @param item, as defined in com.android.ims.ImsConfig#ConfigConstants.
     * @return value in String format.
     */
    String getProvisionedStringValue(int item);

    /**
     * Sets the value for IMS service/capabilities parameters by the operator device
     * management entity. It sets the config item value in the provisioned storage
     * from which the master value is derived. Synchronous blocking call.
     *
     * @param item, as defined in com.android.ims.ImsConfig#ConfigConstants.
     * @param value in Integer format.
     * @return as defined in com.android.ims.ImsConfig#OperationStatusConstants.
     */
    int setProvisionedValue(int item, int value);

    /**
     * Sets the value for IMS service/capabilities parameters by the operator device
     * management entity. It sets the config item value in the provisioned storage
     * from which the master value is derived.  Synchronous blocking call.
     *
     * @param item, as defined in com.android.ims.ImsConfig#ConfigConstants.
     * @param value in String format.
     * @return as defined in com.android.ims.ImsConfig#OperationStatusConstants.
     */
    int setProvisionedStringValue(int item, String value);

    /**
     * Gets the value of the specified IMS feature item for specified network type.
     * This operation gets the feature config value from the master storage (i.e. final
     * value). Asynchronous non-blocking call.
     *
     * @param feature. as defined in com.android.ims.ImsConfig#FeatureConstants.
     * @param network. as defined in android.telephony.TelephonyManager#NETWORK_TYPE_XXX.
     * @param listener. feature value returned asynchronously through listener.
     * @return void
     */
    oneway void getFeatureValue(int feature, int network, ImsConfigListener listener);

    /**
     * Sets the value for IMS feature item for specified network type.
     * This operation stores the user setting in setting db from which master db
     * is dervied.
     *
     * @param feature. as defined in com.android.ims.ImsConfig#FeatureConstants.
     * @param network. as defined in android.telephony.TelephonyManager#NETWORK_TYPE_XXX.
     * @param value. as defined in com.android.ims.ImsConfig#FeatureValueConstants.
     * @param listener, provided if caller needs to be notified for set result.
     * @return void
     */
    oneway void setFeatureValue(int feature, int network, int value, ImsConfigListener listener);

    /**
     * Gets the value for IMS volte provisioned.
     * This should be the same as the operator provisioned value if applies.
     *
     * @return void
     */
    boolean getVolteProvisioned();
}
