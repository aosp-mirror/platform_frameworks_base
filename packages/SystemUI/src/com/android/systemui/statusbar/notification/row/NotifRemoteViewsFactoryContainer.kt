/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.row

import android.widget.flags.Flags.notifLinearlayoutOptimized
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.statusbar.notification.shared.NotificationViewFlipperPausing
import javax.inject.Inject
import javax.inject.Provider

interface NotifRemoteViewsFactoryContainer {
    val factories: Set<NotifRemoteViewsFactory>
}

class NotifRemoteViewsFactoryContainerImpl
@Inject
constructor(
    featureFlags: FeatureFlags,
    precomputedTextViewFactory: PrecomputedTextViewFactory,
    bigPictureLayoutInflaterFactory: BigPictureLayoutInflaterFactory,
    optimizedLinearLayoutFactory: NotificationOptimizedLinearLayoutFactory,
    notificationViewFlipperFactory: Provider<NotificationViewFlipperFactory>,
) : NotifRemoteViewsFactoryContainer {
    override val factories: Set<NotifRemoteViewsFactory> = buildSet {
        add(precomputedTextViewFactory)
        if (featureFlags.isEnabled(Flags.BIGPICTURE_NOTIFICATION_LAZY_LOADING)) {
            add(bigPictureLayoutInflaterFactory)
        }
        if (notifLinearlayoutOptimized()) {
            add(optimizedLinearLayoutFactory)
        }
        if (NotificationViewFlipperPausing.isEnabled) {
            add(notificationViewFlipperFactory.get())
        }
    }
}
