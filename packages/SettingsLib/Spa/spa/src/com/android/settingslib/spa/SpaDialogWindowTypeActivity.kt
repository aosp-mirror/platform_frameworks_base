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

package com.android.settingslib.spa

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import com.android.settingslib.spa.framework.common.LogCategory
import com.android.settingslib.spa.framework.common.SpaEnvironmentFactory
import com.android.settingslib.spa.framework.theme.SettingsTheme

/**
 * Dialog activity when the dialog window type need to be override.
 *
 * Please use [SpaBaseDialogActivity] for all other use cases.
 */
abstract class SpaDialogWindowTypeActivity : ComponentActivity() {
    private val spaEnvironment get() = SpaEnvironmentFactory.instance
    private var dialog: AlertDialogWithType? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        spaEnvironment.logger.message(TAG, "onCreate", category = LogCategory.FRAMEWORK)

        dialog = AlertDialogWithType(this).apply { show() }
    }

    override fun finish() {
        dialog?.dismiss()
        super.finish()
    }

    abstract fun getDialogWindowType(): Int?

    @Composable
    abstract fun Content()

    inner class AlertDialogWithType(context: Context) :
        AlertDialog(context, R.style.Theme_SpaLib_Dialog) {

        init {
            setView(ComposeView(context).apply {
                setContent {
                    SettingsTheme {
                        this@SpaDialogWindowTypeActivity.Content()
                    }
                }
            })
            setOnDismissListener { finish() }
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            getDialogWindowType()?.let { window?.setType(it) }
            super.onCreate(savedInstanceState)
        }
    }

    companion object {
        private const val TAG = "SpaBaseDialogActivity"
    }
}
