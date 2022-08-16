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

package com.android.server.soundtrigger_middleware;

import android.annotation.NonNull;
import android.media.soundtrigger.ModelParameterRange;
import android.media.soundtrigger.PhraseRecognitionEvent;
import android.media.soundtrigger.PhraseSoundModel;
import android.media.soundtrigger.Properties;
import android.media.soundtrigger.RecognitionConfig;
import android.media.soundtrigger.RecognitionEvent;
import android.media.soundtrigger.SoundModel;
import android.media.soundtrigger.SoundModelType;
import android.media.soundtrigger.Status;
import android.os.IBinder;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This is a decorator around ISoundTriggerHal, which implements enforcement of concurrent capture
 * constraints, for HAL implementations older than V2.4 (later versions support this feature at the
 * HAL level).
 * <p>
 * Decorating an instance with this class would result in all active recognitions being aborted as
 * soon as capture state becomes active. This class ensures consistent handling of abortions coming
 * from that HAL and abortions coming from concurrent capture, in that only one abort event will be
 * delivered, irrespective of the relative timing of the two events.
 * <p>
 * There are some delicate thread-safety issues handled here:
 * <ul>
 * <li>When a model is stopped via stopRecognition(), we guarantee that by the time the call
 * returns, there will be no more recognition events (including abort) delivered for this model.
 * This implies synchronous stopping and blocking until all pending events have been delivered.
 * <li>When a model is stopped via onCaptureStateChange(true), the stopping of the recognition at
 * the HAL level must be synchronous, but the call must not block on the delivery of the
 * callbacks, due to the risk of a deadlock: the onCaptureStateChange() calls are typically
 * invoked with the audio policy mutex held, so must not call method which may attempt to lock
 * higher-level mutexes. See README.md in this directory for further details.
 * </ul>
 * The way this behavior is achieved is by having an additional thread with an event queue, which
 * joins together model events coming from the delegate module with abort events originating from
 * this layer (as result of external capture).
 */
public class SoundTriggerHalConcurrentCaptureHandler implements ISoundTriggerHal,
        ICaptureStateNotifier.Listener {
    @NonNull private final ISoundTriggerHal mDelegate;
    private GlobalCallback mGlobalCallback;
    /**
     * This lock must be held to synchronize forward calls (start/stop/onCaptureStateChange) that
     * update the mActiveModels set and mCaptureState.
     * It must not be locked in HAL callbacks to avoid deadlocks.
     */
    @NonNull private final Object mStartStopLock = new Object();

    /**
     * Information about a model that is currently loaded. This is needed in order to be able to
     * send abort events to its designated callback.
     */
    private static class LoadedModel {
        public final int type;
        @NonNull public final ModelCallback callback;

        LoadedModel(int type, @NonNull ModelCallback callback) {
            this.type = type;
            this.callback = callback;
        }
    }

    /**
     * This map holds the model type for every model that is loaded.
     */
    @NonNull private final Map<Integer, LoadedModel> mLoadedModels = new ConcurrentHashMap<>();

    /**
     * A set of all models that are currently active.
     * We use this in order to know which models to stop in case of external capture.
     * Used as a lock to synchronize operations that effect activity.
     */
    @NonNull private final Set<Integer> mActiveModels = new HashSet<>();

    /**
     * Notifier for changes in capture state.
     */
    @NonNull private final ICaptureStateNotifier mNotifier;

    /**
     * Whether capture is active.
     */
    private boolean mCaptureState;

    /**
     * Since we're wrapping the death recipient, we need to keep a translation map for unlinking.
     * Key is the client recipient, value is the wrapper.
     */
    @NonNull private final Map<IBinder.DeathRecipient, IBinder.DeathRecipient>
            mDeathRecipientMap = new ConcurrentHashMap<>();

    @NonNull private final CallbackThread mCallbackThread = new CallbackThread();

    public SoundTriggerHalConcurrentCaptureHandler(
            @NonNull ISoundTriggerHal delegate,
            @NonNull ICaptureStateNotifier notifier) {
        mDelegate = delegate;
        mNotifier = notifier;
        mCaptureState = mNotifier.registerListener(this);
    }

    @Override
    public void startRecognition(int modelHandle, int deviceHandle, int ioHandle,
            RecognitionConfig config) {
        synchronized (mStartStopLock) {
            synchronized (mActiveModels) {
                if (mCaptureState) {
                    throw new RecoverableException(Status.RESOURCE_CONTENTION);
                }
                mDelegate.startRecognition(modelHandle, deviceHandle, ioHandle, config);
                mActiveModels.add(modelHandle);
            }
        }
    }

    @Override
    public void stopRecognition(int modelHandle) {
        synchronized (mStartStopLock) {
            boolean wasActive;
            synchronized (mActiveModels) {
                wasActive = mActiveModels.remove(modelHandle);
            }
            if (wasActive) {
                // Must be done outside of the lock, since it may trigger synchronous callbacks.
                mDelegate.stopRecognition(modelHandle);
            }
        }
        // Block until all previous events are delivered. Since this is potentially blocking on
        // upward calls, it must be done outside the lock.
        mCallbackThread.flush();
    }

    @Override
    public void onCaptureStateChange(boolean active) {
        synchronized (mStartStopLock) {
            if (active) {
                abortAllActiveModels();
            } else {
                if (mGlobalCallback != null) {
                    mGlobalCallback.onResourcesAvailable();
                }
            }
            mCaptureState = active;
        }
    }

    private void abortAllActiveModels() {
        while (true) {
            int toStop;
            synchronized (mActiveModels) {
                Iterator<Integer> iterator = mActiveModels.iterator();
                if (!iterator.hasNext()) {
                    return;
                }
                toStop = iterator.next();
                mActiveModels.remove(toStop);
            }
            // Invoke stop outside of the lock.
            mDelegate.stopRecognition(toStop);

            LoadedModel model = mLoadedModels.get(toStop);
            // Queue an abort event (no need to flush).
            mCallbackThread.push(() -> notifyAbort(toStop, model));
        }
    }

    @Override
    public int loadSoundModel(SoundModel soundModel, ModelCallback callback) {
        int handle = mDelegate.loadSoundModel(soundModel, new CallbackWrapper(callback));
        mLoadedModels.put(handle, new LoadedModel(SoundModelType.GENERIC, callback));
        return handle;
    }

    @Override
    public int loadPhraseSoundModel(PhraseSoundModel soundModel,
            ModelCallback callback) {
        int handle = mDelegate.loadPhraseSoundModel(soundModel, new CallbackWrapper(callback));
        mLoadedModels.put(handle, new LoadedModel(SoundModelType.KEYPHRASE, callback));
        return handle;
    }

    @Override
    public void unloadSoundModel(int modelHandle) {
        mLoadedModels.remove(modelHandle);
        mDelegate.unloadSoundModel(modelHandle);
    }

    @Override
    public void registerCallback(GlobalCallback callback) {
        mGlobalCallback = () -> mCallbackThread.push(callback::onResourcesAvailable);
        mDelegate.registerCallback(mGlobalCallback);
    }

    @Override
    public void linkToDeath(IBinder.DeathRecipient recipient) {
        IBinder.DeathRecipient wrapper = () -> mCallbackThread.push(recipient::binderDied);
        mDelegate.linkToDeath(wrapper);
        mDeathRecipientMap.put(recipient, wrapper);
    }

    @Override
    public void unlinkToDeath(IBinder.DeathRecipient recipient) {
        mDelegate.unlinkToDeath(mDeathRecipientMap.remove(recipient));
    }

    private class CallbackWrapper implements ISoundTriggerHal.ModelCallback {
        @NonNull private final ISoundTriggerHal.ModelCallback mDelegateCallback;

        private CallbackWrapper(@NonNull ModelCallback delegateCallback) {
            mDelegateCallback = delegateCallback;
        }

        @Override
        public void recognitionCallback(int modelHandle, RecognitionEvent event) {
            synchronized (mActiveModels) {
                if (!mActiveModels.contains(modelHandle)) {
                    // Discard the event.
                    return;
                }
                if (!event.recognitionStillActive) {
                    mActiveModels.remove(modelHandle);
                }
                // A recognition event must be the last one for its model, unless it indicates that
                // recognition is still active.
                mCallbackThread.push(
                        () -> mDelegateCallback.recognitionCallback(modelHandle, event));
            }
        }

        @Override
        public void phraseRecognitionCallback(int modelHandle, PhraseRecognitionEvent event) {
            synchronized (mActiveModels) {
                if (!mActiveModels.contains(modelHandle)) {
                    // Discard the event.
                    return;
                }
                if (!event.common.recognitionStillActive) {
                    mActiveModels.remove(modelHandle);
                }
                // A recognition event must be the last one for its model, unless it indicates that
                // recognition is still active.
                mCallbackThread.push(
                        () -> mDelegateCallback.phraseRecognitionCallback(modelHandle, event));
            }
        }

        @Override
        public void modelUnloaded(int modelHandle) {
            mCallbackThread.push(() -> mDelegateCallback.modelUnloaded(modelHandle));
        }
    }

    @Override
    public void flushCallbacks() {
        mDelegate.flushCallbacks();
        mCallbackThread.flush();
    }

    /**
     * This is a thread for asynchronous delivery of callback events, having the following features:
     * <ul>
     * <li>Events are processed on a separate thread than the thread that pushed them, in the order
     * they were pushed.
     * <li>Events can be flushed via {@link #flush()}. This will block until all events pushed prior
     * to this call have been fully processed.
     * </ul>
     */
    private static class CallbackThread {
        private final Queue<Runnable> mList = new LinkedList<>();
        private int mPushCount = 0;
        private int mProcessedCount = 0;

        /**
         * Ctor. Starts the thread.
         */
        CallbackThread() {
            new Thread(() -> {
                try {
                    while (true) {
                        pop().run();
                        synchronized (mList) {
                            mProcessedCount++;
                            mList.notifyAll();
                        }
                    }
                } catch (InterruptedException e) {
                    // If interrupted, exit.
                }
            }).start();
        }

        /**
         * Push a new runnable to the queue, with no deduping.
         *
         * @param runnable The runnable to push.
         */
        void push(Runnable runnable) {
            synchronized (mList) {
                mList.add(runnable);
                mPushCount++;
                mList.notifyAll();
            }
        }

        /**
         * Block until every entry pushed prior to this call has been processed.
         */
        void flush() {
            try {
                synchronized (mList) {
                    int pushCount = mPushCount;
                    while (mProcessedCount != pushCount) {
                        mList.wait();
                    }
                }
            } catch (InterruptedException ignored) {
            }
        }

        private Runnable pop() throws InterruptedException {
            synchronized (mList) {
                while (mList.isEmpty()) {
                    mList.wait();
                }
                return mList.remove();
            }
        }

    }

    /** Notify the client that recognition has been aborted. */
    private static void notifyAbort(int modelHandle, LoadedModel model) {
        switch (model.type) {
            case SoundModelType.GENERIC:
                model.callback.recognitionCallback(modelHandle, AidlUtil.newAbortEvent());
                break;

            case SoundModelType.KEYPHRASE:
                model.callback.phraseRecognitionCallback(modelHandle,
                        AidlUtil.newAbortPhraseEvent());
                break;
        }
    }

    @Override
    public void detach() {
        mDelegate.detach();
        mNotifier.unregisterListener(this);
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    // All methods below do trivial delegation - no interesting logic.
    @Override
    public void reboot() {
        mDelegate.reboot();
    }

    @Override
    public Properties getProperties() {
        return mDelegate.getProperties();
    }

    @Override
    public void forceRecognitionEvent(int modelHandle) {
        mDelegate.forceRecognitionEvent(modelHandle);
    }

    @Override
    public int getModelParameter(int modelHandle, int param) {
        return mDelegate.getModelParameter(modelHandle, param);
    }

    @Override
    public void setModelParameter(int modelHandle, int param, int value) {
        mDelegate.setModelParameter(modelHandle, param, value);
    }

    @Override
    public ModelParameterRange queryParameter(int modelHandle, int param) {
        return mDelegate.queryParameter(modelHandle, param);
    }

    @Override
    public String interfaceDescriptor() {
        return mDelegate.interfaceDescriptor();
    }
}
