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

package com.android.systemui.keyboard.shortcut.ui

import android.content.Context
import android.content.Intent
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyboard.shortcut.ui.view.ShortcutHelperActivity
import com.android.systemui.keyboard.shortcut.ui.viewmodel.ShortcutHelperViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@SysUISingleton
class ShortcutHelperActivityStarter(
    private val context: Context,
    @Application private val applicationScope: CoroutineScope,
    private val viewModel: ShortcutHelperViewModel,
    private val startActivity: (Intent) -> Unit,
) : CoreStartable {

    @Inject
    constructor(
        context: Context,
        @Application applicationScope: CoroutineScope,
        viewModel: ShortcutHelperViewModel,
    ) : this(
        context,
        applicationScope,
        viewModel,
        startActivity = { intent -> context.startActivity(intent) }
    )

    override fun start() {
        applicationScope.launch {
            viewModel.shouldShow.collect { shouldShow ->
                if (shouldShow) {
                    startShortcutHelperActivity()
                }
            }
        }
    }

    private fun startShortcutHelperActivity() {
        startActivity(
            Intent(context, ShortcutHelperActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
