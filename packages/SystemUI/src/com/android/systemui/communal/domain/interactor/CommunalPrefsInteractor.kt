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

package com.android.systemui.communal.domain.interactor

import android.content.pm.UserInfo
import com.android.systemui.communal.data.repository.CommunalPrefsRepository
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.log.dagger.CommunalTableLog
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.logDiffsForTable
import com.android.systemui.settings.UserTracker
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class CommunalPrefsInteractor
@Inject
constructor(
    @Background private val bgScope: CoroutineScope,
    private val repository: CommunalPrefsRepository,
    userInteractor: SelectedUserInteractor,
    private val userTracker: UserTracker,
    @CommunalTableLog tableLogBuffer: TableLogBuffer,
) {

    val isCtaDismissed: Flow<Boolean> =
        userInteractor.selectedUserInfo
            .flatMapLatest { user -> repository.isCtaDismissed(user) }
            .logDiffsForTable(
                tableLogBuffer = tableLogBuffer,
                columnPrefix = "",
                columnName = "isCtaDismissed",
                initialValue = false,
            )
            .stateIn(
                scope = bgScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = false,
            )

    suspend fun setCtaDismissed(user: UserInfo = userTracker.userInfo) =
        repository.setCtaDismissed(user)

    private companion object {
        const val TAG = "CommunalPrefsInteractor"
    }
}
