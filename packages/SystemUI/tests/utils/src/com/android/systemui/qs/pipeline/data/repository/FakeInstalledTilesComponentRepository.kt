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

import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Drawable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeInstalledTilesComponentRepository : InstalledTilesComponentRepository {

    private val installedServicesPerUser = mutableMapOf<Int, MutableStateFlow<List<ServiceInfo>>>()

    override fun getInstalledTilesComponents(userId: Int): Flow<Set<ComponentName>> {
        return getFlow(userId).map { it.map { it.componentName }.toSet() }
    }

    override fun getInstalledTilesServiceInfos(userId: Int): List<ServiceInfo> {
        return getFlow(userId).value
    }

    fun setInstalledPackagesForUser(userId: Int, components: Set<ComponentName>) {
        getFlow(userId).value =
            components.map {
                ServiceInfo().apply {
                    packageName = it.packageName
                    name = it.className
                    applicationInfo = ApplicationInfo()
                }
            }
    }

    fun setInstalledServicesForUser(userId: Int, services: List<ServiceInfo>) {
        getFlow(userId).value = services.toList()
    }

    private fun getFlow(userId: Int): MutableStateFlow<List<ServiceInfo>> =
        installedServicesPerUser.getOrPut(userId) { MutableStateFlow(emptyList()) }

    companion object {
        fun ServiceInfo(
            componentName: ComponentName,
            serviceName: String,
            serviceIcon: Drawable? = null,
            appName: String = "",
            appIcon: Drawable? = null
        ): ServiceInfo {
            val appInfo =
                object : ApplicationInfo() {
                    override fun loadLabel(pm: PackageManager): CharSequence {
                        return appName
                    }

                    override fun loadIcon(pm: PackageManager?): Drawable? {
                        return appIcon
                    }
                }
            val serviceInfo =
                object : ServiceInfo() {
                        override fun loadLabel(pm: PackageManager): CharSequence {
                            return serviceName
                        }

                        override fun loadIcon(pm: PackageManager?): Drawable? {
                            return serviceIcon ?: getApplicationInfo().loadIcon(pm)
                        }
                    }
                    .apply {
                        packageName = componentName.packageName
                        name = componentName.className
                        applicationInfo = appInfo
                    }
            return serviceInfo
        }
    }
}
