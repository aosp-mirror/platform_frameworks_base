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

import android.annotation.SystemApi;
import android.os.RemoteException;
import android.util.Log;

import com.android.ims.internal.IImsEcbm;
import com.android.ims.internal.IImsEcbmListener;

/**
 * Base implementation of ImsEcbm, which implements stub versions of the methods
 * in the IImsEcbm AIDL. Override the methods that your implementation of ImsEcbm supports.
 *
 * DO NOT remove or change the existing APIs, only add new ones to this Base implementation or you
 * will break other implementations of ImsEcbm maintained by other ImsServices.
 *
 * @hide
 */
@SystemApi
public class ImsEcbmImplBase {
    private static final String TAG = "ImsEcbmImplBase";

    private IImsEcbmListener mListener;
    private IImsEcbm mImsEcbm = new IImsEcbm.Stub() {
        @Override
        public void setListener(IImsEcbmListener listener) {
            mListener = listener;
        }

        @Override
        public void exitEmergencyCallbackMode() {
            ImsEcbmImplBase.this.exitEmergencyCallbackMode();
        }
    };

    /** @hide */
    public IImsEcbm getImsEcbm() {
        return mImsEcbm;
    }

    /**
     * This method should be implemented by the IMS provider. Framework will trigger this method to
     * request to come out of ECBM mode
     */
    public void exitEmergencyCallbackMode() {
        Log.d(TAG, "exitEmergencyCallbackMode() not implemented");
    }

    /**
     * Notifies the framework when the device enters Emergency Callback Mode.
     *
     * @throws RuntimeException if the connection to the framework is not available.
     */
    public final void enteredEcbm() {
        Log.d(TAG, "Entered ECBM.");
        if (mListener != null) {
            try {
                mListener.enteredECBM();
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Notifies the framework when the device exits Emergency Callback Mode.
     *
     * @throws RuntimeException if the connection to the framework is not available.
     */
    public final void exitedEcbm() {
        Log.d(TAG, "Exited ECBM.");
        if (mListener != null) {
            try {
                mListener.exitedECBM();
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
