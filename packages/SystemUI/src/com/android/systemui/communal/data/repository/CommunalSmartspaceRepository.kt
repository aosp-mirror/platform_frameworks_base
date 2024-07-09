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

package com.android.systemui.communal.data.repository

import android.app.smartspace.SmartspaceTarget
import android.os.Parcelable
import com.android.systemui.communal.data.model.CommunalSmartspaceTimer
import com.android.systemui.communal.smartspace.CommunalSmartspaceController
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.BcSmartspaceDataPlugin
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart

interface CommunalSmartspaceRepository {
    /** Smartspace timer targets for the communal surface. */
    val timers: Flow<List<CommunalSmartspaceTimer>>
}

@SysUISingleton
class CommunalSmartspaceRepositoryImpl
@Inject
constructor(
    private val communalSmartspaceController: CommunalSmartspaceController,
    @Main private val uiExecutor: Executor,
) : CommunalSmartspaceRepository, BcSmartspaceDataPlugin.SmartspaceTargetListener {

    private val _timers: MutableStateFlow<List<CommunalSmartspaceTimer>> =
        MutableStateFlow(emptyList())
    override val timers: Flow<List<CommunalSmartspaceTimer>> =
        if (!android.app.smartspace.flags.Flags.remoteViews()) emptyFlow()
        else
            _timers
                .onStart {
                    uiExecutor.execute {
                        communalSmartspaceController.addListener(
                            listener = this@CommunalSmartspaceRepositoryImpl
                        )
                    }
                }
                .onCompletion {
                    uiExecutor.execute {
                        communalSmartspaceController.removeListener(
                            listener = this@CommunalSmartspaceRepositoryImpl
                        )
                    }
                }

    override fun onSmartspaceTargetsUpdated(targetsNullable: MutableList<out Parcelable>?) {
        val targets = targetsNullable?.filterIsInstance<SmartspaceTarget>() ?: emptyList()

        _timers.value =
            targets
                .filter { target ->
                    target.featureType == SmartspaceTarget.FEATURE_TIMER &&
                        target.remoteViews != null
                }
                .map { target ->
                    CommunalSmartspaceTimer(
                        smartspaceTargetId = target.smartspaceTargetId,
                        createdTimestampMillis = target.creationTimeMillis,
                        remoteViews = target.remoteViews!!,
                    )
                }
    }
}
