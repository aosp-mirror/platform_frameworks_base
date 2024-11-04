package com.android.systemui.biometrics.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.data.repository.FakePromptRepository
import com.android.systemui.biometrics.domain.interactor.CredentialStatus
import com.android.systemui.biometrics.domain.interactor.FakeCredentialInteractor
import com.android.systemui.biometrics.domain.interactor.PromptCredentialInteractor
import com.android.systemui.biometrics.promptInfo
import com.android.systemui.biometrics.shared.model.PromptKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private const val USER_ID = 9
private const val REQUEST_ID = 9L
private const val OPERATION_ID = 10L

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class CredentialViewModelTest : SysuiTestCase() {

    private val dispatcher = UnconfinedTestDispatcher()
    private val promptRepository = FakePromptRepository()
    private val credentialInteractor = FakeCredentialInteractor()

    private lateinit var viewModel: CredentialViewModel

    @Before
    fun setup() {
        viewModel =
            CredentialViewModel(
                mContext,
                PromptCredentialInteractor(dispatcher, promptRepository, credentialInteractor)
            )
    }

    @Test fun setsPinInputFlags() = setsInputFlags(PromptKind.Pin, expectFlags = true)
    @Test fun setsPasswordInputFlags() = setsInputFlags(PromptKind.Password, expectFlags = false)
    @Test fun setsPatternInputFlags() = setsInputFlags(PromptKind.Pattern, expectFlags = false)

    private fun setsInputFlags(type: PromptKind, expectFlags: Boolean) =
        runTestWithKind(type) {
            var flags: Int? = null
            val job = launch { viewModel.inputFlags.collect { flags = it } }

            if (expectFlags) {
                assertThat(flags).isNotNull()
            } else {
                assertThat(flags).isNull()
            }
            job.cancel()
        }

    @Test fun isStealthIgnoredByPin() = isStealthMode(PromptKind.Pin, expectStealth = false)
    @Test
    fun isStealthIgnoredByPassword() = isStealthMode(PromptKind.Password, expectStealth = false)
    @Test fun isStealthUsedByPattern() = isStealthMode(PromptKind.Pattern, expectStealth = true)

    private fun isStealthMode(type: PromptKind, expectStealth: Boolean) =
        runTestWithKind(type, init = { credentialInteractor.stealthMode = true }) {
            var stealth: Boolean? = null
            val job = launch { viewModel.stealthMode.collect { stealth = it } }

            assertThat(stealth).isEqualTo(expectStealth)

            job.cancel()
        }

    @Test
    fun animatesContents() = runTestWithKind {
        val expected = arrayOf(true, false, true)
        val animate = mutableListOf<Boolean>()
        val job = launch { viewModel.animateContents.toList(animate) }

        for (value in expected) {
            viewModel.setAnimateContents(value)
            viewModel.setAnimateContents(value)
        }
        assertThat(animate).containsExactly(*expected).inOrder()

        job.cancel()
    }

    @Test
    fun showAndClearErrors() = runTestWithKind {
        var error = ""
        val job = launch { viewModel.errorMessage.collect { error = it } }
        assertThat(error).isEmpty()

        viewModel.showPatternTooShortError()
        assertThat(error).isNotEmpty()

        viewModel.resetErrorMessage()
        assertThat(error).isEmpty()

        job.cancel()
    }

    @Test
    fun checkCredential() = runTestWithKind {
        val hat = ByteArray(2)
        credentialInteractor.verifyCredentialResponse = { _ ->
            flowOf(CredentialStatus.Success.Verified(hat))
        }

        val attestations = mutableListOf<ByteArray?>()
        val remainingAttempts = mutableListOf<RemainingAttempts?>()
        var header: CredentialHeaderViewModel? = null
        val job = launch {
            launch { viewModel.validatedAttestation.toList(attestations) }
            launch { viewModel.remainingAttempts.toList(remainingAttempts) }
            launch { viewModel.header.collect { header = it } }
        }
        assertThat(header).isNotNull()

        viewModel.checkCredential("p", header!!)

        val attestation = attestations.removeLastOrNull()
        assertThat(attestation).isSameInstanceAs(hat)
        assertThat(attestations).isEmpty()
        assertThat(remainingAttempts).containsExactly(RemainingAttempts())

        job.cancel()
    }

    @Test
    fun checkCredentialWhenBad() = runTestWithKind {
        val remaining = 2
        val urgentError = "wow"
        credentialInteractor.verifyCredentialResponse = { _ ->
            flowOf(CredentialStatus.Fail.Error("error", remaining, urgentError))
        }

        val attestations = mutableListOf<ByteArray?>()
        val remainingAttempts = mutableListOf<RemainingAttempts?>()
        var header: CredentialHeaderViewModel? = null
        val job = launch {
            launch { viewModel.validatedAttestation.toList(attestations) }
            launch { viewModel.remainingAttempts.toList(remainingAttempts) }
            launch { viewModel.header.collect { header = it } }
        }
        assertThat(header).isNotNull()

        viewModel.checkCredential("1111", header!!)

        assertThat(attestations).containsExactly(null)

        val attemptInfo = remainingAttempts.removeLastOrNull()
        assertThat(attemptInfo).isNotNull()
        assertThat(attemptInfo!!.remaining).isEqualTo(remaining)
        assertThat(attemptInfo.message).isEqualTo(urgentError)
        assertThat(remainingAttempts).containsExactly(RemainingAttempts()) // initial value

        job.cancel()
    }

    private fun runTestWithKind(
        kind: PromptKind = PromptKind.Pin,
        init: () -> Unit = {},
        block: suspend TestScope.() -> Unit,
    ) =
        runTest(dispatcher) {
            init()
            promptRepository.setPrompt(promptInfo(), USER_ID, REQUEST_ID, OPERATION_ID, kind)
            block()
        }
}
