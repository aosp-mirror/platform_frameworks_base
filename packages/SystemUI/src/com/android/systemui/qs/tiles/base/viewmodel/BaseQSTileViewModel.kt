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

package com.android.systemui.qs.tiles.base.viewmodel

import androidx.annotation.CallSuper
import androidx.annotation.VisibleForTesting
import com.android.internal.util.Preconditions
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.qs.tiles.base.analytics.QSTileAnalytics
import com.android.systemui.qs.tiles.base.interactor.DataUpdateTrigger
import com.android.systemui.qs.tiles.base.interactor.DisabledByPolicyInteractor
import com.android.systemui.qs.tiles.base.interactor.QSTileDataInteractor
import com.android.systemui.qs.tiles.base.interactor.QSTileDataToStateMapper
import com.android.systemui.qs.tiles.base.interactor.QSTileInput
import com.android.systemui.qs.tiles.base.interactor.QSTileUserActionInteractor
import com.android.systemui.qs.tiles.base.logging.QSTileLogger
import com.android.systemui.qs.tiles.viewmodel.QSTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileLifecycle
import com.android.systemui.qs.tiles.viewmodel.QSTilePolicy
import com.android.systemui.qs.tiles.viewmodel.QSTileState
import com.android.systemui.qs.tiles.viewmodel.QSTileUserAction
import com.android.systemui.qs.tiles.viewmodel.QSTileViewModel
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.util.kotlin.throttle
import com.android.systemui.util.time.SystemClock
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn

/**
 * Provides a hassle-free way to implement new tiles according to current System UI architecture
 * standards. THis ViewModel is cheap to instantiate and does nothing until it's moved to
 * [QSTileLifecycle.ALIVE] state.
 *
 * Inject [BaseQSTileViewModel.Factory] to create a new instance of this class.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BaseQSTileViewModel<DATA_TYPE>
@VisibleForTesting
constructor(
    override val config: QSTileConfig,
    private val userActionInteractor: QSTileUserActionInteractor<DATA_TYPE>,
    private val tileDataInteractor: QSTileDataInteractor<DATA_TYPE>,
    private val mapper: QSTileDataToStateMapper<DATA_TYPE>,
    private val disabledByPolicyInteractor: DisabledByPolicyInteractor,
    userRepository: UserRepository,
    private val falsingManager: FalsingManager,
    private val qsTileAnalytics: QSTileAnalytics,
    private val qsTileLogger: QSTileLogger,
    private val systemClock: SystemClock,
    private val backgroundDispatcher: CoroutineDispatcher,
    private val tileScope: CoroutineScope,
) : QSTileViewModel {

    @AssistedInject
    constructor(
        @Assisted config: QSTileConfig,
        @Assisted userActionInteractor: QSTileUserActionInteractor<DATA_TYPE>,
        @Assisted tileDataInteractor: QSTileDataInteractor<DATA_TYPE>,
        @Assisted mapper: QSTileDataToStateMapper<DATA_TYPE>,
        disabledByPolicyInteractor: DisabledByPolicyInteractor,
        userRepository: UserRepository,
        falsingManager: FalsingManager,
        qsTileAnalytics: QSTileAnalytics,
        qsTileLogger: QSTileLogger,
        systemClock: SystemClock,
        @Background backgroundDispatcher: CoroutineDispatcher,
    ) : this(
        config,
        userActionInteractor,
        tileDataInteractor,
        mapper,
        disabledByPolicyInteractor,
        userRepository,
        falsingManager,
        qsTileAnalytics,
        qsTileLogger,
        systemClock,
        backgroundDispatcher,
        CoroutineScope(SupervisorJob())
    )

    private val userIds: MutableStateFlow<Int> =
        MutableStateFlow(userRepository.getSelectedUserInfo().id)
    private val userInputs: MutableSharedFlow<QSTileUserAction> =
        MutableSharedFlow(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val forceUpdates: MutableSharedFlow<Unit> =
        MutableSharedFlow(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val spec
        get() = config.tileSpec

    private lateinit var tileData: SharedFlow<DATA_TYPE>

    override lateinit var state: SharedFlow<QSTileState>
    override val isAvailable: StateFlow<Boolean> =
        userIds
            .flatMapLatest { tileDataInteractor.availability(it) }
            .flowOn(backgroundDispatcher)
            .stateIn(
                tileScope,
                SharingStarted.WhileSubscribed(),
                true,
            )

    private var currentLifeState: QSTileLifecycle = QSTileLifecycle.DEAD

    @CallSuper
    override fun forceUpdate() {
        Preconditions.checkState(currentLifeState == QSTileLifecycle.ALIVE)
        forceUpdates.tryEmit(Unit)
    }

    @CallSuper
    override fun onUserIdChanged(userId: Int) {
        Preconditions.checkState(currentLifeState == QSTileLifecycle.ALIVE)
        userIds.tryEmit(userId)
    }

    @CallSuper
    override fun onActionPerformed(userAction: QSTileUserAction) {
        Preconditions.checkState(currentLifeState == QSTileLifecycle.ALIVE)

        qsTileLogger.logUserAction(
            userAction,
            spec,
            tileData.replayCache.isNotEmpty(),
            state.replayCache.isNotEmpty()
        )
        userInputs.tryEmit(userAction)
    }

    @CallSuper
    override fun onLifecycle(lifecycle: QSTileLifecycle) {
        when (lifecycle) {
            QSTileLifecycle.ALIVE -> {
                Preconditions.checkState(currentLifeState == QSTileLifecycle.DEAD)
                tileData = createTileDataFlow()
                state =
                    tileData
                        .map { data ->
                            mapper.map(config, data).also { state ->
                                qsTileLogger.logStateUpdate(spec, state, data)
                            }
                        }
                        .flowOn(backgroundDispatcher)
                        .shareIn(
                            tileScope,
                            SharingStarted.WhileSubscribed(),
                            replay = 1,
                        )
            }
            QSTileLifecycle.DEAD -> {
                Preconditions.checkState(currentLifeState == QSTileLifecycle.ALIVE)
                tileScope.coroutineContext.cancelChildren()
            }
        }
        currentLifeState = lifecycle
    }

    private fun createTileDataFlow(): SharedFlow<DATA_TYPE> =
        userIds
            .flatMapLatest { userId ->
                val updateTriggers =
                    merge(
                            userInputFlow(userId),
                            forceUpdates
                                .map { DataUpdateTrigger.ForceUpdate }
                                .onEach { qsTileLogger.logForceUpdate(spec) },
                        )
                        .onStart {
                            emit(DataUpdateTrigger.InitialRequest)
                            qsTileLogger.logInitialRequest(spec)
                        }
                tileDataInteractor
                    .tileData(userId, updateTriggers)
                    .cancellable()
                    .flowOn(backgroundDispatcher)
            }
            .shareIn(
                tileScope,
                SharingStarted.WhileSubscribed(),
                replay = 1, // we only care about the most recent value
            )

    /**
     * Creates a user input flow which:
     * - filters false inputs with [falsingManager]
     * - takes care of a tile being disable by policy using [disabledByPolicyInteractor]
     * - notifies [userActionInteractor] about the action
     * - logs it accordingly using [qsTileLogger] and [qsTileAnalytics]
     *
     * Subscribing to the result flow twice will result in doubling all actions, logs and analytics.
     */
    private fun userInputFlow(userId: Int): Flow<DataUpdateTrigger> {
        return userInputs
            .filterFalseActions()
            .filterByPolicy(userId)
            .throttle(CLICK_THROTTLE_DURATION, systemClock)
            // Skip the input until there is some data
            .mapNotNull { action ->
                val state: QSTileState = state.replayCache.lastOrNull() ?: return@mapNotNull null
                val data: DATA_TYPE = tileData.replayCache.lastOrNull() ?: return@mapNotNull null
                qsTileLogger.logUserActionPipeline(spec, action, state, data)
                qsTileAnalytics.trackUserAction(config, action)

                DataUpdateTrigger.UserInput(QSTileInput(userId, action, data))
            }
            .onEach { userActionInteractor.handleInput(it.input) }
            .flowOn(backgroundDispatcher)
    }

    private fun Flow<QSTileUserAction>.filterByPolicy(userId: Int): Flow<QSTileUserAction> =
        when (config.policy) {
            is QSTilePolicy.NoRestrictions -> this
            is QSTilePolicy.Restricted ->
                filter { action ->
                    val result =
                        disabledByPolicyInteractor.isDisabled(userId, config.policy.userRestriction)
                    !disabledByPolicyInteractor.handlePolicyResult(result).also { isDisabled ->
                        if (isDisabled) {
                            qsTileLogger.logUserActionRejectedByPolicy(action, spec)
                        }
                    }
                }
        }

    private fun Flow<QSTileUserAction>.filterFalseActions(): Flow<QSTileUserAction> =
        filter { action ->
            val isFalseAction =
                when (action) {
                    is QSTileUserAction.Click ->
                        falsingManager.isFalseTap(FalsingManager.LOW_PENALTY)
                    is QSTileUserAction.LongClick ->
                        falsingManager.isFalseLongTap(FalsingManager.LOW_PENALTY)
                }
            if (isFalseAction) {
                qsTileLogger.logUserActionRejectedByFalsing(action, spec)
            }
            !isFalseAction
        }

    private companion object {
        const val CLICK_THROTTLE_DURATION = 200L
    }

    /**
     * Factory interface for assisted inject. Dagger has bad time supporting generics in assisted
     * injection factories now. That's why you need to create an interface implementing this one and
     * annotate it with [dagger.assisted.AssistedFactory].
     *
     * ex: @AssistedFactory interface FooFactory : BaseQSTileViewModel.Factory<FooData>
     */
    interface Factory<T> {

        /**
         * @param config contains all the static information (like TileSpec) about the tile.
         * @param userActionInteractor encapsulates user input processing logic. Use it to start
         *   activities, show dialogs or otherwise update the tile state.
         * @param tileDataInteractor provides [DATA_TYPE] and its availability.
         * @param mapper maps [DATA_TYPE] to the [QSTileState] that is then displayed by the View
         *   layer. It's called in [backgroundDispatcher], so it's safe to perform long running
         *   operations there.
         */
        fun create(
            config: QSTileConfig,
            userActionInteractor: QSTileUserActionInteractor<T>,
            tileDataInteractor: QSTileDataInteractor<T>,
            mapper: QSTileDataToStateMapper<T>,
        ): BaseQSTileViewModel<T>
    }
}
