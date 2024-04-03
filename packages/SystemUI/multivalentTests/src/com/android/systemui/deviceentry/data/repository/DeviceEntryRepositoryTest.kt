package com.android.systemui.deviceentry.data.repository

import android.content.pm.UserInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.widget.LockPatternUtils
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.testKosmos
import com.android.systemui.user.data.repository.FakeUserRepository
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.mockito.withArgCaptor
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
@android.platform.test.annotations.EnabledOnRavenwood
class DeviceEntryRepositoryTest : SysuiTestCase() {

    @Mock private lateinit var lockPatternUtils: LockPatternUtils
    @Mock private lateinit var keyguardBypassController: KeyguardBypassController

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val userRepository = FakeUserRepository()

    private lateinit var underTest: DeviceEntryRepository

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        userRepository.setUserInfos(USER_INFOS)
        runBlocking { userRepository.setSelectedUserInfo(USER_INFOS[0]) }

        underTest =
            DeviceEntryRepositoryImpl(
                applicationScope = testScope.backgroundScope,
                backgroundDispatcher = kosmos.testDispatcher,
                userRepository = userRepository,
                lockPatternUtils = lockPatternUtils,
                keyguardBypassController = keyguardBypassController,
            )
        testScope.runCurrent()
    }
    @Test
    fun isLockscreenEnabled() =
        testScope.runTest {
            whenever(lockPatternUtils.isLockScreenDisabled(USER_INFOS[0].id)).thenReturn(false)
            whenever(lockPatternUtils.isLockScreenDisabled(USER_INFOS[1].id)).thenReturn(true)

            userRepository.setSelectedUserInfo(USER_INFOS[0])
            assertThat(underTest.isLockscreenEnabled()).isTrue()

            userRepository.setSelectedUserInfo(USER_INFOS[1])
            assertThat(underTest.isLockscreenEnabled()).isFalse()
        }

    @Test
    fun isBypassEnabled_disabledInController() =
        testScope.runTest {
            whenever(keyguardBypassController.isBypassEnabled).thenAnswer { false }
            whenever(keyguardBypassController.bypassEnabled).thenAnswer { false }
            withArgCaptor {
                    verify(keyguardBypassController).registerOnBypassStateChangedListener(capture())
                }
                .onBypassStateChanged(false)
            runCurrent()
            assertThat(underTest.isBypassEnabled.value).isFalse()
        }

    @Test
    fun isBypassEnabled_enabledInController() =
        testScope.runTest {
            whenever(keyguardBypassController.isBypassEnabled).thenAnswer { true }
            whenever(keyguardBypassController.bypassEnabled).thenAnswer { true }
            withArgCaptor {
                    verify(keyguardBypassController).registerOnBypassStateChangedListener(capture())
                }
                .onBypassStateChanged(true)
            runCurrent()
            assertThat(underTest.isBypassEnabled.value).isTrue()
        }

    companion object {
        private val USER_INFOS =
            listOf(
                UserInfo(
                    /* id= */ 100,
                    /* name= */ "First user",
                    /* flags= */ 0,
                ),
                UserInfo(
                    /* id= */ 101,
                    /* name= */ "Second user",
                    /* flags= */ 0,
                ),
            )
    }
}
