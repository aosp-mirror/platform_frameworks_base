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

package com.android.systemui.statusbar.notification.dagger;

import android.content.Context;

import com.android.systemui.R;
import com.android.systemui.statusbar.notification.init.NotificationsController;
import com.android.systemui.statusbar.notification.init.NotificationsControllerImpl;
import com.android.systemui.statusbar.notification.init.NotificationsControllerStub;

import javax.inject.Singleton;

import dagger.Lazy;
import dagger.Module;
import dagger.Provides;

/** Module for classes related to the notifications data pipeline */
@Module
public class NotificationsModule {
    /** Initializes the notification data pipeline (can be disabled via config). */
    @Singleton
    @Provides
    static NotificationsController provideNotificationsController(
            Context context,
            Lazy<NotificationsControllerImpl> realController,
            Lazy<NotificationsControllerStub> stubController) {
        if (context.getResources().getBoolean(R.bool.config_renderNotifications)) {
            return realController.get();
        } else {
            return stubController.get();
        }
    }
}
