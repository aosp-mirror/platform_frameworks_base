package com.android.systemui.user.domain.interactor

import android.content.pm.UserInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags.FLAG_REFACTOR_GET_CURRENT_USER
import com.android.systemui.SysuiTestCase
import com.android.systemui.user.data.repository.FakeUserRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class SelectedUserInteractorTest : SysuiTestCase() {

    private lateinit var underTest: SelectedUserInteractor

    private val userRepository = FakeUserRepository()

    @Before
    fun setUp() {
        userRepository.setUserInfos(USER_INFOS)
        underTest = SelectedUserInteractor(userRepository)
    }

    @Test
    fun getSelectedUserIdReturnsId() {
        mSetFlagsRule.enableFlags(FLAG_REFACTOR_GET_CURRENT_USER)
        runBlocking { userRepository.setSelectedUserInfo(USER_INFOS[0]) }

        val actualId = underTest.getSelectedUserId()

        assertThat(actualId).isEqualTo(USER_INFOS[0].id)
    }

    companion object {
        private val USER_INFOS =
            listOf(
                UserInfo(100, "First user", 0),
                UserInfo(101, "Second user", 0),
            )
    }
}
