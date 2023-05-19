package com.android.systemui.biometrics.data.repository

import android.hardware.biometrics.PromptInfo
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.AuthController
import com.android.systemui.biometrics.shared.model.PromptKind
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.mockito.withArgCaptor
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runBlockingTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit

@SmallTest
@RunWith(JUnit4::class)
class PromptRepositoryImplTest : SysuiTestCase() {

    @JvmField @Rule var mockitoRule = MockitoJUnit.rule()

    @Mock private lateinit var authController: AuthController

    private lateinit var repository: PromptRepositoryImpl

    @Before
    fun setup() {
        repository = PromptRepositoryImpl(authController)
    }

    @Test
    fun isShowing() = runBlockingTest {
        whenever(authController.isShowing).thenReturn(true)

        val values = mutableListOf<Boolean>()
        val job = launch { repository.isShowing.toList(values) }
        assertThat(values).containsExactly(true)

        withArgCaptor<AuthController.Callback> {
            verify(authController).addCallback(capture())

            value.onBiometricPromptShown()
            assertThat(values).containsExactly(true, true)

            value.onBiometricPromptDismissed()
            assertThat(values).containsExactly(true, true, false).inOrder()

            job.cancel()
            verify(authController).removeCallback(eq(value))
        }
    }

    @Test
    fun setsAndUnsetsPrompt() = runBlockingTest {
        val kind = PromptKind.Pin
        val uid = 8
        val challenge = 90L
        val promptInfo = PromptInfo()

        repository.setPrompt(promptInfo, uid, challenge, kind)

        assertThat(repository.kind.value).isEqualTo(kind)
        assertThat(repository.userId.value).isEqualTo(uid)
        assertThat(repository.challenge.value).isEqualTo(challenge)
        assertThat(repository.promptInfo.value).isSameInstanceAs(promptInfo)

        repository.unsetPrompt()

        assertThat(repository.promptInfo.value).isNull()
        assertThat(repository.userId.value).isNull()
        assertThat(repository.challenge.value).isNull()
    }
}
