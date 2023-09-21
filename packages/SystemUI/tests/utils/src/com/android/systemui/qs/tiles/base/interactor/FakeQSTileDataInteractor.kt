package com.android.systemui.qs.tiles.base.interactor

import javax.annotation.CheckReturnValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

class FakeQSTileDataInteractor<T>(
    private val dataFlow: MutableSharedFlow<FakeData<T>> =
        MutableSharedFlow(replay = Int.MAX_VALUE),
    private val availabilityFlow: MutableSharedFlow<Boolean> =
        MutableSharedFlow(replay = Int.MAX_VALUE),
) : QSTileDataInteractor<T> {

    private val mutableDataRequests = mutableListOf<QSTileDataRequest>()
    val dataRequests: List<QSTileDataRequest> = mutableDataRequests

    private val mutableAvailabilityRequests = mutableListOf<Unit>()
    val availabilityRequests: List<Unit> = mutableAvailabilityRequests

    @CheckReturnValue
    fun emitData(data: T): FilterEmit =
        object : FilterEmit {
            override fun forRequest(request: QSTileDataRequest): Boolean =
                dataFlow.tryEmit(FakeData(data, DataFilter.ForRequest(request)))
            override fun forAnyRequest(): Boolean = dataFlow.tryEmit(FakeData(data, DataFilter.Any))
        }

    fun tryEmitAvailability(isAvailable: Boolean): Boolean = availabilityFlow.tryEmit(isAvailable)
    suspend fun emitAvailability(isAvailable: Boolean) = availabilityFlow.emit(isAvailable)

    override fun tileData(qsTileDataRequest: QSTileDataRequest): Flow<T> {
        mutableDataRequests.add(qsTileDataRequest)
        return dataFlow
            .filter {
                when (it.filter) {
                    is DataFilter.Any -> true
                    is DataFilter.ForRequest -> it.filter.request == qsTileDataRequest
                }
            }
            .map { it.data }
    }

    override fun availability(): Flow<Boolean> {
        mutableAvailabilityRequests.add(Unit)
        return availabilityFlow
    }

    interface FilterEmit {
        fun forRequest(request: QSTileDataRequest): Boolean
        fun forAnyRequest(): Boolean
    }

    class FakeData<T>(
        val data: T,
        val filter: DataFilter,
    )

    sealed class DataFilter {
        object Any : DataFilter()
        class ForRequest(val request: QSTileDataRequest) : DataFilter()
    }
}
