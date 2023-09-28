package com.android.systemui.qs.tiles.base.viewmodel

import androidx.annotation.CallSuper
import androidx.annotation.VisibleForTesting
import com.android.internal.util.Preconditions
import com.android.systemui.qs.tiles.base.interactor.QSTileDataInteractor
import com.android.systemui.qs.tiles.base.interactor.QSTileDataRequest
import com.android.systemui.qs.tiles.base.interactor.QSTileDataToStateMapper
import com.android.systemui.qs.tiles.base.interactor.QSTileUserActionInteractor
import com.android.systemui.qs.tiles.base.interactor.StateUpdateTrigger
import com.android.systemui.qs.tiles.viewmodel.QSTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileLifecycle
import com.android.systemui.qs.tiles.viewmodel.QSTileState
import com.android.systemui.qs.tiles.viewmodel.QSTileUserAction
import com.android.systemui.qs.tiles.viewmodel.QSTileViewModel
import com.android.systemui.util.kotlin.sample
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
 */
abstract class BaseQSTileViewModel<DATA_TYPE>
@VisibleForTesting
constructor(
    final override val config: QSTileConfig,
    private val userActionInteractor: QSTileUserActionInteractor<DATA_TYPE>,
    private val tileDataInteractor: QSTileDataInteractor<DATA_TYPE>,
    private val mapper: QSTileDataToStateMapper<DATA_TYPE>,
    private val backgroundDispatcher: CoroutineDispatcher,
    private val tileScope: CoroutineScope,
) : QSTileViewModel {

    /**
     * @param config contains all the static information (like TileSpec) about the tile.
     * @param userActionInteractor encapsulates user input processing logic. Use it to start
     *   activities, show dialogs or otherwise update the tile state.
     * @param tileDataInteractor provides [DATA_TYPE] and its availability.
     * @param backgroundDispatcher is used to run the internal [DATA_TYPE] processing and call
     *   interactors methods. This should likely to be @Background CoroutineDispatcher.
     * @param mapper maps [DATA_TYPE] to the [QSTileState] that is then displayed by the View layer.
     *   It's called in [backgroundDispatcher], so it's safe to perform long running operations
     *   there.
     */
    constructor(
        config: QSTileConfig,
        userActionInteractor: QSTileUserActionInteractor<DATA_TYPE>,
        tileDataInteractor: QSTileDataInteractor<DATA_TYPE>,
        mapper: QSTileDataToStateMapper<DATA_TYPE>,
        backgroundDispatcher: CoroutineDispatcher,
    ) : this(
        config,
        userActionInteractor,
        tileDataInteractor,
        mapper,
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
                        userInputFlow(),
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

    private fun userInputFlow(): Flow<StateUpdateTrigger> {
        data class StateWithData<T>(val state: QSTileState, val data: T)

        // Skip the input until there is some data
        return userInputs.sample(
            state.combine(tileData) { state, data -> StateWithData(state, data) }
        ) { input, stateWithData ->
            StateUpdateTrigger.UserAction(input, stateWithData.state, stateWithData.data)
        }
    }
}
