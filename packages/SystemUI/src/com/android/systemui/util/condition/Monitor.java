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

import android.util.ArraySet;
import android.util.Log;

import com.android.systemui.dagger.qualifiers.Main;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import javax.inject.Inject;

/**
 * {@link Monitor} takes in a set of conditions, monitors whether all of them have
 * been fulfilled, and informs any registered listeners.
 */
public class Monitor {
    private final String mTag = getClass().getSimpleName();
    private final Executor mExecutor;

    private final HashMap<Condition, ArraySet<Subscription.Token>> mConditions = new HashMap<>();
    private final HashMap<Subscription.Token, SubscriptionState> mSubscriptions = new HashMap<>();

    private static class SubscriptionState {
        private final Subscription mSubscription;
        private Boolean mAllConditionsMet;

        SubscriptionState(Subscription subscription) {
            mSubscription = subscription;
        }

        public Set<Condition> getConditions() {
            return mSubscription.mConditions;
        }

        public void update() {
            // Overriding conditions do not override each other
            final Collection<Condition> overridingConditions = mSubscription.mConditions.stream()
                    .filter(Condition::isOverridingCondition).collect(Collectors.toSet());

            final Collection<Condition> targetCollection = overridingConditions.isEmpty()
                    ? mSubscription.mConditions : overridingConditions;

            final boolean newAllConditionsMet = targetCollection.isEmpty() ? true : targetCollection
                    .stream()
                    .map(Condition::isConditionMet)
                    .allMatch(conditionMet -> conditionMet);

            if (mAllConditionsMet != null && newAllConditionsMet == mAllConditionsMet) {
                return;
            }

            mAllConditionsMet = newAllConditionsMet;
            mSubscription.mCallback.onConditionsChanged(mAllConditionsMet);
        }
    }

    // Callback for when each condition has been updated.
    private final Condition.Callback mConditionCallback = new Condition.Callback() {
        @Override
        public void onConditionChanged(Condition condition) {
            mExecutor.execute(() -> updateConditionMetState(condition));
        }
    };

    @Inject
    public Monitor(@Main Executor executor) {
        mExecutor = executor;
    }

    private void updateConditionMetState(Condition condition) {
        final ArraySet<Subscription.Token> subscriptions = mConditions.get(condition);

        // It's possible the condition was removed between the time the callback occurred and
        // update was executed on the main thread.
        if (subscriptions == null) {
            return;
        }

        subscriptions.stream().forEach(token -> mSubscriptions.get(token).update());
    }

    /**
     * Registers a callback and the set of conditions to trigger it.
     * @param subscription A {@link Subscription} detailing the desired conditions and callback.
     * @return A {@link Subscription.Token} that can be used to remove the subscription.
     */
    public Subscription.Token addSubscription(@NotNull Subscription subscription) {
        final Subscription.Token token = new Subscription.Token();
        final SubscriptionState state = new SubscriptionState(subscription);

        mExecutor.execute(() -> {
            mSubscriptions.put(token, state);

            // Add and associate conditions.
            subscription.getConditions().stream().forEach(condition -> {
                if (!mConditions.containsKey(condition)) {
                    mConditions.put(condition, new ArraySet<>());
                    condition.addCallback(mConditionCallback);
                }

                mConditions.get(condition).add(token);
            });

            // Update subscription state.
            state.update();

        });
        return token;
    }

    /**
     * Removes a subscription from participating in future callbacks.
     * @param token The {@link Subscription.Token} returned when the {@link Subscription} was
     *              originally added.
     */
    public void removeSubscription(@NotNull Subscription.Token token) {
        mExecutor.execute(() -> {
            if (shouldLog()) Log.d(mTag, "removing callback");
            if (!mSubscriptions.containsKey(token)) {
                Log.e(mTag, "subscription not present:" + token);
                return;
            }

            mSubscriptions.remove(token).getConditions().forEach(condition -> {
                if (!mConditions.containsKey(condition)) {
                    Log.e(mTag, "condition not present:" + condition);
                    return;

                }
                final Set<Subscription.Token> conditionSubscriptions = mConditions.get(condition);

                conditionSubscriptions.remove(token);
                if (conditionSubscriptions.isEmpty()) {
                    condition.removeCallback(mConditionCallback);
                    mConditions.remove(condition);
                }
            });
        });
    }

    private boolean shouldLog() {
        return Log.isLoggable(mTag, Log.DEBUG);
    }

    /**
     * A {@link Subscription} represents a set of conditions and a callback that is informed when
     * these conditions change.
     */
    public static class Subscription {
        private final Set<Condition> mConditions;
        private final Callback mCallback;

        /** */
        public Subscription(Set<Condition> conditions, Callback callback) {
            this.mConditions = Collections.unmodifiableSet(conditions);
            this.mCallback = callback;
        }

        public Set<Condition> getConditions() {
            return mConditions;
        }

        public Callback getCallback() {
            return mCallback;
        }

        /**
         * A {@link Token} is an identifier that is associated with a {@link Subscription} which is
         * registered with a {@link Monitor}.
         */
        public static class Token {
        }

        /**
         * {@link Builder} is a helper class for constructing a {@link Subscription}.
         */
        public static class Builder {
            private final Callback mCallback;
            private final ArraySet<Condition> mConditions;

            /**
             * Default constructor specifying the {@link Callback} for the {@link Subscription}.
             * @param callback
             */
            public Builder(Callback callback) {
                mCallback = callback;
                mConditions = new ArraySet<>();
            }

            /**
             * Adds a {@link Condition} to be associated with the {@link Subscription}.
             * @param condition
             * @return The updated {@link Builder}.
             */
            public Builder addCondition(Condition condition) {
                mConditions.add(condition);
                return this;
            }

            /**
             * Adds a set of {@link Condition} to be associated with the {@link Subscription}.
             * @param condition
             * @return The updated {@link Builder}.
             */
            public Builder addConditions(Set<Condition> condition) {
                mConditions.addAll(condition);
                return this;
            }

            /**
             * Builds the {@link Subscription}.
             * @return The resulting {@link Subscription}.
             */
            public Subscription build() {
                return new Subscription(mConditions, mCallback);
            }
        }
    }

    /**
     * Callback that receives updates of whether all conditions have been fulfilled.
     */
    public interface Callback {
        /**
         * Returns the conditions associated with this callback.
         */
        default ArrayList<Condition> getConditions() {
            return new ArrayList<>();
        }

        /**
         * Triggered when the fulfillment of all conditions have been met.
         *
         * @param allConditionsMet True if all conditions have been fulfilled. False if none or
         *                         only partial conditions have been fulfilled.
         */
        void onConditionsChanged(boolean allConditionsMet);
    }
}
