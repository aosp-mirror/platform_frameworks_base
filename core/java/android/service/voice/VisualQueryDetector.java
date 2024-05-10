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

package android.service.voice;

import static android.Manifest.permission.CAMERA;
import static android.Manifest.permission.RECORD_AUDIO;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.content.Context;
import android.hardware.soundtrigger.SoundTrigger;
import android.media.AudioFormat;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.SharedMemory;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.app.IHotwordRecognitionStatusCallback;
import com.android.internal.app.IVoiceInteractionManagerService;
import com.android.internal.infra.AndroidFuture;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Manages VisualQueryDetectionService.
 *
 * This detector provides necessary functionalities to initialize, start, update and destroy a
 * {@link VisualQueryDetectionService}.
 *
 * @hide
 **/
@SystemApi
@SuppressLint("NotCloseable")
public class VisualQueryDetector {
    private static final String TAG = VisualQueryDetector.class.getSimpleName();
    private static final boolean DEBUG = false;

    private final Callback mCallback;
    private final Executor mExecutor;
    private final Context mContext;
    private final IVoiceInteractionManagerService mManagerService;
    private final VisualQueryDetectorInitializationDelegate mInitializationDelegate;
    private final String mAttributionTag;

    VisualQueryDetector(
            IVoiceInteractionManagerService managerService,
            @NonNull @CallbackExecutor Executor executor, Callback callback, Context context,
            @Nullable String attributionTag) {
        mManagerService = managerService;
        mCallback = callback;
        mExecutor = executor;
        mInitializationDelegate = new VisualQueryDetectorInitializationDelegate();
        mContext = context;
        mAttributionTag = attributionTag;
    }

    /**
     * Initialize the {@link VisualQueryDetectionService} by passing configurations and read-only
     * data.
     */
    void initialize(@Nullable PersistableBundle options, @Nullable SharedMemory sharedMemory) {
        mInitializationDelegate.initialize(options, sharedMemory);
    }

    /**
     * Set configuration and pass read-only data to {@link VisualQueryDetectionService}.
     *
     * @see HotwordDetector#updateState(PersistableBundle, SharedMemory)
     */
    public void updateState(@Nullable PersistableBundle options,
            @Nullable SharedMemory sharedMemory) {
        mInitializationDelegate.updateState(options, sharedMemory);
    }


    /**
     * On calling this method, {@link VisualQueryDetectionService
     * #onStartDetection(VisualQueryDetectionService.Callback)} will be called to start using
     * visual signals such as camera frames and microphone audio to perform detection. When user
     * attention is captured and the {@link VisualQueryDetectionService} streams queries,
     * {@link VisualQueryDetector.Callback#onQueryDetected(String)} is called to control the
     * behavior of handling {@code transcribedText}. When the query streaming is finished,
     * {@link VisualQueryDetector.Callback#onQueryFinished()} is called. If the current streamed
     * query is invalid, {@link VisualQueryDetector.Callback#onQueryRejected()} is called to abandon
     * the streamed query.
     *
     * @see HotwordDetector#startRecognition()
     */
    @RequiresPermission(allOf = {CAMERA, RECORD_AUDIO})
    public boolean startRecognition() {
        if (DEBUG) {
            Slog.i(TAG, "#startRecognition");
        }
        // check if the detector is active with the initialization delegate
        mInitializationDelegate.startRecognition();

        try {
            mManagerService.startPerceiving(new BinderCallback(mExecutor, mCallback));
        } catch (SecurityException e) {
            Slog.e(TAG, "startRecognition failed: " + e);
            return false;
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        return true;
    }

    /**
     * Stops visual query detection recognition.
     *
     * @see HotwordDetector#stopRecognition()
     */
    @RequiresPermission(allOf = {CAMERA, RECORD_AUDIO})
    public boolean stopRecognition() {
        if (DEBUG) {
            Slog.i(TAG, "#stopRecognition");
        }
        // check if the detector is active with the initialization delegate
        mInitializationDelegate.startRecognition();

        try {
            mManagerService.stopPerceiving();
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        return true;
    }

    /**
     * Destroy the current detector.
     *
     * @see HotwordDetector#destroy()
     */
    public void destroy() {
        if (DEBUG) {
            Slog.i(TAG, "#destroy");
        }
        mInitializationDelegate.destroy();
    }

    /** @hide */
    public void dump(String prefix, PrintWriter pw) {
        // TODO: implement this
    }

    /** @hide */
    public HotwordDetector getInitializationDelegate() {
        return mInitializationDelegate;
    }

    /** @hide */
    void registerOnDestroyListener(Consumer<AbstractDetector> onDestroyListener) {
        mInitializationDelegate.registerOnDestroyListener(onDestroyListener);
    }

    /**
     * A class that lets a VoiceInteractionService implementation interact with
     * visual query detection APIs.
     */
    public interface Callback {

        /**
         * Called when the {@link VisualQueryDetectionService} starts to stream partial queries
         * with {@link VisualQueryDetectionService#streamQuery(String)}.
         *
         * @param partialQuery The partial query in a text form being streamed.
         */
        void onQueryDetected(@NonNull String partialQuery);

        /**
         * Called when the {@link VisualQueryDetectionService} decides to abandon the streamed
         * partial queries with {@link VisualQueryDetectionService#rejectQuery()}.
         */
        void onQueryRejected();

        /**
         *  Called when the {@link VisualQueryDetectionService} finishes streaming partial queries
         *  with {@link VisualQueryDetectionService#finishQuery()}.
         */
        void onQueryFinished();

        /**
         * Called when the {@link VisualQueryDetectionService} is created by the system and given a
         * short amount of time to report its initialization state.
         *
         * @param status Info about initialization state of {@link VisualQueryDetectionService}; the
         * allowed values are
         * {@link SandboxedDetectionInitializer#INITIALIZATION_STATUS_SUCCESS},
         * 1<->{@link SandboxedDetectionInitializer#getMaxCustomInitializationStatus()},
         * {@link SandboxedDetectionInitializer#INITIALIZATION_STATUS_UNKNOWN}.
         */
        void onVisualQueryDetectionServiceInitialized(int status);

         /**
         * Called with the {@link VisualQueryDetectionService} is restarted.
         *
         * Clients are expected to call {@link HotwordDetector#updateState} to share the state with
         * the newly created service.
         */
        void onVisualQueryDetectionServiceRestarted();

        /**
         * Called when the detection fails due to an error occurs in the
         * {@link VisualQueryDetectionService}, {@link VisualQueryDetectionServiceFailure} will be
         * reported to the detector.
         *
         * @param visualQueryDetectionServiceFailure It provides the error code, error message and
         *                                           suggested action.
         */
        void onFailure(
                @NonNull VisualQueryDetectionServiceFailure visualQueryDetectionServiceFailure);

        /**
         * Called when the detection fails due to an unknown error occurs, an error message
         * will be reported to the detector.
         *
         * @param errorMessage It provides the error message.
         */
        void onUnknownFailure(@NonNull String errorMessage);
    }

    private class VisualQueryDetectorInitializationDelegate extends AbstractDetector {

        VisualQueryDetectorInitializationDelegate() {
            super(mManagerService, mExecutor, /* callback= */ null);
        }

        @Override
        void initialize(@Nullable PersistableBundle options, @Nullable SharedMemory sharedMemory) {
            initAndVerifyDetector(options, sharedMemory,
                    new InitializationStateListener(mExecutor, mCallback, mContext),
                    DETECTOR_TYPE_VISUAL_QUERY_DETECTOR, mAttributionTag);
        }

        @Override
        public boolean stopRecognition() {
            throwIfDetectorIsNoLongerActive();
            return true;
        }

        @Override
        public boolean startRecognition() {
            throwIfDetectorIsNoLongerActive();
            return true;
        }

        @Override
        public final boolean startRecognition(
                @NonNull ParcelFileDescriptor audioStream,
                @NonNull AudioFormat audioFormat,
                @Nullable PersistableBundle options) {
            //No-op, not supported by VisualQueryDetector as it should be trusted.
            return false;
        }

        @Override
        public boolean isUsingSandboxedDetectionService() {
            return true;
        }
    }

    private static class BinderCallback
            extends IVisualQueryDetectionVoiceInteractionCallback.Stub {
        private final Executor mExecutor;
        private final VisualQueryDetector.Callback mCallback;

        BinderCallback(Executor executor, VisualQueryDetector.Callback callback) {
            this.mExecutor = executor;
            this.mCallback = callback;
        }

        /** Called when the detected result is valid. */
        @Override
        public void onQueryDetected(@NonNull String partialQuery) {
            Slog.v(TAG, "BinderCallback#onQueryDetected");
            Binder.withCleanCallingIdentity(() -> mExecutor.execute(
                    () -> mCallback.onQueryDetected(partialQuery)));
        }

        @Override
        public void onQueryFinished() {
            Slog.v(TAG, "BinderCallback#onQueryFinished");
            Binder.withCleanCallingIdentity(() -> mExecutor.execute(
                    () -> mCallback.onQueryFinished()));
        }

        @Override
        public void onQueryRejected() {
            Slog.v(TAG, "BinderCallback#onQueryRejected");
            Binder.withCleanCallingIdentity(() -> mExecutor.execute(
                    () -> mCallback.onQueryRejected()));
        }

        /** Called when the detection fails due to an error. */
        @Override
        public void onVisualQueryDetectionServiceFailure(
                VisualQueryDetectionServiceFailure visualQueryDetectionServiceFailure) {
            Slog.v(TAG, "BinderCallback#onVisualQueryDetectionServiceFailure: "
                    + visualQueryDetectionServiceFailure);
            Binder.withCleanCallingIdentity(() -> mExecutor.execute(() -> {
                if (visualQueryDetectionServiceFailure != null) {
                    mCallback.onFailure(visualQueryDetectionServiceFailure);
                } else {
                    mCallback.onUnknownFailure("Error data is null");
                }
            }));
        }
    }


    private static class InitializationStateListener
            extends IHotwordRecognitionStatusCallback.Stub {
        private final Executor mExecutor;
        private final Callback mCallback;

        private final Context mContext;

        InitializationStateListener(Executor executor, Callback callback, Context context) {
            this.mExecutor = executor;
            this.mCallback = callback;
            this.mContext = context;
        }

        @Override
        public void onKeyphraseDetected(
                SoundTrigger.KeyphraseRecognitionEvent recognitionEvent,
                HotwordDetectedResult result) {
            if (DEBUG) {
                Slog.i(TAG, "Ignored #onKeyphraseDetected event");
            }
        }

        @Override
        public void onGenericSoundTriggerDetected(
                SoundTrigger.GenericRecognitionEvent recognitionEvent) throws RemoteException {
            if (DEBUG) {
                Slog.i(TAG, "Ignored #onGenericSoundTriggerDetected event");
            }
        }

        @Override
        public void onRejected(HotwordRejectedResult result) throws RemoteException {
            if (DEBUG) {
                Slog.i(TAG, "Ignored #onRejected event");
            }
        }
        @Override
        public void onTrainingData(HotwordTrainingData data) throws RemoteException {
            if (DEBUG) {
                Slog.i(TAG, "Ignored #onTrainingData event");
            }
        }

        @Override
        public void onRecognitionPaused() throws RemoteException {
            if (DEBUG) {
                Slog.i(TAG, "Ignored #onRecognitionPaused event");
            }
        }

        @Override
        public void onRecognitionResumed() throws RemoteException {
            if (DEBUG) {
                Slog.i(TAG, "Ignored #onRecognitionResumed event");
            }
        }

        @Override
        public void onStatusReported(int status) {
            Slog.v(TAG, "onStatusReported" + (DEBUG ? "(" + status + ")" : ""));
            //TODO: rename the target callback with a more general term
            Binder.withCleanCallingIdentity(() -> mExecutor.execute(
                    () -> mCallback.onVisualQueryDetectionServiceInitialized(status)));

        }

        @Override
        public void onProcessRestarted() throws RemoteException {
            Slog.v(TAG, "onProcessRestarted()");
            //TODO: rename the target callback with a more general term
            Binder.withCleanCallingIdentity(() -> mExecutor.execute(
                    () -> mCallback.onVisualQueryDetectionServiceRestarted()));
        }

        @Override
        public void onHotwordDetectionServiceFailure(
                HotwordDetectionServiceFailure hotwordDetectionServiceFailure)
                throws RemoteException {
            // It should never be called here.
            Slog.w(TAG, "onHotwordDetectionServiceFailure: " + hotwordDetectionServiceFailure);
        }

        @Override
        public void onVisualQueryDetectionServiceFailure(
                VisualQueryDetectionServiceFailure visualQueryDetectionServiceFailure)
                throws RemoteException {
            Slog.v(TAG, "onVisualQueryDetectionServiceFailure: "
                    + visualQueryDetectionServiceFailure);
            Binder.withCleanCallingIdentity(() -> mExecutor.execute(() -> {
                if (visualQueryDetectionServiceFailure != null) {
                    mCallback.onFailure(visualQueryDetectionServiceFailure);
                } else {
                    mCallback.onUnknownFailure("Error data is null");
                }
            }));
        }

        @Override
        public void onSoundTriggerFailure(SoundTriggerFailure soundTriggerFailure) {
            Slog.wtf(TAG, "Unexpected STFailure in VisualQueryDetector" + soundTriggerFailure);
        }

        @Override
        public void onUnknownFailure(String errorMessage) throws RemoteException {
            Slog.v(TAG, "onUnknownFailure: " + errorMessage);
            Binder.withCleanCallingIdentity(() -> mExecutor.execute(() -> {
                mCallback.onUnknownFailure(
                        !TextUtils.isEmpty(errorMessage) ? errorMessage : "Error data is null");
            }));
        }
        @Override
        public void onOpenFile(String filename, AndroidFuture future) throws RemoteException {
            Slog.v(TAG, "BinderCallback#onOpenFile " + filename);
            Binder.withCleanCallingIdentity(() -> mExecutor.execute(() -> {
                Slog.v(TAG, "onOpenFile: " + filename + "under internal app storage.");
                File f = new File(mContext.getFilesDir(), filename);
                ParcelFileDescriptor pfd = null;
                try {
                    pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
                    Slog.d(TAG, "Successfully opened a file with ParcelFileDescriptor.");
                } catch (FileNotFoundException e) {
                    Slog.e(TAG, "Cannot open file. No ParcelFileDescriptor returned.");
                } finally {
                    future.complete(pfd);
                }
            }));
        }
    }
}
