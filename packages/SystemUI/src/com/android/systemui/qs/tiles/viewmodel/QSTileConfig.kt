package com.android.systemui.qs.tiles.viewmodel

import androidx.annotation.StringRes
import com.android.internal.logging.InstanceId
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.qs.pipeline.shared.TileSpec

data class QSTileConfig(
    val tileSpec: TileSpec,
    val tileIcon: Icon,
    @StringRes val tileLabelRes: Int,
    val instanceId: InstanceId,
)
