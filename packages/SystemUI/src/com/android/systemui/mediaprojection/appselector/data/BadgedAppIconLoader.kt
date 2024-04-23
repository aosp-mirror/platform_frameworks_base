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

package com.android.systemui.mediaprojection.appselector.data

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.UserHandle
import com.android.launcher3.icons.BaseIconFactory
import com.android.launcher3.icons.IconFactory
import com.android.launcher3.util.UserIconInfo
import com.android.systemui.dagger.qualifiers.Background
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

class BadgedAppIconLoader
@Inject
constructor(
    private val basicAppIconLoader: BasicAppIconLoader,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val context: Context,
    private val iconFactoryProvider: Provider<IconFactory>,
) {

    suspend fun loadIcon(
        userId: Int,
        userType: RecentTask.UserType,
        componentName: ComponentName
    ): Drawable? =
        withContext(backgroundDispatcher) {
            iconFactoryProvider.get().use<IconFactory, Drawable?> { iconFactory ->
                val icon =
                    basicAppIconLoader.loadIcon(userId, componentName) ?: return@withContext null
                val userHandler = UserHandle.of(userId)
                val iconType = getIconType(userType)
                val options =
                    BaseIconFactory.IconOptions().apply {
                        setUser(UserIconInfo(userHandler, iconType))
                    }
                val badgedIcon = iconFactory.createBadgedIconBitmap(icon, options)
                badgedIcon.newIcon(context)
            }
        }

    private fun getIconType(userType: RecentTask.UserType): Int =
        when (userType) {
            RecentTask.UserType.CLONED -> UserIconInfo.TYPE_CLONED
            RecentTask.UserType.WORK -> UserIconInfo.TYPE_WORK
            RecentTask.UserType.PRIVATE -> UserIconInfo.TYPE_PRIVATE
            RecentTask.UserType.STANDARD -> UserIconInfo.TYPE_MAIN
        }
}
