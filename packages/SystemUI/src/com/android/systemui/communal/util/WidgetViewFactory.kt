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

package com.android.systemui.communal.util

import android.content.Context
import android.os.Bundle
import android.util.SizeF
import com.android.app.tracing.coroutines.withContextTraced as withContext
import com.android.systemui.Flags
import com.android.systemui.communal.domain.model.CommunalContentModel
import com.android.systemui.communal.shared.model.GlanceableHubMultiUserHelper
import com.android.systemui.communal.widgets.AppWidgetHostListenerDelegate
import com.android.systemui.communal.widgets.CommunalAppWidgetHost
import com.android.systemui.communal.widgets.CommunalAppWidgetHostView
import com.android.systemui.communal.widgets.GlanceableHubWidgetManager
import com.android.systemui.communal.widgets.WidgetInteractionHandler
import com.android.systemui.dagger.qualifiers.UiBackground
import dagger.Lazy
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext

/** Factory for creating [CommunalAppWidgetHostView] in a background thread. */
class WidgetViewFactory
@Inject
constructor(
    @UiBackground private val uiBgContext: CoroutineContext,
    @UiBackground private val uiBgExecutor: Executor,
    private val appWidgetHostLazy: Lazy<CommunalAppWidgetHost>,
    private val interactionHandler: WidgetInteractionHandler,
    private val listenerFactory: AppWidgetHostListenerDelegate.Factory,
    private val glanceableHubWidgetManagerLazy: Lazy<GlanceableHubWidgetManager>,
    private val multiUserHelper: GlanceableHubMultiUserHelper,
) {
    suspend fun createWidget(
        context: Context,
        model: CommunalContentModel.WidgetContent.Widget,
        size: SizeF?,
    ): CommunalAppWidgetHostView =
        withContext("$TAG#createWidget", uiBgContext) {
            val view =
                CommunalAppWidgetHostView(context, interactionHandler).apply {
                    if (Flags.communalHubUseThreadPoolForWidgets()) {
                        setExecutor(widgetExecutor)
                    } else {
                        setExecutor(uiBgExecutor)
                    }
                    setAppWidget(model.appWidgetId, model.providerInfo)
                }

            if (
                multiUserHelper.glanceableHubHsumFlagEnabled &&
                    multiUserHelper.isInHeadlessSystemUser()
            ) {
                // If the widget view is created in the headless system user, the widget host lives
                // remotely in the foreground user, and therefore the host listener needs to be
                // registered through the widget manager.
                with(glanceableHubWidgetManagerLazy.get()) {
                    setAppWidgetHostListener(model.appWidgetId, listenerFactory.create(view))
                }
            } else {
                // Instead of setting the view as the listener directly, we wrap the view in a
                // delegate which ensures the callbacks always get called on the main thread.
                with(appWidgetHostLazy.get()) {
                    setListener(model.appWidgetId, listenerFactory.create(view))
                }
            }

            if (size != null) {
                view.updateAppWidgetSize(
                    /* newOptions = */ Bundle(),
                    /* minWidth = */ size.width.toInt(),
                    /* minHeight = */ size.height.toInt(),
                    /* maxWidth = */ size.width.toInt(),
                    /* maxHeight = */ size.height.toInt(),
                    /* ignorePadding = */ true,
                )
            }
            view
        }

    private companion object {
        const val TAG = "WidgetViewFactory"

        val poolSize = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)

        /**
         * This executor is used for widget inflation. Parameters match what launcher uses. See
         * [com.android.launcher3.util.Executors.THREAD_POOL_EXECUTOR].
         */
        val widgetExecutor =
            ThreadPoolExecutor(
                /*corePoolSize*/ poolSize,
                /*maxPoolSize*/ poolSize,
                /*keepAlive*/ 1,
                /*unit*/ TimeUnit.SECONDS,
                /*workQueue*/ LinkedBlockingQueue(),
            )
    }
}
