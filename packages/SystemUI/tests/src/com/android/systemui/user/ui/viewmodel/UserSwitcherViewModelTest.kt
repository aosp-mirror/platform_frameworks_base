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

package com.android.systemui.user.ui.viewmodel

import android.graphics.drawable.Drawable
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.Text
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.power.data.repository.FakePowerRepository
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.statusbar.policy.UserSwitcherController
import com.android.systemui.user.data.repository.FakeUserRepository
import com.android.systemui.user.domain.interactor.UserInteractor
import com.android.systemui.user.legacyhelper.ui.LegacyUserUiHelper
import com.android.systemui.user.shared.model.UserActionModel
import com.android.systemui.user.shared.model.UserModel
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(JUnit4::class)
class UserSwitcherViewModelTest : SysuiTestCase() {

    @Mock private lateinit var controller: UserSwitcherController
    @Mock private lateinit var activityStarter: ActivityStarter

    private lateinit var underTest: UserSwitcherViewModel

    private lateinit var userRepository: FakeUserRepository
    private lateinit var keyguardRepository: FakeKeyguardRepository
    private lateinit var powerRepository: FakePowerRepository

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        userRepository = FakeUserRepository()
        keyguardRepository = FakeKeyguardRepository()
        powerRepository = FakePowerRepository()
        underTest =
            UserSwitcherViewModel.Factory(
                    userInteractor =
                        UserInteractor(
                            repository = userRepository,
                            controller = controller,
                            activityStarter = activityStarter,
                            keyguardInteractor =
                                KeyguardInteractor(
                                    repository = keyguardRepository,
                                )
                        ),
                    powerInteractor =
                        PowerInteractor(
                            repository = powerRepository,
                        ),
                )
                .create(UserSwitcherViewModel::class.java)
    }

    @Test
    fun users() =
        runBlocking(IMMEDIATE) {
            userRepository.setUsers(
                listOf(
                    UserModel(
                        id = 0,
                        name = Text.Loaded("zero"),
                        image = USER_IMAGE,
                        isSelected = true,
                        isSelectable = true,
                    ),
                    UserModel(
                        id = 1,
                        name = Text.Loaded("one"),
                        image = USER_IMAGE,
                        isSelected = false,
                        isSelectable = true,
                    ),
                    UserModel(
                        id = 2,
                        name = Text.Loaded("two"),
                        image = USER_IMAGE,
                        isSelected = false,
                        isSelectable = false,
                    ),
                )
            )

            var userViewModels: List<UserViewModel>? = null
            val job = underTest.users.onEach { userViewModels = it }.launchIn(this)

            assertThat(userViewModels).hasSize(3)
            assertUserViewModel(
                viewModel = userViewModels?.get(0),
                viewKey = 0,
                name = "zero",
                isSelectionMarkerVisible = true,
                alpha = LegacyUserUiHelper.USER_SWITCHER_USER_VIEW_SELECTABLE_ALPHA,
                isClickable = true,
            )
            assertUserViewModel(
                viewModel = userViewModels?.get(1),
                viewKey = 1,
                name = "one",
                isSelectionMarkerVisible = false,
                alpha = LegacyUserUiHelper.USER_SWITCHER_USER_VIEW_SELECTABLE_ALPHA,
                isClickable = true,
            )
            assertUserViewModel(
                viewModel = userViewModels?.get(2),
                viewKey = 2,
                name = "two",
                isSelectionMarkerVisible = false,
                alpha = LegacyUserUiHelper.USER_SWITCHER_USER_VIEW_NOT_SELECTABLE_ALPHA,
                isClickable = false,
            )
            job.cancel()
        }

    @Test
    fun `maximumUserColumns - few users`() =
        runBlocking(IMMEDIATE) {
            setUsers(count = 2)
            var value: Int? = null
            val job = underTest.maximumUserColumns.onEach { value = it }.launchIn(this)

            assertThat(value).isEqualTo(4)
            job.cancel()
        }

    @Test
    fun `maximumUserColumns - many users`() =
        runBlocking(IMMEDIATE) {
            setUsers(count = 5)
            var value: Int? = null
            val job = underTest.maximumUserColumns.onEach { value = it }.launchIn(this)

            assertThat(value).isEqualTo(3)
            job.cancel()
        }

    @Test
    fun `isOpenMenuButtonVisible - has actions - true`() =
        runBlocking(IMMEDIATE) {
            userRepository.setActions(UserActionModel.values().toList())

            var isVisible: Boolean? = null
            val job = underTest.isOpenMenuButtonVisible.onEach { isVisible = it }.launchIn(this)

            assertThat(isVisible).isTrue()
            job.cancel()
        }

    @Test
    fun `isOpenMenuButtonVisible - no actions - false`() =
        runBlocking(IMMEDIATE) {
            userRepository.setActions(emptyList())

            var isVisible: Boolean? = null
            val job = underTest.isOpenMenuButtonVisible.onEach { isVisible = it }.launchIn(this)

            assertThat(isVisible).isFalse()
            job.cancel()
        }

    @Test
    fun menu() =
        runBlocking(IMMEDIATE) {
            userRepository.setActions(UserActionModel.values().toList())
            var isMenuVisible: Boolean? = null
            val job = underTest.isMenuVisible.onEach { isMenuVisible = it }.launchIn(this)
            assertThat(isMenuVisible).isFalse()

            underTest.onOpenMenuButtonClicked()
            assertThat(isMenuVisible).isTrue()

            underTest.onMenuClosed()
            assertThat(isMenuVisible).isFalse()

            job.cancel()
        }

    @Test
    fun `isFinishRequested - finishes when user is switched`() =
        runBlocking(IMMEDIATE) {
            setUsers(count = 2)
            var isFinishRequested: Boolean? = null
            val job = underTest.isFinishRequested.onEach { isFinishRequested = it }.launchIn(this)
            assertThat(isFinishRequested).isFalse()

            userRepository.setSelectedUser(1)
            yield()
            assertThat(isFinishRequested).isTrue()

            job.cancel()
        }

    @Test
    fun `isFinishRequested - finishes when the screen turns off`() =
        runBlocking(IMMEDIATE) {
            setUsers(count = 2)
            powerRepository.setInteractive(true)
            var isFinishRequested: Boolean? = null
            val job = underTest.isFinishRequested.onEach { isFinishRequested = it }.launchIn(this)
            assertThat(isFinishRequested).isFalse()

            powerRepository.setInteractive(false)
            yield()
            assertThat(isFinishRequested).isTrue()

            job.cancel()
        }

    @Test
    fun `isFinishRequested - finishes when cancel button is clicked`() =
        runBlocking(IMMEDIATE) {
            setUsers(count = 2)
            powerRepository.setInteractive(true)
            var isFinishRequested: Boolean? = null
            val job = underTest.isFinishRequested.onEach { isFinishRequested = it }.launchIn(this)
            assertThat(isFinishRequested).isFalse()

            underTest.onCancelButtonClicked()
            yield()
            assertThat(isFinishRequested).isTrue()

            underTest.onFinished()
            yield()
            assertThat(isFinishRequested).isFalse()

            job.cancel()
        }

    private fun setUsers(count: Int) {
        userRepository.setUsers(
            (0 until count).map { index ->
                UserModel(
                    id = index,
                    name = Text.Loaded("$index"),
                    image = USER_IMAGE,
                    isSelected = index == 0,
                    isSelectable = true,
                )
            }
        )
    }

    private fun assertUserViewModel(
        viewModel: UserViewModel?,
        viewKey: Int,
        name: String,
        isSelectionMarkerVisible: Boolean,
        alpha: Float,
        isClickable: Boolean,
    ) {
        checkNotNull(viewModel)
        assertThat(viewModel.viewKey).isEqualTo(viewKey)
        assertThat(viewModel.name).isEqualTo(Text.Loaded(name))
        assertThat(viewModel.isSelectionMarkerVisible).isEqualTo(isSelectionMarkerVisible)
        assertThat(viewModel.alpha).isEqualTo(alpha)
        assertThat(viewModel.onClicked != null).isEqualTo(isClickable)
    }

    companion object {
        private val IMMEDIATE = Dispatchers.Main.immediate
        private val USER_IMAGE = mock<Drawable>()
    }
}
