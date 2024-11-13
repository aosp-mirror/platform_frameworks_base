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

import android.os.Handler
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.android.internal.logging.UiEvent
import com.android.internal.logging.UiEventLogger
import com.android.systemui.Dumpable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.notification.shared.NotificationThrottleHun
import com.android.systemui.statusbar.policy.BaseHeadsUpManager.HeadsUpEntry
import com.android.systemui.util.Compile
import java.io.PrintWriter
import javax.inject.Inject

/*
 * Control when heads up notifications show during an avalanche where notifications arrive in fast
 * succession, by delaying visual listener side effects and removal handling from BaseHeadsUpManager
 */
@SysUISingleton
class AvalancheController
@Inject
constructor(
        dumpManager: DumpManager,
        private val uiEventLogger: UiEventLogger,
        private val headsUpManagerLogger: HeadsUpManagerLogger,
        @Background private val bgHandler: Handler
) : Dumpable {

    private val tag = "AvalancheController"
    private val debug = Compile.IS_DEBUG && Log.isLoggable(tag, Log.DEBUG)
    var baseEntryMapStr : () -> String = { "baseEntryMapStr not initialized" }

    var enableAtRuntime = true
        set(value) {
            if (!value) {
                // Waiting HUNs in AvalancheController are shown in the HUN section in open shade.
                // Clear them so we don't show them again when the shade closes and reordering is
                // allowed again.
                logDroppedHunsInBackground(getWaitingKeys().size)
                clearNext()
                headsUpEntryShowing = null
            }
            if (field != value) {
                field = value
            }
        }

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

    enum class ThrottleEvent(private val id: Int) : UiEventLogger.UiEventEnum {
        @UiEvent(doc = "HUN was shown.") AVALANCHE_THROTTLING_HUN_SHOWN(1821),
        @UiEvent(doc = "HUN was dropped to show higher priority HUNs.")
        AVALANCHE_THROTTLING_HUN_DROPPED(1822),
        @UiEvent(doc = "HUN was removed while waiting to show.")
        AVALANCHE_THROTTLING_HUN_REMOVED(1823);

        override fun getId(): Int {
            return id
        }
    }

    init {
        dumpManager.registerNormalDumpable(tag, /* module */ this)
    }

    fun getShowingHunKey(): String {
        return getKey(headsUpEntryShowing)
    }

    fun isEnabled(): Boolean {
        return NotificationThrottleHun.isEnabled && enableAtRuntime
    }

    /** Run or delay Runnable for given HeadsUpEntry */
    fun update(entry: HeadsUpEntry?, runnable: Runnable?, caller: String) {
        val isEnabled = isEnabled()
        val key = getKey(entry)

        if (runnable == null) {
            headsUpManagerLogger.logAvalancheUpdate(
                caller, isEnabled, key,
                "Runnable NULL, stop. ${getStateStr()}"
            )
            return
        }
        if (!isEnabled) {
            headsUpManagerLogger.logAvalancheUpdate(
                caller, isEnabled, key,
                "NOT ENABLED, run runnable. ${getStateStr()}"
            )
            runnable.run()
            return
        }
        if (entry == null) {
            headsUpManagerLogger.logAvalancheUpdate(
                caller, isEnabled, key,
                "Entry NULL, stop. ${getStateStr()}"
            )
            return
        }
        if (debug) {
            debugRunnableLabelMap[runnable] = caller
        }
        var stateAfter = ""
        if (isShowing(entry)) {
            runnable.run()
            stateAfter = "update showing"

        } else if (entry in nextMap) {
            nextMap[entry]?.add(runnable)
            stateAfter = "update next"

        } else if (headsUpEntryShowing == null) {
            showNow(entry, arrayListOf(runnable))
            stateAfter = "show now"

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
                headsUpEntryShowing!!.updateEntry(
                    /* updatePostTime= */ false,
                    /* updateEarliestRemovalTime= */ false,
                    /* reason= */ "avalanche duration update"
                )
            }
        }
        stateAfter += getStateStr()
        headsUpManagerLogger.logAvalancheUpdate(caller, isEnabled = true, key, stateAfter)
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
    fun delete(entry: HeadsUpEntry?, runnable: Runnable?, caller: String) {
        val isEnabled = isEnabled()
        val key = getKey(entry)

        if (runnable == null) {
            headsUpManagerLogger.logAvalancheDelete(
                caller, isEnabled, key,
                "Runnable NULL, stop. ${getStateStr()}"
            )
            return
        }
        if (!isEnabled) {
            runnable.run()
            headsUpManagerLogger.logAvalancheDelete(
                caller, isEnabled = false, key,
                "NOT ENABLED, run runnable. ${getStateStr()}"
            )
            return
        }
        if (entry == null) {
            runnable.run()
            headsUpManagerLogger.logAvalancheDelete(
                caller, isEnabled = true, key,
                "Entry NULL, run runnable. ${getStateStr()}"
            )
            return
        }
        val stateAfter: String
        if (entry in nextMap) {
            if (entry in nextMap) nextMap.remove(entry)
            if (entry in nextList) nextList.remove(entry)
            uiEventLogger.log(ThrottleEvent.AVALANCHE_THROTTLING_HUN_REMOVED)
            stateAfter = "remove from next. ${getStateStr()}"

        } else if (entry in debugDropSet) {
            debugDropSet.remove(entry)
            stateAfter = "remove from dropset. ${getStateStr()}"

        } else if (isShowing(entry)) {
            previousHunKey = getKey(headsUpEntryShowing)
            // Show the next HUN before removing this one, so that we don't tell listeners
            // onHeadsUpPinnedModeChanged, which causes
            // NotificationPanelViewController.updateTouchableRegion to hide the window while the
            // HUN is animating out, resulting in a flicker.
            showNext()
            runnable.run()
            stateAfter = "remove showing. ${getStateStr()}"

        } else {
            runnable.run()
            stateAfter = "run runnable for untracked shown HUN. ${getStateStr()}"
        }
        headsUpManagerLogger.logAvalancheDelete(caller, isEnabled(), getKey(entry), stateAfter)
    }

    /**
     * Returns duration based on
     * 1) Whether HeadsUpEntry is the last one tracked by AvalancheController
     * 2) The priority of the top HUN in the next batch Used by
     *    BaseHeadsUpManager.HeadsUpEntry.calculateFinishTime to shorten display duration.
     */
    fun getDurationMs(entry: HeadsUpEntry?, autoDismissMs: Int): Int {
        if (!isEnabled()) {
            // Use default duration, like we did before AvalancheController existed
            return autoDismissMs
        }
        if (entry == null) {
            // This should never happen
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
        if (!isEnabled()) {
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
        if (!isEnabled()) {
            return mutableListOf()
        }
        val keyList = mutableListOf<String>()
        for (entry in nextMap.keys) {
            entry.mEntry?.let { keyList.add(entry.mEntry!!.key) }
        }
        return keyList
    }

    fun getWaitingEntry(key: String): HeadsUpEntry? {
        if (!isEnabled()) {
            return null
        }
        for (headsUpEntry in nextMap.keys) {
            if (headsUpEntry.mEntry?.key.equals(key)) {
                return headsUpEntry
            }
        }
        return null
    }

    fun getWaitingEntryList(): List<HeadsUpEntry> {
        if (!isEnabled()) {
            return mutableListOf()
        }
        return nextMap.keys.toList()
    }

    private fun isShowing(entry: HeadsUpEntry): Boolean {
        return headsUpEntryShowing != null && entry.mEntry?.key == headsUpEntryShowing?.mEntry?.key
    }

    private fun showNow(entry: HeadsUpEntry, runnableList: MutableList<Runnable>) {
        log { "SHOW: " + getKey(entry) }

        uiEventLogger.log(ThrottleEvent.AVALANCHE_THROTTLING_HUN_SHOWN)
        headsUpEntryShowing = entry

        runnableList.forEach {
            if (it in debugRunnableLabelMap) {
                log { "RUNNABLE: ${debugRunnableLabelMap[it]}" }
            }
            it.run()
        }
    }

    private fun showNext() {
        log { "SHOW NEXT" }
        headsUpEntryShowing = null

        if (nextList.isEmpty()) {
            log { "NO MORE TO SHOW" }
            previousHunKey = ""
            return
        }

        // Only show first (top priority) entry in next batch
        nextList.sort()
        headsUpEntryShowing = nextList[0]
        headsUpEntryShowingRunnableList = nextMap[headsUpEntryShowing]!!

        // Remove runnable labels for dropped huns
        val listToDrop = nextList.subList(1, nextList.size)
        logDroppedHunsInBackground(listToDrop.size)

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

    private fun logDroppedHunsInBackground(numDropped: Int) {
        bgHandler.post(
            Runnable {
                // Do this in the background to avoid missing frames when closing the shade
                for (n in 1..numDropped) {
                    uiEventLogger.log(ThrottleEvent.AVALANCHE_THROTTLING_HUN_DROPPED)
                }
            }
        )
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
        return "\nAvalancheController:" +
                "\n\tshowing: [${getKey(headsUpEntryShowing)}]" +
                "\n\tprevious: [$previousHunKey]" +
                "\n\tnext list: $nextListStr" +
                "\n\tnext map: $nextMapStr" +
                "\n\tdropped: $dropSetStr" +
                "\nBHUM.mHeadsUpEntryMap: " +
                baseEntryMapStr()
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
