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

package com.android.systemui.qs.external.ui.viewmodel

import android.app.IUriGrantsManager
import android.content.Context
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.qs.external.TileData
import com.android.systemui.qs.panels.ui.viewmodel.toUiState
import com.android.systemui.qs.tileimpl.QSTileImpl.DrawableIcon
import com.android.systemui.qs.tileimpl.QSTileImpl.ResourceIcon
import com.android.systemui.res.R
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext

class TileRequestDialogViewModel
@AssistedInject
constructor(
    private val iUriGrantsManager: IUriGrantsManager,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    @Assisted private val dialogContext: Context,
    @Assisted private val tileData: TileData,
) : ExclusiveActivatable() {

    private var _icon by mutableStateOf(defaultIcon)

    private val state: QSTile.State
        get() =
            QSTile.State().apply {
                label = tileData.label
                handlesLongClick = false
                this.icon = _icon
            }

    val uiState by derivedStateOf { state.toUiState(dialogContext.resources) }

    override suspend fun onActivated(): Nothing {
        withContext(backgroundDispatcher) {
            tileData.icon
                ?.loadDrawableCheckingUriGrant(
                    dialogContext,
                    iUriGrantsManager,
                    tileData.callingUid,
                    tileData.packageName,
                )
                ?.run { _icon = DrawableIcon(this) }
        }
        awaitCancellation()
    }

    @AssistedFactory
    interface Factory {
        fun create(dialogContext: Context, tileData: TileData): TileRequestDialogViewModel
    }

    companion object {
        private val defaultIcon: QSTile.Icon = ResourceIcon.get(R.drawable.android)
    }
}
