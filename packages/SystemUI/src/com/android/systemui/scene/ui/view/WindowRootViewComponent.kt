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

package com.android.systemui.scene.ui.view

import dagger.Subcomponent

/**
 * A component providing access to [WindowRootView].
 *
 * Injecting [WindowRootView] directly into controllers can lead to crash loops. This is because
 * [WindowRootView] contains [NotificationStackScrollLayout] and that view accesses [Dependency.get]
 * in its constructor. If [Dependency.get] is not set up when your controller is constructed (which
 * is possible because there are no guarantees from Dagger about the order of construction), then
 * trying to inflate [NotificationStackScrollLayout] will crash. This component provides a way to
 * fetch [WindowRootView] after the full Dagger graph is set up, which ensures that the inflation
 * won't fail.
 *
 * This component is intended to be *temporary* and *only used from [CentralSurfacesImpl]*. Once
 * [Dependency.get] is removed from [NotificationStackScrollLayout], we should re-attempt injecting
 * [WindowRootView] directly into its controller [NotificationShadeWindowController].
 */
@Subcomponent
interface WindowRootViewComponent {

    @Subcomponent.Factory
    interface Factory {
        fun create(): WindowRootViewComponent
    }

    /** Fetches the root view of the main SysUI window. */
    fun getWindowRootView(): WindowRootView
}
