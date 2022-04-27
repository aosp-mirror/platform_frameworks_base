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

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.UserHandle
import android.provider.Settings.Secure.ACTIVE_UNLOCK_ON_BIOMETRIC_FAIL
import android.provider.Settings.Secure.ACTIVE_UNLOCK_ON_UNLOCK_INTENT
import android.provider.Settings.Secure.ACTIVE_UNLOCK_ON_WAKE
import com.android.keyguard.KeyguardUpdateMonitor.getCurrentUser
import com.android.systemui.Dumpable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.util.settings.SecureSettings
import java.io.PrintWriter
import javax.inject.Inject

/**
 * Handles active unlock settings changes.
 */
@SysUISingleton
class ActiveUnlockConfig @Inject constructor(
    @Main private val handler: Handler,
    private val secureSettings: SecureSettings,
    private val contentResolver: ContentResolver,
    dumpManager: DumpManager
) : Dumpable {

    /**
     * Indicates the origin for an active unlock request.
     */
    enum class ACTIVE_UNLOCK_REQUEST_ORIGIN {
        WAKE, UNLOCK_INTENT, BIOMETRIC_FAIL, ASSISTANT
    }

    private var requestActiveUnlockOnWakeup = false
    private var requestActiveUnlockOnUnlockIntent = false
    private var requestActiveUnlockOnBioFail = false

    private val settingsObserver = object : ContentObserver(handler) {
        private val wakeUri: Uri = secureSettings.getUriFor(ACTIVE_UNLOCK_ON_WAKE)
        private val unlockIntentUri: Uri = secureSettings.getUriFor(ACTIVE_UNLOCK_ON_UNLOCK_INTENT)
        private val bioFailUri: Uri = secureSettings.getUriFor(ACTIVE_UNLOCK_ON_BIOMETRIC_FAIL)

        fun register() {
            contentResolver.registerContentObserver(
                    wakeUri,
                    false,
                    this,
                    UserHandle.USER_ALL)
            contentResolver.registerContentObserver(
                    unlockIntentUri,
                    false,
                    this,
                    UserHandle.USER_ALL)
            contentResolver.registerContentObserver(
                    bioFailUri,
                    false,
                    this,
                    UserHandle.USER_ALL)

            onChange(true, ArrayList(), 0, getCurrentUser())
        }

        override fun onChange(
            selfChange: Boolean,
            uris: Collection<Uri>,
            flags: Int,
            userId: Int
        ) {
            if (getCurrentUser() != userId) {
                return
            }

            if (selfChange || uris.contains(wakeUri)) {
                requestActiveUnlockOnWakeup = secureSettings.getIntForUser(
                        ACTIVE_UNLOCK_ON_WAKE, 0, getCurrentUser()) == 1
            }

            if (selfChange || uris.contains(unlockIntentUri)) {
                requestActiveUnlockOnUnlockIntent = secureSettings.getIntForUser(
                        ACTIVE_UNLOCK_ON_UNLOCK_INTENT, 0, getCurrentUser()) == 1
            }

            if (selfChange || uris.contains(bioFailUri)) {
                requestActiveUnlockOnBioFail = secureSettings.getIntForUser(
                        ACTIVE_UNLOCK_ON_BIOMETRIC_FAIL, 0, getCurrentUser()) == 1
            }
        }
    }

    init {
        settingsObserver.register()
        dumpManager.registerDumpable(this)
    }

    /**
     * Whether to trigger active unlock based on where the request is coming from and
     * the current settings.
     */
    fun shouldAllowActiveUnlockFromOrigin(requestOrigin: ACTIVE_UNLOCK_REQUEST_ORIGIN): Boolean {
        return when (requestOrigin) {
            ACTIVE_UNLOCK_REQUEST_ORIGIN.WAKE -> requestActiveUnlockOnWakeup

            ACTIVE_UNLOCK_REQUEST_ORIGIN.UNLOCK_INTENT ->
                requestActiveUnlockOnUnlockIntent || requestActiveUnlockOnWakeup

            ACTIVE_UNLOCK_REQUEST_ORIGIN.BIOMETRIC_FAIL ->
                requestActiveUnlockOnBioFail || requestActiveUnlockOnUnlockIntent ||
                        requestActiveUnlockOnWakeup

            ACTIVE_UNLOCK_REQUEST_ORIGIN.ASSISTANT -> isActiveUnlockEnabled()
        }
    }

    /**
     * If any active unlock triggers are enabled.
     */
    fun isActiveUnlockEnabled(): Boolean {
        return requestActiveUnlockOnWakeup || requestActiveUnlockOnUnlockIntent ||
                requestActiveUnlockOnBioFail
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("   requestActiveUnlockOnWakeup=$requestActiveUnlockOnWakeup")
        pw.println("   requestActiveUnlockOnUnlockIntent=$requestActiveUnlockOnUnlockIntent")
        pw.println("   requestActiveUnlockOnBioFail=$requestActiveUnlockOnBioFail")
    }
}