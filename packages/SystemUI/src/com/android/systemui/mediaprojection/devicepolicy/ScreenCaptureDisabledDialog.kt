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
package com.android.systemui.mediaprojection.devicepolicy

import android.content.Context
import com.android.systemui.R
import com.android.systemui.statusbar.phone.SystemUIDialog

/** Dialog that shows that screen capture is disabled on this device. */
class ScreenCaptureDisabledDialog(context: Context) : SystemUIDialog(context) {

    init {
        setTitle(context.getString(R.string.screen_capturing_disabled_by_policy_dialog_title))
        setMessage(
            context.getString(R.string.screen_capturing_disabled_by_policy_dialog_description)
        )
        setIcon(R.drawable.ic_cast)
        setButton(BUTTON_POSITIVE, context.getString(android.R.string.ok)) { _, _ -> cancel() }
    }
}
