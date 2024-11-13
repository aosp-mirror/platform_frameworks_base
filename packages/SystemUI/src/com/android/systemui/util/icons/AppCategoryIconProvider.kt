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

package com.android.systemui.util.icons

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.RemoteException
import android.util.Log
import com.android.systemui.assist.AssistManager
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.shared.system.PackageManagerWrapper
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

interface AppCategoryIconProvider {
    /** Returns the [Icon] of the default assistant app, if it exists. */
    suspend fun assistantAppIcon(): Icon?

    /**
     * Returns the [Icon] of the default app of [category], if it exists. Category can be for
     * example [Intent.CATEGORY_APP_EMAIL] or [Intent.CATEGORY_APP_CALCULATOR].
     */
    suspend fun categoryAppIcon(category: String): Icon?
}

class AppCategoryIconProviderImpl
@Inject
constructor(
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val assistManager: AssistManager,
    private val packageManager: PackageManager,
    private val packageManagerWrapper: PackageManagerWrapper,
) : AppCategoryIconProvider {

    override suspend fun assistantAppIcon(): Icon? {
        val assistInfo = assistManager.assistInfo ?: return null
        return getPackageIcon(assistInfo.packageName)
    }

    override suspend fun categoryAppIcon(category: String): Icon? {
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(category) }
        val packageInfo = getPackageInfo(intent) ?: return null
        return getPackageIcon(packageInfo.packageName)
    }

    private suspend fun getPackageInfo(intent: Intent): PackageInfo? =
        withContext(backgroundDispatcher) {
            val packageName =
                packageManagerWrapper
                    .resolveActivity(/* intent= */ intent, /* flags= */ 0)
                    ?.activityInfo
                    ?.packageName ?: return@withContext null
            return@withContext getPackageInfo(packageName)
        }

    private suspend fun getPackageIcon(packageName: String): Icon? {
        val appInfo = getPackageInfo(packageName)?.applicationInfo ?: return null
        return if (appInfo.icon != 0) {
            Icon.createWithResource(appInfo.packageName, appInfo.icon)
        } else {
            null
        }
    }

    private suspend fun getPackageInfo(packageName: String): PackageInfo? =
        withContext(backgroundDispatcher) {
            try {
                return@withContext packageManager.getPackageInfo(packageName, /* flags= */ 0)
            } catch (e: RemoteException) {
                Log.e(TAG, "Failed to retrieve package info for $packageName")
                return@withContext null
            }
        }

    companion object {
        private const val TAG = "DefaultAppsIconProvider"
    }
}
