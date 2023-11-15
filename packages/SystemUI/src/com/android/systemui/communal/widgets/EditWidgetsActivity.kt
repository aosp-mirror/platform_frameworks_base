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

package com.android.systemui.communal.widgets

import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.res.R
import javax.inject.Inject

/** An Activity for editing the widgets that appear in hub mode. */
class EditWidgetsActivity @Inject constructor(private val communalInteractor: CommunalInteractor) :
    ComponentActivity() {
    companion object {
        /**
         * Intent extra name for the {@link AppWidgetProviderInfo} of a widget to add to hub mode.
         */
        const val ADD_WIDGET_INFO = "add_widget_info"
        private const val TAG = "EditWidgetsActivity"
    }

    private val addWidgetActivityLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(StartActivityForResult()) { result ->
            when (result.resultCode) {
                RESULT_OK -> {
                    result.data
                        ?.let {
                            it.getParcelableExtra(
                                ADD_WIDGET_INFO,
                                AppWidgetProviderInfo::class.java
                            )
                        }
                        ?.let { communalInteractor.addWidget(it.provider, 0) }
                        ?: run { Log.w(TAG, "No AppWidgetProviderInfo found in result.") }
                }
                else ->
                    Log.w(
                        TAG,
                        "Failed to receive result from widget picker, code=${result.resultCode}"
                    )
            }
            this@EditWidgetsActivity.finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setShowWhenLocked(true)
        setContentView(R.layout.edit_widgets)

        val addWidgetsButton = findViewById<View>(R.id.add_widget)
        addWidgetsButton?.setOnClickListener({
            addWidgetActivityLauncher.launch(
                Intent(applicationContext, WidgetPickerActivity::class.java)
            )
        })
    }
}
