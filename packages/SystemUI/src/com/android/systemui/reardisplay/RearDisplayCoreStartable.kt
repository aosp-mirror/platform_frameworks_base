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

package com.android.systemui.reardisplay

import android.content.Context
import android.hardware.devicestate.DeviceStateManager
import android.hardware.devicestate.feature.flags.Flags
import androidx.annotation.VisibleForTesting
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.display.domain.interactor.RearDisplayStateInteractor
import com.android.systemui.statusbar.phone.SystemUIDialog
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map

/**
 * Provides a {@link com.android.systemui.statusbar.phone.SystemUIDialog} to be shown on the inner
 * display when the device enters Rear Display Mode, containing an UI affordance to let the user
 * know that the main content has moved to the outer display, as well as an UI affordance to cancel
 * the Rear Display Mode.
 */
@SysUISingleton
class RearDisplayCoreStartable
@Inject
internal constructor(
    private val context: Context,
    private val deviceStateManager: DeviceStateManager,
    private val rearDisplayStateInteractor: RearDisplayStateInteractor,
    private val rearDisplayInnerDialogDelegateFactory: RearDisplayInnerDialogDelegate.Factory,
    @Application private val scope: CoroutineScope,
) : CoreStartable, AutoCloseable {

    companion object {
        private const val TAG: String = "RearDisplayCoreStartable"
    }

    @VisibleForTesting var stateChangeListener: Job? = null

    override fun close() {
        stateChangeListener?.cancel()
    }

    override fun start() {
        if (Flags.deviceStateRdmV2()) {
            var dialog: SystemUIDialog? = null

            stateChangeListener =
                rearDisplayStateInteractor.state
                    .map {
                        when (it) {
                            is RearDisplayStateInteractor.State.Enabled -> {
                                val rearDisplayContext =
                                    context.createDisplayContext(it.innerDisplay)
                                val delegate =
                                    rearDisplayInnerDialogDelegateFactory.create(
                                        rearDisplayContext,
                                        deviceStateManager::cancelStateRequest,
                                    )
                                dialog = delegate.createDialog().apply { show() }
                            }

                            is RearDisplayStateInteractor.State.Disabled -> {
                                dialog?.dismiss()
                                dialog = null
                            }
                        }
                    }
                    .launchIn(scope)
        }
    }
}
