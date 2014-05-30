package android.hardware.camera2.impl;

import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.dispatch.Dispatchable;
import android.hardware.camera2.dispatch.MethodNameInvoker;

import static com.android.internal.util.Preconditions.*;

/**
 * Proxy out invocations to the camera2 API listeners into a {@link Dispatchable}.
 *
 * <p>Since abstract classes do not support Java's dynamic {@code Proxy}, we have to
 * to use our own proxy mechanism.</p>
 */
public class ListenerProxies {

    // TODO: replace with codegen

    public static class DeviceStateListenerProxy extends CameraDevice.StateListener {
        private final MethodNameInvoker<CameraDevice.StateListener> mProxy;

        public DeviceStateListenerProxy(
                Dispatchable<CameraDevice.StateListener> dispatchTarget) {
            dispatchTarget = checkNotNull(dispatchTarget, "dispatchTarget must not be null");
            mProxy = new MethodNameInvoker<>(dispatchTarget, CameraDevice.StateListener.class);
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
    public static class DeviceCaptureListenerProxy extends CameraDevice.CaptureListener {
        private final MethodNameInvoker<CameraDevice.CaptureListener> mProxy;

        public DeviceCaptureListenerProxy(
                Dispatchable<CameraDevice.CaptureListener> dispatchTarget) {
            dispatchTarget = checkNotNull(dispatchTarget, "dispatchTarget must not be null");
            mProxy = new MethodNameInvoker<>(dispatchTarget, CameraDevice.CaptureListener.class);
        }

        @Override
        public void onCaptureStarted(CameraDevice camera,
                CaptureRequest request, long timestamp) {
            mProxy.invoke("onCaptureStarted", camera, request, timestamp);
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

    public static class SessionStateListenerProxy
            extends CameraCaptureSession.StateListener {
        private final MethodNameInvoker<CameraCaptureSession.StateListener> mProxy;

        public SessionStateListenerProxy(
                Dispatchable<CameraCaptureSession.StateListener> dispatchTarget) {
            dispatchTarget = checkNotNull(dispatchTarget, "dispatchTarget must not be null");
            mProxy = new MethodNameInvoker<>(dispatchTarget,
                    CameraCaptureSession.StateListener.class);
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
    }

    private ListenerProxies() {
        throw new AssertionError();
    }
}
