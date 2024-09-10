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
import android.util.SizeF
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.doOnLayout
import com.android.app.tracing.coroutines.launch
import com.android.systemui.communal.domain.model.CommunalContentModel
import com.android.systemui.communal.util.WidgetViewFactory
import com.android.systemui.util.kotlin.DisposableHandles
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle

object CommunalAppWidgetHostViewBinder {
    private const val TAG = "CommunalAppWidgetHostViewBinder"

    fun bind(
        context: Context,
        applicationScope: CoroutineScope,
        container: FrameLayout,
        model: CommunalContentModel.WidgetContent.Widget,
        size: SizeF,
        factory: WidgetViewFactory,
    ): DisposableHandle {
        val disposables = DisposableHandles()

        val loadingJob =
            applicationScope.launch("$TAG#createWidgetView") {
                val widget = factory.createWidget(context, model, size)
                waitForLayout(container)
                container.post { container.setView(widget) }
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
