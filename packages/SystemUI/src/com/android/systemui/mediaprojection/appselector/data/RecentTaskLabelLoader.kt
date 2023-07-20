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

package com.android.systemui.mediaprojection.appselector.data

import android.annotation.UserIdInt
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.UserHandle
import com.android.systemui.dagger.qualifiers.Background
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

interface RecentTaskLabelLoader {
    suspend fun loadLabel(userId: Int, componentName: ComponentName): CharSequence?
}

class ActivityTaskManagerLabelLoader
@Inject
constructor(
    @Background private val coroutineDispatcher: CoroutineDispatcher,
    private val packageManager: PackageManager
) : RecentTaskLabelLoader {

    override suspend fun loadLabel(
        @UserIdInt userId: Int,
        componentName: ComponentName
    ): CharSequence? =
        withContext(coroutineDispatcher) {
            val userHandle = UserHandle(userId)
            val appInfo =
                packageManager.getApplicationInfo(
                    componentName.packageName,
                    PackageManager.ApplicationInfoFlags.of(0 /* no flags */)
                )
            val label = packageManager.getApplicationLabel(appInfo)
            return@withContext packageManager.getUserBadgedLabel(label, userHandle)
        }
}
