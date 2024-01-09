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

package com.android.systemui.statusbar.pipeline.icons.shared.model

/**
 * A BindableIcon describes a status bar icon that can be housed in the [ModernStatusBarView]
 * created by [initializer]. They can be registered statically for [BindableIconsRepositoryImpl].
 *
 * Typical usage would be to create an (@SysUISingleton) adapter class that implements the
 * interface. For example:
 * ```
 * @SysuUISingleton
 * class MyBindableIconAdapter
 * @Inject constructor(
 *     // deps
 *     val viewModel: MyViewModel
 * ) : BindableIcon {
 *     override val slot = "icon_slot_name"
 *
 *     override val initializer = ModernStatusBarViewCreator() {
 *         SingleBindableStatusBarIconView.createView(context).also { iconView ->
 *             MyIconViewBinder.bind(iconView, viewModel)
 *         }
 *     }
 *
 *     override fun shouldBind() = Flags.myFlag()
 * }
 * ```
 *
 * By defining this adapter (and injecting it into the repository), we get our icon registered with
 * the legacy StatusBarIconController while proxying all updates to the view binder that is created
 * elsewhere.
 *
 * Note that the initializer block defines a closure that can pull in the viewModel dependency
 * without us having to store it directly in the icon controller.
 */
interface BindableIcon {
    val slot: String
    val initializer: ModernStatusBarViewCreator
    val shouldBindIcon: Boolean
}
