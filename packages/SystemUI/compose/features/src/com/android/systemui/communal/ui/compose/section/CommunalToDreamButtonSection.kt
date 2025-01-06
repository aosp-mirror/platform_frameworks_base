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

package com.android.systemui.communal.ui.compose.section

import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.PlatformIconButton
import com.android.systemui.communal.domain.interactor.CommunalSettingsInteractor
import com.android.systemui.communal.ui.viewmodel.CommunalToDreamButtonViewModel
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.res.R
import javax.inject.Inject

class CommunalToDreamButtonSection
@Inject
constructor(
    private val communalSettingsInteractor: CommunalSettingsInteractor,
    private val viewModelFactory: CommunalToDreamButtonViewModel.Factory,
) {
    @Composable
    fun Button() {
        if (!communalSettingsInteractor.isV2FlagEnabled()) {
            return
        }

        val viewModel =
            rememberViewModel("CommunalToDreamButtonSection") { viewModelFactory.create() }
        val shouldShowDreamButtonOnHub by
            viewModel.shouldShowDreamButtonOnHub.collectAsStateWithLifecycle(false)

        if (!shouldShowDreamButtonOnHub) {
            return
        }

        PlatformIconButton(
            onClick = { viewModel.onShowDreamButtonTap() },
            iconResource = R.drawable.ic_screensaver_auto,
            contentDescription =
                stringResource(R.string.accessibility_glanceable_hub_to_dream_button),
            colors =
                IconButtonDefaults.filledIconButtonColors(
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
        )
    }
}
