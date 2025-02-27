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

package com.android.systemui.communal.ui.binder

import android.content.Context
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.ui.unit.IntSize
import androidx.core.view.doOnLayout
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.Flags.communalWidgetResizing
import com.android.systemui.common.ui.view.onLayoutChanged
import com.android.systemui.communal.domain.model.CommunalContentModel
import com.android.systemui.communal.util.WidgetViewFactory
import com.android.systemui.util.kotlin.DisposableHandles
import com.android.systemui.util.kotlin.toDp
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn

object CommunalAppWidgetHostViewBinder {
    private const val TAG = "CommunalAppWidgetHostViewBinder"

    fun bind(
        context: Context,
        applicationScope: CoroutineScope,
        mainContext: CoroutineContext,
        backgroundContext: CoroutineContext,
        container: FrameLayout,
        model: CommunalContentModel.WidgetContent.Widget,
        size: SizeF?,
        factory: WidgetViewFactory,
    ): DisposableHandle {
        val disposables = DisposableHandles()

        val loadingJob =
            applicationScope.launch("$TAG#createWidgetView") {
                val widget = factory.createWidget(context, model, size)
                waitForLayout(container)
                container.post { container.setView(widget) }
                if (communalWidgetResizing()) {
                    // Update the app widget size in the background.
                    launch("$TAG#updateSize", backgroundContext) {
                        container.sizeFlow().flowOn(mainContext).distinctUntilChanged().collect {
                            (width, height) ->
                            widget.updateAppWidgetSize(
                                /* newOptions = */ Bundle(),
                                /* minWidth = */ width,
                                /* minHeight = */ height,
                                /* maxWidth = */ width,
                                /* maxHeight = */ height,
                                /* ignorePadding = */ true,
                            )
                        }
                    }
                }
            }

        disposables += DisposableHandle { loadingJob.cancel() }
        disposables += DisposableHandle { container.removeAllViews() }

        return disposables
    }

    private suspend fun waitForLayout(container: FrameLayout) = suspendCoroutine { cont ->
        container.doOnLayout { cont.resume(Unit) }
    }
}

private fun ViewGroup.setView(view: View) {
    if (view.parent == this) {
        return
    }
    (view.parent as? ViewGroup)?.removeView(view)
    addView(view)
}

private fun View.sizeAsDp(): IntSize = IntSize(width.toDp(context), height.toDp(context))

private fun View.sizeFlow(): Flow<IntSize> = conflatedCallbackFlow {
    if (isLaidOut && !isLayoutRequested) {
        trySend(sizeAsDp())
    }
    val disposable = onLayoutChanged { trySend(sizeAsDp()) }
    awaitClose { disposable.dispose() }
}
