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
import android.graphics.Insets
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.updatePadding
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.android.compose.theme.PlatformTheme
import com.android.systemui.keyboard.shortcut.ui.composable.ShortcutHelper
import com.android.systemui.keyboard.shortcut.ui.viewmodel.ShortcutHelperViewModel
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.dpToPx
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetBehavior.BottomSheetCallback
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_HIDDEN
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

    private val bottomSheetContainer
        get() = requireViewById<View>(R.id.shortcut_helper_sheet_container)

    private val bottomSheet
        get() = requireViewById<View>(R.id.shortcut_helper_sheet)

    private val bottomSheetBehavior
        get() = BottomSheetBehavior.from(bottomSheet)

    override fun onCreate(savedInstanceState: Bundle?) {
        setupEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_keyboard_shortcut_helper)
        setUpWidth()
        expandBottomSheet()
        setUpInsets()
        setUpPredictiveBack()
        setUpSheetDismissListener()
        setUpDismissOnTouchOutside()
        setUpComposeView()
        observeFinishRequired()
        viewModel.onViewOpened()
    }

    private fun setUpWidth() {
        // we override this because when maxWidth isn't specified, material imposes a max width
        // constraint on bottom sheets on larger screens which is smaller than our desired width.
        bottomSheetBehavior.maxWidth =
            resources.getDimension(R.dimen.shortcut_helper_width).dpToPx(resources).toInt()
    }

    private fun setUpComposeView() {
        requireViewById<ComposeView>(R.id.shortcut_helper_compose_container).apply {
            setContent {
                CompositionLocalProvider(LocalContext provides userTracker.userContext) {
                    PlatformTheme {
                        val shortcutsUiState by
                            viewModel.shortcutsUiState.collectAsStateWithLifecycle()
                        ShortcutHelper(
                            shortcutsUiState = shortcutsUiState,
                            onKeyboardSettingsClicked = ::onKeyboardSettingsClicked,
                            onSearchQueryChanged = { viewModel.onSearchQueryChanged(it) },
                        )
                    }
                }
            }
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

    private fun setUpInsets() {
        bottomSheetContainer.setOnApplyWindowInsetsListener { _, insets ->
            val safeDrawingInsets = insets.safeDrawing
            // Make sure the bottom sheet is not covered by the status bar.
            bottomSheetBehavior.maxHeight =
                windowManager.maximumWindowMetrics.bounds.height() - safeDrawingInsets.top
            // Make sure the contents inside of the bottom sheet are not hidden by system bars, or
            // cutouts.
            bottomSheet.updatePadding(
                left = safeDrawingInsets.left,
                right = safeDrawingInsets.right,
                bottom = safeDrawingInsets.bottom,
            )
            // The bottom sheet has to be expanded only after setting up insets, otherwise there is
            // a bug and it will not use full height.
            expandBottomSheet()

            // Return CONSUMED if you don't want want the window insets to keep passing
            // down to descendant views.
            WindowInsets.CONSUMED
        }
    }

    private fun expandBottomSheet() {
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
        bottomSheetBehavior.skipCollapsed = true
    }

    private fun setUpPredictiveBack() {
        val onBackPressedCallback =
            object : OnBackPressedCallback(/* enabled= */ true) {
                override fun handleOnBackStarted(backEvent: BackEventCompat) {
                    bottomSheetBehavior.startBackProgress(backEvent)
                }

                override fun handleOnBackProgressed(backEvent: BackEventCompat) {
                    bottomSheetBehavior.updateBackProgress(backEvent)
                }

                override fun handleOnBackPressed() {
                    bottomSheetBehavior.handleBackInvoked()
                }

                override fun handleOnBackCancelled() {
                    bottomSheetBehavior.cancelBackProgress()
                }
            }
        onBackPressedDispatcher.addCallback(
            owner = this,
            onBackPressedCallback = onBackPressedCallback,
        )
    }

    private fun setUpSheetDismissListener() {
        bottomSheetBehavior.addBottomSheetCallback(
            object : BottomSheetCallback() {
                override fun onStateChanged(bottomSheet: View, newState: Int) {
                    if (newState == STATE_HIDDEN) {
                        finish()
                    }
                }

                override fun onSlide(bottomSheet: View, slideOffset: Float) {}
            }
        )
    }

    private fun setUpDismissOnTouchOutside() {
        bottomSheetContainer.setOnClickListener { finish() }
    }
}

private val WindowInsets.safeDrawing
    get() =
        getInsets(WindowInsets.Type.systemBars())
            .union(getInsets(WindowInsets.Type.displayCutout()))

private fun Insets.union(insets: Insets): Insets = Insets.max(this, insets)
