package com.android.systemui.qs.tiles.base.interactor

import com.android.systemui.qs.tiles.viewmodel.QSTileState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow

/**
 * Provides data and availability for the tile. In most cases it would delegate data retrieval to
 * repository, manager, controller or a combination of those. Avoid doing long running operations in
 * these methods because there is no background thread guarantee. Use [Flow.flowOn] (typically
 * with @Background [CoroutineDispatcher]) instead to move the calculations to another thread.
 */
interface QSTileDataInteractor<DATA_TYPE> {

    /**
     * Returns the data to be mapped to [QSTileState]. Make sure to start the flow [Flow.onStart]
     * with the current state to update the tile as soon as possible.
     */
    fun tileData(qsTileDataRequest: QSTileDataRequest): Flow<DATA_TYPE>

    /**
     * Returns tile availability - whether this device currently supports this tile. Make sure to
     * start the flow [Flow.onStart] with the current state to update the tile as soon as possible.
     */
    fun availability(): Flow<Boolean>
}
