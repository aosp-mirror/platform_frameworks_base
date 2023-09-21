package com.android.systemui.qs.tiles.base.interactor

import androidx.annotation.WorkerThread
import com.android.systemui.qs.tiles.viewmodel.QSTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileState

interface QSTileDataToStateMapper<DATA_TYPE> {

    /**
     * Maps [DATA_TYPE] to the [QSTileState] that is then displayed by the View layer. It's called
     * on a background thread, so it's safe to perform long running operations there.
     */
    @WorkerThread fun map(config: QSTileConfig, data: DATA_TYPE): QSTileState
}
