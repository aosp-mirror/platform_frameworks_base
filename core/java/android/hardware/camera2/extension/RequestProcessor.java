/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.hardware.camera2.extension;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.impl.CameraMetadataNative;
import android.hardware.camera2.impl.PhysicalCaptureResultInfo;
import android.os.Binder;
import android.os.RemoteException;
import android.util.Log;
import android.util.Pair;

import com.android.internal.camera.flags.Flags;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * An Interface to execute Camera2 capture requests.
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_CONCERT_MODE)
public final class RequestProcessor {
    private final static String TAG = "RequestProcessor";
    private final IRequestProcessorImpl mRequestProcessor;
    private final long mVendorId;

    @FlaggedApi(Flags.FLAG_CONCERT_MODE)
    RequestProcessor (@NonNull IRequestProcessorImpl requestProcessor, long vendorId) {
        mRequestProcessor = requestProcessor;
        mVendorId = vendorId;
    }

    @FlaggedApi(Flags.FLAG_CONCERT_MODE)
    public interface RequestCallback {
        /**
         * This method is called when the camera device has started
         * capturing the output image for the request, at the beginning of
         * image exposure, or when the camera device has started
         * processing an input image for a reprocess request.
         *
         * @param request The request that was given to the
         *                RequestProcessor
         * @param timestamp the timestamp at start of capture for a
         *                  regular request, or the timestamp at the input
         *                  image's start of capture for a
         *                  reprocess request, in nanoseconds.
         * @param frameNumber the frame number for this capture
         *
         */
        @FlaggedApi(Flags.FLAG_CONCERT_MODE)
        void onCaptureStarted(@NonNull Request request, long frameNumber, long timestamp);

        /**
         * This method is called when an image capture makes partial forward
         * progress; some (but not all) results from an image capture are
         * available.
         *
         * <p>The result provided here will contain some subset of the fields
         * of  a full result. Multiple {@link #onCaptureProgressed} calls may
         * happen per capture; a given result field will only be present in
         * one partial capture at most. The final {@link #onCaptureCompleted}
         * call will always contain all the fields (in particular, the union
         * of all the fields of all the partial results composing the total
         * result).</p>
         *
         * <p>For each request, some result data might be available earlier
         * than others. The typical delay between each partial result (per
         * request) is a single frame interval.
         * For performance-oriented use-cases, applications should query the
         * metadata they need to make forward progress from the partial
         * results and avoid waiting for the completed result.</p>
         *
         * <p>For a particular request, {@link #onCaptureProgressed} may happen
         * before or after {@link #onCaptureStarted}.</p>
         *
         * <p>Each request will generate at least {@code 1} partial results,
         * and at most {@link
         * CameraCharacteristics#REQUEST_PARTIAL_RESULT_COUNT} partial
         * results.</p>
         *
         * <p>Depending on the request settings, the number of partial
         * results per request  will vary, although typically the partial
         * count could be the same as long as the
         * camera device subsystems enabled stay the same.</p>
         *
         * @param request The request that was given to the RequestProcessor
         * @param partialResult The partial output metadata from the capture,
         *                      which includes a subset of the {@link
         *                      TotalCaptureResult} fields.
         */
        @FlaggedApi(Flags.FLAG_CONCERT_MODE)
        void onCaptureProgressed(@NonNull Request request, @NonNull CaptureResult partialResult);

        /**
         * This method is called when an image capture has fully completed and
         * all the result metadata is available.
         *
         * <p>This callback will always fire after the last {@link
         * #onCaptureProgressed}; in other words, no more partial results will
         * be delivered once the completed result is available.</p>
         *
         * <p>For performance-intensive use-cases where latency is a factor,
         * consider using {@link #onCaptureProgressed} instead.</p>
         *
         *
         * @param request The request that was given to the RequestProcessor
         * @param totalCaptureResult The total output metadata from the
         *                           capture, including the final capture
         *                           parameters and the state of the camera
         *                           system during capture.
         */
        @FlaggedApi(Flags.FLAG_CONCERT_MODE)
        void onCaptureCompleted(@NonNull Request request,
                @Nullable TotalCaptureResult totalCaptureResult);

        /**
         * This method is called instead of {@link #onCaptureCompleted} when the
         * camera device failed to produce a {@link CaptureResult} for the
         * request.
         *
         * <p>Other requests are unaffected, and some or all image buffers
         * from the capture may have been pushed to their respective output
         * streams.</p>
         *
         * <p>If a logical multi-camera fails to generate capture result for
         * one of its physical cameras, this method will be called with a
         * {@link CaptureFailure} for that physical camera. In such cases, as
         * long as the logical camera capture result is valid, {@link
         * #onCaptureCompleted} will still be called.</p>
         *
         * <p>The default implementation of this method does nothing.</p>
         *
         * @param request The request that was given to the RequestProcessor
         * @param failure The output failure from the capture, including the
         *                failure reason and the frame number.
         */
        @FlaggedApi(Flags.FLAG_CONCERT_MODE)
        void onCaptureFailed(@NonNull Request request, @NonNull CaptureFailure failure);

        /**
         * <p>This method is called if a single buffer for a capture could not
         * be sent to its destination surface.</p>
         *
         * <p>If the whole capture failed, then {@link #onCaptureFailed} will be
         * called instead. If some but not all buffers were captured but the
         * result metadata will not be available, then captureFailed will be
         * invoked with {@link CaptureFailure#wasImageCaptured}
         * returning true, along with one or more calls to {@link
         * #onCaptureBufferLost} for the failed outputs.</p>
         *
         * @param request The request that was given to the RequestProcessor
         * @param frameNumber The frame number for the request
         * @param outputStreamId The output stream id that the buffer will not
         *                       be produced for
         */
        @FlaggedApi(Flags.FLAG_CONCERT_MODE)
        void onCaptureBufferLost(@NonNull Request request, long frameNumber, int outputStreamId);

        /**
         * This method is called independently of the others in
         * CaptureCallback, when a capture sequence finishes and all {@link
         * CaptureResult} or {@link CaptureFailure} for it have been returned
         * via this listener.
         *
         * <p>In total, there will be at least one result/failure returned by
         * this listener  before this callback is invoked. If the capture
         * sequence is aborted before any requests have been processed,
         * {@link #onCaptureSequenceAborted} is invoked instead.</p>
         *
         * @param sequenceId A sequence ID returned by the RequestProcessor
         *                   capture family of methods
         * @param frameNumber The last frame number (returned by {@link
         *                    CaptureResult#getFrameNumber}
         *                    or {@link CaptureFailure#getFrameNumber}) in
         *                    the capture sequence.
         */
        @FlaggedApi(Flags.FLAG_CONCERT_MODE)
        void onCaptureSequenceCompleted(int sequenceId, long frameNumber);

        /**
         * This method is called independently of the others in
         * CaptureCallback, when a capture sequence aborts before any {@link
         * CaptureResult} or {@link CaptureFailure} for it have been returned
         * via this listener.
         *
         * <p>Due to the asynchronous nature of the camera device, not all
         * submitted captures are immediately processed. It is possible to
         * clear out the pending requests by a variety of operations such as
         * {@link RequestProcessor#stopRepeating} or
         * {@link RequestProcessor#abortCaptures}. When such an event
         * happens, {@link #onCaptureSequenceCompleted} will not be called.</p>
         * @param sequenceId A sequence ID returned by the RequestProcessor
         *                   capture family of methods
         */
        @FlaggedApi(Flags.FLAG_CONCERT_MODE)
        void onCaptureSequenceAborted(int sequenceId);
    }

    @FlaggedApi(Flags.FLAG_CONCERT_MODE)
    public final static class Request {
        private final List<Integer> mOutputIds;
        private final List<Pair<CaptureRequest.Key, Object>> mParameters;
        private final int mTemplateId;

        @FlaggedApi(Flags.FLAG_CONCERT_MODE)
        public Request(@NonNull List<Integer> outputConfigIds,
                @NonNull List<Pair<CaptureRequest.Key, Object>> parameters, int templateId) {
            mOutputIds = outputConfigIds;
            mParameters = parameters;
            mTemplateId = templateId;
        }

        /**
         * Gets the target ids of {@link ExtensionOutputConfiguration} which identifies
         * corresponding Surface to be the targeted for the request.
         */
        @FlaggedApi(Flags.FLAG_CONCERT_MODE)
        @NonNull
        List<Integer> getOutputConfigIds() {
            return mOutputIds;
        }

        /**
         * Gets all the parameters.
         */
        @FlaggedApi(Flags.FLAG_CONCERT_MODE)
        @NonNull
        public List<Pair<CaptureRequest.Key, Object>> getParameters() {
            return mParameters;
        }

        /**
         * Gets the template id.
         */
        @FlaggedApi(Flags.FLAG_CONCERT_MODE)
        Integer getTemplateId() {
            return mTemplateId;
        }

        @NonNull List<OutputConfigId> getTargetIds() {
            ArrayList<OutputConfigId> ret = new ArrayList<>(mOutputIds.size());
            int idx = 0;
            for (Integer outputId : mOutputIds) {
                OutputConfigId configId = new OutputConfigId();
                configId.id = outputId;
                ret.add(idx++, configId);
            }

            return ret;
        }

        @NonNull
        static CameraMetadataNative getParametersMetadata(long vendorId,
                @NonNull List<Pair<CaptureRequest.Key, Object>> parameters) {
            CameraMetadataNative ret = new CameraMetadataNative();
            ret.setVendorId(vendorId);
            for (Pair<CaptureRequest.Key, Object> pair : parameters) {
                ret.set(pair.first, pair.second);
            }

            return ret;
        }

        @NonNull
        static List<android.hardware.camera2.extension.Request> initializeParcelable(
                long vendorId, @NonNull List<Request> requests) {
            ArrayList<android.hardware.camera2.extension.Request> ret =
                    new ArrayList<>(requests.size());
            int requestId = 0;
            for (Request req : requests) {
                android.hardware.camera2.extension.Request request =
                        new android.hardware.camera2.extension.Request();
                request.requestId = requestId++;
                request.templateId = req.getTemplateId();
                request.targetOutputConfigIds = req.getTargetIds();
                request.parameters = getParametersMetadata(vendorId, req.getParameters());
                ret.add(request.requestId, request);
            }

            return ret;
        }
    }

    /**
     * Submit a capture request.
     * @param request  Capture request to queued in the Camera2 session
     * @param executor the executor which will be used for
     *                 invoking the callbacks or null to use the
     *                 current thread's looper
     * @param callback Request callback implementation
     * @return the id of the capture sequence or -1 in case the processor
     *         encounters a fatal error or receives an invalid argument.
     */
    @FlaggedApi(Flags.FLAG_CONCERT_MODE)
    public int submit(@NonNull Request request, @Nullable Executor executor,
            @NonNull RequestCallback callback) {
        ArrayList<Request> requests = new ArrayList<>(1);
        requests.add(0, request);
        List<android.hardware.camera2.extension.Request> parcelableRequests =
                Request.initializeParcelable(mVendorId, requests);

        try {
            return mRequestProcessor.submit(parcelableRequests.get(0),
                    new RequestCallbackImpl(requests, callback, executor));
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Submits a list of requests.
     * @param requests List of capture requests to be queued in the
     *                 Camera2 session
     * @param executor the executor which will be used for
     *                 invoking the callbacks or null to use the
     *                 current thread's looper
     * @param callback Request callback implementation
     * @return the id of the capture sequence or -1 in case the processor
     *         encounters a fatal error or receives an invalid argument.
     */
    @FlaggedApi(Flags.FLAG_CONCERT_MODE)
    public int submitBurst(@NonNull List<Request> requests, @Nullable Executor executor,
            @NonNull RequestCallback callback) {
        List<android.hardware.camera2.extension.Request> parcelableRequests =
                Request.initializeParcelable(mVendorId, requests);

        try {
            return mRequestProcessor.submitBurst(parcelableRequests,
                    new RequestCallbackImpl(requests, callback, executor));
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Set a repeating request.
     * @param request  Repeating capture request to be se in the
     *                 Camera2 session
     * @param executor the executor which will be used for
     *                 invoking the callbacks or null to use the
     *                 current thread's looper
     * @param callback Request callback implementation
     * @return the id of the capture sequence or -1 in case the processor
     *         encounters a fatal error or receives an invalid argument.
     */
    @FlaggedApi(Flags.FLAG_CONCERT_MODE)
    public int setRepeating(@NonNull Request request, @Nullable Executor executor,
            @NonNull RequestCallback callback) {
        ArrayList<Request> requests = new ArrayList<>(1);
        requests.add(0, request);
        List<android.hardware.camera2.extension.Request> parcelableRequests =
                Request.initializeParcelable(mVendorId, requests);

        try {
            return mRequestProcessor.setRepeating(parcelableRequests.get(0),
                    new RequestCallbackImpl(requests, callback, executor));
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Abort all ongoing capture requests.
     */
    @FlaggedApi(Flags.FLAG_CONCERT_MODE)
    public void abortCaptures() {
        try {
            mRequestProcessor.abortCaptures();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Stop the current repeating request.
     */
    @FlaggedApi(Flags.FLAG_CONCERT_MODE)
    public void stopRepeating() {
        try {
            mRequestProcessor.stopRepeating();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    private static class RequestCallbackImpl extends IRequestCallback.Stub {
        private final List<Request> mRequests;
        private final RequestCallback mCallback;
        private final Executor mExecutor;

        public RequestCallbackImpl(@NonNull List<Request> requests,
                @NonNull RequestCallback callback, @Nullable Executor executor) {
            mCallback = callback;
            mRequests = requests;
            mExecutor = executor;
        }

        @Override
        public void onCaptureStarted(int requestId, long frameNumber, long timestamp) {
            if (mRequests.get(requestId) != null) {
                final long ident = Binder.clearCallingIdentity();
                try {
                    if (mExecutor != null) {
                        mExecutor.execute(() -> mCallback.onCaptureStarted(
                                mRequests.get(requestId), frameNumber, timestamp));
                    } else {
                        mCallback.onCaptureStarted(mRequests.get(requestId), frameNumber,
                                timestamp);
                    }
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            } else {
                Log.e(TAG,"Request id: " + requestId + " not found!");
            }
        }

        @Override
        public void onCaptureProgressed(int requestId, ParcelCaptureResult partialResult) {
            if (mRequests.get(requestId) != null) {
                CaptureResult result = new CaptureResult(partialResult.cameraId,
                        partialResult.results, partialResult.parent, partialResult.sequenceId,
                        partialResult.frameNumber);
                final long ident = Binder.clearCallingIdentity();
                try {
                    if (mExecutor != null) {
                        mExecutor.execute(
                                () -> mCallback.onCaptureProgressed(mRequests.get(requestId),
                                        result));
                    } else {
                        mCallback.onCaptureProgressed(mRequests.get(requestId), result);
                    }

                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            } else {
                Log.e(TAG,"Request id: " + requestId + " not found!");
            }
        }

        @Override
        public void onCaptureCompleted(int requestId, ParcelTotalCaptureResult totalCaptureResult) {
            if (mRequests.get(requestId) != null) {
                PhysicalCaptureResultInfo[] physicalResults = new PhysicalCaptureResultInfo[0];
                if ((totalCaptureResult.physicalResult != null) &&
                        (!totalCaptureResult.physicalResult.isEmpty())) {
                    int count = totalCaptureResult.physicalResult.size();
                    physicalResults = new PhysicalCaptureResultInfo[count];
                    physicalResults = totalCaptureResult.physicalResult.toArray(
                            physicalResults);
                }
                ArrayList<CaptureResult> partials = new ArrayList<>(
                        totalCaptureResult.partials.size());
                for (ParcelCaptureResult parcelResult : totalCaptureResult.partials) {
                    partials.add(new CaptureResult(parcelResult.cameraId, parcelResult.results,
                            parcelResult.parent, parcelResult.sequenceId,
                            parcelResult.frameNumber));
                }
                TotalCaptureResult result = new TotalCaptureResult(
                        totalCaptureResult.logicalCameraId, totalCaptureResult.results,
                        totalCaptureResult.parent, totalCaptureResult.sequenceId,
                        totalCaptureResult.frameNumber, partials, totalCaptureResult.sessionId,
                        physicalResults);
                final long ident = Binder.clearCallingIdentity();
                try {
                    if (mExecutor != null) {
                        mExecutor.execute(
                                () -> mCallback.onCaptureCompleted(mRequests.get(requestId),
                                        result));
                    } else {
                        mCallback.onCaptureCompleted(mRequests.get(requestId), result);
                    }
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            } else {
                Log.e(TAG,"Request id: " + requestId + " not found!");
            }
        }

        @Override
        public void onCaptureFailed(int requestId,
                android.hardware.camera2.extension.CaptureFailure captureFailure) {
            if (mRequests.get(requestId) != null) {
                android.hardware.camera2.CaptureFailure failure =
                        new android.hardware.camera2.CaptureFailure(captureFailure.request,
                                captureFailure.reason, captureFailure.dropped,
                                captureFailure.sequenceId, captureFailure.frameNumber,
                                captureFailure.errorPhysicalCameraId);
                final long ident = Binder.clearCallingIdentity();
                try {
                    if (mExecutor != null) {
                        mExecutor.execute(() -> mCallback.onCaptureFailed(mRequests.get(requestId),
                                failure));
                    } else {
                        mCallback.onCaptureFailed(mRequests.get(requestId), failure);
                    }
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            } else {
                Log.e(TAG,"Request id: " + requestId + " not found!");
            }
        }

        @Override
        public void onCaptureBufferLost(int requestId, long frameNumber, int outputStreamId) {
            if (mRequests.get(requestId) != null) {
                final long ident = Binder.clearCallingIdentity();
                try {
                    if (mExecutor != null) {
                        mExecutor.execute(
                                () -> mCallback.onCaptureBufferLost(mRequests.get(requestId),
                                        frameNumber, outputStreamId));
                    } else {
                        mCallback.onCaptureBufferLost(mRequests.get(requestId), frameNumber,
                                outputStreamId);
                    }
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            } else {
                Log.e(TAG,"Request id: " + requestId + " not found!");
            }
        }

        @Override
        public void onCaptureSequenceCompleted(int sequenceId, long frameNumber) {
            final long ident = Binder.clearCallingIdentity();
            try {
                if (mExecutor != null) {
                    mExecutor.execute(() -> mCallback.onCaptureSequenceCompleted(sequenceId,
                            frameNumber));
                } else {
                    mCallback.onCaptureSequenceCompleted(sequenceId, frameNumber);
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void onCaptureSequenceAborted(int sequenceId) {
            final long ident = Binder.clearCallingIdentity();
            try {
                if (mExecutor != null) {
                    mExecutor.execute(() -> mCallback.onCaptureSequenceAborted(sequenceId));
                } else {
                    mCallback.onCaptureSequenceAborted(sequenceId);
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }
}
