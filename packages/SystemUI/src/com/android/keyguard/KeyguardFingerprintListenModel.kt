/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.annotation.CurrentTimeMillisLong
import com.android.systemui.common.buffer.RingBuffer
import com.android.systemui.dump.DumpsysTableLogger
import com.android.systemui.dump.Row

/** Verbose debug information. */
data class KeyguardFingerprintListenModel(
    @CurrentTimeMillisLong override var timeMillis: Long = 0L,
    override var userId: Int = 0,
    override var listening: Boolean = false,
    // keepSorted
    var allowOnCurrentOccludingActivity: Boolean = false,
    var alternateBouncerShowing: Boolean = false,
    var biometricEnabledForUser: Boolean = false,
    var biometricPromptShowing: Boolean = false,
    var bouncerIsOrWillShow: Boolean = false,
    var canSkipBouncer: Boolean = false,
    var credentialAttempted: Boolean = false,
    var deviceInteractive: Boolean = false,
    var dreaming: Boolean = false,
    var fingerprintDisabled: Boolean = false,
    var fingerprintLockedOut: Boolean = false,
    var goingToSleep: Boolean = false,
    var keyguardGoingAway: Boolean = false,
    var keyguardIsVisible: Boolean = false,
    var keyguardOccluded: Boolean = false,
    var occludingAppRequestingFp: Boolean = false,
    var shouldListenForFingerprintAssistant: Boolean = false,
    var strongerAuthRequired: Boolean = false,
    var switchingUser: Boolean = false,
    var systemUser: Boolean = false,
    var udfps: Boolean = false,
    var userDoesNotHaveTrust: Boolean = false,
    var communalShowing: Boolean = false,
) : KeyguardListenModel() {

    /** List of [String] to be used as a [Row] with [DumpsysTableLogger]. */
    val asStringList: List<String> by lazy {
        listOf(
            DATE_FORMAT.format(timeMillis),
            timeMillis.toString(),
            userId.toString(),
            listening.toString(),
            // keep sorted
            allowOnCurrentOccludingActivity.toString(),
            alternateBouncerShowing.toString(),
            biometricEnabledForUser.toString(),
            biometricPromptShowing.toString(),
            bouncerIsOrWillShow.toString(),
            canSkipBouncer.toString(),
            credentialAttempted.toString(),
            deviceInteractive.toString(),
            dreaming.toString(),
            fingerprintDisabled.toString(),
            fingerprintLockedOut.toString(),
            goingToSleep.toString(),
            keyguardGoingAway.toString(),
            keyguardIsVisible.toString(),
            keyguardOccluded.toString(),
            occludingAppRequestingFp.toString(),
            shouldListenForFingerprintAssistant.toString(),
            strongerAuthRequired.toString(),
            switchingUser.toString(),
            systemUser.toString(),
            udfps.toString(),
            userDoesNotHaveTrust.toString(),
            communalShowing.toString(),
        )
    }

    /**
     * [RingBuffer] to store [KeyguardFingerprintListenModel]. After the buffer is full, it will
     * recycle old events.
     *
     * Do not use [append] to add new elements. Instead use [insert], as it will recycle if
     * necessary.
     */
    class Buffer {
        private val buffer = RingBuffer(CAPACITY) { KeyguardFingerprintListenModel() }

        fun insert(model: KeyguardFingerprintListenModel) {
            buffer.advance().apply {
                timeMillis = model.timeMillis
                userId = model.userId
                listening = model.listening
                // keep sorted
                allowOnCurrentOccludingActivity = model.allowOnCurrentOccludingActivity
                alternateBouncerShowing = model.alternateBouncerShowing
                biometricEnabledForUser = model.biometricEnabledForUser
                biometricPromptShowing = model.biometricPromptShowing
                bouncerIsOrWillShow = model.bouncerIsOrWillShow
                canSkipBouncer = model.canSkipBouncer
                credentialAttempted = model.credentialAttempted
                deviceInteractive = model.deviceInteractive
                dreaming = model.dreaming
                fingerprintDisabled = model.fingerprintDisabled
                fingerprintLockedOut = model.fingerprintLockedOut
                goingToSleep = model.goingToSleep
                keyguardGoingAway = model.keyguardGoingAway
                keyguardIsVisible = model.keyguardIsVisible
                keyguardOccluded = model.keyguardOccluded
                occludingAppRequestingFp = model.occludingAppRequestingFp
                shouldListenForFingerprintAssistant = model.shouldListenForFingerprintAssistant
                strongerAuthRequired = model.strongerAuthRequired
                switchingUser = model.switchingUser
                systemUser = model.systemUser
                udfps = model.udfps
                userDoesNotHaveTrust = model.userDoesNotHaveTrust
                communalShowing = model.communalShowing
            }
        }

        /**
         * Returns the content of the buffer (sorted from latest to newest).
         *
         * @see KeyguardFingerprintListenModel.asStringList
         */
        fun toList(): List<Row> {
            return buffer.asSequence().map { it.asStringList }.toList()
        }
    }

    companion object {
        const val CAPACITY = 20 // number of logs to retain

        /** Headers for dumping a table using [DumpsysTableLogger]. */
        @JvmField
        val TABLE_HEADERS =
            listOf(
                "timestamp",
                "time_millis",
                "userId",
                "listening",
                // keep sorted
                "allowOnCurrentOccludingActivity",
                "alternateBouncerShowing",
                "biometricAllowedForUser",
                "biometricPromptShowing",
                "bouncerIsOrWillShow",
                "canSkipBouncer",
                "credentialAttempted",
                "deviceInteractive",
                "dreaming",
                "fingerprintDisabled",
                "fingerprintLockedOut",
                "goingToSleep",
                "keyguardGoingAway",
                "keyguardIsVisible",
                "keyguardOccluded",
                "occludingAppRequestingFp",
                "shouldListenSidFingerprintState",
                "shouldListenForFingerprintAssistant",
                "strongAuthRequired",
                "switchingUser",
                "systemUser",
                "underDisplayFingerprint",
                "userDoesNotHaveTrust",
                "communalShowing",
            )
    }
}
