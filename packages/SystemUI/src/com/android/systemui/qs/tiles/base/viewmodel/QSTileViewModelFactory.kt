/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.qs.tiles.base.viewmodel

import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.base.analytics.QSTileAnalytics
import com.android.systemui.qs.tiles.base.interactor.DisabledByPolicyInteractor
import com.android.systemui.qs.tiles.base.interactor.QSTileDataInteractor
import com.android.systemui.qs.tiles.base.interactor.QSTileDataToStateMapper
import com.android.systemui.qs.tiles.base.interactor.QSTileUserActionInteractor
import com.android.systemui.qs.tiles.base.logging.QSTileLogger
import com.android.systemui.qs.tiles.impl.di.QSTileComponent
import com.android.systemui.qs.tiles.viewmodel.QSTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileConfigProvider
import com.android.systemui.qs.tiles.viewmodel.QSTileState
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.util.time.SystemClock
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Factory to create an appropriate [QSTileViewModelImpl] instance depending on your circumstances.
 *
 * @see [QSTileViewModelFactory.Component]
 * @see [QSTileViewModelFactory.Static]
 */
sealed interface QSTileViewModelFactory<T> {

    /**
     * This factory allows you to pass an instance of [QSTileComponent] to a view model effectively
     * binding them together. This achieves a DI scope that lives along the instance of
     * [QSTileViewModelImpl].
     */
    class Component<T>
    @Inject
    constructor(
        private val disabledByPolicyInteractor: DisabledByPolicyInteractor,
        private val userRepository: UserRepository,
        private val falsingManager: FalsingManager,
        private val qsTileAnalytics: QSTileAnalytics,
        private val qsTileLogger: QSTileLogger,
        private val qsTileConfigProvider: QSTileConfigProvider,
        private val systemClock: SystemClock,
        @Background private val backgroundDispatcher: CoroutineDispatcher,
    ) : QSTileViewModelFactory<T> {

        /**
         * Creates [QSTileViewModelImpl] based on the interactors obtained from [QSTileComponent].
         * Reference of that [QSTileComponent] is then stored along the view model.
         */
        fun create(
            tileSpec: TileSpec,
            componentFactory: (config: QSTileConfig) -> QSTileComponent<T>
        ): QSTileViewModelImpl<T> {
            val config = qsTileConfigProvider.getConfig(tileSpec.spec)
            val component = componentFactory(config)
            return QSTileViewModelImpl(
                config,
                component::userActionInteractor,
                component::dataInteractor,
                component::dataToStateMapper,
                disabledByPolicyInteractor,
                userRepository,
                falsingManager,
                qsTileAnalytics,
                qsTileLogger,
                systemClock,
                backgroundDispatcher,
            )
        }
    }

    /**
     * This factory passes by necessary implementations to the [QSTileViewModelImpl]. This is a
     * default choice for most of the tiles.
     */
    class Static<T>
    @Inject
    constructor(
        private val disabledByPolicyInteractor: DisabledByPolicyInteractor,
        private val userRepository: UserRepository,
        private val falsingManager: FalsingManager,
        private val qsTileAnalytics: QSTileAnalytics,
        private val qsTileLogger: QSTileLogger,
        private val qsTileConfigProvider: QSTileConfigProvider,
        private val systemClock: SystemClock,
        @Background private val backgroundDispatcher: CoroutineDispatcher,
    ) : QSTileViewModelFactory<T> {

        /**
         * @param tileSpec of the created tile.
         * @param userActionInteractor encapsulates user input processing logic. Use it to start
         *   activities, show dialogs or otherwise update the tile state.
         * @param tileDataInteractor provides [DATA_TYPE] and its availability.
         * @param mapper maps [DATA_TYPE] to the [QSTileState] that is then displayed by the View
         *   layer. It's called in [backgroundDispatcher], so it's safe to perform long running
         *   operations there.
         */
        fun create(
            tileSpec: TileSpec,
            userActionInteractor: QSTileUserActionInteractor<T>,
            tileDataInteractor: QSTileDataInteractor<T>,
            mapper: QSTileDataToStateMapper<T>,
        ): QSTileViewModelImpl<T> =
            QSTileViewModelImpl(
                qsTileConfigProvider.getConfig(tileSpec.spec),
                { userActionInteractor },
                { tileDataInteractor },
                { mapper },
                disabledByPolicyInteractor,
                userRepository,
                falsingManager,
                qsTileAnalytics,
                qsTileLogger,
                systemClock,
                backgroundDispatcher,
            )
    }
}
