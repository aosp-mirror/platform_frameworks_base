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

package com.android.systemui.statusbar.phone

import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager

/** [DialogDelegate] that configures a dialog to be an edge-to-edge one. */
class EdgeToEdgeDialogDelegate : DialogDelegate<SystemUIDialog> {

    override fun onCreate(dialog: SystemUIDialog, savedInstanceState: Bundle?) {
        dialog.window?.apply {
            setGravity(Gravity.BOTTOM or Gravity.CENTER)
            attributes =
                attributes.apply {
                    fitInsetsSides = 0
                    attributes.apply {
                        layoutInDisplayCutoutMode =
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    }
                }
        }
    }

    override fun getWidth(dialog: SystemUIDialog): Int = WindowManager.LayoutParams.MATCH_PARENT

    override fun getHeight(dialog: SystemUIDialog): Int = WindowManager.LayoutParams.MATCH_PARENT
}
