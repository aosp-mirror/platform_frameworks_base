/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.privacy

import com.android.internal.annotations.VisibleForTesting
import com.android.systemui.Dumpable
import com.android.systemui.appops.AppOpsController
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.privacy.logging.PrivacyLogger
import com.android.systemui.util.asIndenting
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.time.SystemClock
import com.android.systemui.util.withIncreasedIndent
import java.io.PrintWriter
import java.lang.ref.WeakReference
import java.util.concurrent.Executor
import javax.inject.Inject

@SysUISingleton
class PrivacyItemController @Inject constructor(
    @Main uiExecutor: DelayableExecutor,
    @Background private val bgExecutor: DelayableExecutor,
    private val privacyConfig: PrivacyConfig,
    private val privacyItemMonitors: Set<@JvmSuppressWildcards PrivacyItemMonitor>,
    private val logger: PrivacyLogger,
    private val systemClock: SystemClock,
    dumpManager: DumpManager
) : Dumpable {

    @VisibleForTesting
    internal companion object {
        const val TAG = "PrivacyItemController"
        @VisibleForTesting const val TIME_TO_HOLD_INDICATORS = 5000L
    }

    @VisibleForTesting
    internal var privacyList = emptyList<PrivacyItem>()
        @Synchronized get() = field.toList() // Returns a shallow copy of the list
        @Synchronized set

    private var listening = false
    private val callbacks = mutableListOf<WeakReference<Callback>>()
    private val internalUiExecutor = MyExecutor(uiExecutor)
    private var holdingRunnableCanceler: Runnable? = null

    val micCameraAvailable
        get() = privacyConfig.micCameraAvailable
    val locationAvailable
        get() = privacyConfig.locationAvailable
    val allIndicatorsAvailable
        get() = micCameraAvailable && locationAvailable

    private val notifyChanges = Runnable {
        val list = privacyList
        callbacks.forEach { it.get()?.onPrivacyItemsChanged(list) }
    }

    private val updateListAndNotifyChanges = Runnable {
        updatePrivacyList()
        uiExecutor.execute(notifyChanges)
    }

    private val optionsCallback = object : PrivacyConfig.Callback {
        override fun onFlagLocationChanged(flag: Boolean) {
            callbacks.forEach { it.get()?.onFlagLocationChanged(flag) }
        }

        override fun onFlagMicCameraChanged(flag: Boolean) {
            callbacks.forEach { it.get()?.onFlagMicCameraChanged(flag) }
        }
    }

    private val privacyItemMonitorCallback = object : PrivacyItemMonitor.Callback {
        override fun onPrivacyItemsChanged() {
            update()
        }
    }

    init {
        dumpManager.registerDumpable(TAG, this)
        privacyConfig.addCallback(optionsCallback)
    }

    private fun update() {
        bgExecutor.execute {
            updateListAndNotifyChanges.run()
        }
    }

    /**
     * Updates listening status based on whether there are callbacks and the indicators are enabled.
     *
     * Always listen to all OPS so we don't have to figure out what we should be listening to. We
     * still have to filter anyway. Updates are filtered in the callback.
     *
     * This is only called from private (add/remove)Callback and from the config listener, all in
     * main thread.
     */
    private fun setListeningState() {
        val listen = callbacks.isNotEmpty()
        if (listening == listen) return
        listening = listen
        if (listening) {
            privacyItemMonitors.forEach { it.startListening(privacyItemMonitorCallback) }
            update()
        } else {
            privacyItemMonitors.forEach { it.stopListening() }
            // Make sure that we remove all indicators and notify listeners if we are not
            // listening anymore due to indicators being disabled
            update()
        }
    }

    private fun addCallback(callback: WeakReference<Callback>) {
        callbacks.add(callback)
        if (callbacks.isNotEmpty() && !listening) {
            internalUiExecutor.updateListeningState()
        }
        // Notify this callback if we didn't set to listening
        else if (listening) {
            internalUiExecutor.execute(NotifyChangesToCallback(callback.get(), privacyList))
        }
    }

    private fun removeCallback(callback: WeakReference<Callback>) {
        // Removes also if the callback is null
        callbacks.removeIf { it.get()?.equals(callback.get()) ?: true }
        if (callbacks.isEmpty()) {
            internalUiExecutor.updateListeningState()
        }
    }

    fun addCallback(callback: Callback) {
        addCallback(WeakReference(callback))
    }

    fun removeCallback(callback: Callback) {
        removeCallback(WeakReference(callback))
    }

    private fun updatePrivacyList() {
        holdingRunnableCanceler?.run()?.also {
            holdingRunnableCanceler = null
        }
        if (!listening) {
            privacyList = emptyList()
            return
        }
        val list = privacyItemMonitors.flatMap { it.getActivePrivacyItems() }.distinct()
        privacyList = processNewList(list)
    }

    /**
     * Figure out which items have not been around for long enough and put them back in the list.
     *
     * Also schedule when we should check again to remove expired items. Because we always retrieve
     * the current list, we have the latest info.
     *
     * @param list map of list retrieved from [AppOpsController].
     * @return a list that may have added items that should be kept for some time.
     */
    private fun processNewList(list: List<PrivacyItem>): List<PrivacyItem> {
        logger.logRetrievedPrivacyItemsList(list)

        // Anything earlier than this timestamp can be removed
        val removeBeforeTime = systemClock.elapsedRealtime() - TIME_TO_HOLD_INDICATORS
        val mustKeep = privacyList.filter {
            it.timeStampElapsed > removeBeforeTime && !(it isIn list)
        }

        // There are items we must keep because they haven't been around for enough time.
        if (mustKeep.isNotEmpty()) {
            logger.logPrivacyItemsToHold(mustKeep)
            val earliestTime = mustKeep.minByOrNull { it.timeStampElapsed }!!.timeStampElapsed

            // Update the list again when the earliest item should be removed.
            val delay = earliestTime - removeBeforeTime
            logger.logPrivacyItemsUpdateScheduled(delay)
            holdingRunnableCanceler = bgExecutor.executeDelayed(updateListAndNotifyChanges, delay)
        }
        return list.filter { !it.paused } + mustKeep
    }

    /**
     * Ignores the paused status to determine if the element is in the list
     */
    private infix fun PrivacyItem.isIn(list: List<PrivacyItem>): Boolean {
        return list.any {
            it.privacyType == privacyType &&
                    it.application == application &&
                    it.timeStampElapsed == timeStampElapsed
        }
    }

    interface Callback : PrivacyConfig.Callback {
        fun onPrivacyItemsChanged(privacyItems: List<PrivacyItem>)

        @JvmDefault
        fun onFlagAllChanged(flag: Boolean) {}
    }

    private class NotifyChangesToCallback(
        private val callback: Callback?,
        private val list: List<PrivacyItem>
    ) : Runnable {
        override fun run() {
            callback?.onPrivacyItemsChanged(list)
        }
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        val ipw = pw.asIndenting()
        ipw.println("PrivacyItemController state:")
        ipw.withIncreasedIndent {
            ipw.println("Listening: $listening")
            ipw.println("Privacy Items:")
            ipw.withIncreasedIndent {
                privacyList.forEach {
                    ipw.println(it.toString())
                }
            }

            ipw.println("Callbacks:")
            ipw.withIncreasedIndent {
                callbacks.forEach {
                    it.get()?.let {
                        ipw.println(it.toString())
                    }
                }
            }

            ipw.println("PrivacyItemMonitors:")
            ipw.withIncreasedIndent {
                privacyItemMonitors.forEach {
                    it.dump(ipw, args)
                }
            }
        }
        ipw.flush()
    }

    private inner class MyExecutor(
        private val delegate: DelayableExecutor
    ) : Executor {

        private var listeningCanceller: Runnable? = null

        override fun execute(command: Runnable) {
            delegate.execute(command)
        }

        fun updateListeningState() {
            listeningCanceller?.run()
            listeningCanceller = delegate.executeDelayed({ setListeningState() }, 0L)
        }
    }
}