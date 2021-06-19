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

package com.android.systemui.statusbar.notification.row;

import com.android.systemui.dagger.SysUISingleton;

import dagger.Binds;
import dagger.Module;

/**
 * Dagger Module containing notification row and view inflation implementations.
 */
@Module
public abstract class NotificationRowModule {
    /**
     * Provides notification row content binder instance.
     */
    @Binds
    @SysUISingleton
    public abstract NotificationRowContentBinder provideNotificationRowContentBinder(
            NotificationContentInflater contentBinderImpl);

    /**
     * Provides notification remote view cache instance.
     */
    @Binds
    @SysUISingleton
    public abstract NotifRemoteViewCache provideNotifRemoteViewCache(
            NotifRemoteViewCacheImpl cacheImpl);
}
