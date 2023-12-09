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

import static com.android.systemui.qs.dagger.QSFlagsModule.RBC_AVAILABLE;

import android.content.Context;
import android.os.Handler;

import com.android.systemui.dagger.NightDisplayListenerModule;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.media.dagger.MediaModule;
import com.android.systemui.qs.AutoAddTracker;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.ReduceBrightColorsController;
import com.android.systemui.qs.external.QSExternalModule;
import com.android.systemui.qs.pipeline.dagger.QSPipelineModule;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.statusbar.phone.AutoTileManager;
import com.android.systemui.statusbar.phone.ManagedProfileController;
import com.android.systemui.statusbar.policy.CastController;
import com.android.systemui.statusbar.policy.DataSaverController;
import com.android.systemui.statusbar.policy.DeviceControlsController;
import com.android.systemui.statusbar.policy.HotspotController;
import com.android.systemui.statusbar.policy.SafetyController;
import com.android.systemui.statusbar.policy.WalletController;
import com.android.systemui.util.settings.SecureSettings;

import java.util.Map;

import javax.inject.Named;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.Multibinds;

/**
 * Module for QS dependencies
 */
@Module(subcomponents = {QSFragmentComponent.class},
        includes = {
                MediaModule.class,
                QSExternalModule.class,
                QSFlagsModule.class,
                QSHostModule.class,
                QSPipelineModule.class,
        }
)
public interface QSModule {

    /** A map of internal QS tiles. Ensures that this can be injected even if
     * it is empty */
    @Multibinds
    Map<String, QSTileImpl<?>> tileMap();

    @Provides
    @SysUISingleton
    static AutoTileManager provideAutoTileManager(
            Context context,
            AutoAddTracker.Builder autoAddTrackerBuilder,
            QSHost host,
            @Background Handler handler,
            SecureSettings secureSettings,
            HotspotController hotspotController,
            DataSaverController dataSaverController,
            ManagedProfileController managedProfileController,
            NightDisplayListenerModule.Builder nightDisplayListenerBuilder,
            CastController castController,
            ReduceBrightColorsController reduceBrightColorsController,
            DeviceControlsController deviceControlsController,
            WalletController walletController,
            SafetyController safetyController,
            @Named(RBC_AVAILABLE) boolean isReduceBrightColorsAvailable) {
        AutoTileManager manager = new AutoTileManager(
                context,
                autoAddTrackerBuilder,
                host,
                handler,
                secureSettings,
                hotspotController,
                dataSaverController,
                managedProfileController,
                nightDisplayListenerBuilder,
                castController,
                reduceBrightColorsController,
                deviceControlsController,
                walletController,
                safetyController,
                isReduceBrightColorsAvailable
        );
        manager.init();
        return manager;
    }
}
