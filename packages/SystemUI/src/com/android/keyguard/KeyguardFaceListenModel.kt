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
import com.android.systemui.dump.DumpsysTableLogger
import com.android.systemui.dump.Row
import com.android.systemui.plugins.util.RingBuffer

/** Verbose debug information associated. */
data class KeyguardFaceListenModel(
    @CurrentTimeMillisLong override var timeMillis: Long = 0L,
    override var userId: Int = 0,
    override var listening: Boolean = false,
    // keep sorted
    var authInterruptActive: Boolean = false,
    var biometricSettingEnabledForUser: Boolean = false,
    var bouncerFullyShown: Boolean = false,
    var faceAndFpNotAuthenticated: Boolean = false,
    var faceAuthAllowed: Boolean = false,
    var faceDisabled: Boolean = false,
    var faceLockedOut: Boolean = false,
    var goingToSleep: Boolean = false,
    var keyguardAwake: Boolean = false,
    var keyguardGoingAway: Boolean = false,
    var listeningForFaceAssistant: Boolean = false,
    var occludingAppRequestingFaceAuth: Boolean = false,
    val postureAllowsListening: Boolean = false,
    var primaryUser: Boolean = false,
    var secureCameraLaunched: Boolean = false,
    var supportsDetect: Boolean = false,
    var switchingUser: Boolean = false,
    var udfpsBouncerShowing: Boolean = false,
    var udfpsFingerDown: Boolean = false,
    var userNotTrustedOrDetectionIsNeeded: Boolean = false,
) : KeyguardListenModel() {

    /** List of [String] to be used as a [Row] with [DumpsysTableLogger]. */
    val asStringList: List<String> by lazy {
        listOf(
            DATE_FORMAT.format(timeMillis),
            timeMillis.toString(),
            userId.toString(),
            listening.toString(),
            // keep sorted
            authInterruptActive.toString(),
            biometricSettingEnabledForUser.toString(),
            bouncerFullyShown.toString(),
            faceAndFpNotAuthenticated.toString(),
            faceAuthAllowed.toString(),
            faceDisabled.toString(),
            faceLockedOut.toString(),
            goingToSleep.toString(),
            keyguardAwake.toString(),
            keyguardGoingAway.toString(),
            listeningForFaceAssistant.toString(),
            occludingAppRequestingFaceAuth.toString(),
            primaryUser.toString(),
            secureCameraLaunched.toString(),
            supportsDetect.toString(),
            switchingUser.toString(),
            udfpsBouncerShowing.toString(),
            udfpsFingerDown.toString(),
            userNotTrustedOrDetectionIsNeeded.toString(),
        )
    }

    /**
     * [RingBuffer] to store [KeyguardFaceListenModel]. After the buffer is full, it will recycle
     * old events.
     *
     * Do not use [append] to add new elements. Instead use [insert], as it will recycle if
     * necessary.
     */
    class Buffer {
        private val buffer = RingBuffer(CAPACITY) { KeyguardFaceListenModel() }

        fun insert(model: KeyguardFaceListenModel) {
            buffer.advance().apply {
                timeMillis = model.timeMillis
                userId = model.userId
                listening = model.listening
                // keep sorted
                biometricSettingEnabledForUser = model.biometricSettingEnabledForUser
                bouncerFullyShown = model.bouncerFullyShown
                faceAndFpNotAuthenticated = model.faceAndFpNotAuthenticated
                faceAuthAllowed = model.faceAuthAllowed
                faceDisabled = model.faceDisabled
                faceLockedOut = model.faceLockedOut
                goingToSleep = model.goingToSleep
                keyguardAwake = model.keyguardAwake
                goingToSleep = model.goingToSleep
                keyguardGoingAway = model.keyguardGoingAway
                listeningForFaceAssistant = model.listeningForFaceAssistant
                occludingAppRequestingFaceAuth = model.occludingAppRequestingFaceAuth
                primaryUser = model.primaryUser
                secureCameraLaunched = model.secureCameraLaunched
                supportsDetect = model.supportsDetect
                switchingUser = model.switchingUser
                udfpsBouncerShowing = model.udfpsBouncerShowing
                switchingUser = model.switchingUser
                udfpsFingerDown = model.udfpsFingerDown
                userNotTrustedOrDetectionIsNeeded = model.userNotTrustedOrDetectionIsNeeded
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
        const val CAPACITY = 40 // number of logs to retain

        /** Headers for dumping a table using [DumpsysTableLogger]. */
        @JvmField
        val TABLE_HEADERS =
            listOf(
                "timestamp",
                "time_millis",
                "userId",
                "listening",
                // keep sorted
                "authInterruptActive",
                "biometricSettingEnabledForUser",
                "bouncerFullyShown",
                "faceAndFpNotAuthenticated",
                "faceAuthAllowed",
                "faceDisabled",
                "faceLockedOut",
                "goingToSleep",
                "keyguardAwake",
                "keyguardGoingAway",
                "listeningForFaceAssistant",
                "occludingAppRequestingFaceAuth",
                "primaryUser",
                "secureCameraLaunched",
                "supportsDetect",
                "switchingUser",
                "udfpsBouncerShowing",
                "udfpsFingerDown",
                "userNotTrustedOrDetectionIsNeeded",
            )
    }
}
