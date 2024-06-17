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

package com.android.systemui.common.data.repository

import android.os.UserHandle
import com.android.systemui.common.shared.model.PackageChangeModel
import com.android.systemui.util.time.SystemClock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter

class FakePackageChangeRepository(private val systemClock: SystemClock) : PackageChangeRepository {

    private var _packageChanged = MutableSharedFlow<PackageChangeModel>()

    override fun packageChanged(user: UserHandle) =
        _packageChanged.filter {
            user == UserHandle.ALL || user == UserHandle.getUserHandleForUid(it.packageUid)
        }

    suspend fun notifyChange(model: PackageChangeModel) {
        _packageChanged.emit(model)
    }

    suspend fun notifyInstall(packageName: String, user: UserHandle) {
        notifyChange(
            PackageChangeModel.Installed(
                packageName = packageName,
                packageUid =
                    UserHandle.getUid(
                        /* userId = */ user.identifier,
                        /* appId = */ packageName.hashCode(),
                    ),
                timeMillis = systemClock.currentTimeMillis(),
            )
        )
    }

    suspend fun notifyUpdateStarted(packageName: String, user: UserHandle) {
        notifyChange(
            PackageChangeModel.UpdateStarted(
                packageName = packageName,
                packageUid =
                    UserHandle.getUid(
                        /* userId = */ user.identifier,
                        /* appId = */ packageName.hashCode(),
                    ),
                timeMillis = systemClock.currentTimeMillis(),
            )
        )
    }

    suspend fun notifyUpdateFinished(packageName: String, user: UserHandle) {
        notifyChange(
            PackageChangeModel.UpdateFinished(
                packageName = packageName,
                packageUid =
                    UserHandle.getUid(
                        /* userId = */ user.identifier,
                        /* appId = */ packageName.hashCode(),
                    ),
                timeMillis = systemClock.currentTimeMillis(),
            )
        )
    }

    suspend fun notifyUninstall(packageName: String, user: UserHandle) {
        notifyChange(
            PackageChangeModel.Uninstalled(
                packageName = packageName,
                packageUid =
                    UserHandle.getUid(
                        /* userId = */ user.identifier,
                        /* appId = */ packageName.hashCode(),
                    ),
                timeMillis = systemClock.currentTimeMillis(),
            )
        )
    }
}
