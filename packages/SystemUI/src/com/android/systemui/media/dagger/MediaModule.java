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

import android.app.Service;
import android.content.Context;
import android.view.WindowManager;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.media.MediaDataManager;
import com.android.systemui.media.MediaHierarchyManager;
import com.android.systemui.media.MediaHost;
import com.android.systemui.media.MediaHostStatesManager;
import com.android.systemui.media.dream.dagger.MediaComplicationComponent;
import com.android.systemui.media.nearby.NearbyMediaDevicesService;
import com.android.systemui.media.taptotransfer.MediaTttCommandLineHelper;
import com.android.systemui.media.taptotransfer.MediaTttFlags;
import com.android.systemui.media.taptotransfer.receiver.MediaTttChipControllerReceiver;
import com.android.systemui.media.taptotransfer.sender.MediaTttChipControllerSender;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.commandline.CommandRegistry;

import java.util.Optional;
import java.util.concurrent.Executor;

import javax.inject.Named;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ClassKey;
import dagger.multibindings.IntoMap;

/** Dagger module for the media package. */
@Module(subcomponents = {
        MediaComplicationComponent.class,
})
public interface MediaModule {
    String QS_PANEL = "media_qs_panel";
    String QUICK_QS_PANEL = "media_quick_qs_panel";
    String KEYGUARD = "media_keyguard";
    String DREAM = "dream";

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

    /** */
    @Provides
    @SysUISingleton
    @Named(DREAM)
    static MediaHost providesDreamMediaHost(MediaHost.MediaHostStateHolder stateHolder,
            MediaHierarchyManager hierarchyManager, MediaDataManager dataManager,
            MediaHostStatesManager statesManager) {
        return new MediaHost(stateHolder, hierarchyManager, dataManager, statesManager);
    }

    /** */
    @Provides
    @SysUISingleton
    static Optional<MediaTttChipControllerSender> providesMediaTttChipControllerSender(
            MediaTttFlags mediaTttFlags,
            CommandQueue commandQueue,
            Context context,
            WindowManager windowManager) {
        if (!mediaTttFlags.isMediaTttEnabled()) {
            return Optional.empty();
        }
        return Optional.of(new MediaTttChipControllerSender(commandQueue, context, windowManager));
    }

    /** */
    @Provides
    @SysUISingleton
    static Optional<MediaTttChipControllerReceiver> providesMediaTttChipControllerReceiver(
            MediaTttFlags mediaTttFlags,
            CommandQueue commandQueue,
            Context context,
            WindowManager windowManager) {
        if (!mediaTttFlags.isMediaTttEnabled()) {
            return Optional.empty();
        }
        return Optional.of(
                new MediaTttChipControllerReceiver(commandQueue, context, windowManager));
    }

    /** */
    @Provides
    @SysUISingleton
    static Optional<MediaTttCommandLineHelper> providesMediaTttCommandLineHelper(
            MediaTttFlags mediaTttFlags,
            CommandRegistry commandRegistry,
            Context context,
            @Main Executor mainExecutor) {
        if (!mediaTttFlags.isMediaTttEnabled()) {
            return Optional.empty();
        }
        return Optional.of(
                new MediaTttCommandLineHelper(commandRegistry, context, mainExecutor));
    }

    /** Inject into NearbyMediaDevicesService. */
    @Binds
    @IntoMap
    @ClassKey(NearbyMediaDevicesService.class)
    Service bindMediaNearbyDevicesService(NearbyMediaDevicesService service);
}
