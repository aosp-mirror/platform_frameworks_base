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
import android.graphics.drawable.Drawable
import com.android.systemui.Flags.mediaControlsDrawablesReuse
import com.android.systemui.res.R

object MediaControlDrawables {

    // Prev button.
    private var prevIcon: Drawable? = null
    // Next button.
    private var nextIcon: Drawable? = null
    // Output switcher drawables.
    private var leAudioSharing: Drawable? = null
    private var antenna: Drawable? = null
    private var groupDevice: Drawable? = null
    private var homeDevices: Drawable? = null

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
}
