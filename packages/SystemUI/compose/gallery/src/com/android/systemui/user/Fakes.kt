/*
 * Copyright (C) 2022 The Android Open Source Project
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
 *
 */

package com.android.systemui.user

import android.content.Context
import androidx.appcompat.content.res.AppCompatResources
import com.android.systemui.common.shared.model.Text
import com.android.systemui.compose.gallery.R
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.power.data.repository.FakePowerRepository
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.user.data.repository.FakeUserRepository
import com.android.systemui.user.domain.interactor.UserInteractor
import com.android.systemui.user.shared.model.UserActionModel
import com.android.systemui.user.shared.model.UserModel
import com.android.systemui.user.ui.viewmodel.UserSwitcherViewModel
import com.android.systemui.util.mockito.mock

object Fakes {
    private val USER_TINT_COLORS =
        arrayOf(
            0x000000,
            0x0000ff,
            0x00ff00,
            0x00ffff,
            0xff0000,
            0xff00ff,
            0xffff00,
            0xffffff,
        )

    fun fakeUserSwitcherViewModel(
        context: Context,
        userCount: Int,
    ): UserSwitcherViewModel {
        return UserSwitcherViewModel.Factory(
                userInteractor =
                    UserInteractor(
                        repository =
                            FakeUserRepository().apply {
                                setUsers(
                                    (0 until userCount).map { index ->
                                        UserModel(
                                            id = index,
                                            name = Text.Loaded("user_$index"),
                                            image =
                                                checkNotNull(
                                                    AppCompatResources.getDrawable(
                                                        context,
                                                        R.drawable.ic_avatar_guest_user
                                                    )
                                                ),
                                            isSelected = index == 0,
                                            isSelectable = true,
                                        )
                                    }
                                )
                                setActions(
                                    UserActionModel.values().mapNotNull {
                                        if (it == UserActionModel.NAVIGATE_TO_USER_MANAGEMENT) {
                                            null
                                        } else {
                                            it
                                        }
                                    }
                                )
                            },
                        controller = mock(),
                        activityStarter = mock(),
                        keyguardInteractor =
                            KeyguardInteractor(
                                repository =
                                    FakeKeyguardRepository().apply { setKeyguardShowing(false) },
                            ),
                    ),
                powerInteractor =
                    PowerInteractor(
                        repository = FakePowerRepository(),
                    )
            )
            .create(UserSwitcherViewModel::class.java)
    }
}
