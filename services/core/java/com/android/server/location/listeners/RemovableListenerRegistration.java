/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.location.listeners;

import android.annotation.Nullable;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A listener registration that stores its own key, and thus can remove itself. By default it will
 * remove itself if any checked exception occurs on listener execution.
 *
 * @param <TKey>               key type
 * @param <TListener>          listener type
 */
public abstract class RemovableListenerRegistration<TKey, TListener> extends
        ListenerRegistration<TListener> {

    @Nullable private volatile TKey mKey;
    private final AtomicBoolean mRemoved = new AtomicBoolean(false);

    protected RemovableListenerRegistration(Executor executor, TListener listener) {
        super(executor, listener);
    }

    /**
     * Must be implemented to return the {@link ListenerMultiplexer} this registration is registered
     * with. Often this is easiest to accomplish by defining registration subclasses as non-static
     * inner classes of the multiplexer they are to be used with.
     */
    protected abstract ListenerMultiplexer<TKey, ? super TListener, ?, ?> getOwner();

    /**
     * Returns the key associated with this registration. May not be invoked before
     * {@link #onRegister(Object)} or after {@link #onUnregister()}.
     */
    protected final TKey getKey() {
        return Objects.requireNonNull(mKey);
    }

    /**
     * Convenience method equivalent to invoking {@link #remove(boolean)} with the
     * {@code immediately} parameter set to true.
     */
    public final void remove() {
        remove(true);
    }

    /**
     * Removes this registration. If the {@code immediately} parameter is true, all pending listener
     * invocations will fail. If the {@code immediately} parameter is false, listener invocations
     * that were scheduled before remove was invoked (including invocations scheduled within {@link
     * #onRemove(boolean)}) will continue, but any listener invocations scheduled after remove was
     * invoked will fail.
     *
     * <p>Only the first call to this method will ever go through (and so {@link #onRemove(boolean)}
     * will only ever be invoked once).
     *
     * <p>Does nothing if invoked before {@link #onRegister()} or after {@link #onUnregister()}.
     */
    public final void remove(boolean immediately) {
        TKey key = mKey;
        if (key != null && !mRemoved.getAndSet(true)) {
            onRemove(immediately);
            if (immediately) {
                getOwner().removeRegistration(key, this);
            } else {
                executeOperation(listener -> getOwner().removeRegistration(key, this));
            }
        }
    }

    /**
     * Invoked just before this registration is removed due to {@link #remove(boolean)}, on the same
     * thread as the responsible {@link #remove(boolean)} call.
     *
     * <p>This method will only ever be invoked once, no matter how many calls to {@link
     * #remove(boolean)} are made, as any registration can only be removed once.
     */
    protected void onRemove(boolean immediately) {}

    @Override
    protected final void onRegister(Object key) {
        super.onRegister(key);
        mKey = (TKey) Objects.requireNonNull(key);
        onRegister();
    }

    /**
     * May be overridden by subclasses. Invoked when registration occurs. Invoked while holding the
     * owning multiplexer's internal lock.
     *
     * <p>If overridden you must ensure the superclass method is invoked (usually as the first thing
     * in the overridden method).
     */
    protected void onRegister() {}

    @Override
    protected void onUnregister() {
        mKey = null;
        super.onUnregister();
    }
}
