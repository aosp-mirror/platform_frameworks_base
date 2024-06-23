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
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import java.lang.IllegalStateException
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

@SmallTest
class ScreenshotSoundControllerTest : SysuiTestCase() {

    private val soundProvider = mock<ScreenshotSoundProvider>()
    private val mediaPlayer = mock<MediaPlayer>()
    private val bgDispatcher = UnconfinedTestDispatcher()
    private val scope = TestScope(bgDispatcher)
    @Before
    fun setup() {
        whenever(soundProvider.getScreenshotSound()).thenReturn(mediaPlayer)
    }

    @Test
    fun init_soundLoading() {
        createController()
        bgDispatcher.scheduler.runCurrent()

        verify(soundProvider).getScreenshotSound()
    }

    @Test
    fun init_soundLoadingException_playAndReleaseDoNotThrow() = runTest {
        whenever(soundProvider.getScreenshotSound()).thenThrow(IllegalStateException())

        val controller = createController()

        controller.playCameraSound().await()
        controller.releaseScreenshotSound().await()

        verify(mediaPlayer, never()).start()
        verify(mediaPlayer, never()).release()
    }

    @Test
    fun playCameraSound_soundLoadingSuccessful_mediaPlayerPlays() = runTest {
        val controller = createController()

        controller.playCameraSound().await()

        verify(mediaPlayer).start()
    }

    @Test
    fun playCameraSound_illegalStateException_doesNotThrow() = runTest {
        whenever(mediaPlayer.start()).thenThrow(IllegalStateException())

        val controller = createController()
        controller.playCameraSound().await()

        verify(mediaPlayer).start()
        verify(mediaPlayer).release()
    }

    @Test
    fun playCameraSound_soundLoadingSuccessful_mediaPlayerReleases() = runTest {
        val controller = createController()

        controller.releaseScreenshotSound().await()

        verify(mediaPlayer).release()
    }

    private fun createController() =
        ScreenshotSoundControllerImpl(soundProvider, scope, bgDispatcher)
}
