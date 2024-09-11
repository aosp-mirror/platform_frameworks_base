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

package com.android.systemui.people

import android.appwidget.AppWidgetManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.android.compose.theme.PlatformTheme
import com.android.systemui.people.ui.compose.PeopleScreen
import com.android.systemui.people.ui.viewmodel.PeopleViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

/** People Tile Widget configuration activity that shows the user their conversation tiles. */
class PeopleSpaceActivity
@Inject
constructor(private val viewModelFactory: PeopleViewModel.Factory) : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)

        // Update the widget ID coming from the intent.
        val viewModel = ViewModelProvider(this, viewModelFactory)[PeopleViewModel::class.java]
        val widgetId =
            intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID,
            )
        viewModel.onWidgetIdChanged(widgetId)

        // Make sure to refresh the tiles/conversations when the lifecycle is resumed, so that it
        // updates them when going back to the Activity after leaving it.
        // Note that we do this here instead of inside an effect in the PeopleScreen() composable
        // because otherwise onTileRefreshRequested() will be called after the first composition,
        // which will trigger a new recomposition and redraw, affecting the GPU memory (see
        // b/276871425).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) { viewModel.onTileRefreshRequested() }
        }

        // Set the content of the activity, using either the View or Compose implementation.
        setContent { PlatformTheme { PeopleScreen(viewModel, onResult = { finishActivity(it) }) } }
    }

    private fun finishActivity(result: PeopleViewModel.Result) {
        if (result is PeopleViewModel.Result.Success) {
            if (DEBUG) Log.d(TAG, "Widget added!")
            setResult(RESULT_OK, result.data)
        } else {
            if (DEBUG) Log.d(TAG, "Activity dismissed with no widgets added!")
            setResult(RESULT_CANCELED)
        }

        finish()
    }

    companion object {
        private const val TAG = "PeopleSpaceActivity"
        private const val DEBUG = PeopleSpaceUtils.DEBUG
    }
}
