/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.keyguard

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidTestingRunner::class)
@SmallTest
class KeyguardListenQueueTest : SysuiTestCase() {

    @Test
    fun testQueueIsBounded() {
        val size = 5
        val queue = KeyguardListenQueue(sizePerModality = size)

        val fingerprints = List(100) { fingerprintModel(it) }
        fingerprints.forEach { queue.add(it) }

        assertThat(queue.models).containsExactlyElementsIn(fingerprints.takeLast(size))

        val faces = List(100) { faceModel(it) }
        faces.forEach { queue.add(it) }

        assertThat(queue.models).containsExactlyElementsIn(
            faces.takeLast(size) + fingerprints.takeLast(5)
        )

        repeat(100) {
            queue.add(faceModel(-1))
            queue.add(fingerprintModel(-1))
        }
        assertThat(queue.models).hasSize(2 * size)
        assertThat(queue.models.count { it.userId == -1 }).isEqualTo(2 * size)
    }
}

private fun fingerprintModel(user: Int) = KeyguardFingerprintListenModel(
    timeMillis = System.currentTimeMillis(),
    userId = user,
    listening = false,
    biometricEnabledForUser = false,
    bouncer = false,
    canSkipBouncer = false,
    credentialAttempted = false,
    deviceInteractive = false,
    dreaming = false,
    encryptedOrLockdown = false,
    fingerprintDisabled = false,
    fingerprintLockedOut = false,
    goingToSleep = false,
    keyguardGoingAway = false,
    keyguardIsVisible = false,
    keyguardOccluded = false,
    occludingAppRequestingFp = false,
    primaryUser = false,
    shouldListenForFingerprintAssistant = false,
    switchingUser = false,
    udfps = false,
    userDoesNotHaveTrust = false,
    userNeedsStrongAuth = false
)

private fun faceModel(user: Int) = KeyguardFaceListenModel(
    timeMillis = System.currentTimeMillis(),
    userId = user,
    listening = false,
    authInterruptActive = false,
    becauseCannotSkipBouncer = false,
    biometricSettingEnabledForUser = false,
    bouncer = false,
    faceAuthenticated = false,
    faceDisabled = false,
    keyguardAwake = false,
    keyguardGoingAway = false,
    listeningForFaceAssistant = false,
    lockIconPressed = false,
    occludingAppRequestingFaceAuth = false,
    primaryUser = false,
    scanningAllowedByStrongAuth = false,
    secureCameraLaunched = false,
    switchingUser = false
)
