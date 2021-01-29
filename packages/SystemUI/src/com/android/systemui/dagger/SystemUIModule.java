/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.dagger;

import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.PackageManager;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.BootCompleteCache;
import com.android.systemui.BootCompleteCacheImpl;
import com.android.systemui.assist.AssistModule;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.log.dagger.LogModule;
import com.android.systemui.model.SysUiState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.recents.Recents;
import com.android.systemui.settings.dagger.SettingsModule;
import com.android.systemui.stackdivider.Divider;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.notification.collection.inflation.NotificationRowBinder;
import com.android.systemui.statusbar.notification.collection.inflation.NotificationRowBinderImpl;
import com.android.systemui.statusbar.notification.people.PeopleHubModule;
import com.android.systemui.statusbar.notification.row.dagger.ExpandableNotificationRowComponent;
import com.android.systemui.statusbar.notification.row.dagger.NotificationRowComponent;
import com.android.systemui.statusbar.phone.KeyguardLiftController;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.phone.dagger.StatusBarComponent;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.util.concurrency.ConcurrencyModule;
import com.android.systemui.util.sensors.AsyncSensorManager;
import com.android.systemui.util.sensors.SensorModule;
import com.android.systemui.util.time.SystemClock;
import com.android.systemui.util.time.SystemClockImpl;

import javax.inject.Singleton;

import dagger.Binds;
import dagger.BindsOptionalOf;
import dagger.Module;
import dagger.Provides;

/**
 * A dagger module for injecting components of System UI that are not overridden by the System UI
 * implementation.
 */
@Module(includes = {
            AssistModule.class,
            ConcurrencyModule.class,
            LogModule.class,
            PeopleHubModule.class,
            SensorModule.class,
            SettingsModule.class,
        },
        subcomponents = {StatusBarComponent.class,
                NotificationRowComponent.class,
                ExpandableNotificationRowComponent.class})
public abstract class SystemUIModule {

    @Binds
    abstract BootCompleteCache bindBootCompleteCache(BootCompleteCacheImpl bootCompleteCache);

    /** */
    @Binds
    public abstract ContextComponentHelper bindComponentHelper(
            ContextComponentResolver componentHelper);

    @Singleton
    @Provides
    @Nullable
    static KeyguardLiftController provideKeyguardLiftController(
            Context context,
            StatusBarStateController statusBarStateController,
            AsyncSensorManager asyncSensorManager,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            DumpManager dumpManager) {
        if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_FACE)) {
            return null;
        }
        return new KeyguardLiftController(statusBarStateController, asyncSensorManager,
                keyguardUpdateMonitor, dumpManager);
    }

    /** */
    @Binds
    public abstract NotificationRowBinder bindNotificationRowBinder(
            NotificationRowBinderImpl notificationRowBinder);

    @Singleton
    @Provides
    static SysUiState provideSysUiState() {
        return new SysUiState();
    }

    @BindsOptionalOf
    abstract CommandQueue optionalCommandQueue();

    @BindsOptionalOf
    abstract Divider optionalDivider();

    @BindsOptionalOf
    abstract HeadsUpManager optionalHeadsUpManager();

    @BindsOptionalOf
    abstract Recents optionalRecents();

    @BindsOptionalOf
    abstract StatusBar optionalStatusBar();

    @Singleton
    @Binds
    abstract SystemClock bindSystemClock(SystemClockImpl systemClock);
}
