/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline

import android.content.Context
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.statusbar.pipeline.wifi.ui.viewmodel.WifiViewModel
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * A temporary object that collects on [WifiViewModel] flows for debugging purposes.
 *
 * This will eventually get migrated to a view binder that will use the flow outputs to set state on
 * views. For now, this just collects on flows so that the information gets logged.
 */
@SysUISingleton
class ConnectivityInfoProcessor @Inject constructor(
        context: Context,
        // TODO(b/238425913): Don't use the application scope; instead, use the status bar view's
        // scope so we only do work when there's UI that cares about it.
        @Application private val scope: CoroutineScope,
        private val statusBarPipelineFlags: StatusBarPipelineFlags,
        private val wifiViewModelProvider: Provider<WifiViewModel>,
) : CoreStartable(context) {
    override fun start() {
        if (!statusBarPipelineFlags.isNewPipelineBackendEnabled()) {
            return
        }
        // TODO(b/238425913): The view binder should do this instead. For now, do it here so we can
        // see the logs.
        scope.launch {
            wifiViewModelProvider.get().isActivityInVisible.collect { }
        }
    }
}
