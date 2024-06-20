/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.systemui.statusbar.phone

import android.annotation.StyleRes
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager.LayoutParams.MATCH_PARENT
import android.view.WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION
import android.view.WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS
import android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL
import androidx.activity.ComponentDialog
import androidx.annotation.VisibleForTesting
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.onConfigChanged
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

/** A dialog shown as a bottom sheet. */
class SystemUIBottomSheetDialog
@VisibleForTesting
constructor(
    context: Context,
    private val coroutineScope: CoroutineScope,
    private val configurationController: ConfigurationController,
    private val delegate: DialogDelegate<in Dialog>,
    private val windowLayout: WindowLayout,
    theme: Int,
) : ComponentDialog(context, theme) {

    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        delegate.beforeCreate(this, savedInstanceState)
        super.onCreate(savedInstanceState)
        setupWindow()
        setCanceledOnTouchOutside(true)
        delegate.onCreate(this, savedInstanceState)
    }

    private fun setupWindow() {
        window?.apply {
            setType(TYPE_STATUS_BAR_SUB_PANEL)
            addPrivateFlags(SYSTEM_FLAG_SHOW_FOR_ALL_USERS or PRIVATE_FLAG_NO_MOVE_ANIMATION)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            decorView.setPadding(0, 0, 0, 0)
            attributes =
                attributes.apply {
                    fitInsetsSides = 0
                    horizontalMargin = 0f
                }
        }
    }

    override fun onStart() {
        super.onStart()
        job?.cancel()
        job =
            coroutineScope.launch {
                windowLayout
                    .calculate()
                    .onEach { window?.apply { setLayout(it.width, it.height) } }
                    .launchIn(this)
                configurationController.onConfigChanged
                    .onEach { delegate.onConfigurationChanged(this@SystemUIBottomSheetDialog, it) }
                    .launchIn(this)
            }
        delegate.onStart(this)
    }

    override fun onStop() {
        job?.cancel()
        delegate.onStop(this)
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        delegate.onWindowFocusChanged(this, hasFocus)
    }

    class Factory
    @Inject
    constructor(
        @Application private val context: Context,
        @Application private val coroutineScope: CoroutineScope,
        private val defaultWindowLayout: Lazy<WindowLayout.LimitedEdgeToEdge>,
        private val configurationController: ConfigurationController,
    ) {

        fun create(
            delegate: DialogDelegate<in Dialog>,
            windowLayout: WindowLayout = defaultWindowLayout.get(),
            @StyleRes theme: Int = R.style.Theme_SystemUI_Dialog,
        ): SystemUIBottomSheetDialog =
            SystemUIBottomSheetDialog(
                context = context,
                configurationController = configurationController,
                coroutineScope = coroutineScope,
                delegate = delegate,
                windowLayout = windowLayout,
                theme = theme,
            )
    }

    /** [SystemUIBottomSheetDialog] uses this to determine the [android.view.Window] layout. */
    interface WindowLayout {

        /** Returns a [Layout] to apply to [android.view.Window.setLayout]. */
        fun calculate(): Flow<Layout>

        /** Edge to edge with which doesn't fill the whole space on the large screen. */
        class LimitedEdgeToEdge
        @Inject
        constructor(
            @Application private val context: Context,
            private val configurationController: ConfigurationController,
        ) : WindowLayout {

            override fun calculate(): Flow<Layout> {
                return configurationController.onConfigChanged
                    .onStart { emit(context.resources.configuration) }
                    .map {
                        val edgeToEdgeHorizontally =
                            context.resources.getBoolean(R.bool.config_edgeToEdgeBottomSheetDialog)
                        val width = if (edgeToEdgeHorizontally) MATCH_PARENT else WRAP_CONTENT

                        Layout(width, WRAP_CONTENT)
                    }
            }
        }

        data class Layout(val width: Int, val height: Int)
    }
}
