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
import com.android.systemui.common.shared.model.PackageInstallSession
import kotlinx.coroutines.flow.Flow

interface PackageChangeRepository {
    /**
     * Emits values when packages for the specified user are changed. See supported modifications in
     * [PackageChangeModel]
     *
     * [UserHandle.USER_ALL] may be used to listen to all users.
     */
    fun packageChanged(user: UserHandle): Flow<PackageChangeModel>

    /** Emits a list of all known install sessions associated with the primary user. */
    val packageInstallSessionsForPrimaryUser: Flow<List<PackageInstallSession>>
}
