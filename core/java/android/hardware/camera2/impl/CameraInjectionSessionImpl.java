/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.hardware.camera2.impl;

import static com.android.internal.util.function.pooled.PooledLambda.obtainRunnable;

import android.hardware.camera2.CameraInjectionSession;
import android.hardware.camera2.ICameraInjectionCallback;
import android.hardware.camera2.ICameraInjectionSession;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.util.concurrent.Executor;


/**
 * The class inherits CameraInjectionSession. Use CameraManager#injectCamera to instantiate.
 */
public class CameraInjectionSessionImpl extends CameraInjectionSession
        implements IBinder.DeathRecipient {
    private static final String TAG = "CameraInjectionSessionImpl";

    private final CameraInjectionCallback mCallback = new CameraInjectionCallback();
    private final CameraInjectionSession.InjectionStatusCallback mInjectionStatusCallback;
    private final Executor mExecutor;
    private final Object mInterfaceLock = new Object();
    private ICameraInjectionSession mInjectionSession;

    public CameraInjectionSessionImpl(InjectionStatusCallback callback, Executor executor) {
        mInjectionStatusCallback = callback;
        mExecutor = executor;
    }

    @Override
    public void close() {
        synchronized (mInterfaceLock) {
            try {
                if (mInjectionSession != null) {
                    mInjectionSession.stopInjection();
                    mInjectionSession.asBinder().unlinkToDeath(this, /*flags*/0);
                    mInjectionSession = null;
                }
            } catch (RemoteException e) {
                // Ignore binder errors for disconnect
            }
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            close();
        } finally {
            super.finalize();
        }
    }

    @Override
    public void binderDied() {
        synchronized (mInterfaceLock) {
            Log.w(TAG, "CameraInjectionSessionImpl died unexpectedly");

            if (mInjectionSession == null) {
                return; // CameraInjectionSession already closed
            }

            Runnable r = new Runnable() {
                @Override
                public void run() {
                    mInjectionStatusCallback.onInjectionError(
                            CameraInjectionSession.InjectionStatusCallback.ERROR_INJECTION_SERVICE);
                }
            };
            final long ident = Binder.clearCallingIdentity();
            try {
                CameraInjectionSessionImpl.this.mExecutor.execute(r);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    public CameraInjectionCallback getCallback() {
        return mCallback;
    }

    /**
     * Set remote injection session, which triggers initial onInjectionSucceeded callbacks.
     *
     * <p>This function may post onInjectionError if remoteInjectionSession dies
     * during injecting.</p>
     */
    public void setRemoteInjectionSession(ICameraInjectionSession injectionSession) {
        synchronized (mInterfaceLock) {
            if (injectionSession == null) {
                Log.e(TAG, "The camera injection session has encountered a serious error");
                scheduleNotifyError(
                        CameraInjectionSession.InjectionStatusCallback.ERROR_INJECTION_SESSION);
                return;
            }

            mInjectionSession = injectionSession;

            IBinder remoteSessionBinder = injectionSession.asBinder();
            if (remoteSessionBinder == null) {
                Log.e(TAG, "The camera injection session has encountered a serious error");
                scheduleNotifyError(
                        CameraInjectionSession.InjectionStatusCallback.ERROR_INJECTION_SESSION);
                return;
            }

            final long ident = Binder.clearCallingIdentity();
            try {
                remoteSessionBinder.linkToDeath(this, /*flag*/ 0);
                mExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        mInjectionStatusCallback
                                .onInjectionSucceeded(CameraInjectionSessionImpl.this);
                    }
                });
            } catch (RemoteException e) {
                scheduleNotifyError(
                        CameraInjectionSession.InjectionStatusCallback.ERROR_INJECTION_SESSION);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    /**
     * The method called when the injection camera has encountered a serious error.
     *
     * @param errorCode The error code.
     * @see #ERROR_INJECTION_SESSION
     * @see #ERROR_INJECTION_SERVICE
     * @see #ERROR_INJECTION_UNSUPPORTED
     */
    public void onInjectionError(final int errorCode) {
        Log.v(TAG, String.format(
                "Injection session error received, code %d", errorCode));

        synchronized (mInterfaceLock) {
            if (mInjectionSession == null) {
                return; // mInjectionSession already closed
            }

            switch (errorCode) {
                case CameraInjectionCallback.ERROR_INJECTION_SESSION:
                    scheduleNotifyError(
                            CameraInjectionSession.InjectionStatusCallback.ERROR_INJECTION_SESSION);
                    break;
                case CameraInjectionCallback.ERROR_INJECTION_SERVICE:
                    scheduleNotifyError(
                            CameraInjectionSession.InjectionStatusCallback.ERROR_INJECTION_SERVICE);
                    break;
                case CameraInjectionCallback.ERROR_INJECTION_UNSUPPORTED:
                    scheduleNotifyError(
                            CameraInjectionSession.InjectionStatusCallback
                                    .ERROR_INJECTION_UNSUPPORTED);
                    break;
                default:
                    Log.e(TAG, "Unknown error from injection session: " + errorCode);
                    scheduleNotifyError(
                            CameraInjectionSession.InjectionStatusCallback.ERROR_INJECTION_SERVICE);
            }
        }
    }

    private void scheduleNotifyError(final int errorCode) {
        final long ident = Binder.clearCallingIdentity();
        try {
            mExecutor.execute(obtainRunnable(
                    CameraInjectionSessionImpl::notifyError,
                    this, errorCode).recycleOnUse());
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void notifyError(final int errorCode) {
        if (mInjectionSession != null) {
            mInjectionStatusCallback.onInjectionError(errorCode);
        }
    }

    /**
     * The class inherits ICameraInjectionCallbacks.Stub. Use CameraManager#injectCamera to
     * instantiate.
     */
    public class CameraInjectionCallback extends ICameraInjectionCallback.Stub {

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        public void onInjectionError(int errorCode) {
            CameraInjectionSessionImpl.this.onInjectionError(errorCode);
        }
    }
}
