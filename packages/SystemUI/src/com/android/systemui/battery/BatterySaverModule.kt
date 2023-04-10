package com.android.systemui.battery

import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.qs.tiles.BatterySaverTile
import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey

@Module
interface BatterySaverModule {

    /** Inject BatterySaverTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(BatterySaverTile.TILE_SPEC)
    fun bindBatterySaverTile(batterySaverTile: BatterySaverTile): QSTileImpl<*>
}
