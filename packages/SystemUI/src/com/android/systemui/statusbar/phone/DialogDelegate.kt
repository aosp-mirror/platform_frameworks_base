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

package com.android.systemui.statusbar.phone

import android.app.Dialog
import android.content.res.Configuration
import android.os.Bundle
import android.view.ViewRootImpl

/**
 * A delegate class that should be implemented in place of subclassing [Dialog].
 *
 * To use with [SystemUIDialog], implement this interface and then pass an instance of your
 * implementation to [SystemUIDialog.Factory.create].
 */
interface DialogDelegate<T : Dialog> {
    /** Called before [Dialog.onCreate] is called. */
    fun beforeCreate(dialog: T, savedInstanceState: Bundle?) {}

    /** Called after [Dialog.onCreate] is called. */
    fun onCreate(dialog: T, savedInstanceState: Bundle?) {}

    /** Called after [Dialog.onStart] is called. */
    fun onStart(dialog: T) {}

    /** Called after [Dialog.onStop] is called. */
    fun onStop(dialog: T) {}

    /** Called after [Dialog.onWindowFocusChanged] is called. */
    fun onWindowFocusChanged(dialog: T, hasFocus: Boolean) {}

    /** Called as part of [ViewRootImpl.ConfigChangedCallback.onConfigurationChanged]. */
    fun onConfigurationChanged(dialog: T, configuration: Configuration) {}

    fun getWidth(dialog: T): Int = SystemUIDialog.getDefaultDialogWidth(dialog)

    fun getHeight(dialog: T): Int = SystemUIDialog.getDefaultDialogHeight()
}
