/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.systemui.statusbar.notification.data.repository

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.notification.collection.ListEntry
import dagger.Binds
import dagger.Module
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Repository of notifications in the notification stack.
 *
 * This repository serves as the boundary between the
 * [com.android.systemui.statusbar.notification.collection.NotifPipeline] and the modern
 * notifications presentation codebase.
 */
interface NotificationStackRepository {
    /**
     * Notifications actively presented to the user in the notification stack.
     *
     * @see com.android.systemui.statusbar.notification.collection.listbuilder.OnAfterRenderListListener
     */
    val renderedEntries: Flow<List<ListEntry>>
}

/**
 * A mutable implementation of [NotificationStackRepository]. Like other "mutable" objects, the
 * mutable type should only be exposed where necessary; most consumers should only have access to it
 * from behind the immutable [NotificationStackRepository] interface.
 */
@SysUISingleton
class MutableNotificationStackRepository @Inject constructor() : NotificationStackRepository {
    override val renderedEntries = MutableStateFlow(emptyList<ListEntry>())
}

@Module
interface NotificationStackRepositoryModule {
    @Binds fun bindImpl(impl: MutableNotificationStackRepository): NotificationStackRepository
}
