package com.android.systemui.qs.tiles.di

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.plugins.qs.QSFactory
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.qs.tiles.viewmodel.QSTileLifecycle
import com.android.systemui.qs.tiles.viewmodel.QSTileViewModel
import com.android.systemui.qs.tiles.viewmodel.QSTileViewModelAdapter
import javax.inject.Inject
import javax.inject.Provider

// TODO(b/http://b/299909989): Rename the factory after rollout
@SysUISingleton
class NewQSTileFactory
@Inject
constructor(
    private val adapterFactory: QSTileViewModelAdapter.Factory,
    private val tileMap:
        Map<String, @JvmSuppressWildcards Provider<@JvmSuppressWildcards QSTileViewModel>>,
) : QSFactory {

    override fun createTile(tileSpec: String): QSTile? =
        tileMap[tileSpec]?.let {
            val tile = it.get()
            tile.onLifecycle(QSTileLifecycle.ALIVE)
            adapterFactory.create(tile)
        }
}
