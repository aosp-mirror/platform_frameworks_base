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

package com.android.systemui.volume.panel.component.mediaoutput.domain.interactor

import android.content.pm.PackageManager
import android.media.VolumeProvider
import android.media.session.MediaController
import android.os.Handler
import android.util.Log
import com.android.settingslib.media.MediaDevice
import com.android.settingslib.volume.data.repository.LocalMediaRepository
import com.android.settingslib.volume.data.repository.MediaControllerRepository
import com.android.settingslib.volume.data.repository.stateChanges
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.volume.panel.component.mediaoutput.data.repository.LocalMediaRepositoryFactory
import com.android.systemui.volume.panel.component.mediaoutput.domain.model.MediaDeviceSessions
import com.android.systemui.volume.panel.component.mediaoutput.shared.model.MediaDeviceSession
import com.android.systemui.volume.panel.dagger.scope.VolumePanelScope
import com.android.systemui.volume.panel.shared.model.Result
import com.android.systemui.volume.panel.shared.model.filterData
import com.android.systemui.volume.panel.shared.model.wrapInResult
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/** Provides observable models about the current media session state. */
@OptIn(ExperimentalCoroutinesApi::class)
@VolumePanelScope
class MediaOutputInteractor
@Inject
constructor(
    private val localMediaRepositoryFactory: LocalMediaRepositoryFactory,
    private val packageManager: PackageManager,
    @VolumePanelScope private val coroutineScope: CoroutineScope,
    @Background private val backgroundCoroutineContext: CoroutineContext,
    mediaControllerRepository: MediaControllerRepository,
    @Background private val backgroundHandler: Handler,
) {

    private val activeMediaControllers: Flow<MediaControllers> =
        mediaControllerRepository.activeSessions
            .flatMapLatest { activeSessions ->
                activeSessions
                    .map { activeSession -> activeSession.stateChanges() }
                    .merge()
                    .map { activeSessions }
                    .onStart { emit(activeSessions) }
            }
            .map { getMediaControllers(it) }
            .stateIn(coroutineScope, SharingStarted.Eagerly, MediaControllers(null, null))

    /** [MediaDeviceSessions] that contains currently active sessions. */
    val activeMediaDeviceSessions: Flow<MediaDeviceSessions> =
        activeMediaControllers
            .map {
                MediaDeviceSessions(
                    local = it.local?.mediaDeviceSession(),
                    remote = it.remote?.mediaDeviceSession()
                )
            }
            .stateIn(coroutineScope, SharingStarted.Eagerly, MediaDeviceSessions(null, null))

    /** Returns the default [MediaDeviceSession] from [activeMediaDeviceSessions] */
    val defaultActiveMediaSession: StateFlow<Result<MediaDeviceSession?>> =
        activeMediaControllers
            .map {
                when {
                    it.local?.playbackState?.isActive == true -> it.local.mediaDeviceSession()
                    it.remote?.playbackState?.isActive == true -> it.remote.mediaDeviceSession()
                    it.local != null -> it.local.mediaDeviceSession()
                    else -> null
                }
            }
            .wrapInResult()
            .flowOn(backgroundCoroutineContext)
            .stateIn(coroutineScope, SharingStarted.Eagerly, Result.Loading())

    private val localMediaRepository: Flow<LocalMediaRepository> =
        defaultActiveMediaSession
            .filterData()
            .map { it?.packageName }
            .distinctUntilChanged()
            .map { localMediaRepositoryFactory.create(it) }
            .stateIn(
                coroutineScope,
                SharingStarted.Eagerly,
                localMediaRepositoryFactory.create(null)
            )

    /** Currently connected [MediaDevice]. */
    val currentConnectedDevice: Flow<MediaDevice?> =
        localMediaRepository.flatMapLatest { it.currentConnectedDevice }.distinctUntilChanged()

    private suspend fun getApplicationLabel(packageName: String): CharSequence? {
        return try {
            withContext(backgroundCoroutineContext) {
                val appInfo =
                    packageManager.getApplicationInfo(
                        packageName,
                        PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_ANY_USER
                    )
                appInfo.loadLabel(packageManager)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "Unable to find info for package: $packageName")
            null
        }
    }

    /** Finds local and remote media controllers. */
    private fun getMediaControllers(
        controllers: Collection<MediaController>,
    ): MediaControllers {
        var localController: MediaController? = null
        var remoteController: MediaController? = null
        val remoteMediaSessions: MutableSet<String> = mutableSetOf()
        for (controller in controllers) {
            val playbackInfo: MediaController.PlaybackInfo = controller.playbackInfo ?: continue
            when (playbackInfo.playbackType) {
                MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE -> {
                    // MediaController can't be local if there is a remote one for the same package
                    if (localController?.packageName.equals(controller.packageName)) {
                        localController = null
                    }
                    if (!remoteMediaSessions.contains(controller.packageName)) {
                        remoteMediaSessions.add(controller.packageName)
                        remoteController = chooseController(remoteController, controller)
                    }
                }
                MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL -> {
                    if (controller.packageName in remoteMediaSessions) continue
                    localController = chooseController(localController, controller)
                }
            }
        }
        return MediaControllers(local = localController, remote = remoteController)
    }

    private fun chooseController(
        currentController: MediaController?,
        newController: MediaController,
    ): MediaController {
        if (currentController == null) {
            return newController
        }
        val isNewControllerActive = newController.playbackState?.isActive == true
        val isCurrentControllerActive = currentController.playbackState?.isActive == true
        if (isNewControllerActive && !isCurrentControllerActive) {
            return newController
        }
        return currentController
    }

    private suspend fun MediaController.mediaDeviceSession(): MediaDeviceSession? {
        return MediaDeviceSession(
            packageName = packageName,
            sessionToken = sessionToken,
            canAdjustVolume =
                playbackInfo != null &&
                    playbackInfo?.volumeControl != VolumeProvider.VOLUME_CONTROL_FIXED,
            appLabel = getApplicationLabel(packageName) ?: return null
        )
    }

    private fun MediaController?.stateChanges(): Flow<MediaController?> {
        if (this == null) {
            return flowOf(null)
        }

        return stateChanges(backgroundHandler).map { this }.onStart { emit(this@stateChanges) }
    }

    private data class MediaControllers(
        val local: MediaController?,
        val remote: MediaController?,
    )

    private companion object {
        const val TAG = "MediaOutputInteractor"
    }
}
