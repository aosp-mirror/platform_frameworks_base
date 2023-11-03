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

package com.android.systemui.qs.tiles.impl.custom.data.repository

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.UserHandle
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.qs.tiles.impl.custom.data.entity.CustomTileDefaults
import com.android.systemui.qs.tiles.impl.di.QSTileScope
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.withContext

/** Gets a label and an icon for a custom tile based on its package. */
interface CustomTileDefaultsRepository {

    /**
     * Returns [CustomTileDefaults] for a specified [user]. An updated value may be emitted as a
     * response for [requestNewDefaults].
     *
     * @see requestNewDefaults
     */
    fun defaults(user: UserHandle): Flow<CustomTileDefaults>

    /**
     * Requests the new default from the [PackageManager]. The result is cached until the input of
     * this method changes or [force] == true is passed.
     *
     * Listen to [defaults] to get the loaded result
     */
    fun requestNewDefaults(
        user: UserHandle,
        componentName: ComponentName,
        force: Boolean = false,
    )
}

@QSTileScope
class CustomTileDefaultsRepositoryImpl
@Inject
constructor(
    private val context: Context,
    @Application applicationScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
) : CustomTileDefaultsRepository {

    private val defaultsRequests =
        MutableSharedFlow<DefaultsRequest>(
            replay = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

    private val defaults: SharedFlow<DefaultsResult> =
        defaultsRequests
            .distinctUntilChanged { old, new ->
                if (new.force) {
                    // force update should always pass
                    false
                } else {
                    old == new
                }
            }
            .map { DefaultsResult(it.user, loadDefaults(it.user, it.componentName)) }
            .shareIn(applicationScope, SharingStarted.WhileSubscribed(), replay = 1)

    override fun defaults(user: UserHandle): Flow<CustomTileDefaults> =
        defaults.filter { it.user == user }.map { it.data }

    override fun requestNewDefaults(
        user: UserHandle,
        componentName: ComponentName,
        force: Boolean,
    ) {
        defaultsRequests.tryEmit(DefaultsRequest(user, componentName, force))
    }

    private suspend fun loadDefaults(
        user: UserHandle,
        componentName: ComponentName
    ): CustomTileDefaults =
        withContext(backgroundDispatcher) {
            try {
                val userContext = context.createContextAsUser(user, 0)
                val info = componentName.getServiceInfo(userContext.packageManager)

                val iconRes = if (info.icon == NO_ICON_RES) info.applicationInfo.icon else info.icon
                if (iconRes == NO_ICON_RES) {
                    return@withContext CustomTileDefaults.Error
                }

                CustomTileDefaults.Result(
                    Icon.createWithResource(componentName.packageName, iconRes),
                    info.loadLabel(userContext.packageManager)
                )
            } catch (e: PackageManager.NameNotFoundException) {
                CustomTileDefaults.Error
            }
        }

    private fun ComponentName.getServiceInfo(
        packageManager: PackageManager,
    ): ServiceInfo {
        val isSystemApp = packageManager.getApplicationInfo(packageName, 0).isSystemApp
        var flags =
            (PackageManager.MATCH_DIRECT_BOOT_UNAWARE or PackageManager.MATCH_DIRECT_BOOT_AWARE)
        if (isSystemApp) {
            flags = flags or PackageManager.MATCH_DISABLED_COMPONENTS
        }
        return packageManager.getServiceInfo(this, flags)
    }

    private data class DefaultsRequest(
        val user: UserHandle,
        val componentName: ComponentName,
        val force: Boolean = false,
    )

    private data class DefaultsResult(val user: UserHandle, val data: CustomTileDefaults)

    private companion object {

        const val NO_ICON_RES = 0
    }
}
