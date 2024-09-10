package com.android.systemui.battery

import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.qs.tiles.BatterySaverTile
import com.android.systemui.qs.tiles.base.interactor.QSTileAvailabilityInteractor
import com.android.systemui.qs.tiles.base.viewmodel.QSTileViewModelFactory
import com.android.systemui.qs.tiles.impl.battery.domain.interactor.BatterySaverTileDataInteractor
import com.android.systemui.qs.tiles.impl.battery.domain.interactor.BatterySaverTileUserActionInteractor
import com.android.systemui.qs.tiles.impl.battery.domain.model.BatterySaverTileModel
import com.android.systemui.qs.tiles.impl.battery.ui.BatterySaverTileMapper
import com.android.systemui.qs.tiles.viewmodel.QSTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileUIConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileViewModel
import com.android.systemui.res.R
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey

@Module
interface BatterySaverModule {

    /** Inject BatterySaverTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(BatterySaverTile.TILE_SPEC)
    fun bindBatterySaverTile(batterySaverTile: BatterySaverTile): QSTileImpl<*>

    @Binds
    @IntoMap
    @StringKey(BATTERY_SAVER_TILE_SPEC)
    fun provideBatterySaverAvailabilityInteractor(
            impl: BatterySaverTileDataInteractor
    ): QSTileAvailabilityInteractor

    companion object {
        private const val BATTERY_SAVER_TILE_SPEC = "battery"

        @Provides
        @IntoMap
        @StringKey(BATTERY_SAVER_TILE_SPEC)
        fun provideBatterySaverTileConfig(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(BATTERY_SAVER_TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = R.drawable.qs_battery_saver_icon_off,
                        labelRes = R.string.battery_detail_switch_title,
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
            )

        /** Inject BatterySaverTile into tileViewModelMap in QSModule */
        @Provides
        @IntoMap
        @StringKey(BATTERY_SAVER_TILE_SPEC)
        fun provideBatterySaverTileViewModel(
            factory: QSTileViewModelFactory.Static<BatterySaverTileModel>,
            mapper: BatterySaverTileMapper,
            stateInteractor: BatterySaverTileDataInteractor,
            userActionInteractor: BatterySaverTileUserActionInteractor
        ): QSTileViewModel =
            factory.create(
                TileSpec.create(BATTERY_SAVER_TILE_SPEC),
                userActionInteractor,
                stateInteractor,
                mapper,
            )
    }
}
