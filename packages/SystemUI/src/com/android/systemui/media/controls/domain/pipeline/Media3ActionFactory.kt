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

import android.content.Context
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.media.session.MediaController
import android.media.session.MediaSession
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.media.utils.MediaConstants
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaController as Media3Controller
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.graphics.ImageLoader
import com.android.systemui.media.controls.shared.MediaControlDrawables
import com.android.systemui.media.controls.shared.MediaLogger
import com.android.systemui.media.controls.shared.model.MediaAction
import com.android.systemui.media.controls.shared.model.MediaButton
import com.android.systemui.media.controls.util.MediaControllerFactory
import com.android.systemui.media.controls.util.SessionTokenFactory
import com.android.systemui.res.R
import com.android.systemui.util.concurrency.Execution
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine

private const val TAG = "Media3ActionFactory"

@SysUISingleton
class Media3ActionFactory
@Inject
constructor(
    @Application val context: Context,
    private val imageLoader: ImageLoader,
    private val controllerFactory: MediaControllerFactory,
    private val tokenFactory: SessionTokenFactory,
    private val logger: MediaLogger,
    @Background private val looper: Looper,
    @Background private val handler: Handler,
    @Background private val bgScope: CoroutineScope,
    private val execution: Execution,
) {

    /**
     * Generates action button info for this media session based on the Media3 session info
     *
     * @param packageName Package name for the media app
     * @param controller The framework [MediaController] for the session
     * @return The media action buttons, or null if the session token is null
     */
    suspend fun createActionsFromSession(
        packageName: String,
        sessionToken: MediaSession.Token,
    ): MediaButton? {
        // Get the Media3 controller using the legacy token
        val token = tokenFactory.createTokenFromLegacy(sessionToken)
        val m3controller = controllerFactory.create(token, looper)

        // Build button info
        val buttons = suspendCancellableCoroutine { continuation ->
            // Media3Controller methods must always be called from a specific looper
            val runnable = Runnable {
                try {
                    val result = getMedia3Actions(packageName, m3controller, token)
                    continuation.resumeWith(Result.success(result))
                } finally {
                    m3controller.release()
                }
            }
            handler.post(runnable)
            continuation.invokeOnCancellation {
                // Ensure controller is released, even if loading was cancelled partway through
                handler.post(m3controller::release)
                handler.removeCallbacks(runnable)
            }
        }
        return buttons
    }

    /** This method must be called on the Media3 looper! */
    @WorkerThread
    private fun getMedia3Actions(
        packageName: String,
        m3controller: Media3Controller,
        token: SessionToken,
    ): MediaButton? {
        require(!execution.isMainThread())

        // First, get standard actions
        val playOrPause =
            if (m3controller.playbackState == Player.STATE_BUFFERING) {
                // Spinner needs to be animating to render anything. Start it here.
                val drawable =
                    context.getDrawable(com.android.internal.R.drawable.progress_small_material)
                (drawable as Animatable).start()
                MediaAction(
                    drawable,
                    null, // no action to perform when clicked
                    context.getString(R.string.controls_media_button_connecting),
                    context.getDrawable(R.drawable.ic_media_connecting_container),
                    // Specify a rebind id to prevent the spinner from restarting on later binds.
                    com.android.internal.R.drawable.progress_small_material,
                )
            } else {
                getStandardAction(m3controller, token, Player.COMMAND_PLAY_PAUSE)
            }

        val prevButton =
            getStandardAction(
                m3controller,
                token,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            )
        val nextButton =
            getStandardAction(
                m3controller,
                token,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            )

        // Then, get custom actions
        var customActions =
            m3controller.customLayout
                .asSequence()
                .filter {
                    it.isEnabled &&
                        it.sessionCommand?.commandCode == SessionCommand.COMMAND_CODE_CUSTOM &&
                        m3controller.isSessionCommandAvailable(it.sessionCommand!!)
                }
                .map { getCustomAction(packageName, token, it) }
                .iterator()
        fun nextCustomAction() = if (customActions.hasNext()) customActions.next() else null

        // Finally, assign the remaining button slots: play/pause A B C D
        // A = previous, else custom action (if not reserved)
        // B = next, else custom action (if not reserved)
        // C and D are always custom actions
        val reservePrev =
            m3controller.sessionExtras.getBoolean(
                MediaConstants.SESSION_EXTRAS_KEY_SLOT_RESERVATION_SKIP_TO_PREV,
                false,
            )
        val reserveNext =
            m3controller.sessionExtras.getBoolean(
                MediaConstants.SESSION_EXTRAS_KEY_SLOT_RESERVATION_SKIP_TO_NEXT,
                false,
            )

        val prevOrCustom =
            prevButton
                ?: if (reservePrev) {
                    null
                } else {
                    nextCustomAction()
                }

        val nextOrCustom =
            nextButton
                ?: if (reserveNext) {
                    null
                } else {
                    nextCustomAction()
                }

        return MediaButton(
            playOrPause = playOrPause,
            nextOrCustom = nextOrCustom,
            prevOrCustom = prevOrCustom,
            custom0 = nextCustomAction(),
            custom1 = nextCustomAction(),
            reserveNext = reserveNext,
            reservePrev = reservePrev,
        )
    }

    /**
     * Create a [MediaAction] for a given command, if supported
     *
     * @param controller Media3 controller for the session
     * @param commands Commands to check, in priority order
     * @return A [MediaAction] representing the first supported command, or null if not supported
     */
    private fun getStandardAction(
        controller: Media3Controller,
        token: SessionToken,
        vararg commands: @Player.Command Int,
    ): MediaAction? {
        for (command in commands) {
            if (!controller.isCommandAvailable(command)) {
                continue
            }

            return when (command) {
                Player.COMMAND_PLAY_PAUSE -> {
                    if (!controller.isPlaying) {
                        MediaAction(
                            context.getDrawable(R.drawable.ic_media_play),
                            { executeAction(token, Player.COMMAND_PLAY_PAUSE) },
                            context.getString(R.string.controls_media_button_play),
                            context.getDrawable(R.drawable.ic_media_play_container),
                        )
                    } else {
                        MediaAction(
                            context.getDrawable(R.drawable.ic_media_pause),
                            { executeAction(token, Player.COMMAND_PLAY_PAUSE) },
                            context.getString(R.string.controls_media_button_pause),
                            context.getDrawable(R.drawable.ic_media_pause_container),
                        )
                    }
                }
                else -> {
                    MediaAction(
                        icon = getIconForAction(command),
                        action = { executeAction(token, command) },
                        contentDescription = getDescriptionForAction(command),
                        background = null,
                    )
                }
            }
        }
        return null
    }

    /** Get a [MediaAction] representing a [CommandButton] */
    private fun getCustomAction(
        packageName: String,
        token: SessionToken,
        customAction: CommandButton,
    ): MediaAction {
        return MediaAction(
            getIconForAction(customAction, packageName),
            { executeAction(token, Player.COMMAND_INVALID, customAction) },
            customAction.displayName,
            null,
        )
    }

    private fun getIconForAction(command: @Player.Command Int): Drawable? {
        return when (command) {
            Player.COMMAND_SEEK_TO_PREVIOUS -> MediaControlDrawables.getPrevIcon(context)
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> MediaControlDrawables.getPrevIcon(context)
            Player.COMMAND_SEEK_TO_NEXT -> MediaControlDrawables.getNextIcon(context)
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> MediaControlDrawables.getNextIcon(context)
            else -> {
                Log.e(TAG, "Unknown icon for $command")
                null
            }
        }
    }

    private fun getIconForAction(customAction: CommandButton, packageName: String): Drawable? {
        val size = context.resources.getDimensionPixelSize(R.dimen.min_clickable_item_size)
        // TODO(b/360196209): check customAction.icon field to use platform icons
        if (customAction.iconResId != 0) {
            val packageContext = context.createPackageContext(packageName, 0)
            val source = ImageLoader.Res(customAction.iconResId, packageContext)
            return runBlocking { imageLoader.loadDrawable(source, size, size) }
        }

        if (customAction.iconUri != null) {
            val source = ImageLoader.Uri(customAction.iconUri!!)
            return runBlocking { imageLoader.loadDrawable(source, size, size) }
        }
        return null
    }

    private fun getDescriptionForAction(command: @Player.Command Int): String? {
        return when (command) {
            Player.COMMAND_SEEK_TO_PREVIOUS ->
                context.getString(R.string.controls_media_button_prev)
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM ->
                context.getString(R.string.controls_media_button_prev)
            Player.COMMAND_SEEK_TO_NEXT -> context.getString(R.string.controls_media_button_next)
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ->
                context.getString(R.string.controls_media_button_next)
            else -> {
                Log.e(TAG, "Unknown content description for $command")
                null
            }
        }
    }

    private fun executeAction(
        token: SessionToken,
        command: Int,
        customAction: CommandButton? = null,
    ) {
        bgScope.launch {
            val controller = controllerFactory.create(token, looper)
            handler.post {
                try {
                    when (command) {
                        Player.COMMAND_PLAY_PAUSE -> {
                            if (controller.isPlaying) controller.pause() else controller.play()
                        }

                        Player.COMMAND_SEEK_TO_PREVIOUS -> controller.seekToPrevious()
                        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM ->
                            controller.seekToPreviousMediaItem()

                        Player.COMMAND_SEEK_TO_NEXT -> controller.seekToNext()
                        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> controller.seekToNextMediaItem()
                        Player.COMMAND_INVALID -> {
                            if (customAction?.sessionCommand != null) {
                                val sessionCommand = customAction.sessionCommand!!
                                if (controller.isSessionCommandAvailable(sessionCommand)) {
                                    controller.sendCustomCommand(
                                        sessionCommand,
                                        customAction.extras,
                                    )
                                } else {
                                    logger.logMedia3UnsupportedCommand(
                                        "$sessionCommand, action $customAction"
                                    )
                                }
                            } else {
                                logger.logMedia3UnsupportedCommand("$command, action $customAction")
                            }
                        }
                        else -> logger.logMedia3UnsupportedCommand(command.toString())
                    }
                } finally {
                    controller.release()
                }
            }
        }
    }
}
