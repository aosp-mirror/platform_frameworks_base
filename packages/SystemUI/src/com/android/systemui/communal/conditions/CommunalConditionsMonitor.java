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

package com.android.systemui.communal.conditions;

import static com.android.systemui.communal.dagger.CommunalModule.COMMUNAL_CONDITIONS;

import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.statusbar.policy.CallbackController;

import org.jetbrains.annotations.NotNull;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * {@link CommunalConditionsMonitor} takes in a set of conditions, monitors whether all of them have
 * been fulfilled, and informs any registered listeners.
 */
@SysUISingleton
public class CommunalConditionsMonitor implements
        CallbackController<CommunalConditionsMonitor.Callback> {
    private final String mTag = getClass().getSimpleName();

    private final ArrayList<WeakReference<Callback>> mCallbacks = new ArrayList<>();

    // Set of all conditions that need to be monitored.
    private final Set<CommunalCondition> mConditions;

    // Map of values of each condition.
    private final HashMap<CommunalCondition, Boolean> mConditionsMap = new HashMap<>();

    // Whether all conditions have been met.
    private boolean mAllConditionsMet = false;

    // Whether the monitor has started listening for all the conditions.
    private boolean mHaveConditionsStarted = false;

    // Callback for when each condition has been updated.
    private final CommunalCondition.Callback mConditionCallback = (condition, isConditionMet) -> {
        mConditionsMap.put(condition, isConditionMet);

        final boolean newAllConditionsMet = !mConditionsMap.containsValue(false);

        if (newAllConditionsMet == mAllConditionsMet) {
            return;
        }

        if (shouldLog()) Log.d(mTag, "all conditions met: " + newAllConditionsMet);
        mAllConditionsMet = newAllConditionsMet;

        // Updates all callbacks.
        final Iterator<WeakReference<Callback>> iterator = mCallbacks.iterator();
        while (iterator.hasNext()) {
            final Callback callback = iterator.next().get();
            if (callback == null) {
                iterator.remove();
            } else {
                callback.onConditionsChanged(mAllConditionsMet);
            }
        }
    };

    @Inject
    public CommunalConditionsMonitor(
            @Named(COMMUNAL_CONDITIONS) Set<CommunalCondition> communalConditions) {
        mConditions = communalConditions;

        // Initializes the conditions map and registers a callback for each condition.
        mConditions.forEach((condition -> mConditionsMap.put(condition, false)));
    }

    @Override
    public void addCallback(@NotNull Callback callback) {
        if (shouldLog()) Log.d(mTag, "adding callback");
        mCallbacks.add(new WeakReference<>(callback));

        // Updates the callback immediately.
        callback.onConditionsChanged(mAllConditionsMet);

        if (!mHaveConditionsStarted) {
            if (shouldLog()) Log.d(mTag, "starting all conditions");
            mConditions.forEach(condition -> condition.addCallback(mConditionCallback));
            mHaveConditionsStarted = true;
        }
    }

    @Override
    public void removeCallback(@NotNull Callback callback) {
        if (shouldLog()) Log.d(mTag, "removing callback");
        final Iterator<WeakReference<Callback>> iterator = mCallbacks.iterator();
        while (iterator.hasNext()) {
            final Callback cb = iterator.next().get();
            if (cb == null || cb == callback) {
                iterator.remove();
            }
        }

        if (mCallbacks.isEmpty() && mHaveConditionsStarted) {
            if (shouldLog()) Log.d(mTag, "stopping all conditions");
            mConditions.forEach(condition -> condition.removeCallback(mConditionCallback));

            mAllConditionsMet = false;
            mHaveConditionsStarted = false;
        }
    }

    /**
     * Force updates each condition to the value provided.
     */
    @VisibleForTesting
    public void overrideAllConditionsMet(boolean value) {
        mConditions.forEach(condition -> condition.updateCondition(value));
    }

    private boolean shouldLog() {
        return Log.isLoggable(mTag, Log.DEBUG);
    }

    /**
     * Callback that receives updates of whether all conditions have been fulfilled.
     */
    public interface Callback {
        /**
         * Triggered when the fulfillment of all conditions have been met.
         *
         * @param allConditionsMet True if all conditions have been fulfilled. False if none or
         *                         only partial conditions have been fulfilled.
         */
        void onConditionsChanged(boolean allConditionsMet);
    }
}
