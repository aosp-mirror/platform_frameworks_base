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

package com.android.systemui.qs.footer.data.repository

import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.qs.FgsManagerController
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

interface ForegroundServicesRepository {
    /**
     * The number of packages with a service running in the foreground.
     *
     * Note that this will be equal to 0 if [FgsManagerController.isAvailable] is false.
     */
    val foregroundServicesCount: Flow<Int>

    /**
     * Whether there were new changes to the foreground packages since a dialog was last shown.
     *
     * Note that this will be equal to `false` if [FgsManagerController.showFooterDot] is false.
     */
    val hasNewChanges: Flow<Boolean>
}

@SysUISingleton
class ForegroundServicesRepositoryImpl
@Inject
constructor(
    fgsManagerController: FgsManagerController,
) : ForegroundServicesRepository {
    override val foregroundServicesCount: Flow<Int> =
        fgsManagerController.isAvailable
            .flatMapLatest { isAvailable ->
                if (!isAvailable) {
                    return@flatMapLatest flowOf(0)
                }

                conflatedCallbackFlow {
                    fun updateState(numberOfPackages: Int) {
                        trySendWithFailureLogging(numberOfPackages, TAG)
                    }

                    val listener =
                        object : FgsManagerController.OnNumberOfPackagesChangedListener {
                            override fun onNumberOfPackagesChanged(numberOfPackages: Int) {
                                updateState(numberOfPackages)
                            }
                        }

                    fgsManagerController.addOnNumberOfPackagesChangedListener(listener)
                    updateState(fgsManagerController.numRunningPackages)
                    awaitClose {
                        fgsManagerController.removeOnNumberOfPackagesChangedListener(listener)
                    }
                }
            }
            .distinctUntilChanged()

    override val hasNewChanges: Flow<Boolean> =
        fgsManagerController.showFooterDot.flatMapLatest { showFooterDot ->
            if (!showFooterDot) {
                return@flatMapLatest flowOf(false)
            }

            // A flow that emits whenever the FGS dialog is dismissed.
            val dialogDismissedEvents = conflatedCallbackFlow {
                fun updateState() {
                    trySendWithFailureLogging(
                        Unit,
                        TAG,
                    )
                }

                val listener =
                    object : FgsManagerController.OnDialogDismissedListener {
                        override fun onDialogDismissed() {
                            updateState()
                        }
                    }

                fgsManagerController.addOnDialogDismissedListener(listener)
                awaitClose { fgsManagerController.removeOnDialogDismissedListener(listener) }
            }

            // Query [fgsManagerController.newChangesSinceDialogWasDismissed] everytime the dialog
            // is dismissed or when [foregroundServices] is changing.
            merge(
                    foregroundServicesCount,
                    dialogDismissedEvents,
                )
                .map { fgsManagerController.newChangesSinceDialogWasDismissed }
                .distinctUntilChanged()
        }

    companion object {
        private const val TAG = "ForegroundServicesRepositoryImpl"
    }
}
