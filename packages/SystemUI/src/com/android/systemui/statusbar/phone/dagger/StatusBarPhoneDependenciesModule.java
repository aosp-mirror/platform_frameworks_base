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

package com.android.systemui.statusbar.phone.dagger;

import com.android.systemui.statusbar.notification.row.RowContentBindStage;
import com.android.systemui.statusbar.phone.NotificationGroupAlertTransferHelper;
import com.android.systemui.statusbar.phone.StatusBar;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

/**
 * This module provides instances needed to construct {@link StatusBar}. These are moved to this
 * separate from {@link StatusBarPhoneModule} module so that components that wish to build their own
 * version of StatusBar can include just dependencies, without injecting StatusBar itself.
 */
@Module
public interface StatusBarPhoneDependenciesModule {

    /** */
    @Singleton
    @Provides
    static NotificationGroupAlertTransferHelper provideNotificationGroupAlertTransferHelper(
            RowContentBindStage bindStage) {
        return new NotificationGroupAlertTransferHelper(bindStage);
    }
}
