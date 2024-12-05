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

package com.android.systemui.media.controls.domain.pipeline

import android.media.session.MediaSession
import android.os.Bundle
import android.os.Handler
import android.os.looper
import android.testing.TestableLooper.RunWithLooper
import androidx.media.utils.MediaConstants
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaController as Media3Controller
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.graphics.imageLoader
import com.android.systemui.kosmos.testScope
import com.android.systemui.media.controls.shared.mediaLogger
import com.android.systemui.media.controls.shared.model.MediaButton
import com.android.systemui.media.controls.util.fakeMediaControllerFactory
import com.android.systemui.media.controls.util.fakeSessionTokenFactory
import com.android.systemui.res.R
import com.android.systemui.testKosmos
import com.android.systemui.util.concurrency.execution
import com.google.common.collect.ImmutableList
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

private const val PACKAGE_NAME = "package_name"
private const val CUSTOM_ACTION_NAME = "Custom Action"
private const val CUSTOM_ACTION_COMMAND = "custom-action"

@SmallTest
@RunWithLooper
@RunWith(AndroidJUnit4::class)
class Media3ActionFactoryTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val controllerFactory = kosmos.fakeMediaControllerFactory
    private val tokenFactory = kosmos.fakeSessionTokenFactory

    private val commandCaptor = argumentCaptor<SessionCommand>()
    private val runnableCaptor = argumentCaptor<Runnable>()

    private val legacyToken = MediaSession.Token(1, null)
    private val token = mock<SessionToken>()
    private val handler =
        mock<Handler> {
            on { post(runnableCaptor.capture()) } doAnswer
                {
                    runnableCaptor.lastValue.run()
                    true
                }
        }
    private val customLayout = ImmutableList.of<CommandButton>()
    private val media3Controller =
        mock<Media3Controller> {
            on { customLayout } doReturn customLayout
            on { sessionExtras } doReturn Bundle()
            on { isCommandAvailable(any()) } doReturn true
            on { isSessionCommandAvailable(any<SessionCommand>()) } doReturn true
        }

    private lateinit var underTest: Media3ActionFactory

    @Before
    fun setup() {
        underTest =
            Media3ActionFactory(
                context,
                kosmos.imageLoader,
                controllerFactory,
                tokenFactory,
                kosmos.mediaLogger,
                kosmos.looper,
                handler,
                kosmos.testScope,
                kosmos.execution,
            )

        controllerFactory.setMedia3Controller(media3Controller)
        tokenFactory.setMedia3SessionToken(token)
    }

    @Test
    fun media3Actions_playingState_withCustomActions() =
        testScope.runTest {
            // Media is playing, all commands available, with custom actions
            val customLayout = ImmutableList.copyOf((0..1).map { createCustomCommandButton(it) })
            whenever(media3Controller.customLayout).thenReturn(customLayout)
            whenever(media3Controller.isPlaying).thenReturn(true)
            val result = getActions()

            assertThat(result).isNotNull()

            val actions = result!!
            assertThat(actions.playOrPause!!.contentDescription)
                .isEqualTo(context.getString(R.string.controls_media_button_pause))
            actions.playOrPause!!.action!!.run()
            runCurrent()
            verify(media3Controller).pause()
            verify(media3Controller).release()
            clearInvocations(media3Controller)

            assertThat(actions.prevOrCustom!!.contentDescription)
                .isEqualTo(context.getString(R.string.controls_media_button_prev))
            actions.prevOrCustom!!.action!!.run()
            runCurrent()
            verify(media3Controller).seekToPrevious()
            verify(media3Controller).release()
            clearInvocations(media3Controller)

            assertThat(actions.nextOrCustom!!.contentDescription)
                .isEqualTo(context.getString(R.string.controls_media_button_next))
            actions.nextOrCustom!!.action!!.run()
            runCurrent()
            verify(media3Controller).seekToNext()
            verify(media3Controller).release()
            clearInvocations(media3Controller)

            assertThat(actions.custom0!!.contentDescription).isEqualTo("$CUSTOM_ACTION_NAME 0")
            actions.custom0!!.action!!.run()
            runCurrent()
            verify(media3Controller).sendCustomCommand(commandCaptor.capture(), any<Bundle>())
            assertThat(commandCaptor.lastValue.customAction).isEqualTo("$CUSTOM_ACTION_COMMAND 0")
            verify(media3Controller).release()
            clearInvocations(media3Controller)

            assertThat(actions.custom1!!.contentDescription).isEqualTo("$CUSTOM_ACTION_NAME 1")
            actions.custom1!!.action!!.run()
            runCurrent()
            verify(media3Controller).sendCustomCommand(commandCaptor.capture(), any<Bundle>())
            assertThat(commandCaptor.lastValue.customAction).isEqualTo("$CUSTOM_ACTION_COMMAND 1")
            verify(media3Controller).release()
        }

    @Test
    fun media3Actions_pausedState_hasPauseAction() =
        testScope.runTest {
            whenever(media3Controller.isPlaying).thenReturn(false)
            val result = getActions()

            assertThat(result).isNotNull()
            val actions = result!!
            assertThat(actions.playOrPause!!.contentDescription)
                .isEqualTo(context.getString(R.string.controls_media_button_play))
            clearInvocations(media3Controller)

            actions.playOrPause!!.action!!.run()
            runCurrent()
            verify(media3Controller).play()
            verify(media3Controller).release()
            clearInvocations(media3Controller)
        }

    @Test
    fun media3Actions_bufferingState_hasLoadingSpinner() =
        testScope.runTest {
            whenever(media3Controller.isPlaying).thenReturn(false)
            whenever(media3Controller.playbackState).thenReturn(Player.STATE_BUFFERING)
            val result = getActions()

            assertThat(result).isNotNull()
            val actions = result!!
            assertThat(actions.playOrPause!!.contentDescription)
                .isEqualTo(context.getString(R.string.controls_media_button_connecting))
            assertThat(actions.playOrPause!!.action).isNull()
            assertThat(actions.playOrPause!!.rebindId)
                .isEqualTo(com.android.internal.R.drawable.progress_small_material)
        }

    @Test
    fun media3Actions_noPrevNext_usesCustom() =
        testScope.runTest {
            val customLayout = ImmutableList.copyOf((0..4).map { createCustomCommandButton(it) })
            whenever(media3Controller.customLayout).thenReturn(customLayout)
            whenever(media3Controller.isPlaying).thenReturn(true)
            whenever(media3Controller.isCommandAvailable(eq(Player.COMMAND_SEEK_TO_PREVIOUS)))
                .thenReturn(false)
            whenever(
                    media3Controller.isCommandAvailable(
                        eq(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    )
                )
                .thenReturn(false)
            whenever(media3Controller.isCommandAvailable(eq(Player.COMMAND_SEEK_TO_NEXT)))
                .thenReturn(false)
            whenever(
                    media3Controller.isCommandAvailable(eq(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
                )
                .thenReturn(false)
            val result = getActions()

            assertThat(result).isNotNull()
            val actions = result!!

            assertThat(actions.prevOrCustom!!.contentDescription).isEqualTo("$CUSTOM_ACTION_NAME 0")
            actions.prevOrCustom!!.action!!.run()
            runCurrent()
            verify(media3Controller).sendCustomCommand(commandCaptor.capture(), any<Bundle>())
            assertThat(commandCaptor.lastValue.customAction).isEqualTo("$CUSTOM_ACTION_COMMAND 0")
            verify(media3Controller).release()
            clearInvocations(media3Controller)

            assertThat(actions.nextOrCustom!!.contentDescription).isEqualTo("$CUSTOM_ACTION_NAME 1")
            actions.nextOrCustom!!.action!!.run()
            runCurrent()
            verify(media3Controller).sendCustomCommand(commandCaptor.capture(), any<Bundle>())
            assertThat(commandCaptor.lastValue.customAction).isEqualTo("$CUSTOM_ACTION_COMMAND 1")
            verify(media3Controller).release()
            clearInvocations(media3Controller)

            assertThat(actions.custom0!!.contentDescription).isEqualTo("$CUSTOM_ACTION_NAME 2")
            actions.custom0!!.action!!.run()
            runCurrent()
            verify(media3Controller).sendCustomCommand(commandCaptor.capture(), any<Bundle>())
            assertThat(commandCaptor.lastValue.customAction).isEqualTo("$CUSTOM_ACTION_COMMAND 2")
            verify(media3Controller).release()
            clearInvocations(media3Controller)

            assertThat(actions.custom1!!.contentDescription).isEqualTo("$CUSTOM_ACTION_NAME 3")
            actions.custom1!!.action!!.run()
            runCurrent()
            verify(media3Controller).sendCustomCommand(commandCaptor.capture(), any<Bundle>())
            assertThat(commandCaptor.lastValue.customAction).isEqualTo("$CUSTOM_ACTION_COMMAND 3")
            verify(media3Controller).release()
        }

    @Test
    fun media3Actions_noPrevNext_reservedSpace() =
        testScope.runTest {
            val customLayout = ImmutableList.copyOf((0..4).map { createCustomCommandButton(it) })
            whenever(media3Controller.customLayout).thenReturn(customLayout)
            whenever(media3Controller.isPlaying).thenReturn(true)
            whenever(media3Controller.isCommandAvailable(eq(Player.COMMAND_SEEK_TO_PREVIOUS)))
                .thenReturn(false)
            whenever(
                    media3Controller.isCommandAvailable(
                        eq(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    )
                )
                .thenReturn(false)
            whenever(media3Controller.isCommandAvailable(eq(Player.COMMAND_SEEK_TO_NEXT)))
                .thenReturn(false)
            whenever(
                    media3Controller.isCommandAvailable(eq(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
                )
                .thenReturn(false)
            val extras =
                Bundle().apply {
                    putBoolean(
                        MediaConstants.SESSION_EXTRAS_KEY_SLOT_RESERVATION_SKIP_TO_PREV,
                        true,
                    )
                    putBoolean(
                        MediaConstants.SESSION_EXTRAS_KEY_SLOT_RESERVATION_SKIP_TO_NEXT,
                        true,
                    )
                }
            whenever(media3Controller.sessionExtras).thenReturn(extras)
            val result = getActions()

            assertThat(result).isNotNull()
            val actions = result!!

            assertThat(actions.prevOrCustom).isNull()
            assertThat(actions.nextOrCustom).isNull()

            assertThat(actions.custom0!!.contentDescription).isEqualTo("$CUSTOM_ACTION_NAME 0")
            actions.custom0!!.action!!.run()
            runCurrent()
            verify(media3Controller).sendCustomCommand(commandCaptor.capture(), any<Bundle>())
            assertThat(commandCaptor.lastValue.customAction).isEqualTo("$CUSTOM_ACTION_COMMAND 0")
            verify(media3Controller).release()
            clearInvocations(media3Controller)

            assertThat(actions.custom1!!.contentDescription).isEqualTo("$CUSTOM_ACTION_NAME 1")
            actions.custom1!!.action!!.run()
            runCurrent()
            verify(media3Controller).sendCustomCommand(commandCaptor.capture(), any<Bundle>())
            assertThat(commandCaptor.lastValue.customAction).isEqualTo("$CUSTOM_ACTION_COMMAND 1")
            verify(media3Controller).release()
        }

    private suspend fun getActions(): MediaButton? {
        val result = underTest.createActionsFromSession(PACKAGE_NAME, legacyToken)
        testScope.runCurrent()
        verify(media3Controller).release()

        // Clear so tests can verify the correct number of release() calls in later operations
        clearInvocations(media3Controller)
        return result
    }

    private fun createCustomCommandButton(id: Int): CommandButton {
        return CommandButton.Builder()
            .setDisplayName("$CUSTOM_ACTION_NAME $id")
            .setSessionCommand(SessionCommand("$CUSTOM_ACTION_COMMAND $id", Bundle()))
            .build()
    }
}
