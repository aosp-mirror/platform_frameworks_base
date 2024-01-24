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
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ElementsIntoSet;

import java.util.HashSet;
import java.util.Set;

import javax.inject.Named;

/**
 * Dagger Module containing notification row and view inflation implementations.
 */
@Module
public abstract class NotificationRowModule {
    public static final String NOTIF_REMOTEVIEWS_FACTORIES =
            "notif_remoteviews_factories";

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

    /** Provides view factories to be inflated in notification content. */
    @Provides
    @ElementsIntoSet
    @Named(NOTIF_REMOTEVIEWS_FACTORIES)
    static Set<NotifRemoteViewsFactory> provideNotifRemoteViewsFactories(
            FeatureFlags featureFlags,
            PrecomputedTextViewFactory precomputedTextViewFactory,
            BigPictureLayoutInflaterFactory bigPictureLayoutInflaterFactory,
            CallLayoutSetDataAsyncFactory callLayoutSetDataAsyncFactory
    ) {
        final Set<NotifRemoteViewsFactory> replacementFactories = new HashSet<>();
        replacementFactories.add(precomputedTextViewFactory);
        if (featureFlags.isEnabled(Flags.BIGPICTURE_NOTIFICATION_LAZY_LOADING)) {
            replacementFactories.add(bigPictureLayoutInflaterFactory);
        }
        if (featureFlags.isEnabled(Flags.CALL_LAYOUT_ASYNC_SET_DATA)) {
            replacementFactories.add(callLayoutSetDataAsyncFactory);
        }
        return replacementFactories;
    }
}
