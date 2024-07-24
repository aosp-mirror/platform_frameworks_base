package com.android.systemui.biometrics.domain.model

import android.graphics.Bitmap
import android.hardware.biometrics.PromptContentItemBulletedText
import android.hardware.biometrics.PromptVerticalListContentView
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.fingerprintSensorPropertiesInternal
import com.android.systemui.biometrics.promptInfo
import com.android.systemui.biometrics.shared.model.BiometricModalities
import com.android.systemui.biometrics.shared.model.BiometricUserInfo
import com.android.systemui.res.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

private const val USER_ID = 2
private const val OPERATION_ID = 8L
private const val OP_PACKAGE_NAME = "biometric.testapp"

@SmallTest
@RunWith(JUnit4::class)
class BiometricPromptRequestTest : SysuiTestCase() {

    @Test
    fun biometricRequestFromPromptInfo() {
        val logoRes = R.drawable.ic_cake
        val logoDescription = "test cake"
        val title = "what"
        val subtitle = "a"
        val description = "request"
        val contentView =
            PromptVerticalListContentView.Builder()
                .setDescription("content description")
                .addListItem(PromptContentItemBulletedText("content item 1"))
                .addListItem(PromptContentItemBulletedText("content item 2"), 1)
                .build()

        val fpPros = fingerprintSensorPropertiesInternal().first()
        val request =
            BiometricPromptRequest.Biometric(
                promptInfo(
                    logoRes = logoRes,
                    logoDescription = logoDescription,
                    title = title,
                    subtitle = subtitle,
                    description = description,
                    contentView = contentView
                ),
                BiometricUserInfo(USER_ID),
                BiometricOperationInfo(OPERATION_ID),
                BiometricModalities(fingerprintProperties = fpPros),
                OP_PACKAGE_NAME,
            )

        assertThat(request.logoRes).isEqualTo(logoRes)
        assertThat(request.logoDescription).isEqualTo(logoDescription)
        assertThat(request.title).isEqualTo(title)
        assertThat(request.subtitle).isEqualTo(subtitle)
        assertThat(request.description).isEqualTo(description)
        assertThat(request.contentView).isEqualTo(contentView)
        assertThat(request.userInfo).isEqualTo(BiometricUserInfo(USER_ID))
        assertThat(request.operationInfo).isEqualTo(BiometricOperationInfo(OPERATION_ID))
        assertThat(request.modalities)
            .isEqualTo(BiometricModalities(fingerprintProperties = fpPros))
    }

    @Test
    fun biometricRequestLogoBitmapFromPromptInfo() {
        val logoBitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        val fpPros = fingerprintSensorPropertiesInternal().first()
        val request =
            BiometricPromptRequest.Biometric(
                promptInfo(
                    logoBitmap = logoBitmap,
                ),
                BiometricUserInfo(USER_ID),
                BiometricOperationInfo(OPERATION_ID),
                BiometricModalities(fingerprintProperties = fpPros),
                OP_PACKAGE_NAME,
            )
        assertThat(request.logoBitmap).isEqualTo(logoBitmap)
    }

    @Test
    fun credentialRequestFromPromptInfo() {
        val title = "what"
        val subtitle = "a"
        val description = "request"
        val stealth = true

        val toCheck =
            listOf(
                BiometricPromptRequest.Credential.Pin(
                    promptInfo(
                        title = title,
                        subtitle = subtitle,
                        description = description,
                        credentialTitle = null,
                        credentialSubtitle = null,
                        credentialDescription = null,
                    ),
                    BiometricUserInfo(USER_ID),
                    BiometricOperationInfo(OPERATION_ID),
                ),
                BiometricPromptRequest.Credential.Password(
                    promptInfo(
                        credentialTitle = title,
                        credentialSubtitle = subtitle,
                        credentialDescription = description,
                    ),
                    BiometricUserInfo(USER_ID),
                    BiometricOperationInfo(OPERATION_ID),
                ),
                BiometricPromptRequest.Credential.Pattern(
                    promptInfo(
                        subtitle = subtitle,
                        description = description,
                        credentialTitle = title,
                        credentialSubtitle = null,
                        credentialDescription = null,
                    ),
                    BiometricUserInfo(USER_ID),
                    BiometricOperationInfo(OPERATION_ID),
                    stealth,
                )
            )

        for (request in toCheck) {
            assertThat(request.title).isEqualTo(title)
            assertThat(request.subtitle).isEqualTo(subtitle)
            assertThat(request.description).isEqualTo(description)
            assertThat(request.userInfo).isEqualTo(BiometricUserInfo(USER_ID))
            assertThat(request.operationInfo).isEqualTo(BiometricOperationInfo(OPERATION_ID))
            if (request is BiometricPromptRequest.Credential.Pattern) {
                assertThat(request.stealthMode).isEqualTo(stealth)
            }
        }
    }
}
