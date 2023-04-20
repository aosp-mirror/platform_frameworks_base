/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection.provider

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.notification.VisibilityLocationProvider
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import javax.inject.Inject

/**
 * An injectable component which delegates the visibility location computation to a delegate which
 * can be initialized after the initial injection, generally because it's provided by a view.
 */
@SysUISingleton
class VisibilityLocationProviderDelegator @Inject constructor() : VisibilityLocationProvider {
    private var delegate: VisibilityLocationProvider? = null

    fun setDelegate(provider: VisibilityLocationProvider) {
        delegate = provider
    }

    override fun isInVisibleLocation(entry: NotificationEntry): Boolean =
        requireNotNull(this.delegate) { "delegate not initialized" }.isInVisibleLocation(entry)
}
