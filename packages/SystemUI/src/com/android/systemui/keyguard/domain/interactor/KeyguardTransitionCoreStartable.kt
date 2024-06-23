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
 * limitations under the License
 */
package com.android.systemui.keyguard.domain.interactor

import android.util.Log
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import java.util.Set
import javax.inject.Inject

@SysUISingleton
class KeyguardTransitionCoreStartable
@Inject
constructor(
    private val interactors: Set<TransitionInteractor>,
    private val auditLogger: KeyguardTransitionAuditLogger,
) : CoreStartable {

    override fun start() {
        // By listing the interactors in a when, the compiler will help enforce all classes
        // extending the sealed class [TransitionInteractor] will be initialized.
        interactors.forEach {
            // `when` needs to be an expression in order for the compiler to enforce it being
            // exhaustive
            val ret =
                when (it) {
                    is FromPrimaryBouncerTransitionInteractor -> Log.d(TAG, "Started $it")
                    is FromAodTransitionInteractor -> Log.d(TAG, "Started $it")
                    is FromGoneTransitionInteractor -> Log.d(TAG, "Started $it")
                    is FromLockscreenTransitionInteractor -> Log.d(TAG, "Started $it")
                    is FromDreamingTransitionInteractor -> Log.d(TAG, "Started $it")
                    is FromGlanceableHubTransitionInteractor -> Log.d(TAG, "Started $it")
                    is FromOccludedTransitionInteractor -> Log.d(TAG, "Started $it")
                    is FromDozingTransitionInteractor -> Log.d(TAG, "Started $it")
                    is FromAlternateBouncerTransitionInteractor -> Log.d(TAG, "Started $it")
                    is FromDreamingLockscreenHostedTransitionInteractor -> Log.d(TAG, "Started $it")
                }
            it.start()
        }
        auditLogger.start()
    }

    companion object {
        private const val TAG = "KeyguardTransitionCoreStartable"
    }
}
