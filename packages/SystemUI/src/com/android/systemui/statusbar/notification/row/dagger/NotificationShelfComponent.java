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

package com.android.systemui.statusbar.notification.row.dagger;

import com.android.systemui.statusbar.NotificationShelf;
import com.android.systemui.statusbar.NotificationShelfController;
import com.android.systemui.statusbar.notification.row.ActivatableNotificationView;

import dagger.Binds;
import dagger.BindsInstance;
import dagger.Module;
import dagger.Subcomponent;

/**
 * Dagger subcomponent for NotificationShelf.
 */
@Subcomponent(modules = {ActivatableNotificationViewModule.class,
        NotificationShelfComponent.NotificationShelfModule.class})
@NotificationRowScope
public interface NotificationShelfComponent {
    /**
     * Builder for {@link NotificationShelfComponent}.
     */
    @Subcomponent.Builder
    interface Builder {
        @BindsInstance
        Builder notificationShelf(NotificationShelf view);
        NotificationShelfComponent build();
    }

    /**
     * Creates a NotificationShelfController.
     */
    @NotificationRowScope
    NotificationShelfController getNotificationShelfController();
    /**
     * Dagger Module that extracts interesting properties from a NotificationShelf.
     */
    @Module
    abstract class NotificationShelfModule {

        /** NotificationShelf is provided as an instance of ActivatableNotificationView. */
        @Binds
        abstract ActivatableNotificationView bindNotificationShelf(NotificationShelf view);
    }
}
