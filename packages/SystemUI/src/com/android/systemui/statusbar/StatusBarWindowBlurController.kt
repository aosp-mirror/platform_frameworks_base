/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar

import android.content.res.Resources
import android.view.View
import com.android.internal.util.IndentingPrintWriter
import com.android.systemui.DumpController
import com.android.systemui.Dumpable
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.statusbar.phone.PanelExpansionListener
import java.io.FileDescriptor
import java.io.PrintWriter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Controller responsible for statusbar window blur.
 */
@Singleton
class StatusBarWindowBlurController @Inject constructor(
    @Main private val resources: Resources,
    private val statusBarStateController: SysuiStatusBarStateController,
    private val blurUtils: BlurUtils,
    dumpController: DumpController
) : PanelExpansionListener, Dumpable {

    lateinit var root: View
    private var blurRadius = 0

    init {
        dumpController.registerDumpable(this)
    }

    override fun onPanelExpansionChanged(expansion: Float, tracking: Boolean) {
        val newBlur = if (statusBarStateController.state == StatusBarState.SHADE)
            blurUtils.radiusForRatio(expansion)
        else
            0

        if (blurRadius == newBlur) {
            return
        }
        blurRadius = newBlur
        blurUtils.applyBlur(root.viewRootImpl, blurRadius)
    }

    override fun dump(fd: FileDescriptor, pw: PrintWriter, args: Array<out String>) {
        IndentingPrintWriter(pw, "  ").use {
            it.println("StatusBarWindowBlurController:")
            it.increaseIndent()
            it.println("blurRadius: $blurRadius")
        }
    }
}