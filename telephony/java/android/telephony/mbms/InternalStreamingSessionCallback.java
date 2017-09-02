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

package android.telephony.mbms;

import android.os.Handler;
import android.os.RemoteException;

import java.util.List;

/** @hide */
public class InternalStreamingSessionCallback extends IMbmsStreamingSessionCallback.Stub {
    private final Handler mHandler;
    private final MbmsStreamingSessionCallback mAppCallback;
    private volatile boolean mIsStopped = false;

    public InternalStreamingSessionCallback(MbmsStreamingSessionCallback appCallback,
            Handler handler) {
        mAppCallback = appCallback;
        mHandler = handler;
    }

    @Override
    public void onError(final int errorCode, final String message) throws RemoteException {
        if (mIsStopped) {
            return;
        }

        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mAppCallback.onError(errorCode, message);
            }
        });
    }

    @Override
    public void onStreamingServicesUpdated(final List<StreamingServiceInfo> services)
            throws RemoteException {
        if (mIsStopped) {
            return;
        }

        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mAppCallback.onStreamingServicesUpdated(services);
            }
        });
    }

    @Override
    public void onMiddlewareReady() throws RemoteException {
        if (mIsStopped) {
            return;
        }

        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mAppCallback.onMiddlewareReady();
            }
        });
    }

    public Handler getHandler() {
        return mHandler;
    }

    public void stop() {
        mIsStopped = true;
    }
}
