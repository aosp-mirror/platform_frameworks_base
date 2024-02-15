package com.android.systemui.biometrics.domain.interactor

import android.hardware.biometrics.PromptInfo
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.Utils
import com.android.systemui.biometrics.data.repository.FakePromptRepository
import com.android.systemui.biometrics.domain.model.BiometricOperationInfo
import com.android.systemui.biometrics.domain.model.BiometricPromptRequest
import com.android.systemui.biometrics.promptInfo
import com.android.systemui.biometrics.shared.model.BiometricUserInfo
import com.android.systemui.coroutines.collectLastValue
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.junit.MockitoJUnit

private const val USER_ID = 22
private const val OPERATION_ID = 100L
private const val OP_PACKAGE_NAME = "biometric.testapp"

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class PromptCredentialInteractorTest : SysuiTestCase() {

    @JvmField @Rule var mockitoRule = MockitoJUnit.rule()

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val biometricPromptRepository = FakePromptRepository()
    private val credentialInteractor = FakeCredentialInteractor()

    private lateinit var interactor: PromptCredentialInteractor

    @Before
    fun setup() {
        interactor =
            PromptCredentialInteractor(
                testDispatcher,
                biometricPromptRepository,
                credentialInteractor,
            )
    }

    @Test
    fun testIsShowing() =
        testScope.runTest {
            val showing by collectLastValue(interactor.isShowing)

            biometricPromptRepository.setIsShowing(false)
            assertThat(showing).isFalse()

            biometricPromptRepository.setIsShowing(true)
            assertThat(showing).isTrue()
        }

    @Test
    fun testShowError() =
        testScope.runTest {
            val error by collectLastValue(interactor.verificationError)

            for (msg in listOf("once", "again")) {
                interactor.setVerificationError(error(msg))
                assertThat(error).isEqualTo(error(msg))
            }

            interactor.resetVerificationError()
            assertThat(error).isNull()
        }

    @Test
    fun nullWhenNoPromptInfo() =
        testScope.runTest {
            val prompt by collectLastValue(interactor.prompt)

            assertThat(prompt).isNull()
        }

    @Test fun usePinCredentialForPrompt() = useCredentialForPrompt(Utils.CREDENTIAL_PIN)

    @Test fun usePasswordCredentialForPrompt() = useCredentialForPrompt(Utils.CREDENTIAL_PASSWORD)

    @Test fun usePatternCredentialForPrompt() = useCredentialForPrompt(Utils.CREDENTIAL_PATTERN)

    private fun useCredentialForPrompt(kind: Int) =
        testScope.runTest {
            val isStealth = false
            credentialInteractor.stealthMode = isStealth

            val prompt by collectLastValue(interactor.prompt)

            val title = "what a prompt"
            val subtitle = "s"
            val description = "something to see"

            interactor.useCredentialsForAuthentication(
                PromptInfo().also {
                    it.title = title
                    it.description = description
                    it.subtitle = subtitle
                },
                kind = kind,
                userId = USER_ID,
                challenge = OPERATION_ID,
                opPackageName = OP_PACKAGE_NAME
            )

            assertThat(prompt?.title).isEqualTo(title)
            assertThat(prompt?.subtitle).isEqualTo(subtitle)
            assertThat(prompt?.description).isEqualTo(description)
            assertThat(prompt?.userInfo).isEqualTo(BiometricUserInfo(USER_ID))
            assertThat(prompt?.operationInfo).isEqualTo(BiometricOperationInfo(OPERATION_ID))
            assertThat(prompt)
                .isInstanceOf(
                    when (kind) {
                        Utils.CREDENTIAL_PIN -> BiometricPromptRequest.Credential.Pin::class.java
                        Utils.CREDENTIAL_PASSWORD ->
                            BiometricPromptRequest.Credential.Password::class.java
                        Utils.CREDENTIAL_PATTERN ->
                            BiometricPromptRequest.Credential.Pattern::class.java
                        else -> throw Exception("wrong kind")
                    }
                )
            val pattern = prompt as? BiometricPromptRequest.Credential.Pattern
            if (pattern != null) {
                assertThat(pattern.stealthMode).isEqualTo(isStealth)
            }

            interactor.resetPrompt()

            assertThat(prompt).isNull()
        }

    @Test
    fun checkCredential() =
        testScope.runTest {
            val hat = ByteArray(4)
            credentialInteractor.verifyCredentialResponse = { _ -> flowOf(verified(hat)) }

            val errors = mutableListOf<CredentialStatus.Fail?>()
            val job = launch { interactor.verificationError.toList(errors) }
            runCurrent()

            val checked =
                interactor.checkCredential(pinRequest(), text = "1234")
                    as? CredentialStatus.Success.Verified

            assertThat(checked).isNotNull()
            assertThat(checked!!.hat).isSameInstanceAs(hat)

            runCurrent()
            assertThat(errors.map { it?.error }).containsExactly(null)

            job.cancel()
        }

    @Test
    fun checkCredentialWhenBad() =
        testScope.runTest {
            val errorMessage = "bad"
            val remainingAttempts = 12
            credentialInteractor.verifyCredentialResponse = { _ ->
                flowOf(error(errorMessage, remainingAttempts))
            }

            val errors = mutableListOf<CredentialStatus.Fail?>()
            val job = launch { interactor.verificationError.toList(errors) }
            runCurrent()

            val checked =
                interactor.checkCredential(pinRequest(), text = "1234")
                    as? CredentialStatus.Fail.Error

            assertThat(checked).isNotNull()
            assertThat(checked!!.remainingAttempts).isEqualTo(remainingAttempts)
            assertThat(checked.urgentMessage).isNull()

            runCurrent()
            assertThat(errors.map { it?.error }).containsExactly(null, errorMessage).inOrder()

            job.cancel()
        }

    @Test
    fun checkCredentialWhenBadAndUrgentMessage() =
        testScope.runTest {
            val error = "not so bad"
            val urgentMessage = "really bad"
            credentialInteractor.verifyCredentialResponse = { _ ->
                flowOf(error(error, 10, urgentMessage))
            }

            val errors = mutableListOf<CredentialStatus.Fail?>()
            val job = launch { interactor.verificationError.toList(errors) }
            runCurrent()

            val checked =
                interactor.checkCredential(pinRequest(), text = "1234")
                    as? CredentialStatus.Fail.Error

            assertThat(checked).isNotNull()
            assertThat(checked!!.urgentMessage).isEqualTo(urgentMessage)

            runCurrent()
            assertThat(errors.map { it?.error }).containsExactly(null, error).inOrder()
            assertThat(errors.last() as? CredentialStatus.Fail.Error)
                .isEqualTo(error(error, 10, urgentMessage))

            job.cancel()
        }

    @Test
    fun checkCredentialWhenBadAndThrottled() =
        testScope.runTest {
            val remainingAttempts = 3
            val error = ":("
            val urgentMessage = ":D"
            credentialInteractor.verifyCredentialResponse = { _ ->
                flow {
                    for (i in 1..3) {
                        emit(throttled("$i"))
                        delay(100)
                    }
                    emit(error(error, remainingAttempts, urgentMessage))
                }
            }
            val errors = mutableListOf<CredentialStatus.Fail?>()
            val job = launch { interactor.verificationError.toList(errors) }
            runCurrent()

            val checked =
                interactor.checkCredential(pinRequest(), text = "1234")
                    as? CredentialStatus.Fail.Error

            assertThat(checked).isNotNull()
            assertThat(checked!!.remainingAttempts).isEqualTo(remainingAttempts)

            runCurrent()
            assertThat(checked.urgentMessage).isEqualTo(urgentMessage)
            assertThat(errors.map { it?.error })
                .containsExactly(null, "1", "2", "3", error)
                .inOrder()

            job.cancel()
        }
}

private fun pinRequest(): BiometricPromptRequest.Credential.Pin =
    BiometricPromptRequest.Credential.Pin(
        promptInfo(),
        BiometricUserInfo(USER_ID),
        BiometricOperationInfo(OPERATION_ID)
    )

private fun verified(hat: ByteArray) = CredentialStatus.Success.Verified(hat)

private fun throttled(error: String) = CredentialStatus.Fail.Throttled(error)

private fun error(error: String? = null, remaining: Int? = null, urgentMessage: String? = null) =
    CredentialStatus.Fail.Error(error, remaining, urgentMessage)
