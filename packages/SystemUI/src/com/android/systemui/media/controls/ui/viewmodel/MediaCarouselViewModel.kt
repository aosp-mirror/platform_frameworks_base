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
import com.android.internal.logging.InstanceId
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.media.controls.domain.pipeline.interactor.MediaCarouselInteractor
import com.android.systemui.media.controls.domain.pipeline.interactor.factory.MediaControlInteractorFactory
import com.android.systemui.media.controls.shared.model.MediaCommonModel
import com.android.systemui.media.controls.util.MediaFlags
import com.android.systemui.media.controls.util.MediaUiEventLogger
import com.android.systemui.statusbar.notification.collection.provider.VisualStabilityProvider
import com.android.systemui.util.Utils
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Models UI state and handles user inputs for media carousel */
@SysUISingleton
class MediaCarouselViewModel
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    @Application private val applicationContext: Context,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    @Background private val backgroundExecutor: Executor,
    private val visualStabilityProvider: VisualStabilityProvider,
    private val interactor: MediaCarouselInteractor,
    private val controlInteractorFactory: MediaControlInteractorFactory,
    private val recommendationsViewModel: MediaRecommendationsViewModel,
    private val logger: MediaUiEventLogger,
    private val mediaFlags: MediaFlags,
) {

    val mediaItems: StateFlow<List<MediaCommonViewModel>> =
        interactor.currentMedia
            .map { sortedItems ->
                val mediaList = buildList {
                    sortedItems.forEach { commonModel ->
                        // When view is started we should make sure to clean models that are pending
                        // removal.
                        // This action should only be triggered once.
                        if (!allowReorder || !modelsPendingRemoval.contains(commonModel)) {
                            when (commonModel) {
                                is MediaCommonModel.MediaControl -> add(toViewModel(commonModel))
                                is MediaCommonModel.MediaRecommendations ->
                                    add(toViewModel(commonModel))
                            }
                        }
                    }
                }
                if (allowReorder) {
                    if (modelsPendingRemoval.size > 0) {
                        updateHostVisibility()
                    }
                    modelsPendingRemoval.clear()
                }
                allowReorder = false

                mediaList
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = emptyList(),
            )

    var updateHostVisibility: () -> Unit = {}

    private val mediaControlByInstanceId =
        mutableMapOf<InstanceId, MediaCommonViewModel.MediaControl>()

    private var mediaRecs: MediaCommonViewModel.MediaRecommendations? = null

    private var modelsPendingRemoval: MutableSet<MediaCommonModel> = mutableSetOf()

    private var allowReorder = false

    fun onSwipeToDismiss() {
        logger.logSwipeDismiss()
        interactor.onSwipeToDismiss()
    }

    fun onReorderingAllowed() {
        allowReorder = true
        interactor.reorderMedia()
    }

    private fun toViewModel(
        commonModel: MediaCommonModel.MediaControl
    ): MediaCommonViewModel.MediaControl {
        val instanceId = commonModel.mediaLoadedModel.instanceId
        return mediaControlByInstanceId[instanceId]?.copy(
            immediatelyUpdateUi = commonModel.mediaLoadedModel.immediatelyUpdateUi
        )
            ?: MediaCommonViewModel.MediaControl(
                    instanceId = instanceId,
                    immediatelyUpdateUi = commonModel.mediaLoadedModel.immediatelyUpdateUi,
                    controlViewModel = createMediaControlViewModel(instanceId),
                    onAdded = { onMediaControlAddedOrUpdated(it, commonModel) },
                    onRemoved = {
                        interactor.removeMediaControl(instanceId, delay = 0L)
                        mediaControlByInstanceId.remove(instanceId)
                    },
                    onUpdated = { onMediaControlAddedOrUpdated(it, commonModel) },
                    isMediaFromRec = commonModel.isMediaFromRec
                )
                .also { mediaControlByInstanceId[instanceId] = it }
    }

    private fun createMediaControlViewModel(instanceId: InstanceId): MediaControlViewModel {
        return MediaControlViewModel(
            applicationContext = applicationContext,
            backgroundDispatcher = backgroundDispatcher,
            backgroundExecutor = backgroundExecutor,
            interactor = controlInteractorFactory.create(instanceId),
            logger = logger,
        )
    }

    private fun toViewModel(
        commonModel: MediaCommonModel.MediaRecommendations
    ): MediaCommonViewModel.MediaRecommendations {
        return mediaRecs?.copy(
            key = commonModel.recsLoadingModel.key,
            loadingEnabled =
                interactor.isRecommendationActive() || mediaFlags.isPersistentSsCardEnabled()
        )
            ?: MediaCommonViewModel.MediaRecommendations(
                    key = commonModel.recsLoadingModel.key,
                    loadingEnabled =
                        interactor.isRecommendationActive() ||
                            mediaFlags.isPersistentSsCardEnabled(),
                    recsViewModel = recommendationsViewModel,
                    onAdded = { commonViewModel ->
                        onMediaRecommendationAddedOrUpdated(
                            commonViewModel as MediaCommonViewModel.MediaRecommendations
                        )
                    },
                    onRemoved = { immediatelyRemove ->
                        onMediaRecommendationRemoved(commonModel, immediatelyRemove)
                    },
                    onUpdated = { commonViewModel ->
                        onMediaRecommendationAddedOrUpdated(
                            commonViewModel as MediaCommonViewModel.MediaRecommendations
                        )
                    },
                )
                .also { mediaRecs = it }
    }

    private fun onMediaControlAddedOrUpdated(
        commonViewModel: MediaCommonViewModel,
        commonModel: MediaCommonModel.MediaControl
    ) {
        // TODO (b/330897926) log smartspace card reported (SMARTSPACE_CARD_RECEIVED)
        if (commonModel.canBeRemoved && !Utils.useMediaResumption(applicationContext)) {
            // This media control is due for removal as it is now paused + timed out, and resumption
            // setting is off.
            if (isReorderingAllowed()) {
                commonViewModel.onRemoved(true)
            } else {
                modelsPendingRemoval.add(commonModel)
            }
        } else {
            modelsPendingRemoval.remove(commonModel)
        }
    }

    private fun onMediaRecommendationAddedOrUpdated(
        commonViewModel: MediaCommonViewModel.MediaRecommendations
    ) {
        if (!interactor.isRecommendationActive()) {
            if (!mediaFlags.isPersistentSsCardEnabled()) {
                commonViewModel.onRemoved(true)
            }
        } else {
            // TODO (b/330897926) log smartspace card reported (SMARTSPACE_CARD_RECEIVED)
        }
    }

    private fun onMediaRecommendationRemoved(
        commonModel: MediaCommonModel.MediaRecommendations,
        immediatelyRemove: Boolean
    ) {
        if (immediatelyRemove || isReorderingAllowed()) {
            interactor.dismissSmartspaceRecommendation(commonModel.recsLoadingModel.key, 0L)
            mediaRecs = null
            if (!immediatelyRemove) {
                // Although it wasn't requested, we were able to process the removal
                // immediately since reordering is allowed. So, notify hosts to update
                updateHostVisibility()
            }
        } else {
            modelsPendingRemoval.add(commonModel)
        }
    }

    private fun isReorderingAllowed(): Boolean {
        return visualStabilityProvider.isReorderingAllowed
    }
}
