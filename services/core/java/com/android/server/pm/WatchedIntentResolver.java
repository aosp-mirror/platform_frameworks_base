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

package com.android.server.pm;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.IntentFilter;

import com.android.server.IntentResolver;
import com.android.server.pm.snapshot.PackageDataSnapshot;
import com.android.server.utils.Snappable;
import com.android.server.utils.Watchable;
import com.android.server.utils.WatchableImpl;
import com.android.server.utils.Watcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * A watched {@link IntentResolver}.  The parameters are inherited from the superclass.
 * @param <F> The filter type
 * @param <R> The resolver type.
 * {@hide}
 */
public abstract class WatchedIntentResolver<F extends WatchedIntentFilter,
                                            R extends WatchedIntentFilter>
        extends IntentResolver<F, R>
        implements Watchable, Snappable {

    /**
     * Watchable machinery
     */
    private final Watchable mWatchable = new WatchableImpl();

    /**
     * Register an observer to receive change notifications.
     * @param observer The observer to register.
     */
    @Override
    public void registerObserver(@NonNull Watcher observer) {
        mWatchable.registerObserver(observer);
    }

    /**
     * Unregister the observer, which will no longer receive change notifications.
     * @param observer The observer to unregister.
     */
    @Override
    public void unregisterObserver(@NonNull Watcher observer) {
        mWatchable.unregisterObserver(observer);
    }

    /**
     * Return true if the {@link Watcher) is a registered observer.
     * @param observer A {@link Watcher} that might be registered
     * @return true if the observer is registered with this {@link Watchable}.
     */
    @Override
    public boolean isRegisteredObserver(@NonNull Watcher observer) {
        return mWatchable.isRegisteredObserver(observer);
    }

    /**
     * Notify listeners that the object has changd.  The argument is a hint as to the
     * source of the change.
     * @param what The attribute or sub-object that changed, if not null.
     */
    @Override
    public void dispatchChange(@Nullable Watchable what) {
        mWatchable.dispatchChange(what);
    }

    private final Watcher mWatcher = new Watcher() {
            @Override
            public void onChange(@Nullable Watchable what) {
                dispatchChange(what);
            }
        };

    /**
     * Notify listeners that this object has changed.
     */
    protected void onChanged() {
        dispatchChange(this);
    }

    @Override
    public void addFilter(@Nullable PackageDataSnapshot snapshot, F f) {
        super.addFilter(snapshot, f);
        f.registerObserver(mWatcher);
        onChanged();
    }

    @Override
    public void removeFilter(F f) {
        f.unregisterObserver(mWatcher);
        super.removeFilter(f);
        onChanged();
    }

    @Override
    protected void removeFilterInternal(F f) {
        f.unregisterObserver(mWatcher);
        super.removeFilterInternal(f);
        onChanged();
    }

    // Sorts a List of IntentFilter objects into descending priority order.
    @SuppressWarnings("rawtypes")
    private static final Comparator<WatchedIntentFilter> sResolvePrioritySorter =
            new Comparator<>() {
        public int compare(WatchedIntentFilter o1, WatchedIntentFilter o2) {
            final int q1 = o1.getPriority();
            final int q2 = o2.getPriority();
            return (q1 > q2) ? -1 : ((q1 < q2) ? 1 : 0);
        }
    };

    @Override
    @SuppressWarnings("unchecked")
    protected void sortResults(List<R> results) {
        Collections.sort(results, sResolvePrioritySorter);
    }

    /**
     * @see IntentResolver#findFilters(IntentFilter)
     */
    public ArrayList<F> findFilters(WatchedIntentFilter matching) {
        return super.findFilters(matching.getIntentFilter());
    }

    // Make <this> a copy of <orig>.  The presumption is that <this> is empty but all
    // arrays are cleared out explicitly, just to be sure.
    protected void copyFrom(WatchedIntentResolver orig) {
        super.copyFrom(orig);
    }
}
