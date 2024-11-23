/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.wm.shell.compatui.letterbox

import android.graphics.Rect
import android.view.SurfaceControl
import android.view.SurfaceControl.Transaction
import com.android.internal.protolog.ProtoLog
import com.android.wm.shell.dagger.WMSingleton
import com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_APP_COMPAT
import javax.inject.Inject

/**
 * Component responsible for handling the lifecycle of a single letterbox surface.
 */
@WMSingleton
class SingleSurfaceLetterboxController @Inject constructor(
    private val letterboxBuilder: LetterboxSurfaceBuilder
) : LetterboxController {

    companion object {
        @JvmStatic
        private val TAG = "LetterboxController"
    }

    private val letterboxMap = mutableMapOf<LetterboxKey, SurfaceControl>()

    /**
     * Creates a Letterbox Surface for a given displayId/taskId if it doesn't exist.
     */
    override fun createLetterboxSurface(
        key: LetterboxKey,
        transaction: Transaction,
        parentLeash: SurfaceControl
    ) {
        letterboxMap.runOnItem(key, onMissed = { k, m ->
            m[k] = letterboxBuilder.createSurface(
                transaction,
                parentLeash,
                surfaceName = "ShellLetterboxSurface-$key",
                callSite = "LetterboxController-createLetterboxSurface"
            )
        })
    }

    /**
     * Invoked to destroy the surfaces for a letterbox session for given displayId/taskId.
     */
    override fun destroyLetterboxSurface(
        key: LetterboxKey,
        transaction: Transaction
    ) {
        letterboxMap.runOnItem(key, onFound = { item ->
            item.run {
                transaction.remove(this)
            }
        })
        letterboxMap.remove(key)
    }

    /**
     * Invoked to show/hide the letterbox surfaces for given displayId/taskId.
     */
    override fun updateLetterboxSurfaceVisibility(
        key: LetterboxKey,
        transaction: Transaction,
        visible: Boolean
    ) {
        letterboxMap.runOnItem(key, onFound = { item ->
            item.run {
                transaction.setVisibility(this, visible)
            }
        })
    }

    /**
     * Updates the bounds for the letterbox surfaces for given displayId/taskId.
     */
    override fun updateLetterboxSurfaceBounds(
        key: LetterboxKey,
        transaction: Transaction,
        taskBounds: Rect
    ) {
        letterboxMap.runOnItem(key, onFound = { item ->
            item.run {
                transaction.moveAndCrop(this, taskBounds)
            }
        })
    }

    override fun dump() {
        ProtoLog.v(WM_SHELL_APP_COMPAT, "%s: %s", TAG, "${letterboxMap.keys}")
    }

    /*
     * Executes [onFound] on the [SurfaceControl] if present or [onMissed] if not present.
     */
    private fun MutableMap<LetterboxKey, SurfaceControl>.runOnItem(
        key: LetterboxKey,
        onFound: (SurfaceControl) -> Unit = { _ -> },
        onMissed: (
            LetterboxKey,
            MutableMap<LetterboxKey, SurfaceControl>
        ) -> Unit = { _, _ -> }
    ) {
        this[key]?.let {
            return onFound(it)
        }
        return onMissed(key, this)
    }

    private fun Transaction.moveAndCrop(
        surface: SurfaceControl,
        rect: Rect
    ): Transaction =
        setPosition(surface, rect.left.toFloat(), rect.top.toFloat())
            .setWindowCrop(
                surface,
                rect.width(),
                rect.height()
            )
}
