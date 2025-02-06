/*
 * Copyright 2025 The Android Open Source Project
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

import android.content.Context
import android.graphics.Rect
import android.graphics.Region
import android.os.Handler
import android.view.SurfaceControl
import android.view.SurfaceControl.Transaction
import com.android.internal.protolog.ProtoLog
import com.android.wm.shell.common.InputChannelSupplier
import com.android.wm.shell.common.WindowSessionSupplier
import com.android.wm.shell.compatui.letterbox.LetterboxUtils.Maps.runOnItem
import com.android.wm.shell.dagger.WMSingleton
import com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_APP_COMPAT
import java.util.function.Supplier
import javax.inject.Inject

/**
 * [LetterboxController] implementation responsible for handling the spy [SurfaceControl] we use
 * to detect letterbox events.
 */
@WMSingleton
class LetterboxInputController @Inject constructor(
    private val context: Context,
    private val handler: Handler,
    private val inputSurfaceBuilder: LetterboxInputSurfaceBuilder,
    private val listenerSupplier: Supplier<LetterboxGestureListener>,
    private val windowSessionSupplier: WindowSessionSupplier,
    private val inputChannelSupplier: InputChannelSupplier
) : LetterboxController {

    companion object {
        @JvmStatic
        private val TAG = "LetterboxInputController"
    }

    private val inputDetectorMap = mutableMapOf<LetterboxKey, LetterboxInputDetector>()

    override fun createLetterboxSurface(
        key: LetterboxKey,
        transaction: Transaction,
        parentLeash: SurfaceControl
    ) {
        inputDetectorMap.runOnItem(key, onMissed = { k, m ->
            m[k] =
                LetterboxInputDetector(
                    context,
                    handler,
                    listenerSupplier.get(),
                    inputSurfaceBuilder,
                    windowSessionSupplier,
                    inputChannelSupplier
                ).apply {
                    start(transaction, parentLeash, key)
                }
        })
    }

    override fun destroyLetterboxSurface(
        key: LetterboxKey,
        transaction: Transaction
    ) {
        with(inputDetectorMap) {
            runOnItem(key, onFound = { item ->
                item.stop(transaction)
            })
            remove(key)
        }
    }

    override fun updateLetterboxSurfaceVisibility(
        key: LetterboxKey,
        transaction: Transaction,
        visible: Boolean
    ) {
        with(inputDetectorMap) {
            runOnItem(key, onFound = { item ->
                item.updateVisibility(transaction, visible)
            })
        }
    }

    override fun updateLetterboxSurfaceBounds(
        key: LetterboxKey,
        transaction: Transaction,
        taskBounds: Rect,
        activityBounds: Rect
    ) {
        inputDetectorMap.runOnItem(key, onFound = { item ->
            item.updateTouchableRegion(transaction, Region(taskBounds))
        })
    }

    override fun dump() {
        ProtoLog.v(WM_SHELL_APP_COMPAT, "%s: %s", TAG, "${inputDetectorMap.keys}")
    }
}
