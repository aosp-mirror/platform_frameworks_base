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

package com.android.systemui.media.dagger;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.media.MediaDataManager;
import com.android.systemui.media.MediaHierarchyManager;
import com.android.systemui.media.MediaHost;
import com.android.systemui.media.MediaHostStatesManager;

import javax.inject.Named;

import dagger.Module;
import dagger.Provides;

/** Dagger module for the media package. */
@Module
public interface MediaModule {
    String QS_PANEL = "media_qs_panel";
    String QUICK_QS_PANEL = "media_quick_qs_panel";
    String KEYGUARD = "media_keyguard";

    /** */
    @Provides
    @SysUISingleton
    @Named(QS_PANEL)
    static MediaHost providesQSMediaHost(MediaHost.MediaHostStateHolder stateHolder,
            MediaHierarchyManager hierarchyManager, MediaDataManager dataManager,
            MediaHostStatesManager statesManager) {
        return new MediaHost(stateHolder, hierarchyManager, dataManager, statesManager);
    }

    /** */
    @Provides
    @SysUISingleton
    @Named(QUICK_QS_PANEL)
    static MediaHost providesQuickQSMediaHost(MediaHost.MediaHostStateHolder stateHolder,
            MediaHierarchyManager hierarchyManager, MediaDataManager dataManager,
            MediaHostStatesManager statesManager) {
        return new MediaHost(stateHolder, hierarchyManager, dataManager, statesManager);
    }

    /** */
    @Provides
    @SysUISingleton
    @Named(KEYGUARD)
    static MediaHost providesKeyguardMediaHost(MediaHost.MediaHostStateHolder stateHolder,
            MediaHierarchyManager hierarchyManager, MediaDataManager dataManager,
            MediaHostStatesManager statesManager) {
        return new MediaHost(stateHolder, hierarchyManager, dataManager, statesManager);
    }
}
