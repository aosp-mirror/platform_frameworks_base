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

package com.android.systemui.qs.pipeline.data.model

/**
 * Perform processing of the [RestoreData] before or after it's applied to repositories.
 *
 * The order in which the restore processors are applied in not deterministic.
 *
 * In order to declare a restore processor, add it in [RestoreProcessingModule] using
 *
 * ```
 * @Binds
 * @IntoSet
 * ``
 */
interface RestoreProcessor {

    /** Should be called before applying the restore to the necessary repositories */
    suspend fun preProcessRestore(restoreData: RestoreData) {}

    /** Should be called after requesting the repositories to update. */
    suspend fun postProcessRestore(restoreData: RestoreData) {}
}
