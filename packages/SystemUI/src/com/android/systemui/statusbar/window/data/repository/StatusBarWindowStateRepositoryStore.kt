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

package com.android.systemui.statusbar.window.data.repository

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.DisplayId
import java.lang.ref.WeakReference
import javax.inject.Inject

/**
 * Singleton class to create instances of [StatusBarWindowStatePerDisplayRepository] for a specific
 * display.
 *
 * Repository instances for a specific display should be cached so that if multiple classes request
 * a repository for the same display ID, we only create the repository once.
 */
interface StatusBarWindowStateRepositoryStore {
    val defaultDisplay: StatusBarWindowStatePerDisplayRepository

    fun forDisplay(displayId: Int): StatusBarWindowStatePerDisplayRepository
}

@SysUISingleton
class StatusBarWindowStateRepositoryStoreImpl
@Inject
constructor(
    @DisplayId private val displayId: Int,
    private val factory: StatusBarWindowStatePerDisplayRepositoryFactory,
) : StatusBarWindowStateRepositoryStore {
    // Use WeakReferences to store the repositories so that the repositories are kept around so long
    // as some UI holds a reference to them, but the repositories are cleaned up once no UI is using
    // them anymore.
    // See Change-Id Ib490062208506d646add2fe7e5e5d4df5fb3e66e for similar behavior in
    // MobileConnectionsRepositoryImpl.
    private val repositoryCache =
        mutableMapOf<Int, WeakReference<StatusBarWindowStatePerDisplayRepository>>()

    override val defaultDisplay = factory.create(displayId)

    override fun forDisplay(displayId: Int): StatusBarWindowStatePerDisplayRepository {
        synchronized(repositoryCache) {
            return repositoryCache[displayId]?.get()
                ?: factory.create(displayId).also { repositoryCache[displayId] = WeakReference(it) }
        }
    }
}
