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

package com.android.systemui.statusbar.notification.footer.ui.viewbinder

import android.view.View
import androidx.lifecycle.lifecycleScope
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.statusbar.notification.footer.ui.view.FooterView
import com.android.systemui.statusbar.notification.footer.ui.viewmodel.FooterViewModel
import com.android.systemui.util.ui.isAnimating
import com.android.systemui.util.ui.stopAnimating
import com.android.systemui.util.ui.value
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.launch

/** Binds a [FooterView] to its [view model][FooterViewModel]. */
object FooterViewBinder {
    fun bind(
        footer: FooterView,
        viewModel: FooterViewModel,
        clearAllNotifications: View.OnClickListener,
    ): DisposableHandle {
        // Listen for changes when the view is attached.
        return footer.repeatWhenAttached {
            lifecycleScope.launch {
                viewModel.clearAllButton.collect { button ->
                    if (button.isVisible.isAnimating) {
                        footer.setClearAllButtonVisible(
                            button.isVisible.value,
                            /* animate = */ true,
                        ) { _ ->
                            button.isVisible.stopAnimating()
                        }
                    } else {
                        footer.setClearAllButtonVisible(
                            button.isVisible.value,
                            /* animate = */ false,
                        )
                    }
                    footer.setClearAllButtonText(button.labelId)
                    footer.setClearAllButtonDescription(button.accessibilityDescriptionId)
                    footer.setClearAllButtonClickListener(clearAllNotifications)
                }
            }

            lifecycleScope.launch {
                viewModel.message.collect { message ->
                    footer.setFooterLabelVisible(message.visible)
                    footer.setMessageString(message.messageId)
                    footer.setMessageIcon(message.iconId)
                }
            }
        }
    }
}
