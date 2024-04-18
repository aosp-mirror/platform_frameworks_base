/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.systemui.statusbar.policy

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.android.systemui.Dumpable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.notification.shared.NotificationThrottleHun
import com.android.systemui.statusbar.policy.BaseHeadsUpManager.HeadsUpEntry
import java.io.PrintWriter
import javax.inject.Inject

/*
 * Control when heads up notifications show during an avalanche where notifications arrive in fast
 * succession, by delaying visual listener side effects and removal handling from BaseHeadsUpManager
 */
@SysUISingleton
class AvalancheController @Inject constructor(
    dumpManager: DumpManager,
) : Dumpable {

    private val tag = "AvalancheController"
    private val debug = false

    // HUN showing right now, in the floating state where full shade is hidden, on launcher or AOD
    @VisibleForTesting var headsUpEntryShowing: HeadsUpEntry? = null

    // Key of HUN previously showing, is being removed or was removed
    var previousHunKey: String = ""

    // List of runnables to run for the HUN showing right now
    private var headsUpEntryShowingRunnableList: MutableList<Runnable> = ArrayList()

    // HeadsUpEntry waiting to show
    // Use sortable list instead of priority queue for debugging
    private val nextList: MutableList<HeadsUpEntry> = ArrayList()

    // Map of HeadsUpEntry waiting to show, and runnables to run when it shows.
    // Use HashMap instead of SortedMap for faster lookup, and also because the ordering
    // provided by HeadsUpEntry.compareTo is not consistent over time or with HeadsUpEntry.equals
    @VisibleForTesting var nextMap: MutableMap<HeadsUpEntry, MutableList<Runnable>> = HashMap()

    // Map of Runnable to label for debugging only
    private val debugRunnableLabelMap: MutableMap<Runnable, String> = HashMap()

    // HeadsUpEntry we did not show at all because they are not the top priority hun in their batch
    // For debugging only
    @VisibleForTesting var debugDropSet: MutableSet<HeadsUpEntry> = HashSet()

    init {
        dumpManager.registerNormalDumpable(tag, /* module */ this)
    }

    fun getShowingHunKey(): String {
        return getKey(headsUpEntryShowing)
    }

    /** Run or delay Runnable for given HeadsUpEntry */
    fun update(entry: HeadsUpEntry?, runnable: Runnable, label: String) {
        if (!NotificationThrottleHun.isEnabled) {
            runnable.run()
            return
        }
        val fn = "[$label] => AvalancheController.update [${getKey(entry)}]"
        if (entry == null) {
            log { "Entry is NULL, stop update." }
            return;
        }
        if (debug) {
            debugRunnableLabelMap[runnable] = label
        }
        if (isShowing(entry)) {
            log { "\n$fn => [update showing]" }
            runnable.run()
        } else if (entry in nextMap) {
            log { "\n$fn => [update next]" }
            nextMap[entry]?.add(runnable)
        } else if (headsUpEntryShowing == null) {
            log { "\n$fn => [showNow]" }
            showNow(entry, arrayListOf(runnable))
        } else {
            // Clean up invalid state when entry is in list but not map and vice versa
            if (entry in nextMap) nextMap.remove(entry)
            if (entry in nextList) nextList.remove(entry)

            addToNext(entry, runnable)

            // Shorten headsUpEntryShowing display time
            val nextIndex = nextList.indexOf(entry)
            val isOnlyNextEntry = nextIndex == 0 && nextList.size == 1
            if (isOnlyNextEntry) {
                // HeadsUpEntry.updateEntry recursively calls AvalancheController#update
                // and goes to the isShowing case above
                headsUpEntryShowing!!.updateEntry(false, "avalanche duration update")
            }
        }
        logState("after $fn")
    }

    @VisibleForTesting
    fun addToNext(entry: HeadsUpEntry, runnable: Runnable) {
        nextMap[entry] = arrayListOf(runnable)
        nextList.add(entry)
    }

    /**
     * Run or ignore Runnable for given HeadsUpEntry. If entry was never shown, ignore and delete
     * all Runnables associated with that entry.
     */
    fun delete(entry: HeadsUpEntry?, runnable: Runnable, label: String) {
        if (!NotificationThrottleHun.isEnabled) {
            runnable.run()
            return
        }
        val fn = "[$label] => AvalancheController.delete " + getKey(entry)
        if (entry == null) {
            log { "$fn => cannot remove NULL entry" }
            return
        }
        if (entry in nextMap) {
            log { "$fn => [remove from next]" }
            if (entry in nextMap) nextMap.remove(entry)
            if (entry in nextList) nextList.remove(entry)
        } else if (entry in debugDropSet) {
            log { "$fn => [remove from dropset]" }
            debugDropSet.remove(entry)
        } else if (isShowing(entry)) {
            log { "$fn => [remove showing ${getKey(entry)}]" }
            previousHunKey = getKey(headsUpEntryShowing)

            runnable.run()
            showNextAfterRemove()
        } else {
            log { "$fn => [removing untracked ${getKey(entry)}]" }
        }
        logState("after $fn")
    }

    /**
     * Returns duration based on
     * 1) Whether HeadsUpEntry is the last one tracked byAvalancheController
     * 2) The priority of the top HUN in the next batch Used by
     *    BaseHeadsUpManager.HeadsUpEntry.calculateFinishTime to shorten display duration.
     */
    fun getDurationMs(entry: HeadsUpEntry, autoDismissMs: Int): Int {
        if (!NotificationThrottleHun.isEnabled) {
            // Use default duration, like we did before AvalancheController existed
            return autoDismissMs
        }
        val showingList: MutableList<HeadsUpEntry> = mutableListOf()
        if (headsUpEntryShowing != null) {
            showingList.add(headsUpEntryShowing!!)
        }
        nextList.sort()
        val entryList = showingList + nextList
        if (entryList.isEmpty()) {
            log { "No avalanche HUNs, use default ms: $autoDismissMs" }
            return autoDismissMs
        }
        // entryList.indexOf(entry) returns -1 even when the entry is in entryList
        var thisEntryIndex = -1
        for ((i, e) in entryList.withIndex()) {
            if (e == entry) {
                thisEntryIndex = i
            }
        }
        if (thisEntryIndex == -1) {
            log { "Untracked entry, use default ms: $autoDismissMs" }
            return autoDismissMs
        }
        val nextEntryIndex = thisEntryIndex + 1

        // If last entry, use default duration
        if (nextEntryIndex >= entryList.size) {
            log { "Last entry, use default ms: $autoDismissMs" }
            return autoDismissMs
        }
        val nextEntry = entryList[nextEntryIndex]
        if (nextEntry.compareNonTimeFields(entry) == -1) {
            // Next entry is higher priority
            log { "Next entry is higher priority: 500ms" }
            return 500
        } else if (nextEntry.compareNonTimeFields(entry) == 0) {
            // Next entry is same priority
            log { "Next entry is same priority: 1000ms" }
            return 1000
        } else {
            log { "Next entry is lower priority, use default ms: $autoDismissMs" }
            return autoDismissMs
        }
    }

    /** Return true if entry is waiting to show. */
    fun isWaiting(key: String): Boolean {
        if (!NotificationThrottleHun.isEnabled) {
            return false
        }
        for (entry in nextMap.keys) {
            if (entry.mEntry?.key.equals(key)) {
                return true
            }
        }
        return false
    }

    /** Return list of keys for huns waiting */
    fun getWaitingKeys(): MutableList<String> {
        if (!NotificationThrottleHun.isEnabled) {
            return mutableListOf()
        }
        val keyList = mutableListOf<String>()
        for (entry in nextMap.keys) {
            entry.mEntry?.let { keyList.add(entry.mEntry!!.key) }
        }
        return keyList
    }

    private fun isShowing(entry: HeadsUpEntry): Boolean {
        return headsUpEntryShowing != null && entry.mEntry?.key == headsUpEntryShowing?.mEntry?.key
    }

    private fun showNow(entry: HeadsUpEntry, runnableList: MutableList<Runnable>) {
        log { "SHOW: " + getKey(entry) }

        headsUpEntryShowing = entry

        runnableList.forEach {
            if (it in debugRunnableLabelMap) {
                log { "RUNNABLE: ${debugRunnableLabelMap[it]}" }
            }
            it.run()
        }
    }

    private fun showNextAfterRemove() {
        log { "SHOW NEXT" }
        headsUpEntryShowing = null

        if (nextList.isEmpty()) {
            log { "NO MORE TO SHOW" }
            return
        }

        // Only show first (top priority) entry in next batch
        nextList.sort()
        headsUpEntryShowing = nextList[0]
        headsUpEntryShowingRunnableList = nextMap[headsUpEntryShowing]!!

        // Remove runnable labels for dropped huns
        val listToDrop = nextList.subList(1, nextList.size)
        if (debug) {
            // Clear runnable labels
            for (e in listToDrop) {
                val runnableList = nextMap[e]!!
                for (r in runnableList) {
                    debugRunnableLabelMap.remove(r)
                }
            }
            debugDropSet.addAll(listToDrop)
        }

        clearNext()
        showNow(headsUpEntryShowing!!, headsUpEntryShowingRunnableList)
    }

    fun clearNext() {
        nextList.clear()
        nextMap.clear()
    }

    // Methods below are for logging only ==========================================================

    private inline fun log(s: () -> String) {
        if (debug) {
            Log.d(tag, s())
        }
    }

    private fun getStateStr(): String {
        return "SHOWING: [${getKey(headsUpEntryShowing)}]" +
                "\nPREVIOUS: [$previousHunKey]" +
                "\nNEXT LIST: $nextListStr" +
                "\nNEXT MAP: $nextMapStr" +
                "\nDROPPED: $dropSetStr"
    }

    private fun logState(reason: String) {
        log { "\n================================================================================="}
        log { "STATE $reason" }
        log { getStateStr() }
        log { "=================================================================================\n"}
    }

    private val dropSetStr: String
        get() {
            val queue = ArrayList<String>()
            for (entry in debugDropSet) {
                queue.add("[${getKey(entry)}]")
            }
            return java.lang.String.join("\n", queue)
        }

    private val nextListStr: String
        get() {
            val queue = ArrayList<String>()
            for (entry in nextList) {
                queue.add("[${getKey(entry)}]")
            }
            return java.lang.String.join("\n", queue)
        }

    private val nextMapStr: String
        get() {
            val queue = ArrayList<String>()
            for (entry in nextMap.keys) {
                queue.add("[${getKey(entry)}]")
            }
            return java.lang.String.join("\n", queue)
        }

    fun getKey(entry: HeadsUpEntry?): String {
        if (entry == null) {
            return "HeadsUpEntry null"
        }
        if (entry.mEntry == null) {
            return "HeadsUpEntry.mEntry null"
        }
        return entry.mEntry!!.key
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("AvalancheController: ${getStateStr()}")
    }
}
