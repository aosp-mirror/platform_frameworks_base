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

import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import com.android.systemui.statusbar.phone.DialogDelegate
import com.android.systemui.statusbar.phone.SystemUIDialog

class ShortcutCustomizationDialogDelegate : DialogDelegate<SystemUIDialog> {

    override fun onCreate(dialog: SystemUIDialog, savedInstanceState: Bundle?) {
        super.onCreate(dialog, savedInstanceState)
        dialog.window?.apply { setGravity(Gravity.CENTER) }
    }

    override fun getWidth(dialog: SystemUIDialog): Int {
        return WindowManager.LayoutParams.WRAP_CONTENT
    }

    override fun getHeight(dialog: SystemUIDialog): Int {
        return WindowManager.LayoutParams.WRAP_CONTENT
    }
}
