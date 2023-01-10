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

package com.android.systemui.mediaprojection.appselector.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.UserHandle
import com.android.launcher3.icons.BaseIconFactory.IconOptions
import com.android.launcher3.icons.IconFactory
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.shared.system.PackageManagerWrapper
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

interface AppIconLoader {
    suspend fun loadIcon(userId: Int, component: ComponentName): Drawable?
}

class IconLoaderLibAppIconLoader
@Inject
constructor(
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val context: Context,
    // Use wrapper to access hidden API that allows to get ActivityInfo for any user id
    private val packageManagerWrapper: PackageManagerWrapper,
    private val packageManager: PackageManager,
    private val iconFactoryProvider: Provider<IconFactory>
) : AppIconLoader {

    override suspend fun loadIcon(userId: Int, component: ComponentName): Drawable? =
        withContext(backgroundDispatcher) {
            iconFactoryProvider.get().use<IconFactory, Drawable?> { iconFactory ->
                val activityInfo =
                    packageManagerWrapper.getActivityInfo(component, userId)
                        ?: return@withContext null
                val icon = activityInfo.loadIcon(packageManager) ?: return@withContext null
                val userHandler = UserHandle.of(userId)
                val options = IconOptions().apply { setUser(userHandler) }
                val badgedIcon = iconFactory.createBadgedIconBitmap(icon, options)
                badgedIcon.newIcon(context)
            }
        }
}
