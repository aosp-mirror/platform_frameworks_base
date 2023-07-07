package com.android.systemui.rotationlock

import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.qs.tiles.RotationLockTile
import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey

@Module
interface RotationLockModule {

    /** Inject RotationLockTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(RotationLockTile.TILE_SPEC)
    fun bindRotationLockTile(rotationLockTile: RotationLockTile): QSTileImpl<*>
}
