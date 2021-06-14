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

/**
 * Interface for the system server to deal with the resource economy subsystem.
 *
 * @hide
 */
interface EconomyManagerInternal {
    /** Listener for when an app changes its solvency status. */
    interface BalanceChangeListener {
        /**
         * Called when an app runs out of funds.
         * {@link #noteOngoingEventStopped(int, String, int, String)} must still be called to
         * formally end the action.
         */
        void onBankruptcy(int userId, @NonNull String pkgName);

        /**
         * Called when an app goes from being insolvent to solvent.
         */
        void onSolvent(int userId, @NonNull String pkgName);
    }

    /** Register a {@link BalanceChangeListener} to track all apps' solvency status changes. */
    void registerBalanceChangeListener(@NonNull BalanceChangeListener listener);

    /**
     * Unregister a {@link BalanceChangeListener} from being notified of any app's solvency status
     * changes.
     */
    void unregisterBalanceChangeListener(@NonNull BalanceChangeListener listener);

    /**
     * Return {@code true} if the app has a balance equal to or greater than the specified min
     * balance.
     */
    boolean hasBalanceAtLeast(int userId, @NonNull String pkgName, int minBalance);

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
