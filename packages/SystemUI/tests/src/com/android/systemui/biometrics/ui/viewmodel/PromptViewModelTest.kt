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

import android.app.ActivityManager.RunningTaskInfo
import android.app.ActivityTaskManager
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.NameNotFoundException
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.hardware.biometrics.BiometricFingerprintConstants
import android.hardware.biometrics.Flags.FLAG_CUSTOM_BIOMETRIC_PROMPT
import android.hardware.biometrics.PromptContentItemBulletedText
import android.hardware.biometrics.PromptContentView
import android.hardware.biometrics.PromptContentViewWithMoreOptionsButton
import android.hardware.biometrics.PromptInfo
import android.hardware.biometrics.PromptVerticalListContentView
import android.hardware.face.FaceSensorPropertiesInternal
import android.hardware.fingerprint.FingerprintSensorProperties
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal
import android.platform.test.annotations.EnableFlags
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import androidx.test.filters.SmallTest
import com.android.internal.widget.LockPatternUtils
import com.android.launcher3.icons.IconProvider
import com.android.systemui.Flags.FLAG_BP_TALKBACK
import com.android.systemui.Flags.FLAG_CONSTRAINT_BP
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.AuthController
import com.android.systemui.biometrics.UdfpsUtils
import com.android.systemui.biometrics.Utils.toBitmap
import com.android.systemui.biometrics.data.repository.FakeBiometricStatusRepository
import com.android.systemui.biometrics.data.repository.FakeDisplayStateRepository
import com.android.systemui.biometrics.data.repository.FakeFingerprintPropertyRepository
import com.android.systemui.biometrics.data.repository.FakePromptRepository
import com.android.systemui.biometrics.domain.interactor.BiometricStatusInteractor
import com.android.systemui.biometrics.domain.interactor.BiometricStatusInteractorImpl
import com.android.systemui.biometrics.domain.interactor.DisplayStateInteractor
import com.android.systemui.biometrics.domain.interactor.DisplayStateInteractorImpl
import com.android.systemui.biometrics.domain.interactor.PromptSelectorInteractor
import com.android.systemui.biometrics.domain.interactor.PromptSelectorInteractorImpl
import com.android.systemui.biometrics.domain.interactor.UdfpsOverlayInteractor
import com.android.systemui.biometrics.extractAuthenticatorTypes
import com.android.systemui.biometrics.faceSensorPropertiesInternal
import com.android.systemui.biometrics.fingerprintSensorPropertiesInternal
import com.android.systemui.biometrics.shared.model.AuthenticationReason
import com.android.systemui.biometrics.shared.model.BiometricModalities
import com.android.systemui.biometrics.shared.model.BiometricModality
import com.android.systemui.biometrics.shared.model.DisplayRotation
import com.android.systemui.biometrics.shared.model.toSensorStrength
import com.android.systemui.biometrics.shared.model.toSensorType
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.display.data.repository.FakeDisplayRepository
import com.android.systemui.keyguard.shared.model.AcquiredFingerprintAuthenticationStatus
import com.android.systemui.res.R
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
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
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

private const val USER_ID = 4
private const val REQUEST_ID = 4L
private const val CHALLENGE = 2L
private const val DELAY = 1000L
private const val OP_PACKAGE_NAME = "biometric.testapp"
private const val OP_PACKAGE_NAME_NO_ICON = "biometric.testapp.noicon"
private const val OP_PACKAGE_NAME_CAN_NOT_BE_FOUND = "can.not.be.found"

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
internal class PromptViewModelTest(private val testCase: TestCase) : SysuiTestCase() {

    @JvmField @Rule var mockitoRule = MockitoJUnit.rule()

    @Mock private lateinit var lockPatternUtils: LockPatternUtils
    @Mock private lateinit var authController: AuthController
    @Mock private lateinit var selectedUserInteractor: SelectedUserInteractor
    @Mock private lateinit var udfpsUtils: UdfpsUtils
    @Mock private lateinit var packageManager: PackageManager
    @Mock private lateinit var iconProvider: IconProvider
    @Mock private lateinit var applicationInfoWithIcon: ApplicationInfo
    @Mock private lateinit var applicationInfoNoIcon: ApplicationInfo
    @Mock private lateinit var activityTaskManager: ActivityTaskManager
    @Mock private lateinit var activityInfo: ActivityInfo
    @Mock private lateinit var runningTaskInfo: RunningTaskInfo

    private val fakeExecutor = FakeExecutor(FakeSystemClock())
    private val testScope = TestScope()
    private val defaultLogoIcon = context.getDrawable(R.drawable.ic_android)
    private val defaultLogoIconWithOverrides = context.getDrawable(R.drawable.ic_add)
    private val logoResFromApp = R.drawable.ic_cake
    private val logoDrawableFromAppRes = context.getDrawable(logoResFromApp)
    private val logoBitmapFromApp = Bitmap.createBitmap(400, 400, Bitmap.Config.RGB_565)
    private val defaultLogoDescription = "Test Android App"
    private val logoDescriptionFromApp = "Test Cake App"
    private val packageNameForLogoWithOverrides = "should.use.overridden.logo"
    /** Prompt panel size padding */
    private val smallHorizontalGuidelinePadding =
        context.resources.getDimensionPixelSize(
            R.dimen.biometric_prompt_land_small_horizontal_guideline_padding
        )
    private val udfpsHorizontalGuidelinePadding =
        context.resources.getDimensionPixelSize(
            R.dimen.biometric_prompt_two_pane_udfps_horizontal_guideline_padding
        )
    private val udfpsHorizontalShorterGuidelinePadding =
        context.resources.getDimensionPixelSize(
            R.dimen.biometric_prompt_two_pane_udfps_shorter_horizontal_guideline_padding
        )
    private val mediumTopGuidelinePadding =
        context.resources.getDimensionPixelSize(
            R.dimen.biometric_prompt_one_pane_medium_top_guideline_padding
        )
    private val mediumHorizontalGuidelinePadding =
        context.resources.getDimensionPixelSize(
            R.dimen.biometric_prompt_two_pane_medium_horizontal_guideline_padding
        )

    private lateinit var fingerprintRepository: FakeFingerprintPropertyRepository
    private lateinit var promptRepository: FakePromptRepository
    private lateinit var displayStateRepository: FakeDisplayStateRepository
    private lateinit var biometricStatusRepository: FakeBiometricStatusRepository
    private lateinit var displayRepository: FakeDisplayRepository
    private lateinit var displayStateInteractor: DisplayStateInteractor
    private lateinit var udfpsOverlayInteractor: UdfpsOverlayInteractor
    private lateinit var biometricStatusInteractor: BiometricStatusInteractor

    private lateinit var selector: PromptSelectorInteractor
    private lateinit var viewModel: PromptViewModel
    private lateinit var iconViewModel: PromptIconViewModel
    private lateinit var promptContentView: PromptContentView
    private lateinit var promptContentViewWithMoreOptionsButton:
        PromptContentViewWithMoreOptionsButton

    @Before
    fun setup() {
        fingerprintRepository = FakeFingerprintPropertyRepository()
        testCase.fingerprint?.let {
            fingerprintRepository.setProperties(
                it.sensorId,
                it.sensorStrength.toSensorStrength(),
                it.sensorType.toSensorType(),
                it.allLocations.associateBy { sensorLocationInternal ->
                    sensorLocationInternal.displayId
                }
            )
        }
        promptRepository = FakePromptRepository()
        displayStateRepository = FakeDisplayStateRepository()
        displayRepository = FakeDisplayRepository()
        displayStateInteractor =
            DisplayStateInteractorImpl(
                testScope.backgroundScope,
                mContext,
                fakeExecutor,
                displayStateRepository,
                displayRepository,
            )
        udfpsOverlayInteractor =
            UdfpsOverlayInteractor(
                context,
                authController,
                selectedUserInteractor,
                testScope.backgroundScope
            )
        biometricStatusRepository = FakeBiometricStatusRepository()
        biometricStatusInteractor =
            BiometricStatusInteractorImpl(
                activityTaskManager,
                biometricStatusRepository,
                fingerprintRepository
            )

        promptContentView =
            PromptVerticalListContentView.Builder()
                .addListItem(PromptContentItemBulletedText("content item 1"))
                .addListItem(PromptContentItemBulletedText("content item 2"), 1)
                .build()

        promptContentViewWithMoreOptionsButton =
            PromptContentViewWithMoreOptionsButton.Builder()
                .setDescription("test")
                .setMoreOptionsButtonListener(fakeExecutor) { _, _ -> }
                .build()

        // Set up default logo info and app customized info
        whenever(packageManager.getApplicationInfo(eq(OP_PACKAGE_NAME_NO_ICON), anyInt()))
            .thenReturn(applicationInfoNoIcon)
        whenever(packageManager.getApplicationInfo(eq(OP_PACKAGE_NAME), anyInt()))
            .thenReturn(applicationInfoWithIcon)
        whenever(packageManager.getApplicationInfo(eq(packageNameForLogoWithOverrides), anyInt()))
            .thenReturn(applicationInfoWithIcon)
        whenever(packageManager.getApplicationInfo(eq(OP_PACKAGE_NAME_CAN_NOT_BE_FOUND), anyInt()))
            .thenThrow(NameNotFoundException())

        whenever(packageManager.getActivityInfo(any(), anyInt())).thenReturn(activityInfo)
        whenever(iconProvider.getIcon(activityInfo)).thenReturn(defaultLogoIconWithOverrides)
        whenever(packageManager.getApplicationIcon(applicationInfoWithIcon))
            .thenReturn(defaultLogoIcon)
        whenever(packageManager.getApplicationLabel(applicationInfoWithIcon))
            .thenReturn(defaultLogoDescription)
        whenever(packageManager.getUserBadgedIcon(any(), any())).then { it.getArgument(0) }
        whenever(packageManager.getUserBadgedLabel(any(), any())).then { it.getArgument(0) }

        context.setMockPackageManager(packageManager)
        val resources = context.getOrCreateTestableResources()
        resources.addOverride(logoResFromApp, logoDrawableFromAppRes)
        resources.addOverride(
            R.array.biometric_dialog_package_names_for_logo_with_overrides,
            arrayOf(packageNameForLogoWithOverrides)
        )
    }

    @Test
    fun start_idle_and_show_authenticating() =
        runGenericTest(doNotStart = true) {
            val expectedSize =
                if (testCase.shouldStartAsImplicitFlow) PromptSize.SMALL else PromptSize.MEDIUM
            val authenticating by collectLastValue(viewModel.isAuthenticating)
            val authenticated by collectLastValue(viewModel.isAuthenticated)
            val modalities by collectLastValue(viewModel.modalities)
            val message by collectLastValue(viewModel.message)
            val size by collectLastValue(viewModel.size)

            assertThat(authenticating).isFalse()
            assertThat(authenticated?.isNotAuthenticated).isTrue()
            with(modalities ?: throw Exception("missing modalities")) {
                assertThat(hasFace).isEqualTo(testCase.face != null)
                assertThat(hasFingerprint).isEqualTo(testCase.fingerprint != null)
            }
            assertThat(message).isEqualTo(PromptMessage.Empty)
            assertThat(size).isEqualTo(expectedSize)

            val startMessage = "here we go"
            viewModel.showAuthenticating(startMessage, isRetry = false)

            assertThat(message).isEqualTo(PromptMessage.Help(startMessage))
            assertThat(authenticating).isTrue()
            assertThat(authenticated?.isNotAuthenticated).isTrue()
            assertThat(size).isEqualTo(expectedSize)
            assertButtonsVisible(negative = expectedSize != PromptSize.SMALL)
        }

    @Test
    fun shows_authenticated_with_no_errors() = runGenericTest {
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

    @Test
    fun set_haptic_on_confirm_when_confirmation_required_otherwise_on_authenticated() =
        runGenericTest {
            val expectConfirmation = testCase.expectConfirmation(atLeastOneFailure = false)

            viewModel.showAuthenticated(testCase.authenticatedModality, 1_000L)

            val confirmHaptics by collectLastValue(viewModel.hapticsToPlay)
            assertThat(confirmHaptics?.hapticFeedbackConstant)
                .isEqualTo(
                    if (expectConfirmation) HapticFeedbackConstants.NO_HAPTICS
                    else HapticFeedbackConstants.CONFIRM
                )
            assertThat(confirmHaptics?.flag)
                .isEqualTo(
                    if (expectConfirmation) null
                    else HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                )

            if (expectConfirmation) {
                viewModel.confirmAuthenticated()
            }

            val confirmedHaptics by collectLastValue(viewModel.hapticsToPlay)
            assertThat(confirmedHaptics?.hapticFeedbackConstant)
                .isEqualTo(HapticFeedbackConstants.CONFIRM)
            assertThat(confirmedHaptics?.flag)
                .isEqualTo(HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
        }

    @Test
    fun playSuccessHaptic_SetsConfirmConstant() = runGenericTest {
        val expectConfirmation = testCase.expectConfirmation(atLeastOneFailure = false)
        viewModel.showAuthenticated(testCase.authenticatedModality, 1_000L)

        if (expectConfirmation) {
            viewModel.confirmAuthenticated()
        }

        val currentHaptics by collectLastValue(viewModel.hapticsToPlay)
        assertThat(currentHaptics?.hapticFeedbackConstant)
            .isEqualTo(HapticFeedbackConstants.CONFIRM)
        assertThat(currentHaptics?.flag)
            .isEqualTo(HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
    }

    @Test
    fun playErrorHaptic_SetsRejectConstant() = runGenericTest {
        viewModel.showTemporaryError("test", "messageAfterError", false)

        val currentHaptics by collectLastValue(viewModel.hapticsToPlay)
        assertThat(currentHaptics?.hapticFeedbackConstant).isEqualTo(HapticFeedbackConstants.REJECT)
        assertThat(currentHaptics?.flag)
            .isEqualTo(HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
    }

    @Test
    fun start_idle_and_show_authenticating_iconUpdate() =
        runGenericTest(doNotStart = true) {
            val currentRotation by collectLastValue(displayStateInteractor.currentRotation)
            val iconAsset by collectLastValue(iconViewModel.iconAsset)
            val iconContentDescriptionId by collectLastValue(iconViewModel.contentDescriptionId)
            val shouldAnimateIconView by collectLastValue(iconViewModel.shouldAnimateIconView)

            val forceExplicitFlow = testCase.isCoex && testCase.authenticatedByFingerprint
            if (forceExplicitFlow) {
                viewModel.ensureFingerprintHasStarted(isDelayed = true)
            }

            val startMessage = "here we go"
            viewModel.showAuthenticating(startMessage, isRetry = false)

            if (testCase.isFingerprintOnly) {
                val iconOverlayAsset by collectLastValue(iconViewModel.iconOverlayAsset)
                val shouldAnimateIconOverlay by
                    collectLastValue(iconViewModel.shouldAnimateIconOverlay)

                if (testCase.sensorType == FingerprintSensorProperties.TYPE_POWER_BUTTON) {
                    val expectedOverlayAsset =
                        when (currentRotation) {
                            DisplayRotation.ROTATION_0 ->
                                R.raw.biometricprompt_fingerprint_to_error_landscape
                            DisplayRotation.ROTATION_90 ->
                                R.raw.biometricprompt_symbol_fingerprint_to_error_portrait_topleft
                            DisplayRotation.ROTATION_180 ->
                                R.raw.biometricprompt_fingerprint_to_error_landscape
                            DisplayRotation.ROTATION_270 ->
                                R.raw
                                    .biometricprompt_symbol_fingerprint_to_error_portrait_bottomright
                            else -> throw Exception("invalid rotation")
                        }
                    assertThat(iconOverlayAsset).isEqualTo(expectedOverlayAsset)
                    assertThat(iconContentDescriptionId)
                        .isEqualTo(R.string.security_settings_sfps_enroll_find_sensor_message)
                    assertThat(shouldAnimateIconOverlay).isEqualTo(false)
                } else {
                    assertThat(iconAsset)
                        .isEqualTo(R.raw.fingerprint_dialogue_fingerprint_to_error_lottie)
                    assertThat(iconOverlayAsset).isEqualTo(-1)
                    assertThat(iconContentDescriptionId)
                        .isEqualTo(R.string.fingerprint_dialog_touch_sensor)
                    assertThat(shouldAnimateIconView).isEqualTo(false)
                    assertThat(shouldAnimateIconOverlay).isEqualTo(false)
                }
            }

            if (testCase.isFaceOnly) {
                val expectedIconAsset = R.raw.face_dialog_authenticating
                assertThat(iconAsset).isEqualTo(expectedIconAsset)
                assertThat(iconContentDescriptionId)
                    .isEqualTo(R.string.biometric_dialog_face_icon_description_authenticating)
                assertThat(shouldAnimateIconView).isEqualTo(true)
            }

            if (testCase.isCoex) {
                if (testCase.confirmationRequested || forceExplicitFlow) {
                    // explicit flow
                    val iconOverlayAsset by collectLastValue(iconViewModel.iconOverlayAsset)
                    val shouldAnimateIconOverlay by
                        collectLastValue(iconViewModel.shouldAnimateIconOverlay)

                    // TODO: Update when SFPS co-ex is implemented
                    if (testCase.sensorType != FingerprintSensorProperties.TYPE_POWER_BUTTON) {
                        assertThat(iconAsset)
                            .isEqualTo(R.raw.fingerprint_dialogue_fingerprint_to_error_lottie)
                        assertThat(iconOverlayAsset).isEqualTo(-1)
                        assertThat(iconContentDescriptionId)
                            .isEqualTo(R.string.fingerprint_dialog_touch_sensor)
                        assertThat(shouldAnimateIconView).isEqualTo(false)
                        assertThat(shouldAnimateIconOverlay).isEqualTo(false)
                    }
                } else {
                    // implicit flow
                    val expectedIconAsset = R.raw.face_dialog_authenticating
                    assertThat(iconAsset).isEqualTo(expectedIconAsset)
                    assertThat(iconContentDescriptionId)
                        .isEqualTo(R.string.biometric_dialog_face_icon_description_authenticating)
                    assertThat(shouldAnimateIconView).isEqualTo(true)
                }
            }
        }

    @Test
    fun start_authenticating_show_and_clear_error_iconUpdate() = runGenericTest {
        val currentRotation by collectLastValue(displayStateInteractor.currentRotation)

        val iconAsset by collectLastValue(iconViewModel.iconAsset)
        val iconContentDescriptionId by collectLastValue(iconViewModel.contentDescriptionId)
        val shouldAnimateIconView by collectLastValue(iconViewModel.shouldAnimateIconView)

        val forceExplicitFlow = testCase.isCoex && testCase.authenticatedByFingerprint
        if (forceExplicitFlow) {
            viewModel.ensureFingerprintHasStarted(isDelayed = true)
        }

        val errorJob = launch {
            viewModel.showTemporaryError(
                "so sad",
                messageAfterError = "",
                authenticateAfterError = testCase.isFingerprintOnly || testCase.isCoex,
            )
            // Usually done by binder
            iconViewModel.setPreviousIconWasError(true)
            iconViewModel.setPreviousIconOverlayWasError(true)
        }

        if (testCase.isFingerprintOnly) {
            val iconOverlayAsset by collectLastValue(iconViewModel.iconOverlayAsset)
            val shouldAnimateIconOverlay by collectLastValue(iconViewModel.shouldAnimateIconOverlay)

            if (testCase.sensorType == FingerprintSensorProperties.TYPE_POWER_BUTTON) {
                val expectedOverlayAsset =
                    when (currentRotation) {
                        DisplayRotation.ROTATION_0 ->
                            R.raw.biometricprompt_fingerprint_to_error_landscape
                        DisplayRotation.ROTATION_90 ->
                            R.raw.biometricprompt_symbol_fingerprint_to_error_portrait_topleft
                        DisplayRotation.ROTATION_180 ->
                            R.raw.biometricprompt_fingerprint_to_error_landscape
                        DisplayRotation.ROTATION_270 ->
                            R.raw.biometricprompt_symbol_fingerprint_to_error_portrait_bottomright
                        else -> throw Exception("invalid rotation")
                    }
                assertThat(iconOverlayAsset).isEqualTo(expectedOverlayAsset)
                assertThat(iconContentDescriptionId).isEqualTo(R.string.biometric_dialog_try_again)
                assertThat(shouldAnimateIconOverlay).isEqualTo(true)
            } else {
                assertThat(iconAsset)
                    .isEqualTo(R.raw.fingerprint_dialogue_fingerprint_to_error_lottie)
                assertThat(iconOverlayAsset).isEqualTo(-1)
                assertThat(iconContentDescriptionId).isEqualTo(R.string.biometric_dialog_try_again)
                assertThat(shouldAnimateIconView).isEqualTo(true)
                assertThat(shouldAnimateIconOverlay).isEqualTo(false)
            }

            // Clear error, restart authenticating
            errorJob.join()

            if (testCase.sensorType == FingerprintSensorProperties.TYPE_POWER_BUTTON) {
                val expectedOverlayAsset =
                    when (currentRotation) {
                        DisplayRotation.ROTATION_0 ->
                            R.raw.biometricprompt_symbol_error_to_fingerprint_landscape
                        DisplayRotation.ROTATION_90 ->
                            R.raw.biometricprompt_symbol_error_to_fingerprint_portrait_topleft
                        DisplayRotation.ROTATION_180 ->
                            R.raw.biometricprompt_symbol_error_to_fingerprint_landscape
                        DisplayRotation.ROTATION_270 ->
                            R.raw.biometricprompt_symbol_error_to_fingerprint_portrait_bottomright
                        else -> throw Exception("invalid rotation")
                    }
                assertThat(iconOverlayAsset).isEqualTo(expectedOverlayAsset)
                assertThat(iconContentDescriptionId)
                    .isEqualTo(R.string.security_settings_sfps_enroll_find_sensor_message)
                assertThat(shouldAnimateIconOverlay).isEqualTo(true)
            } else {
                assertThat(iconAsset)
                    .isEqualTo(R.raw.fingerprint_dialogue_error_to_fingerprint_lottie)
                assertThat(iconOverlayAsset).isEqualTo(-1)
                assertThat(iconContentDescriptionId)
                    .isEqualTo(R.string.fingerprint_dialog_touch_sensor)
                assertThat(shouldAnimateIconView).isEqualTo(true)
                assertThat(shouldAnimateIconOverlay).isEqualTo(false)
            }
        }

        if (testCase.isFaceOnly) {
            assertThat(iconAsset).isEqualTo(R.drawable.face_dialog_dark_to_error)
            assertThat(iconContentDescriptionId).isEqualTo(R.string.keyguard_face_failed)
            assertThat(shouldAnimateIconView).isEqualTo(true)

            // Clear error, go to idle
            errorJob.join()

            assertThat(iconAsset).isEqualTo(R.drawable.face_dialog_error_to_idle)
            assertThat(iconContentDescriptionId)
                .isEqualTo(R.string.biometric_dialog_face_icon_description_idle)
            assertThat(shouldAnimateIconView).isEqualTo(true)
        }

        if (testCase.isCoex) {
            val iconOverlayAsset by collectLastValue(iconViewModel.iconOverlayAsset)
            val shouldAnimateIconOverlay by collectLastValue(iconViewModel.shouldAnimateIconOverlay)

            // TODO: Update when SFPS co-ex is implemented
            if (testCase.sensorType != FingerprintSensorProperties.TYPE_POWER_BUTTON) {
                assertThat(iconAsset)
                    .isEqualTo(R.raw.fingerprint_dialogue_fingerprint_to_error_lottie)
                assertThat(iconOverlayAsset).isEqualTo(-1)
                assertThat(iconContentDescriptionId).isEqualTo(R.string.biometric_dialog_try_again)
                assertThat(shouldAnimateIconView).isEqualTo(true)
                assertThat(shouldAnimateIconOverlay).isEqualTo(false)
            }

            // Clear error, restart authenticating
            errorJob.join()

            if (testCase.sensorType != FingerprintSensorProperties.TYPE_POWER_BUTTON) {
                assertThat(iconAsset)
                    .isEqualTo(R.raw.fingerprint_dialogue_error_to_fingerprint_lottie)
                assertThat(iconOverlayAsset).isEqualTo(-1)
                assertThat(iconContentDescriptionId)
                    .isEqualTo(R.string.fingerprint_dialog_touch_sensor)
                assertThat(shouldAnimateIconView).isEqualTo(true)
                assertThat(shouldAnimateIconOverlay).isEqualTo(false)
            }
        }
    }

    @Test
    fun shows_authenticated_no_errors_no_confirmation_required_iconUpdate() = runGenericTest {
        if (!testCase.confirmationRequested) {
            val currentRotation by collectLastValue(displayStateInteractor.currentRotation)

            val iconAsset by collectLastValue(iconViewModel.iconAsset)
            val iconContentDescriptionId by collectLastValue(iconViewModel.contentDescriptionId)
            val shouldAnimateIconView by collectLastValue(iconViewModel.shouldAnimateIconView)

            viewModel.showAuthenticated(
                modality = testCase.authenticatedModality,
                dismissAfterDelay = DELAY
            )

            if (testCase.isFingerprintOnly) {
                val iconOverlayAsset by collectLastValue(iconViewModel.iconOverlayAsset)
                val shouldAnimateIconOverlay by
                    collectLastValue(iconViewModel.shouldAnimateIconOverlay)

                if (testCase.sensorType == FingerprintSensorProperties.TYPE_POWER_BUTTON) {
                    val expectedOverlayAsset =
                        when (currentRotation) {
                            DisplayRotation.ROTATION_0 ->
                                R.raw.biometricprompt_symbol_fingerprint_to_success_landscape
                            DisplayRotation.ROTATION_90 ->
                                R.raw.biometricprompt_symbol_fingerprint_to_success_portrait_topleft
                            DisplayRotation.ROTATION_180 ->
                                R.raw.biometricprompt_symbol_fingerprint_to_success_landscape
                            DisplayRotation.ROTATION_270 ->
                                R.raw
                                    .biometricprompt_symbol_fingerprint_to_success_portrait_bottomright
                            else -> throw Exception("invalid rotation")
                        }
                    assertThat(iconOverlayAsset).isEqualTo(expectedOverlayAsset)
                    assertThat(iconContentDescriptionId)
                        .isEqualTo(R.string.security_settings_sfps_enroll_find_sensor_message)
                    assertThat(shouldAnimateIconOverlay).isEqualTo(true)
                } else {
                    val isAuthenticated by collectLastValue(viewModel.isAuthenticated)
                    assertThat(iconAsset)
                        .isEqualTo(R.raw.fingerprint_dialogue_fingerprint_to_success_lottie)
                    assertThat(iconOverlayAsset).isEqualTo(-1)
                    assertThat(iconContentDescriptionId)
                        .isEqualTo(R.string.fingerprint_dialog_touch_sensor)
                    assertThat(shouldAnimateIconView).isEqualTo(true)
                    assertThat(shouldAnimateIconOverlay).isEqualTo(false)
                }
            }

            // If co-ex, using implicit flow (explicit flow always requires confirmation)
            if (testCase.isFaceOnly || testCase.isCoex) {
                assertThat(iconAsset).isEqualTo(R.drawable.face_dialog_dark_to_checkmark)
                assertThat(iconContentDescriptionId)
                    .isEqualTo(R.string.biometric_dialog_face_icon_description_authenticated)
                assertThat(shouldAnimateIconView).isEqualTo(true)
            }
        }
    }

    @Test
    fun shows_pending_confirmation_iconUpdate() = runGenericTest {
        if (
            (testCase.isFaceOnly || testCase.isCoex) &&
                testCase.authenticatedByFace &&
                testCase.confirmationRequested
        ) {
            val iconAsset by collectLastValue(iconViewModel.iconAsset)
            val iconContentDescriptionId by collectLastValue(iconViewModel.contentDescriptionId)
            val shouldAnimateIconView by collectLastValue(iconViewModel.shouldAnimateIconView)

            viewModel.showAuthenticated(
                modality = testCase.authenticatedModality,
                dismissAfterDelay = DELAY
            )

            if (testCase.isFaceOnly) {
                assertThat(iconAsset).isEqualTo(R.drawable.face_dialog_wink_from_dark)
                assertThat(iconContentDescriptionId)
                    .isEqualTo(R.string.biometric_dialog_face_icon_description_authenticated)
                assertThat(shouldAnimateIconView).isEqualTo(true)
            }

            // explicit flow because confirmation requested
            if (testCase.isCoex) {
                val iconOverlayAsset by collectLastValue(iconViewModel.iconOverlayAsset)
                val shouldAnimateIconOverlay by
                    collectLastValue(iconViewModel.shouldAnimateIconOverlay)

                // TODO: Update when SFPS co-ex is implemented
                if (testCase.sensorType != FingerprintSensorProperties.TYPE_POWER_BUTTON) {
                    assertThat(iconAsset)
                        .isEqualTo(R.raw.fingerprint_dialogue_fingerprint_to_unlock_lottie)
                    assertThat(iconOverlayAsset).isEqualTo(-1)
                    assertThat(iconContentDescriptionId)
                        .isEqualTo(R.string.fingerprint_dialog_authenticated_confirmation)
                    assertThat(shouldAnimateIconView).isEqualTo(true)
                    assertThat(shouldAnimateIconOverlay).isEqualTo(false)
                }
            }
        }
    }

    @Test
    fun shows_authenticated_explicitly_confirmed_iconUpdate() = runGenericTest {
        if (
            (testCase.isFaceOnly || testCase.isCoex) &&
                testCase.authenticatedByFace &&
                testCase.confirmationRequested
        ) {
            val iconAsset by collectLastValue(iconViewModel.iconAsset)
            val iconContentDescriptionId by collectLastValue(iconViewModel.contentDescriptionId)
            val shouldAnimateIconView by collectLastValue(iconViewModel.shouldAnimateIconView)

            viewModel.showAuthenticated(
                modality = testCase.authenticatedModality,
                dismissAfterDelay = DELAY
            )

            viewModel.confirmAuthenticated()

            if (testCase.isFaceOnly) {
                assertThat(iconAsset).isEqualTo(R.drawable.face_dialog_dark_to_checkmark)
                assertThat(iconContentDescriptionId)
                    .isEqualTo(R.string.biometric_dialog_face_icon_description_confirmed)
                assertThat(shouldAnimateIconView).isEqualTo(true)
            }

            // explicit flow because confirmation requested
            if (testCase.isCoex) {
                val iconOverlayAsset by collectLastValue(iconViewModel.iconOverlayAsset)
                val shouldAnimateIconOverlay by
                    collectLastValue(iconViewModel.shouldAnimateIconOverlay)

                // TODO: Update when SFPS co-ex is implemented
                if (testCase.sensorType != FingerprintSensorProperties.TYPE_POWER_BUTTON) {
                    assertThat(iconAsset)
                        .isEqualTo(R.raw.fingerprint_dialogue_unlocked_to_checkmark_success_lottie)
                    assertThat(iconOverlayAsset).isEqualTo(-1)
                    assertThat(iconContentDescriptionId)
                        .isEqualTo(R.string.fingerprint_dialog_touch_sensor)
                    assertThat(shouldAnimateIconView).isEqualTo(true)
                    assertThat(shouldAnimateIconOverlay).isEqualTo(false)
                }
            }
        }
    }

    @Test
    fun sfpsIconUpdates_onConfigurationChanged() = runGenericTest {
        if (testCase.sensorType == FingerprintSensorProperties.TYPE_POWER_BUTTON) {
            val testConfig = Configuration()
            val folded = INNER_SCREEN_SMALLEST_SCREEN_WIDTH_THRESHOLD_DP - 1
            val unfolded = INNER_SCREEN_SMALLEST_SCREEN_WIDTH_THRESHOLD_DP + 1
            val currentIcon by collectLastValue(iconViewModel.iconAsset)

            testConfig.smallestScreenWidthDp = folded
            iconViewModel.onConfigurationChanged(testConfig)
            val foldedIcon = currentIcon

            testConfig.smallestScreenWidthDp = unfolded
            iconViewModel.onConfigurationChanged(testConfig)
            val unfoldedIcon = currentIcon

            assertThat(foldedIcon).isNotEqualTo(unfoldedIcon)
        }
    }

    @Test
    fun sfpsIconUpdates_onRotation() = runGenericTest {
        if (testCase.sensorType == FingerprintSensorProperties.TYPE_POWER_BUTTON) {
            val currentIcon by collectLastValue(iconViewModel.iconAsset)

            displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_0)
            val iconRotation0 = currentIcon

            displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_90)
            val iconRotation90 = currentIcon

            displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_180)
            val iconRotation180 = currentIcon

            displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_270)
            val iconRotation270 = currentIcon

            assertThat(iconRotation0).isEqualTo(iconRotation180)
            assertThat(iconRotation0).isNotEqualTo(iconRotation90)
            assertThat(iconRotation0).isNotEqualTo(iconRotation270)
        }
    }

    @Test
    fun sfpsIconUpdates_onRearDisplayMode() = runGenericTest {
        if (testCase.sensorType == FingerprintSensorProperties.TYPE_POWER_BUTTON) {
            val currentIcon by collectLastValue(iconViewModel.iconAsset)

            displayStateRepository.setIsInRearDisplayMode(false)
            val iconNotRearDisplayMode = currentIcon

            displayStateRepository.setIsInRearDisplayMode(true)
            val iconRearDisplayMode = currentIcon

            assertThat(iconNotRearDisplayMode).isNotEqualTo(iconRearDisplayMode)
        }
    }

    private suspend fun TestScope.showAuthenticated(
        authenticatedModality: BiometricModality,
        expectConfirmation: Boolean,
    ) {
        val authenticating by collectLastValue(viewModel.isAuthenticating)
        val authenticated by collectLastValue(viewModel.isAuthenticated)
        val fpStartMode by collectLastValue(viewModel.fingerprintStartMode)
        val size by collectLastValue(viewModel.size)

        val authWithSmallPrompt =
            testCase.shouldStartAsImplicitFlow &&
                (fpStartMode == FingerprintStartMode.Pending || testCase.isFaceOnly)
        assertThat(authenticating).isTrue()
        assertThat(authenticated?.isNotAuthenticated).isTrue()
        assertThat(size).isEqualTo(if (authWithSmallPrompt) PromptSize.SMALL else PromptSize.MEDIUM)
        assertButtonsVisible(negative = !authWithSmallPrompt)

        viewModel.showAuthenticated(authenticatedModality, DELAY)

        assertThat(authenticated?.isAuthenticated).isTrue()
        assertThat(authenticated?.delay).isEqualTo(DELAY)
        assertThat(authenticated?.needsUserConfirmation).isEqualTo(expectConfirmation)
        assertThat(size)
            .isEqualTo(
                if (authenticatedModality == BiometricModality.Fingerprint || expectConfirmation) {
                    PromptSize.MEDIUM
                } else {
                    PromptSize.SMALL
                }
            )

        assertButtonsVisible(
            cancel = expectConfirmation,
            confirm = expectConfirmation,
        )
    }

    @Test
    fun shows_temporary_errors() = runGenericTest {
        val checkAtEnd = suspend { assertButtonsVisible(negative = true) }

        showTemporaryErrors(restart = false) { checkAtEnd() }
        showTemporaryErrors(restart = false, helpAfterError = "foo") { checkAtEnd() }
        showTemporaryErrors(restart = true) { checkAtEnd() }
    }

    @Test
    fun set_haptic_on_errors() = runGenericTest {
        viewModel.showTemporaryError(
            "so sad",
            messageAfterError = "",
            authenticateAfterError = false,
            hapticFeedback = true,
        )

        val haptics by collectLastValue(viewModel.hapticsToPlay)
        assertThat(haptics?.hapticFeedbackConstant).isEqualTo(HapticFeedbackConstants.REJECT)
        assertThat(haptics?.flag).isEqualTo(HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
    }

    @Test
    fun plays_haptic_on_errors_unless_skipped() = runGenericTest {
        viewModel.showTemporaryError(
            "still sad",
            messageAfterError = "",
            authenticateAfterError = false,
            hapticFeedback = false,
        )

        val haptics by collectLastValue(viewModel.hapticsToPlay)
        assertThat(haptics?.hapticFeedbackConstant).isEqualTo(HapticFeedbackConstants.NO_HAPTICS)
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

        // temporary error should disappear after a delay
        errorJob.join()
        if (helpAfterError.isNotBlank()) {
            assertThat(message).isEqualTo(PromptMessage.Help(helpAfterError))
            assertThat(messageVisible).isTrue()
        } else {
            assertThat(message).isEqualTo(PromptMessage.Empty)
            assertThat(messageVisible).isFalse()
        }

        assertThat(authenticating).isEqualTo(restart)
        assertThat(authenticated?.isNotAuthenticated).isTrue()
        assertThat(canTryAgainNow).isFalse()

        block()
    }

    @Test
    fun no_errors_or_temporary_help_after_authenticated() = runGenericTest {
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

        val errorJob = launch {
            viewModel.showTemporaryError(
                "error",
                messageAfterError = "",
                authenticateAfterError = false,
            )
        }
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

    @Test
    fun suppress_temporary_error() = runGenericTest {
        val messages by collectValues(viewModel.message)

        for (error in listOf("never", "see", "me")) {
            launch {
                viewModel.showTemporaryError(
                    error,
                    messageAfterError = "or me",
                    authenticateAfterError = false,
                    suppressIf = { _, _ -> true },
                )
            }
        }

        testScheduler.advanceUntilIdle()
        assertThat(messages).containsExactly(PromptMessage.Empty)
    }

    @Test
    fun suppress_temporary_error_when_already_showing_when_requested() =
        suppress_temporary_error_when_already_showing(suppress = true)

    @Test
    fun do_not_suppress_temporary_error_when_already_showing_when_not_requested() =
        suppress_temporary_error_when_already_showing(suppress = false)

    private fun suppress_temporary_error_when_already_showing(suppress: Boolean) = runGenericTest {
        val errors = listOf("woot", "oh yeah", "nope")
        val afterSuffix = "(after)"
        val expectedErrorMessage = if (suppress) errors.first() else errors.last()
        val messages by collectValues(viewModel.message)

        for (error in errors) {
            launch {
                viewModel.showTemporaryError(
                    error,
                    messageAfterError = "$error $afterSuffix",
                    authenticateAfterError = false,
                    suppressIf = { currentMessage, _ -> suppress && currentMessage.isError },
                )
            }
        }

        testScheduler.runCurrent()
        assertThat(messages)
            .containsExactly(
                PromptMessage.Empty,
                PromptMessage.Error(expectedErrorMessage),
            )
            .inOrder()

        testScheduler.advanceUntilIdle()
        assertThat(messages)
            .containsExactly(
                PromptMessage.Empty,
                PromptMessage.Error(expectedErrorMessage),
                PromptMessage.Help("$expectedErrorMessage $afterSuffix"),
            )
            .inOrder()
    }

    @Test
    fun authenticated_at_most_once() = runGenericTest {
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
    fun authenticating_cannot_restart_after_authenticated() = runGenericTest {
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
    fun confirm_authentication() = runGenericTest {
        val expectConfirmation = testCase.expectConfirmation(atLeastOneFailure = false)

        viewModel.showAuthenticated(testCase.authenticatedModality, 0)

        val authenticating by collectLastValue(viewModel.isAuthenticating)
        val authenticated by collectLastValue(viewModel.isAuthenticated)
        val message by collectLastValue(viewModel.message)
        val size by collectLastValue(viewModel.size)
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
        assertThat(canTryAgain).isFalse()
    }

    @Test
    fun auto_confirm_authentication_when_finger_down() = runGenericTest {
        val expectConfirmation = testCase.expectConfirmation(atLeastOneFailure = false)

        if (testCase.isCoex) {
            viewModel.onOverlayTouch(obtainMotionEvent(MotionEvent.ACTION_DOWN))
        }
        viewModel.showAuthenticated(testCase.authenticatedModality, 0)

        val authenticating by collectLastValue(viewModel.isAuthenticating)
        val authenticated by collectLastValue(viewModel.isAuthenticated)
        val message by collectLastValue(viewModel.message)
        val size by collectLastValue(viewModel.size)
        val canTryAgain by collectLastValue(viewModel.canTryAgainNow)

        assertThat(authenticating).isFalse()
        assertThat(canTryAgain).isFalse()
        assertThat(authenticated?.isAuthenticated).isTrue()

        if (expectConfirmation) {
            if (testCase.isFaceOnly) {
                assertThat(size).isEqualTo(PromptSize.MEDIUM)
                assertButtonsVisible(
                    cancel = true,
                    confirm = true,
                )

                viewModel.confirmAuthenticated()
            } else if (testCase.isCoex) {
                assertThat(authenticated?.isAuthenticatedAndConfirmed).isTrue()
            }
            assertThat(message).isEqualTo(PromptMessage.Empty)
            assertButtonsVisible()
        }
    }

    @Test
    fun cannot_auto_confirm_authentication_when_finger_up() = runGenericTest {
        val expectConfirmation = testCase.expectConfirmation(atLeastOneFailure = false)

        if (testCase.isCoex) {
            viewModel.onOverlayTouch(obtainMotionEvent(MotionEvent.ACTION_DOWN))
            viewModel.onOverlayTouch(obtainMotionEvent(MotionEvent.ACTION_UP))
        }
        viewModel.showAuthenticated(testCase.authenticatedModality, 0)

        val authenticating by collectLastValue(viewModel.isAuthenticating)
        val authenticated by collectLastValue(viewModel.isAuthenticated)
        val message by collectLastValue(viewModel.message)
        val size by collectLastValue(viewModel.size)
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
        assertThat(canTryAgain).isFalse()
    }

    @Test
    fun cannot_confirm_unless_authenticated() = runGenericTest {
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
    fun shows_help_before_authenticated() = runGenericTest {
        val helpMessage = "please help yourself to some cookies"
        val message by collectLastValue(viewModel.message)
        val messageVisible by collectLastValue(viewModel.isIndicatorMessageVisible)
        val size by collectLastValue(viewModel.size)

        viewModel.showHelp(helpMessage)

        assertThat(size).isEqualTo(PromptSize.MEDIUM)
        assertThat(message).isEqualTo(PromptMessage.Help(helpMessage))
        assertThat(messageVisible).isTrue()

        assertThat(viewModel.isAuthenticating.first()).isFalse()
        assertThat(viewModel.isAuthenticated.first().isNotAuthenticated).isTrue()
    }

    @Test
    fun shows_help_after_authenticated() = runGenericTest {
        val expectConfirmation = testCase.expectConfirmation(atLeastOneFailure = false)
        val helpMessage = "more cookies please"
        val authenticating by collectLastValue(viewModel.isAuthenticating)
        val authenticated by collectLastValue(viewModel.isAuthenticated)
        val message by collectLastValue(viewModel.message)
        val messageVisible by collectLastValue(viewModel.isIndicatorMessageVisible)
        val size by collectLastValue(viewModel.size)
        val confirmationRequired by collectLastValue(viewModel.isConfirmationRequired)

        if (testCase.isCoex && testCase.authenticatedByFingerprint) {
            viewModel.ensureFingerprintHasStarted(isDelayed = true)
        }
        viewModel.showAuthenticated(testCase.authenticatedModality, 0)
        viewModel.showHelp(helpMessage)

        assertThat(size).isEqualTo(PromptSize.MEDIUM)

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
    fun retries_after_failure() = runGenericTest {
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
    fun switch_to_credential_fallback() = runGenericTest {
        val size by collectLastValue(viewModel.size)

        // TODO(b/251476085): remove Spaghetti, migrate logic, and update this test
        viewModel.onSwitchToCredential()

        assertThat(size).isEqualTo(PromptSize.LARGE)
    }

    @Test
    @EnableFlags(FLAG_BP_TALKBACK)
    fun hint_for_talkback_guidance() = runGenericTest {
        val hint by collectLastValue(viewModel.accessibilityHint)

        // Touches should fall outside of sensor area
        whenever(udfpsUtils.getTouchInNativeCoordinates(any(), any(), any()))
            .thenReturn(Point(0, 0))
        whenever(udfpsUtils.onTouchOutsideOfSensorArea(any(), any(), any(), any(), any()))
            .thenReturn("Direction")

        viewModel.onAnnounceAccessibilityHint(
            obtainMotionEvent(MotionEvent.ACTION_HOVER_ENTER),
            true
        )

        if (testCase.modalities.hasUdfps) {
            assertThat(hint?.isNotBlank()).isTrue()
        } else {
            assertThat(hint.isNullOrBlank()).isTrue()
        }
    }

    @Test
    @EnableFlags(FLAG_CUSTOM_BIOMETRIC_PROMPT, FLAG_CONSTRAINT_BP)
    fun descriptionOverriddenByVerticalListContentView() =
        runGenericTest(description = "test description", contentView = promptContentView) {
            val contentView by collectLastValue(viewModel.contentView)
            val description by collectLastValue(viewModel.description)

            assertThat(description).isEqualTo("")
            assertThat(contentView).isEqualTo(promptContentView)
        }

    @Test
    @EnableFlags(FLAG_CUSTOM_BIOMETRIC_PROMPT, FLAG_CONSTRAINT_BP)
    fun descriptionOverriddenByContentViewWithMoreOptionsButton() =
        runGenericTest(
            description = "test description",
            contentView = promptContentViewWithMoreOptionsButton
        ) {
            val contentView by collectLastValue(viewModel.contentView)
            val description by collectLastValue(viewModel.description)

            assertThat(description).isEqualTo("")
            assertThat(contentView).isEqualTo(promptContentViewWithMoreOptionsButton)
        }

    @Test
    @EnableFlags(FLAG_CUSTOM_BIOMETRIC_PROMPT, FLAG_CONSTRAINT_BP)
    fun descriptionWithoutContentView() =
        runGenericTest(description = "test description") {
            val contentView by collectLastValue(viewModel.contentView)
            val description by collectLastValue(viewModel.description)

            assertThat(description).isEqualTo("test description")
            assertThat(contentView).isNull()
        }

    @Test
    @EnableFlags(FLAG_CUSTOM_BIOMETRIC_PROMPT, FLAG_CONSTRAINT_BP)
    fun logo_nullIfPkgNameNotFound() =
        runGenericTest(packageName = OP_PACKAGE_NAME_CAN_NOT_BE_FOUND) {
            val logo by collectLastValue(viewModel.logo)
            assertThat(logo).isNull()
        }

    @Test
    @EnableFlags(FLAG_CUSTOM_BIOMETRIC_PROMPT, FLAG_CONSTRAINT_BP)
    fun logo_defaultWithOverrides() =
        runGenericTest(packageName = packageNameForLogoWithOverrides) {
            val logo by collectLastValue(viewModel.logo)

            // 1. PM.getApplicationInfo(packageNameForLogoWithOverrides) is set to return
            // applicationInfoWithIcon with defaultLogoIcon,
            // 2. iconProvider.getIcon() is set to return defaultLogoIconForGMSCore
            // For the apps with packageNameForLogoWithOverrides, 2 should be called instead of 1
            assertThat(logo).isEqualTo(defaultLogoIconWithOverrides)
        }

    @Test
    @EnableFlags(FLAG_CUSTOM_BIOMETRIC_PROMPT, FLAG_CONSTRAINT_BP)
    fun logo_defaultIsNull() =
        runGenericTest(packageName = OP_PACKAGE_NAME_NO_ICON) {
            val logo by collectLastValue(viewModel.logo)
            assertThat(logo).isNull()
        }

    @Test
    @EnableFlags(FLAG_CUSTOM_BIOMETRIC_PROMPT, FLAG_CONSTRAINT_BP)
    fun logo_default() = runGenericTest {
        val logo by collectLastValue(viewModel.logo)
        assertThat(logo).isEqualTo(defaultLogoIcon)
    }

    @Test
    @EnableFlags(FLAG_CUSTOM_BIOMETRIC_PROMPT, FLAG_CONSTRAINT_BP)
    fun logo_resSetByApp() =
        runGenericTest(logoRes = logoResFromApp) {
            val expectedBitmap = context.getDrawable(logoResFromApp).toBitmap()
            val logo by collectLastValue(viewModel.logo)
            assertThat((logo as BitmapDrawable).bitmap.sameAs(expectedBitmap)).isTrue()
        }

    @Test
    @EnableFlags(FLAG_CUSTOM_BIOMETRIC_PROMPT, FLAG_CONSTRAINT_BP)
    fun logo_bitmapSetByApp() =
        runGenericTest(logoBitmap = logoBitmapFromApp) {
            val logo by collectLastValue(viewModel.logo)
            assertThat((logo as BitmapDrawable).bitmap).isEqualTo(logoBitmapFromApp)
        }

    @Test
    @EnableFlags(FLAG_CUSTOM_BIOMETRIC_PROMPT, FLAG_CONSTRAINT_BP)
    fun logoDescription_emptyIfPkgNameNotFound() =
        runGenericTest(packageName = OP_PACKAGE_NAME_CAN_NOT_BE_FOUND) {
            val logoDescription by collectLastValue(viewModel.logoDescription)
            assertThat(logoDescription).isEqualTo("")
        }

    @Test
    @EnableFlags(FLAG_CUSTOM_BIOMETRIC_PROMPT, FLAG_CONSTRAINT_BP)
    fun logoDescription_defaultIsEmpty() =
        runGenericTest(packageName = OP_PACKAGE_NAME_NO_ICON) {
            val logoDescription by collectLastValue(viewModel.logoDescription)
            assertThat(logoDescription).isEqualTo("")
        }

    @Test
    @EnableFlags(FLAG_CUSTOM_BIOMETRIC_PROMPT, FLAG_CONSTRAINT_BP)
    fun logoDescription_default() = runGenericTest {
        val logoDescription by collectLastValue(viewModel.logoDescription)
        assertThat(logoDescription).isEqualTo(defaultLogoDescription)
    }

    @Test
    @EnableFlags(FLAG_CUSTOM_BIOMETRIC_PROMPT, FLAG_CONSTRAINT_BP)
    fun logoDescription_setByApp() =
        runGenericTest(logoDescription = logoDescriptionFromApp) {
            val logoDescription by collectLastValue(viewModel.logoDescription)
            assertThat(logoDescription).isEqualTo(logoDescriptionFromApp)
        }

    @Test
    @EnableFlags(FLAG_CONSTRAINT_BP)
    fun position_bottom_rotation0() = runGenericTest {
        displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_0)
        val position by collectLastValue(viewModel.position)
        assertThat(position).isEqualTo(PromptPosition.Bottom)
    } // TODO(b/335278136): Add test for no sensor landscape

    @Test
    @EnableFlags(FLAG_CONSTRAINT_BP)
    fun position_bottom_forceLarge() = runGenericTest {
        displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_270)
        viewModel.onSwitchToCredential()
        val position by collectLastValue(viewModel.position)
        assertThat(position).isEqualTo(PromptPosition.Bottom)
    }

    @Test
    @EnableFlags(FLAG_CONSTRAINT_BP)
    fun position_bottom_largeScreen() = runGenericTest {
        displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_270)
        displayStateRepository.setIsLargeScreen(true)
        val position by collectLastValue(viewModel.position)
        assertThat(position).isEqualTo(PromptPosition.Bottom)
    }

    @Test
    @EnableFlags(FLAG_CONSTRAINT_BP)
    fun position_right_rotation90() = runGenericTest {
        displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_90)
        val position by collectLastValue(viewModel.position)
        assertThat(position).isEqualTo(PromptPosition.Right)
    }

    @Test
    @EnableFlags(FLAG_CONSTRAINT_BP)
    fun position_left_rotation270() = runGenericTest {
        displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_270)
        val position by collectLastValue(viewModel.position)
        assertThat(position).isEqualTo(PromptPosition.Left)
    }

    @Test
    @EnableFlags(FLAG_CONSTRAINT_BP)
    fun position_top_rotation180() = runGenericTest {
        displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_180)
        val position by collectLastValue(viewModel.position)
        if (testCase.modalities.hasUdfps) {
            assertThat(position).isEqualTo(PromptPosition.Top)
        } else {
            assertThat(position).isEqualTo(PromptPosition.Bottom)
        }
    }

    @Test
    @EnableFlags(FLAG_CONSTRAINT_BP)
    fun guideline_bottom() = runGenericTest {
        displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_0)
        val guidelineBounds by collectLastValue(viewModel.guidelineBounds)
        assertThat(guidelineBounds).isEqualTo(Rect(0, mediumTopGuidelinePadding, 0, 0))
    } // TODO(b/335278136): Add test for no sensor landscape

    @Test
    @EnableFlags(FLAG_CONSTRAINT_BP)
    fun guideline_right() = runGenericTest {
        displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_90)

        val isSmall = testCase.shouldStartAsImplicitFlow
        val guidelineBounds by collectLastValue(viewModel.guidelineBounds)

        if (isSmall) {
            assertThat(guidelineBounds).isEqualTo(Rect(-smallHorizontalGuidelinePadding, 0, 0, 0))
        } else if (testCase.modalities.hasUdfps) {
            assertThat(guidelineBounds).isEqualTo(Rect(udfpsHorizontalGuidelinePadding, 0, 0, 0))
        } else {
            assertThat(guidelineBounds).isEqualTo(Rect(-mediumHorizontalGuidelinePadding, 0, 0, 0))
        }
    }

    @Test
    @EnableFlags(FLAG_CONSTRAINT_BP)
    fun guideline_right_onlyShortTitle() =
        runGenericTest(subtitle = "") {
            displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_90)

            val isSmall = testCase.shouldStartAsImplicitFlow
            val guidelineBounds by collectLastValue(viewModel.guidelineBounds)

            if (!isSmall && testCase.modalities.hasUdfps) {
                assertThat(guidelineBounds)
                    .isEqualTo(Rect(-udfpsHorizontalShorterGuidelinePadding, 0, 0, 0))
            }
        }

    @Test
    @EnableFlags(FLAG_CONSTRAINT_BP)
    fun guideline_left() = runGenericTest {
        displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_270)

        val isSmall = testCase.shouldStartAsImplicitFlow
        val guidelineBounds by collectLastValue(viewModel.guidelineBounds)

        if (isSmall) {
            assertThat(guidelineBounds).isEqualTo(Rect(0, 0, -smallHorizontalGuidelinePadding, 0))
        } else if (testCase.modalities.hasUdfps) {
            assertThat(guidelineBounds).isEqualTo(Rect(0, 0, udfpsHorizontalGuidelinePadding, 0))
        } else {
            assertThat(guidelineBounds).isEqualTo(Rect(0, 0, -mediumHorizontalGuidelinePadding, 0))
        }
    }

    @Test
    @EnableFlags(FLAG_CONSTRAINT_BP)
    fun guideline_left_onlyShortTitle() =
        runGenericTest(subtitle = "") {
            displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_270)

            val isSmall = testCase.shouldStartAsImplicitFlow
            val guidelineBounds by collectLastValue(viewModel.guidelineBounds)

            if (!isSmall && testCase.modalities.hasUdfps) {
                assertThat(guidelineBounds)
                    .isEqualTo(Rect(0, 0, -udfpsHorizontalShorterGuidelinePadding, 0))
            }
        }

    @Test
    @EnableFlags(FLAG_CONSTRAINT_BP)
    fun guideline_top() = runGenericTest {
        displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_180)
        val guidelineBounds by collectLastValue(viewModel.guidelineBounds)
        if (testCase.modalities.hasUdfps) {
            assertThat(guidelineBounds).isEqualTo(Rect(0, 0, 0, 0))
        }
    }

    @Test
    fun iconViewLoaded() = runGenericTest {
        val isIconViewLoaded by collectLastValue(viewModel.isIconViewLoaded)
        // TODO(b/328677869): Add test for noIcon logic.
        assertThat(isIconViewLoaded).isFalse()

        viewModel.setIsIconViewLoaded(true)

        assertThat(isIconViewLoaded).isTrue()
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
        subtitle: String? = "s",
        description: String? = null,
        contentView: PromptContentView? = null,
        logoRes: Int = -1,
        logoBitmap: Bitmap? = null,
        logoDescription: String? = null,
        packageName: String = OP_PACKAGE_NAME,
        block: suspend TestScope.() -> Unit,
    ) {
        val topActivity = ComponentName(packageName, "test app")
        runningTaskInfo.topActivity = topActivity
        whenever(activityTaskManager.getTasks(1)).thenReturn(listOf(runningTaskInfo))
        selector =
            PromptSelectorInteractorImpl(
                fingerprintRepository,
                displayStateInteractor,
                promptRepository,
                lockPatternUtils
            )
        selector.resetPrompt(REQUEST_ID)

        viewModel =
            PromptViewModel(
                displayStateInteractor,
                selector,
                mContext,
                udfpsOverlayInteractor,
                biometricStatusInteractor,
                udfpsUtils,
                iconProvider,
                activityTaskManager
            )
        iconViewModel = viewModel.iconViewModel

        selector.initializePrompt(
            requireConfirmation = testCase.confirmationRequested,
            allowCredentialFallback = allowCredentialFallback,
            fingerprint = testCase.fingerprint,
            face = testCase.face,
            subtitleFromApp = subtitle,
            descriptionFromApp = description,
            contentViewFromApp = contentView,
            logoResFromApp = logoRes,
            logoBitmapFromApp =
                if (logoRes != -1) logoDrawableFromAppRes.toBitmap() else logoBitmap,
            logoDescriptionFromApp = logoDescription,
            packageName = packageName,
        )

        biometricStatusRepository.setFingerprintAcquiredStatus(
            AcquiredFingerprintAuthenticationStatus(
                AuthenticationReason.BiometricPromptAuthentication,
                BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_UNKNOWN
            )
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

    /** Obtain a MotionEvent with the specified MotionEvent action constant */
    private fun obtainMotionEvent(action: Int): MotionEvent =
        MotionEvent.obtain(0, 0, action, 0f, 0f, 0)

    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun data(): Collection<TestCase> = singleModalityTestCases + coexTestCases

        private val singleModalityTestCases =
            listOf(
                TestCase(
                    face = faceSensorPropertiesInternal(strong = true).first(),
                    authenticatedModality = BiometricModality.Face,
                ),
                TestCase(
                    fingerprint =
                        fingerprintSensorPropertiesInternal(
                                strong = true,
                                sensorType = FingerprintSensorProperties.TYPE_POWER_BUTTON
                            )
                            .first(),
                    authenticatedModality = BiometricModality.Fingerprint,
                ),
                TestCase(
                    fingerprint =
                        fingerprintSensorPropertiesInternal(
                                strong = true,
                                sensorType = FingerprintSensorProperties.TYPE_UDFPS_OPTICAL
                            )
                            .first(),
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
                TestCase(
                    fingerprint =
                        fingerprintSensorPropertiesInternal(
                                strong = true,
                                sensorType = FingerprintSensorProperties.TYPE_POWER_BUTTON
                            )
                            .first(),
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
                    authenticatedModality = BiometricModality.Face,
                    confirmationRequested = true,
                ),
                TestCase(
                    face = faceSensorPropertiesInternal(strong = true).first(),
                    fingerprint =
                        fingerprintSensorPropertiesInternal(
                                strong = true,
                                sensorType = FingerprintSensorProperties.TYPE_POWER_BUTTON
                            )
                            .first(),
                    authenticatedModality = BiometricModality.Fingerprint,
                    confirmationRequested = true,
                ),
                TestCase(
                    face = faceSensorPropertiesInternal(strong = true).first(),
                    fingerprint =
                        fingerprintSensorPropertiesInternal(
                                strong = true,
                                sensorType = FingerprintSensorProperties.TYPE_UDFPS_OPTICAL
                            )
                            .first(),
                    authenticatedModality = BiometricModality.Fingerprint,
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
                fingerprint != null && fingerprint.isAnySidefpsType -> "fingerprint only, sideFps"
                fingerprint != null && !fingerprint.isAnySidefpsType ->
                    "fingerprint only, non-sideFps"
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

    val modalities: BiometricModalities
        get() = BiometricModalities(fingerprint, face)

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

    @FingerprintSensorProperties.SensorType val sensorType: Int? = fingerprint?.sensorType

    val shouldStartAsImplicitFlow: Boolean
        get() = (isFaceOnly || isCoex) && !confirmationRequested
}

/** Initialize the test by selecting the give [fingerprint] or [face] configuration(s). */
private fun PromptSelectorInteractor.initializePrompt(
    fingerprint: FingerprintSensorPropertiesInternal? = null,
    face: FaceSensorPropertiesInternal? = null,
    requireConfirmation: Boolean = false,
    allowCredentialFallback: Boolean = false,
    subtitleFromApp: String? = "s",
    descriptionFromApp: String? = null,
    contentViewFromApp: PromptContentView? = null,
    logoResFromApp: Int = -1,
    logoBitmapFromApp: Bitmap? = null,
    logoDescriptionFromApp: String? = null,
    packageName: String = OP_PACKAGE_NAME,
) {
    val info =
        PromptInfo().apply {
            logoDescription = logoDescriptionFromApp
            title = "t"
            subtitle = subtitleFromApp
            description = descriptionFromApp
            contentView = contentViewFromApp
            authenticators = listOf(face, fingerprint).extractAuthenticatorTypes()
            isDeviceCredentialAllowed = allowCredentialFallback
            isConfirmationRequested = requireConfirmation
        }
    if (logoBitmapFromApp != null) {
        info.setLogo(logoResFromApp, logoBitmapFromApp)
    }

    setPrompt(
        info,
        USER_ID,
        REQUEST_ID,
        BiometricModalities(fingerprintProperties = fingerprint, faceProperties = face),
        CHALLENGE,
        packageName,
        onSwitchToCredential = false,
        isLandscape = false,
    )
}

internal const val INNER_SCREEN_SMALLEST_SCREEN_WIDTH_THRESHOLD_DP = 600
