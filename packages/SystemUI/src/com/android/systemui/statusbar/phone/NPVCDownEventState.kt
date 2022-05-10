/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.android.systemui.statusbar.phone

import android.view.MotionEvent
import com.android.internal.util.RingBuffer
import com.android.systemui.dump.DumpsysTableLogger
import com.android.systemui.dump.Row
import java.text.SimpleDateFormat
import java.util.Locale

/** Container for storing information about [MotionEvent.ACTION_DOWN] on
 * [NotificationPanelViewController].
 *
 * This will be used in a dump to log the latest recorded down events.
 *
 * @see NotificationPanelViewController.initDownStates
 */
class NPVCDownEventState private constructor(
    private var timeStamp: Long = 0,
    private var x: Float = 0f,
    private var y: Float = 0f,
    private var qsTouchAboveFalsingThreshold: Boolean = false,
    private var dozing: Boolean = false,
    private var collapsed: Boolean = false,
    private var canCollapseOnQQS: Boolean = false,
    private var listenForHeadsUp: Boolean = false,
    private var allowExpandForSmallExpansion: Boolean = false,
    private var touchSlopExceededBeforeDown: Boolean = false,
    private var lastEventSynthesized: Boolean = false
) {

    /**
     * List of [String] to be used as a [Row] with [DumpsysTableLogger].
     */
    val asStringList: List<String> by lazy {
        listOf(
            DATE_FORMAT.format(timeStamp),
            x.toString(),
            y.toString(),
            qsTouchAboveFalsingThreshold.toString(),
            dozing.toString(),
            collapsed.toString(),
            canCollapseOnQQS.toString(),
            listenForHeadsUp.toString(),
            allowExpandForSmallExpansion.toString(),
            touchSlopExceededBeforeDown.toString(),
            lastEventSynthesized.toString()
        )
    }

    /**
     * [RingBuffer] to store [NPVCDownEventState]. After the buffer is full, it will recycle old
     * events.
     *
     * Do not use [append] to add new elements. Instead use [insert], as it will recycle if
     * necessary.
     */
    class Buffer(
        capacity: Int
    ) : RingBuffer<NPVCDownEventState>(NPVCDownEventState::class.java, capacity) {
        override fun append(t: NPVCDownEventState?) {
            throw UnsupportedOperationException("Not supported, use insert instead")
        }

        override fun createNewItem(): NPVCDownEventState {
            return NPVCDownEventState()
        }

        /**
         * Insert a new element in the buffer.
         */
        fun insert(
            timeStamp: Long,
            x: Float,
            y: Float,
            qsTouchAboveFalsingThreshold: Boolean,
            dozing: Boolean,
            collapsed: Boolean,
            canCollapseOnQQS: Boolean,
            listenForHeadsUp: Boolean,
            allowExpandForSmallExpansion: Boolean,
            touchSlopExceededBeforeDown: Boolean,
            lastEventSynthesized: Boolean
        ) {
            nextSlot.apply {
                this.timeStamp = timeStamp
                this.x = x
                this.y = y
                this.qsTouchAboveFalsingThreshold = qsTouchAboveFalsingThreshold
                this.dozing = dozing
                this.collapsed = collapsed
                this.canCollapseOnQQS = canCollapseOnQQS
                this.listenForHeadsUp = listenForHeadsUp
                this.allowExpandForSmallExpansion = allowExpandForSmallExpansion
                this.touchSlopExceededBeforeDown = touchSlopExceededBeforeDown
                this.lastEventSynthesized = lastEventSynthesized
            }
        }

        /**
         * Returns the content of the buffer (sorted from latest to newest).
         *
         * @see NPVCDownEventState.asStringList
         */
        fun toList(): List<Row> {
            return toArray().map { it.asStringList }
        }
    }

    companion object {
        /**
         * Headers for dumping a table using [DumpsysTableLogger].
         */
        @JvmField
        val TABLE_HEADERS = listOf(
            "Timestamp",
            "X",
            "Y",
            "QSTouchAboveFalsingThreshold",
            "Dozing",
            "Collapsed",
            "CanCollapseOnQQS",
            "ListenForHeadsUp",
            "AllowExpandForSmallExpansion",
            "TouchSlopExceededBeforeDown",
            "LastEventSynthesized"
        )
    }
}

private val DATE_FORMAT = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
