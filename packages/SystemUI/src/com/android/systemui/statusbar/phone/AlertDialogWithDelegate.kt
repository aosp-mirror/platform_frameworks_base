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

import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.ViewRootImpl
import android.view.ViewRootImpl.ConfigChangedCallback
import androidx.annotation.StyleRes

/**
 * Implementation of [AlertDialog] that takes as parameter a [DialogDelegate].
 *
 * Can be used when composition is preferred over inheritance.
 */
class AlertDialogWithDelegate(
    context: Context,
    @StyleRes theme: Int,
    private val delegate: DialogDelegate<AlertDialog>
) : AlertDialog(context, theme), ConfigChangedCallback {

    override fun onCreate(savedInstanceState: Bundle?) {
        delegate.beforeCreate(dialog = this, savedInstanceState)
        super.onCreate(savedInstanceState)
        delegate.onCreate(dialog = this, savedInstanceState)
    }

    override fun onConfigurationChanged(configuration: Configuration) {
        delegate.onConfigurationChanged(dialog = this, configuration)
    }

    override fun onStart() {
        super.onStart()
        ViewRootImpl.addConfigCallback(this)
        delegate.onStart(dialog = this)
    }

    override fun onStop() {
        super.onStop()
        ViewRootImpl.removeConfigCallback(this)
        delegate.onStop(dialog = this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        delegate.onWindowFocusChanged(dialog = this, hasFocus)
    }
}
