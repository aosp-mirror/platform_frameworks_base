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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.os.Bundle;
import android.os.RemoteException;
import android.telephony.ims.ImsUtListener;

import com.android.ims.internal.IImsUt;
import com.android.ims.internal.IImsUtListener;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Base implementation of IMS UT interface, which implements stubs. Override these methods to
 * implement functionality.
 *
 * @hide
 */
// DO NOT remove or change the existing APIs, only add new ones to this Base implementation or you
// will break other implementations of ImsUt maintained by other ImsServices.
@SystemApi
@TestApi
public class ImsUtImplBase {
    /**
     * Bar all incoming calls. (See 3GPP TS 24.611)
     * @hide
     */
    public static final int CALL_BARRING_ALL_INCOMING = 1;

    /**
     * Bar all outgoing calls. (See 3GPP TS 24.611)
     * @hide
     */
    public static final int CALL_BARRING_ALL_OUTGOING = 2;

    /**
     * Bar all outgoing international calls. (See 3GPP TS 24.611)
     * @hide
     */
    public static final int CALL_BARRING_OUTGOING_INTL = 3;

    /**
     * Bar all outgoing international calls, excluding those to the home PLMN country
     * (See 3GPP TS 24.611)
     * @hide
     */
    public static final int CALL_BARRING_OUTGOING_INTL_EXCL_HOME = 4;

    /**
     * Bar all incoming calls when roaming (See 3GPP TS 24.611)
     * @hide
     */
    public static final int CALL_BLOCKING_INCOMING_WHEN_ROAMING = 5;

    /**
     * Enable Anonymous Communication Rejection (See 3GPP TS 24.611)
     * @hide
     */
    public static final int CALL_BARRING_ANONYMOUS_INCOMING = 6;

    /**
     * Bar all incoming and outgoing calls. (See 3GPP TS 24.611)
     * @hide
     */
    public static final int CALL_BARRING_ALL = 7;

    /**
     * Bar all outgoing service requests, including calls. (See 3GPP TS 24.611)
     * @hide
     */
    public static final int CALL_BARRING_OUTGOING_ALL_SERVICES = 8;

    /**
     * Bar all incoming service requests, including calls. (See 3GPP TS 24.611)
     * @hide
     */
    public static final int CALL_BARRING_INCOMING_ALL_SERVICES = 9;

    /**
     * Bar specific incoming calls. (See 3GPP TS 24.611)
     * @hide
     */
    public static final int CALL_BARRING_SPECIFIC_INCOMING_CALLS = 10;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "CALL_BARRING_", value = {CALL_BARRING_ALL_INCOMING, CALL_BARRING_ALL_OUTGOING,
            CALL_BARRING_OUTGOING_INTL, CALL_BARRING_OUTGOING_INTL_EXCL_HOME,
            CALL_BLOCKING_INCOMING_WHEN_ROAMING, CALL_BARRING_ANONYMOUS_INCOMING,
            CALL_BARRING_ALL, CALL_BARRING_OUTGOING_ALL_SERVICES,
            CALL_BARRING_INCOMING_ALL_SERVICES, CALL_BARRING_SPECIFIC_INCOMING_CALLS})
    public @interface CallBarringMode {}

    /**
     * Constant used to denote an invalid return value.
     * @hide
     */
    public static final int INVALID_RESULT = -1;

    private IImsUt.Stub mServiceImpl = new IImsUt.Stub() {
        @Override
        public void close() throws RemoteException {
            ImsUtImplBase.this.close();
        }

        @Override
        public int queryCallBarring(int cbType) throws RemoteException {
            return ImsUtImplBase.this.queryCallBarring(cbType);
        }

        @Override
        public int queryCallForward(int condition, String number) throws RemoteException {
            return ImsUtImplBase.this.queryCallForward(condition, number);
        }

        @Override
        public int queryCallWaiting() throws RemoteException {
            return ImsUtImplBase.this.queryCallWaiting();
        }

        @Override
        public int queryCLIR() throws RemoteException {
            return ImsUtImplBase.this.queryCLIR();
        }

        @Override
        public int queryCLIP() throws RemoteException {
            return ImsUtImplBase.this.queryCLIP();
        }

        @Override
        public int queryCOLR() throws RemoteException {
            return ImsUtImplBase.this.queryCOLR();
        }

        @Override
        public int queryCOLP() throws RemoteException {
            return ImsUtImplBase.this.queryCOLP();
        }

        @Override
        public int transact(Bundle ssInfo) throws RemoteException {
            return ImsUtImplBase.this.transact(ssInfo);
        }

        @Override
        public int updateCallBarring(int cbType, int action, String[] barrList) throws
                RemoteException {
            return ImsUtImplBase.this.updateCallBarring(cbType, action, barrList);
        }

        @Override
        public int updateCallForward(int action, int condition, String number, int serviceClass,
                int timeSeconds) throws RemoteException {
            return ImsUtImplBase.this.updateCallForward(action, condition, number, serviceClass,
                    timeSeconds);
        }

        @Override
        public int updateCallWaiting(boolean enable, int serviceClass) throws RemoteException {
            return ImsUtImplBase.this.updateCallWaiting(enable, serviceClass);
        }

        @Override
        public int updateCLIR(int clirMode) throws RemoteException {
            return ImsUtImplBase.this.updateCLIR(clirMode);
        }

        @Override
        public int updateCLIP(boolean enable) throws RemoteException {
            return ImsUtImplBase.this.updateCLIP(enable);
        }

        @Override
        public int updateCOLR(int presentation) throws RemoteException {
            return ImsUtImplBase.this.updateCOLR(presentation);
        }

        @Override
        public int updateCOLP(boolean enable) throws RemoteException {
            return ImsUtImplBase.this.updateCOLP(enable);
        }

        @Override
        public void setListener(IImsUtListener listener) throws RemoteException {
            ImsUtImplBase.this.setListener(new ImsUtListener(listener));
        }

        @Override
        public int queryCallBarringForServiceClass(int cbType, int serviceClass)
                throws RemoteException {
            return ImsUtImplBase.this.queryCallBarringForServiceClass(cbType, serviceClass);
        }

        @Override
        public int updateCallBarringForServiceClass(int cbType, int action,
                String[] barrList, int serviceClass) throws RemoteException {
            return ImsUtImplBase.this.updateCallBarringForServiceClass(
                    cbType, action, barrList, serviceClass);
        }

        @Override
        public int updateCallBarringWithPassword(int cbType, int action, String[] barrList,
                int serviceClass, String password) throws RemoteException {
            return ImsUtImplBase.this.updateCallBarringWithPassword(
                    cbType, action, barrList, serviceClass, password);
        }
    };

    /**
     * Called when the framework no longer needs to interact with the IMS UT implementation any
     * longer.
     */
    public void close() {

    }

    /**
     * Retrieves the call barring configuration.
     * @param cbType
     */
    public int queryCallBarring(int cbType) {
        return -1;
    }

    /**
     * Retrieves the configuration of the call barring for specified service class.
     */
    public int queryCallBarringForServiceClass(int cbType, int serviceClass) {
        return -1;
    }

    /**
     * Retrieves the configuration of the call forward.
     */
    public int queryCallForward(int condition, String number) {
        return -1;
    }

    /**
     * Retrieves the configuration of the call waiting.
     */
    public int queryCallWaiting() {
        return -1;
    }

    /**
     * Retrieves the default CLIR setting.
     * @hide
     */
    public int queryCLIR() {
        return queryClir();
    }

    /**
     * Retrieves the CLIP call setting.
     * @hide
     */
    public int queryCLIP() {
        return queryClip();
    }

    /**
     * Retrieves the COLR call setting.
     * @hide
     */
    public int queryCOLR() {
        return queryColr();
    }

    /**
     * Retrieves the COLP call setting.
     * @hide
     */
    public int queryCOLP() {
        return queryColp();
    }

    /**
     * Retrieves the default CLIR setting.
     */
    public int queryClir() {
        return -1;
    }

    /**
     * Retrieves the CLIP call setting.
     */
    public int queryClip() {
        return -1;
    }

    /**
     * Retrieves the COLR call setting.
     */
    public int queryColr() {
        return -1;
    }

    /**
     * Retrieves the COLP call setting.
     */
    public int queryColp() {
        return -1;
    }

    /**
     * Updates or retrieves the supplementary service configuration.
     */
    public int transact(Bundle ssInfo) {
        return -1;
    }

    /**
     * Updates the configuration of the call barring.
     */
    public int updateCallBarring(@CallBarringMode int cbType, int action, String[] barrList) {
        return -1;
    }

    /**
     * Updates the configuration of the call barring for specified service class.
     */
    public int updateCallBarringForServiceClass(@CallBarringMode int cbType, int action,
            String[] barrList, int serviceClass) {
        return -1;
    }

    /**
     * Updates the configuration of the call barring for specified service class with password.
     * @hide
     */
    public int updateCallBarringWithPassword(int cbType, int action, @Nullable String[] barrList,
            int serviceClass, @NonNull String password) {
        return -1;
    }

    /**
     * Updates the configuration of the call forward.
     */
    public int updateCallForward(int action, int condition, String number, int serviceClass,
            int timeSeconds) {
        return 0;
    }

    /**
     * Updates the configuration of the call waiting.
     */
    public int updateCallWaiting(boolean enable, int serviceClass) {
        return -1;
    }

    /**
     * Updates the configuration of the CLIR supplementary service.
     * @hide
     */
    public int updateCLIR(int clirMode) {
        return updateClir(clirMode);
    }

    /**
     * Updates the configuration of the CLIP supplementary service.
     * @hide
     */
    public int updateCLIP(boolean enable) {
        return updateClip(enable);
    }

    /**
     * Updates the configuration of the COLR supplementary service.
     * @hide
     */
    public int updateCOLR(int presentation) {
        return updateColr(presentation);
    }

    /**
     * Updates the configuration of the COLP supplementary service.
     * @hide
     */
    public int updateCOLP(boolean enable) {
        return updateColp(enable);
    }

    /**
     * Updates the configuration of the CLIR supplementary service.
     */
    public int updateClir(int clirMode) {
        return -1;
    }

    /**
     * Updates the configuration of the CLIP supplementary service.
     */
    public int updateClip(boolean enable) {
        return -1;
    }

    /**
     * Updates the configuration of the COLR supplementary service.
     */
    public int updateColr(int presentation) {
        return -1;
    }

    /**
     * Updates the configuration of the COLP supplementary service.
     */
    public int updateColp(boolean enable) {
        return -1;
    }

    /**
     * Sets the listener.
     */
    public void setListener(ImsUtListener listener) {
    }

    /**
     * @hide
     */
    public IImsUt getInterface() {
        return mServiceImpl;
    }
}
