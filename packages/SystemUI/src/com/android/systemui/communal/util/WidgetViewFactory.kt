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
import com.android.app.tracing.coroutines.withContext
import com.android.systemui.communal.domain.model.CommunalContentModel
import com.android.systemui.communal.widgets.AppWidgetHostListenerDelegate
import com.android.systemui.communal.widgets.CommunalAppWidgetHost
import com.android.systemui.communal.widgets.CommunalAppWidgetHostView
import com.android.systemui.communal.widgets.WidgetInteractionHandler
import com.android.systemui.dagger.qualifiers.UiBackground
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext

/** Factory for creating [CommunalAppWidgetHostView] in a background thread. */
class WidgetViewFactory
@Inject
constructor(
    @UiBackground private val uiBgContext: CoroutineContext,
    @UiBackground private val uiBgExecutor: Executor,
    private val appWidgetHost: CommunalAppWidgetHost,
    private val interactionHandler: WidgetInteractionHandler,
    private val listenerFactory: AppWidgetHostListenerDelegate.Factory,
) {
    suspend fun createWidget(
        context: Context,
        model: CommunalContentModel.WidgetContent.Widget,
        size: SizeF,
    ): CommunalAppWidgetHostView =
        withContext("$TAG#createWidget", uiBgContext) {
            val view =
                CommunalAppWidgetHostView(context, interactionHandler).apply {
                    setExecutor(uiBgExecutor)
                    setAppWidget(model.appWidgetId, model.providerInfo)
                }
            // Instead of setting the view as the listener directly, we wrap the view in a delegate
            // which ensures the callbacks always get called on the main thread.
            appWidgetHost.setListener(model.appWidgetId, listenerFactory.create(view))
            view.updateAppWidgetSize(
                /* newOptions = */ Bundle(),
                /* minWidth = */ size.width.toInt(),
                /* minHeight = */ size.height.toInt(),
                /* maxWidth = */ size.width.toInt(),
                /* maxHeight = */ size.height.toInt(),
                /* ignorePadding = */ true,
            )
            view
        }

    private companion object {
        const val TAG = "WidgetViewFactory"
    }
}
