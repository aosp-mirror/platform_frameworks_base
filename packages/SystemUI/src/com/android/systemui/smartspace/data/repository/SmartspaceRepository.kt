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

package com.android.systemui.smartspace.data.repository

import android.app.smartspace.SmartspaceTarget
import android.os.Parcelable
import android.widget.RemoteViews
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.plugins.BcSmartspaceDataPlugin
import com.android.systemui.statusbar.lockscreen.LockscreenSmartspaceController
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart

interface SmartspaceRepository {
    /** Whether [RemoteViews] are passed through smartspace targets. */
    val isSmartspaceRemoteViewsEnabled: Boolean

    /** Smartspace targets for the lockscreen surface. */
    val lockscreenSmartspaceTargets: Flow<List<SmartspaceTarget>>
}

@SysUISingleton
class SmartspaceRepositoryImpl
@Inject
constructor(
    private val lockscreenSmartspaceController: LockscreenSmartspaceController,
) : SmartspaceRepository, BcSmartspaceDataPlugin.SmartspaceTargetListener {

    override val isSmartspaceRemoteViewsEnabled: Boolean
        get() = android.app.smartspace.flags.Flags.remoteViews()

    private val _lockscreenSmartspaceTargets: MutableStateFlow<List<SmartspaceTarget>> =
        MutableStateFlow(emptyList())
    override val lockscreenSmartspaceTargets: Flow<List<SmartspaceTarget>> =
        _lockscreenSmartspaceTargets
            .onStart {
                lockscreenSmartspaceController.addListener(listener = this@SmartspaceRepositoryImpl)
            }
            .onCompletion {
                lockscreenSmartspaceController.removeListener(
                    listener = this@SmartspaceRepositoryImpl
                )
            }

    override fun onSmartspaceTargetsUpdated(targetsNullable: MutableList<out Parcelable>?) {
        targetsNullable?.let { targets ->
            _lockscreenSmartspaceTargets.value = targets.filterIsInstance<SmartspaceTarget>()
        }
            ?: run { _lockscreenSmartspaceTargets.value = emptyList() }
    }
}
