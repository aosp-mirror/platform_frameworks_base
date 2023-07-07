/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.user.ui.binder

import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.animation.Expandable
import com.android.systemui.common.ui.binder.TextViewBinder
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.statusbar.phone.userswitcher.StatusBarUserSwitcherContainer
import com.android.systemui.user.ui.viewmodel.StatusBarUserChipViewModel
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@OptIn(InternalCoroutinesApi::class)
object StatusBarUserChipViewBinder {
    /** Binds the status bar user chip view model to the given view */
    @JvmStatic
    fun bind(
        view: StatusBarUserSwitcherContainer,
        viewModel: StatusBarUserChipViewModel,
    ) {
        view.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.isChipVisible.collect { isVisible -> view.isVisible = isVisible }
                }

                launch {
                    viewModel.userName.collect { name -> TextViewBinder.bind(view.text, name) }
                }

                launch {
                    viewModel.userAvatar.collect { avatar -> view.avatar.setImageDrawable(avatar) }
                }

                bindButton(view, viewModel)
            }
        }
    }

    private fun bindButton(
        view: StatusBarUserSwitcherContainer,
        viewModel: StatusBarUserChipViewModel,
    ) {
        view.setOnClickListener { viewModel.onClick(Expandable.fromView(view)) }
    }
}
