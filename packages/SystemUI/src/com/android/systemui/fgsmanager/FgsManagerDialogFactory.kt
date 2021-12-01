/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.systemui.fgsmanager

import android.content.Context
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.animation.DialogLaunchAnimator
import android.content.DialogInterface
import android.view.View
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.util.time.SystemClock
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * Factory to create [FgsManagerDialog] instances
 */
@SysUISingleton
class FgsManagerDialogFactory
@Inject constructor(
    private val context: Context,
    @Main private val executor: Executor,
    @Background private val backgroundExecutor: Executor,
    private val systemClock: SystemClock,
    private val dialogLaunchAnimator: DialogLaunchAnimator,
    private val fgsManagerDialogController: FgsManagerDialogController
) {

    val lock = Any()

    companion object {
        private var fgsManagerDialog: FgsManagerDialog? = null
    }

    /**
     * Creates the dialog if it doesn't exist
     */
    fun create(viewLaunchedFrom: View?) {
        if (fgsManagerDialog == null) {
            fgsManagerDialog = FgsManagerDialog(context, executor, backgroundExecutor,
                    systemClock, fgsManagerDialogController)
            fgsManagerDialog!!.setOnDismissListener { i: DialogInterface? ->
                fgsManagerDialogController.onFinishDialog()
                fgsManagerDialog = null
            }
            dialogLaunchAnimator.showFromView(fgsManagerDialog!!, viewLaunchedFrom!!)
        }
    }
}