package com.android.systemui.qs.pipeline.data.repository

import android.content.res.Resources
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.shade.ShadeDisplayAware
import javax.inject.Inject

interface DefaultTilesRepository {
    val defaultTiles: List<TileSpec>
}

@SysUISingleton
class DefaultTilesQSHostRepository
@Inject
constructor(@ShadeDisplayAware private val resources: Resources) : DefaultTilesRepository {
    override val defaultTiles: List<TileSpec>
        get() =
            QSHost.getDefaultSpecs(resources).map(TileSpec::create).filter {
                it != TileSpec.Invalid
            }
}
