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
 *
 */

package com.android.systemui.keyguard.ui.binder

import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.keyguard.ui.viewmodel.AccessibilityActionsViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.res.R
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.launch

/** View binder for accessibility actions placeholder on keyguard. */
object AccessibilityActionsViewBinder {
    fun bind(
        view: View,
        viewModel: AccessibilityActionsViewModel,
    ): DisposableHandle {
        val disposableHandle =
            view.repeatWhenAttached {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    view.contentDescription =
                        view.resources.getString(R.string.accessibility_desc_lock_screen)

                    launch {
                        viewModel.isOnKeyguard.collect { isOnKeyguard ->
                            view.importantForAccessibility =
                                if (isOnKeyguard) {
                                    View.IMPORTANT_FOR_ACCESSIBILITY_YES
                                } else {
                                    // The border won't be displayed when keyguard is not showing or
                                    // when the focus was previously on it but is now transitioning
                                    // away from the keyguard.
                                    View.IMPORTANT_FOR_ACCESSIBILITY_NO
                                }
                        }
                    }

                    launch {
                        viewModel.isCommunalAvailable.collect { canOpenGlanceableHub ->
                            view.accessibilityDelegate =
                                object : View.AccessibilityDelegate() {
                                    override fun onInitializeAccessibilityNodeInfo(
                                        host: View,
                                        info: AccessibilityNodeInfo
                                    ) {
                                        super.onInitializeAccessibilityNodeInfo(host, info)
                                        // Add custom actions
                                        if (canOpenGlanceableHub) {
                                            val action =
                                                AccessibilityNodeInfo.AccessibilityAction(
                                                    R.id.accessibility_action_open_communal_hub,
                                                    view.resources.getString(
                                                        R.string
                                                            .accessibility_action_open_communal_hub
                                                    ),
                                                )
                                            info.addAction(action)
                                        }
                                    }

                                    override fun performAccessibilityAction(
                                        host: View,
                                        action: Int,
                                        args: Bundle?
                                    ): Boolean {
                                        return if (
                                            action == R.id.accessibility_action_open_communal_hub
                                        ) {
                                            viewModel.openCommunalHub()
                                            true
                                        } else super.performAccessibilityAction(host, action, args)
                                    }
                                }
                        }
                    }
                }
            }
        return disposableHandle
    }
}
