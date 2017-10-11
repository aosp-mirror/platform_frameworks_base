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

/**
 * @hide
 */
public class InternalDownloadStateCallback extends IDownloadStateCallback.Stub {
    private final Handler mHandler;
    private final DownloadStateCallback mAppCallback;
    private volatile boolean mIsStopped = false;

    public InternalDownloadStateCallback(DownloadStateCallback appCallback, Handler handler) {
        mAppCallback = appCallback;
        mHandler = handler;
    }

    @Override
    public void onProgressUpdated(final DownloadRequest request, final FileInfo fileInfo,
            final int currentDownloadSize, final int fullDownloadSize, final int currentDecodedSize,
            final int fullDecodedSize) throws RemoteException {
        if (mIsStopped) {
            return;
        }

        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mAppCallback.onProgressUpdated(request, fileInfo, currentDownloadSize,
                        fullDownloadSize, currentDecodedSize, fullDecodedSize);
            }
        });
    }

    @Override
    public void onStateUpdated(final DownloadRequest request, final FileInfo fileInfo,
            final int state) throws RemoteException {
        if (mIsStopped) {
            return;
        }

        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mAppCallback.onStateUpdated(request, fileInfo, state);
            }
        });
    }

    public void stop() {
        mIsStopped = true;
    }
}
