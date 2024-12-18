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

package com.android.systemui.communal.ui.view.layout.sections

import android.util.SizeF
import android.view.View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
import android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.systemui.communal.domain.model.CommunalContentModel
import com.android.systemui.communal.ui.binder.CommunalAppWidgetHostViewBinder
import com.android.systemui.communal.ui.viewmodel.BaseCommunalViewModel
import com.android.systemui.communal.util.WidgetViewFactory
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.res.R
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle

class CommunalAppWidgetSection
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val factory: WidgetViewFactory,
) {

    private companion object {
        val DISPOSABLE_TAG = R.id.communal_widget_disposable_tag
    }

    @Composable
    fun Widget(
        viewModel: BaseCommunalViewModel,
        model: CommunalContentModel.WidgetContent.Widget,
        size: SizeF,
        modifier: Modifier = Modifier,
    ) {
        val isFocusable by viewModel.isFocusable.collectAsStateWithLifecycle(initialValue = false)

        AndroidView(
            factory = { context ->
                FrameLayout(context).apply {
                    layoutParams =
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                        )

                    // Need to attach the disposable handle to the view here instead of storing
                    // the state in the composable in order to properly support lazy lists. In a
                    // lazy list, when the composable is no longer in view - it will exit
                    // composition and any state inside the composable will be lost. However,
                    // the View instance will be re-used. Therefore we can store data on the view
                    // in order to preserve it.
                    setTag(
                        DISPOSABLE_TAG,
                        CommunalAppWidgetHostViewBinder.bind(
                            context = context,
                            container = this,
                            model = model,
                            size = size,
                            factory = factory,
                            applicationScope = applicationScope,
                        )
                    )

                    accessibilityDelegate = viewModel.widgetAccessibilityDelegate
                }
            },
            update = { container ->
                container.importantForAccessibility =
                    if (isFocusable) {
                        IMPORTANT_FOR_ACCESSIBILITY_AUTO
                    } else {
                        IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    }
            },
            onRelease = { view ->
                val disposable = (view.getTag(DISPOSABLE_TAG) as? DisposableHandle)
                disposable?.dispose()
            },
            modifier = modifier,
            // For reusing composition in lazy lists.
            onReset = {},
        )
    }
}
