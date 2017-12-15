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

package android.telephony.ims.internal.stub;

import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.telephony.ims.internal.aidl.IImsConfig;
import android.telephony.ims.internal.aidl.IImsConfigCallback;

import com.android.ims.ImsConfig;

/**
 * Controls the modification of IMS specific configurations. For more information on the supported
 * IMS configuration constants, see {@link ImsConfig}.
 *
 * @hide
 */

public class ImsConfigImplBase {

    //TODO: Implement the Binder logic to call base APIs. Need to finish other ImsService Config
    // work first.
    private final IImsConfig mBinder = new IImsConfig.Stub() {

        @Override
        public void addImsConfigCallback(IImsConfigCallback c) throws RemoteException {
            ImsConfigImplBase.this.addImsConfigCallback(c);
        }

        @Override
        public void removeImsConfigCallback(IImsConfigCallback c) throws RemoteException {
            ImsConfigImplBase.this.removeImsConfigCallback(c);
        }

        @Override
        public int getConfigInt(int item) throws RemoteException {
            return Integer.MIN_VALUE;
        }

        @Override
        public String getConfigString(int item) throws RemoteException {
            return null;
        }

        @Override
        public int setConfigInt(int item, int value) throws RemoteException {
            return Integer.MIN_VALUE;
        }

        @Override
        public int setConfigString(int item, String value) throws RemoteException {
            return Integer.MIN_VALUE;
        }
    };

    public class Callback extends IImsConfigCallback.Stub {

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

    private final RemoteCallbackList<IImsConfigCallback> mCallbacks = new RemoteCallbackList<>();

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

    public final IImsConfig getBinder() {
        return mBinder;
    }

    /**
     * Sets the value for IMS service/capabilities parameters by the operator device
     * management entity. It sets the config item value in the provisioned storage
     * from which the master value is derived.
     *
     * @param item as defined in com.android.ims.ImsConfig#ConfigConstants.
     * @param value in Integer format.
     * @return as defined in com.android.ims.ImsConfig#OperationStatusConstants.
     */
    public int setConfig(int item, int value) {
        // Base Implementation - To be overridden.
        return ImsConfig.OperationStatusConstants.FAILED;
    }

    /**
     * Sets the value for IMS service/capabilities parameters by the operator device
     * management entity. It sets the config item value in the provisioned storage
     * from which the master value is derived.
     *
     * @param item as defined in com.android.ims.ImsConfig#ConfigConstants.
     * @param value in String format.
     * @return as defined in com.android.ims.ImsConfig#OperationStatusConstants.
     */
    public int setConfig(int item, String value) {
        return ImsConfig.OperationStatusConstants.FAILED;
    }

    /**
     * Gets the value for ims service/capabilities parameters from the provisioned
     * value storage.
     *
     * @param item as defined in com.android.ims.ImsConfig#ConfigConstants.
     * @return value in Integer format.
     */
    public int getConfigInt(int item) {
        return ImsConfig.OperationStatusConstants.FAILED;
    }

    /**
     * Gets the value for ims service/capabilities parameters from the provisioned
     * value storage.
     *
     * @param item as defined in com.android.ims.ImsConfig#ConfigConstants.
     * @return value in String format.
     */
    public String getConfigString(int item) {
        return null;
    }
}
