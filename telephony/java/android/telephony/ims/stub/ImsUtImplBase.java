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

import android.os.Bundle;
import android.os.RemoteException;

import com.android.ims.internal.IImsUt;
import com.android.ims.internal.IImsUtListener;

/**
 * Base implementation of ImsUt, which implements stub versions of the methods
 * in the IImsUt AIDL. Override the methods that your implementation of ImsUt supports.
 *
 * DO NOT remove or change the existing APIs, only add new ones to this Base implementation or you
 * will break other implementations of ImsUt maintained by other ImsServices.
 *
 * Provides the Ut interface interworking to get/set the supplementary service configuration.
 *
 * @hide
 */

public class ImsUtImplBase extends IImsUt.Stub {

    /**
     * Closes the object. This object is not usable after being closed.
     */
    @Override
    public void close() throws RemoteException {

    }

    /**
     * Retrieves the configuration of the call barring.
     */
    @Override
    public int queryCallBarring(int cbType) throws RemoteException {
        return -1;
    }

    /**
     * Retrieves the configuration of the call forward.
     */
    @Override
    public int queryCallForward(int condition, String number) throws RemoteException {
        return -1;
    }

    /**
     * Retrieves the configuration of the call waiting.
     */
    @Override
    public int queryCallWaiting() throws RemoteException {
        return -1;
    }

    /**
     * Retrieves the default CLIR setting.
     */
    @Override
    public int queryCLIR() throws RemoteException {
        return -1;
    }

    /**
     * Retrieves the CLIP call setting.
     */
    @Override
    public int queryCLIP() throws RemoteException {
        return -1;
    }

    /**
     * Retrieves the COLR call setting.
     */
    @Override
    public int queryCOLR() throws RemoteException {
        return -1;
    }

    /**
     * Retrieves the COLP call setting.
     */
    @Override
    public int queryCOLP() throws RemoteException {
        return -1;
    }

    /**
     * Updates or retrieves the supplementary service configuration.
     */
    @Override
    public int transact(Bundle ssInfo) throws RemoteException {
        return -1;
    }

    /**
     * Updates the configuration of the call barring.
     */
    @Override
    public int updateCallBarring(int cbType, int action, String[] barrList) throws RemoteException {
        return -1;
    }

    /**
     * Updates the configuration of the call forward.
     */
    @Override
    public int updateCallForward(int action, int condition, String number, int serviceClass,
            int timeSeconds) throws RemoteException {
        return 0;
    }

    /**
     * Updates the configuration of the call waiting.
     */
    @Override
    public int updateCallWaiting(boolean enable, int serviceClass) throws RemoteException {
        return -1;
    }

    /**
     * Updates the configuration of the CLIR supplementary service.
     */
    @Override
    public int updateCLIR(int clirMode) throws RemoteException {
        return -1;
    }

    /**
     * Updates the configuration of the CLIP supplementary service.
     */
    @Override
    public int updateCLIP(boolean enable) throws RemoteException {
        return -1;
    }

    /**
     * Updates the configuration of the COLR supplementary service.
     */
    @Override
    public int updateCOLR(int presentation) throws RemoteException {
        return -1;
    }

    /**
     * Updates the configuration of the COLP supplementary service.
     */
    @Override
    public int updateCOLP(boolean enable) throws RemoteException {
        return -1;
    }

    /**
     * Sets the listener.
     */
    @Override
    public void setListener(IImsUtListener listener) throws RemoteException {
    }
}
