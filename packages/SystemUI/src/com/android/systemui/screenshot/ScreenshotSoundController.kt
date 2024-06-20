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

import android.media.MediaPlayer
import android.util.Log
import com.android.app.tracing.coroutines.async
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Controls sound reproduction after a screenshot is taken. */
interface ScreenshotSoundController {
    /** Reproduces the camera sound. */
    suspend fun playScreenshotSound()

    /**
     * Releases the sound. [playScreenshotSound] behaviour is undefined after this has been called.
     */
    suspend fun releaseScreenshotSound()

    /** Reproduces the camera sound. Used for compatibility with Java code. */
    fun playScreenshotSoundAsync()

    /**
     * Releases the sound. [playScreenshotSound] behaviour is undefined after this has been called.
     * Used for compatibility with Java code.
     */
    fun releaseScreenshotSoundAsync()
}

class ScreenshotSoundControllerImpl
@Inject
constructor(
    private val soundProvider: ScreenshotSoundProvider,
    @Application private val coroutineScope: CoroutineScope,
    @Background private val bgDispatcher: CoroutineDispatcher
) : ScreenshotSoundController {

    private val player: Deferred<MediaPlayer?> =
        coroutineScope.async("loadScreenshotSound", bgDispatcher) {
            try {
                soundProvider.getScreenshotSound()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Screenshot sound initialization failed", e)
                null
            }
        }

    override suspend fun playScreenshotSound() {
        withContext(bgDispatcher) {
            try {
                player.await()?.start()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Screenshot sound failed to play", e)
                releaseScreenshotSound()
            }
        }
    }

    override suspend fun releaseScreenshotSound() {
        withContext(bgDispatcher) {
            try {
                withTimeout(1.seconds) { player.await()?.release() }
            } catch (e: TimeoutCancellationException) {
                player.cancel()
                Log.w(TAG, "Error releasing shutter sound", e)
            }
        }
    }

    override fun playScreenshotSoundAsync() {
        coroutineScope.launch { playScreenshotSound() }
    }

    override fun releaseScreenshotSoundAsync() {
        coroutineScope.launch { releaseScreenshotSound() }
    }

    private companion object {
        const val TAG = "ScreenshotSoundControllerImpl"
    }
}
