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

package com.android.systemui.bouncer.domain.interactor

import android.util.Log
import com.android.systemui.CoreStartable
import com.android.systemui.bouncer.data.repository.BouncerMessageRepository
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val TAG = BouncerMessageAuditLogger::class.simpleName!!

/** Logger that echoes bouncer messages state to logcat in debuggable builds. */
@SysUISingleton
class BouncerMessageAuditLogger
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    private val repository: BouncerMessageRepository,
) : CoreStartable {
    override fun start() {
        scope.launch { repository.bouncerMessage.collect { Log.d(TAG, "bouncerMessage: $it") } }
    }
}
