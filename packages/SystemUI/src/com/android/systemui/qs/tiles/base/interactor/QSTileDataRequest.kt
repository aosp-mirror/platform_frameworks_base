package com.android.systemui.qs.tiles.base.interactor

data class QSTileDataRequest(
    val userId: Int,
    val trigger: StateUpdateTrigger,
)
