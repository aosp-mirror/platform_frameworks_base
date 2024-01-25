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
package com.android.systemui.volume.panel.component.mediaoutput.data.repository

import com.android.settingslib.volume.data.repository.LocalMediaRepository
import com.android.settingslib.volume.data.repository.LocalMediaRepositoryImpl
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.media.controls.pipeline.LocalMediaManagerFactory
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope

class LocalMediaRepositoryFactory
@Inject
constructor(
    private val localMediaManagerFactory: LocalMediaManagerFactory,
    @Application private val coroutineScope: CoroutineScope,
    @Background private val backgroundCoroutineContext: CoroutineContext,
) {

    fun create(packageName: String?): LocalMediaRepository =
        LocalMediaRepositoryImpl(
            localMediaManagerFactory.create(packageName),
            coroutineScope,
            backgroundCoroutineContext,
        )
}
