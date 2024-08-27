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

package com.android.systemui.media.controls.shared

import android.content.Context
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.Drawable
import com.android.systemui.Flags.mediaControlsDrawablesReuse
import com.android.systemui.res.R

object MediaControlDrawables {

    // Play/Pause Button drawables.
    private var progress: Drawable? = null
    private var connecting: Drawable? = null
    private var playIcon: AnimatedVectorDrawable? = null
    private var playBackground: AnimatedVectorDrawable? = null
    private var pauseIcon: AnimatedVectorDrawable? = null
    private var pauseBackground: AnimatedVectorDrawable? = null
    // Prev button.
    private var prevIcon: Drawable? = null
    // Next button.
    private var nextIcon: Drawable? = null
    // Output switcher drawables.
    private var leAudioSharing: Drawable? = null
    private var antenna: Drawable? = null
    private var groupDevice: Drawable? = null
    private var homeDevices: Drawable? = null
    // Guts drawables.
    private var outline: Drawable? = null
    private var solid: Drawable? = null

    fun getProgress(context: Context): Drawable? {
        if (!mediaControlsDrawablesReuse()) {
            return context.getDrawable(com.android.internal.R.drawable.progress_small_material)
        }
        return progress?.mutate()
            ?: context.getDrawable(com.android.internal.R.drawable.progress_small_material).also {
                progress = it
            }
    }

    fun getConnecting(context: Context): Drawable? {
        if (!mediaControlsDrawablesReuse()) {
            return context.getDrawable(R.drawable.ic_media_connecting_container)
        }
        return connecting?.mutate()
            ?: context.getDrawable(R.drawable.ic_media_connecting_container).also {
                connecting = it
            }
    }

    fun getPlayIcon(context: Context): AnimatedVectorDrawable? {
        if (!mediaControlsDrawablesReuse()) {
            return context.getDrawable(R.drawable.ic_media_play) as AnimatedVectorDrawable?
        }
        return playIcon?.let {
            it.reset()
            it.mutate() as AnimatedVectorDrawable
        }
            ?: (context.getDrawable(R.drawable.ic_media_play) as AnimatedVectorDrawable?).also {
                playIcon = it
            }
    }

    fun getPlayBackground(context: Context): AnimatedVectorDrawable? {
        if (!mediaControlsDrawablesReuse()) {
            return context.getDrawable(R.drawable.ic_media_play_container)
                as AnimatedVectorDrawable?
        }
        return playBackground?.let {
            it.reset()
            it.mutate() as AnimatedVectorDrawable
        }
            ?: (context.getDrawable(R.drawable.ic_media_play_container) as AnimatedVectorDrawable?)
                .also { playBackground = it }
    }

    fun getPauseIcon(context: Context): AnimatedVectorDrawable? {
        if (!mediaControlsDrawablesReuse()) {
            return context.getDrawable(R.drawable.ic_media_pause) as AnimatedVectorDrawable?
        }
        return pauseIcon?.let {
            it.reset()
            it.mutate() as AnimatedVectorDrawable
        }
            ?: (context.getDrawable(R.drawable.ic_media_pause) as AnimatedVectorDrawable?).also {
                pauseIcon = it
            }
    }

    fun getPauseBackground(context: Context): AnimatedVectorDrawable? {
        if (!mediaControlsDrawablesReuse()) {
            return context.getDrawable(R.drawable.ic_media_pause_container)
                as AnimatedVectorDrawable?
        }
        return pauseBackground?.let {
            it.reset()
            it.mutate() as AnimatedVectorDrawable
        }
            ?: (context.getDrawable(R.drawable.ic_media_pause_container) as AnimatedVectorDrawable?)
                .also { pauseBackground = it }
    }

    fun getNextIcon(context: Context): Drawable? {
        if (!mediaControlsDrawablesReuse()) {
            return context.getDrawable(R.drawable.ic_media_next)
        }
        return nextIcon ?: context.getDrawable(R.drawable.ic_media_next).also { nextIcon = it }
    }

    fun getPrevIcon(context: Context): Drawable? {
        if (!mediaControlsDrawablesReuse()) {
            return context.getDrawable(R.drawable.ic_media_prev)
        }
        return prevIcon ?: context.getDrawable(R.drawable.ic_media_prev).also { prevIcon = it }
    }

    fun getLeAudioSharing(context: Context): Drawable? {
        if (!mediaControlsDrawablesReuse()) {
            return context.getDrawable(com.android.settingslib.R.drawable.ic_bt_le_audio_sharing)
        }
        return leAudioSharing
            ?: context.getDrawable(com.android.settingslib.R.drawable.ic_bt_le_audio_sharing).also {
                leAudioSharing = it
            }
    }

    fun getAntenna(context: Context): Drawable? {
        if (!mediaControlsDrawablesReuse()) {
            return context.getDrawable(R.drawable.settings_input_antenna)
        }
        return antenna
            ?: context.getDrawable(R.drawable.settings_input_antenna).also { antenna = it }
    }

    fun getGroupDevice(context: Context): Drawable? {
        if (!mediaControlsDrawablesReuse()) {
            return context.getDrawable(com.android.settingslib.R.drawable.ic_media_group_device)
        }
        return groupDevice
            ?: context.getDrawable(com.android.settingslib.R.drawable.ic_media_group_device).also {
                groupDevice = it
            }
    }

    fun getHomeDevices(context: Context): Drawable? {
        if (!mediaControlsDrawablesReuse()) {
            return context.getDrawable(R.drawable.ic_media_home_devices)
        }
        return homeDevices
            ?: context.getDrawable(R.drawable.ic_media_home_devices).also { homeDevices = it }
    }

    fun getOutline(context: Context): Drawable? {
        if (!mediaControlsDrawablesReuse()) {
            return context.getDrawable(R.drawable.qs_media_outline_button)
        }
        return outline
            ?: context.getDrawable(R.drawable.qs_media_outline_button).also { outline = it }
    }

    fun getSolid(context: Context): Drawable? {
        if (!mediaControlsDrawablesReuse()) {
            return context.getDrawable(R.drawable.qs_media_solid_button)
        }
        return solid ?: context.getDrawable(R.drawable.qs_media_solid_button).also { solid = it }
    }
}
