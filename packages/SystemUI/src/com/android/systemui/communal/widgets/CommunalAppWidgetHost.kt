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

package com.android.systemui.communal.widgets

import android.appwidget.AppWidgetHost
import android.content.Context
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.Logger
import javax.annotation.concurrent.GuardedBy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/** Communal app widget host that creates a [CommunalAppWidgetHostView]. */
class CommunalAppWidgetHost(
    context: Context,
    private val backgroundScope: CoroutineScope,
    hostId: Int,
    logBuffer: LogBuffer,
) : AppWidgetHost(context, hostId) {
    private val logger = Logger(logBuffer, TAG)

    private val _appWidgetIdToRemove = MutableSharedFlow<Int>()

    /** App widget ids that have been removed and no longer available. */
    val appWidgetIdToRemove: SharedFlow<Int> = _appWidgetIdToRemove.asSharedFlow()

    @GuardedBy("observers") private val observers = mutableSetOf<Observer>()

    override fun onAppWidgetRemoved(appWidgetId: Int) {
        backgroundScope.launch {
            logger.i({ "App widget removed from system: $int1" }) { int1 = appWidgetId }
            _appWidgetIdToRemove.emit(appWidgetId)
        }
    }

    override fun allocateAppWidgetId(): Int {
        return super.allocateAppWidgetId().also { appWidgetId ->
            backgroundScope.launch {
                synchronized(observers) {
                    observers.forEach { observer -> observer.onAllocateAppWidgetId(appWidgetId) }
                }
            }
        }
    }

    override fun deleteAppWidgetId(appWidgetId: Int) {
        super.deleteAppWidgetId(appWidgetId)
        backgroundScope.launch {
            synchronized(observers) {
                observers.forEach { observer -> observer.onDeleteAppWidgetId(appWidgetId) }
            }
        }
    }

    override fun startListening() {
        super.startListening()
        backgroundScope.launch {
            synchronized(observers) {
                observers.forEach { observer -> observer.onHostStartListening() }
            }
        }
    }

    override fun stopListening() {
        super.stopListening()
        backgroundScope.launch {
            synchronized(observers) {
                observers.forEach { observer -> observer.onHostStopListening() }
            }
        }
    }

    fun addObserver(observer: Observer) {
        synchronized(observers) { observers.add(observer) }
    }

    fun removeObserver(observer: Observer) {
        synchronized(observers) { observers.remove(observer) }
    }

    /**
     * Allows another class to observe the [CommunalAppWidgetHost] and handle any logic there.
     *
     * This is mainly for testability as it is difficult to test a real instance of [AppWidgetHost]
     * which communicates with framework services.
     *
     * Note: all the callbacks are launched from the background scope.
     */
    interface Observer {
        /** Called immediately after the host has started listening for widget updates. */
        fun onHostStartListening() {}

        /** Called immediately after the host has stopped listening for widget updates. */
        fun onHostStopListening() {}

        /** Called immediately after a new app widget id has been allocated. */
        fun onAllocateAppWidgetId(appWidgetId: Int) {}

        /** Called immediately after an app widget id is to be deleted. */
        fun onDeleteAppWidgetId(appWidgetId: Int) {}
    }

    companion object {
        private const val TAG = "CommunalAppWidgetHost"
    }
}
