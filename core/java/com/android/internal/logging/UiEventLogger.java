/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.internal.logging;

import android.annotation.NonNull;
import android.annotation.Nullable;

/**
 * Logging interface for UI events. Normal implementation is UiEventLoggerImpl.
 * For testing, use fake implementation UiEventLoggerFake.
 *
 * See go/sysui-event-logs and UiEventReported atom in atoms.proto.
 */
public interface UiEventLogger {
    /** Put your Event IDs in enums that implement this interface, and document them using the
     * UiEventEnum annotation.
     * Event IDs must be globally unique. This will be enforced by tooling (forthcoming).
     * OEMs should use event IDs above 100000 and below 1000000 (1 million).
     */
    interface UiEventEnum {

        /**
         * Tag used to request new UI Event IDs via presubmit analysis.
         *
         * <p>Use RESERVE_NEW_UI_EVENT_ID as the constructor parameter for a new {@link EventEnum}
         * to signal the presubmit analyzer to reserve a new ID for the event. The new ID will be
         * returned as a Gerrit presubmit finding.  Do not submit {@code RESERVE_NEW_UI_EVENT_ID} as
         * the constructor parameter for any event.
         *
         * <pre>
         * &#064;UiEvent(doc = "Briefly describe the interaction when this event will be logged")
         * UNIQUE_EVENT_NAME(RESERVE_NEW_UI_EVENT_ID);
         * </pre>
         */
        int RESERVE_NEW_UI_EVENT_ID = Integer.MIN_VALUE; // Negative IDs are ignored by the logger.

        int getId();
    }

    /**
     * Log a simple event, with no package information. Does nothing if event.getId() <= 0.
     * @param event an enum implementing UiEventEnum interface.
     */
    void log(@NonNull UiEventEnum event);

    /**
     * Log a simple event with an instance id, without package information.
     * Does nothing if event.getId() <= 0.
     * @param event an enum implementing UiEventEnum interface.
     * @param instance An identifier obtained from an InstanceIdSequence. If null, reduces to log().
     */
    void log(@NonNull UiEventEnum event, @Nullable InstanceId instance);

    /**
     * Log an event with package information. Does nothing if event.getId() <= 0.
     * Give both uid and packageName if both are known, but one may be omitted if unknown.
     * @param event an enum implementing UiEventEnum interface.
     * @param uid the uid of the relevant app, if known (0 otherwise).
     * @param packageName the package name of the relevant app, if known (null otherwise).
     */
    void log(@NonNull UiEventEnum event, int uid, @Nullable String packageName);

    /**
     * Log an event with package information and an instance ID.
     * Does nothing if event.getId() <= 0.
     * @param event an enum implementing UiEventEnum interface.
     * @param uid the uid of the relevant app, if known (0 otherwise).
     * @param packageName the package name of the relevant app, if known (null otherwise).
     * @param instance An identifier obtained from an InstanceIdSequence. If null, reduces to log().
     */
    void logWithInstanceId(@NonNull UiEventEnum event, int uid, @Nullable String packageName,
            @Nullable InstanceId instance);

    /**
     * Log an event with ranked-choice information along with package.
     * Does nothing if event.getId() <= 0.
     * @param event an enum implementing UiEventEnum interface.
     * @param uid the uid of the relevant app, if known (0 otherwise).
     * @param packageName the package name of the relevant app, if known (null otherwise).
     * @param position the position picked.
     */
    void logWithPosition(@NonNull UiEventEnum event, int uid, @Nullable String packageName,
            int position);

    /**
     * Log an event with ranked-choice information along with package and instance ID.
     * Does nothing if event.getId() <= 0.
     * @param event an enum implementing UiEventEnum interface.
     * @param uid the uid of the relevant app, if known (0 otherwise).
     * @param packageName the package name of the relevant app, if known (null otherwise).
     * @param instance An identifier obtained from an InstanceIdSequence. If null, reduces to
     *                 logWithPosition().
     * @param position the position picked.
     */
    void logWithInstanceIdAndPosition(@NonNull UiEventEnum event, int uid,
            @Nullable String packageName, @Nullable InstanceId instance, int position);
}
