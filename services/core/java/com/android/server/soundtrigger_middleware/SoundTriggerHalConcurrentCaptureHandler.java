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
import android.hardware.soundtrigger.V2_1.ISoundTriggerHw;
import android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback;
import android.hardware.soundtrigger.V2_3.ModelParameterRange;
import android.hardware.soundtrigger.V2_3.Properties;
import android.hardware.soundtrigger.V2_3.RecognitionConfig;
import android.media.permission.SafeCloseable;
import android.media.soundtrigger.RecognitionStatus;
import android.media.soundtrigger.SoundModelType;
import android.media.soundtrigger.Status;
import android.os.IHwBinder;
import android.os.RemoteException;

import java.util.HashSet;
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
    private final @NonNull ISoundTriggerHal mDelegate;
    private GlobalCallback mGlobalCallback;

    /**
     * Information about a model that is currently loaded. This is needed in order to be able to
     * send abort events to its designated callback.
     */
    private static class LoadedModel {
        final int type;
        final @NonNull ModelCallback callback;

        private LoadedModel(int type, @NonNull ModelCallback callback) {
            this.type = type;
            this.callback = callback;
        }
    }

    /**
     * This map holds the model type for every model that is loaded.
     */
    private final @NonNull Map<Integer, LoadedModel> mLoadedModels = new ConcurrentHashMap<>();

    /**
     * A set of all models that are currently active.
     * We use this in order to know which models to stop in case of external capture.
     * Used as a lock to synchronize operations that effect activity.
     */
    private final @NonNull Set<Integer> mActiveModels = new HashSet<>();

    /**
     * Notifier for changes in capture state.
     */
    private final @NonNull ICaptureStateNotifier mNotifier;

    /**
     * Whether capture is active.
     */
    private boolean mCaptureState;

    /**
     * Since we're wrapping the death recipient, we need to keep a translation map for unlinking.
     * Key is the client recipient, value is the wrapper.
     */
    private final @NonNull Map<IHwBinder.DeathRecipient, IHwBinder.DeathRecipient>
            mDeathRecipientMap = new ConcurrentHashMap<>();

    private final @NonNull CallbackThread mCallbackThread = new CallbackThread();

    public SoundTriggerHalConcurrentCaptureHandler(
            @NonNull ISoundTriggerHal delegate,
            @NonNull ICaptureStateNotifier notifier) {
        mDelegate = delegate;
        mNotifier = notifier;
        mCaptureState = mNotifier.registerListener(this);
    }

    @Override
    public void startRecognition(int modelHandle, RecognitionConfig config) {
        synchronized (mActiveModels) {
            if (mCaptureState) {
                throw new RecoverableException(Status.RESOURCE_CONTENTION);
            }
            mDelegate.startRecognition(modelHandle, config);
            mActiveModels.add(modelHandle);
        }
    }

    @Override
    public void stopRecognition(int modelHandle) {
        synchronized (mActiveModels) {
            mDelegate.stopRecognition(modelHandle);
            mActiveModels.remove(modelHandle);
        }
        // Block until all previous events are delivered. Since this is potentially blocking on
        // upward calls, it must be done outside the lock.
        mCallbackThread.flush();
    }

    @Override
    public void onCaptureStateChange(boolean active) {
        synchronized (mActiveModels) {
            if (active) {
                // Abort all active models. This must be done as one transaction to the event
                // thread, in order to be able to dedupe events before they are delivered.
                try (SafeCloseable ignored = mCallbackThread.stallReader()) {
                    for (int modelHandle : mActiveModels) {
                        mDelegate.stopRecognition(modelHandle);
                        LoadedModel model = mLoadedModels.get(modelHandle);
                        // An abort event must be the last one for its model.
                        mCallbackThread.pushWithDedupe(modelHandle, true,
                                () -> notifyAbort(modelHandle, model));
                    }
                }
            } else {
                mGlobalCallback.onResourcesAvailable();
            }

            mCaptureState = active;
        }
    }

    @Override
    public int loadSoundModel(ISoundTriggerHw.SoundModel soundModel, ModelCallback callback) {
        int handle = mDelegate.loadSoundModel(soundModel, new CallbackWrapper(callback));
        mLoadedModels.put(handle, new LoadedModel(SoundModelType.GENERIC, callback));
        return handle;
    }

    @Override
    public int loadPhraseSoundModel(ISoundTriggerHw.PhraseSoundModel soundModel,
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
        mGlobalCallback = new GlobalCallback() {
            @Override
            public void onResourcesAvailable() {
                mCallbackThread.push(callback::onResourcesAvailable);
            }
        };
        mDelegate.registerCallback(mGlobalCallback);
    }

    @Override
    public boolean linkToDeath(IHwBinder.DeathRecipient recipient, long cookie) {
        IHwBinder.DeathRecipient wrapper = new IHwBinder.DeathRecipient() {
            @Override
            public void serviceDied(long cookieBack) {
                mCallbackThread.push(() -> recipient.serviceDied(cookieBack));
            }
        };
        if (mDelegate.linkToDeath(wrapper, cookie)) {
            mDeathRecipientMap.put(recipient, wrapper);
            return true;
        }
        return false;
    }

    @Override
    public boolean unlinkToDeath(IHwBinder.DeathRecipient recipient) {
        return mDelegate.unlinkToDeath(mDeathRecipientMap.remove(recipient));
    }

    private class CallbackWrapper implements ISoundTriggerHal.ModelCallback {
        private final @NonNull ISoundTriggerHal.ModelCallback mDelegateCallback;

        private CallbackWrapper(@NonNull ModelCallback delegateCallback) {
            mDelegateCallback = delegateCallback;
        }

        @Override
        public void recognitionCallback(ISoundTriggerHwCallback.RecognitionEvent event) {
            // A recognition event must be the last one for its model, unless it is a forced one
            // (those leave the model active).
            mCallbackThread.pushWithDedupe(event.header.model,
                    event.header.status != RecognitionStatus.FORCED,
                    () -> mDelegateCallback.recognitionCallback(event));
        }

        @Override
        public void phraseRecognitionCallback(
                ISoundTriggerHwCallback.PhraseRecognitionEvent event) {
            // A recognition event must be the last one for its model, unless it is a forced one
            // (those leave the model active).
            mCallbackThread.pushWithDedupe(event.common.header.model,
                    event.common.header.status != RecognitionStatus.FORCED,
                    () -> mDelegateCallback.phraseRecognitionCallback(event));
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
     * <li>Events can be deduped upon entry to the queue. This is achieved as follows:
     * <ul>
     *     <li>Temporarily stall the reader via {@link #stallReader()}.
     *     <li>Within this scope, push as many events as needed via
     *     {@link #pushWithDedupe(int, boolean, Runnable)}.
     *     If an event with the same model handle as the one being pushed is already in the queue
     *     and has been marked as "lastForModel", the new event will be discarded before entering
     *     the queue.
     *     <li>Finally, un-stall the reader by existing the scope.
     *     <li>Events that do not require deduping can be pushed via {@link #push(Runnable)}.
     * </ul>
     * <li>Events can be flushed via {@link #flush()}. This will block until all events pushed prior
     * to this call have been fully processed.
     * </ul>
     */
    private static class CallbackThread {
        private static class Entry {
            final boolean lastForModel;
            final int modelHandle;
            final Runnable runnable;

            private Entry(boolean lastForModel, int modelHandle, Runnable runnable) {
                this.lastForModel = lastForModel;
                this.modelHandle = modelHandle;
                this.runnable = runnable;
            }
        }

        private boolean mStallReader = false;
        private final Queue<Entry> mList = new LinkedList<>();
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
            pushEntry(new Entry(false, 0, runnable), false);
        }


        /**
         * Push a new runnable to the queue, with deduping.
         * If an entry with the same model handle is already in the queue and was designated as
         * last for model, this one will be discarded.
         *
         * @param modelHandle The model handle, used for deduping purposes.
         * @param lastForModel If true, this entry will be considered the last one for this model
         *                     and any subsequence calls for this handle (whether lastForModel or
         *                     not) will be discarded while this entry is in the queue.
         * @param runnable    The runnable to push.
         */
        void pushWithDedupe(int modelHandle, boolean lastForModel, Runnable runnable) {
            pushEntry(new Entry(lastForModel, modelHandle, runnable), true);
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

        /**
         * Creates a scope (using a try-with-resources block), within which events that are pushed
         * remain queued and processed. This is useful in order to utilize deduping.
         */
        SafeCloseable stallReader() {
            synchronized (mList) {
                mStallReader = true;
                return () -> {
                    synchronized (mList) {
                        mStallReader = false;
                        mList.notifyAll();
                    }
                };
            }
        }

        private void pushEntry(Entry entry, boolean dedupe) {
            synchronized (mList) {
                if (dedupe) {
                    for (Entry existing : mList) {
                        if (existing.lastForModel && existing.modelHandle == entry.modelHandle) {
                            return;
                        }
                    }
                }
                mList.add(entry);
                mPushCount++;
                mList.notifyAll();
            }
        }

        private Runnable pop() throws InterruptedException {
            synchronized (mList) {
                while (mStallReader || mList.isEmpty()) {
                    mList.wait();
                }
                return mList.remove().runnable;
            }
        }
    }

    /** Notify the client that recognition has been aborted. */
    private static void notifyAbort(int modelHandle, LoadedModel model) {
        switch (model.type) {
            case SoundModelType.GENERIC: {
                ISoundTriggerHwCallback.RecognitionEvent event =
                        new ISoundTriggerHwCallback.RecognitionEvent();
                event.header.model = modelHandle;
                event.header.status =
                        android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback.RecognitionStatus.ABORT;
                event.header.type = android.hardware.soundtrigger.V2_0.SoundModelType.GENERIC;
                model.callback.recognitionCallback(event);
            }
            break;

            case SoundModelType.KEYPHRASE: {
                ISoundTriggerHwCallback.PhraseRecognitionEvent event =
                        new ISoundTriggerHwCallback.PhraseRecognitionEvent();
                event.common.header.model = modelHandle;
                event.common.header.status =
                        android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback.RecognitionStatus.ABORT;
                event.common.header.type =
                        android.hardware.soundtrigger.V2_0.SoundModelType.KEYPHRASE;
                model.callback.phraseRecognitionCallback(event);
            }
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
    public void getModelState(int modelHandle) {
        mDelegate.getModelState(modelHandle);
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
    public String interfaceDescriptor() throws RemoteException {
        return mDelegate.interfaceDescriptor();
    }
}
