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

package com.android.systemui.communal.dagger

import android.content.Context
import com.android.systemui.communal.data.backup.CommunalBackupUtils
import com.android.systemui.communal.data.db.CommunalDatabaseModule
import com.android.systemui.communal.data.repository.CommunalMediaRepositoryModule
import com.android.systemui.communal.data.repository.CommunalPrefsRepositoryModule
import com.android.systemui.communal.data.repository.CommunalRepositoryModule
import com.android.systemui.communal.data.repository.CommunalSettingsRepositoryModule
import com.android.systemui.communal.data.repository.CommunalTutorialRepositoryModule
import com.android.systemui.communal.data.repository.CommunalWidgetRepositoryModule
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.communal.util.CommunalColors
import com.android.systemui.communal.util.CommunalColorsImpl
import com.android.systemui.communal.widgets.CommunalWidgetModule
import com.android.systemui.communal.widgets.EditWidgetsActivityStarter
import com.android.systemui.communal.widgets.EditWidgetsActivityStarterImpl
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.scene.shared.model.SceneContainerConfig
import com.android.systemui.scene.shared.model.SceneDataSource
import com.android.systemui.scene.shared.model.SceneDataSourceDelegator
import dagger.Binds
import dagger.Module
import dagger.Provides
import kotlinx.coroutines.CoroutineScope

@Module(
    includes =
        [
            CommunalRepositoryModule::class,
            CommunalMediaRepositoryModule::class,
            CommunalTutorialRepositoryModule::class,
            CommunalWidgetRepositoryModule::class,
            CommunalDatabaseModule::class,
            CommunalWidgetModule::class,
            CommunalPrefsRepositoryModule::class,
            CommunalSettingsRepositoryModule::class,
        ]
)
interface CommunalModule {
    @Binds
    fun bindEditWidgetsActivityStarter(
        starter: EditWidgetsActivityStarterImpl
    ): EditWidgetsActivityStarter

    @Binds
    @Communal
    fun bindCommunalSceneDataSource(@Communal delegator: SceneDataSourceDelegator): SceneDataSource

    @Binds fun bindCommunalColors(impl: CommunalColorsImpl): CommunalColors

    companion object {
        @Provides
        @Communal
        @SysUISingleton
        fun providesCommunalSceneDataSourceDelegator(
            @Application applicationScope: CoroutineScope
        ): SceneDataSourceDelegator {
            val config =
                SceneContainerConfig(
                    sceneKeys = listOf(CommunalScenes.Blank, CommunalScenes.Communal),
                    initialSceneKey = CommunalScenes.Blank,
                    navigationDistances =
                        mapOf(
                            CommunalScenes.Blank to 0,
                            CommunalScenes.Communal to 1,
                        ),
                )
            return SceneDataSourceDelegator(applicationScope, config)
        }

        @Provides
        @SysUISingleton
        fun providesCommunalBackupUtils(
            @Application context: Context,
        ): CommunalBackupUtils {
            return CommunalBackupUtils(context)
        }
    }
}
