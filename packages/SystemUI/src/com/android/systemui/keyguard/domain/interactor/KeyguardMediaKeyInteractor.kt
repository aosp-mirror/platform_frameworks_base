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

package com.android.systemui.keyguard.domain.interactor

import android.media.AudioManager
import android.view.KeyEvent
import com.android.settingslib.volume.data.repository.AudioRepository
import com.android.systemui.bouncer.shared.flag.ComposeBouncerFlags
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.telephony.domain.interactor.TelephonyInteractor
import javax.inject.Inject

/** Handle media key events while on keyguard or bouncer. */
@SysUISingleton
class KeyguardMediaKeyInteractor
@Inject
constructor(
    private val telephonyInteractor: TelephonyInteractor,
    private val audioRepository: AudioRepository,
) : ExclusiveActivatable() {

    /**
     * Allows the media keys to work when the keyguard is showing. Forwards the relevant media keys
     * to [AudioManager].
     *
     * @param event The key event
     * @return whether the event was consumed as a media key.
     */
    fun processMediaKeyEvent(event: KeyEvent): Boolean {
        if (ComposeBouncerFlags.isUnexpectedlyInLegacyMode()) {
            return false
        }
        val keyCode = event.keyCode
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    /* Suppress PLAY/PAUSE toggle when phone is ringing or
                     * in-call to avoid music playback */
                    // suppress key event
                    return telephonyInteractor.isInCall.value
                }

                KeyEvent.KEYCODE_MUTE,
                KeyEvent.KEYCODE_HEADSETHOOK,
                KeyEvent.KEYCODE_MEDIA_STOP,
                KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                KeyEvent.KEYCODE_MEDIA_REWIND,
                KeyEvent.KEYCODE_MEDIA_RECORD,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> {
                    audioRepository.dispatchMediaKeyEvent(event)
                    return true
                }

                KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.KEYCODE_VOLUME_MUTE -> return false
            }
        } else if (event.action == KeyEvent.ACTION_UP) {
            when (keyCode) {
                KeyEvent.KEYCODE_MUTE,
                KeyEvent.KEYCODE_HEADSETHOOK,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_STOP,
                KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                KeyEvent.KEYCODE_MEDIA_REWIND,
                KeyEvent.KEYCODE_MEDIA_RECORD,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> {
                    audioRepository.dispatchMediaKeyEvent(event)
                    return true
                }
            }
        }
        return false
    }

    override suspend fun onActivated(): Nothing {
        // Collect to keep this flow hot for this interactor.
        telephonyInteractor.isInCall.collect {}
    }
}
