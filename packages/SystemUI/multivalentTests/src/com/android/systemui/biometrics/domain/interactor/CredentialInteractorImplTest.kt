package com.android.systemui.biometrics.domain.interactor

import android.app.admin.DevicePolicyManager
import android.app.admin.DevicePolicyResourcesManager
import android.content.pm.UserInfo
import android.os.UserManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.widget.LockPatternUtils
import com.android.internal.widget.LockscreenCredential
import com.android.internal.widget.VerifyCredentialResponse
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.domain.model.BiometricOperationInfo
import com.android.systemui.biometrics.domain.model.BiometricPromptRequest
import com.android.systemui.biometrics.promptInfo
import com.android.systemui.biometrics.shared.model.BiometricUserInfo
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit

private const val USER_ID = 22
private const val OPERATION_ID = 100L
private const val MAX_ATTEMPTS = 5

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class CredentialInteractorImplTest : SysuiTestCase() {

    @JvmField @Rule var mockitoRule = MockitoJUnit.rule()

    @Mock private lateinit var lockPatternUtils: LockPatternUtils
    @Mock private lateinit var userManager: UserManager
    @Mock private lateinit var devicePolicyManager: DevicePolicyManager
    @Mock private lateinit var devicePolicyResourcesManager: DevicePolicyResourcesManager

    private val systemClock = FakeSystemClock()

    private lateinit var interactor: CredentialInteractorImpl

    @Before
    fun setup() {
        whenever(devicePolicyManager.resources).thenReturn(devicePolicyResourcesManager)
        whenever(lockPatternUtils.getMaximumFailedPasswordsForWipe(anyInt()))
            .thenReturn(MAX_ATTEMPTS)
        whenever(userManager.getUserInfo(eq(USER_ID))).thenReturn(UserInfo(USER_ID, "", 0))
        whenever(devicePolicyManager.getProfileWithMinimumFailedPasswordsForWipe(eq(USER_ID)))
            .thenReturn(USER_ID)

        interactor =
            CredentialInteractorImpl(
                mContext,
                lockPatternUtils,
                userManager,
                devicePolicyManager,
                systemClock
            )
    }

    @Test
    fun testStealthMode() {
        for (value in listOf(true, false, false, true)) {
            whenever(lockPatternUtils.isVisiblePatternEnabled(eq(USER_ID))).thenReturn(value)

            assertThat(interactor.isStealthModeActive(USER_ID)).isEqualTo(!value)
        }
    }

    @Test
    fun testCredentialOwner() {
        for (value in listOf(12, 8, 4)) {
            whenever(userManager.getCredentialOwnerProfile(eq(USER_ID))).thenReturn(value)

            assertThat(interactor.getCredentialOwnerOrSelfId(USER_ID)).isEqualTo(value)
        }
    }

    @Test
    fun testParentProfile() {
        for (value in listOf(12, 8, 4)) {
            whenever(userManager.getProfileParent(eq(USER_ID)))
                .thenReturn(UserInfo(value, "test", 0))

            assertThat(interactor.getParentProfileIdOrSelfId(USER_ID)).isEqualTo(value)
        }
    }

    @Test
    fun useCredentialOwnerWhenParentProfileIsNull() {
        val value = 1

        whenever(userManager.getProfileParent(eq(USER_ID))).thenReturn(null)
        whenever(userManager.getCredentialOwnerProfile(eq(USER_ID))).thenReturn(value)

        assertThat(interactor.getParentProfileIdOrSelfId(USER_ID)).isEqualTo(value)
    }

    @Test fun pinCredentialWhenGood() = pinCredential(goodCredential())

    @Test fun pinCredentialWhenBad() = pinCredential(badCredential())

    @Test fun pinCredentialWhenBadAndThrottled() = pinCredential(badCredential(timeout = 5_000))

    private fun pinCredential(result: VerifyCredentialResponse) = runTest {
        val usedAttempts = 1
        whenever(lockPatternUtils.getCurrentFailedPasswordAttempts(eq(USER_ID)))
            .thenReturn(usedAttempts)
        whenever(lockPatternUtils.verifyCredential(any(), eq(USER_ID), anyInt())).thenReturn(result)
        whenever(lockPatternUtils.verifyGatekeeperPasswordHandle(anyLong(), anyLong(), eq(USER_ID)))
            .thenReturn(result)
        whenever(lockPatternUtils.setLockoutAttemptDeadline(anyInt(), anyInt())).thenAnswer {
            systemClock.elapsedRealtime() + (it.arguments[1] as Int)
        }

        // wrap in an async block so the test can advance the clock if throttling credential
        // checks prevents the method from returning
        val statusList = mutableListOf<CredentialStatus>()
        interactor
            .verifyCredential(pinRequest(), LockscreenCredential.createPin("1234"))
            .toList(statusList)

        val last = statusList.removeLastOrNull()
        if (result.isMatched) {
            assertThat(statusList).isEmpty()
            val successfulResult = last as? CredentialStatus.Success.Verified
            assertThat(successfulResult).isNotNull()
            assertThat(successfulResult!!.hat).isEqualTo(result.gatekeeperHAT)

            verify(lockPatternUtils).userPresent(eq(USER_ID))
            verify(lockPatternUtils)
                .removeGatekeeperPasswordHandle(eq(result.gatekeeperPasswordHandle))
        } else {
            val failedResult = last as? CredentialStatus.Fail.Error
            assertThat(failedResult).isNotNull()
            assertThat(failedResult!!.remainingAttempts)
                .isEqualTo(if (result.timeout > 0) null else MAX_ATTEMPTS - usedAttempts - 1)
            assertThat(failedResult.urgentMessage).isNull()

            if (result.timeout > 0) { // failed and throttled
                // messages are in the throttled errors, so the final Error.error is empty
                assertThat(failedResult.error).isEmpty()
                assertThat(statusList).isNotEmpty()
                assertThat(statusList.filterIsInstance(CredentialStatus.Fail.Throttled::class.java))
                    .hasSize(statusList.size)

                verify(lockPatternUtils).setLockoutAttemptDeadline(eq(USER_ID), eq(result.timeout))
            } else { // failed
                assertThat(failedResult.error)
                    .matches(Regex("(.*)try again(.*)", RegexOption.IGNORE_CASE).toPattern())
                assertThat(statusList).isEmpty()

                verify(lockPatternUtils).reportFailedPasswordAttempt(eq(USER_ID))
            }
        }
    }

    @Test
    fun pinCredentialWhenBadAndFinalAttempt() = runTest {
        whenever(lockPatternUtils.verifyCredential(any(), eq(USER_ID), anyInt()))
            .thenReturn(badCredential())
        whenever(lockPatternUtils.getCurrentFailedPasswordAttempts(eq(USER_ID)))
            .thenReturn(MAX_ATTEMPTS - 2)

        val statusList = mutableListOf<CredentialStatus>()
        interactor
            .verifyCredential(pinRequest(), LockscreenCredential.createPin("1234"))
            .toList(statusList)

        val result = statusList.removeLastOrNull() as? CredentialStatus.Fail.Error
        assertThat(result).isNotNull()
        assertThat(result!!.remainingAttempts).isEqualTo(1)
        assertThat(result.urgentMessage).isNotEmpty()
        assertThat(statusList).isEmpty()

        verify(lockPatternUtils).reportFailedPasswordAttempt(eq(USER_ID))
    }

    @Test
    fun pinCredentialWhenBadAndNoMoreAttempts() = runTest {
        whenever(lockPatternUtils.verifyCredential(any(), eq(USER_ID), anyInt()))
            .thenReturn(badCredential())
        whenever(lockPatternUtils.getCurrentFailedPasswordAttempts(eq(USER_ID)))
            .thenReturn(MAX_ATTEMPTS - 1)
        whenever(devicePolicyResourcesManager.getString(any(), any())).thenReturn("wipe")

        val statusList = mutableListOf<CredentialStatus>()
        interactor
            .verifyCredential(pinRequest(), LockscreenCredential.createPin("1234"))
            .toList(statusList)

        val result = statusList.removeLastOrNull() as? CredentialStatus.Fail.Error
        assertThat(result).isNotNull()
        assertThat(result!!.remainingAttempts).isEqualTo(0)
        assertThat(result.urgentMessage).isNotEmpty()
        assertThat(statusList).isEmpty()

        verify(lockPatternUtils).reportFailedPasswordAttempt(eq(USER_ID))
    }
}

private fun pinRequest(): BiometricPromptRequest.Credential.Pin =
    BiometricPromptRequest.Credential.Pin(
        promptInfo(),
        BiometricUserInfo(USER_ID),
        BiometricOperationInfo(OPERATION_ID)
    )

private fun goodCredential(
    passwordHandle: Long = 90,
    hat: ByteArray = ByteArray(69),
): VerifyCredentialResponse =
    VerifyCredentialResponse.Builder()
        .setGatekeeperPasswordHandle(passwordHandle)
        .setGatekeeperHAT(hat)
        .build()

private fun badCredential(timeout: Int = 0): VerifyCredentialResponse =
    if (timeout > 0) {
        VerifyCredentialResponse.fromTimeout(timeout)
    } else {
        VerifyCredentialResponse.fromError()
    }
