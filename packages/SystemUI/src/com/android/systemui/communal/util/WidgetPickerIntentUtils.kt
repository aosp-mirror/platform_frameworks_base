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

package com.android.systemui.communal.util

import android.content.ComponentName
import android.content.Intent
import android.os.UserHandle

/** Provides util functions for the intent of adding widgets from the Communal widget picker. */
object WidgetPickerIntentUtils {
    fun getWidgetExtraFromIntent(intent: Intent) =
        WidgetExtra(
            intent.getParcelableExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName::class.java),
            intent.getParcelableExtra(Intent.EXTRA_USER, UserHandle::class.java)
        )
    data class WidgetExtra(val componentName: ComponentName?, val user: UserHandle?)
}
