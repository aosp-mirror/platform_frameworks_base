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

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.RemoteException;
import android.telephony.ims.ImsExternalCallState;
import android.util.Log;

import com.android.ims.internal.IImsExternalCallStateListener;
import com.android.ims.internal.IImsMultiEndpoint;
import com.android.internal.telephony.util.TelephonyUtils;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

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
    private Executor mExecutor = Runnable::run;

    private final IImsMultiEndpoint mImsMultiEndpoint = new IImsMultiEndpoint.Stub() {

        @Override
        public void setListener(IImsExternalCallStateListener listener) throws RemoteException {
            executeMethodAsync(() -> {
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
            }, "setListener");
        }

        @Override
        public void requestImsExternalCallStateInfo() throws RemoteException {
            executeMethodAsync(() -> ImsMultiEndpointImplBase.this
                    .requestImsExternalCallStateInfo(), "requestImsExternalCallStateInfo");
        }

        // Call the methods with a clean calling identity on the executor and wait indefinitely for
        // the future to return.
        private void executeMethodAsync(Runnable r, String errorLogName) {
            try {
                CompletableFuture.runAsync(
                        () -> TelephonyUtils.runWithCleanCallingIdentity(r), mExecutor).join();
            } catch (CancellationException | CompletionException e) {
                Log.w(TAG, "ImsMultiEndpointImplBase Binder - " + errorLogName + " exception: "
                        + e.getMessage());
            }
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

    /**
     * Set default Executor from MmTelFeature.
     * @param executor The default executor for the framework to use when executing the methods
     * overridden by the implementation of ImsMultiEndpoint.
     * @hide
     */
    public final void setDefaultExecutor(@NonNull Executor executor) {
        mExecutor = executor;
    }
}
