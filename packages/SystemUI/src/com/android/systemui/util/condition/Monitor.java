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

package com.android.systemui.util.condition;

import android.util.Log;

import com.android.systemui.statusbar.policy.CallbackController;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import javax.inject.Inject;

/**
 * {@link Monitor} takes in a set of conditions, monitors whether all of them have
 * been fulfilled, and informs any registered listeners.
 */
public class Monitor implements CallbackController<Monitor.Callback> {
    private final String mTag = getClass().getSimpleName();

    private final ArrayList<Callback> mCallbacks = new ArrayList<>();

    // Set of all conditions that need to be monitored.
    private final Set<Condition> mConditions;
    private final Executor mExecutor;

    // Whether all conditions have been met.
    private boolean mAllConditionsMet = false;

    // Whether the monitor has started listening for all the conditions.
    private boolean mHaveConditionsStarted = false;

    // Callback for when each condition has been updated.
    private final Condition.Callback mConditionCallback = new Condition.Callback() {
        @Override
        public void onConditionChanged(Condition condition) {
            mExecutor.execute(() -> updateConditionMetState());
        }
    };

    @Inject
    public Monitor(Executor executor, Set<Condition> conditions, Set<Callback> callbacks) {
        mConditions = new HashSet<>();
        mExecutor = executor;

        if (conditions != null) {
            mConditions.addAll(conditions);
        }

        if (callbacks == null) {
            return;
        }

        for (Callback callback : callbacks) {
            addCallbackLocked(callback);
        }
    }

    private void updateConditionMetState() {
        // Overriding conditions do not override each other
        final Collection<Condition> overridingConditions = mConditions.stream()
                .filter(Condition::isOverridingCondition).collect(Collectors.toSet());

        final Collection<Condition> targetCollection = overridingConditions.isEmpty()
                ? mConditions : overridingConditions;

        final boolean newAllConditionsMet = targetCollection.isEmpty() ? true : targetCollection
                .stream()
                .map(Condition::isConditionMet)
                .allMatch(conditionMet -> conditionMet);

        if (newAllConditionsMet == mAllConditionsMet) {
            return;
        }

        if (shouldLog()) Log.d(mTag, "all conditions met: " + newAllConditionsMet);
        mAllConditionsMet = newAllConditionsMet;

        // Updates all callbacks.
        final Iterator<Callback> iterator = mCallbacks.iterator();
        while (iterator.hasNext()) {
            final Callback callback = iterator.next();
            if (callback == null) {
                iterator.remove();
            } else {
                callback.onConditionsChanged(mAllConditionsMet);
            }
        }
    }

    private void addConditionLocked(@NotNull Condition condition) {
        mConditions.add(condition);

        if (!mHaveConditionsStarted) {
            return;
        }

        condition.addCallback(mConditionCallback);
        updateConditionMetState();
    }

    /**
     * Adds a condition for the monitor to listen to and consider when determining whether the
     * overall condition state is met.
     */
    public void addCondition(@NotNull Condition condition) {
        mExecutor.execute(() -> addConditionLocked(condition));
    }

    /**
     * Removes a condition from further consideration.
     */
    public void removeCondition(@NotNull Condition condition) {
        mExecutor.execute(() -> {
            mConditions.remove(condition);

            if (!mHaveConditionsStarted) {
                return;
            }

            condition.removeCallback(mConditionCallback);
            updateConditionMetState();
        });
    }

    private void addCallbackLocked(@NotNull Callback callback) {
        if (mCallbacks.contains(callback)) {
            return;
        }

        if (shouldLog()) Log.d(mTag, "adding callback");
        mCallbacks.add(callback);

        // Updates the callback immediately.
        callback.onConditionsChanged(mAllConditionsMet);

        if (!mHaveConditionsStarted) {
            if (shouldLog()) Log.d(mTag, "starting all conditions");
            mConditions.forEach(condition -> condition.addCallback(mConditionCallback));
            updateConditionMetState();
            mHaveConditionsStarted = true;
        }
    }

    @Override
    public void addCallback(@NotNull Callback callback) {
        mExecutor.execute(() -> addCallbackLocked(callback));
    }

    @Override
    public void removeCallback(@NotNull Callback callback) {
        mExecutor.execute(() -> {
            if (shouldLog()) Log.d(mTag, "removing callback");
            final Iterator<Callback> iterator = mCallbacks.iterator();
            while (iterator.hasNext()) {
                final Callback cb = iterator.next();
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
        });
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
