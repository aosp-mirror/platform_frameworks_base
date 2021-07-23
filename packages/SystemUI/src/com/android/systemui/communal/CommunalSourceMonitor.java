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

package com.android.systemui.communal;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.dagger.SysUISingleton;

import com.google.android.collect.Lists;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

import javax.inject.Inject;

/**
 * A Monitor for reporting a {@link CommunalSource} presence.
 */
@SysUISingleton
public class CommunalSourceMonitor {
    // A list of {@link Callback} that have registered to receive updates.
    private final ArrayList<WeakReference<Callback>> mCallbacks = Lists.newArrayList();

    private CommunalSource mCurrentSource;

    private CommunalSource.Callback mSourceCallback = new CommunalSource.Callback() {
        @Override
        public void onDisconnected() {
            // Clear source reference.
            setSource(null /* source */);
        }
    };

    @VisibleForTesting
    @Inject
    public CommunalSourceMonitor() {
    }

    /**
     * Sets the current {@link CommunalSource}, informing any callbacks. Any existing
     * {@link CommunalSource} will be disconnected.
     *
     * @param source The new {@link CommunalSource}.
     */
    public void setSource(CommunalSource source) {
        if (mCurrentSource != null) {
            mCurrentSource.removeCallback(mSourceCallback);
        }

        mCurrentSource = source;

        // If the new source is valid, inform registered Callbacks of its presence.
        for (int i = 0; i < mCallbacks.size(); i++) {
            Callback cb = mCallbacks.get(i).get();
            if (cb != null) {
                cb.onSourceAvailable(
                        mCurrentSource != null ? new WeakReference<>(mCurrentSource) : null);
            }
        }

        // Add callback to be informed when the source disconnects.
        if (mCurrentSource != null) {
            mCurrentSource.addCallback(mSourceCallback);
        }
    }

    /**
     * Adds a {@link Callback} to receive {@link CommunalSource} updates.
     *
     * @param callback The {@link Callback} to add.
     */
    public void addCallback(Callback callback) {
        mCallbacks.add(new WeakReference<>(callback));

        // Inform the callback of any already present CommunalSource.
        if (mCurrentSource != null) {
            callback.onSourceAvailable(new WeakReference<>(mCurrentSource));
        }
    }

    /**
     * Removes the specified {@link Callback} from receive future updates if present.
     *
     * @param callback The {@link Callback} to add.
     */
    public void removeCallback(Callback callback) {
        mCallbacks.removeIf(el -> el.get() == callback);
    }

    /**
     * Interface implemented to be notified when new {@link CommunalSource} become available.
     */
    public interface Callback {
        /**
         * Called when a new {@link CommunalSource} has been registered. This will also be invoked
         * when a {@link Callback} is first registered and a {@link CommunalSource} is already
         * registered.
         *
         * @param source The new {@link CommunalSource}.
         */
        void onSourceAvailable(WeakReference<CommunalSource> source);
    }
}
