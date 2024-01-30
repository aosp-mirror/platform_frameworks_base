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

package com.android.systemui.screenshot

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioSystem
import android.media.MediaPlayer
import android.net.Uri
import com.android.internal.R
import com.android.systemui.dagger.SysUISingleton
import java.io.File
import javax.inject.Inject

/** Provides a [MediaPlayer] that reproduces the screenshot sound. */
interface ScreenshotSoundProvider {

    /**
     * Creates a new [MediaPlayer] that reproduces the screenshot sound. This should be called from
     * a background thread, as it might take time.
     */
    fun getScreenshotSound(): MediaPlayer
}

@SysUISingleton
class ScreenshotSoundProviderImpl
@Inject
constructor(
    private val context: Context,
) : ScreenshotSoundProvider {
    override fun getScreenshotSound(): MediaPlayer {
        return MediaPlayer.create(
            context,
            Uri.fromFile(File(context.resources.getString(R.string.config_cameraShutterSound))),
            /* holder = */ null,
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
            AudioSystem.newAudioSessionId()
        )
    }
}
