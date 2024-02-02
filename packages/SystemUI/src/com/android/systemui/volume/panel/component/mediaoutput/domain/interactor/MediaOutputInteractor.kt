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
import android.util.Log
import com.android.settingslib.media.MediaDevice
import com.android.settingslib.volume.data.repository.LocalMediaRepository
import com.android.settingslib.volume.data.repository.MediaControllerRepository
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.volume.panel.component.mediaoutput.data.repository.LocalMediaRepositoryFactory
import com.android.systemui.volume.panel.component.mediaoutput.domain.model.MediaDeviceSession
import com.android.systemui.volume.panel.dagger.scope.VolumePanelScope
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
@VolumePanelScope
class MediaOutputInteractor
@Inject
constructor(
    private val localMediaRepositoryFactory: LocalMediaRepositoryFactory,
    private val packageManager: PackageManager,
    @VolumePanelScope private val coroutineScope: CoroutineScope,
    @Background private val backgroundCoroutineContext: CoroutineContext,
    mediaControllerRepository: MediaControllerRepository
) {

    val mediaDeviceSession: Flow<MediaDeviceSession> =
        mediaControllerRepository.activeMediaController.mapNotNull { mediaController ->
            if (mediaController == null) {
                MediaDeviceSession.Inactive
            } else {
                MediaDeviceSession.Active(
                    appLabel = getApplicationLabel(mediaController.packageName)
                            ?: return@mapNotNull null,
                    packageName = mediaController.packageName,
                    sessionToken = mediaController.sessionToken,
                )
            }
        }
    private val localMediaRepository: Flow<LocalMediaRepository> =
        mediaDeviceSession
            .map { (it as? MediaDeviceSession.Active)?.packageName }
            .distinctUntilChanged()
            .map { localMediaRepositoryFactory.create(it) }
            .shareIn(coroutineScope, SharingStarted.WhileSubscribed(), replay = 1)

    val currentConnectedDevice: Flow<MediaDevice?> =
        localMediaRepository.flatMapLatest { it.currentConnectedDevice }

    val mediaDevices: Flow<Collection<MediaDevice>> =
        localMediaRepository.flatMapLatest { it.mediaDevices }

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

    private companion object {
        const val TAG = "MediaOutputInteractor"
    }
}
