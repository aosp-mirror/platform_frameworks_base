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

package com.android.systemui.user

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowInsets
import android.view.WindowInsetsController
import com.android.systemui.res.R
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.user.ui.binder.UserSwitcherViewBinder
import com.android.systemui.user.ui.viewmodel.UserSwitcherViewModel

class UserSwitchFullscreenDialog(
    context: Context,
    private val falsingCollector: FalsingCollector,
    private val userSwitcherViewModel: UserSwitcherViewModel,
) : SystemUIDialog(context, R.style.Theme_UserSwitcherFullscreenDialog) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowForAllUsers(true)
        setCanceledOnTouchOutside(true)

        window?.decorView?.windowInsetsController?.let { controller ->
            controller.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsets.Type.systemBars())
        }

        val view =
            LayoutInflater.from(this.context).inflate(R.layout.user_switcher_fullscreen, null)
        setContentView(view)

        UserSwitcherViewBinder.bind(
            view = requireViewById(R.id.user_switcher_root),
            viewModel = userSwitcherViewModel,
            layoutInflater = layoutInflater,
            falsingCollector = falsingCollector,
            onFinish = this::dismiss,
        )
    }

    override fun getWidth(): Int {
        val displayMetrics = context.resources.displayMetrics.apply {
            checkNotNull(context.display).getRealMetrics(this)
        }
        return displayMetrics.widthPixels
    }

    override fun getHeight() = MATCH_PARENT

}