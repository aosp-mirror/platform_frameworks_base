/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.bouncer.ui.helper

import android.view.HapticFeedbackConstants
import android.view.View
import com.android.keyguard.AuthInteractionProperties
import com.android.systemui.Flags
//noinspection CleanArchitectureDependencyViolation: Data layer only referenced for this enum class
import com.google.android.msdl.data.model.MSDLToken
import com.google.android.msdl.domain.MSDLPlayer

/** A helper object to deliver haptic feedback in bouncer interactions. */
object BouncerHapticHelper {

    private val authInteractionProperties = AuthInteractionProperties()

    /**
     * Deliver MSDL feedback as a result of authenticating through a bouncer.
     *
     * @param[authenticationSucceeded] Whether the authentication was successful or not.
     * @param[player] The [MSDLPlayer] that delivers the correct feedback.
     */
    fun playMSDLAuthenticationFeedback(
        authenticationSucceeded: Boolean,
        player: MSDLPlayer?,
    ) {
        if (player == null || !Flags.msdlFeedback()) {
            return
        }

        val token =
            if (authenticationSucceeded) {
                MSDLToken.UNLOCK
            } else {
                MSDLToken.FAILURE
            }
        player.playToken(token, authInteractionProperties)
    }

    /**
     * Deliver feedback when dragging through cells in the pattern bouncer. This function can play
     * MSDL feedback using a [MSDLPlayer], or fallback to a default haptic feedback using the
     * [View.performHapticFeedback] API and a [View].
     *
     * @param[player] [MSDLPlayer] for MSDL feedback.
     * @param[view] A [View] for default haptic feedback using [View.performHapticFeedback]
     */
    fun playPatternDotFeedback(player: MSDLPlayer?, view: View?) {
        if (player == null || !Flags.msdlFeedback()) {
            view?.performHapticFeedback(
                HapticFeedbackConstants.VIRTUAL_KEY,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING,
            )
        } else {
            player.playToken(MSDLToken.DRAG_INDICATOR)
        }
    }
}
