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

package com.android.systemui.development.domain.interactor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.Resources
import android.os.Build
import android.os.UserHandle
import com.android.internal.R as InternalR
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.development.data.repository.DevelopmentSettingRepository
import com.android.systemui.development.shared.model.BuildNumber
import com.android.systemui.res.R as SystemUIR
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.user.utils.UserScopedService
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class BuildNumberInteractor
@Inject
constructor(
    repository: DevelopmentSettingRepository,
    @Main resources: Resources,
    private val userRepository: UserRepository,
    private val clipboardManagerProvider: UserScopedService<ClipboardManager>,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
) {

    /**
     * Build number, or `null` if Development Settings is not enabled for the current user.
     *
     * @see DevelopmentSettingRepository.isDevelopmentSettingEnabled
     */
    val buildNumber: Flow<BuildNumber?> =
        userRepository.selectedUserInfo
            .flatMapConcat { userInfo -> repository.isDevelopmentSettingEnabled(userInfo) }
            .map { enabled -> buildText.takeIf { enabled } }

    private val buildText =
        BuildNumber(
            resources.getString(
                InternalR.string.bugreport_status,
                Build.VERSION.RELEASE_OR_CODENAME,
                Build.ID,
            )
        )

    private val clipLabel = resources.getString(SystemUIR.string.build_number_clip_data_label)

    private val currentUserHandle: UserHandle
        get() = userRepository.getSelectedUserInfo().userHandle

    /**
     * Copy to the clipboard the build number for the current user.
     *
     * This can be performed regardless of the current user having Development Settings enabled
     */
    suspend fun copyBuildNumber() {
        withContext(backgroundDispatcher) {
            clipboardManagerProvider
                .forUser(currentUserHandle)
                .setPrimaryClip(ClipData.newPlainText(clipLabel, buildText.value))
        }
    }
}
