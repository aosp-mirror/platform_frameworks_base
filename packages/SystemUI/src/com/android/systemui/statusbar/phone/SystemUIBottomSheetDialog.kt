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
package com.android.systemui.statusbar.phone

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import com.android.systemui.res.R

/** A dialog shown as a bottom sheet. */
open class SystemUIBottomSheetDialog(
    context: Context,
    theme: Int = R.style.Theme_SystemUI_Dialog,
) : Dialog(context, theme) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window?.apply {
            setType(WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL)
            addPrivateFlags(WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS)

            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            val edgeToEdgeHorizontally =
                context.resources.getBoolean(R.bool.config_edgeToEdgeBottomSheetDialog)
            if (edgeToEdgeHorizontally) {
                decorView.setPadding(0, 0, 0, 0)
                setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT
                )

                val lp = attributes
                lp.fitInsetsSides = 0
                lp.horizontalMargin = 0f
                attributes = lp
            }
        }
        setCanceledOnTouchOutside(true)
    }
}
