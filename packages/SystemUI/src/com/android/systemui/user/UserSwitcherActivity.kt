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
 */

package com.android.systemui.user

import android.os.Bundle
import android.view.WindowInsets.Type
import android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import com.android.systemui.R
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.user.ui.binder.UserSwitcherViewBinder
import com.android.systemui.user.ui.viewmodel.UserSwitcherViewModel
import dagger.Lazy
import javax.inject.Inject

/** Support a fullscreen user switcher */
open class UserSwitcherActivity
@Inject
constructor(
    private val falsingCollector: FalsingCollector,
    private val viewModelFactory: Lazy<UserSwitcherViewModel.Factory>,
) : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.user_switcher_fullscreen)
        window.decorView.windowInsetsController?.let { controller ->
            controller.systemBarsBehavior = BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(Type.systemBars())
        }
        val viewModel =
            ViewModelProvider(this, viewModelFactory.get())[UserSwitcherViewModel::class.java]
        UserSwitcherViewBinder.bind(
            view = requireViewById(R.id.user_switcher_root),
            viewModel = viewModel,
            lifecycleOwner = this,
            layoutInflater = layoutInflater,
            falsingCollector = falsingCollector,
            onFinish = this::finish,
        )
    }
}
