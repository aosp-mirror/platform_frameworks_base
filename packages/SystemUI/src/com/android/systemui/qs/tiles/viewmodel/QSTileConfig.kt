package com.android.systemui.qs.tiles.viewmodel

import android.graphics.drawable.Icon
import com.android.systemui.qs.pipeline.shared.TileSpec

data class QSTileConfig(
    val tileSpec: TileSpec,
    val tileIcon: Icon,
    val tileLabel: CharSequence,
// TODO(b/299908705): Fill necessary params
/*
val instanceId: InstanceId,
 */
)
