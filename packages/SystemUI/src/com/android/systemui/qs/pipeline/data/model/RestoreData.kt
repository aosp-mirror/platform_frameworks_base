package com.android.systemui.qs.pipeline.data.model

import com.android.systemui.qs.pipeline.shared.TileSpec

/** Data restored from Quick Settings as part of Backup & Restore. */
data class RestoreData(
    val restoredTiles: List<TileSpec>,
    val restoredAutoAddedTiles: Set<TileSpec>,
    val userId: Int,
)
