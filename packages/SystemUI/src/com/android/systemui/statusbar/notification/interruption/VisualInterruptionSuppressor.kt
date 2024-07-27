/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.interruption

import com.android.internal.logging.UiEventLogger.UiEventEnum
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionSuppressor.EventLogData

/**
 * A reason why visual interruptions might be suppressed.
 *
 * @see VisualInterruptionCondition
 * @see VisualInterruptionFilter
 */
enum class VisualInterruptionType {
    /* HUN when awake */
    PEEK,

    /* HUN when dozing */
    PULSE,

    /* Bubble */
    BUBBLE
}

/**
 * A reason why visual interruptions might be suppressed.
 *
 * @see VisualInterruptionCondition
 * @see VisualInterruptionFilter
 */
sealed interface VisualInterruptionSuppressor {
    /** Data to be logged in the EventLog when an interruption is suppressed. */
    data class EventLogData(val number: String, val description: String)

    /** The type(s) of interruption that this suppresses. */
    val types: Set<VisualInterruptionType>

    /** A human-readable string to be logged to explain why this suppressed an interruption. */
    val reason: String

    /** An optional UiEvent ID to be recorded when this suppresses an interruption. */
    val uiEventId: UiEventEnum?

    /** Optional data to be logged in the EventLog when this suppresses an interruption. */
    val eventLogData: EventLogData?

    /** Whether the interruption is spammy and should be dropped under normal circumstances. */
    val isSpammy: Boolean
        get() = false

    /**
     * Called after the suppressor is added to the [VisualInterruptionDecisionProvider] but before
     * any other methods are called on the suppressor.
     */
    fun start() {}
}

/** A reason why visual interruptions might be suppressed regardless of the notification. */
abstract class VisualInterruptionCondition(
    override val types: Set<VisualInterruptionType>,
    override val reason: String,
    override val uiEventId: UiEventEnum? = null,
    override val eventLogData: EventLogData? = null
) : VisualInterruptionSuppressor {
    constructor(
        types: Set<VisualInterruptionType>,
        reason: String
    ) : this(types, reason, /* uiEventId= */ null)

    /** @return true if these interruptions should be suppressed right now. */
    abstract fun shouldSuppress(): Boolean
}

/** A reason why visual interruptions might be suppressed based on the notification. */
abstract class VisualInterruptionFilter(
    override val types: Set<VisualInterruptionType>,
    override val reason: String,
    override val uiEventId: UiEventEnum? = null,
    override val eventLogData: EventLogData? = null,
    override val isSpammy: Boolean = false,
) : VisualInterruptionSuppressor {
    constructor(
        types: Set<VisualInterruptionType>,
        reason: String
    ) : this(types, reason, /* uiEventId= */ null)

    /**
     * @param entry the notification to consider suppressing
     * @return true if these interruptions should be suppressed for this notification right now
     */
    abstract fun shouldSuppress(entry: NotificationEntry): Boolean
}
