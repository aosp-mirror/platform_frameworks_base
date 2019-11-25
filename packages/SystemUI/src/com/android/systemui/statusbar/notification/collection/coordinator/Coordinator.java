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

package com.android.systemui.statusbar.notification.collection.coordinator;

import com.android.systemui.statusbar.notification.collection.NotifCollection;
import com.android.systemui.statusbar.notification.collection.NotifCollectionListener;
import com.android.systemui.statusbar.notification.collection.NotifLifetimeExtender;
import com.android.systemui.statusbar.notification.collection.init.NewNotifPipeline;
import com.android.systemui.statusbar.notification.collection.listbuilder.NotifListBuilder;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.Pluggable;

/**
 * Interface for registering callbacks to the {@link NewNotifPipeline}.
 *
 * This includes registering:
 *  {@link Pluggable}s to the {@link NotifListBuilder}
 *  {@link NotifCollectionListener}s and {@link NotifLifetimeExtender}s to {@link NotifCollection}
 */
public interface Coordinator {

    /**
     * Called after the NewNotifPipeline is initialized.
     * Coordinators should register their {@link Pluggable}s to the notifListBuilder
     * and their {@link NotifCollectionListener}s and {@link NotifLifetimeExtender}s
     * to the notifCollection in this method.
     */
    void attach(NotifCollection notifCollection, NotifListBuilder notifListBuilder);
}
