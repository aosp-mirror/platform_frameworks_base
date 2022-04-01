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

package android.service.voice;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityThread;
import android.app.compat.CompatChanges;
import android.media.AudioFormat;
import android.media.permission.Identity;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.SharedMemory;
import android.util.Slog;

import com.android.internal.app.IHotwordRecognitionStatusCallback;
import com.android.internal.app.IVoiceInteractionManagerService;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Base implementation of {@link HotwordDetector}. */
abstract class AbstractHotwordDetector implements HotwordDetector {
    private static final String TAG = AbstractHotwordDetector.class.getSimpleName();
    private static final boolean DEBUG = false;

    protected final Object mLock = new Object();

    private final IVoiceInteractionManagerService mManagerService;
    private final Handler mHandler;
    private final HotwordDetector.Callback mCallback;
    private final int mDetectorType;
    private Consumer<AbstractHotwordDetector> mOnDestroyListener;
    private final AtomicBoolean mIsDetectorActive;

    AbstractHotwordDetector(
            IVoiceInteractionManagerService managerService,
            HotwordDetector.Callback callback,
            int detectorType) {
        mManagerService = managerService;
        // TODO: this needs to be supplied from above
        mHandler = new Handler(Looper.getMainLooper());
        mCallback = callback;
        mDetectorType = detectorType;
        mIsDetectorActive = new AtomicBoolean(true);
    }

    /**
     * Method to be called for the detector to ready/register itself with underlying system
     * services.
     */
    abstract void initialize(@Nullable PersistableBundle options,
            @Nullable SharedMemory sharedMemory);

    /**
     * Detect hotword from an externally supplied stream of data.
     *
     * @return true if the request to start recognition succeeded
     */
    @Override
    public boolean startRecognition(
            @NonNull ParcelFileDescriptor audioStream,
            @NonNull AudioFormat audioFormat,
            @Nullable PersistableBundle options) throws IllegalDetectorStateException {
        if (DEBUG) {
            Slog.i(TAG, "#recognizeHotword");
        }
        throwIfDetectorIsNoLongerActive();

        // TODO: consider closing existing session.

        try {
            mManagerService.startListeningFromExternalSource(
                    audioStream,
                    audioFormat,
                    options,
                    new BinderCallback(mHandler, mCallback));
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }

        return true;
    }

    /**
     * Set configuration and pass read-only data to hotword detection service.
     *
     * @param options Application configuration data to provide to the
     *         {@link HotwordDetectionService}. PersistableBundle does not allow any remotable
     *         objects or other contents that can be used to communicate with other processes.
     * @param sharedMemory The unrestricted data blob to provide to the
     *         {@link HotwordDetectionService}. Use this to provide the hotword models data or other
     *         such data to the trusted process.
     * @throws IllegalDetectorStateException Thrown when a caller has a target SDK of
     *         Android Tiramisu or above and attempts to start a recognition when the detector is
     *         not able based on the state. Because the caller receives updates via an asynchronous
     *         callback and the state of the detector can change without caller's knowledge, a
     *         checked exception is thrown.
     * @throws IllegalStateException if this HotwordDetector wasn't specified to use a
     *         {@link HotwordDetectionService} when it was created.
     */
    @Override
    public void updateState(@Nullable PersistableBundle options,
            @Nullable SharedMemory sharedMemory) throws IllegalDetectorStateException {
        if (DEBUG) {
            Slog.d(TAG, "updateState()");
        }
        throwIfDetectorIsNoLongerActive();
        synchronized (mLock) {
            updateStateLocked(options, sharedMemory, null /* callback */, mDetectorType);
        }
    }

    protected void updateStateLocked(@Nullable PersistableBundle options,
            @Nullable SharedMemory sharedMemory, IHotwordRecognitionStatusCallback callback,
            int detectorType) {
        if (DEBUG) {
            Slog.d(TAG, "updateStateLocked()");
        }
        Identity identity = new Identity();
        identity.packageName = ActivityThread.currentOpPackageName();
        try {
            mManagerService.updateState(identity, options, sharedMemory, callback, detectorType);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    void registerOnDestroyListener(Consumer<AbstractHotwordDetector> onDestroyListener) {
        synchronized (mLock) {
            if (mOnDestroyListener != null) {
                throw new IllegalStateException("only one destroy listener can be registered");
            }
            mOnDestroyListener = onDestroyListener;
        }
    }

    @CallSuper
    @Override
    public void destroy() {
        if (!mIsDetectorActive.get()) {
            return;
        }
        mIsDetectorActive.set(false);
        synchronized (mLock) {
            mOnDestroyListener.accept(this);
        }
    }

    protected void throwIfDetectorIsNoLongerActive() throws IllegalDetectorStateException {
        if (!mIsDetectorActive.get()) {
            Slog.e(TAG, "attempting to use a destroyed detector which is no longer active");
            if (CompatChanges.isChangeEnabled(HOTWORD_DETECTOR_THROW_CHECKED_EXCEPTION)) {
                throw new IllegalDetectorStateException(
                        "attempting to use a destroyed detector which is no longer active");
            }
            throw new IllegalStateException(
                    "attempting to use a destroyed detector which is no longer active");
        }
    }

    private static class BinderCallback
            extends IMicrophoneHotwordDetectionVoiceInteractionCallback.Stub {
        private final Handler mHandler;
        // TODO: these need to be weak references.
        private final HotwordDetector.Callback mCallback;

        BinderCallback(Handler handler, HotwordDetector.Callback callback) {
            this.mHandler = handler;
            this.mCallback = callback;
        }

        /** TODO: onDetected */
        @Override
        public void onDetected(
                @Nullable HotwordDetectedResult hotwordDetectedResult,
                @Nullable AudioFormat audioFormat,
                @Nullable ParcelFileDescriptor audioStreamIgnored) {
            mHandler.sendMessage(obtainMessage(
                    HotwordDetector.Callback::onDetected,
                    mCallback,
                    new AlwaysOnHotwordDetector.EventPayload.Builder()
                            .setCaptureAudioFormat(audioFormat)
                            .setHotwordDetectedResult(hotwordDetectedResult)
                            .build()));
        }
    }
}
