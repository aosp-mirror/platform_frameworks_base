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

package com.android.settingslib.spa.livedata

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.lifecycle.LiveData

/**
 * Starts observing this LiveData and represents its values via State and Callback.
 *
 * Every time there would be new value posted into the LiveData the returned State will be updated
 * causing recomposition of every Callback usage.
 */
@Composable
fun <T> LiveData<T>.observeAsCallback(): () -> T? {
    val isAllowed by observeAsState()
    return { isAllowed }
}
