package com.android.systemui.qs.tiles.base.interactor

import com.android.systemui.qs.tiles.viewmodel.QSTileState
import com.android.systemui.qs.tiles.viewmodel.QSTileUserAction

sealed interface StateUpdateTrigger {
    class UserAction<T>(val action: QSTileUserAction, val tileState: QSTileState, val tileData: T) :
        StateUpdateTrigger
    data object ForceUpdate : StateUpdateTrigger
    data object InitialRequest : StateUpdateTrigger
}
