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

package com.android.systemui.volume.dialog.ringer.data.repository

import android.content.Context
import com.android.systemui.Prefs
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

interface VolumeDialogRingerFeedbackRepository {

    /** gets number of shown toasts */
    suspend fun getToastCount(): Int

    /** updates number of shown toasts */
    suspend fun updateToastCount(toastCount: Int)
}

class VolumeDialogRingerFeedbackRepositoryImpl
@Inject
constructor(
    @Application private val applicationContext: Context,
    @Background val backgroundDispatcher: CoroutineDispatcher,
) : VolumeDialogRingerFeedbackRepository {

    override suspend fun getToastCount(): Int =
        withContext(backgroundDispatcher) {
            return@withContext Prefs.getInt(
                applicationContext,
                Prefs.Key.SEEN_RINGER_GUIDANCE_COUNT,
                0,
            )
        }

    override suspend fun updateToastCount(toastCount: Int) {
        withContext(backgroundDispatcher) {
            Prefs.putInt(applicationContext, Prefs.Key.SEEN_RINGER_GUIDANCE_COUNT, toastCount + 1)
        }
    }
}
