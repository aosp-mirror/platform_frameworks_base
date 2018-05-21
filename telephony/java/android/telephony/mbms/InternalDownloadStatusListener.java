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

import android.os.Binder;
import android.os.RemoteException;
import android.telephony.MbmsDownloadSession;

import java.util.concurrent.Executor;

/**
 * @hide
 */
public class InternalDownloadStatusListener extends IDownloadStatusListener.Stub {
    private final Executor mExecutor;
    private final DownloadStatusListener mAppListener;
    private volatile boolean mIsStopped = false;

    public InternalDownloadStatusListener(DownloadStatusListener appCallback, Executor executor) {
        mAppListener = appCallback;
        mExecutor = executor;
    }

    @Override
    public void onStatusUpdated(final DownloadRequest request, final FileInfo fileInfo,
            @MbmsDownloadSession.DownloadStatus final int status) throws RemoteException {
        if (mIsStopped) {
            return;
        }

        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                long token = Binder.clearCallingIdentity();
                try {
                    mAppListener.onStatusUpdated(request, fileInfo, status);
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        });
    }

    public void stop() {
        mIsStopped = true;
    }
}
