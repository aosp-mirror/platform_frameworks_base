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

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.util.LayoutDirection
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.res.R
import com.android.systemui.screenshot.ScreenshotEvent
import com.android.systemui.screenshot.ui.ScreenshotAnimationController
import com.android.systemui.screenshot.ui.ScreenshotShelfView
import com.android.systemui.screenshot.ui.SwipeGestureListener
import com.android.systemui.screenshot.ui.viewmodel.ActionButtonViewModel
import com.android.systemui.screenshot.ui.viewmodel.AnimationState
import com.android.systemui.screenshot.ui.viewmodel.ScreenshotViewModel
import com.android.systemui.util.children
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import com.android.app.tracing.coroutines.launchTraced as launch

class ScreenshotShelfViewBinder
@Inject
constructor(private val buttonViewBinder: ActionButtonViewBinder) {
    fun bind(
        view: ScreenshotShelfView,
        viewModel: ScreenshotViewModel,
        animationController: ScreenshotAnimationController,
        layoutInflater: LayoutInflater,
        onDismissalRequested: (event: ScreenshotEvent, velocity: Float?) -> Unit,
        onUserInteraction: () -> Unit
    ) {
        val swipeGestureListener =
            SwipeGestureListener(
                view,
                onDismiss = {
                    onDismissalRequested(ScreenshotEvent.SCREENSHOT_SWIPE_DISMISSED, it)
                },
                onCancel = { animationController.getSwipeReturnAnimation().start() }
            )
        view.onTouchInterceptListener = { swipeGestureListener.onMotionEvent(it) }
        view.userInteractionCallback = onUserInteraction

        val previewView: ImageView = view.requireViewById(R.id.screenshot_preview)
        val previewViewBlur: ImageView = view.requireViewById(R.id.screenshot_preview_blur)
        val previewBorder = view.requireViewById<View>(R.id.screenshot_preview_border)
        previewView.clipToOutline = true
        previewViewBlur.clipToOutline = true
        val actionsContainer: LinearLayout = view.requireViewById(R.id.screenshot_actions)
        val dismissButton = view.requireViewById<View>(R.id.screenshot_dismiss_button)
        dismissButton.visibility = if (viewModel.showDismissButton) View.VISIBLE else View.GONE
        dismissButton.setOnClickListener {
            onDismissalRequested(ScreenshotEvent.SCREENSHOT_EXPLICIT_DISMISSAL, null)
        }
        val scrollingScrim: ImageView = view.requireViewById(R.id.screenshot_scrolling_scrim)
        val scrollablePreview: ImageView = view.requireViewById(R.id.screenshot_scrollable_preview)
        val badgeView = view.requireViewById<ImageView>(R.id.screenshot_badge)

        // use immediate dispatcher to ensure screenshot bitmap is set before animation
        view.repeatWhenAttached(Dispatchers.Main.immediate) {
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    launch {
                        viewModel.preview.collect { bitmap ->
                            if (bitmap != null) {
                                setScreenshotBitmap(previewView, bitmap)
                                setScreenshotBitmap(previewViewBlur, bitmap)
                                previewView.visibility = View.VISIBLE
                                previewBorder.visibility = View.VISIBLE
                            } else {
                                previewView.visibility = View.GONE
                                previewBorder.visibility = View.GONE
                            }
                        }
                    }
                    launch {
                        viewModel.scrollingScrim.collect { bitmap ->
                            if (bitmap != null) {
                                scrollingScrim.setImageBitmap(bitmap)
                                scrollingScrim.visibility = View.VISIBLE
                            } else {
                                scrollingScrim.visibility = View.GONE
                            }
                        }
                    }
                    launch {
                        viewModel.scrollableRect.collect { rect ->
                            if (rect != null) {
                                setScrollablePreview(
                                    scrollablePreview,
                                    viewModel.preview.value,
                                    rect
                                )
                            } else {
                                scrollablePreview.visibility = View.GONE
                            }
                        }
                    }
                    launch {
                        viewModel.badge.collect { badge ->
                            badgeView.setImageDrawable(badge)
                            badgeView.visibility = if (badge != null) View.VISIBLE else View.GONE
                        }
                    }
                    launch {
                        viewModel.previewAction.collect { action ->
                            previewView.setOnClickListener { action?.onClick?.invoke() }
                            previewView.contentDescription = action?.contentDescription
                        }
                    }
                    launch {
                        viewModel.isAnimating.collect { isAnimating ->
                            previewView.isClickable = !isAnimating
                            for (child in actionsContainer.children) {
                                child.isClickable = !isAnimating
                            }
                        }
                    }
                    launch {
                        viewModel.actions.collect { actions ->
                            updateActions(
                                actions,
                                viewModel.animationState.value,
                                view,
                                layoutInflater
                            )
                        }
                    }
                    launch {
                        viewModel.animationState.collect { animationState ->
                            updateActions(
                                viewModel.actions.value,
                                animationState,
                                view,
                                layoutInflater
                            )
                        }
                    }
                }
            }
        }
    }

    private fun updateActions(
        actions: List<ActionButtonViewModel>,
        animationState: AnimationState,
        view: ScreenshotShelfView,
        layoutInflater: LayoutInflater
    ) {
        val actionsContainer: LinearLayout = view.requireViewById(R.id.screenshot_actions)
        val visibleActions =
            actions.filter {
                it.visible &&
                    (animationState == AnimationState.ENTRANCE_COMPLETE ||
                        animationState == AnimationState.ENTRANCE_REVEAL ||
                        it.showDuringEntrance)
            }

        if (visibleActions.isNotEmpty()) {
            view.requireViewById<View>(R.id.actions_container_background).visibility = View.VISIBLE
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
                buttonViewBinder.bind(currentView, action)
            } else {
                // Different ID. Removals have already happened so this must
                // mean that the new action must be inserted here.
                val actionButton =
                    layoutInflater.inflate(R.layout.shelf_action_chip, actionsContainer, false)
                actionsContainer.addView(actionButton, index)
                buttonViewBinder.bind(actionButton, action)
            }
        }
    }

    private fun setScreenshotBitmap(screenshotPreview: ImageView, bitmap: Bitmap) {
        screenshotPreview.setImageBitmap(bitmap)
        val hasPortraitAspectRatio = bitmap.width < bitmap.height
        val fixedSize = screenshotPreview.resources.getDimensionPixelSize(R.dimen.overlay_x_scale)
        val params: ViewGroup.LayoutParams = screenshotPreview.layoutParams
        if (hasPortraitAspectRatio) {
            params.width = fixedSize
            params.height = FrameLayout.LayoutParams.WRAP_CONTENT
            screenshotPreview.scaleType = ImageView.ScaleType.FIT_START
        } else {
            params.width = FrameLayout.LayoutParams.WRAP_CONTENT
            params.height = fixedSize
            screenshotPreview.scaleType = ImageView.ScaleType.FIT_END
        }

        screenshotPreview.layoutParams = params
        screenshotPreview.requestLayout()
    }

    private fun setScrollablePreview(
        scrollablePreview: ImageView,
        bitmap: Bitmap?,
        scrollableRect: Rect
    ) {
        if (bitmap == null) {
            return
        }
        val fixedSize = scrollablePreview.resources.getDimensionPixelSize(R.dimen.overlay_x_scale)
        val inPortrait =
            scrollablePreview.resources.configuration.orientation ==
                Configuration.ORIENTATION_PORTRAIT
        val scale: Float = fixedSize / ((if (inPortrait) bitmap.width else bitmap.height).toFloat())
        val params = scrollablePreview.layoutParams

        params.width = (scale * scrollableRect.width()).toInt()
        params.height = (scale * scrollableRect.height()).toInt()
        val matrix = Matrix()
        matrix.setScale(scale, scale)
        matrix.postTranslate(-scrollableRect.left * scale, -scrollableRect.top * scale)

        scrollablePreview.translationX =
            (scale *
                if (scrollablePreview.layoutDirection == LayoutDirection.LTR) scrollableRect.left
                else scrollableRect.right - (scrollablePreview.parent as View).width)
        scrollablePreview.translationY = scale * scrollableRect.top
        scrollablePreview.setImageMatrix(matrix)
        scrollablePreview.setImageBitmap(bitmap)
        scrollablePreview.setVisibility(View.VISIBLE)
    }
}
