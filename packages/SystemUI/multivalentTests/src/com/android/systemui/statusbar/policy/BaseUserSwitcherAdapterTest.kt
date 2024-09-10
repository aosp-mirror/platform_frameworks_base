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

package com.android.systemui.statusbar.policy

import android.content.pm.UserInfo
import android.graphics.Bitmap
import android.os.UserHandle
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.qs.user.UserSwitchDialogController
import com.android.systemui.user.data.source.UserRecord
import com.android.systemui.util.mockito.kotlinArgumentCaptor
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import java.lang.ref.WeakReference
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class BaseUserSwitcherAdapterTest : SysuiTestCase() {

    @Mock private lateinit var controller: UserSwitcherController

    private lateinit var underTest: BaseUserSwitcherAdapter

    private lateinit var users: ArrayList<UserRecord>

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        users =
            ArrayList(
                listOf(
                    createUserRecord(
                        id = 0,
                        picture = mock(),
                        isSelected = true,
                        isGuest = false,
                    ),
                    createUserRecord(
                        id = 1,
                        picture = mock(),
                        isSelected = false,
                        isGuest = false,
                    ),
                    createUserRecord(
                        id = UserHandle.USER_NULL,
                        picture = null,
                        isSelected = false,
                        isGuest = true,
                    ),
                )
            )

        whenever(controller.users).thenAnswer { users }
        whenever(controller.isUserSwitcherEnabled).thenReturn(true)

        underTest =
            object : BaseUserSwitcherAdapter(controller) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                    return mock()
                }
            }
    }

    @Test
    fun addsSelfToControllerInConstructor() {
        val captor = kotlinArgumentCaptor<WeakReference<BaseUserSwitcherAdapter>>()
        verify(controller).addAdapter(captor.capture())

        assertThat(captor.value.get()).isEqualTo(underTest)
    }

    @Test
    fun count() {
        assertThat(underTest.count).isEqualTo(users.size)
    }

    @Test
    fun count_ignoresRestrictedUsersWhenDeviceIsLocked() {
        whenever(controller.isKeyguardShowing).thenReturn(true)
        users =
            ArrayList(
                listOf(
                    createUserRecord(
                        id = 0,
                        picture = mock(),
                        isSelected = true,
                        isGuest = false,
                        isRestricted = false,
                    ),
                    createUserRecord(
                        id = 1,
                        picture = mock(),
                        isSelected = false,
                        isGuest = false,
                        isRestricted = true, // this one will be ignored.
                    ),
                    createUserRecord(
                        id = UserHandle.USER_NULL,
                        picture = null,
                        isSelected = false,
                        isGuest = true,
                    ),
                )
            )
        assertThat(underTest.count).isEqualTo(users.size - 1)
    }

    @Test
    fun count_doesNotIgnoreRestrictedUsersWhenDeviceIsNotLocked() {
        whenever(controller.isKeyguardShowing).thenReturn(false)
        users =
            ArrayList(
                listOf(
                    createUserRecord(
                        id = 0,
                        picture = mock(),
                        isSelected = true,
                        isGuest = false,
                        isRestricted = false,
                    ),
                    createUserRecord(
                        id = 1,
                        picture = mock(),
                        isSelected = false,
                        isGuest = false,
                        isRestricted = true,
                    ),
                    createUserRecord(
                        id = UserHandle.USER_NULL,
                        picture = null,
                        isSelected = false,
                        isGuest = true,
                    ),
                )
            )
        assertThat(underTest.count).isEqualTo(users.size)
    }

    @Test
    fun count_onlyShowsCurrentUserWhenMultiUserDisabled() {
        whenever(controller.isUserSwitcherEnabled).thenReturn(false)
        assertThat(underTest.count).isEqualTo(1)
        assertThat(underTest.getItem(0).isCurrent).isTrue()
    }

    @Test
    fun count_doesNotIgnoreAllOtherUsersWhenMultiUserEnabled() {
        whenever(controller.isUserSwitcherEnabled).thenReturn(true)
        assertThat(underTest.count).isEqualTo(users.size)
    }

    @Test
    fun getItem() {
        assertThat((0 until underTest.count).map { position -> underTest.getItem(position) })
            .isEqualTo(users)
    }

    @Test
    fun getItemId() {
        (0 until underTest.count).map { position ->
            assertThat(underTest.getItemId(position)).isEqualTo(position)
        }
    }

    @Test
    fun onUserListItemClicked() {
        val userRecord = users[users.size / 2]
        val dialogShower: UserSwitchDialogController.DialogShower = mock()

        underTest.onUserListItemClicked(userRecord, dialogShower)

        verify(controller).onUserListItemClicked(userRecord, dialogShower)
    }

    @Test
    fun getName_nonGuest_returnsRealName() {
        val userRecord =
            createUserRecord(
                id = 1,
                picture = mock(),
            )

        assertThat(underTest.getName(context, userRecord)).isEqualTo(userRecord.info?.name)
    }

    @Test
    fun getName_guestAndSelected_returnsExitGuestActionName() {
        val expected = "Exit guest"
        context.orCreateTestableResources.addOverride(
            com.android.settingslib.R.string.guest_exit_quick_settings_button,
            expected,
        )

        val userRecord =
            createUserRecord(
                id = 2,
                picture = null,
                isGuest = true,
                isSelected = true,
            )

        assertThat(underTest.getName(context, userRecord)).isEqualTo(expected)
    }

    @Test
    fun getName_guestAndNotSelected_returnsEnterGuestActionName() {
        val expected = "Guest"
        context.orCreateTestableResources.addOverride(
            com.android.internal.R.string.guest_name,
            expected,
        )

        val userRecord =
            createUserRecord(
                id = 2,
                picture = null,
                isGuest = true,
                isSelected = false,
            )

        assertThat(underTest.getName(context, userRecord)).isEqualTo("Guest")
    }

    @Test
    fun refresh() {
        underTest.refresh()

        verify(controller).refreshUsers()
    }

    private fun createUserRecord(
        id: Int,
        picture: Bitmap? = null,
        isSelected: Boolean = false,
        isGuest: Boolean = false,
        isAction: Boolean = false,
        isRestricted: Boolean = false,
    ): UserRecord {
        return UserRecord(
            info =
                if (isAction) {
                    null
                } else {
                    UserInfo(id, "name$id", 0)
                },
            picture = picture,
            isCurrent = isSelected,
            isGuest = isGuest,
            isRestricted = isRestricted,
        )
    }
}
