package com.android.systemui.keyboard.stickykeys.data.repository

import android.content.pm.UserInfo
import android.hardware.input.InputManager
import android.provider.Settings.Secure.ACCESSIBILITY_STICKY_KEYS
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.keyboard.stickykeys.StickyKeysLogger
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.user.data.repository.fakeUserRepository
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.settings.FakeSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class StickyKeysRepositoryImplTest : SysuiTestCase() {

    private val dispatcher = StandardTestDispatcher()
    private val testScope = TestScope(dispatcher)
    private val secureSettings = FakeSettings()
    private val userRepository = Kosmos().fakeUserRepository
    private lateinit var stickyKeysRepository: StickyKeysRepositoryImpl

    @Before
    fun setup() {
        stickyKeysRepository = StickyKeysRepositoryImpl(
            mock<InputManager>(),
            dispatcher,
            secureSettings,
            userRepository,
            mock<StickyKeysLogger>()
        )
        userRepository.setUserInfos(USER_INFOS)
        setStickyKeySettingForUser(enabled = true, userInfo = SETTING_ENABLED_USER)
        setStickyKeySettingForUser(enabled = false, userInfo = SETTING_DISABLED_USER)
    }

    @Test
    fun settingEnabledEmitsValueForCurrentUser() {
        testScope.runTest {
            userRepository.setSelectedUserInfo(SETTING_ENABLED_USER)

            val enabled by collectLastValue(stickyKeysRepository.settingEnabled)

            assertThat(enabled).isTrue()
        }
    }

    @Test
    fun settingEnabledEmitsNewValueWhenSettingChanges() {
        testScope.runTest {
            userRepository.setSelectedUserInfo(SETTING_ENABLED_USER)
            val enabled by collectValues(stickyKeysRepository.settingEnabled)
            runCurrent()

            setStickyKeySettingForUser(enabled = false, userInfo = SETTING_ENABLED_USER)

            assertThat(enabled).containsExactly(true, false).inOrder()
        }
    }

    @Test
    fun settingEnabledEmitsValueForNewUserWhenUserChanges() {
        testScope.runTest {
            userRepository.setSelectedUserInfo(SETTING_ENABLED_USER)
            val enabled by collectLastValue(stickyKeysRepository.settingEnabled)
            runCurrent()

            userRepository.setSelectedUserInfo(SETTING_DISABLED_USER)

            assertThat(enabled).isFalse()
        }
    }

    private fun setStickyKeySettingForUser(enabled: Boolean, userInfo: UserInfo) {
        val newValue = if (enabled) "1" else "0"
        secureSettings.putStringForUser(ACCESSIBILITY_STICKY_KEYS, newValue, userInfo.id)
    }

    private companion object {
        val SETTING_ENABLED_USER = UserInfo(/* id= */ 0, "user1", /* flags= */ 0)
        val SETTING_DISABLED_USER = UserInfo(/* id= */ 1, "user2", /* flags= */ 0)
        val USER_INFOS = listOf(SETTING_ENABLED_USER, SETTING_DISABLED_USER)
    }
}