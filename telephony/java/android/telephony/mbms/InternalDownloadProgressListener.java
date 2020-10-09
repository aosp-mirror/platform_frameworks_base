/*
 * Copyright (C) 2018 The Android Open Source Project
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

import java.util.concurrent.Executor;

/**
 * @hide
 */
public class InternalDownloadProgressListener extends IDownloadProgressListener.Stub {
    private final Executor mExecutor;
    private final DownloadProgressListener mAppListener;
    private volatile boolean mIsStopped = false;

    public InternalDownloadProgressListener(DownloadProgressListener appListener,
            Executor executor) {
        mAppListener = appListener;
        mExecutor = executor;
    }

    @Override
    public void onProgressUpdated(final DownloadRequest request, final FileInfo fileInfo,
            final int currentDownloadSize, final int fullDownloadSize, final int currentDecodedSize,
            final int fullDecodedSize) throws RemoteException {
        if (mIsStopped) {
            return;
        }

        final long token = Binder.clearCallingIdentity();
        try {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    mAppListener.onProgressUpdated(request, fileInfo, currentDownloadSize,
                            fullDownloadSize, currentDecodedSize, fullDecodedSize);
                }
            });
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void stop() {
        mIsStopped = true;
    }
}
