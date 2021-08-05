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

package com.android.server.tare;

import android.annotation.NonNull;
import android.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Interface for the system server to deal with the resource economy subsystem.
 *
 * @hide
 */
public interface EconomyManagerInternal {
    /**
     * Used to indicate a future action an app is expected to take.
     */
    final class AnticipatedAction {
        public final int actionId;
        public final int numInstantaneousCalls;
        public final long ongoingDurationMs;
        private final int mHashCode;

        /**
         * @param actionId              The expected action
         * @param numInstantaneousCalls How many instantaneous times the action will be performed
         * @param ongoingDurationMs     An estimate of how long the ongoing event will go on for
         */
        public AnticipatedAction(@EconomicPolicy.AppAction int actionId,
                int numInstantaneousCalls, long ongoingDurationMs) {
            this.actionId = actionId;
            this.numInstantaneousCalls = numInstantaneousCalls;
            this.ongoingDurationMs = ongoingDurationMs;

            int hash = 0;
            hash = 31 * hash + actionId;
            hash = 31 * hash + numInstantaneousCalls;
            hash = 31 * hash + (int) (ongoingDurationMs ^ (ongoingDurationMs >>> 32));
            mHashCode = hash;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            AnticipatedAction that = (AnticipatedAction) o;
            return actionId == that.actionId
                    && numInstantaneousCalls == that.numInstantaneousCalls
                    && ongoingDurationMs == that.ongoingDurationMs;
        }

        @Override
        public int hashCode() {
            return mHashCode;
        }
    }

    /**
     * A collection of {@link AnticipatedAction}s that will be performed together.
     */
    final class ActionBill {
        private static final Comparator<AnticipatedAction>
                sAnticipatedActionComparator = Comparator.comparingInt(aa -> aa.actionId);

        private final List<AnticipatedAction> mAnticipatedActions;
        private final int mHashCode;

        public ActionBill(@NonNull List<AnticipatedAction> anticipatedActions) {
            List<AnticipatedAction> actions = new ArrayList<>(anticipatedActions);
            actions.sort(sAnticipatedActionComparator);
            mAnticipatedActions = Collections.unmodifiableList(actions);

            int hash = 0;
            for (int i = 0; i < mAnticipatedActions.size(); ++i) {
                hash = 31 * hash + mAnticipatedActions.get(i).hashCode();
            }
            mHashCode = hash;
        }

        List<AnticipatedAction> getAnticipatedActions() {
            return mAnticipatedActions;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ActionBill that = (ActionBill) o;
            return mAnticipatedActions.equals(that.mAnticipatedActions);
        }

        @Override
        public int hashCode() {
            return mHashCode;
        }
    }

    /** Listener for when an app's ability to afford a bill changes. */
    interface AffordabilityChangeListener {
        void onAffordabilityChanged(int userId, @NonNull String pkgName, @NonNull ActionBill bill,
                boolean canAfford);
    }

    /**
     * Return {@code true} if the app is able to pay for the anticipated actions.
     */
    boolean canPayFor(int userId, @NonNull String pkgName, @NonNull ActionBill bill);

    /**
     * Returns the maximum duration (in milliseconds) that the specified app can afford the bill,
     * based on current prices.
     */
    long getMaxDurationMs(int userId, @NonNull String pkgName, @NonNull ActionBill bill);

    /**
     * Register an {@link AffordabilityChangeListener} to track when an app's ability to afford the
     * indicated bill changes.
     */
    void registerAffordabilityChangeListener(int userId, @NonNull String pkgName,
            @NonNull AffordabilityChangeListener listener, @NonNull ActionBill bill);

    /**
     * Unregister a {@link AffordabilityChangeListener} from being notified of any changes to an
     * app's ability to afford the specified bill.
     */
    void unregisterAffordabilityChangeListener(int userId, @NonNull String pkgName,
            @NonNull AffordabilityChangeListener listener, @NonNull ActionBill bill);

    /**
     * Note that an instantaneous event has occurred. The event must be specified in one of the
     * EconomicPolicies.
     *
     * @param tag An optional tag that can be used to differentiate the same event for the same app.
     */
    void noteInstantaneousEvent(int userId, @NonNull String pkgName, int eventId,
            @Nullable String tag);

    /**
     * Note that a long-running event is starting. The event must be specified in one of the
     * EconomicPolicies. You must always call
     * {@link #noteOngoingEventStopped(int, String, int, String)} to end the event. Ongoing
     * events will be separated and grouped by event-tag combinations. There must be an equal
     * number of start() and stop() calls for the same event-tag combination in order for the
     * tracking to finally stop (ie. ongoing events are ref-counted).
     *
     * @param tag An optional tag that can be used to differentiate the same event for the same app.
     */
    void noteOngoingEventStarted(int userId, @NonNull String pkgName, int eventId,
            @Nullable String tag);

    /**
     * Note that a long-running event has stopped. The event must be specified in one of the
     * EconomicPolicies. Ongoing events are separated and grouped by event-tag combinations.
     * There must be an equal number of start() and stop() calls for the same event-tag combination
     * in order for the tracking to finally stop (ie. ongoing events are ref-counted).
     *
     * @param tag An optional tag that can be used to differentiate the same event for the same app.
     */
    void noteOngoingEventStopped(int userId, @NonNull String pkgName, int eventId,
            @Nullable String tag);
}
