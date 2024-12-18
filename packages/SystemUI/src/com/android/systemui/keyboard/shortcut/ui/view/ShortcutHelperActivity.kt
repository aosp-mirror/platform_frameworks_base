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

package com.android.systemui.keyboard.shortcut.ui.view

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.android.compose.theme.PlatformTheme
import com.android.systemui.keyboard.shortcut.ui.composable.ShortcutHelper
import com.android.systemui.keyboard.shortcut.ui.composable.hasCompactWindowSize
import com.android.systemui.keyboard.shortcut.ui.viewmodel.ShortcutHelperViewModel
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * Activity that hosts the new version of the keyboard shortcut helper. It will be used both for
 * small and large screen devices.
 */
class ShortcutHelperActivity
@Inject
constructor(private val userTracker: UserTracker, private val viewModel: ShortcutHelperViewModel) :
    ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        setupEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { Content() }
        observeFinishRequired()
        viewModel.onViewOpened()
    }

    @Composable
    private fun Content() {
        CompositionLocalProvider(LocalContext provides userTracker.userContext) {
            PlatformTheme { BottomSheet { finish() } }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun BottomSheet(onDismiss: () -> Unit) {
        ModalBottomSheet(
            onDismissRequest = { onDismiss() },
            modifier =
                Modifier.width(getWidth()).padding(top = getTopPadding()).onKeyEvent {
                    if (it.key == Key.Escape) {
                        onDismiss()
                        true
                    } else false
                },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            dragHandle = { DragHandle() },
        ) {
            val shortcutsUiState by viewModel.shortcutsUiState.collectAsStateWithLifecycle()
            ShortcutHelper(
                shortcutsUiState = shortcutsUiState,
                onKeyboardSettingsClicked = ::onKeyboardSettingsClicked,
                onSearchQueryChanged = { viewModel.onSearchQueryChanged(it) },
            )
        }
    }

    @Composable
    fun DragHandle() {
        val dragHandleContentDescription =
            stringResource(id = R.string.shortcut_helper_content_description_drag_handle)
        Surface(
            modifier =
                Modifier.padding(top = 16.dp, bottom = 6.dp).semantics {
                    contentDescription = dragHandleContentDescription
                },
            color = MaterialTheme.colorScheme.outlineVariant,
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Box(Modifier.size(width = 32.dp, height = 4.dp))
        }
    }

    private fun onKeyboardSettingsClicked() {
        try {
            startActivityAsUser(
                Intent(Settings.ACTION_HARD_KEYBOARD_SETTINGS),
                userTracker.userHandle,
            )
        } catch (e: ActivityNotFoundException) {
            // From the Settings docs: In some cases, a matching Activity may not exist, so ensure
            // you safeguard against this.
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            viewModel.onViewClosed()
        }
    }

    private fun observeFinishRequired() {
        lifecycleScope.launch {
            viewModel.shouldShow.flowWithLifecycle(lifecycle).collect { shouldShow ->
                if (!shouldShow) {
                    finish()
                }
            }
        }
    }

    private fun setupEdgeToEdge() {
        // Draw behind system bars
        window.setDecorFitsSystemWindows(false)
    }

    @Composable
    private fun getTopPadding(): Dp {
        return if (hasCompactWindowSize()) DefaultTopPadding else LargeScreenTopPadding
    }

    @Composable
    private fun getWidth(): Dp {
        return if (hasCompactWindowSize()) {
            DefaultWidth
        } else
            when (LocalConfiguration.current.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> LargeScreenWidthLandscape
                else -> LargeScreenWidthPortrait
            }
    }

    companion object {
        private val DefaultTopPadding = 64.dp
        private val LargeScreenTopPadding = 72.dp
        private val DefaultWidth = 412.dp
        private val LargeScreenWidthPortrait = 704.dp
        private val LargeScreenWidthLandscape = 864.dp
    }
}
