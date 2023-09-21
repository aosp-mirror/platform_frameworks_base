package com.android.systemui.qs.tiles.base.interactor

import android.annotation.WorkerThread
import com.android.systemui.qs.tiles.viewmodel.QSTileUserAction

interface QSTileUserActionInteractor<DATA_TYPE> {

    /**
     * Processes user input based on [userAction] and [currentData]. It's safe to run long running
     * computations inside this function in this.
     */
    @WorkerThread suspend fun handleInput(userAction: QSTileUserAction, currentData: DATA_TYPE)
}
