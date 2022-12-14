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
import com.android.systemui.log.dagger.MediaTttReceiverLogBuffer;
import com.android.systemui.log.dagger.MediaTttSenderLogBuffer;
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
import com.android.systemui.media.taptotransfer.common.MediaTttLogger;
import com.android.systemui.media.taptotransfer.receiver.ChipReceiverInfo;
import com.android.systemui.media.taptotransfer.receiver.MediaTttReceiverLogger;
import com.android.systemui.media.taptotransfer.sender.MediaTttSenderLogger;
import com.android.systemui.plugins.log.LogBuffer;
import com.android.systemui.temporarydisplay.chipbar.ChipbarInfo;

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

    @Provides
    @SysUISingleton
    @MediaTttSenderLogger
    static MediaTttLogger<ChipbarInfo> providesMediaTttSenderLogger(
            @MediaTttSenderLogBuffer LogBuffer buffer
    ) {
        return new MediaTttLogger<>("Sender", buffer);
    }

    @Provides
    @SysUISingleton
    @MediaTttReceiverLogger
    static MediaTttLogger<ChipReceiverInfo> providesMediaTttReceiverLogger(
            @MediaTttReceiverLogBuffer LogBuffer buffer
    ) {
        return new MediaTttLogger<>("Receiver", buffer);
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
