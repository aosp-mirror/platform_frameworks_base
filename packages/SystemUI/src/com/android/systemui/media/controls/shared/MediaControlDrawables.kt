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
        return progress
            ?: context.getDrawable(com.android.internal.R.drawable.progress_small_material).also {
                if (!mediaControlsDrawablesReuse()) return@also
                progress = it
            }
    }

    fun getConnecting(context: Context): Drawable? {
        return connecting
            ?: context.getDrawable(R.drawable.ic_media_connecting_container).also {
                if (!mediaControlsDrawablesReuse()) return@also
                connecting = it
            }
    }

    fun getPlayIcon(context: Context): AnimatedVectorDrawable? {
        return playIcon?.let {
            it.reset()
            it
        }
            ?: (context.getDrawable(R.drawable.ic_media_play) as AnimatedVectorDrawable?).also {
                if (!mediaControlsDrawablesReuse()) return@also
                playIcon = it
            }
    }

    fun getPlayBackground(context: Context): AnimatedVectorDrawable? {
        return playBackground?.let {
            it.reset()
            it
        }
            ?: (context.getDrawable(R.drawable.ic_media_play_container) as AnimatedVectorDrawable?)
                .also {
                    if (!mediaControlsDrawablesReuse()) return@also
                    playBackground = it
                }
    }

    fun getPauseIcon(context: Context): AnimatedVectorDrawable? {
        return pauseIcon?.let {
            it.reset()
            it
        }
            ?: (context.getDrawable(R.drawable.ic_media_pause) as AnimatedVectorDrawable?).also {
                if (!mediaControlsDrawablesReuse()) return@also
                pauseIcon = it
            }
    }

    fun getPauseBackground(context: Context): AnimatedVectorDrawable? {
        return pauseBackground?.let {
            it.reset()
            it
        }
            ?: (context.getDrawable(R.drawable.ic_media_pause_container) as AnimatedVectorDrawable?)
                .also {
                    if (!mediaControlsDrawablesReuse()) return@also
                    pauseBackground = it
                }
    }

    fun getNextIcon(context: Context): Drawable? {
        return nextIcon
            ?: context.getDrawable(R.drawable.ic_media_next).also {
                if (!mediaControlsDrawablesReuse()) return@also
                nextIcon = it
            }
    }

    fun getPrevIcon(context: Context): Drawable? {
        return prevIcon
            ?: context.getDrawable(R.drawable.ic_media_prev).also {
                if (!mediaControlsDrawablesReuse()) return@also
                prevIcon = it
            }
    }

    fun getLeAudioSharing(context: Context): Drawable? {
        return leAudioSharing
            ?: context.getDrawable(com.android.settingslib.R.drawable.ic_bt_le_audio_sharing).also {
                if (!mediaControlsDrawablesReuse()) return@also
                leAudioSharing = it
            }
    }

    fun getAntenna(context: Context): Drawable? {
        return antenna
            ?: context.getDrawable(R.drawable.settings_input_antenna).also {
                if (!mediaControlsDrawablesReuse()) return@also
                antenna = it
            }
    }

    fun getGroupDevice(context: Context): Drawable? {
        return groupDevice
            ?: context.getDrawable(com.android.settingslib.R.drawable.ic_media_group_device).also {
                if (!mediaControlsDrawablesReuse()) return@also
                groupDevice = it
            }
    }

    fun getHomeDevices(context: Context): Drawable? {
        return homeDevices
            ?: context.getDrawable(R.drawable.ic_media_home_devices).also {
                if (!mediaControlsDrawablesReuse()) return@also
                homeDevices = it
            }
    }

    fun getOutline(context: Context): Drawable? {
        return outline
            ?: context.getDrawable(R.drawable.qs_media_outline_button).also {
                if (!mediaControlsDrawablesReuse()) return@also
                outline = it
            }
    }

    fun getSolid(context: Context): Drawable? {
        return solid
            ?: context.getDrawable(R.drawable.qs_media_solid_button).also {
                if (!mediaControlsDrawablesReuse()) return@also
                solid = it
            }
    }
}
