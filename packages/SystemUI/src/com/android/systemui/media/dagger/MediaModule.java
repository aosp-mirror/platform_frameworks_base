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
import com.android.systemui.log.LogBufferFactory;
import com.android.systemui.media.controls.pipeline.MediaDataManager;
import com.android.systemui.media.controls.ui.MediaHierarchyManager;
import com.android.systemui.media.controls.ui.MediaHost;
import com.android.systemui.media.controls.ui.MediaHostStatesManager;
import com.android.systemui.media.controls.util.MediaFlags;
import com.android.systemui.media.dream.dagger.MediaComplicationComponent;
import com.android.systemui.media.muteawait.MediaMuteAwaitConnectionCli;
import com.android.systemui.media.nearby.NearbyMediaDevicesManager;
import com.android.systemui.media.taptotransfer.MediaTttCommandLineHelper;
import com.android.systemui.media.taptotransfer.MediaTttFlags;
import com.android.systemui.media.taptotransfer.receiver.MediaTttReceiverLogBuffer;
import com.android.systemui.media.taptotransfer.sender.MediaTttSenderLogBuffer;
import com.android.systemui.plugins.log.LogBuffer;

import java.util.Optional;

import javax.inject.Named;

import dagger.Lazy;
import dagger.Module;
import dagger.Provides;

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

    /** Provides a logging buffer related to the media tap-to-transfer chip on the sender device. */
    @Provides
    @SysUISingleton
    @MediaTttSenderLogBuffer
    static LogBuffer provideMediaTttSenderLogBuffer(LogBufferFactory factory) {
        return factory.create("MediaTttSender", 30);
    }

    /**
     * Provides a logging buffer related to the media tap-to-transfer chip on the receiver device.
     */
    @Provides
    @SysUISingleton
    @MediaTttReceiverLogBuffer
    static LogBuffer provideMediaTttReceiverLogBuffer(LogBufferFactory factory) {
        return factory.create("MediaTttReceiver", 20);
    }

    /** */
    @Provides
    @SysUISingleton
    static Optional<MediaTttCommandLineHelper> providesMediaTttCommandLineHelper(
            MediaTttFlags mediaTttFlags,
            Lazy<MediaTttCommandLineHelper> helperLazy) {
        if (!mediaTttFlags.isMediaTttEnabled()) {
            return Optional.empty();
        }
        return Optional.of(helperLazy.get());
    }

    /** */
    @Provides
    @SysUISingleton
    static Optional<MediaMuteAwaitConnectionCli> providesMediaMuteAwaitConnectionCli(
            MediaFlags mediaFlags,
            Lazy<MediaMuteAwaitConnectionCli> muteAwaitConnectionCliLazy
    ) {
        if (!mediaFlags.areMuteAwaitConnectionsEnabled()) {
            return Optional.empty();
        }
        return Optional.of(muteAwaitConnectionCliLazy.get());
    }

    /** */
    @Provides
    @SysUISingleton
    static Optional<NearbyMediaDevicesManager> providesNearbyMediaDevicesManager(
            MediaFlags mediaFlags,
            Lazy<NearbyMediaDevicesManager> nearbyMediaDevicesManagerLazy) {
        if (!mediaFlags.areNearbyMediaDevicesEnabled()) {
            return Optional.empty();
        }
        return Optional.of(nearbyMediaDevicesManagerLazy.get());
    }
}
