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
public class InternalDownloadManagerCallback extends IMbmsDownloadManagerCallback.Stub {

    private final Handler mHandler;
    private final MbmsDownloadManagerCallback mAppCallback;

    public InternalDownloadManagerCallback(MbmsDownloadManagerCallback appCallback,
            Handler handler) {
        mAppCallback = appCallback;
        mHandler = handler;
    }

    @Override
    public void error(final int errorCode, final String message) throws RemoteException {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mAppCallback.onError(errorCode, message);
            }
        });
    }

    @Override
    public void fileServicesUpdated(final List<FileServiceInfo> services) throws RemoteException {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mAppCallback.onFileServicesUpdated(services);
            }
        });
    }

    @Override
    public void middlewareReady() throws RemoteException {
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
}
