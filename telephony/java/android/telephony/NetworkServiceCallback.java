/*
 * Copyright 2017 The Android Open Source Project
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

package android.telephony;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.RemoteException;
import android.telephony.NetworkService.NetworkServiceProvider;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;

/**
 * Network service callback. Object of this class is passed to NetworkServiceProvider upon
 * calling getNetworkRegistrationInfo, to receive asynchronous feedback from NetworkServiceProvider
 * upon onGetNetworkRegistrationInfoComplete. It's like a wrapper of INetworkServiceCallback
 * because INetworkServiceCallback can't be a parameter type in public APIs.
 *
 * @hide
 */
@SystemApi
public class NetworkServiceCallback {

    private static final String mTag = NetworkServiceCallback.class.getSimpleName();

    /**
     * Result of network requests
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({RESULT_SUCCESS, RESULT_ERROR_UNSUPPORTED, RESULT_ERROR_INVALID_ARG, RESULT_ERROR_BUSY,
            RESULT_ERROR_ILLEGAL_STATE, RESULT_ERROR_FAILED})
    public @interface Result {}

    /** Request is completed successfully */
    public static final int RESULT_SUCCESS              = 0;
    /** Request is not support */
    public static final int RESULT_ERROR_UNSUPPORTED    = 1;
    /** Request contains invalid arguments */
    public static final int RESULT_ERROR_INVALID_ARG    = 2;
    /** Service is busy */
    public static final int RESULT_ERROR_BUSY           = 3;
    /** Request sent in illegal state */
    public static final int RESULT_ERROR_ILLEGAL_STATE  = 4;
    /** Request failed */
    public static final int RESULT_ERROR_FAILED         = 5;

    private final WeakReference<INetworkServiceCallback> mCallback;

    /** @hide */
    public NetworkServiceCallback(INetworkServiceCallback callback) {
        mCallback = new WeakReference<>(callback);
    }

    /**
     * Called to indicate result of
     * {@link NetworkServiceProvider#getNetworkRegistrationInfo(int, NetworkServiceCallback)}
     *
     * @param result Result status like {@link NetworkServiceCallback#RESULT_SUCCESS} or
     *                {@link NetworkServiceCallback#RESULT_ERROR_UNSUPPORTED}
     * @param state The state information to be returned to callback.
     */
    public void onGetNetworkRegistrationInfoComplete(int result,
                                                      @Nullable NetworkRegistrationInfo state) {
        INetworkServiceCallback callback = mCallback.get();
        if (callback != null) {
            try {
                callback.onGetNetworkRegistrationInfoComplete(result, state);
            } catch (RemoteException e) {
                Rlog.e(mTag, "Failed to onGetNetworkRegistrationInfoComplete on the remote");
            }
        } else {
            Rlog.e(mTag, "Weak reference of callback is null.");
        }
    }
}