/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraOfflineSession;
import android.hardware.camera2.CameraOfflineSession.CameraOfflineSessionCallback;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.ICameraDeviceCallbacks;
import android.hardware.camera2.ICameraOfflineSession;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.InputConfiguration;
import android.hardware.camera2.params.OutputConfiguration;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.util.Range;
import android.util.SparseArray;
import android.view.Surface;

import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Executor;

import static com.android.internal.util.Preconditions.*;

public class CameraOfflineSessionImpl extends CameraOfflineSession
        implements IBinder.DeathRecipient {
    private static final String TAG = "CameraOfflineSessionImpl";
    private static final int REQUEST_ID_NONE = -1;
    private static final long NANO_PER_SECOND = 1000000000; //ns
    private final boolean DEBUG = false;

    private ICameraOfflineSession mRemoteSession;
    private final AtomicBoolean mClosing = new AtomicBoolean();

    private SimpleEntry<Integer, InputConfiguration> mOfflineInput =
            new SimpleEntry<>(REQUEST_ID_NONE, null);
    private SparseArray<OutputConfiguration> mOfflineOutputs = new SparseArray<>();
    private SparseArray<OutputConfiguration> mConfiguredOutputs = new SparseArray<>();

    final Object mInterfaceLock = new Object(); // access from this class and Session only!

    private final String mCameraId;
    private final CameraCharacteristics mCharacteristics;
    private final int mTotalPartialCount;

    private final Executor mOfflineExecutor;
    private final CameraOfflineSessionCallback mOfflineCallback;

    private final CameraDeviceCallbacks mCallbacks = new CameraDeviceCallbacks();

    /**
     * A list tracking request and its expected last regular/reprocess/zslStill frame
     * number.
     */
    private List<RequestLastFrameNumbersHolder> mOfflineRequestLastFrameNumbersList =
            new ArrayList<>();

    /**
     * An object tracking received frame numbers.
     * Updated when receiving callbacks from ICameraDeviceCallbacks.
     */
    private FrameNumberTracker mFrameNumberTracker = new FrameNumberTracker();

    /** map request IDs to callback/request data */
    private SparseArray<CaptureCallbackHolder> mCaptureCallbackMap =
            new SparseArray<CaptureCallbackHolder>();

    public CameraOfflineSessionImpl(String cameraId, CameraCharacteristics characteristics,
            Executor offlineExecutor, CameraOfflineSessionCallback offlineCallback,
            SparseArray<OutputConfiguration> offlineOutputs,
            SimpleEntry<Integer, InputConfiguration> offlineInput,
            SparseArray<OutputConfiguration> configuredOutputs,
            FrameNumberTracker frameNumberTracker, SparseArray<CaptureCallbackHolder> callbackMap,
            List<RequestLastFrameNumbersHolder> frameNumberList) {
        if ((cameraId == null) || (characteristics == null)) {
            throw new IllegalArgumentException("Null argument given");
        }

        mCameraId = cameraId;
        mCharacteristics = characteristics;

        Integer partialCount =
                mCharacteristics.get(CameraCharacteristics.REQUEST_PARTIAL_RESULT_COUNT);
        if (partialCount == null) {
            // 1 means partial result is not supported.
            mTotalPartialCount = 1;
        } else {
            mTotalPartialCount = partialCount;
        }

        mOfflineRequestLastFrameNumbersList.addAll(frameNumberList);
        mFrameNumberTracker = frameNumberTracker;
        mCaptureCallbackMap = callbackMap;
        mConfiguredOutputs = configuredOutputs;
        mOfflineOutputs = offlineOutputs;
        mOfflineInput = offlineInput;
        mOfflineExecutor = checkNotNull(offlineExecutor, "offline executor must not be null");
        mOfflineCallback = checkNotNull(offlineCallback, "offline callback must not be null");

    }

    public CameraDeviceCallbacks getCallbacks() {
        return mCallbacks;
    }

    public class CameraDeviceCallbacks extends ICameraDeviceCallbacks.Stub {
        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        public void onDeviceError(final int errorCode, CaptureResultExtras resultExtras) {
            synchronized(mInterfaceLock) {

                switch (errorCode) {
                    case CameraDeviceCallbacks.ERROR_CAMERA_REQUEST:
                    case CameraDeviceCallbacks.ERROR_CAMERA_RESULT:
                    case CameraDeviceCallbacks.ERROR_CAMERA_BUFFER:
                        onCaptureErrorLocked(errorCode, resultExtras);
                        break;
                    default: {
                        Runnable errorDispatch = new Runnable() {
                            @Override
                            public void run() {
                                if (!isClosed()) {
                                    mOfflineCallback.onError(CameraOfflineSessionImpl.this,
                                            CameraOfflineSessionCallback.STATUS_INTERNAL_ERROR);
                                }
                            }
                        };

                        final long ident = Binder.clearCallingIdentity();
                        try {
                            mOfflineExecutor.execute(errorDispatch);
                        } finally {
                            Binder.restoreCallingIdentity(ident);
                        }
                    }
                }
            }
        }

        @Override
        public void onRepeatingRequestError(long lastFrameNumber, int repeatingRequestId) {
            Log.e(TAG, "Unexpected repeating request error received. Last frame number is " +
                    lastFrameNumber);
        }

        @Override
        public void onDeviceIdle() {
            synchronized(mInterfaceLock) {
                if (mRemoteSession == null) {
                    Log.v(TAG, "Ignoring idle state notifications during offline switches");
                    return;
                }

                // Remove all capture callbacks now that device has gone to IDLE state.
                removeCompletedCallbackHolderLocked(
                        Long.MAX_VALUE, /*lastCompletedRegularFrameNumber*/
                        Long.MAX_VALUE, /*lastCompletedReprocessFrameNumber*/
                        Long.MAX_VALUE /*lastCompletedZslStillFrameNumber*/);

                Runnable idleDispatch = new Runnable() {
                    @Override
                    public void run() {
                        if (!isClosed()) {
                            mOfflineCallback.onIdle(CameraOfflineSessionImpl.this);
                        }
                    }
                };

                final long ident = Binder.clearCallingIdentity();
                try {
                    mOfflineExecutor.execute(idleDispatch);
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }

        @Override
        public void onCaptureStarted(final CaptureResultExtras resultExtras, final long timestamp) {
            int requestId = resultExtras.getRequestId();
            final long frameNumber = resultExtras.getFrameNumber();
            final long lastCompletedRegularFrameNumber =
                    resultExtras.getLastCompletedRegularFrameNumber();
            final long lastCompletedReprocessFrameNumber =
                    resultExtras.getLastCompletedReprocessFrameNumber();
            final long lastCompletedZslFrameNumber =
                    resultExtras.getLastCompletedZslFrameNumber();

            final CaptureCallbackHolder holder;

            synchronized(mInterfaceLock) {
                // Check if it's okay to remove completed callbacks from mCaptureCallbackMap.
                // A callback is completed if the corresponding inflight request has been removed
                // from the inflight queue in cameraservice.
                removeCompletedCallbackHolderLocked(lastCompletedRegularFrameNumber,
                        lastCompletedReprocessFrameNumber, lastCompletedZslFrameNumber);

                // Get the callback for this frame ID, if there is one
                holder = CameraOfflineSessionImpl.this.mCaptureCallbackMap.get(requestId);

                if (holder == null) {
                    return;
                }

                final Executor executor = holder.getCallback().getExecutor();
                if (isClosed() || (executor == null)) return;

                // Dispatch capture start notice
                final long ident = Binder.clearCallingIdentity();
                try {
                    executor.execute(
                        new Runnable() {
                            @Override
                            public void run() {
                                final CameraCaptureSession.CaptureCallback callback =
                                        holder.getCallback().getSessionCallback();
                                if (!CameraOfflineSessionImpl.this.isClosed() &&
                                        (callback != null)) {
                                    final int subsequenceId = resultExtras.getSubsequenceId();
                                    final CaptureRequest request = holder.getRequest(subsequenceId);

                                    if (holder.hasBatchedOutputs()) {
                                        // Send derived onCaptureStarted for requests within the
                                        // batch
                                        final Range<Integer> fpsRange =
                                                request.get(
                                                        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE);
                                        for (int i = 0; i < holder.getRequestCount(); i++) {
                                            final CaptureRequest cbRequest = holder.getRequest(i);
                                            final long cbTimestamp =
                                                        timestamp - (subsequenceId - i) *
                                                        NANO_PER_SECOND/fpsRange.getUpper();
                                            final long cbFrameNumber =
                                                    frameNumber - (subsequenceId - i);
                                            callback.onCaptureStarted(CameraOfflineSessionImpl.this,
                                                    cbRequest, cbTimestamp, cbFrameNumber);
                                        }
                                    } else {
                                        callback.onCaptureStarted(CameraOfflineSessionImpl.this,
                                                holder.getRequest(
                                                    resultExtras.getSubsequenceId()),
                                                timestamp, frameNumber);
                                    }
                                }
                            }
                        });
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }

        @Override
        public void onResultReceived(CameraMetadataNative result,
                CaptureResultExtras resultExtras, PhysicalCaptureResultInfo physicalResults[])
                throws RemoteException {

            int requestId = resultExtras.getRequestId();
            long frameNumber = resultExtras.getFrameNumber();

            synchronized(mInterfaceLock) {
                // TODO: Handle CameraCharacteristics access from CaptureResult correctly.
                result.set(CameraCharacteristics.LENS_INFO_SHADING_MAP_SIZE,
                        mCharacteristics.get(CameraCharacteristics.LENS_INFO_SHADING_MAP_SIZE));

                final CaptureCallbackHolder holder =
                        CameraOfflineSessionImpl.this.mCaptureCallbackMap.get(requestId);
                final CaptureRequest request = holder.getRequest(resultExtras.getSubsequenceId());

                boolean isPartialResult =
                        (resultExtras.getPartialResultCount() < mTotalPartialCount);
                int requestType = request.getRequestType();

                // Check if we have a callback for this
                if (holder == null) {
                    mFrameNumberTracker.updateTracker(frameNumber, /*result*/null, isPartialResult,
                            requestType);

                    return;
                }

                if (isClosed()) {
                    mFrameNumberTracker.updateTracker(frameNumber, /*result*/null, isPartialResult,
                            requestType);
                    return;
                }


                Runnable resultDispatch = null;

                CaptureResult finalResult;
                // Make a copy of the native metadata before it gets moved to a CaptureResult
                // object.
                final CameraMetadataNative resultCopy;
                if (holder.hasBatchedOutputs()) {
                    resultCopy = new CameraMetadataNative(result);
                } else {
                    resultCopy = null;
                }

                final Executor executor = holder.getCallback().getExecutor();
                // Either send a partial result or the final capture completed result
                if (isPartialResult) {
                    final CaptureResult resultAsCapture =
                            new CaptureResult(mCameraId, result, request, resultExtras);
                    // Partial result
                    resultDispatch = new Runnable() {
                        @Override
                        public void run() {
                            final CameraCaptureSession.CaptureCallback callback =
                                    holder.getCallback().getSessionCallback();
                            if (!CameraOfflineSessionImpl.this.isClosed() && (callback != null)) {
                                if (holder.hasBatchedOutputs()) {
                                    // Send derived onCaptureProgressed for requests within
                                    // the batch.
                                    for (int i = 0; i < holder.getRequestCount(); i++) {
                                        CameraMetadataNative resultLocal =
                                                new CameraMetadataNative(resultCopy);
                                        final CaptureResult resultInBatch = new CaptureResult(
                                                mCameraId, resultLocal, holder.getRequest(i),
                                                resultExtras);

                                        final CaptureRequest cbRequest = holder.getRequest(i);
                                        callback.onCaptureProgressed(CameraOfflineSessionImpl.this,
                                                cbRequest, resultInBatch);
                                    }
                                } else {
                                    callback.onCaptureProgressed(CameraOfflineSessionImpl.this,
                                            request, resultAsCapture);
                                }
                            }
                        }
                    };
                    finalResult = resultAsCapture;
                } else {
                    List<CaptureResult> partialResults =
                            mFrameNumberTracker.popPartialResults(frameNumber);

                    final long sensorTimestamp =
                            result.get(CaptureResult.SENSOR_TIMESTAMP);
                    final Range<Integer> fpsRange =
                            request.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE);
                    final int subsequenceId = resultExtras.getSubsequenceId();
                    final TotalCaptureResult resultAsCapture = new TotalCaptureResult(mCameraId,
                            result, request, resultExtras, partialResults, holder.getSessionId(),
                            physicalResults);
                    // Final capture result
                    resultDispatch = new Runnable() {
                        @Override
                        public void run() {
                            final CameraCaptureSession.CaptureCallback callback =
                                    holder.getCallback().getSessionCallback();
                            if (!CameraOfflineSessionImpl.this.isClosed() && (callback != null)) {
                                if (holder.hasBatchedOutputs()) {
                                    // Send derived onCaptureCompleted for requests within
                                    // the batch.
                                    for (int i = 0; i < holder.getRequestCount(); i++) {
                                        resultCopy.set(CaptureResult.SENSOR_TIMESTAMP,
                                                sensorTimestamp - (subsequenceId - i) *
                                                NANO_PER_SECOND/fpsRange.getUpper());
                                        CameraMetadataNative resultLocal =
                                                new CameraMetadataNative(resultCopy);
                                        // No logical multi-camera support for batched output mode.
                                        TotalCaptureResult resultInBatch = new TotalCaptureResult(
                                                mCameraId, resultLocal, holder.getRequest(i),
                                                resultExtras, partialResults, holder.getSessionId(),
                                                new PhysicalCaptureResultInfo[0]);

                                        final CaptureRequest cbRequest = holder.getRequest(i);
                                        callback.onCaptureCompleted(CameraOfflineSessionImpl.this,
                                                cbRequest, resultInBatch);
                                    }
                                } else {
                                    callback.onCaptureCompleted(CameraOfflineSessionImpl.this,
                                            request, resultAsCapture);
                                }
                            }
                        }
                    };
                    finalResult = resultAsCapture;
                }

                if (executor != null) {
                    final long ident = Binder.clearCallingIdentity();
                    try {
                        executor.execute(resultDispatch);
                    } finally {
                        Binder.restoreCallingIdentity(ident);
                    }
                }

                // Collect the partials for a total result; or mark the frame as totally completed
                mFrameNumberTracker.updateTracker(frameNumber, finalResult, isPartialResult,
                        requestType);

                // Fire onCaptureSequenceCompleted
                if (!isPartialResult) {
                    checkAndFireSequenceComplete();
                }
            }
        }

        @Override
        public void onPrepared(int streamId) {
            Log.e(TAG, "Unexpected stream " + streamId + " is prepared");
        }

        @Override
        public void onRequestQueueEmpty() {
            // No-op during offline mode
            Log.v(TAG, "onRequestQueueEmpty");
        }

        /**
         * Called by onDeviceError for handling single-capture failures.
         */
        private void onCaptureErrorLocked(int errorCode, CaptureResultExtras resultExtras) {
            final int requestId = resultExtras.getRequestId();
            final int subsequenceId = resultExtras.getSubsequenceId();
            final long frameNumber = resultExtras.getFrameNumber();
            final String errorPhysicalCameraId = resultExtras.getErrorPhysicalCameraId();
            final CaptureCallbackHolder holder =
                    CameraOfflineSessionImpl.this.mCaptureCallbackMap.get(requestId);

            if (holder == null) {
                Log.e(TAG, String.format("Receive capture error on unknown request ID %d",
                        requestId));
                return;
            }

            final CaptureRequest request = holder.getRequest(subsequenceId);

            Runnable failureDispatch = null;
            if (errorCode == ERROR_CAMERA_BUFFER) {
                // Because 1 stream id could map to multiple surfaces, we need to specify both
                // streamId and surfaceId.
                OutputConfiguration config;
                if ((mRemoteSession == null) && !isClosed()) {
                    config = mConfiguredOutputs.get(resultExtras.getErrorStreamId());
                } else {
                    config = mOfflineOutputs.get(resultExtras.getErrorStreamId());
                }
                if (config == null) {
                    Log.v(TAG, String.format(
                            "Stream %d has been removed. Skipping buffer lost callback",
                            resultExtras.getErrorStreamId()));
                    return;
                }
                for (Surface surface : config.getSurfaces()) {
                    if (!request.containsTarget(surface)) {
                        continue;
                    }
                    final Executor executor = holder.getCallback().getExecutor();
                    failureDispatch = new Runnable() {
                        @Override
                        public void run() {
                            final CameraCaptureSession.CaptureCallback callback =
                                    holder.getCallback().getSessionCallback();
                            if (!CameraOfflineSessionImpl.this.isClosed() && (callback != null)) {
                                callback.onCaptureBufferLost( CameraOfflineSessionImpl.this,
                                        request, surface, frameNumber);
                            }
                        }
                    };
                    if (executor != null) {
                        // Dispatch the failure callback
                        final long ident = Binder.clearCallingIdentity();
                        try {
                            executor.execute(failureDispatch);
                        } finally {
                            Binder.restoreCallingIdentity(ident);
                        }
                    }
                }
            } else {
                boolean mayHaveBuffers = (errorCode == ERROR_CAMERA_RESULT);
                int reason = CaptureFailure.REASON_ERROR;

                final CaptureFailure failure = new CaptureFailure(
                    request,
                    reason,
                    /*dropped*/ mayHaveBuffers,
                    requestId,
                    frameNumber,
                    errorPhysicalCameraId);

                final Executor executor = holder.getCallback().getExecutor();
                failureDispatch = new Runnable() {
                    @Override
                    public void run() {
                        final CameraCaptureSession.CaptureCallback callback =
                                holder.getCallback().getSessionCallback();
                        if (!CameraOfflineSessionImpl.this.isClosed() && (callback != null)) {
                            callback.onCaptureFailed(CameraOfflineSessionImpl.this, request,
                                    failure);
                        }
                    }
                };

                // Fire onCaptureSequenceCompleted if appropriate
                mFrameNumberTracker.updateTracker(frameNumber,
                        /*error*/true, request.getRequestType());
                checkAndFireSequenceComplete();

                if (executor != null) {
                    // Dispatch the failure callback
                    final long ident = Binder.clearCallingIdentity();
                    try {
                        executor.execute(failureDispatch);
                    } finally {
                        Binder.restoreCallingIdentity(ident);
                    }
                }
            }

        }

    }

    private void checkAndFireSequenceComplete() {
        long completedFrameNumber = mFrameNumberTracker.getCompletedFrameNumber();
        long completedReprocessFrameNumber = mFrameNumberTracker.getCompletedReprocessFrameNumber();
        long completedZslStillFrameNumber = mFrameNumberTracker.getCompletedZslStillFrameNumber();
        Iterator<RequestLastFrameNumbersHolder> iter =
                mOfflineRequestLastFrameNumbersList.iterator();
        while (iter.hasNext()) {
            final RequestLastFrameNumbersHolder requestLastFrameNumbers = iter.next();
            boolean sequenceCompleted = false;
            final int requestId = requestLastFrameNumbers.getRequestId();
            final CaptureCallbackHolder holder;
            final Executor executor;
            final CameraCaptureSession.CaptureCallback callback;
            synchronized(mInterfaceLock) {
                int index = mCaptureCallbackMap.indexOfKey(requestId);
                holder = (index >= 0) ?
                        mCaptureCallbackMap.valueAt(index) : null;
                if (holder != null) {
                    long lastRegularFrameNumber =
                            requestLastFrameNumbers.getLastRegularFrameNumber();
                    long lastReprocessFrameNumber =
                            requestLastFrameNumbers.getLastReprocessFrameNumber();
                    long lastZslStillFrameNumber =
                            requestLastFrameNumbers.getLastZslStillFrameNumber();
                    executor = holder.getCallback().getExecutor();
                    callback = holder.getCallback().getSessionCallback();
                    // check if it's okay to remove request from mCaptureCallbackMap
                    if (lastRegularFrameNumber <= completedFrameNumber
                            && lastReprocessFrameNumber <= completedReprocessFrameNumber
                            && lastZslStillFrameNumber <= completedZslStillFrameNumber) {
                        sequenceCompleted = true;
                        mCaptureCallbackMap.removeAt(index);
                    }
                } else {
                    executor = null;
                    callback = null;
                }
            }

            // If no callback is registered for this requestId or sequence completed, remove it
            // from the frame number->request pair because it's not needed anymore.
            if (holder == null || sequenceCompleted) {
                iter.remove();
            }

            // Call onCaptureSequenceCompleted
            if ((sequenceCompleted) && (callback != null) && (executor != null)) {
                Runnable resultDispatch = new Runnable() {
                    @Override
                    public void run() {
                        if (!isClosed()) {
                            callback.onCaptureSequenceCompleted(CameraOfflineSessionImpl.this,
                                    requestId, requestLastFrameNumbers.getLastFrameNumber());
                        }
                    }
                };

                final long ident = Binder.clearCallingIdentity();
                try {
                    executor.execute(resultDispatch);
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }

                if (mCaptureCallbackMap.size() == 0) {
                    getCallbacks().onDeviceIdle();
                }
            }

        }
    }

    private void removeCompletedCallbackHolderLocked(long lastCompletedRegularFrameNumber,
            long lastCompletedReprocessFrameNumber, long lastCompletedZslStillFrameNumber) {
        if (DEBUG) {
            Log.v(TAG, String.format("remove completed callback holders for "
                    + "lastCompletedRegularFrameNumber %d, "
                    + "lastCompletedReprocessFrameNumber %d, "
                    + "lastCompletedZslStillFrameNumber %d",
                    lastCompletedRegularFrameNumber,
                    lastCompletedReprocessFrameNumber,
                    lastCompletedZslStillFrameNumber));
        }

        boolean isReprocess = false;
        Iterator<RequestLastFrameNumbersHolder> iter =
                mOfflineRequestLastFrameNumbersList.iterator();
        while (iter.hasNext()) {
            final RequestLastFrameNumbersHolder requestLastFrameNumbers = iter.next();
            final int requestId = requestLastFrameNumbers.getRequestId();
            final CaptureCallbackHolder holder;

            int index = mCaptureCallbackMap.indexOfKey(requestId);
            holder = (index >= 0) ?
                    mCaptureCallbackMap.valueAt(index) : null;
            if (holder != null) {
                long lastRegularFrameNumber =
                        requestLastFrameNumbers.getLastRegularFrameNumber();
                long lastReprocessFrameNumber =
                        requestLastFrameNumbers.getLastReprocessFrameNumber();
                long lastZslStillFrameNumber =
                        requestLastFrameNumbers.getLastZslStillFrameNumber();
                if (lastRegularFrameNumber <= lastCompletedRegularFrameNumber
                        && lastReprocessFrameNumber <= lastCompletedReprocessFrameNumber
                        && lastZslStillFrameNumber <= lastCompletedZslStillFrameNumber) {
                    if (requestLastFrameNumbers.isSequenceCompleted()) {
                        mCaptureCallbackMap.removeAt(index);
                        if (DEBUG) {
                            Log.v(TAG, String.format(
                                    "Remove holder for requestId %d, because lastRegularFrame %d "
                                    + "is <= %d, lastReprocessFrame %d is <= %d, "
                                    + "lastZslStillFrame %d is <= %d", requestId,
                                    lastRegularFrameNumber, lastCompletedRegularFrameNumber,
                                    lastReprocessFrameNumber, lastCompletedReprocessFrameNumber,
                                    lastZslStillFrameNumber, lastCompletedZslStillFrameNumber));
                        }

                        iter.remove();
                    } else {
                        Log.e(TAG, "Sequence not yet completed for request id " + requestId);
                        continue;
                    }
                }
            }
        }
    }

    public void notifyFailedSwitch() {
        synchronized(mInterfaceLock) {
            Runnable switchFailDispatch = new Runnable() {
                @Override
                public void run() {
                    mOfflineCallback.onSwitchFailed(CameraOfflineSessionImpl.this);
                }
            };

            final long ident = Binder.clearCallingIdentity();
            try {
                mOfflineExecutor.execute(switchFailDispatch);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    /**
     * Set remote session.
     *
     */
    public void setRemoteSession(ICameraOfflineSession remoteSession) throws CameraAccessException {
        synchronized(mInterfaceLock) {
            if (remoteSession == null) {
                notifyFailedSwitch();
                return;
            }

            mRemoteSession = remoteSession;

            IBinder remoteSessionBinder = remoteSession.asBinder();
            if (remoteSessionBinder == null) {
                throw new CameraAccessException(CameraAccessException.CAMERA_DISCONNECTED,
                        "The camera offline session has encountered a serious error");
            }

            try {
                remoteSessionBinder.linkToDeath(this, /*flag*/ 0);
            } catch (RemoteException e) {
                throw new CameraAccessException(CameraAccessException.CAMERA_DISCONNECTED,
                        "The camera offline session has encountered a serious error");
            }

            Runnable readyDispatch = new Runnable() {
                @Override
                public void run() {
                    if (!isClosed()) {
                        mOfflineCallback.onReady(CameraOfflineSessionImpl.this);
                    }
                }
            };

            final long ident = Binder.clearCallingIdentity();
            try {
                mOfflineExecutor.execute(readyDispatch);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    /** Whether the offline session has started to close (may not yet have finished) */
    private boolean isClosed() {
        return mClosing.get();
    }

    private void disconnect() {
        synchronized (mInterfaceLock) {
            if (mClosing.getAndSet(true)) {
                return;
            }

            if (mRemoteSession != null) {
                mRemoteSession.asBinder().unlinkToDeath(this, /*flags*/0);

                try {
                    mRemoteSession.disconnect();
                } catch (RemoteException e) {
                    Log.e(TAG, "Exception while disconnecting from offline session: ", e);
                }
            } else {
                throw new IllegalStateException("Offline session is not yet ready");
            }

            mRemoteSession = null;

            Runnable closeDispatch = new Runnable() {
                @Override
                public void run() {
                    mOfflineCallback.onClosed(CameraOfflineSessionImpl.this);
                }
            };

            final long ident = Binder.clearCallingIdentity();
            try {
                mOfflineExecutor.execute(closeDispatch);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            disconnect();
        }
        finally {
            super.finalize();
        }
    }

    /**
     * Listener for binder death.
     *
     * <p> Handle binder death for ICameraOfflineSession.</p>
     */
    @Override
    public void binderDied() {
        Log.w(TAG, "CameraOfflineSession on device " + mCameraId + " died unexpectedly");
        disconnect();
    }

    @Override
    public CameraDevice getDevice() {
        throw new UnsupportedOperationException("Operation not supported in offline mode");
    }

    @Override
    public void prepare(Surface surface) throws CameraAccessException {
        throw new UnsupportedOperationException("Operation not supported in offline mode");
    }

    @Override
    public void prepare(int maxCount, Surface surface) throws CameraAccessException {
        throw new UnsupportedOperationException("Operation not supported in offline mode");
    }

    @Override
    public void tearDown(Surface surface) throws CameraAccessException {
        throw new UnsupportedOperationException("Operation not supported in offline mode");
    }

    @Override
    public void finalizeOutputConfigurations(
            List<OutputConfiguration> outputConfigs) throws CameraAccessException {
        throw new UnsupportedOperationException("Operation not supported in offline mode");
    }

    @Override
    public int capture(CaptureRequest request, CaptureCallback callback,
            Handler handler) throws CameraAccessException {
        throw new UnsupportedOperationException("Operation not supported in offline mode");
    }

    @Override
    public int captureSingleRequest(CaptureRequest request, Executor executor,
            CaptureCallback callback) throws CameraAccessException {
        throw new UnsupportedOperationException("Operation not supported in offline mode");
    }

    @Override
    public int captureBurst(List<CaptureRequest> requests, CaptureCallback callback,
            Handler handler) throws CameraAccessException {
        throw new UnsupportedOperationException("Operation not supported in offline mode");
    }

    @Override
    public int captureBurstRequests(List<CaptureRequest> requests, Executor executor,
            CaptureCallback callback) throws CameraAccessException {
        throw new UnsupportedOperationException("Operation not supported in offline mode");
    }

    @Override
    public int setRepeatingRequest(CaptureRequest request, CaptureCallback callback,
            Handler handler) throws CameraAccessException {
        throw new UnsupportedOperationException("Operation not supported in offline mode");
    }

    @Override
    public int setSingleRepeatingRequest(CaptureRequest request, Executor executor,
            CaptureCallback callback) throws CameraAccessException {
        throw new UnsupportedOperationException("Operation not supported in offline mode");
    }

    @Override
    public int setRepeatingBurst(List<CaptureRequest> requests,
            CaptureCallback callback, Handler handler) throws CameraAccessException {
        throw new UnsupportedOperationException("Operation not supported in offline mode");
    }

    @Override
    public int setRepeatingBurstRequests(List<CaptureRequest> requests, Executor executor,
            CaptureCallback callback) throws CameraAccessException {
        throw new UnsupportedOperationException("Operation not supported in offline mode");
    }

    @Override
    public void stopRepeating() throws CameraAccessException {
        throw new UnsupportedOperationException("Operation not supported in offline mode");
    }

    @Override
    public void abortCaptures() throws CameraAccessException {
        throw new UnsupportedOperationException("Operation not supported in offline mode");
    }

    @Override
    public void updateOutputConfiguration(OutputConfiguration config)
            throws CameraAccessException {
        throw new UnsupportedOperationException("Operation not supported in offline mode");
    }

    @Override
    public boolean isReprocessable() {
        throw new UnsupportedOperationException("Operation not supported in offline mode");
    }

    @Override
    public Surface getInputSurface() {
        throw new UnsupportedOperationException("Operation not supported in offline mode");
    }

    @Override
    public CameraOfflineSession switchToOffline(Collection<Surface> offlineOutputs,
            Executor executor, CameraOfflineSessionCallback listener) throws CameraAccessException {
        throw new UnsupportedOperationException("Operation not supported in offline mode");
    }

    @Override
    public boolean supportsOfflineProcessing(Surface surface) {
        throw new UnsupportedOperationException("Operation not supported in offline mode");
    }

    @Override
    public void close() {
        disconnect();
    }
}
