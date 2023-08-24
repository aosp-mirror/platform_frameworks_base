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

package com.android.systemui.statusbar.notification.row.ui.viewbinder

import android.view.MotionEvent
import android.view.View
import android.view.View.OnTouchListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.Gefingerpoken
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.statusbar.notification.row.ActivatableNotificationView
import com.android.systemui.statusbar.notification.row.ui.viewmodel.ActivatableNotificationViewModel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch

/** Binds an [ActivatableNotificationView] to its [view model][ActivatableNotificationViewModel]. */
object ActivatableNotificationViewBinder {

    fun bind(
        viewModel: ActivatableNotificationViewModel,
        view: ActivatableNotificationView,
        falsingManager: FalsingManager,
    ) {
        ExpandableOutlineViewBinder.bind(viewModel, view)
        val touchHandler = TouchHandler(view, falsingManager)
        view.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.isTouchable.collect { isTouchable ->
                        touchHandler.isTouchEnabled = isTouchable
                    }
                }
                view.registerListenersWhileAttached(touchHandler)
            }
        }
    }

    private suspend fun ActivatableNotificationView.registerListenersWhileAttached(
        touchHandler: TouchHandler,
    ): Unit =
        try {
            setOnTouchListener(touchHandler)
            setTouchHandler(touchHandler)
            awaitCancellation()
        } finally {
            setTouchHandler(null)
            setOnTouchListener(null)
        }
}

private class TouchHandler(
    private val view: ActivatableNotificationView,
    private val falsingManager: FalsingManager,
) : Gefingerpoken, OnTouchListener {

    var isTouchEnabled = false

    override fun onTouch(v: View, ev: MotionEvent): Boolean {
        val result = false
        if (ev.action == MotionEvent.ACTION_UP) {
            view.setLastActionUpTime(ev.eventTime)
        }
        // With a11y, just do nothing.
        if (!isTouchEnabled) {
            return false
        }
        if (ev.action == MotionEvent.ACTION_UP) {
            // If this is a false tap, capture the even so it doesn't result in a click.
            return falsingManager.isFalseTap(FalsingManager.LOW_PENALTY)
        }
        return result
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = false

    /** Use [onTouch] instead. */
    override fun onTouchEvent(ev: MotionEvent): Boolean = false
}
