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

package com.android.systemui.fold.ui.composable

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.WindowInfoTracker
import com.android.systemui.fold.ui.helper.FoldPosture
import com.android.systemui.fold.ui.helper.foldPostureInternal

/** Returns the [FoldPosture] of the device currently. */
@Composable
fun foldPosture(): State<FoldPosture> {
    val context = LocalContext.current
    val infoTracker = remember(context) { WindowInfoTracker.getOrCreate(context) }
    val layoutInfo by
        infoTracker.windowLayoutInfo(context).collectAsStateWithLifecycle(initialValue = null)

    return produceState<FoldPosture>(
        initialValue = FoldPosture.Folded,
        key1 = layoutInfo,
    ) {
        value = foldPostureInternal(layoutInfo)
    }
}
