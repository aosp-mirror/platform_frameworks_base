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

package com.android.systemui.media.controls.ui.viewmodel

import android.content.Context
import android.content.pm.PackageManager
import android.media.session.MediaController
import android.media.session.MediaSession.Token
import android.text.TextUtils
import android.util.Log
import androidx.constraintlayout.widget.ConstraintSet
import com.android.internal.logging.InstanceId
import com.android.settingslib.flags.Flags.legacyLeAudioSharing
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.media.controls.domain.pipeline.interactor.MediaControlInteractor
import com.android.systemui.media.controls.shared.model.MediaAction
import com.android.systemui.media.controls.shared.model.MediaButton
import com.android.systemui.media.controls.shared.model.MediaControlModel
import com.android.systemui.media.controls.ui.animation.accentPrimaryFromScheme
import com.android.systemui.media.controls.ui.animation.surfaceFromScheme
import com.android.systemui.media.controls.ui.animation.textPrimaryFromScheme
import com.android.systemui.media.controls.ui.util.MediaArtworkHelper
import com.android.systemui.media.controls.util.MediaUiEventLogger
import com.android.systemui.monet.ColorScheme
import com.android.systemui.monet.Style
import com.android.systemui.res.R
import com.android.systemui.util.kotlin.sample
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Models UI state and handles user input for a media control. */
class MediaControlViewModel(
    @Application private val applicationContext: Context,
    @Application private val applicationScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val interactor: MediaControlInteractor,
    private val logger: MediaUiEventLogger,
) {

    private val isAnyButtonClicked: MutableStateFlow<Boolean> = MutableStateFlow(false)

    private val playTurbulenceNoise: Flow<Boolean> =
        interactor.mediaControl.sample(
            combine(isAnyButtonClicked, interactor.isStartedPlaying) {
                    isButtonClicked,
                    isStartedPlaying ->
                    isButtonClicked && isStartedPlaying
                }
                .distinctUntilChanged()
        )

    val player: Flow<MediaPlayerViewModel?> =
        combine(playTurbulenceNoise, interactor.mediaControl) { playTurbulenceNoise, mediaControl ->
                mediaControl?.let { toViewModel(it, playTurbulenceNoise) }
            }
            .distinctUntilChanged()
            .flowOn(backgroundDispatcher)

    private fun onDismissMediaData(
        token: Token?,
        uid: Int,
        packageName: String,
        instanceId: InstanceId
    ) {
        logger.logLongPressDismiss(uid, packageName, instanceId)
        interactor.removeMediaControl(token, instanceId, MEDIA_PLAYER_ANIMATION_DELAY)
    }

    private suspend fun toViewModel(
        model: MediaControlModel,
        playTurbulenceNoise: Boolean
    ): MediaPlayerViewModel? {
        val wallpaperColors =
            MediaArtworkHelper.getWallpaperColor(
                applicationContext,
                backgroundDispatcher,
                model.artwork,
                TAG
            )
        val scheme =
            wallpaperColors?.let { ColorScheme(it, true, Style.CONTENT) }
                ?: MediaArtworkHelper.getColorScheme(
                    applicationContext,
                    model.packageName,
                    TAG,
                    Style.CONTENT
                )
                    ?: return null

        val gutsViewModel = toGutsViewModel(model, scheme)

        // Resetting button clicks state.
        isAnyButtonClicked.value = false

        return MediaPlayerViewModel(
            contentDescription = { gutsVisible ->
                if (gutsVisible) {
                    gutsViewModel.gutsText
                } else {
                    applicationContext.getString(
                        R.string.controls_media_playing_item_description,
                        model.songName,
                        model.artistName,
                        model.appName
                    )
                }
            },
            backgroundCover = model.artwork,
            appIcon = model.appIcon,
            launcherIcon = getIconFromApp(model.packageName),
            useGrayColorFilter = model.appIcon == null || model.isResume,
            artistName = model.artistName ?: "",
            titleName = model.songName ?: "",
            isExplicitVisible = model.showExplicit,
            shouldAddGradient = wallpaperColors != null,
            colorScheme = scheme,
            canShowTime = canShowScrubbingTimeViews(model.semanticActionButtons),
            playTurbulenceNoise = playTurbulenceNoise,
            useSemanticActions = model.semanticActionButtons != null,
            actionButtons = toActionViewModels(model),
            outputSwitcher = toOutputSwitcherViewModel(model),
            gutsMenu = gutsViewModel,
            onClicked = { expandable ->
                model.clickIntent?.let { clickIntent ->
                    logger.logTapContentView(model.uid, model.packageName, model.instanceId)
                    // TODO (b/330897926) log smartspace card reported (SMARTSPACE_CARD_CLICK_EVENT)
                    interactor.startClickIntent(expandable, clickIntent)
                }
            },
            onLongClicked = {
                logger.logLongPressOpen(model.uid, model.packageName, model.instanceId)
            },
            onSeek = {
                logger.logSeek(model.uid, model.packageName, model.instanceId)
                // TODO (b/330897926) log smartspace card reported (SMARTSPACE_CARD_CLICK_EVENT)
            },
            onBindSeekbar = { seekBarViewModel ->
                if (model.isResume && model.resumeProgress != null) {
                    seekBarViewModel.updateStaticProgress(model.resumeProgress)
                } else {
                    applicationScope.launch {
                        withContext(backgroundDispatcher) {
                            seekBarViewModel.updateController(
                                model.token?.let { MediaController(applicationContext, it) }
                            )
                        }
                    }
                }
            }
        )
    }

    private fun toOutputSwitcherViewModel(model: MediaControlModel): MediaOutputSwitcherViewModel {
        val device = model.deviceData
        val showBroadcastButton = legacyLeAudioSharing() && device?.showBroadcastButton == true

        // TODO(b/233698402): Use the package name instead of app label to avoid the unexpected
        //  result.
        val isCurrentBroadcastApp =
            device?.name?.let {
                TextUtils.equals(
                    it,
                    applicationContext.getString(R.string.broadcasting_description_is_broadcasting)
                )
            }
                ?: false
        val useDisabledAlpha =
            if (showBroadcastButton) {
                !isCurrentBroadcastApp
            } else {
                device?.enabled == false || model.isResume
            }
        val deviceString =
            device?.name
                ?: if (showBroadcastButton) {
                    applicationContext.getString(R.string.bt_le_audio_broadcast_dialog_unknown_name)
                } else {
                    applicationContext.getString(R.string.media_seamless_other_device)
                }
        return MediaOutputSwitcherViewModel(
            isTapEnabled = showBroadcastButton || !useDisabledAlpha,
            deviceString = deviceString,
            deviceIcon = device?.icon?.let { Icon.Loaded(it, null) }
                    ?: if (showBroadcastButton) {
                        Icon.Resource(R.drawable.settings_input_antenna, null)
                    } else {
                        Icon.Resource(R.drawable.ic_media_home_devices, null)
                    },
            isCurrentBroadcastApp = isCurrentBroadcastApp,
            isIntentValid = device?.intent != null,
            alpha =
                if (useDisabledAlpha) {
                    DISABLED_ALPHA
                } else {
                    1.0f
                },
            isVisible = showBroadcastButton,
            onClicked = { expandable ->
                if (showBroadcastButton) {
                    // If the current media app is not broadcasted and users press the outputer
                    // button, we should pop up the broadcast dialog to check do they want to
                    // switch broadcast to the other media app, otherwise we still pop up the
                    // media output dialog.
                    if (!isCurrentBroadcastApp) {
                        logger.logOpenBroadcastDialog(
                            model.uid,
                            model.packageName,
                            model.instanceId
                        )
                        interactor.startBroadcastDialog(
                            expandable,
                            device?.name.toString(),
                            model.packageName
                        )
                    } else {
                        logger.logOpenOutputSwitcher(model.uid, model.packageName, model.instanceId)
                        interactor.startMediaOutputDialog(expandable, model.packageName)
                    }
                } else {
                    logger.logOpenOutputSwitcher(model.uid, model.packageName, model.instanceId)
                    device?.intent?.let { interactor.startDeviceIntent(it) }
                        ?: interactor.startMediaOutputDialog(expandable, model.packageName)
                }
            }
        )
    }

    private fun toGutsViewModel(model: MediaControlModel, scheme: ColorScheme): GutsViewModel {
        return GutsViewModel(
            gutsText =
                if (model.isDismissible) {
                    applicationContext.getString(
                        R.string.controls_media_close_session,
                        model.appName
                    )
                } else {
                    applicationContext.getString(R.string.controls_media_active_session)
                },
            textPrimaryColor = textPrimaryFromScheme(scheme),
            accentPrimaryColor = accentPrimaryFromScheme(scheme),
            surfaceColor = surfaceFromScheme(scheme),
            isDismissEnabled = model.isDismissible,
            onDismissClicked = {
                onDismissMediaData(model.token, model.uid, model.packageName, model.instanceId)
            },
            cancelTextBackground =
                if (model.isDismissible) {
                    applicationContext.getDrawable(R.drawable.qs_media_outline_button)
                } else {
                    applicationContext.getDrawable(R.drawable.qs_media_solid_button)
                },
            onSettingsClicked = {
                logger.logLongPressSettings(model.uid, model.packageName, model.instanceId)
                interactor.startSettings()
            },
        )
    }

    private fun toActionViewModels(model: MediaControlModel): List<MediaActionViewModel?> {
        val semanticActionButtons =
            model.semanticActionButtons?.let { mediaButton ->
                with(mediaButton) {
                    val isScrubbingTimeEnabled = canShowScrubbingTimeViews(mediaButton)
                    SEMANTIC_ACTIONS_ALL.map { buttonId ->
                        getActionById(buttonId)?.let {
                            toSemanticActionViewModel(model, it, buttonId, isScrubbingTimeEnabled)
                        }
                    }
                }
            }
        val notifActionButtons =
            model.notificationActionButtons.mapIndexed { index, mediaAction ->
                toNotifActionViewModel(model, mediaAction, index)
            }
        return semanticActionButtons ?: notifActionButtons
    }

    private fun toSemanticActionViewModel(
        model: MediaControlModel,
        mediaAction: MediaAction,
        buttonId: Int,
        canShowScrubbingTimeViews: Boolean
    ): MediaActionViewModel {
        val showInCollapsed = SEMANTIC_ACTIONS_COMPACT.contains(buttonId)
        val hideWhenScrubbing = SEMANTIC_ACTIONS_HIDE_WHEN_SCRUBBING.contains(buttonId)
        val shouldHideWhenScrubbing = canShowScrubbingTimeViews && hideWhenScrubbing
        return MediaActionViewModel(
            icon = mediaAction.icon,
            contentDescription = mediaAction.contentDescription,
            background = mediaAction.background,
            isVisibleWhenScrubbing = !shouldHideWhenScrubbing,
            notVisibleValue =
                if (
                    (buttonId == R.id.actionPrev && model.semanticActionButtons!!.reservePrev) ||
                        (buttonId == R.id.actionNext && model.semanticActionButtons!!.reserveNext)
                ) {
                    ConstraintSet.INVISIBLE
                } else {
                    ConstraintSet.GONE
                },
            showInCollapsed = showInCollapsed,
            rebindId = mediaAction.rebindId,
            buttonId = buttonId,
            isEnabled = mediaAction.action != null,
            onClicked = { id ->
                mediaAction.action?.let {
                    onButtonClicked(id, model.uid, model.packageName, model.instanceId, it)
                }
            },
        )
    }

    private fun toNotifActionViewModel(
        model: MediaControlModel,
        mediaAction: MediaAction,
        index: Int
    ): MediaActionViewModel {
        return MediaActionViewModel(
            icon = mediaAction.icon,
            contentDescription = mediaAction.contentDescription,
            background = mediaAction.background,
            showInCollapsed = model.actionsToShowInCollapsed.contains(index),
            rebindId = mediaAction.rebindId,
            isEnabled = mediaAction.action != null,
            onClicked = { id ->
                mediaAction.action?.let {
                    onButtonClicked(id, model.uid, model.packageName, model.instanceId, it)
                }
            },
        )
    }

    private fun onButtonClicked(
        id: Int,
        uid: Int,
        packageName: String,
        instanceId: InstanceId,
        action: Runnable
    ) {
        logger.logTapAction(id, uid, packageName, instanceId)
        // TODO (b/330897926) log smartspace card reported (SMARTSPACE_CARD_CLICK_EVENT)
        isAnyButtonClicked.value = true
        action.run()
    }

    private fun getIconFromApp(packageName: String): Icon {
        return try {
            Icon.Loaded(applicationContext.packageManager.getApplicationIcon(packageName), null)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Cannot find icon for package $packageName", e)
            Icon.Resource(R.drawable.ic_music_note, null)
        }
    }

    private fun canShowScrubbingTimeViews(semanticActions: MediaButton?): Boolean {
        // The scrubbing time views replace the SEMANTIC_ACTIONS_HIDE_WHEN_SCRUBBING action views,
        // so we should only allow scrubbing times to be shown if those action views are present.
        return semanticActions?.let {
            SEMANTIC_ACTIONS_HIDE_WHEN_SCRUBBING.stream().allMatch { id: Int ->
                semanticActions.getActionById(id) != null
            }
        }
            ?: false
    }

    companion object {
        private const val TAG = "MediaControlViewModel"
        private const val MEDIA_PLAYER_ANIMATION_DELAY = 334L
        private const val DISABLED_ALPHA = 0.38f

        /** Buttons to show in small player when using semantic actions */
        val SEMANTIC_ACTIONS_COMPACT =
            listOf(R.id.actionPlayPause, R.id.actionPrev, R.id.actionNext)

        /**
         * Buttons that should get hidden when we are scrubbing (they will be replaced with the
         * views showing scrubbing time)
         */
        val SEMANTIC_ACTIONS_HIDE_WHEN_SCRUBBING = listOf(R.id.actionPrev, R.id.actionNext)

        /** Buttons to show in player when using semantic actions. */
        val SEMANTIC_ACTIONS_ALL =
            listOf(
                R.id.actionPlayPause,
                R.id.actionPrev,
                R.id.actionNext,
                R.id.action0,
                R.id.action1
            )

        const val TURBULENCE_NOISE_PLAY_MS_DURATION = 7500L
        const val MEDIA_PLAYER_SCRIM_START_ALPHA = 0.25f
        const val MEDIA_PLAYER_SCRIM_END_ALPHA = 1.0f
    }
}
