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

package com.android.server.utils;

import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.Objects;

/**
 * A concrete implementation of {@link Watchable}.  This includes one commonly needed feature:
 * the Watchable may be sealed, so that it throws an {@link IllegalStateException} if
 * a change is detected.
 */
public class WatchableImpl implements Watchable {
    /**
     * The list of observers.
     */
    protected final ArrayList<Watcher> mObservers = new ArrayList<>();

    /**
     * Ensure the observer is the list. The observer cannot be null but it is okay if it
     * is already in the list.
     *
     * @param observer The {@link} Watcher to be added to the notification list.
     */
    @Override
    public void registerObserver(@NonNull Watcher observer) {
        Objects.requireNonNull(observer, "observer may not be null");
        synchronized (mObservers) {
            if (!mObservers.contains(observer)) {
                mObservers.add(observer);
            }
        }
    }

    /**
     * Removes a previously registered observer. The observer must not be null and it
     * must already have been registered.
     *
     * @param observer The {@link} Watcher to be removed from the notification list.
     */
    @Override
    public void unregisterObserver(@NonNull Watcher observer) {
        Objects.requireNonNull(observer, "observer may not be null");
        synchronized (mObservers) {
            mObservers.remove(observer);
        }
    }

    /**
     * Return true if the {@link Watcher) is a registered observer.
     * @param observer A {@link Watcher} that might be registered
     * @return true if the observer is registered with this {@link Watchable}.
     */
    @Override
    public boolean isRegisteredObserver(@NonNull Watcher observer) {
        synchronized (mObservers) {
            return mObservers.contains(observer);
        }
    }

    /**
     * Return the number of registered observers.
     *
     * @return The number of registered observers.
     */
    public int registeredObserverCount() {
        return mObservers.size();
    }

    /**
     * Invokes {@link Watcher#onChange} on each observer.
     *
     * @param what The {@link Watchable} that generated the event
     */
    @Override
    public void dispatchChange(@Nullable Watchable what) {
        synchronized (mObservers) {
            if (mSealed) {
                throw new IllegalStateException("attempt to change a sealed object");
            }
            final int end = mObservers.size();
            for (int i = 0; i < end; i++) {
                mObservers.get(i).onChange(what);
            }
        }
    }

    /**
     * True if the object is sealed.
     */
    @GuardedBy("mObservers")
    private boolean mSealed = false;

    /**
     * Freeze the {@link Watchable}.
     */
    public void seal() {
        synchronized (mObservers) {
            mSealed = true;
        }
    }

    /**
     * Return the sealed state.
     */
    public boolean isSealed() {
        synchronized (mObservers) {
            return mSealed;
        }
    }
}
