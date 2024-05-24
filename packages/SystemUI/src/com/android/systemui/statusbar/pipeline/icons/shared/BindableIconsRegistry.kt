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

package com.android.systemui.statusbar.pipeline.icons.shared

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.pipeline.icons.shared.model.BindableIcon
import com.android.systemui.statusbar.pipeline.satellite.ui.DeviceBasedSatelliteBindableIcon
import javax.inject.Inject

/**
 * Bindable status bar icons represent icon descriptions which can be registered with
 * StatusBarIconController and can also create their own bindings. A bound icon is responsible for
 * its own updates via the [repeatWhenAttached] view lifecycle utility. Thus,
 * StatusBarIconController can (and will) ignore any call to setIcon.
 *
 * In other words, these icons are bound once (at controller init) and they will control their
 * visibility on their own (while their overall appearance remains at the discretion of
 * StatusBarIconController, via the ModernStatusBarViewBinding interface).
 */
interface BindableIconsRegistry {
    val bindableIcons: List<BindableIcon>
}

@SysUISingleton
class BindableIconsRegistryImpl
@Inject
constructor(
    /** Bindables go here */
    oemSatellite: DeviceBasedSatelliteBindableIcon
) : BindableIconsRegistry {
    /**
     * Adding the injected bindables to this list will get them registered with
     * StatusBarIconController
     */
    override val bindableIcons: List<BindableIcon> = listOf(oemSatellite)
}
