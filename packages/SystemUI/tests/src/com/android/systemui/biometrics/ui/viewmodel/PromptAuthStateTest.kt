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

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.shared.model.BiometricModality
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@SmallTest
@RunWith(JUnit4::class)
class PromptAuthStateTest : SysuiTestCase() {

    @Test
    fun notAuthenticated() {
        with(PromptAuthState(isAuthenticated = false)) {
            assertThat(isNotAuthenticated).isTrue()
            assertThat(isAuthenticatedAndConfirmed).isFalse()
            assertThat(isAuthenticatedAndExplicitlyConfirmed).isFalse()
            assertThat(isAuthenticatedByFace).isFalse()
            assertThat(isAuthenticatedByFingerprint).isFalse()
        }
    }

    @Test
    fun authenticatedByUnknown() {
        with(PromptAuthState(isAuthenticated = true)) {
            assertThat(isNotAuthenticated).isFalse()
            assertThat(isAuthenticatedAndConfirmed).isTrue()
            assertThat(isAuthenticatedAndExplicitlyConfirmed).isFalse()
            assertThat(isAuthenticatedByFace).isFalse()
            assertThat(isAuthenticatedByFingerprint).isFalse()
        }

        with(PromptAuthState(isAuthenticated = true, needsUserConfirmation = true)) {
            assertThat(isNotAuthenticated).isFalse()
            assertThat(isAuthenticatedAndConfirmed).isFalse()
            assertThat(isAuthenticatedAndExplicitlyConfirmed).isFalse()
            assertThat(isAuthenticatedByFace).isFalse()
            assertThat(isAuthenticatedByFingerprint).isFalse()

            assertThat(asExplicitlyConfirmed().isAuthenticatedAndConfirmed).isTrue()
            assertThat(asExplicitlyConfirmed().isAuthenticatedAndExplicitlyConfirmed).isTrue()
        }
    }

    @Test
    fun authenticatedWithFace() {
        with(
            PromptAuthState(isAuthenticated = true, authenticatedModality = BiometricModality.Face)
        ) {
            assertThat(isNotAuthenticated).isFalse()
            assertThat(isAuthenticatedAndConfirmed).isTrue()
            assertThat(isAuthenticatedAndExplicitlyConfirmed).isFalse()
            assertThat(isAuthenticatedByFace).isTrue()
            assertThat(isAuthenticatedByFingerprint).isFalse()
        }
    }

    @Test
    fun authenticatedWithFingerprint() {
        with(
            PromptAuthState(
                isAuthenticated = true,
                authenticatedModality = BiometricModality.Fingerprint,
            )
        ) {
            assertThat(isNotAuthenticated).isFalse()
            assertThat(isAuthenticatedAndConfirmed).isTrue()
            assertThat(isAuthenticatedAndExplicitlyConfirmed).isFalse()
            assertThat(isAuthenticatedByFace).isFalse()
            assertThat(isAuthenticatedByFingerprint).isTrue()
        }
    }
}
