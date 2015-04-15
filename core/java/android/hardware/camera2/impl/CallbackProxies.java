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

import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.dispatch.Dispatchable;
import android.hardware.camera2.dispatch.MethodNameInvoker;
import android.view.Surface;

import static com.android.internal.util.Preconditions.*;

/**
 * Proxy out invocations to the camera2 API callbacks into a {@link Dispatchable}.
 *
 * <p>Since abstract classes do not support Java's dynamic {@code Proxy}, we have to
 * to use our own proxy mechanism.</p>
 */
public class CallbackProxies {

    // TODO: replace with codegen

    public static class DeviceStateCallbackProxy extends CameraDeviceImpl.StateCallbackKK {
        private final MethodNameInvoker<CameraDeviceImpl.StateCallbackKK> mProxy;

        public DeviceStateCallbackProxy(
                Dispatchable<CameraDeviceImpl.StateCallbackKK> dispatchTarget) {
            dispatchTarget = checkNotNull(dispatchTarget, "dispatchTarget must not be null");
            mProxy = new MethodNameInvoker<>(dispatchTarget, CameraDeviceImpl.StateCallbackKK.class);
        }

        @Override
        public void onOpened(CameraDevice camera) {
            mProxy.invoke("onOpened", camera);
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            mProxy.invoke("onDisconnected", camera);
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            mProxy.invoke("onError", camera, error);
        }

        @Override
        public void onUnconfigured(CameraDevice camera) {
            mProxy.invoke("onUnconfigured", camera);
        }

        @Override
        public void onActive(CameraDevice camera) {
            mProxy.invoke("onActive", camera);
        }

        @Override
        public void onBusy(CameraDevice camera) {
            mProxy.invoke("onBusy", camera);
        }

        @Override
        public void onClosed(CameraDevice camera) {
            mProxy.invoke("onClosed", camera);
        }

        @Override
        public void onIdle(CameraDevice camera) {
            mProxy.invoke("onIdle", camera);
        }
    }

    @SuppressWarnings("deprecation")
    public static class DeviceCaptureCallbackProxy extends CameraDeviceImpl.CaptureCallback {
        private final MethodNameInvoker<CameraDeviceImpl.CaptureCallback> mProxy;

        public DeviceCaptureCallbackProxy(
                Dispatchable<CameraDeviceImpl.CaptureCallback> dispatchTarget) {
            dispatchTarget = checkNotNull(dispatchTarget, "dispatchTarget must not be null");
            mProxy = new MethodNameInvoker<>(dispatchTarget, CameraDeviceImpl.CaptureCallback.class);
        }

        @Override
        public void onCaptureStarted(CameraDevice camera,
                CaptureRequest request, long timestamp, long frameNumber) {
            mProxy.invoke("onCaptureStarted", camera, request, timestamp, frameNumber);
        }

        @Override
        public void onCapturePartial(CameraDevice camera,
                CaptureRequest request, CaptureResult result) {
            mProxy.invoke("onCapturePartial", camera, request, result);
        }

        @Override
        public void onCaptureProgressed(CameraDevice camera,
                CaptureRequest request, CaptureResult partialResult) {
            mProxy.invoke("onCaptureProgressed", camera, request, partialResult);
        }

        @Override
        public void onCaptureCompleted(CameraDevice camera,
                CaptureRequest request, TotalCaptureResult result) {
            mProxy.invoke("onCaptureCompleted", camera, request, result);
        }

        @Override
        public void onCaptureFailed(CameraDevice camera,
                CaptureRequest request, CaptureFailure failure) {
            mProxy.invoke("onCaptureFailed", camera, request, failure);
        }

        @Override
        public void onCaptureSequenceCompleted(CameraDevice camera,
                int sequenceId, long frameNumber) {
            mProxy.invoke("onCaptureSequenceCompleted", camera, sequenceId, frameNumber);
        }

        @Override
        public void onCaptureSequenceAborted(CameraDevice camera,
                int sequenceId) {
            mProxy.invoke("onCaptureSequenceAborted", camera, sequenceId);
        }
    }

    public static class SessionStateCallbackProxy
            extends CameraCaptureSession.StateCallback {
        private final MethodNameInvoker<CameraCaptureSession.StateCallback> mProxy;

        public SessionStateCallbackProxy(
                Dispatchable<CameraCaptureSession.StateCallback> dispatchTarget) {
            dispatchTarget = checkNotNull(dispatchTarget, "dispatchTarget must not be null");
            mProxy = new MethodNameInvoker<>(dispatchTarget,
                    CameraCaptureSession.StateCallback.class);
        }

        @Override
        public void onConfigured(CameraCaptureSession session) {
            mProxy.invoke("onConfigured", session);
        }


        @Override
        public void onConfigureFailed(CameraCaptureSession session) {
            mProxy.invoke("onConfigureFailed", session);
        }

        @Override
        public void onReady(CameraCaptureSession session) {
            mProxy.invoke("onReady", session);
        }

        @Override
        public void onActive(CameraCaptureSession session) {
            mProxy.invoke("onActive", session);
        }

        @Override
        public void onClosed(CameraCaptureSession session) {
            mProxy.invoke("onClosed", session);
        }

        @Override
        public void onSurfacePrepared(CameraCaptureSession session, Surface surface) {
            mProxy.invoke("onSurfacePrepared", session, surface);
        }

    }

    private CallbackProxies() {
        throw new AssertionError();
    }
}
