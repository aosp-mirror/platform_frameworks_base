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

package com.android.systemui.common.domain.interactor

import android.os.UserHandle
import com.android.systemui.common.data.repository.PackageChangeRepository
import com.android.systemui.common.shared.model.PackageChangeModel
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest

/**
 * Allows listening to package updates. This is recommended over registering broadcasts directly as
 * it avoids the delay imposed by broadcasts, and provides more structured updates.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class PackageChangeInteractor
@Inject
constructor(
    private val packageChangeRepository: PackageChangeRepository,
    private val userInteractor: SelectedUserInteractor,
) {
    /**
     * Emits values when packages for the specified user are changed. See supported modifications in
     * [PackageChangeModel]
     *
     * @param user The user to listen to. [UserHandle.USER_ALL] may be used to listen to all users.
     *   [UserHandle.USER_CURRENT] can be used to listen to the currently active user, and
     *   automatically handles user switching.
     * @param packageName An optional package name to filter updates by. If not specified, will
     *   receive updates for all packages.
     */
    fun packageChanged(user: UserHandle, packageName: String? = null): Flow<PackageChangeModel> {
        if (user == UserHandle.CURRENT) {
            return userInteractor.selectedUser.flatMapLatest { userId ->
                packageChangedInternal(UserHandle.of(userId), packageName)
            }
        }
        return packageChangedInternal(user, packageName)
    }

    private fun packageChangedInternal(
        user: UserHandle,
        packageName: String?,
    ): Flow<PackageChangeModel> =
        packageChangeRepository.packageChanged(user).filter { model ->
            (model.packageName == (packageName ?: model.packageName))
        }
}
