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

package com.android.systemui.common.ui.view

import android.view.View

/**
 * Set this view's [View#importantForAccessibility] to [View#IMPORTANT_FOR_ACCESSIBILITY_YES] or
 * [View#IMPORTANT_FOR_ACCESSIBILITY_NO] based on [value].
 */
fun View.setImportantForAccessibilityYesNo(value: Boolean) {
    importantForAccessibility =
        if (value) View.IMPORTANT_FOR_ACCESSIBILITY_YES else View.IMPORTANT_FOR_ACCESSIBILITY_NO
}
