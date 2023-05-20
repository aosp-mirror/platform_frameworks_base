/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.biometrics.ui.viewmodel

import android.hardware.biometrics.PromptInfo
import android.hardware.face.FaceSensorPropertiesInternal
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal
import androidx.test.filters.SmallTest
import com.android.internal.widget.LockPatternUtils
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.AuthBiometricView
import com.android.systemui.biometrics.data.repository.FakePromptRepository
import com.android.systemui.biometrics.domain.interactor.PromptSelectorInteractor
import com.android.systemui.biometrics.domain.interactor.PromptSelectorInteractorImpl
import com.android.systemui.biometrics.domain.model.BiometricModalities
import com.android.systemui.biometrics.domain.model.BiometricModality
import com.android.systemui.biometrics.extractAuthenticatorTypes
import com.android.systemui.biometrics.faceSensorPropertiesInternal
import com.android.systemui.biometrics.fingerprintSensorPropertiesInternal
import com.android.systemui.coroutines.collectLastValue
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit

private const val USER_ID = 4
private const val CHALLENGE = 2L

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(Parameterized::class)
internal class PromptViewModelTest(private val testCase: TestCase) : SysuiTestCase() {

    @JvmField @Rule var mockitoRule = MockitoJUnit.rule()

    @Mock private lateinit var lockPatternUtils: LockPatternUtils

    private val testScope = TestScope()
    private val promptRepository = FakePromptRepository()

    private lateinit var selector: PromptSelectorInteractor
    private lateinit var viewModel: PromptViewModel

    @Before
    fun setup() {
        selector = PromptSelectorInteractorImpl(promptRepository, lockPatternUtils)
        selector.resetPrompt()

        viewModel = PromptViewModel(selector)
    }

    @Test
    fun `start idle and show authenticating`() =
        runGenericTest(doNotStart = true) {
            val expectedSize =
                if (testCase.shouldStartAsImplicitFlow) PromptSize.SMALL else PromptSize.MEDIUM
            val authenticating by collectLastValue(viewModel.isAuthenticating)
            val authenticated by collectLastValue(viewModel.isAuthenticated)
            val modalities by collectLastValue(viewModel.modalities)
            val message by collectLastValue(viewModel.message)
            val size by collectLastValue(viewModel.size)
            val legacyState by collectLastValue(viewModel.legacyState)

            assertThat(authenticating).isFalse()
            assertThat(authenticated?.isNotAuthenticated).isTrue()
            with(modalities ?: throw Exception("missing modalities")) {
                assertThat(hasFace).isEqualTo(testCase.face != null)
                assertThat(hasFingerprint).isEqualTo(testCase.fingerprint != null)
            }
            assertThat(message).isEqualTo(PromptMessage.Empty)
            assertThat(size).isEqualTo(expectedSize)
            assertThat(legacyState).isEqualTo(AuthBiometricView.STATE_AUTHENTICATING_ANIMATING_IN)

            val startMessage = "here we go"
            viewModel.showAuthenticating(startMessage, isRetry = false)

            assertThat(message).isEqualTo(PromptMessage.Help(startMessage))
            assertThat(authenticating).isTrue()
            assertThat(authenticated?.isNotAuthenticated).isTrue()
            assertThat(size).isEqualTo(expectedSize)
            assertButtonsVisible(negative = expectedSize != PromptSize.SMALL)
            assertThat(legacyState).isEqualTo(AuthBiometricView.STATE_AUTHENTICATING)
        }

    @Test
    fun `shows authenticated - no errors`() = runGenericTest {
        // this case can't happen until fingerprint is started
        // trigger it now since no error has occurred in this test
        val forceError = testCase.isCoex && testCase.authenticatedByFingerprint

        if (forceError) {
            assertThat(viewModel.fingerprintStartMode.first())
                .isEqualTo(FingerprintStartMode.Pending)
            viewModel.ensureFingerprintHasStarted(isDelayed = true)
        }

        showAuthenticated(
            testCase.authenticatedModality,
            testCase.expectConfirmation(atLeastOneFailure = forceError),
        )
    }

    private suspend fun TestScope.showAuthenticated(
        authenticatedModality: BiometricModality,
        expectConfirmation: Boolean,
    ) {
        val authenticating by collectLastValue(viewModel.isAuthenticating)
        val authenticated by collectLastValue(viewModel.isAuthenticated)
        val fpStartMode by collectLastValue(viewModel.fingerprintStartMode)
        val size by collectLastValue(viewModel.size)
        val legacyState by collectLastValue(viewModel.legacyState)

        val authWithSmallPrompt =
            testCase.shouldStartAsImplicitFlow &&
                (fpStartMode == FingerprintStartMode.Pending || testCase.isFaceOnly)
        assertThat(authenticating).isTrue()
        assertThat(authenticated?.isNotAuthenticated).isTrue()
        assertThat(size).isEqualTo(if (authWithSmallPrompt) PromptSize.SMALL else PromptSize.MEDIUM)
        assertThat(legacyState).isEqualTo(AuthBiometricView.STATE_AUTHENTICATING)
        assertButtonsVisible(negative = !authWithSmallPrompt)

        val delay = 1000L
        viewModel.showAuthenticated(authenticatedModality, delay)

        assertThat(authenticated?.isAuthenticated).isTrue()
        assertThat(authenticated?.delay).isEqualTo(delay)
        assertThat(authenticated?.needsUserConfirmation).isEqualTo(expectConfirmation)
        assertThat(size)
            .isEqualTo(
                if (authenticatedModality == BiometricModality.Fingerprint || expectConfirmation) {
                    PromptSize.MEDIUM
                } else {
                    PromptSize.SMALL
                }
            )
        assertThat(legacyState)
            .isEqualTo(
                if (expectConfirmation) {
                    AuthBiometricView.STATE_PENDING_CONFIRMATION
                } else {
                    AuthBiometricView.STATE_AUTHENTICATED
                }
            )
        assertButtonsVisible(
            cancel = expectConfirmation,
            confirm = expectConfirmation,
        )
    }

    @Test
    fun `shows temporary errors`() = runGenericTest {
        val checkAtEnd = suspend { assertButtonsVisible(negative = true) }

        showTemporaryErrors(restart = false) { checkAtEnd() }
        showTemporaryErrors(restart = false, helpAfterError = "foo") { checkAtEnd() }
        showTemporaryErrors(restart = true) { checkAtEnd() }
    }

    private suspend fun TestScope.showTemporaryErrors(
        restart: Boolean,
        helpAfterError: String = "",
        block: suspend TestScope.() -> Unit = {},
    ) {
        val errorMessage = "oh no!"
        val authenticating by collectLastValue(viewModel.isAuthenticating)
        val authenticated by collectLastValue(viewModel.isAuthenticated)
        val message by collectLastValue(viewModel.message)
        val messageVisible by collectLastValue(viewModel.isIndicatorMessageVisible)
        val size by collectLastValue(viewModel.size)
        val legacyState by collectLastValue(viewModel.legacyState)
        val canTryAgainNow by collectLastValue(viewModel.canTryAgainNow)

        val errorJob = launch {
            viewModel.showTemporaryError(
                errorMessage,
                authenticateAfterError = restart,
                messageAfterError = helpAfterError,
            )
        }

        assertThat(size).isEqualTo(PromptSize.MEDIUM)
        assertThat(message).isEqualTo(PromptMessage.Error(errorMessage))
        assertThat(messageVisible).isTrue()
        assertThat(legacyState).isEqualTo(AuthBiometricView.STATE_ERROR)

        // temporary error should disappear after a delay
        errorJob.join()
        if (helpAfterError.isNotBlank()) {
            assertThat(message).isEqualTo(PromptMessage.Help(helpAfterError))
            assertThat(messageVisible).isTrue()
        } else {
            assertThat(message).isEqualTo(PromptMessage.Empty)
            assertThat(messageVisible).isFalse()
        }
        assertThat(legacyState)
            .isEqualTo(
                if (restart) {
                    AuthBiometricView.STATE_AUTHENTICATING
                } else {
                    AuthBiometricView.STATE_HELP
                }
            )

        assertThat(authenticating).isEqualTo(restart)
        assertThat(authenticated?.isNotAuthenticated).isTrue()
        assertThat(canTryAgainNow).isFalse()

        block()
    }

    @Test
    fun `no errors or temporary help after authenticated`() = runGenericTest {
        val authenticating by collectLastValue(viewModel.isAuthenticating)
        val authenticated by collectLastValue(viewModel.isAuthenticated)
        val message by collectLastValue(viewModel.message)
        val messageIsShowing by collectLastValue(viewModel.isIndicatorMessageVisible)
        val canTryAgain by collectLastValue(viewModel.canTryAgainNow)

        viewModel.showAuthenticated(testCase.authenticatedModality, 0)

        val verifyNoError = {
            assertThat(authenticating).isFalse()
            assertThat(authenticated?.isAuthenticated).isTrue()
            assertThat(message).isEqualTo(PromptMessage.Empty)
            assertThat(canTryAgain).isFalse()
        }

        val errorJob = launch { viewModel.showTemporaryError("error") }
        verifyNoError()
        errorJob.join()
        verifyNoError()

        val helpJob = launch { viewModel.showTemporaryHelp("hi") }
        verifyNoError()
        helpJob.join()
        verifyNoError()

        // persistent help is allowed
        val stickyHelpMessage = "blah"
        viewModel.showHelp(stickyHelpMessage)
        assertThat(authenticating).isFalse()
        assertThat(authenticated?.isAuthenticated).isTrue()
        assertThat(message).isEqualTo(PromptMessage.Help(stickyHelpMessage))
        assertThat(messageIsShowing).isTrue()
    }

    //    @Test
    fun `suppress errors`() = runGenericTest {
        val errorMessage = "woot"
        val message by collectLastValue(viewModel.message)

        val errorJob = launch { viewModel.showTemporaryError(errorMessage) }
    }

    @Test
    fun `authenticated at most once`() = runGenericTest {
        val authenticating by collectLastValue(viewModel.isAuthenticating)
        val authenticated by collectLastValue(viewModel.isAuthenticated)

        viewModel.showAuthenticated(testCase.authenticatedModality, 0)

        assertThat(authenticating).isFalse()
        assertThat(authenticated?.isAuthenticated).isTrue()

        viewModel.showAuthenticated(testCase.authenticatedModality, 0)

        assertThat(authenticating).isFalse()
        assertThat(authenticated?.isAuthenticated).isTrue()
    }

    @Test
    fun `authenticating cannot restart after authenticated`() = runGenericTest {
        val authenticating by collectLastValue(viewModel.isAuthenticating)
        val authenticated by collectLastValue(viewModel.isAuthenticated)

        viewModel.showAuthenticated(testCase.authenticatedModality, 0)

        assertThat(authenticating).isFalse()
        assertThat(authenticated?.isAuthenticated).isTrue()

        viewModel.showAuthenticating("again!")

        assertThat(authenticating).isFalse()
        assertThat(authenticated?.isAuthenticated).isTrue()
    }

    @Test
    fun `confirm authentication`() = runGenericTest {
        val expectConfirmation = testCase.expectConfirmation(atLeastOneFailure = false)

        viewModel.showAuthenticated(testCase.authenticatedModality, 0)

        val authenticating by collectLastValue(viewModel.isAuthenticating)
        val authenticated by collectLastValue(viewModel.isAuthenticated)
        val message by collectLastValue(viewModel.message)
        val size by collectLastValue(viewModel.size)
        val legacyState by collectLastValue(viewModel.legacyState)
        val canTryAgain by collectLastValue(viewModel.canTryAgainNow)

        assertThat(authenticated?.needsUserConfirmation).isEqualTo(expectConfirmation)
        if (expectConfirmation) {
            assertThat(size).isEqualTo(PromptSize.MEDIUM)
            assertButtonsVisible(
                cancel = true,
                confirm = true,
            )

            viewModel.confirmAuthenticated()
            assertThat(message).isEqualTo(PromptMessage.Empty)
            assertButtonsVisible()
        }

        assertThat(authenticating).isFalse()
        assertThat(authenticated?.isAuthenticated).isTrue()
        assertThat(legacyState).isEqualTo(AuthBiometricView.STATE_AUTHENTICATED)
        assertThat(canTryAgain).isFalse()
    }

    @Test
    fun `cannot confirm unless authenticated`() = runGenericTest {
        val authenticating by collectLastValue(viewModel.isAuthenticating)
        val authenticated by collectLastValue(viewModel.isAuthenticated)

        viewModel.confirmAuthenticated()
        assertThat(authenticating).isTrue()
        assertThat(authenticated?.isNotAuthenticated).isTrue()

        viewModel.showAuthenticated(testCase.authenticatedModality, 0)

        // reconfirm should be a no-op
        viewModel.confirmAuthenticated()
        viewModel.confirmAuthenticated()

        assertThat(authenticating).isFalse()
        assertThat(authenticated?.isNotAuthenticated).isFalse()
    }

    @Test
    fun `shows help - before authenticated`() = runGenericTest {
        val helpMessage = "please help yourself to some cookies"
        val message by collectLastValue(viewModel.message)
        val messageVisible by collectLastValue(viewModel.isIndicatorMessageVisible)
        val size by collectLastValue(viewModel.size)
        val legacyState by collectLastValue(viewModel.legacyState)

        viewModel.showHelp(helpMessage)

        assertThat(size).isEqualTo(PromptSize.MEDIUM)
        assertThat(legacyState).isEqualTo(AuthBiometricView.STATE_HELP)
        assertThat(message).isEqualTo(PromptMessage.Help(helpMessage))
        assertThat(messageVisible).isTrue()

        assertThat(viewModel.isAuthenticating.first()).isFalse()
        assertThat(viewModel.isAuthenticated.first().isNotAuthenticated).isTrue()
    }

    @Test
    fun `shows help - after authenticated`() = runGenericTest {
        val expectConfirmation = testCase.expectConfirmation(atLeastOneFailure = false)
        val helpMessage = "more cookies please"
        val authenticating by collectLastValue(viewModel.isAuthenticating)
        val authenticated by collectLastValue(viewModel.isAuthenticated)
        val message by collectLastValue(viewModel.message)
        val messageVisible by collectLastValue(viewModel.isIndicatorMessageVisible)
        val size by collectLastValue(viewModel.size)
        val legacyState by collectLastValue(viewModel.legacyState)

        if (testCase.isCoex && testCase.authenticatedByFingerprint) {
            viewModel.ensureFingerprintHasStarted(isDelayed = true)
        }
        viewModel.showAuthenticated(testCase.authenticatedModality, 0)
        viewModel.showHelp(helpMessage)

        assertThat(size).isEqualTo(PromptSize.MEDIUM)
        assertThat(legacyState).isEqualTo(AuthBiometricView.STATE_PENDING_CONFIRMATION)
        assertThat(message).isEqualTo(PromptMessage.Help(helpMessage))
        assertThat(messageVisible).isTrue()
        assertThat(authenticating).isFalse()
        assertThat(authenticated?.isAuthenticated).isTrue()
        assertThat(authenticated?.needsUserConfirmation).isEqualTo(expectConfirmation)
        assertButtonsVisible(
            cancel = expectConfirmation,
            confirm = expectConfirmation,
        )
    }

    @Test
    fun `retries after failure`() = runGenericTest {
        val errorMessage = "bad"
        val helpMessage = "again?"
        val expectTryAgainButton = testCase.isFaceOnly
        val authenticating by collectLastValue(viewModel.isAuthenticating)
        val authenticated by collectLastValue(viewModel.isAuthenticated)
        val message by collectLastValue(viewModel.message)
        val messageVisible by collectLastValue(viewModel.isIndicatorMessageVisible)
        val canTryAgain by collectLastValue(viewModel.canTryAgainNow)

        viewModel.showAuthenticating("go")
        val errorJob = launch {
            viewModel.showTemporaryError(
                errorMessage,
                messageAfterError = helpMessage,
                authenticateAfterError = false,
                failedModality = testCase.authenticatedModality
            )
        }

        assertThat(authenticating).isFalse()
        assertThat(authenticated?.isAuthenticated).isFalse()
        assertThat(message).isEqualTo(PromptMessage.Error(errorMessage))
        assertThat(messageVisible).isTrue()
        assertThat(canTryAgain).isEqualTo(testCase.authenticatedByFace)
        assertButtonsVisible(negative = true, tryAgain = expectTryAgainButton)

        errorJob.join()

        assertThat(authenticating).isFalse()
        assertThat(authenticated?.isAuthenticated).isFalse()
        assertThat(message).isEqualTo(PromptMessage.Help(helpMessage))
        assertThat(messageVisible).isTrue()
        assertThat(canTryAgain).isEqualTo(testCase.authenticatedByFace)
        assertButtonsVisible(negative = true, tryAgain = expectTryAgainButton)

        val helpMessage2 = "foo"
        viewModel.showAuthenticating(helpMessage2, isRetry = true)
        assertThat(authenticating).isTrue()
        assertThat(authenticated?.isAuthenticated).isFalse()
        assertThat(message).isEqualTo(PromptMessage.Help(helpMessage2))
        assertThat(messageVisible).isTrue()
        assertButtonsVisible(negative = true)
    }

    @Test
    fun `switch to credential fallback`() = runGenericTest {
        val size by collectLastValue(viewModel.size)

        // TODO(b/251476085): remove Spaghetti, migrate logic, and update this test
        viewModel.onSwitchToCredential()

        assertThat(size).isEqualTo(PromptSize.LARGE)
    }

    /** Asserts that the selected buttons are visible now. */
    private suspend fun TestScope.assertButtonsVisible(
        tryAgain: Boolean = false,
        confirm: Boolean = false,
        cancel: Boolean = false,
        negative: Boolean = false,
        credential: Boolean = false,
    ) {
        runCurrent()
        assertThat(viewModel.isTryAgainButtonVisible.first()).isEqualTo(tryAgain)
        assertThat(viewModel.isConfirmButtonVisible.first()).isEqualTo(confirm)
        assertThat(viewModel.isCancelButtonVisible.first()).isEqualTo(cancel)
        assertThat(viewModel.isNegativeButtonVisible.first()).isEqualTo(negative)
        assertThat(viewModel.isCredentialButtonVisible.first()).isEqualTo(credential)
    }

    private fun runGenericTest(
        doNotStart: Boolean = false,
        allowCredentialFallback: Boolean = false,
        block: suspend TestScope.() -> Unit
    ) {
        selector.initializePrompt(
            requireConfirmation = testCase.confirmationRequested,
            allowCredentialFallback = allowCredentialFallback,
            fingerprint = testCase.fingerprint,
            face = testCase.face,
        )

        // put the view model in the initial authenticating state, unless explicitly skipped
        val startMode =
            when {
                doNotStart -> null
                testCase.isCoex -> FingerprintStartMode.Delayed
                else -> FingerprintStartMode.Normal
            }
        when (startMode) {
            FingerprintStartMode.Normal -> {
                viewModel.ensureFingerprintHasStarted(isDelayed = false)
                viewModel.showAuthenticating()
            }
            FingerprintStartMode.Delayed -> {
                viewModel.showAuthenticating()
            }
            else -> {
                /* skip */
            }
        }

        testScope.runTest { block() }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<TestCase> = singleModalityTestCases + coexTestCases

        private val singleModalityTestCases =
            listOf(
                TestCase(
                    face = faceSensorPropertiesInternal(strong = true).first(),
                    authenticatedModality = BiometricModality.Face,
                ),
                TestCase(
                    fingerprint = fingerprintSensorPropertiesInternal(strong = true).first(),
                    authenticatedModality = BiometricModality.Fingerprint,
                ),
                TestCase(
                    face = faceSensorPropertiesInternal(strong = true).first(),
                    authenticatedModality = BiometricModality.Face,
                    confirmationRequested = true,
                ),
                TestCase(
                    fingerprint = fingerprintSensorPropertiesInternal(strong = true).first(),
                    authenticatedModality = BiometricModality.Fingerprint,
                    confirmationRequested = true,
                ),
            )

        private val coexTestCases =
            listOf(
                TestCase(
                    face = faceSensorPropertiesInternal(strong = true).first(),
                    fingerprint = fingerprintSensorPropertiesInternal(strong = true).first(),
                    authenticatedModality = BiometricModality.Face,
                ),
                TestCase(
                    face = faceSensorPropertiesInternal(strong = true).first(),
                    fingerprint = fingerprintSensorPropertiesInternal(strong = true).first(),
                    authenticatedModality = BiometricModality.Fingerprint,
                ),
                TestCase(
                    face = faceSensorPropertiesInternal(strong = true).first(),
                    fingerprint = fingerprintSensorPropertiesInternal(strong = true).first(),
                    authenticatedModality = BiometricModality.Face,
                    confirmationRequested = true,
                ),
                TestCase(
                    face = faceSensorPropertiesInternal(strong = true).first(),
                    fingerprint = fingerprintSensorPropertiesInternal(strong = true).first(),
                    authenticatedModality = BiometricModality.Fingerprint,
                    confirmationRequested = true,
                ),
            )
    }
}

internal data class TestCase(
    val fingerprint: FingerprintSensorPropertiesInternal? = null,
    val face: FaceSensorPropertiesInternal? = null,
    val authenticatedModality: BiometricModality,
    val confirmationRequested: Boolean = false,
) {
    override fun toString(): String {
        val modality =
            when {
                fingerprint != null && face != null -> "coex"
                fingerprint != null -> "fingerprint only"
                face != null -> "face only"
                else -> "?"
            }
        return "[$modality, by: $authenticatedModality, confirm: $confirmationRequested]"
    }

    fun expectConfirmation(atLeastOneFailure: Boolean): Boolean =
        when {
            isCoex && authenticatedModality == BiometricModality.Face ->
                atLeastOneFailure || confirmationRequested
            isFaceOnly -> confirmationRequested
            else -> false
        }

    val authenticatedByFingerprint: Boolean
        get() = authenticatedModality == BiometricModality.Fingerprint

    val authenticatedByFace: Boolean
        get() = authenticatedModality == BiometricModality.Face

    val isFaceOnly: Boolean
        get() = face != null && fingerprint == null

    val isFingerprintOnly: Boolean
        get() = face == null && fingerprint != null

    val isCoex: Boolean
        get() = face != null && fingerprint != null

    val shouldStartAsImplicitFlow: Boolean
        get() = (isFaceOnly || isCoex) && !confirmationRequested
}

/** Initialize the test by selecting the give [fingerprint] or [face] configuration(s). */
private fun PromptSelectorInteractor.initializePrompt(
    fingerprint: FingerprintSensorPropertiesInternal? = null,
    face: FaceSensorPropertiesInternal? = null,
    requireConfirmation: Boolean = false,
    allowCredentialFallback: Boolean = false,
) {
    val info =
        PromptInfo().apply {
            title = "t"
            subtitle = "s"
            authenticators = listOf(face, fingerprint).extractAuthenticatorTypes()
            isDeviceCredentialAllowed = allowCredentialFallback
            isConfirmationRequested = requireConfirmation
        }
    useBiometricsForAuthentication(
        info,
        requireConfirmation,
        USER_ID,
        CHALLENGE,
        BiometricModalities(fingerprintProperties = fingerprint, faceProperties = face),
    )
}
