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
import android.telephony.ims.ImsExternalCallState;
import android.util.Log;

import com.android.ims.internal.IImsExternalCallStateListener;
import com.android.ims.internal.IImsMultiEndpoint;

import java.util.List;
import java.util.Objects;

/**
 * Base implementation of ImsMultiEndpoint, which implements stub versions of the methods
 * in the IImsMultiEndpoint AIDL. Override the methods that your implementation of
 * ImsMultiEndpoint supports.
 *
 * DO NOT remove or change the existing APIs, only add new ones to this Base implementation or you
 * will break other implementations of ImsMultiEndpoint maintained by other ImsServices.
 *
 * @hide
 */
@SystemApi
public class ImsMultiEndpointImplBase {
    private static final String TAG = "MultiEndpointImplBase";

    private IImsExternalCallStateListener mListener;
    private final Object mLock = new Object();
    private final IImsMultiEndpoint mImsMultiEndpoint = new IImsMultiEndpoint.Stub() {

        @Override
        public void setListener(IImsExternalCallStateListener listener) throws RemoteException {
            synchronized (mLock) {
                if (mListener != null && !mListener.asBinder().isBinderAlive()) {
                    Log.w(TAG, "setListener: discarding dead Binder");
                    mListener = null;
                }
                if (mListener != null && listener != null && Objects.equals(
                        mListener.asBinder(), listener.asBinder())) {
                    return;
                }

                if (listener == null) {
                    mListener = null;
                } else if (listener != null && mListener == null) {
                    mListener = listener;
                } else {
                    // Warn that the listener is being replaced while active
                    Log.w(TAG, "setListener is being called when there is already an active "
                            + "listener");
                    mListener = listener;
                }
            }
        }

        @Override
        public void requestImsExternalCallStateInfo() throws RemoteException {
            ImsMultiEndpointImplBase.this.requestImsExternalCallStateInfo();
        }
    };

    /** @hide */
    public IImsMultiEndpoint getIImsMultiEndpoint() {
        return mImsMultiEndpoint;
    }

    /**
     * Notifies framework when Dialog Event Package update is received
     *
     * @throws RuntimeException if the connection to the framework is not available.
     */
    public final void onImsExternalCallStateUpdate(List<ImsExternalCallState> externalCallDialogs) {
        Log.d(TAG, "ims external call state update triggered.");
        IImsExternalCallStateListener listener;
        synchronized (mLock) {
            listener = mListener;
        }
        if (listener != null) {
            try {
                listener.onImsExternalCallStateUpdate(externalCallDialogs);
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * This method should be implemented by the IMS provider. Framework will trigger this to get the
     * latest Dialog Event Package information. Should
     */
    public void requestImsExternalCallStateInfo() {
        Log.d(TAG, "requestImsExternalCallStateInfo() not implemented");
    }
}
