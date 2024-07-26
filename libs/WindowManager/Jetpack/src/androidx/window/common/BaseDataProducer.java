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

package androidx.window.common;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Base class that manages listeners when listening to a piece of data that changes.  This class is
 * thread safe for adding, removing, and notifying consumers.
 *
 * @param <T> The type of data this producer returns through {@link BaseDataProducer#getData}.
 */
public abstract class BaseDataProducer<T> implements
        AcceptOnceConsumer.AcceptOnceProducerCallback<T> {

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final Set<Consumer<T>> mCallbacks = new LinkedHashSet<>();
    @GuardedBy("mLock")
    private final Set<Consumer<T>> mCallbacksToRemove = new HashSet<>();

    /**
     * Emits the first available data at that point in time.
     * @param dataConsumer a {@link Consumer} that will receive one value.
     */
    public abstract void getData(@NonNull Consumer<T> dataConsumer);

    /**
     * Adds a callback to the set of callbacks listening for data. Data is delivered through
     * {@link BaseDataProducer#notifyDataChanged(Object)}. This method is thread safe. Callers
     * should ensure that callbacks are thread safe.
     * @param callback that will receive data from the producer.
     */
    public final void addDataChangedCallback(@NonNull Consumer<T> callback) {
        synchronized (mLock) {
            mCallbacks.add(callback);
        }
        Optional<T> currentData = getCurrentData();
        currentData.ifPresent(callback);
        onListenersChanged();
    }

    /**
     * Removes a callback to the set of callbacks listening for data. This method is thread safe
     * for adding.
     * @param callback that was registered in
     * {@link BaseDataProducer#addDataChangedCallback(Consumer)}.
     */
    public final void removeDataChangedCallback(@NonNull Consumer<T> callback) {
        synchronized (mLock) {
            mCallbacks.remove(callback);
        }
        onListenersChanged();
    }

    /**
     * Returns {@code true} if there are any registered callbacks {@code false} if there are no
     * registered callbacks.
     */
    // TODO(b/278132889) Improve the structure of BaseDataProdcuer while avoiding known issues.
    public final boolean hasListeners() {
        synchronized (mLock) {
            return !mCallbacks.isEmpty();
        }
    }

    protected void onListenersChanged() {}

    /**
     * @return the current data if available and {@code Optional.empty()} otherwise.
     */
    @NonNull
    public abstract Optional<T> getCurrentData();

    /**
     * Called to notify all registered consumers that the data provided
     * by {@link BaseDataProducer#getData} has changed. Calls to this are thread save but callbacks
     * need to ensure thread safety.
     */
    protected void notifyDataChanged(T value) {
        synchronized (mLock) {
            for (Consumer<T> callback : mCallbacks) {
                callback.accept(value);
            }
            removeFinishedCallbacksLocked();
        }
    }

    /**
     * Removes any callbacks that notified us through {@link #onConsumerReadyToBeRemoved(Consumer)}
     * that they are ready to be removed.
     */
    @GuardedBy("mLock")
    private void removeFinishedCallbacksLocked() {
        for (Consumer<T> callback: mCallbacksToRemove) {
            mCallbacks.remove(callback);
        }
        mCallbacksToRemove.clear();
    }

    @Override
    public void onConsumerReadyToBeRemoved(Consumer<T> callback) {
        synchronized (mLock) {
            mCallbacksToRemove.add(callback);
        }
    }
}
