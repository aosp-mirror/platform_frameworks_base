/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.rotationlock

import com.android.systemui.camera.CameraRotationModule
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.base.interactor.QSTileAvailabilityInteractor
import com.android.systemui.qs.tiles.base.viewmodel.QSTileViewModelFactory
import com.android.systemui.qs.tiles.impl.rotation.domain.interactor.RotationLockTileDataInteractor
import com.android.systemui.qs.tiles.impl.rotation.domain.interactor.RotationLockTileUserActionInteractor
import com.android.systemui.qs.tiles.impl.rotation.domain.model.RotationLockTileModel
import com.android.systemui.qs.tiles.impl.rotation.ui.mapper.RotationLockTileMapper
import com.android.systemui.qs.tiles.viewmodel.QSTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileUIConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileViewModel
import com.android.systemui.res.R
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey

@Module(includes = [CameraRotationModule::class])
interface RotationLockNewModule {

    @Binds
    @IntoMap
    @StringKey(ROTATION_TILE_SPEC)
    fun provideRotationAvailabilityInteractor(
            impl: RotationLockTileDataInteractor
    ): QSTileAvailabilityInteractor
    companion object {
        private const val ROTATION_TILE_SPEC = "rotation"

        /** Inject rotation tile config */
        @Provides
        @IntoMap
        @StringKey(ROTATION_TILE_SPEC)
        fun provideRotationTileConfig(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(ROTATION_TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = R.drawable.qs_auto_rotate_icon_off,
                        labelRes = R.string.quick_settings_rotation_unlocked_label,
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
            )

        /** Inject Rotation tile into tileViewModelMap in QSModule */
        @Provides
        @IntoMap
        @StringKey(ROTATION_TILE_SPEC)
        fun provideRotationTileViewModel(
            factory: QSTileViewModelFactory.Static<RotationLockTileModel>,
            mapper: RotationLockTileMapper,
            stateInteractor: RotationLockTileDataInteractor,
            userActionInteractor: RotationLockTileUserActionInteractor
        ): QSTileViewModel =
            factory.create(
                TileSpec.create(ROTATION_TILE_SPEC),
                userActionInteractor,
                stateInteractor,
                mapper,
            )
    }
}
