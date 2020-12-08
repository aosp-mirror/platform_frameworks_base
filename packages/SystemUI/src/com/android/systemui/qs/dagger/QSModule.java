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

import android.content.Context;
import android.hardware.display.NightDisplayListener;
import android.os.Handler;

import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.qs.AutoAddTracker;
import com.android.systemui.qs.QSTileHost;
import com.android.systemui.statusbar.phone.AutoTileManager;
import com.android.systemui.statusbar.phone.ManagedProfileController;
import com.android.systemui.statusbar.policy.CastController;
import com.android.systemui.statusbar.policy.DataSaverController;
import com.android.systemui.statusbar.policy.HotspotController;

import dagger.Module;
import dagger.Provides;

/**
 * Module for QS dependencies
 */
// TODO: Add other QS classes
@Module
public interface QSModule {

    @Provides
    static AutoTileManager provideAutoTileManager(
            Context context,
            AutoAddTracker.Builder autoAddTrackerBuilder,
            QSTileHost host,
            @Background Handler handler,
            HotspotController hotspotController,
            DataSaverController dataSaverController,
            ManagedProfileController managedProfileController,
            NightDisplayListener nightDisplayListener,
            CastController castController) {
        AutoTileManager manager = new AutoTileManager(context, autoAddTrackerBuilder,
                host, handler, hotspotController, dataSaverController, managedProfileController,
                nightDisplayListener, castController);
        manager.init();
        return manager;
    }
}
