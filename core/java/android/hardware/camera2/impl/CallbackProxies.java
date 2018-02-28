/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.os.Binder;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.view.Surface;

import java.util.concurrent.Executor;

import static com.android.internal.util.Preconditions.*;

/**
 * Proxy out invocations to the camera2 API callbacks into a {@link Dispatchable}.
 *
 * <p>Since abstract classes do not support Java's dynamic {@code Proxy}, we have to
 * to use our own proxy mechanism.</p>
 */
public class CallbackProxies {
    public static class SessionStateCallbackProxy
            extends CameraCaptureSession.StateCallback {
        private final Executor mExecutor;
        private final CameraCaptureSession.StateCallback mCallback;

        public SessionStateCallbackProxy(Executor executor,
                CameraCaptureSession.StateCallback callback) {
            mExecutor = checkNotNull(executor, "executor must not be null");
            mCallback = checkNotNull(callback, "callback must not be null");
        }

        @Override
        public void onConfigured(CameraCaptureSession session) {
            final long ident = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> mCallback.onConfigured(session));
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }


        @Override
        public void onConfigureFailed(CameraCaptureSession session) {
            final long ident = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> mCallback.onConfigureFailed(session));
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void onReady(CameraCaptureSession session) {
            final long ident = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> mCallback.onReady(session));
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void onActive(CameraCaptureSession session) {
            final long ident = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> mCallback.onActive(session));
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void onCaptureQueueEmpty(CameraCaptureSession session) {
            final long ident = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> mCallback.onCaptureQueueEmpty(session));
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void onClosed(CameraCaptureSession session) {
            final long ident = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> mCallback.onClosed(session));
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void onSurfacePrepared(CameraCaptureSession session, Surface surface) {
            final long ident = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> mCallback.onSurfacePrepared(session, surface));
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

    }

    private CallbackProxies() {
        throw new AssertionError();
    }
}
