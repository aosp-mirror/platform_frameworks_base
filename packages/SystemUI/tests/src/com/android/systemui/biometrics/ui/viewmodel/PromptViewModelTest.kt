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
import android.content.ComponentName
import android.content.applicationContext
import android.content.packageManager
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager.NameNotFoundException
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
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.Surface
import androidx.test.filters.SmallTest
import com.android.app.activityTaskManager
import com.android.keyguard.AuthInteractionProperties
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.AuthController
import com.android.systemui.biometrics.Utils.toBitmap
import com.android.systemui.biometrics.authController
import com.android.systemui.biometrics.data.repository.biometricStatusRepository
import com.android.systemui.biometrics.data.repository.fakeFingerprintPropertyRepository
import com.android.systemui.biometrics.domain.interactor.PromptSelectorInteractor
import com.android.systemui.biometrics.domain.interactor.promptSelectorInteractor
import com.android.systemui.biometrics.domain.interactor.udfpsOverlayInteractor
import com.android.systemui.biometrics.extractAuthenticatorTypes
import com.android.systemui.biometrics.faceSensorPropertiesInternal
import com.android.systemui.biometrics.fingerprintSensorPropertiesInternal
import com.android.systemui.biometrics.shared.model.AuthenticationReason
import com.android.systemui.biometrics.shared.model.BiometricModalities
import com.android.systemui.biometrics.shared.model.BiometricModality
import com.android.systemui.biometrics.shared.model.DisplayRotation
import com.android.systemui.biometrics.shared.model.UdfpsOverlayParams
import com.android.systemui.biometrics.shared.model.toSensorStrength
import com.android.systemui.biometrics.shared.model.toSensorType
import com.android.systemui.biometrics.udfpsUtils
import com.android.systemui.concurrency.fakeExecutor
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.display.data.repository.displayStateRepository
import com.android.systemui.keyguard.shared.model.AcquiredFingerprintAuthenticationStatus
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
import com.android.systemui.util.mockito.withArgCaptor
import com.google.android.msdl.data.model.MSDLToken
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
import org.mockito.Mockito
import org.mockito.junit.MockitoJUnit
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

private const val USER_ID = 4
private const val REQUEST_ID = 4L
private const val CHALLENGE = 2L
private const val DELAY = 1000L
private const val OP_PACKAGE_NAME_WITH_APP_LOGO = "biometric.testapp"
private const val OP_PACKAGE_NAME_NO_ICON = "biometric.testapp.noicon"
private const val OP_PACKAGE_NAME_CAN_NOT_BE_FOUND = "can.not.be.found"
private const val OP_PACKAGE_NAME_WITH_ACTIVITY_LOGO = "should.use.activiy.logo"

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
internal class PromptViewModelTest(private val testCase: TestCase) : SysuiTestCase() {

    @JvmField @Rule var mockitoRule = MockitoJUnit.rule()

    @Mock private lateinit var authController: AuthController
    @Mock private lateinit var applicationInfoWithIconAndDescription: ApplicationInfo
    @Mock private lateinit var applicationInfoNoIconOrDescription: ApplicationInfo
    @Mock private lateinit var activityInfo: ActivityInfo
    @Mock private lateinit var runningTaskInfo: RunningTaskInfo

    private val defaultLogoIconFromAppInfo = context.getDrawable(R.drawable.ic_android)
    private val defaultLogoIconFromActivityInfo = context.getDrawable(R.drawable.ic_add)
    private val logoResFromApp = R.drawable.ic_cake
    private val logoDrawableFromAppRes = context.getDrawable(logoResFromApp)
    private val logoBitmapFromApp = Bitmap.createBitmap(400, 400, Bitmap.Config.RGB_565)
    private val defaultLogoDescriptionFromAppInfo = "Test Android App"
    private val defaultLogoDescriptionFromActivityInfo = "Test Coke App"
    private val logoDescriptionFromApp = "Test Cake App"
    private val packageNameForLogoWithOverrides = "should.use.overridden.logo"
    private val authInteractionProperties = AuthInteractionProperties()

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
    private val mockFaceIconSize = 200
    private val mockFingerprintIconWidth = 300
    private val mockFingerprintIconHeight = 300

    /** Mock [UdfpsOverlayParams] for a test. */
    private fun mockUdfpsOverlayParams(isLandscape: Boolean = false): UdfpsOverlayParams =
        UdfpsOverlayParams(
            sensorBounds = Rect(400, 1600, 600, 1800),
            overlayBounds = Rect(0, 1200, 1000, 2400),
            naturalDisplayWidth = 1000,
            naturalDisplayHeight = 3000,
            scaleFactor = 1f,
            rotation = if (isLandscape) Surface.ROTATION_90 else Surface.ROTATION_0
        )

    private lateinit var promptContentView: PromptContentView
    private lateinit var promptContentViewWithMoreOptionsButton:
        PromptContentViewWithMoreOptionsButton

    private val kosmos = Kosmos()

    @Before
    fun setup() {
        // Set up default logo info and app customized info
        whenever(kosmos.packageManager.getApplicationInfo(eq(OP_PACKAGE_NAME_NO_ICON), anyInt()))
            .thenReturn(applicationInfoNoIconOrDescription)
        whenever(
                kosmos.packageManager.getApplicationInfo(
                    eq(OP_PACKAGE_NAME_WITH_APP_LOGO),
                    anyInt()
                )
            )
            .thenReturn(applicationInfoWithIconAndDescription)
        whenever(
                kosmos.packageManager.getApplicationInfo(
                    eq(OP_PACKAGE_NAME_WITH_ACTIVITY_LOGO),
                    anyInt()
                )
            )
            .thenReturn(applicationInfoWithIconAndDescription)
        whenever(
                kosmos.packageManager.getApplicationInfo(
                    eq(OP_PACKAGE_NAME_CAN_NOT_BE_FOUND),
                    anyInt()
                )
            )
            .thenThrow(NameNotFoundException())

        whenever(kosmos.packageManager.getActivityInfo(any(), anyInt())).thenReturn(activityInfo)
        whenever(kosmos.iconProvider.getIcon(activityInfo))
            .thenReturn(defaultLogoIconFromActivityInfo)
        whenever(activityInfo.loadLabel(kosmos.packageManager))
            .thenReturn(defaultLogoDescriptionFromActivityInfo)

        whenever(kosmos.packageManager.getApplicationIcon(applicationInfoWithIconAndDescription))
            .thenReturn(defaultLogoIconFromAppInfo)
        whenever(kosmos.packageManager.getApplicationLabel(applicationInfoWithIconAndDescription))
            .thenReturn(defaultLogoDescriptionFromAppInfo)
        whenever(kosmos.packageManager.getApplicationIcon(applicationInfoNoIconOrDescription))
            .thenReturn(null)
        whenever(kosmos.packageManager.getApplicationLabel(applicationInfoNoIconOrDescription))
            .thenReturn("")
        whenever(kosmos.packageManager.getUserBadgedIcon(any(), any())).then { it.getArgument(0) }
        whenever(kosmos.packageManager.getUserBadgedLabel(any(), any())).then { it.getArgument(0) }

        context.setMockPackageManager(kosmos.packageManager)
        overrideResource(logoResFromApp, logoDrawableFromAppRes)
        overrideResource(
            R.array.config_useActivityLogoForBiometricPrompt,
            arrayOf(OP_PACKAGE_NAME_WITH_ACTIVITY_LOGO)
        )

        overrideResource(R.dimen.biometric_dialog_fingerprint_icon_width, mockFingerprintIconWidth)
        overrideResource(
            R.dimen.biometric_dialog_fingerprint_icon_height,
            mockFingerprintIconHeight
        )
        overrideResource(R.dimen.biometric_dialog_face_icon_size, mockFaceIconSize)

        kosmos.applicationContext = context

        if (testCase.fingerprint?.isAnyUdfpsType == true) {
            kosmos.authController = authController
        }

        testCase.fingerprint?.let {
            kosmos.fakeFingerprintPropertyRepository.setProperties(
                it.sensorId,
                it.sensorStrength.toSensorStrength(),
                it.sensorType.toSensorType(),
                it.allLocations.associateBy { sensorLocationInternal ->
                    sensorLocationInternal.displayId
                }
            )
        }

        kosmos.displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_0)
        testCase.fingerprint?.isAnySidefpsType.let {
            kosmos.displayStateRepository.setIsInRearDisplayMode(testCase.isInRearDisplayMode)
        }

        promptContentView =
            PromptVerticalListContentView.Builder()
                .addListItem(PromptContentItemBulletedText("content item 1"))
                .addListItem(PromptContentItemBulletedText("content item 2"), 1)
                .build()

        promptContentViewWithMoreOptionsButton =
            PromptContentViewWithMoreOptionsButton.Builder()
                .setDescription("test")
                .setMoreOptionsButtonListener(kosmos.fakeExecutor) { _, _ -> }
                .build()
    }

    @Test
    fun start_idle_and_show_authenticating() =
        runGenericTest(doNotStart = true) {
            var expectedPromptSize =
                if (testCase.shouldStartAsImplicitFlow) PromptSize.SMALL else PromptSize.MEDIUM
            val authenticating by collectLastValue(kosmos.promptViewModel.isAuthenticating)
            val authenticated by collectLastValue(kosmos.promptViewModel.isAuthenticated)
            val modalities by collectLastValue(kosmos.promptViewModel.modalities)
            val iconAsset by collectLastValue(kosmos.promptViewModel.iconViewModel.iconAsset)
            val shouldAnimateIconView by
                collectLastValue(kosmos.promptViewModel.iconViewModel.shouldAnimateIconView)
            val iconContentDescriptionId by
                collectLastValue(kosmos.promptViewModel.iconViewModel.contentDescriptionId)
            val message by collectLastValue(kosmos.promptViewModel.message)
            val size by collectLastValue(kosmos.promptViewModel.size)

            assertThat(authenticating).isFalse()
            assertThat(authenticated?.isNotAuthenticated).isTrue()
            with(modalities ?: throw Exception("missing modalities")) {
                assertThat(hasFace).isEqualTo(testCase.face != null)
                assertThat(hasFingerprint).isEqualTo(testCase.fingerprint != null)
            }

            assertThat(message).isEqualTo(PromptMessage.Empty)
            assertThat(size).isEqualTo(expectedPromptSize)

            val forceExplicitFlow =
                testCase.isCoex && testCase.confirmationRequested ||
                    testCase.authenticatedByFingerprint

            if ((testCase.isCoex && !forceExplicitFlow) || testCase.isFaceOnly) {
                // Face-only or implicit co-ex auth
                assertThat(iconAsset).isEqualTo(R.raw.face_dialog_idle_static)
                assertThat(shouldAnimateIconView).isEqualTo(false)
            }

            if (forceExplicitFlow) {
                expectedPromptSize = PromptSize.MEDIUM
                kosmos.promptViewModel.ensureFingerprintHasStarted(isDelayed = true)
            }

            val startMessage = "here we go"
            kosmos.promptViewModel.showAuthenticating(startMessage, isRetry = false)
            verifyIconSize(forceExplicitFlow)

            // Icon asset assertions
            if ((testCase.isCoex && !forceExplicitFlow) || testCase.isFaceOnly) {
                // Face-only or implicit co-ex auth
                assertThat(iconAsset).isEqualTo(R.raw.face_dialog_authenticating)
                assertThat(iconContentDescriptionId)
                    .isEqualTo(R.string.biometric_dialog_face_icon_description_authenticating)
                assertThat(shouldAnimateIconView).isEqualTo(true)
            } else if ((testCase.isCoex && forceExplicitFlow) || testCase.isFingerprintOnly) {
                // Fingerprint-only or explicit co-ex auth
                if (testCase.sensorType == FingerprintSensorProperties.TYPE_POWER_BUTTON) {
                    assertThat(iconAsset).isEqualTo(getSfpsAsset_fingerprintAuthenticating())
                    assertThat(iconContentDescriptionId)
                        .isEqualTo(R.string.security_settings_sfps_enroll_find_sensor_message)
                    assertThat(shouldAnimateIconView).isEqualTo(true)
                } else {
                    assertThat(iconAsset)
                        .isEqualTo(R.raw.fingerprint_dialogue_fingerprint_to_error_lottie)
                    assertThat(iconContentDescriptionId)
                        .isEqualTo(R.string.fingerprint_dialog_touch_sensor)
                    assertThat(shouldAnimateIconView).isEqualTo(false)
                }
            }

            assertThat(message).isEqualTo(PromptMessage.Help(startMessage))
            assertThat(authenticating).isTrue()
            assertThat(authenticated?.isNotAuthenticated).isTrue()
            assertThat(size).isEqualTo(expectedPromptSize)
            assertButtonsVisible(negative = expectedPromptSize != PromptSize.SMALL)
        }

    @Test
    fun start_authenticating_show_and_clear_error() = runGenericTest {
        val iconAsset by collectLastValue(kosmos.promptViewModel.iconViewModel.iconAsset)
        val iconContentDescriptionId by
            collectLastValue(kosmos.promptViewModel.iconViewModel.contentDescriptionId)
        val shouldAnimateIconView by
            collectLastValue(kosmos.promptViewModel.iconViewModel.shouldAnimateIconView)
        val message by collectLastValue(kosmos.promptViewModel.message)

        var forceExplicitFlow =
            testCase.isCoex && testCase.confirmationRequested || testCase.authenticatedByFingerprint
        if (forceExplicitFlow) {
            kosmos.promptViewModel.ensureFingerprintHasStarted(isDelayed = true)
        }
        verifyIconSize(forceExplicitFlow)

        val errorJob = launch {
            kosmos.promptViewModel.showTemporaryError(
                "so sad",
                messageAfterError = "",
                authenticateAfterError = testCase.isFingerprintOnly || testCase.isCoex,
            )
            forceExplicitFlow = true
            // Usually done by binder
            kosmos.promptViewModel.iconViewModel.setPreviousIconWasError(true)
        }

        assertThat(message?.isError).isEqualTo(true)
        assertThat(message?.message).isEqualTo("so sad")

        // Icon asset assertions
        if (testCase.isFaceOnly) {
            // Face-only auth
            assertThat(iconAsset).isEqualTo(R.raw.face_dialog_dark_to_error)
            assertThat(iconContentDescriptionId).isEqualTo(R.string.keyguard_face_failed)
            assertThat(shouldAnimateIconView).isEqualTo(true)

            // Clear error, go to idle
            errorJob.join()

            assertThat(iconAsset).isEqualTo(R.raw.face_dialog_error_to_idle)
            assertThat(iconContentDescriptionId)
                .isEqualTo(R.string.biometric_dialog_face_icon_description_idle)
            assertThat(shouldAnimateIconView).isEqualTo(true)
        } else if ((testCase.isCoex && forceExplicitFlow) || testCase.isFingerprintOnly) {
            // Fingerprint-only or explicit co-ex auth
            if (testCase.sensorType == FingerprintSensorProperties.TYPE_POWER_BUTTON) {
                assertThat(iconAsset).isEqualTo(getSfpsAsset_fingerprintToError())
                assertThat(iconContentDescriptionId).isEqualTo(R.string.biometric_dialog_try_again)
                assertThat(shouldAnimateIconView).isEqualTo(true)
            } else {
                assertThat(iconAsset)
                    .isEqualTo(R.raw.fingerprint_dialogue_fingerprint_to_error_lottie)
                assertThat(iconContentDescriptionId).isEqualTo(R.string.biometric_dialog_try_again)
                assertThat(shouldAnimateIconView).isEqualTo(true)
            }

            // Clear error, restart authenticating
            errorJob.join()

            if (testCase.sensorType == FingerprintSensorProperties.TYPE_POWER_BUTTON) {
                assertThat(iconAsset).isEqualTo(getSfpsAsset_errorToFingerprint())
                assertThat(iconContentDescriptionId)
                    .isEqualTo(R.string.security_settings_sfps_enroll_find_sensor_message)
                assertThat(shouldAnimateIconView).isEqualTo(true)
            } else {
                assertThat(iconAsset)
                    .isEqualTo(R.raw.fingerprint_dialogue_error_to_fingerprint_lottie)
                assertThat(iconContentDescriptionId)
                    .isEqualTo(R.string.fingerprint_dialog_touch_sensor)
                assertThat(shouldAnimateIconView).isEqualTo(true)
            }
        }
    }

    @Test
    fun shows_error_to_unlock_or_success() {
        // Face-only auth does not use error -> unlock or error -> success assets
        if (testCase.isFingerprintOnly || testCase.isCoex) {
            runGenericTest {
                // Distinct asset for error -> success only applicable for fingerprint-only /
                // explicit co-ex auth
                val iconAsset by collectLastValue(kosmos.promptViewModel.iconViewModel.iconAsset)
                val iconContentDescriptionId by
                    collectLastValue(kosmos.promptViewModel.iconViewModel.contentDescriptionId)
                val shouldAnimateIconView by
                    collectLastValue(kosmos.promptViewModel.iconViewModel.shouldAnimateIconView)

                var forceExplicitFlow =
                    testCase.isCoex && testCase.confirmationRequested ||
                        testCase.authenticatedByFingerprint
                if (forceExplicitFlow) {
                    kosmos.promptViewModel.ensureFingerprintHasStarted(isDelayed = true)
                }
                verifyIconSize(forceExplicitFlow)

                kosmos.promptViewModel.ensureFingerprintHasStarted(isDelayed = true)
                kosmos.promptViewModel.iconViewModel.setPreviousIconWasError(true)

                kosmos.promptViewModel.showAuthenticated(
                    modality = testCase.authenticatedModality,
                    dismissAfterDelay = DELAY
                )

                // SFPS test cases
                if (testCase.sensorType == FingerprintSensorProperties.TYPE_POWER_BUTTON) {
                    // Covers (1) fingerprint-only (2) co-ex, authenticated by fingerprint
                    if (testCase.authenticatedByFingerprint) {
                        assertThat(iconAsset).isEqualTo(R.raw.biometricprompt_sfps_error_to_success)
                        assertThat(iconContentDescriptionId)
                            .isEqualTo(R.string.security_settings_sfps_enroll_find_sensor_message)
                        assertThat(shouldAnimateIconView).isEqualTo(true)
                    } else { // Covers co-ex, authenticated by face
                        assertThat(iconAsset).isEqualTo(R.raw.biometricprompt_sfps_error_to_unlock)
                        assertThat(iconContentDescriptionId)
                            .isEqualTo(R.string.fingerprint_dialog_authenticated_confirmation)
                        assertThat(shouldAnimateIconView).isEqualTo(true)

                        // Confirm authentication
                        kosmos.promptViewModel.confirmAuthenticated()

                        assertThat(iconAsset)
                            .isEqualTo(R.raw.biometricprompt_sfps_unlock_to_success)
                        assertThat(iconContentDescriptionId)
                            .isEqualTo(R.string.fingerprint_dialog_touch_sensor)
                        assertThat(shouldAnimateIconView).isEqualTo(true)
                    }
                } else { // Non-SFPS (UDFPS / rear-FPS) test cases
                    // Covers (1) fingerprint-only (2) co-ex, authenticated by fingerprint
                    if (testCase.authenticatedByFingerprint) {
                        assertThat(iconAsset)
                            .isEqualTo(R.raw.fingerprint_dialogue_error_to_success_lottie)
                        assertThat(iconContentDescriptionId)
                            .isEqualTo(R.string.fingerprint_dialog_touch_sensor)
                        assertThat(shouldAnimateIconView).isEqualTo(true)
                    } else { //  co-ex, authenticated by face
                        assertThat(iconAsset)
                            .isEqualTo(R.raw.fingerprint_dialogue_error_to_unlock_lottie)
                        assertThat(iconContentDescriptionId)
                            .isEqualTo(R.string.biometric_dialog_confirm)
                        assertThat(shouldAnimateIconView).isEqualTo(true)

                        // Confirm authentication
                        kosmos.promptViewModel.confirmAuthenticated()

                        assertThat(iconAsset)
                            .isEqualTo(
                                R.raw.fingerprint_dialogue_unlocked_to_checkmark_success_lottie
                            )
                        assertThat(iconContentDescriptionId)
                            .isEqualTo(R.string.fingerprint_dialog_touch_sensor)
                        assertThat(shouldAnimateIconView).isEqualTo(true)
                    }
                }
            }
        }
    }

    @Test
    fun shows_authenticated_no_errors_no_confirmation_required() {
        if (!testCase.confirmationRequested) {
            runGenericTest {
                val iconAsset by collectLastValue(kosmos.promptViewModel.iconViewModel.iconAsset)
                val iconContentDescriptionId by
                    collectLastValue(kosmos.promptViewModel.iconViewModel.contentDescriptionId)
                val shouldAnimateIconView by
                    collectLastValue(kosmos.promptViewModel.iconViewModel.shouldAnimateIconView)
                val message by collectLastValue(kosmos.promptViewModel.message)
                verifyIconSize()

                kosmos.promptViewModel.showAuthenticated(
                    modality = testCase.authenticatedModality,
                    dismissAfterDelay = DELAY,
                    "TEST"
                )

                if (testCase.isFingerprintOnly) {
                    // Fingerprint icon asset assertions
                    if (testCase.sensorType == FingerprintSensorProperties.TYPE_POWER_BUTTON) {
                        assertThat(iconAsset).isEqualTo(getSfpsAsset_fingerprintToSuccess())
                        assertThat(iconContentDescriptionId)
                            .isEqualTo(R.string.security_settings_sfps_enroll_find_sensor_message)
                        assertThat(shouldAnimateIconView).isEqualTo(true)
                    } else {
                        assertThat(iconAsset)
                            .isEqualTo(R.raw.fingerprint_dialogue_fingerprint_to_success_lottie)
                        assertThat(iconContentDescriptionId)
                            .isEqualTo(R.string.fingerprint_dialog_touch_sensor)
                        assertThat(shouldAnimateIconView).isEqualTo(true)
                    }
                } else if (testCase.isFaceOnly || testCase.isCoex) {
                    // Face icon asset assertions
                    // If co-ex, use implicit flow (explicit flow always requires confirmation)
                    assertThat(iconAsset).isEqualTo(R.raw.face_dialog_dark_to_checkmark)
                    assertThat(iconContentDescriptionId)
                        .isEqualTo(R.string.biometric_dialog_face_icon_description_authenticated)
                    assertThat(shouldAnimateIconView).isEqualTo(true)
                    assertThat(message).isEqualTo(PromptMessage.Empty)
                }
            }
        }
    }

    @Test
    fun shows_pending_confirmation() {
        if (testCase.authenticatedByFace && testCase.confirmationRequested) {
            runGenericTest {
                val iconAsset by collectLastValue(kosmos.promptViewModel.iconViewModel.iconAsset)
                val iconContentDescriptionId by
                    collectLastValue(kosmos.promptViewModel.iconViewModel.contentDescriptionId)
                val shouldAnimateIconView by
                    collectLastValue(kosmos.promptViewModel.iconViewModel.shouldAnimateIconView)

                val forceExplicitFlow = testCase.isCoex && testCase.confirmationRequested
                verifyIconSize(forceExplicitFlow = forceExplicitFlow)

                kosmos.promptViewModel.showAuthenticated(
                    modality = testCase.authenticatedModality,
                    dismissAfterDelay = DELAY
                )

                if (testCase.isFaceOnly) {
                    assertThat(iconAsset).isEqualTo(R.raw.face_dialog_wink_from_dark)
                    assertThat(iconContentDescriptionId)
                        .isEqualTo(R.string.biometric_dialog_face_icon_description_authenticated)
                    assertThat(shouldAnimateIconView).isEqualTo(true)
                } else if (testCase.isCoex) { // explicit flow, confirmation requested
                    if (testCase.sensorType == FingerprintSensorProperties.TYPE_POWER_BUTTON) {
                        assertThat(iconAsset).isEqualTo(getSfpsAsset_fingerprintToUnlock())
                        assertThat(iconContentDescriptionId)
                            .isEqualTo(R.string.fingerprint_dialog_authenticated_confirmation)
                        assertThat(shouldAnimateIconView).isEqualTo(true)
                    } else {
                        assertThat(iconAsset)
                            .isEqualTo(R.raw.fingerprint_dialogue_fingerprint_to_unlock_lottie)
                        assertThat(iconContentDescriptionId)
                            .isEqualTo(R.string.biometric_dialog_confirm)
                        assertThat(shouldAnimateIconView).isEqualTo(true)
                    }
                }
            }
        }
    }

    @Test
    fun shows_authenticated_explicitly_confirmed() {
        if (testCase.authenticatedByFace && testCase.confirmationRequested) {
            runGenericTest {
                val iconAsset by collectLastValue(kosmos.promptViewModel.iconViewModel.iconAsset)
                val iconContentDescriptionId by
                    collectLastValue(kosmos.promptViewModel.iconViewModel.contentDescriptionId)
                val shouldAnimateIconView by
                    collectLastValue(kosmos.promptViewModel.iconViewModel.shouldAnimateIconView)
                val forceExplicitFlow = testCase.isCoex && testCase.confirmationRequested
                verifyIconSize(forceExplicitFlow = forceExplicitFlow)

                kosmos.promptViewModel.showAuthenticated(
                    modality = testCase.authenticatedModality,
                    dismissAfterDelay = DELAY
                )

                kosmos.promptViewModel.confirmAuthenticated()

                if (testCase.isFaceOnly) {
                    assertThat(iconAsset).isEqualTo(R.raw.face_dialog_dark_to_checkmark)
                    assertThat(iconContentDescriptionId)
                        .isEqualTo(R.string.biometric_dialog_face_icon_description_confirmed)
                    assertThat(shouldAnimateIconView).isEqualTo(true)
                }

                // explicit flow because confirmation requested
                if (testCase.isCoex) {
                    if (testCase.sensorType == FingerprintSensorProperties.TYPE_POWER_BUTTON) {
                        assertThat(iconAsset)
                            .isEqualTo(R.raw.biometricprompt_sfps_unlock_to_success)
                        assertThat(shouldAnimateIconView).isEqualTo(true)
                    } else {
                        assertThat(iconAsset)
                            .isEqualTo(
                                R.raw.fingerprint_dialogue_unlocked_to_checkmark_success_lottie
                            )
                        assertThat(iconContentDescriptionId)
                            .isEqualTo(R.string.fingerprint_dialog_touch_sensor)
                        assertThat(shouldAnimateIconView).isEqualTo(true)
                    }
                }
            }
        }
    }

    private fun getSfpsAsset_fingerprintAuthenticating(): Int =
        if (testCase.isInRearDisplayMode) {
            R.raw.biometricprompt_sfps_rear_display_fingerprint_authenticating
        } else {
            R.raw.biometricprompt_sfps_fingerprint_authenticating
        }

    private fun getSfpsAsset_fingerprintToError(): Int =
        if (testCase.isInRearDisplayMode) {
            R.raw.biometricprompt_sfps_rear_display_fingerprint_to_error
        } else {
            R.raw.biometricprompt_sfps_fingerprint_to_error
        }

    private fun getSfpsAsset_fingerprintToUnlock(): Int =
        if (testCase.isInRearDisplayMode) {
            R.raw.biometricprompt_sfps_rear_display_fingerprint_to_unlock
        } else {
            R.raw.biometricprompt_sfps_fingerprint_to_unlock
        }

    private fun getSfpsAsset_errorToFingerprint(): Int =
        if (testCase.isInRearDisplayMode) {
            R.raw.biometricprompt_sfps_rear_display_error_to_fingerprint
        } else {
            R.raw.biometricprompt_sfps_error_to_fingerprint
        }

    private fun getSfpsAsset_fingerprintToSuccess(): Int =
        if (testCase.isInRearDisplayMode) {
            R.raw.biometricprompt_sfps_rear_display_fingerprint_to_success
        } else {
            R.raw.biometricprompt_sfps_fingerprint_to_success
        }

    @Test
    fun shows_authenticated_with_no_errors() = runGenericTest {
        // this case can't happen until fingerprint is started
        // trigger it now since no error has occurred in this test
        val forceError = testCase.isCoex && testCase.authenticatedByFingerprint

        if (forceError) {
            assertThat(kosmos.promptViewModel.fingerprintStartMode.first())
                .isEqualTo(FingerprintStartMode.Pending)
            kosmos.promptViewModel.ensureFingerprintHasStarted(isDelayed = true)
        }

        showAuthenticated(
            testCase.authenticatedModality,
            testCase.expectConfirmation(atLeastOneFailure = forceError),
        )
    }

    // Verifies expected icon sizes for all modalities
    private fun TestScope.verifyIconSize(forceExplicitFlow: Boolean = false) {
        val iconSize by collectLastValue(kosmos.promptViewModel.iconSize)
        if ((testCase.isCoex && !forceExplicitFlow) || testCase.isFaceOnly) {
            // Face-only or implicit co-ex auth
            assertThat(iconSize).isEqualTo(Pair(mockFaceIconSize, mockFaceIconSize))
        } else if ((testCase.isCoex && forceExplicitFlow) || testCase.isFingerprintOnly) {
            // Fingerprint-only or explicit co-ex auth
            if (testCase.fingerprint?.isAnyUdfpsType == true) {
                val udfpsOverlayParams by
                    collectLastValue(kosmos.promptViewModel.udfpsOverlayParams)
                val expectedUdfpsOverlayParams = mockUdfpsOverlayParams()
                assertThat(udfpsOverlayParams).isEqualTo(expectedUdfpsOverlayParams)

                assertThat(iconSize)
                    .isEqualTo(
                        Pair(
                            expectedUdfpsOverlayParams.sensorBounds.width(),
                            expectedUdfpsOverlayParams.sensorBounds.height()
                        )
                    )
            } else {
                assertThat(iconSize)
                    .isEqualTo(Pair(mockFingerprintIconWidth, mockFingerprintIconHeight))
            }
        }
    }

    @Test
    @DisableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun set_haptic_on_confirm_when_confirmation_required_otherwise_on_authenticated() =
        runGenericTest {
            val expectConfirmation = testCase.expectConfirmation(atLeastOneFailure = false)

            kosmos.promptViewModel.showAuthenticated(testCase.authenticatedModality, 1_000L)

            val hapticsPreConfirm by collectLastValue(kosmos.promptViewModel.hapticsToPlay)
            if (expectConfirmation) {
                assertThat(hapticsPreConfirm).isEqualTo(PromptViewModel.HapticsToPlay.None)
            } else {
                val confirmHaptics =
                    hapticsPreConfirm as PromptViewModel.HapticsToPlay.HapticConstant
                assertThat(confirmHaptics.constant)
                    .isEqualTo(HapticFeedbackConstants.BIOMETRIC_CONFIRM)
                assertThat(confirmHaptics.flag).isNull()
            }

            if (expectConfirmation) {
                kosmos.promptViewModel.confirmAuthenticated()
            }

            val hapticsPostConfirm by collectLastValue(kosmos.promptViewModel.hapticsToPlay)
            val confirmedHaptics =
                hapticsPostConfirm as PromptViewModel.HapticsToPlay.HapticConstant
            assertThat(confirmedHaptics.constant)
                .isEqualTo(HapticFeedbackConstants.BIOMETRIC_CONFIRM)
            assertThat(confirmedHaptics.flag).isNull()
        }

    @Test
    @EnableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun set_msdl_haptic_on_confirm_when_confirmation_required_otherwise_on_authenticated() =
        runGenericTest {
            val expectConfirmation = testCase.expectConfirmation(atLeastOneFailure = false)

            kosmos.promptViewModel.showAuthenticated(testCase.authenticatedModality, 1_000L)

            val hapticsPreConfirm by collectLastValue(kosmos.promptViewModel.hapticsToPlay)

            if (expectConfirmation) {
                assertThat(hapticsPreConfirm).isEqualTo(PromptViewModel.HapticsToPlay.None)
            } else {
                val confirmHaptics = hapticsPreConfirm as PromptViewModel.HapticsToPlay.MSDL
                assertThat(confirmHaptics.token).isEqualTo(MSDLToken.UNLOCK)
                assertThat(confirmHaptics.properties).isEqualTo(authInteractionProperties)
            }

            if (expectConfirmation) {
                kosmos.promptViewModel.confirmAuthenticated()
            }

            val hapticsPostConfirm by collectLastValue(kosmos.promptViewModel.hapticsToPlay)
            val confirmedHaptics = hapticsPostConfirm as PromptViewModel.HapticsToPlay.MSDL
            assertThat(confirmedHaptics.token).isEqualTo(MSDLToken.UNLOCK)
            assertThat(confirmedHaptics.properties).isEqualTo(authInteractionProperties)
        }

    @Test
    @DisableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun playSuccessHaptic_SetsConfirmConstant() = runGenericTest {
        val expectConfirmation = testCase.expectConfirmation(atLeastOneFailure = false)
        kosmos.promptViewModel.showAuthenticated(testCase.authenticatedModality, 1_000L)

        if (expectConfirmation) {
            kosmos.promptViewModel.confirmAuthenticated()
        }

        val haptics by collectLastValue(kosmos.promptViewModel.hapticsToPlay)
        val currentHaptics = haptics as PromptViewModel.HapticsToPlay.HapticConstant
        assertThat(currentHaptics.constant).isEqualTo(HapticFeedbackConstants.BIOMETRIC_CONFIRM)
        assertThat(currentHaptics.flag).isNull()
    }

    @Test
    @EnableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun playSuccessHaptic_SetsUnlockMSDLFeedback() = runGenericTest {
        val expectConfirmation = testCase.expectConfirmation(atLeastOneFailure = false)
        kosmos.promptViewModel.showAuthenticated(testCase.authenticatedModality, 1_000L)

        if (expectConfirmation) {
            kosmos.promptViewModel.confirmAuthenticated()
        }

        val haptics by collectLastValue(kosmos.promptViewModel.hapticsToPlay)
        val currentHaptics = haptics as PromptViewModel.HapticsToPlay.MSDL
        assertThat(currentHaptics.token).isEqualTo(MSDLToken.UNLOCK)
        assertThat(currentHaptics.properties).isEqualTo(authInteractionProperties)
    }

    @Test
    @DisableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun playErrorHaptic_SetsRejectConstant() = runGenericTest {
        kosmos.promptViewModel.showTemporaryError("test", "messageAfterError", false)

        val haptics by collectLastValue(kosmos.promptViewModel.hapticsToPlay)
        val currentHaptics = haptics as PromptViewModel.HapticsToPlay.HapticConstant
        assertThat(currentHaptics.constant).isEqualTo(HapticFeedbackConstants.BIOMETRIC_REJECT)
        assertThat(currentHaptics.flag).isNull()
    }

    @Test
    @EnableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun playErrorHaptic_SetsFailureMSDLFeedback() = runGenericTest {
        kosmos.promptViewModel.showTemporaryError("test", "messageAfterError", false)

        val haptics by collectLastValue(kosmos.promptViewModel.hapticsToPlay)
        val currentHaptics = haptics as PromptViewModel.HapticsToPlay.MSDL
        assertThat(currentHaptics.token).isEqualTo(MSDLToken.FAILURE)
        assertThat(currentHaptics.properties).isEqualTo(authInteractionProperties)
    }

    // biometricprompt_sfps_fingerprint_authenticating reused across rotations
    // Other SFPS assets change across rotations, testing authenticated asset
    @Test
    fun sfpsAuthenticatedIconUpdates_onRotation() {
        if (testCase.sensorType == FingerprintSensorProperties.TYPE_POWER_BUTTON) {
            runGenericTest {
                val currentIcon by collectLastValue(kosmos.promptViewModel.iconViewModel.iconAsset)

                kosmos.promptViewModel.showAuthenticated(
                    modality = testCase.authenticatedModality,
                    dismissAfterDelay = DELAY
                )

                kosmos.displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_0)
                val iconRotation0 = currentIcon

                kosmos.displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_90)
                val iconRotation90 = currentIcon

                kosmos.displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_180)
                val iconRotation180 = currentIcon

                kosmos.displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_270)
                val iconRotation270 = currentIcon

                assertThat(iconRotation0).isNotEqualTo(iconRotation90)
                assertThat(iconRotation0).isNotEqualTo(iconRotation180)
                assertThat(iconRotation0).isNotEqualTo(iconRotation270)
                assertThat(iconRotation90).isNotEqualTo(iconRotation180)
                assertThat(iconRotation90).isNotEqualTo(iconRotation270)
                assertThat(iconRotation180).isNotEqualTo(iconRotation270)
            }
        }
    }

    @Test
    fun sfpsIconUpdates_onRearDisplayMode() {
        if (testCase.sensorType == FingerprintSensorProperties.TYPE_POWER_BUTTON) {
            runGenericTest {
                val currentIcon by collectLastValue(kosmos.promptViewModel.iconViewModel.iconAsset)

                kosmos.displayStateRepository.setIsInRearDisplayMode(false)
                val iconNotRearDisplayMode = currentIcon

                kosmos.displayStateRepository.setIsInRearDisplayMode(true)
                val iconRearDisplayMode = currentIcon

                assertThat(iconNotRearDisplayMode).isNotEqualTo(iconRearDisplayMode)
            }
        }
    }

    private suspend fun TestScope.showAuthenticated(
        authenticatedModality: BiometricModality,
        expectConfirmation: Boolean,
    ) {
        val authenticating by collectLastValue(kosmos.promptViewModel.isAuthenticating)
        val authenticated by collectLastValue(kosmos.promptViewModel.isAuthenticated)
        val fpStartMode by collectLastValue(kosmos.promptViewModel.fingerprintStartMode)
        val size by collectLastValue(kosmos.promptViewModel.size)

        val authWithSmallPrompt =
            testCase.shouldStartAsImplicitFlow &&
                (fpStartMode == FingerprintStartMode.Pending || testCase.isFaceOnly)
        assertThat(authenticating).isTrue()
        assertThat(authenticated?.isNotAuthenticated).isTrue()
        assertThat(size).isEqualTo(if (authWithSmallPrompt) PromptSize.SMALL else PromptSize.MEDIUM)
        assertButtonsVisible(negative = !authWithSmallPrompt)

        kosmos.promptViewModel.showAuthenticated(authenticatedModality, DELAY)

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
    @DisableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun set_haptic_on_errors() = runGenericTest {
        kosmos.promptViewModel.showTemporaryError(
            "so sad",
            messageAfterError = "",
            authenticateAfterError = false,
            hapticFeedback = true,
        )

        val hapticsToPlay by collectLastValue(kosmos.promptViewModel.hapticsToPlay)
        val haptics = hapticsToPlay as PromptViewModel.HapticsToPlay.HapticConstant
        assertThat(haptics.constant).isEqualTo(HapticFeedbackConstants.BIOMETRIC_REJECT)
        assertThat(haptics.flag).isNull()
    }

    @Test
    @EnableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun set_msdl_haptic_on_errors() = runGenericTest {
        kosmos.promptViewModel.showTemporaryError(
            "so sad",
            messageAfterError = "",
            authenticateAfterError = false,
            hapticFeedback = true,
        )

        val hapticsToPlay by collectLastValue(kosmos.promptViewModel.hapticsToPlay)
        val haptics = hapticsToPlay as PromptViewModel.HapticsToPlay.MSDL
        assertThat(haptics.token).isEqualTo(MSDLToken.FAILURE)
        assertThat(haptics.properties).isEqualTo(authInteractionProperties)
    }

    @Test
    @DisableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun plays_haptic_on_errors_unless_skipped() = runGenericTest {
        kosmos.promptViewModel.showTemporaryError(
            "still sad",
            messageAfterError = "",
            authenticateAfterError = false,
            hapticFeedback = false,
        )

        val hapticsToPlay by collectLastValue(kosmos.promptViewModel.hapticsToPlay)
        assertThat(hapticsToPlay).isEqualTo(PromptViewModel.HapticsToPlay.None)
    }

    @Test
    @EnableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun plays_msdl_haptic_on_errors_unless_skipped() = runGenericTest {
        kosmos.promptViewModel.showTemporaryError(
            "still sad",
            messageAfterError = "",
            authenticateAfterError = false,
            hapticFeedback = false,
        )

        val hapticsToPlay by collectLastValue(kosmos.promptViewModel.hapticsToPlay)
        assertThat(hapticsToPlay).isEqualTo(PromptViewModel.HapticsToPlay.None)
    }

    @Test
    @DisableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun plays_haptic_on_error_after_auth_when_confirmation_needed() = runGenericTest {
        val expectConfirmation = testCase.expectConfirmation(atLeastOneFailure = false)
        kosmos.promptViewModel.showAuthenticated(testCase.authenticatedModality, 0)

        kosmos.promptViewModel.showTemporaryError(
            "still sad",
            messageAfterError = "",
            authenticateAfterError = false,
            hapticFeedback = true,
        )

        val hapticsToPlay by collectLastValue(kosmos.promptViewModel.hapticsToPlay)
        val haptics = hapticsToPlay as PromptViewModel.HapticsToPlay.HapticConstant
        if (expectConfirmation) {
            assertThat(haptics.constant).isEqualTo(HapticFeedbackConstants.BIOMETRIC_REJECT)
            assertThat(haptics.flag).isNull()
        } else {
            assertThat(haptics.constant).isEqualTo(HapticFeedbackConstants.BIOMETRIC_CONFIRM)
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun plays_msdl_haptic_on_error_after_auth_when_confirmation_needed() = runGenericTest {
        val expectConfirmation = testCase.expectConfirmation(atLeastOneFailure = false)
        kosmos.promptViewModel.showAuthenticated(testCase.authenticatedModality, 0)

        kosmos.promptViewModel.showTemporaryError(
            "still sad",
            messageAfterError = "",
            authenticateAfterError = false,
            hapticFeedback = true,
        )

        val hapticsToPlay by collectLastValue(kosmos.promptViewModel.hapticsToPlay)
        val haptics = hapticsToPlay as PromptViewModel.HapticsToPlay.MSDL
        if (expectConfirmation) {
            assertThat(haptics.token).isEqualTo(MSDLToken.FAILURE)
        } else {
            assertThat(haptics.token).isEqualTo(MSDLToken.UNLOCK)
        }
        assertThat(haptics.properties).isEqualTo(authInteractionProperties)
    }

    private suspend fun TestScope.showTemporaryErrors(
        restart: Boolean,
        helpAfterError: String = "",
        block: suspend TestScope.() -> Unit = {},
    ) {
        val errorMessage = "oh no!"
        val authenticating by collectLastValue(kosmos.promptViewModel.isAuthenticating)
        val authenticated by collectLastValue(kosmos.promptViewModel.isAuthenticated)
        val message by collectLastValue(kosmos.promptViewModel.message)
        val messageVisible by collectLastValue(kosmos.promptViewModel.isIndicatorMessageVisible)
        val size by collectLastValue(kosmos.promptViewModel.size)
        val canTryAgainNow by collectLastValue(kosmos.promptViewModel.canTryAgainNow)

        val errorJob = launch {
            kosmos.promptViewModel.showTemporaryError(
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
        val authenticating by collectLastValue(kosmos.promptViewModel.isAuthenticating)
        val authenticated by collectLastValue(kosmos.promptViewModel.isAuthenticated)
        val message by collectLastValue(kosmos.promptViewModel.message)
        val messageIsShowing by collectLastValue(kosmos.promptViewModel.isIndicatorMessageVisible)
        val canTryAgain by collectLastValue(kosmos.promptViewModel.canTryAgainNow)

        kosmos.promptViewModel.showAuthenticated(testCase.authenticatedModality, 0)

        val verifyNoError = {
            assertThat(authenticating).isFalse()
            assertThat(authenticated?.isAuthenticated).isTrue()
            assertThat(message).isEqualTo(PromptMessage.Empty)
            assertThat(canTryAgain).isFalse()
        }

        val errorJob = launch {
            kosmos.promptViewModel.showTemporaryError(
                "error",
                messageAfterError = "",
                authenticateAfterError = false,
            )
        }
        verifyNoError()
        errorJob.join()
        verifyNoError()

        val helpJob = launch { kosmos.promptViewModel.showTemporaryHelp("hi") }
        verifyNoError()
        helpJob.join()
        verifyNoError()

        // persistent help is allowed
        val stickyHelpMessage = "blah"
        kosmos.promptViewModel.showHelp(stickyHelpMessage)
        assertThat(authenticating).isFalse()
        assertThat(authenticated?.isAuthenticated).isTrue()
        assertThat(message).isEqualTo(PromptMessage.Help(stickyHelpMessage))
        assertThat(messageIsShowing).isTrue()
    }

    @Test
    fun suppress_temporary_error() = runGenericTest {
        val messages by collectValues(kosmos.promptViewModel.message)

        for (error in listOf("never", "see", "me")) {
            launch {
                kosmos.promptViewModel.showTemporaryError(
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
        val messages by collectValues(kosmos.promptViewModel.message)

        for (error in errors) {
            launch {
                kosmos.promptViewModel.showTemporaryError(
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
    fun authenticated_at_most_once_same_modality() = runGenericTest {
        val authenticating by collectLastValue(kosmos.promptViewModel.isAuthenticating)
        val authenticated by collectLastValue(kosmos.promptViewModel.isAuthenticated)

        kosmos.promptViewModel.showAuthenticated(testCase.authenticatedModality, 0)

        assertThat(authenticating).isFalse()
        assertThat(authenticated?.isAuthenticated).isTrue()

        kosmos.promptViewModel.showAuthenticated(testCase.authenticatedModality, 0)

        assertThat(authenticating).isFalse()
        assertThat(authenticated?.isAuthenticated).isTrue()
    }

    @Test
    fun authenticating_cannot_restart_after_authenticated() = runGenericTest {
        val authenticating by collectLastValue(kosmos.promptViewModel.isAuthenticating)
        val authenticated by collectLastValue(kosmos.promptViewModel.isAuthenticated)

        kosmos.promptViewModel.showAuthenticated(testCase.authenticatedModality, 0)

        assertThat(authenticating).isFalse()
        assertThat(authenticated?.isAuthenticated).isTrue()

        kosmos.promptViewModel.showAuthenticating("again!")

        assertThat(authenticating).isFalse()
        assertThat(authenticated?.isAuthenticated).isTrue()
    }

    @Test
    fun confirm_authentication() = runGenericTest {
        val expectConfirmation = testCase.expectConfirmation(atLeastOneFailure = false)

        kosmos.promptViewModel.showAuthenticated(testCase.authenticatedModality, 0)

        val authenticating by collectLastValue(kosmos.promptViewModel.isAuthenticating)
        val authenticated by collectLastValue(kosmos.promptViewModel.isAuthenticated)
        val message by collectLastValue(kosmos.promptViewModel.message)
        val size by collectLastValue(kosmos.promptViewModel.size)
        val canTryAgain by collectLastValue(kosmos.promptViewModel.canTryAgainNow)

        assertThat(authenticated?.needsUserConfirmation).isEqualTo(expectConfirmation)
        if (expectConfirmation) {
            assertThat(size).isEqualTo(PromptSize.MEDIUM)
            assertButtonsVisible(
                cancel = true,
                confirm = true,
            )

            kosmos.promptViewModel.confirmAuthenticated()
            assertThat(message).isEqualTo(PromptMessage.Empty)
            assertButtonsVisible()
        }

        assertThat(authenticating).isFalse()
        assertThat(authenticated?.isAuthenticated).isTrue()
        assertThat(canTryAgain).isFalse()
    }

    @Test
    fun second_authentication_acts_as_confirmation() = runGenericTest {
        val expectConfirmation = testCase.expectConfirmation(atLeastOneFailure = false)

        kosmos.promptViewModel.showAuthenticated(testCase.authenticatedModality, 0)

        val authenticating by collectLastValue(kosmos.promptViewModel.isAuthenticating)
        val authenticated by collectLastValue(kosmos.promptViewModel.isAuthenticated)
        val message by collectLastValue(kosmos.promptViewModel.message)
        val size by collectLastValue(kosmos.promptViewModel.size)
        val canTryAgain by collectLastValue(kosmos.promptViewModel.canTryAgainNow)

        assertThat(authenticated?.needsUserConfirmation).isEqualTo(expectConfirmation)
        if (expectConfirmation) {
            assertThat(size).isEqualTo(PromptSize.MEDIUM)
            assertButtonsVisible(
                cancel = true,
                confirm = true,
            )

            if (testCase.modalities.hasSfps) {
                kosmos.promptViewModel.showAuthenticated(BiometricModality.Fingerprint, 0)
                assertThat(message).isEqualTo(PromptMessage.Empty)
                assertButtonsVisible()
            }
        }

        assertThat(authenticating).isFalse()
        assertThat(authenticated?.isAuthenticated).isTrue()
        assertThat(canTryAgain).isFalse()
    }

    @Test
    fun auto_confirm_authentication_when_finger_down() = runGenericTest {
        val expectConfirmation = testCase.expectConfirmation(atLeastOneFailure = false)

        if (testCase.isCoex) {
            kosmos.promptViewModel.onOverlayTouch(obtainMotionEvent(MotionEvent.ACTION_DOWN))
        }
        kosmos.promptViewModel.showAuthenticated(testCase.authenticatedModality, 0)

        val authenticating by collectLastValue(kosmos.promptViewModel.isAuthenticating)
        val authenticated by collectLastValue(kosmos.promptViewModel.isAuthenticated)
        val message by collectLastValue(kosmos.promptViewModel.message)
        val size by collectLastValue(kosmos.promptViewModel.size)
        val canTryAgain by collectLastValue(kosmos.promptViewModel.canTryAgainNow)

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

                kosmos.promptViewModel.confirmAuthenticated()
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
            kosmos.promptViewModel.onOverlayTouch(obtainMotionEvent(MotionEvent.ACTION_DOWN))
            kosmos.promptViewModel.onOverlayTouch(obtainMotionEvent(MotionEvent.ACTION_UP))
        }
        kosmos.promptViewModel.showAuthenticated(testCase.authenticatedModality, 0)

        val authenticating by collectLastValue(kosmos.promptViewModel.isAuthenticating)
        val authenticated by collectLastValue(kosmos.promptViewModel.isAuthenticated)
        val message by collectLastValue(kosmos.promptViewModel.message)
        val size by collectLastValue(kosmos.promptViewModel.size)
        val canTryAgain by collectLastValue(kosmos.promptViewModel.canTryAgainNow)

        assertThat(authenticated?.needsUserConfirmation).isEqualTo(expectConfirmation)
        if (expectConfirmation) {
            assertThat(size).isEqualTo(PromptSize.MEDIUM)
            assertButtonsVisible(
                cancel = true,
                confirm = true,
            )

            kosmos.promptViewModel.confirmAuthenticated()
            assertThat(message).isEqualTo(PromptMessage.Empty)
            assertButtonsVisible()
        }

        assertThat(authenticating).isFalse()
        assertThat(authenticated?.isAuthenticated).isTrue()
        assertThat(canTryAgain).isFalse()
    }

    @Test
    fun cannot_confirm_unless_authenticated() = runGenericTest {
        val authenticating by collectLastValue(kosmos.promptViewModel.isAuthenticating)
        val authenticated by collectLastValue(kosmos.promptViewModel.isAuthenticated)

        kosmos.promptViewModel.confirmAuthenticated()
        assertThat(authenticating).isTrue()
        assertThat(authenticated?.isNotAuthenticated).isTrue()

        kosmos.promptViewModel.showAuthenticated(testCase.authenticatedModality, 0)

        // reconfirm should be a no-op
        kosmos.promptViewModel.confirmAuthenticated()
        kosmos.promptViewModel.confirmAuthenticated()

        assertThat(authenticating).isFalse()
        assertThat(authenticated?.isNotAuthenticated).isFalse()
    }

    @Test
    fun shows_help_before_authenticated() = runGenericTest {
        val helpMessage = "please help yourself to some cookies"
        val message by collectLastValue(kosmos.promptViewModel.message)
        val messageVisible by collectLastValue(kosmos.promptViewModel.isIndicatorMessageVisible)
        val size by collectLastValue(kosmos.promptViewModel.size)

        kosmos.promptViewModel.showHelp(helpMessage)

        assertThat(size).isEqualTo(PromptSize.MEDIUM)
        assertThat(message).isEqualTo(PromptMessage.Help(helpMessage))
        assertThat(messageVisible).isTrue()

        assertThat(kosmos.promptViewModel.isAuthenticating.first()).isFalse()
        assertThat(kosmos.promptViewModel.isAuthenticated.first().isNotAuthenticated).isTrue()
    }

    @Test
    fun shows_help_after_authenticated() = runGenericTest {
        val expectConfirmation = testCase.expectConfirmation(atLeastOneFailure = false)
        val helpMessage = "more cookies please"
        val authenticating by collectLastValue(kosmos.promptViewModel.isAuthenticating)
        val authenticated by collectLastValue(kosmos.promptViewModel.isAuthenticated)
        val message by collectLastValue(kosmos.promptViewModel.message)
        val messageVisible by collectLastValue(kosmos.promptViewModel.isIndicatorMessageVisible)
        val size by collectLastValue(kosmos.promptViewModel.size)
        val confirmationRequired by collectLastValue(kosmos.promptViewModel.isConfirmationRequired)

        if (testCase.isCoex && testCase.authenticatedByFingerprint) {
            kosmos.promptViewModel.ensureFingerprintHasStarted(isDelayed = true)
        }
        kosmos.promptViewModel.showAuthenticated(testCase.authenticatedModality, 0)
        kosmos.promptViewModel.showHelp(helpMessage)

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
        val authenticating by collectLastValue(kosmos.promptViewModel.isAuthenticating)
        val authenticated by collectLastValue(kosmos.promptViewModel.isAuthenticated)
        val message by collectLastValue(kosmos.promptViewModel.message)
        val messageVisible by collectLastValue(kosmos.promptViewModel.isIndicatorMessageVisible)
        val canTryAgain by collectLastValue(kosmos.promptViewModel.canTryAgainNow)

        kosmos.promptViewModel.showAuthenticating("go")
        val errorJob = launch {
            kosmos.promptViewModel.showTemporaryError(
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
        kosmos.promptViewModel.showAuthenticating(helpMessage2, isRetry = true)
        assertThat(authenticating).isTrue()
        assertThat(authenticated?.isAuthenticated).isFalse()
        assertThat(message).isEqualTo(PromptMessage.Help(helpMessage2))
        assertThat(messageVisible).isTrue()
        assertButtonsVisible(negative = true)
    }

    @Test
    fun switch_to_credential_fallback() = runGenericTest {
        val size by collectLastValue(kosmos.promptViewModel.size)

        // TODO(b/251476085): remove Spaghetti, migrate logic, and update this test
        kosmos.promptViewModel.onSwitchToCredential()

        assertThat(size).isEqualTo(PromptSize.LARGE)
    }

    @Test
    fun hint_for_talkback_guidance() = runGenericTest {
        val hint by collectLastValue(kosmos.promptViewModel.accessibilityHint)

        // Touches should fall outside of sensor area
        whenever(kosmos.udfpsUtils.getTouchInNativeCoordinates(any(), any(), any()))
            .thenReturn(Point(0, 0))
        whenever(kosmos.udfpsUtils.onTouchOutsideOfSensorArea(any(), any(), any(), any(), any()))
            .thenReturn("Direction")

        kosmos.promptViewModel.onAnnounceAccessibilityHint(
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
    fun no_hint_for_talkback_guidance_after_auth() = runGenericTest {
        val hint by collectLastValue(kosmos.promptViewModel.accessibilityHint)

        kosmos.promptViewModel.showAuthenticated(testCase.authenticatedModality, 0)
        kosmos.promptViewModel.confirmAuthenticated()

        // Touches should fall outside of sensor area
        whenever(kosmos.udfpsUtils.getTouchInNativeCoordinates(any(), any(), any()))
            .thenReturn(Point(0, 0))
        whenever(kosmos.udfpsUtils.onTouchOutsideOfSensorArea(any(), any(), any(), any(), any()))
            .thenReturn("Direction")

        kosmos.promptViewModel.onAnnounceAccessibilityHint(
            obtainMotionEvent(MotionEvent.ACTION_HOVER_ENTER),
            true
        )

        assertThat(hint.isNullOrBlank()).isTrue()
    }

    @Test
    @EnableFlags(FLAG_CUSTOM_BIOMETRIC_PROMPT)
    fun descriptionOverriddenByVerticalListContentView() =
        runGenericTest(description = "test description", contentView = promptContentView) {
            val contentView by collectLastValue(kosmos.promptViewModel.contentView)
            val description by collectLastValue(kosmos.promptViewModel.description)

            assertThat(description).isEqualTo("")
            assertThat(contentView).isEqualTo(promptContentView)
        }

    @Test
    @EnableFlags(FLAG_CUSTOM_BIOMETRIC_PROMPT)
    fun descriptionOverriddenByContentViewWithMoreOptionsButton() =
        runGenericTest(
            description = "test description",
            contentView = promptContentViewWithMoreOptionsButton
        ) {
            val contentView by collectLastValue(kosmos.promptViewModel.contentView)
            val description by collectLastValue(kosmos.promptViewModel.description)

            assertThat(description).isEqualTo("")
            assertThat(contentView).isEqualTo(promptContentViewWithMoreOptionsButton)
        }

    @Test
    @EnableFlags(FLAG_CUSTOM_BIOMETRIC_PROMPT)
    fun descriptionWithoutContentView() =
        runGenericTest(description = "test description") {
            val contentView by collectLastValue(kosmos.promptViewModel.contentView)
            val description by collectLastValue(kosmos.promptViewModel.description)

            assertThat(description).isEqualTo("test description")
            assertThat(contentView).isNull()
        }

    @Test
    @EnableFlags(FLAG_CUSTOM_BIOMETRIC_PROMPT)
    fun logo_nullIfPkgNameNotFound() =
        runGenericTest(packageName = OP_PACKAGE_NAME_CAN_NOT_BE_FOUND) {
            val logoInfo by collectLastValue(kosmos.promptViewModel.logoInfo)
            assertThat(logoInfo).isNotNull()
            assertThat(logoInfo!!.first).isNull()
        }

    @Test
    @EnableFlags(FLAG_CUSTOM_BIOMETRIC_PROMPT)
    fun logo_defaultFromActivityInfo() =
        runGenericTest(packageName = OP_PACKAGE_NAME_WITH_ACTIVITY_LOGO) {
            val logoInfo by collectLastValue(kosmos.promptViewModel.logoInfo)

            // 1. PM.getApplicationInfo(OP_PACKAGE_NAME_WITH_ACTIVITY_LOGO) is set to return
            // applicationInfoWithIconAndDescription with "defaultLogoIconFromAppInfo",
            // 2. iconProvider.getIcon(activityInfo) is set to return
            // "defaultLogoIconFromActivityInfo"
            // For the apps with OP_PACKAGE_NAME_WITH_ACTIVITY_LOGO, 2 should be called instead of 1
            assertThat(logoInfo).isNotNull()
            assertThat(logoInfo!!.first).isEqualTo(defaultLogoIconFromActivityInfo)
        }

    @Test
    @EnableFlags(FLAG_CUSTOM_BIOMETRIC_PROMPT)
    fun logo_defaultIsNull() =
        runGenericTest(packageName = OP_PACKAGE_NAME_NO_ICON) {
            val logoInfo by collectLastValue(kosmos.promptViewModel.logoInfo)
            assertThat(logoInfo).isNotNull()
            assertThat(logoInfo!!.first).isNull()
        }

    @Test
    @EnableFlags(FLAG_CUSTOM_BIOMETRIC_PROMPT)
    fun logo_default() = runGenericTest {
        val logoInfo by collectLastValue(kosmos.promptViewModel.logoInfo)
        assertThat(logoInfo).isNotNull()
        assertThat(logoInfo!!.first).isEqualTo(defaultLogoIconFromAppInfo)
    }

    @Test
    @EnableFlags(FLAG_CUSTOM_BIOMETRIC_PROMPT)
    fun logo_resSetByApp() =
        runGenericTest(logoRes = logoResFromApp) {
            val expectedBitmap = context.getDrawable(logoResFromApp).toBitmap()
            val logoInfo by collectLastValue(kosmos.promptViewModel.logoInfo)
            assertThat(logoInfo).isNotNull()
            assertThat((logoInfo!!.first as BitmapDrawable).bitmap.sameAs(expectedBitmap)).isTrue()
        }

    @Test
    @EnableFlags(FLAG_CUSTOM_BIOMETRIC_PROMPT)
    fun logo_bitmapSetByApp() =
        runGenericTest(logoBitmap = logoBitmapFromApp) {
            val logoInfo by collectLastValue(kosmos.promptViewModel.logoInfo)
            assertThat((logoInfo!!.first as BitmapDrawable).bitmap).isEqualTo(logoBitmapFromApp)
        }

    @Test
    @EnableFlags(FLAG_CUSTOM_BIOMETRIC_PROMPT)
    fun logoDescription_emptyIfPkgNameNotFound() =
        runGenericTest(packageName = OP_PACKAGE_NAME_CAN_NOT_BE_FOUND) {
            val logoInfo by collectLastValue(kosmos.promptViewModel.logoInfo)
            assertThat(logoInfo!!.second).isEqualTo("")
        }

    @Test
    @EnableFlags(FLAG_CUSTOM_BIOMETRIC_PROMPT)
    fun logoDescription_defaultFromActivityInfo() =
        runGenericTest(packageName = OP_PACKAGE_NAME_WITH_ACTIVITY_LOGO) {
            val logoInfo by collectLastValue(kosmos.promptViewModel.logoInfo)
            // 1. PM.getApplicationInfo(packageNameForLogoWithOverrides) is set to return
            // applicationInfoWithIconAndDescription with defaultLogoDescription,
            // 2. activityInfo.loadLabel() is set to return defaultLogoDescriptionWithOverrides
            // For the apps with packageNameForLogoWithOverrides, 2 should be called instead of 1
            assertThat(logoInfo!!.second).isEqualTo(defaultLogoDescriptionFromActivityInfo)
        }

    @Test
    @EnableFlags(FLAG_CUSTOM_BIOMETRIC_PROMPT)
    fun logoDescription_defaultIsEmpty() =
        runGenericTest(packageName = OP_PACKAGE_NAME_NO_ICON) {
            val logoInfo by collectLastValue(kosmos.promptViewModel.logoInfo)
            assertThat(logoInfo!!.second).isEqualTo("")
        }

    @Test
    @EnableFlags(FLAG_CUSTOM_BIOMETRIC_PROMPT)
    fun logoDescription_default() = runGenericTest {
        val logoInfo by collectLastValue(kosmos.promptViewModel.logoInfo)
        assertThat(logoInfo!!.second).isEqualTo(defaultLogoDescriptionFromAppInfo)
    }

    @Test
    @EnableFlags(FLAG_CUSTOM_BIOMETRIC_PROMPT)
    fun logoDescription_setByApp() =
        runGenericTest(logoDescription = logoDescriptionFromApp) {
            val logoInfo by collectLastValue(kosmos.promptViewModel.logoInfo)
            assertThat(logoInfo!!.second).isEqualTo(logoDescriptionFromApp)
        }

    @Test
    fun position_bottom_rotation0() = runGenericTest {
        kosmos.displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_0)
        val position by collectLastValue(kosmos.promptViewModel.position)
        assertThat(position).isEqualTo(PromptPosition.Bottom)
    } // TODO(b/335278136): Add test for no sensor landscape

    @Test
    fun position_bottom_forceLarge() = runGenericTest {
        kosmos.displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_270)
        kosmos.promptViewModel.onSwitchToCredential()
        val position by collectLastValue(kosmos.promptViewModel.position)
        assertThat(position).isEqualTo(PromptPosition.Bottom)
    }

    @Test
    fun position_bottom_largeScreen() = runGenericTest {
        kosmos.displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_270)
        kosmos.displayStateRepository.setIsLargeScreen(true)
        val position by collectLastValue(kosmos.promptViewModel.position)
        assertThat(position).isEqualTo(PromptPosition.Bottom)
    }

    @Test
    fun position_right_rotation90() = runGenericTest {
        kosmos.displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_90)
        val position by collectLastValue(kosmos.promptViewModel.position)
        assertThat(position).isEqualTo(PromptPosition.Right)
    }

    @Test
    fun position_left_rotation270() = runGenericTest {
        kosmos.displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_270)
        val position by collectLastValue(kosmos.promptViewModel.position)
        assertThat(position).isEqualTo(PromptPosition.Left)
    }

    @Test
    fun position_top_rotation180() = runGenericTest {
        kosmos.displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_180)
        val position by collectLastValue(kosmos.promptViewModel.position)
        if (testCase.modalities.hasUdfps) {
            assertThat(position).isEqualTo(PromptPosition.Top)
        } else {
            assertThat(position).isEqualTo(PromptPosition.Bottom)
        }
    }

    @Test
    fun guideline_bottom() = runGenericTest {
        kosmos.displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_0)
        val guidelineBounds by collectLastValue(kosmos.promptViewModel.guidelineBounds)
        assertThat(guidelineBounds).isEqualTo(Rect(0, mediumTopGuidelinePadding, 0, 0))
    } // TODO(b/335278136): Add test for no sensor landscape

    @Test
    fun guideline_right() = runGenericTest {
        kosmos.displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_90)

        val isSmall = testCase.shouldStartAsImplicitFlow
        val guidelineBounds by collectLastValue(kosmos.promptViewModel.guidelineBounds)

        if (isSmall) {
            assertThat(guidelineBounds).isEqualTo(Rect(-smallHorizontalGuidelinePadding, 0, 0, 0))
        } else if (testCase.modalities.hasUdfps) {
            assertThat(guidelineBounds).isEqualTo(Rect(udfpsHorizontalGuidelinePadding, 0, 0, 0))
        } else {
            assertThat(guidelineBounds).isEqualTo(Rect(-mediumHorizontalGuidelinePadding, 0, 0, 0))
        }
    }

    @Test
    fun guideline_right_onlyShortTitle() =
        runGenericTest(subtitle = "") {
            kosmos.displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_90)

            val isSmall = testCase.shouldStartAsImplicitFlow
            val guidelineBounds by collectLastValue(kosmos.promptViewModel.guidelineBounds)

            if (!isSmall && testCase.modalities.hasUdfps) {
                assertThat(guidelineBounds)
                    .isEqualTo(Rect(-udfpsHorizontalShorterGuidelinePadding, 0, 0, 0))
            }
        }

    @Test
    fun guideline_left() = runGenericTest {
        kosmos.displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_270)

        val isSmall = testCase.shouldStartAsImplicitFlow
        val guidelineBounds by collectLastValue(kosmos.promptViewModel.guidelineBounds)

        if (isSmall) {
            assertThat(guidelineBounds).isEqualTo(Rect(0, 0, -smallHorizontalGuidelinePadding, 0))
        } else if (testCase.modalities.hasUdfps) {
            assertThat(guidelineBounds).isEqualTo(Rect(0, 0, udfpsHorizontalGuidelinePadding, 0))
        } else {
            assertThat(guidelineBounds).isEqualTo(Rect(0, 0, -mediumHorizontalGuidelinePadding, 0))
        }
    }

    @Test
    fun guideline_left_onlyShortTitle() =
        runGenericTest(subtitle = "") {
            kosmos.displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_270)

            val isSmall = testCase.shouldStartAsImplicitFlow
            val guidelineBounds by collectLastValue(kosmos.promptViewModel.guidelineBounds)

            if (!isSmall && testCase.modalities.hasUdfps) {
                assertThat(guidelineBounds)
                    .isEqualTo(Rect(0, 0, -udfpsHorizontalShorterGuidelinePadding, 0))
            }
        }

    @Test
    fun guideline_top() = runGenericTest {
        kosmos.displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_180)
        val guidelineBounds by collectLastValue(kosmos.promptViewModel.guidelineBounds)
        if (testCase.modalities.hasUdfps) {
            assertThat(guidelineBounds).isEqualTo(Rect(0, 0, 0, 0))
        }
    }

    @Test
    fun iconViewLoaded() = runGenericTest {
        val isIconViewLoaded by collectLastValue(kosmos.promptViewModel.isIconViewLoaded)
        // TODO(b/328677869): Add test for noIcon logic.
        assertThat(isIconViewLoaded).isFalse()

        kosmos.promptViewModel.setIsIconViewLoaded(true)

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
        assertThat(kosmos.promptViewModel.isTryAgainButtonVisible.first()).isEqualTo(tryAgain)
        assertThat(kosmos.promptViewModel.isConfirmButtonVisible.first()).isEqualTo(confirm)
        assertThat(kosmos.promptViewModel.isCancelButtonVisible.first()).isEqualTo(cancel)
        assertThat(kosmos.promptViewModel.isNegativeButtonVisible.first()).isEqualTo(negative)
        assertThat(kosmos.promptViewModel.isCredentialButtonVisible.first()).isEqualTo(credential)
    }

    private fun runGenericTest(
        doNotStart: Boolean = false,
        allowCredentialFallback: Boolean = false,
        subtitle: String? = "s",
        description: String? = null,
        contentView: PromptContentView? = null,
        logoRes: Int = 0,
        logoBitmap: Bitmap? = null,
        logoDescription: String? = null,
        packageName: String = OP_PACKAGE_NAME_WITH_APP_LOGO,
        block: suspend TestScope.() -> Unit,
    ) {
        val topActivity = ComponentName(packageName, "test app")
        runningTaskInfo.topActivity = topActivity
        whenever(kosmos.activityTaskManager.getTasks(1)).thenReturn(listOf(runningTaskInfo))
        kosmos.promptSelectorInteractor.resetPrompt(REQUEST_ID)

        kosmos.promptSelectorInteractor.initializePrompt(
            requireConfirmation = testCase.confirmationRequested,
            allowCredentialFallback = allowCredentialFallback,
            fingerprint = testCase.fingerprint,
            face = testCase.face,
            subtitleFromApp = subtitle,
            descriptionFromApp = description,
            contentViewFromApp = contentView,
            logoResFromApp = logoRes,
            logoBitmapFromApp = if (logoRes != 0) logoDrawableFromAppRes.toBitmap() else logoBitmap,
            logoDescriptionFromApp = logoDescription,
            packageName = packageName,
        )

        kosmos.biometricStatusRepository.setFingerprintAcquiredStatus(
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
                kosmos.promptViewModel.ensureFingerprintHasStarted(isDelayed = false)
                kosmos.promptViewModel.showAuthenticating()
            }
            FingerprintStartMode.Delayed -> {
                kosmos.promptViewModel.showAuthenticating()
            }
            else -> {
                /* skip */
            }
        }

        if (testCase.fingerprint?.isAnyUdfpsType == true) {
            kosmos.testScope.collectLastValue(kosmos.udfpsOverlayInteractor.udfpsOverlayParams)
            kosmos.testScope.runCurrent()
            overrideUdfpsOverlayParams()
        }

        kosmos.testScope.runTest { block() }
    }

    private fun overrideUdfpsOverlayParams(isLandscape: Boolean = false) {
        val authControllerCallback = authController.captureCallback()
        authControllerCallback.onUdfpsLocationChanged(
            mockUdfpsOverlayParams(isLandscape = isLandscape)
        )
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
                                sensorType = FingerprintSensorProperties.TYPE_REAR
                            )
                            .first(),
                    authenticatedModality = BiometricModality.Fingerprint,
                ),
                TestCase(
                    fingerprint =
                        fingerprintSensorPropertiesInternal(
                                strong = true,
                                sensorType = FingerprintSensorProperties.TYPE_POWER_BUTTON
                            )
                            .first(),
                    authenticatedModality = BiometricModality.Fingerprint,
                    isInRearDisplayMode = false,
                ),
                TestCase(
                    fingerprint =
                        fingerprintSensorPropertiesInternal(
                                strong = true,
                                sensorType = FingerprintSensorProperties.TYPE_POWER_BUTTON
                            )
                            .first(),
                    authenticatedModality = BiometricModality.Fingerprint,
                    isInRearDisplayMode = true,
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
    val isInRearDisplayMode: Boolean = false,
    val authenticatedModality: BiometricModality,
    val confirmationRequested: Boolean = false,
) {
    override fun toString(): String {
        val modality =
            when {
                fingerprint != null && face != null -> "coex"
                fingerprint != null && fingerprint.isAnySidefpsType -> "fingerprint only, sideFps"
                fingerprint != null && fingerprint.isAnyUdfpsType -> "fingerprint only, udfps"
                fingerprint != null &&
                    fingerprint.sensorType == FingerprintSensorProperties.TYPE_REAR ->
                    "fingerprint only, rearFps"
                face != null -> "face only"
                else -> "?"
            }
        return "[$modality, isInRearDisplayMode: $isInRearDisplayMode, by: " +
            "$authenticatedModality, confirm: $confirmationRequested]"
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
    logoResFromApp: Int = 0,
    logoBitmapFromApp: Bitmap? = null,
    logoDescriptionFromApp: String? = null,
    packageName: String = OP_PACKAGE_NAME_WITH_APP_LOGO,
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

private fun AuthController.captureCallback() =
    withArgCaptor<AuthController.Callback> {
        Mockito.verify(this@captureCallback).addCallback(capture())
    }
