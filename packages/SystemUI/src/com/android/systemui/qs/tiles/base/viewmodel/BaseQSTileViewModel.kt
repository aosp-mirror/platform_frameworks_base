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
import com.android.systemui.qs.tiles.base.interactor.DisabledByPolicyInteractor
import com.android.systemui.qs.tiles.base.interactor.QSTileDataInteractor
import com.android.systemui.qs.tiles.base.interactor.QSTileDataRequest
import com.android.systemui.qs.tiles.base.interactor.QSTileDataToStateMapper
import com.android.systemui.qs.tiles.base.interactor.QSTileUserActionInteractor
import com.android.systemui.qs.tiles.base.interactor.StateUpdateTrigger
import com.android.systemui.qs.tiles.viewmodel.QSTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileLifecycle
import com.android.systemui.qs.tiles.viewmodel.QSTilePolicy
import com.android.systemui.qs.tiles.viewmodel.QSTileState
import com.android.systemui.qs.tiles.viewmodel.QSTileUserAction
import com.android.systemui.qs.tiles.viewmodel.QSTileViewModel
import com.android.systemui.util.kotlin.sample
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
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
class BaseQSTileViewModel<DATA_TYPE>
@VisibleForTesting
constructor(
    override val config: QSTileConfig,
    private val userActionInteractor: QSTileUserActionInteractor<DATA_TYPE>,
    private val tileDataInteractor: QSTileDataInteractor<DATA_TYPE>,
    private val mapper: QSTileDataToStateMapper<DATA_TYPE>,
    private val disabledByPolicyInteractor: DisabledByPolicyInteractor,
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
        @Background backgroundDispatcher: CoroutineDispatcher,
    ) : this(
        config,
        userActionInteractor,
        tileDataInteractor,
        mapper,
        disabledByPolicyInteractor,
        backgroundDispatcher,
        CoroutineScope(SupervisorJob())
    )

    private val userInputs: MutableSharedFlow<QSTileUserAction> =
        MutableSharedFlow(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val userIds: MutableSharedFlow<Int> =
        MutableSharedFlow(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val forceUpdates: MutableSharedFlow<Unit> =
        MutableSharedFlow(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private lateinit var tileData: SharedFlow<DATA_TYPE>

    override lateinit var state: SharedFlow<QSTileState>
    override val isAvailable: StateFlow<Boolean> =
        tileDataInteractor
            .availability()
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
        Preconditions.checkState(tileData.replayCache.isNotEmpty())
        Preconditions.checkState(currentLifeState == QSTileLifecycle.ALIVE)
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
                        // TODO(b/299908705): log data and corresponding tile state
                        .map { mapper.map(config, it) }
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
                merge(
                        userInputFlow(userId),
                        forceUpdates.map { StateUpdateTrigger.ForceUpdate },
                    )
                    .onStart { emit(StateUpdateTrigger.InitialRequest) }
                    .map { trigger -> QSTileDataRequest(userId, trigger) }
            }
            .flatMapLatest { request ->
                // 1) get an updated data source
                // 2) process user input, possibly triggering new data to be emitted
                // This handles the case when the data isn't buffered in the interactor
                // TODO(b/299908705): Log events that trigger data flow to update
                val dataFlow = tileDataInteractor.tileData(request)
                if (request.trigger is StateUpdateTrigger.UserAction<*>) {
                    userActionInteractor.handleInput(
                        request.trigger.action,
                        request.trigger.tileData as DATA_TYPE,
                    )
                }
                dataFlow
            }
            .flowOn(backgroundDispatcher)
            .shareIn(
                tileScope,
                SharingStarted.WhileSubscribed(),
                replay = 1, // we only care about the most recent value
            )

    private fun userInputFlow(userId: Int): Flow<StateUpdateTrigger> {
        data class StateWithData<T>(val state: QSTileState, val data: T)

        return when (config.policy) {
            is QSTilePolicy.NoRestrictions -> userInputs
            is QSTilePolicy.Restricted ->
                userInputs.filter {
                    val result =
                        disabledByPolicyInteractor.isDisabled(userId, config.policy.userRestriction)
                    !disabledByPolicyInteractor.handlePolicyResult(result)
                }
        // Skip the input until there is some data
        }.sample(state.combine(tileData) { state, data -> StateWithData(state, data) }) {
            input,
            stateWithData ->
            StateUpdateTrigger.UserAction(input, stateWithData.state, stateWithData.data)
        }
    }

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
