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

package com.android.systemui.statusbar.phone

import android.annotation.ColorInt
import android.app.WallpaperManager
import android.graphics.Color
import android.os.Handler
import android.os.RemoteException
import android.view.IWindowManager
import com.android.systemui.Dumpable
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.phone.dagger.CentralSurfacesComponent
import com.android.systemui.statusbar.phone.dagger.CentralSurfacesComponent.CentralSurfacesScope
import java.io.PrintWriter
import java.util.concurrent.Executor
import javax.inject.Inject

/** Responsible for providing information about the background of letterboxed apps. */
@CentralSurfacesScope
class LetterboxBackgroundProvider
@Inject
constructor(
    private val windowManager: IWindowManager,
    @Background private val backgroundExecutor: Executor,
    private val dumpManager: DumpManager,
    private val wallpaperManager: WallpaperManager,
    @Main private val mainHandler: Handler,
) : CentralSurfacesComponent.Startable, Dumpable {

    @ColorInt
    var letterboxBackgroundColor: Int = Color.BLACK
        private set

    var isLetterboxBackgroundMultiColored: Boolean = false
        private set

    private val wallpaperColorsListener =
        WallpaperManager.OnColorsChangedListener { _, _ ->
            fetchBackgroundColorInfo()
        }

    override fun start() {
        dumpManager.registerDumpable(javaClass.simpleName, this)
        fetchBackgroundColorInfo()
        wallpaperManager.addOnColorsChangedListener(wallpaperColorsListener, mainHandler)
    }

    private fun fetchBackgroundColorInfo() {
        // Using a background executor, as binder calls to IWindowManager are blocking
        backgroundExecutor.execute {
            try {
                isLetterboxBackgroundMultiColored = windowManager.isLetterboxBackgroundMultiColored
                letterboxBackgroundColor = windowManager.letterboxBackgroundColorInArgb
            } catch (e: RemoteException) {
                e.rethrowFromSystemServer()
            }
        }
    }

    override fun stop() {
        dumpManager.unregisterDumpable(javaClass.simpleName)
        wallpaperManager.removeOnColorsChangedListener(wallpaperColorsListener)
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println(
            """
           letterboxBackgroundColor: ${Color.valueOf(letterboxBackgroundColor)}
           isLetterboxBackgroundMultiColored: $isLetterboxBackgroundMultiColored
       """.trimIndent())
    }
}
