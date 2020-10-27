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

/**
 * A listener registration that stores its own key, and thus can remove itself. By default it will
 * remove itself if any checked exception occurs on listener execution.
 *
 * @param <TRequest>           request type
 * @param <TListener>          listener type
 */
public abstract class RemovableListenerRegistration<TRequest, TListener> extends
        RequestListenerRegistration<TRequest, TListener> {

    private volatile @Nullable Object mKey;

    protected RemovableListenerRegistration(Executor executor, @Nullable TRequest request,
            TListener listener) {
        super(executor, request, listener);
    }

    /**
     * Must be implemented to return the {@link ListenerMultiplexer} this registration is registered
     * with. Often this is easiest to accomplish by defining registration subclasses as non-static
     * inner classes of the multiplexer they are to be used with.
     */
    protected abstract ListenerMultiplexer<?, ? super TListener, ?, ?> getOwner();

    /**
     * Returns the key associated with this registration. May not be invoked before
     * {@link #onRegister(Object)} or after {@link #onUnregister()}.
     */
    protected final Object getKey() {
        return Objects.requireNonNull(mKey);
    }

    /**
     * Removes this registration. Does nothing if invoked before {@link #onRegister(Object)} or
     * after {@link #onUnregister()}. It is safe to invoke this from within either function.
     */
    public final void remove() {
        Object key = mKey;
        if (key != null) {
            getOwner().removeRegistration(key, this);
        }
    }

    @Override
    protected final void onRegister(Object key) {
        mKey = Objects.requireNonNull(key);
        onRemovableListenerRegister();
    }

    @Override
    protected final void onUnregister() {
        onRemovableListenerUnregister();
        mKey = null;
    }

    /**
     * May be overridden in place of {@link #onRegister(Object)}.
     */
    protected void onRemovableListenerRegister() {}

    /**
     * May be overridden in place of {@link #onUnregister()}.
     */
    protected void onRemovableListenerUnregister() {}
}
