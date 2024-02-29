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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification

import android.icu.text.SimpleDateFormat
import android.util.IndentingPrintWriter
import com.android.systemui.Dumpable
import com.android.systemui.Flags.notificationColorUpdateLogger
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dump.DumpManager
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.util.Compile
import com.android.systemui.util.asIndenting
import com.android.systemui.util.printCollection
import com.android.systemui.util.withIncreasedIndent
import com.google.errorprone.annotations.CompileTimeConstant
import java.io.PrintWriter
import java.util.Locale
import java.util.SortedSet
import java.util.TreeSet
import javax.inject.Inject

@SysUISingleton
class ColorUpdateLogger
@Inject
constructor(
    val featureFlags: FeatureFlagsClassic,
    dumpManager: DumpManager,
) : Dumpable {

    inline val isEnabled
        get() = Compile.IS_DEBUG && notificationColorUpdateLogger()
    private val frames: MutableList<Frame> = mutableListOf()

    init {
        dumpManager.registerDumpable(this)
        if (isEnabled) {
            instance = this
        }
    }

    @JvmOverloads
    fun logTriggerEvent(@CompileTimeConstant type: String, extra: String? = null) {
        if (!isEnabled) return
        val event = Event(type = type, extraValue = extra)
        val didAppend = frames.lastOrNull()?.tryAddTrigger(event) == true
        if (!didAppend) {
            frames.add(Frame(event))
            if (frames.size > maxFrames) frames.removeAt(0)
        }
    }

    @JvmOverloads
    fun logEvent(@CompileTimeConstant type: String, extra: String? = null) {
        if (!isEnabled) return
        val frame = frames.lastOrNull() ?: return
        frame.events.add(Event(type = type, extraValue = extra))
        frame.trim()
    }

    @JvmOverloads
    fun logNotificationEvent(
        @CompileTimeConstant type: String,
        key: String,
        extra: String? = null
    ) {
        if (!isEnabled) return
        val frame = frames.lastOrNull() ?: return
        frame.events.add(Event(type = type, extraValue = extra, notificationKey = key))
        frame.trim()
    }

    override fun dump(pwOrig: PrintWriter, args: Array<out String>) {
        val pw = pwOrig.asIndenting()
        pw.println("enabled: $isEnabled")
        pw.printCollection("frames", frames) { it.dump(pw) }
    }

    private class Frame(event: Event) {
        val startTime: Long = event.time
        val events: MutableList<Event> = mutableListOf(event)
        val triggers: SortedSet<String> = TreeSet<String>().apply { add(event.type) }
        var trimmedEvents: Int = 0

        fun tryAddTrigger(newEvent: Event): Boolean {
            if (newEvent.time < startTime) return false
            if (newEvent.time - startTime > triggerStartsNewFrameAge) return false
            if (newEvent.type in triggers) return false
            triggers.add(newEvent.type)
            events.add(newEvent)
            trim()
            return true
        }

        fun trim() {
            if (events.size > maxEventsPerFrame) {
                events.removeAt(0)
                trimmedEvents++
            }
        }

        fun dump(pw: IndentingPrintWriter) {
            pw.println("Frame")
            pw.withIncreasedIndent {
                pw.println("startTime: ${timeString(startTime)}")
                pw.printCollection("triggers", triggers)
                pw.println("trimmedEvents: $trimmedEvents")
                pw.printCollection("events", events) { it.dump(pw) }
            }
        }
    }

    private class Event(
        @CompileTimeConstant val type: String,
        val extraValue: String? = null,
        val notificationKey: String? = null,
    ) {
        val time: Long = System.currentTimeMillis()

        fun dump(pw: IndentingPrintWriter) {
            pw.append(timeString(time)).append(": ").append(type)
            extraValue?.let { pw.append(" ").append(it) }
            notificationKey?.let { pw.append(" ---- ").append(logKey(it)) }
            pw.println()
        }
    }

    private companion object {
        @JvmStatic
        var instance: ColorUpdateLogger? = null
            private set
        private const val maxFrames = 5
        private const val maxEventsPerFrame = 250
        private const val triggerStartsNewFrameAge = 5000

        private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
        private fun timeString(time: Long): String = dateFormat.format(time)
    }
}
