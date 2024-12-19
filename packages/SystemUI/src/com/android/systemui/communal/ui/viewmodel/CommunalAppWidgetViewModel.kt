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

package com.android.systemui.communal.ui.viewmodel

import android.appwidget.AppWidgetHost.AppWidgetHostListener
import android.appwidget.AppWidgetHostView
import android.os.Bundle
import android.util.SizeF
import com.android.app.tracing.coroutines.coroutineScopeTraced
import com.android.app.tracing.coroutines.withContextTraced
import com.android.systemui.communal.shared.model.GlanceableHubMultiUserHelper
import com.android.systemui.communal.widgets.AppWidgetHostListenerDelegate
import com.android.systemui.communal.widgets.CommunalAppWidgetHost
import com.android.systemui.communal.widgets.GlanceableHubWidgetManager
import com.android.systemui.dagger.qualifiers.UiBackground
import com.android.systemui.lifecycle.ExclusiveActivatable
import dagger.Lazy
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/** View model for showing a widget. */
class CommunalAppWidgetViewModel
@AssistedInject
constructor(
    @UiBackground private val backgroundContext: CoroutineContext,
    private val appWidgetHostLazy: Lazy<CommunalAppWidgetHost>,
    private val listenerDelegateFactory: AppWidgetHostListenerDelegate.Factory,
    private val glanceableHubWidgetManagerLazy: Lazy<GlanceableHubWidgetManager>,
    private val multiUserHelper: GlanceableHubMultiUserHelper,
) : ExclusiveActivatable() {

    private companion object {
        const val TAG = "CommunalAppWidgetViewModel"
        const val CHANNEL_CAPACITY = 10
    }

    @AssistedFactory
    interface Factory {
        fun create(): CommunalAppWidgetViewModel
    }

    private val requests =
        Channel<Request>(capacity = CHANNEL_CAPACITY, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    fun setListener(appWidgetId: Int, listener: AppWidgetHostListener) {
        requests.trySend(SetListener(appWidgetId, listener))
    }

    fun updateSize(size: SizeF, view: AppWidgetHostView) {
        requests.trySend(UpdateSize(size, view))
    }

    override suspend fun onActivated(): Nothing {
        coroutineScopeTraced("$TAG#onActivated") {
            requests.receiveAsFlow().collect { request ->
                when (request) {
                    is SetListener -> handleSetListener(request.appWidgetId, request.listener)
                    is UpdateSize -> handleUpdateSize(request.size, request.view)
                }
            }
        }

        awaitCancellation()
    }

    private suspend fun handleSetListener(appWidgetId: Int, listener: AppWidgetHostListener) =
        withContextTraced("${TAG}_$appWidgetId#setListenerInner", backgroundContext) {
            if (
                multiUserHelper.glanceableHubHsumFlagEnabled &&
                    multiUserHelper.isInHeadlessSystemUser()
            ) {
                // If the widget view is created in the headless system user, the widget host lives
                // remotely in the foreground user, and therefore the host listener needs to be
                // registered through the widget manager.
                with(glanceableHubWidgetManagerLazy.get()) {
                    setAppWidgetHostListener(
                        appWidgetId,
                        listenerDelegateFactory.create("${TAG}_$appWidgetId", listener),
                    )
                }
            } else {
                // Instead of setting the view as the listener directly, we wrap the view in a
                // delegate which ensures the callbacks always get called on the main thread.
                with(appWidgetHostLazy.get()) {
                    setListener(
                        appWidgetId,
                        listenerDelegateFactory.create("${TAG}_$appWidgetId", listener),
                    )
                }
            }
        }

    private suspend fun handleUpdateSize(size: SizeF, view: AppWidgetHostView) =
        withContextTraced("$TAG#updateSizeInner", backgroundContext) {
            view.updateAppWidgetSize(
                /* newOptions = */ Bundle(),
                /* minWidth = */ size.width.toInt(),
                /* minHeight = */ size.height.toInt(),
                /* maxWidth = */ size.width.toInt(),
                /* maxHeight = */ size.height.toInt(),
                /* ignorePadding = */ true,
            )
        }
}

private sealed interface Request

/**
 * [Request] to call [CommunalAppWidgetHost.setListener] to tie this view to a particular widget.
 * Since this is involves an IPC to system_server, the call is asynchronous and happens in the
 * background.
 */
private data class SetListener(val appWidgetId: Int, val listener: AppWidgetHostListener) : Request

/**
 * [Request] to call [AppWidgetHostView.updateAppWidgetSize] to notify the widget provider of the
 * new size. Since this is involves an IPC to system_server, the call is asynchronous and happens in
 * the background.
 */
private data class UpdateSize(val size: SizeF, val view: AppWidgetHostView) : Request
