/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.temporarydisplay

import android.view.View
import com.android.systemui.plugins.log.LogBuffer
import com.android.systemui.plugins.log.LogLevel

/** A logger for temporary view changes -- see [TemporaryViewDisplayController]. */
open class TemporaryViewLogger<T : TemporaryViewInfo>(
    internal val buffer: LogBuffer,
    internal val tag: String,
) {
    fun logViewExpiration(info: T) {
        buffer.log(
            tag,
            LogLevel.DEBUG,
            {
                str1 = info.id
                str2 = info.windowTitle
                str3 = info.priority.name
            },
            { "View timeout has already expired; removing. id=$str1 window=$str2 priority=$str3" }
        )
    }

    fun logViewUpdate(info: T) {
        buffer.log(
            tag,
            LogLevel.DEBUG,
            {
                str1 = info.id
                str2 = info.windowTitle
                str3 = info.priority.name
            },
            { "Existing view updated with new data. id=$str1 window=$str2 priority=$str3" }
        )
    }

    fun logViewAdditionDelayed(info: T) {
        buffer.log(
            tag,
            LogLevel.DEBUG,
            {
                str1 = info.id
                str2 = info.windowTitle
                str3 = info.priority.name
            },
            {
                "New view can't be displayed because higher priority view is currently " +
                    "displayed. New view id=$str1 window=$str2 priority=$str3"
            }
        )
    }

    /** Logs that we added the view with the given information. */
    fun logViewAddition(info: T) {
        buffer.log(
            tag,
            LogLevel.DEBUG,
            {
                str1 = info.id
                str2 = info.windowTitle
                str3 = info.priority.name
            },
            { "View added. id=$str1 window=$str2 priority=$str3" }
        )
    }

    /** Logs that there was a failure to animate the view in. */
    fun logAnimateInFailure() {
        buffer.log(
            tag,
            LogLevel.WARNING,
            {},
            { "View's appearance animation failed. Forcing view display manually." },
        )
    }

    /** Logs that there was a failure to animate the view out. */
    fun logAnimateOutFailure() {
        buffer.log(
            tag,
            LogLevel.WARNING,
            {},
            { "View's disappearance animation failed." },
        )
    }

    fun logViewHidden(info: T) {
        buffer.log(
            tag,
            LogLevel.DEBUG,
            {
                str1 = info.id
                str2 = info.windowTitle
                str3 = info.priority.name
            },
            {
                "View hidden in favor of newer view. " +
                    "Hidden view id=$str1 window=$str2 priority=$str3"
            }
        )
    }

    /** Logs that we removed the view with the given [id] for the given [reason]. */
    fun logViewRemoval(id: String, reason: String) {
        buffer.log(
            tag,
            LogLevel.DEBUG,
            {
                str1 = reason
                str2 = id
            },
            { "View with id=$str2 is removed due to: $str1" }
        )
    }

    /** Logs that we ignored removal of the view with the given [id]. */
    fun logViewRemovalIgnored(id: String, reason: String) {
        buffer.log(
            tag,
            LogLevel.DEBUG,
            {
                str1 = reason
                str2 = id
            },
            { "Removal of view with id=$str2 is ignored because $str1" }
        )
    }

    fun logViewAddedToWindowManager(info: T, view: View) {
        buffer.log(
            tag,
            LogLevel.DEBUG,
            {
                str1 = info.id
                str2 = info.windowTitle
                str3 = view.javaClass.name
                int1 = view.getIdForLogging()
            },
            {
                "Adding view to window manager. " +
                    "id=$str1 window=$str2 view=$str3(id=${Integer.toHexString(int1)})"
            }
        )
    }

    fun logViewRemovedFromWindowManager(info: T, view: View, isReinflation: Boolean = false) {
        buffer.log(
            tag,
            LogLevel.DEBUG,
            {
                str1 = info.id
                str2 = info.windowTitle
                str3 = view.javaClass.name
                int1 = view.getIdForLogging()
                bool1 = isReinflation
            },
            {
                "Removing view from window manager${if (bool1) " due to reinflation" else ""}. " +
                    "id=$str1 window=$str2 view=$str3(id=${Integer.toHexString(int1)})"
            }
        )
    }

    companion object {
        private fun View.getIdForLogging(): Int {
            // The identityHashCode is guaranteed to be constant for the lifetime of the object.
            return System.identityHashCode(this)
        }
    }
}
