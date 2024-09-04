/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.qs.dagger;

import com.android.systemui.media.dagger.MediaModule;
import com.android.systemui.qs.ReduceBrightColorsController;
import com.android.systemui.qs.ReduceBrightColorsControllerImpl;
import com.android.systemui.qs.external.QSExternalModule;
import com.android.systemui.qs.panels.dagger.PanelsModule;
import com.android.systemui.qs.pipeline.dagger.QSPipelineModule;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.qs.tiles.di.QSTilesModule;
import com.android.systemui.qs.ui.adapter.QSSceneAdapter;
import com.android.systemui.qs.ui.adapter.QSSceneAdapterImpl;

import java.util.Map;

import dagger.Binds;
import dagger.Module;
import dagger.multibindings.Multibinds;

/**
 * Module for QS dependencies
 */
@Module(subcomponents = {QSFragmentComponent.class, QSSceneComponent.class},
        includes = {
                MediaModule.class,
                PanelsModule.class,
                QSExternalModule.class,
                QSFlagsModule.class,
                QSHostModule.class,
                QSPipelineModule.class,
                QSTilesModule.class,
        }
)
public interface QSModule {

    /**
     * A map of internal QS tiles. Ensures that this can be injected even if
     * it is empty
     */
    @Multibinds
    Map<String, QSTileImpl<?>> tileMap();

    @Binds
    QSSceneAdapter bindsQsSceneInteractor(QSSceneAdapterImpl impl);

    /**
     * Dims the screen
     */
    @Binds
    ReduceBrightColorsController bindReduceBrightColorsController(
            ReduceBrightColorsControllerImpl impl);
}
