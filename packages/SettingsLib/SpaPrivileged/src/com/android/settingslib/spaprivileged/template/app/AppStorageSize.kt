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

package com.android.settingslib.spaprivileged.template.app

import android.content.pm.ApplicationInfo
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.settingslib.spa.framework.compose.rememberContext
import com.android.settingslib.spaprivileged.framework.compose.placeholder
import com.android.settingslib.spaprivileged.model.app.AppStorageRepository
import com.android.settingslib.spaprivileged.model.app.AppStorageRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

@Composable
fun ApplicationInfo.getStorageSize(): State<String> =
    getStorageSize(rememberContext(::AppStorageRepositoryImpl))

@VisibleForTesting
@Composable
fun ApplicationInfo.getStorageSize(appStorageRepository: AppStorageRepository): State<String> {
    val app = this
    return remember(app) {
            flow { emit(appStorageRepository.formatSize(app)) }.flowOn(Dispatchers.Default)
        }
        .collectAsStateWithLifecycle(initialValue = placeholder())
}
