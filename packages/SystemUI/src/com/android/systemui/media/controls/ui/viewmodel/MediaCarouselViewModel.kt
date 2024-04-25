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
import com.android.systemui.statusbar.notification.collection.provider.OnReorderingAllowedListener
import com.android.systemui.statusbar.notification.collection.provider.VisualStabilityProvider
import com.android.systemui.util.Utils
import com.android.systemui.util.kotlin.pairwiseBy
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

/** Models UI state and handles user inputs for media carousel */
@SysUISingleton
class MediaCarouselViewModel
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    @Application private val applicationContext: Context,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val visualStabilityProvider: VisualStabilityProvider,
    private val interactor: MediaCarouselInteractor,
    private val controlInteractorFactory: MediaControlInteractorFactory,
    private val recommendationsViewModel: MediaRecommendationsViewModel,
    private val logger: MediaUiEventLogger,
    private val mediaFlags: MediaFlags,
) {

    @OptIn(ExperimentalCoroutinesApi::class)
    val mediaItems: StateFlow<List<MediaCommonViewModel>> =
        conflatedCallbackFlow {
                val listener = OnReorderingAllowedListener { trySend(Unit) }
                visualStabilityProvider.addPersistentReorderingAllowedListener(listener)
                trySend(Unit)
                awaitClose { visualStabilityProvider.removeReorderingAllowedListener(listener) }
            }
            .flatMapLatest {
                combine(interactor.isMediaFromRec, interactor.sortedMedia) {
                    isRecsToMedia,
                    sortedItems ->
                    buildList {
                        shouldReorder = isRecsToMedia
                        val reorderAllowed = isReorderingAllowed()
                        sortedItems.forEach { commonModel ->
                            if (!reorderAllowed || !modelsPendingRemoval.contains(commonModel)) {
                                when (commonModel) {
                                    is MediaCommonModel.MediaControl ->
                                        add(toViewModel(commonModel))
                                    is MediaCommonModel.MediaRecommendations ->
                                        add(toViewModel(commonModel))
                                }
                            }
                        }
                        if (reorderAllowed) {
                            modelsPendingRemoval.clear()
                        }
                    }
                }
            }
            .pairwiseBy { old, new ->
                // This condition can only happen when view is attached. So the old emit is of the
                // most recent list updated.
                // If the old list is empty, it is okay to emit the new ordered list.
                if (isReorderingAllowed() || shouldReorder || old.isEmpty()) {
                    new
                } else {
                    old
                }
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = emptyList(),
            )

    private val mediaControlByInstanceId =
        mutableMapOf<InstanceId, MediaCommonViewModel.MediaControl>()

    private var mediaRecs: MediaCommonViewModel.MediaRecommendations? = null

    private var modelsPendingRemoval: MutableSet<MediaCommonModel> = mutableSetOf()

    private var shouldReorder = true

    fun onSwipeToDismiss() {
        logger.logSwipeDismiss()
        interactor.onSwipeToDismiss()
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
                )
                .also { mediaControlByInstanceId[instanceId] = it }
    }

    private fun createMediaControlViewModel(instanceId: InstanceId): MediaControlViewModel {
        return MediaControlViewModel(
            applicationScope = applicationScope,
            applicationContext = applicationContext,
            backgroundDispatcher = backgroundDispatcher,
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
                        onMediaRecommendationAddedOrUpdated(commonViewModel)
                    },
                    onRemoved = { immediatelyRemove ->
                        onMediaRecommendationRemoved(commonModel, immediatelyRemove)
                    },
                    onUpdated = { commonViewModel ->
                        onMediaRecommendationAddedOrUpdated(commonViewModel)
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

    private fun onMediaRecommendationAddedOrUpdated(commonViewModel: MediaCommonViewModel) {
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
            // TODO if not immediate remove update host visibility
        } else {
            modelsPendingRemoval.add(commonModel)
        }
    }

    private fun isReorderingAllowed(): Boolean {
        return visualStabilityProvider.isReorderingAllowed
    }
}
