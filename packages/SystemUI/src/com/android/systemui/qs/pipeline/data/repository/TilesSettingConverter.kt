package com.android.systemui.qs.pipeline.data.repository

import com.android.systemui.qs.pipeline.shared.TileSpec

object TilesSettingConverter {

    const val DELIMITER = ","

    fun toTilesList(commaSeparatedTiles: String) =
        commaSeparatedTiles.split(DELIMITER).map(TileSpec::create).filter { it != TileSpec.Invalid }

    fun toTilesSet(commaSeparatedTiles: String) =
        commaSeparatedTiles
            .split(DELIMITER)
            .map(TileSpec::create)
            .filter { it != TileSpec.Invalid }
            .toSet()
}
