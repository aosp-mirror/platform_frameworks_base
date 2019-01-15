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

import java.util.List;
import java.util.concurrent.Executor;

/** @hide */
public class InternalDownloadSessionCallback extends IMbmsDownloadSessionCallback.Stub {

    private final Executor mExecutor;
    private final MbmsDownloadSessionCallback mAppCallback;
    private volatile boolean mIsStopped = false;

    public InternalDownloadSessionCallback(MbmsDownloadSessionCallback appCallback,
            Executor executor) {
        mAppCallback = appCallback;
        mExecutor = executor;
    }

    @Override
    public void onError(final int errorCode, final String message) {
        if (mIsStopped) {
            return;
        }

        long token = Binder.clearCallingIdentity();
        try {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    mAppCallback.onError(errorCode, message);
                }
            });
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public void onFileServicesUpdated(final List<FileServiceInfo> services) {
        if (mIsStopped) {
            return;
        }

        long token = Binder.clearCallingIdentity();
        try {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    mAppCallback.onFileServicesUpdated(services);
                }
            });
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public void onMiddlewareReady() {
        if (mIsStopped) {
            return;
        }

        long token = Binder.clearCallingIdentity();
        try {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    mAppCallback.onMiddlewareReady();
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
