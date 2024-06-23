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
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter

@SysUISingleton
class PackageChangeRepositoryImpl
@Inject
constructor(
    private val monitorFactory: PackageUpdateMonitor.Factory,
) : PackageChangeRepository {
    /**
     * A [PackageUpdateMonitor] which monitors package updates for all users. The per-user filtering
     * is done by [packageChanged].
     */
    private val monitor by lazy { monitorFactory.create(UserHandle.ALL) }

    override fun packageChanged(user: UserHandle): Flow<PackageChangeModel> =
        monitor.packageChanged.filter { user == UserHandle.ALL || user == it.user }
}
