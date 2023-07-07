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

package com.android.systemui.util

import android.annotation.StringRes
import android.content.res.Resources
import android.util.PluralsMessageFormatter

/**
 * Utility method that provides the localized plural string for the given [messageId]
 * using the [count] parameter.
 */
fun icuMessageFormat(res: Resources, @StringRes messageId: Int, count: Int): String {
    return PluralsMessageFormatter.format(res, mapOf<String, Any>("count" to count), messageId)
}
