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

package com.android.systemui.car.notification;

import android.content.Context;

import com.android.car.notification.CarHeadsUpNotificationManager;
import com.android.car.notification.CarNotificationListener;
import com.android.car.notification.CarUxRestrictionManagerWrapper;
import com.android.car.notification.NotificationClickHandlerFactory;
import com.android.car.notification.NotificationDataManager;
import com.android.car.notification.headsup.CarHeadsUpNotificationContainer;
import com.android.internal.statusbar.IStatusBarService;

import javax.inject.Singleton;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;

/**
 * Module for Car SysUI Notifications
 */
@Module
public abstract class CarNotificationModule {
    @Provides
    @Singleton
    static NotificationClickHandlerFactory provideNotificationClickHandlerFactory(
            IStatusBarService barService) {
        return new NotificationClickHandlerFactory(barService);
    }

    @Provides
    @Singleton
    static NotificationDataManager provideNotificationDataManager() {
        return new NotificationDataManager();
    }

    @Provides
    @Singleton
    static CarUxRestrictionManagerWrapper provideCarUxRestrictionManagerWrapper() {
        return new CarUxRestrictionManagerWrapper();
    }

    @Provides
    @Singleton
    static CarNotificationListener provideCarNotificationListener(Context context,
            CarUxRestrictionManagerWrapper carUxRestrictionManagerWrapper,
            CarHeadsUpNotificationManager carHeadsUpNotificationManager,
            NotificationDataManager notificationDataManager) {
        CarNotificationListener listener = new CarNotificationListener();
        listener.registerAsSystemService(context, carUxRestrictionManagerWrapper,
                carHeadsUpNotificationManager, notificationDataManager);
        return listener;
    }

    @Provides
    @Singleton
    static CarHeadsUpNotificationManager provideCarHeadsUpNotificationManager(Context context,
            NotificationClickHandlerFactory notificationClickHandlerFactory,
            NotificationDataManager notificationDataManager,
            CarHeadsUpNotificationContainer headsUpNotificationDisplay) {
        return new CarHeadsUpNotificationManager(context, notificationClickHandlerFactory,
                notificationDataManager, headsUpNotificationDisplay);
    }

    @Binds
    abstract CarHeadsUpNotificationContainer bindsCarHeadsUpNotificationContainer(
            CarHeadsUpNotificationSystemContainer carHeadsUpNotificationSystemContainer);
}
