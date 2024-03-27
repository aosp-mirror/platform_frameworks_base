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

package com.android.systemui.screenshot.ui.binder

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.res.R
import com.android.systemui.screenshot.ui.viewmodel.ScreenshotViewModel
import com.android.systemui.util.children
import kotlinx.coroutines.launch

object ScreenshotShelfViewBinder {
    fun bind(
        view: ViewGroup,
        viewModel: ScreenshotViewModel,
        layoutInflater: LayoutInflater,
    ) {
        val previewView: ImageView = view.requireViewById(R.id.screenshot_preview)
        val previewBorder = view.requireViewById<View>(R.id.screenshot_preview_border)
        previewView.clipToOutline = true
        val actionsContainer: LinearLayout = view.requireViewById(R.id.screenshot_actions)
        view.requireViewById<View>(R.id.screenshot_dismiss_button).visibility =
            if (viewModel.showDismissButton) View.VISIBLE else View.GONE

        view.repeatWhenAttached {
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    launch {
                        viewModel.preview.collect { bitmap ->
                            if (bitmap != null) {
                                previewView.setImageBitmap(bitmap)
                                previewView.visibility = View.VISIBLE
                                previewBorder.visibility = View.VISIBLE
                            } else {
                                previewView.visibility = View.GONE
                                previewBorder.visibility = View.GONE
                            }
                        }
                    }
                    launch {
                        viewModel.previewAction.collect { onClick ->
                            previewView.setOnClickListener { onClick?.invoke() }
                        }
                    }
                    launch {
                        viewModel.actions.collect { actions ->
                            if (actions.isNotEmpty()) {
                                view
                                    .requireViewById<View>(R.id.actions_container_background)
                                    .visibility = View.VISIBLE
                            }
                            val viewPool = actionsContainer.children.toList()
                            actionsContainer.removeAllViews()
                            val actionButtons =
                                List(actions.size) {
                                    viewPool.getOrElse(it) {
                                        layoutInflater.inflate(
                                            R.layout.overlay_action_chip,
                                            actionsContainer,
                                            false
                                        )
                                    }
                                }
                            actionButtons.zip(actions).forEach {
                                actionsContainer.addView(it.first)
                                ActionButtonViewBinder.bind(it.first, it.second)
                            }
                        }
                    }
                }
            }
        }
    }
}
