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
import android.content.pm.ServiceInfo
import android.os.UserHandle
import android.service.quicksettings.TileService
import androidx.annotation.GuardedBy
import com.android.systemui.common.data.repository.PackageChangeRepository
import com.android.systemui.common.shared.model.PackageChangeModel
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.util.kotlin.isComponentActuallyEnabled
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

interface InstalledTilesComponentRepository {

    fun getInstalledTilesComponents(userId: Int): Flow<Set<ComponentName>>

    fun getInstalledTilesServiceInfos(userId: Int): List<ServiceInfo>
}

@SysUISingleton
class InstalledTilesComponentRepositoryImpl
@Inject
constructor(
    @Application private val applicationContext: Context,
    @Background private val backgroundScope: CoroutineScope,
    private val packageChangeRepository: PackageChangeRepository
) : InstalledTilesComponentRepository {

    @GuardedBy("userMap") private val userMap = mutableMapOf<Int, StateFlow<List<ServiceInfo>>>()

    override fun getInstalledTilesComponents(userId: Int): Flow<Set<ComponentName>> =
        synchronized(userMap) { getForUserLocked(userId) }
            .map { it.mapTo(mutableSetOf()) { it.componentName } }

    override fun getInstalledTilesServiceInfos(userId: Int): List<ServiceInfo> {
        return synchronized(userMap) { getForUserLocked(userId).value }
    }

    private fun getForUserLocked(userId: Int): StateFlow<List<ServiceInfo>> {
        return userMap.getOrPut(userId) {
            /*
             * In order to query [PackageManager] for different users, this implementation will
             * call [Context.createContextAsUser] and retrieve the [PackageManager] from that
             * context.
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
            packageChangeRepository
                .packageChanged(UserHandle.of(userId))
                .onStart { emit(PackageChangeModel.Empty) }
                .map { reloadComponents(userId, packageManager) }
                .distinctUntilChanged()
                .stateIn(backgroundScope, SharingStarted.WhileSubscribed(), emptyList())
        }
    }

    @WorkerThread
    private fun reloadComponents(userId: Int, packageManager: PackageManager): List<ServiceInfo> {
        return packageManager
            .queryIntentServicesAsUser(INTENT, FLAGS, userId)
            .mapNotNull { it.serviceInfo }
            .filter { it.permission == BIND_QUICK_SETTINGS_TILE }
            .filter {
                try {
                    packageManager.isComponentActuallyEnabled(it)
                } catch (e: IllegalArgumentException) {
                    // If the package is not found, it means it was uninstalled between query
                    // and now. So it's clearly not enabled.
                    false
                }
            }
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
