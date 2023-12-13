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

package com.android.systemui.statusbar.pipeline.shared.ui.binder

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.coroutineScope
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.statusbar.pipeline.shared.ui.model.InternetTileModel
import java.util.function.Consumer
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Binds an [InternetTileModel] flow to a consumer for the internet tile to apply to its qs state
 */
object InternetTileBinder {
    fun bind(
        lifecycle: Lifecycle,
        tileModelFlow: StateFlow<InternetTileModel>,
        consumer: Consumer<InternetTileModel>
    ) {
        lifecycle.coroutineScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                tileModelFlow.collect { consumer.accept(it) }
            }
        }
    }
}
