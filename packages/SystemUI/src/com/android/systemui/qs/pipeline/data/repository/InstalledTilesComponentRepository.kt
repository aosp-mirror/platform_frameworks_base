/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.pipeline.data.repository

import android.Manifest.permission.BIND_QUICK_SETTINGS_TILE
import android.annotation.WorkerThread
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageManager.ResolveInfoFlags
import android.os.UserHandle
import android.service.quicksettings.TileService
import com.android.systemui.common.data.repository.PackageChangeRepository
import com.android.systemui.common.data.shared.model.PackageChangeModel
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.util.kotlin.isComponentActuallyEnabled
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

interface InstalledTilesComponentRepository {

    fun getInstalledTilesComponents(userId: Int): Flow<Set<ComponentName>>
}

@SysUISingleton
class InstalledTilesComponentRepositoryImpl
@Inject
constructor(
    @Application private val applicationContext: Context,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val packageChangeRepository: PackageChangeRepository
) : InstalledTilesComponentRepository {

    override fun getInstalledTilesComponents(userId: Int): Flow<Set<ComponentName>> {
        /*
         * In order to query [PackageManager] for different users, this implementation will call
         * [Context.createContextAsUser] and retrieve the [PackageManager] from that context.
         */
        val packageManager =
            if (applicationContext.userId == userId) {
                applicationContext.packageManager
            } else {
                applicationContext
                    .createContextAsUser(
                        UserHandle.of(userId),
                        /* flags */ 0,
                    )
                    .packageManager
            }
        return packageChangeRepository
            .packageChanged(UserHandle.of(userId))
            .onStart { emit(PackageChangeModel.Empty) }
            .map { reloadComponents(userId, packageManager) }
            .distinctUntilChanged()
            .flowOn(backgroundDispatcher)
    }

    @WorkerThread
    private fun reloadComponents(userId: Int, packageManager: PackageManager): Set<ComponentName> {
        return packageManager
            .queryIntentServicesAsUser(INTENT, FLAGS, userId)
            .mapNotNull { it.serviceInfo }
            .filter { it.permission == BIND_QUICK_SETTINGS_TILE }
            .filter { packageManager.isComponentActuallyEnabled(it) }
            .mapTo(mutableSetOf()) { it.componentName }
    }

    companion object {
        private val INTENT = Intent(TileService.ACTION_QS_TILE)
        private val FLAGS =
            ResolveInfoFlags.of(
                (PackageManager.GET_SERVICES or
                        PackageManager.MATCH_DIRECT_BOOT_AWARE or
                        PackageManager.MATCH_DIRECT_BOOT_UNAWARE)
                    .toLong()
            )
    }
}
