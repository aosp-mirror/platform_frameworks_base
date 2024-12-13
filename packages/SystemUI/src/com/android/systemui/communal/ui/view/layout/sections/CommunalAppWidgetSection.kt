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

import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.view.View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
import android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.android.systemui.Flags.communalHubUseThreadPoolForWidgets
import com.android.systemui.communal.domain.model.CommunalContentModel
import com.android.systemui.communal.ui.viewmodel.CommunalAppWidgetViewModel
import com.android.systemui.communal.widgets.CommunalAppWidgetHostView
import com.android.systemui.communal.widgets.WidgetInteractionHandler
import com.android.systemui.dagger.qualifiers.UiBackground
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.res.R
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class CommunalAppWidgetSection
@Inject
constructor(
    @UiBackground private val uiBgExecutor: Executor,
    private val interactionHandler: WidgetInteractionHandler,
    private val viewModelFactory: CommunalAppWidgetViewModel.Factory,
) {

    private companion object {
        const val TAG = "CommunalAppWidgetSection"
        val LISTENER_TAG = R.id.communal_widget_listener_tag

        val poolSize by lazy { Runtime.getRuntime().availableProcessors().coerceAtLeast(2) }

        /**
         * This executor is used for widget inflation. Parameters match what launcher uses. See
         * [com.android.launcher3.util.Executors.THREAD_POOL_EXECUTOR].
         */
        val widgetExecutor by lazy {
            ThreadPoolExecutor(
                /*corePoolSize*/ poolSize,
                /*maxPoolSize*/ poolSize,
                /*keepAlive*/ 1,
                /*unit*/ TimeUnit.SECONDS,
                /*workQueue*/ LinkedBlockingQueue(),
            )
        }
    }

    @Composable
    fun Widget(
        isFocusable: Boolean,
        openWidgetEditor: () -> Unit,
        model: CommunalContentModel.WidgetContent.Widget,
        size: SizeF,
        modifier: Modifier = Modifier,
    ) {
        val viewModel = rememberViewModel("$TAG#viewModel") { viewModelFactory.create() }
        val longClickLabel = stringResource(R.string.accessibility_action_label_edit_widgets)
        val accessibilityDelegate =
            remember(longClickLabel, openWidgetEditor) {
                WidgetAccessibilityDelegate(longClickLabel, openWidgetEditor)
            }

        AndroidView(
            factory = { context ->
                CommunalAppWidgetHostView(context, interactionHandler).apply {
                    if (communalHubUseThreadPoolForWidgets()) {
                        setExecutor(widgetExecutor)
                    } else {
                        setExecutor(uiBgExecutor)
                    }
                }
            },
            update = { view ->
                view.accessibilityDelegate = accessibilityDelegate
                view.importantForAccessibility =
                    if (isFocusable) {
                        IMPORTANT_FOR_ACCESSIBILITY_AUTO
                    } else {
                        IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    }
                view.setAppWidget(model.appWidgetId, model.providerInfo)
                // To avoid calling the expensive setListener method on every recomposition if
                // the appWidgetId hasn't changed, we store the current appWidgetId of the view in
                // a tag.
                if ((view.getTag(LISTENER_TAG) as? Int) != model.appWidgetId) {
                    viewModel.setListener(model.appWidgetId, view)
                    view.setTag(LISTENER_TAG, model.appWidgetId)
                }
                viewModel.updateSize(size, view)
            },
            modifier = modifier,
            // For reusing composition in lazy lists.
            onReset = {},
        )
    }

    private class WidgetAccessibilityDelegate(
        private val longClickLabel: String,
        private val longClickAction: () -> Unit,
    ) : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            // Hint user to long press in order to enter edit mode
            info.addAction(
                AccessibilityNodeInfo.AccessibilityAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK.id,
                    longClickLabel.lowercase(),
                )
            )
        }

        override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
            when (action) {
                AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK.id -> {
                    longClickAction()
                    return true
                }
            }
            return super.performAccessibilityAction(host, action, args)
        }
    }
}
