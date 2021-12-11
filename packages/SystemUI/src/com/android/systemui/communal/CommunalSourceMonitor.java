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

import static com.android.systemui.communal.dagger.CommunalModule.COMMUNAL_CONDITIONS;

import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.util.condition.Monitor;

import com.google.android.collect.Lists;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * A Monitor for reporting a {@link CommunalSource} presence.
 */
@SysUISingleton
public class CommunalSourceMonitor {
    private static final String TAG = "CommunalSourceMonitor";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    // A list of {@link Callback} that have registered to receive updates.
    private final ArrayList<WeakReference<Callback>> mCallbacks = Lists.newArrayList();
    private final Monitor mConditionsMonitor;
    private final Executor mExecutor;

    private CommunalSource mCurrentSource;

    // Whether all conditions for communal mode to show have been met.
    private boolean mAllCommunalConditionsMet = false;

    // Whether the class is currently listening for condition changes.
    private boolean mListeningForConditions = false;

    private final Monitor.Callback mConditionsCallback =
            allConditionsMet -> {
                if (mAllCommunalConditionsMet != allConditionsMet) {
                    if (DEBUG) Log.d(TAG, "communal conditions changed: " + allConditionsMet);

                    mAllCommunalConditionsMet = allConditionsMet;
                    executeOnSourceAvailableCallbacks();
                }
            };

    @VisibleForTesting
    @Inject
    public CommunalSourceMonitor(@Main Executor executor,
            @Named(COMMUNAL_CONDITIONS) Monitor communalConditionsMonitor) {
        mExecutor = executor;
        mConditionsMonitor = communalConditionsMonitor;
    }

    /**
     * Sets the current {@link CommunalSource}, informing any callbacks. Any existing
     * {@link CommunalSource} will be disconnected.
     *
     * @param source The new {@link CommunalSource}.
     */
    public void setSource(CommunalSource source) {
        mCurrentSource = source;

        if (mAllCommunalConditionsMet) {
            executeOnSourceAvailableCallbacks();
        }
    }

    private void executeOnSourceAvailableCallbacks() {
        mExecutor.execute(() -> {
            // If the new source is valid, inform registered Callbacks of its presence.
            Iterator<WeakReference<Callback>> itr = mCallbacks.iterator();
            while (itr.hasNext()) {
                Callback cb = itr.next().get();
                if (cb == null) {
                    itr.remove();
                } else {
                    cb.onSourceAvailable(
                            (mAllCommunalConditionsMet && mCurrentSource != null)
                                    ? new WeakReference<>(mCurrentSource) : null);
                }
            }
        });
    }

    /**
     * Adds a {@link Callback} to receive {@link CommunalSource} updates.
     *
     * @param callback The {@link Callback} to add.
     */
    public void addCallback(Callback callback) {
        mExecutor.execute(() -> {
            mCallbacks.add(new WeakReference<>(callback));

            // Inform the callback of any already present CommunalSource.
            if (mAllCommunalConditionsMet && mCurrentSource != null) {
                callback.onSourceAvailable(new WeakReference<>(mCurrentSource));
            }

            if (!mListeningForConditions) {
                mConditionsMonitor.addCallback(mConditionsCallback);
                mListeningForConditions = true;
            }
        });
    }

    /**
     * Removes the specified {@link Callback} from receive future updates if present.
     *
     * @param callback The {@link Callback} to add.
     */
    public void removeCallback(Callback callback) {
        mExecutor.execute(() -> {
            mCallbacks.removeIf(el -> el.get() == callback);

            if (mCallbacks.isEmpty() && mListeningForConditions) {
                mConditionsMonitor.removeCallback(mConditionsCallback);
                mListeningForConditions = false;
            }
        });
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
