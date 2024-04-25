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
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.res.R
import com.android.systemui.screenshot.ScreenshotEvent
import com.android.systemui.screenshot.ui.ScreenshotShelfView
import com.android.systemui.screenshot.ui.SwipeGestureListener
import com.android.systemui.screenshot.ui.viewmodel.ScreenshotViewModel
import com.android.systemui.util.children
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object ScreenshotShelfViewBinder {
    fun bind(
        view: ScreenshotShelfView,
        viewModel: ScreenshotViewModel,
        layoutInflater: LayoutInflater,
        onDismissalRequested: (event: ScreenshotEvent, velocity: Float?) -> Unit,
        onDismissalCancelled: () -> Unit,
    ) {
        val swipeGestureListener =
            SwipeGestureListener(
                view,
                onDismiss = {
                    onDismissalRequested(ScreenshotEvent.SCREENSHOT_SWIPE_DISMISSED, it)
                },
                onCancel = onDismissalCancelled
            )
        view.onTouchInterceptListener = { swipeGestureListener.onMotionEvent(it) }

        val previewView: ImageView = view.requireViewById(R.id.screenshot_preview)
        val previewBorder = view.requireViewById<View>(R.id.screenshot_preview_border)
        previewView.clipToOutline = true
        val actionsContainer: LinearLayout = view.requireViewById(R.id.screenshot_actions)
        val dismissButton = view.requireViewById<View>(R.id.screenshot_dismiss_button)
        dismissButton.visibility = if (viewModel.showDismissButton) View.VISIBLE else View.GONE
        dismissButton.setOnClickListener {
            onDismissalRequested(ScreenshotEvent.SCREENSHOT_EXPLICIT_DISMISSAL, null)
        }

        // use immediate dispatcher to ensure screenshot bitmap is set before animation
        view.repeatWhenAttached(Dispatchers.Main.immediate) {
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
                            val visibleActions = actions.filter { it.visible }

                            if (visibleActions.isNotEmpty()) {
                                view
                                    .requireViewById<View>(R.id.actions_container_background)
                                    .visibility = View.VISIBLE
                            }

                            // Remove any buttons not in the new list, then do another pass to add
                            // any new actions and update any that are already there.
                            // This assumes that actions can never change order and that each action
                            // ID is unique.
                            val newIds = visibleActions.map { it.id }

                            for (child in actionsContainer.children.toList()) {
                                if (child.tag !in newIds) {
                                    actionsContainer.removeView(child)
                                }
                            }

                            for ((index, action) in visibleActions.withIndex()) {
                                val currentView: View? = actionsContainer.getChildAt(index)
                                if (action.id == currentView?.tag) {
                                    // Same ID, update the display
                                    ActionButtonViewBinder.bind(currentView, action)
                                } else {
                                    // Different ID. Removals have already happened so this must
                                    // mean that the new action must be inserted here.
                                    val actionButton =
                                        layoutInflater.inflate(
                                            R.layout.shelf_action_chip,
                                            actionsContainer,
                                            false
                                        )
                                    actionsContainer.addView(actionButton, index)
                                    ActionButtonViewBinder.bind(actionButton, action)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
