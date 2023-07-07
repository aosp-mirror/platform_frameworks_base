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

package com.android.settingslib.spa.framework.util

import android.content.Context
import android.content.res.Resources
import android.icu.text.MessageFormat
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import java.util.Locale

@RequiresApi(Build.VERSION_CODES.N)
@SafeVarargs
fun Context.formatString(@StringRes resId: Int, vararg arguments: Pair<String, Any>): String =
    resources.formatString(resId, *arguments)

@RequiresApi(Build.VERSION_CODES.N)
@SafeVarargs
fun Resources.formatString(@StringRes resId: Int, vararg arguments: Pair<String, Any>): String =
    MessageFormat(getString(resId), Locale.getDefault(Locale.Category.FORMAT))
        .format(mapOf(*arguments))
