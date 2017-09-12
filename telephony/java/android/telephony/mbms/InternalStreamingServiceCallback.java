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

/** @hide */
public class InternalStreamingServiceCallback extends IStreamingServiceCallback.Stub {
    private final StreamingServiceCallback mAppCallback;
    private final Handler mHandler;

    public InternalStreamingServiceCallback(StreamingServiceCallback appCallback, Handler handler) {
        mAppCallback = appCallback;
        mHandler = handler;
    }

    @Override
    public void error(int errorCode, String message) throws RemoteException {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mAppCallback.onError(errorCode, message);
            }
        });
    }

    @Override
    public void streamStateUpdated(int state, int reason) throws RemoteException {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mAppCallback.onStreamStateUpdated(state, reason);
            }
        });
    }

    @Override
    public void mediaDescriptionUpdated() throws RemoteException {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mAppCallback.onMediaDescriptionUpdated();
            }
        });
    }

    @Override
    public void broadcastSignalStrengthUpdated(int signalStrength) throws RemoteException {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mAppCallback.onBroadcastSignalStrengthUpdated(signalStrength);
            }
        });
    }

    @Override
    public void streamMethodUpdated(int methodType) throws RemoteException {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mAppCallback.onStreamMethodUpdated(methodType);
            }
        });
    }
}
