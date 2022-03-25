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
import android.util.Log;

import com.android.ims.internal.IImsEcbm;
import com.android.ims.internal.IImsEcbmListener;
import com.android.internal.telephony.util.TelephonyUtils;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;


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

    private final Object mLock = new Object();
    private IImsEcbmListener mListener;
    private Executor mExecutor = Runnable::run;

    private final IImsEcbm mImsEcbm = new IImsEcbm.Stub() {
        @Override
        public void setListener(IImsEcbmListener listener) {
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
        public void exitEmergencyCallbackMode() {
            executeMethodAsync(() -> ImsEcbmImplBase.this.exitEmergencyCallbackMode(),
                    "exitEmergencyCallbackMode");
        }

        // Call the methods with a clean calling identity on the executor and wait indefinitely for
        // the future to return.
        private void executeMethodAsync(Runnable r, String errorLogName) {
            try {
                CompletableFuture.runAsync(
                        () -> TelephonyUtils.runWithCleanCallingIdentity(r), mExecutor).join();
            } catch (CancellationException | CompletionException e) {
                Log.w(TAG, "ImsEcbmImplBase Binder - " + errorLogName + " exception: "
                        + e.getMessage());
            }
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
        IImsEcbmListener listener;
        synchronized (mLock) {
            listener = mListener;
        }
        if (listener != null) {
            try {
                listener.enteredECBM();
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
        IImsEcbmListener listener;
        synchronized (mLock) {
            listener = mListener;
        }
        if (listener != null) {
            try {
                listener.exitedECBM();
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Set default Executor from MmTelFeature.
     * @param executor The default executor for the framework to use when executing the methods
     * overridden by the implementation of ImsEcbm.
     * @hide
     */
    public final void setDefaultExecutor(@NonNull Executor executor) {
        mExecutor = executor;
    }
}
