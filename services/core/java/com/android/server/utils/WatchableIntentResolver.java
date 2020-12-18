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

import com.android.server.IntentResolver;

import java.util.List;

/**
 * A watched {@link IntentResolver}.  The parameters are inherited from the superclass.
 * @param <F> The filter type
 * @param <R> The resolver type.
 * {@hide}
 */
public abstract class WatchableIntentResolver<F, R extends Object>
        extends IntentResolver<F, R>
        implements Watchable {

    /**
     * Watchable machinery
     */
    private final Watchable mWatchable = new WatchableImpl();
    /**
     * Register an observer to receive change notifications.
     * @param observer The observer to register.
     */
    public void registerObserver(@NonNull Watcher observer) {
        mWatchable.registerObserver(observer);
    }
    /**
     * Unregister the observer, which will no longer receive change notifications.
     * @param observer The observer to unregister.
     */
    public void unregisterObserver(@NonNull Watcher observer) {
        mWatchable.unregisterObserver(observer);
    }
    /**
     * Notify listeners that the object has changd.  The argument is a hint as to the
     * source of the change.
     * @param what The attribute or sub-object that changed, if not null.
     */
    public void dispatchChange(@Nullable Watchable what) {
        mWatchable.dispatchChange(what);
    }
    /**
     * Notify listeners that this object has changed.
     */
    protected void onChanged() {
        dispatchChange(this);
    }

    @Override
    public void addFilter(F f) {
        super.addFilter(f);
        onChanged();
    }

    @Override
    public void removeFilter(F f) {
        super.removeFilter(f);
        onChanged();
    }

    @Override
    protected void removeFilterInternal(F f) {
        super.removeFilterInternal(f);
        onChanged();
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void sortResults(List<R> results) {
        super.sortResults(results);
        onChanged();
    }
}
