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

package com.android.systemui.biometrics.ui.controller

import com.android.systemui.biometrics.UdfpsAnimationViewController
import com.android.systemui.biometrics.UdfpsKeyguardView
import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor
import com.android.systemui.dump.DumpManager
import com.android.systemui.keyguard.ui.adapter.UdfpsKeyguardViewControllerAdapter
import com.android.systemui.keyguard.ui.viewmodel.UdfpsKeyguardViewModels
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.phone.SystemUIDialogManager
import kotlinx.coroutines.ExperimentalCoroutinesApi

/** Class that coordinates non-HBM animations during keyguard authentication. */
@ExperimentalCoroutinesApi
open class UdfpsKeyguardViewController(
    val view: UdfpsKeyguardView,
    statusBarStateController: StatusBarStateController,
    primaryBouncerInteractor: PrimaryBouncerInteractor,
    systemUIDialogManager: SystemUIDialogManager,
    dumpManager: DumpManager,
    private val alternateBouncerInteractor: AlternateBouncerInteractor,
    udfpsKeyguardViewModels: UdfpsKeyguardViewModels,
) :
    UdfpsAnimationViewController<UdfpsKeyguardView>(
        view,
        statusBarStateController,
        primaryBouncerInteractor,
        systemUIDialogManager,
        dumpManager,
    ),
    UdfpsKeyguardViewControllerAdapter {
    private val uniqueIdentifier = this.toString()
    override val tag: String
        get() = TAG

    init {
        udfpsKeyguardViewModels.bindViews(view)
    }

    public override fun onViewAttached() {
        super.onViewAttached()
        alternateBouncerInteractor.setAlternateBouncerUIAvailable(true, uniqueIdentifier)
    }

    public override fun onViewDetached() {
        super.onViewDetached()
        alternateBouncerInteractor.setAlternateBouncerUIAvailable(false, uniqueIdentifier)
    }

    override fun shouldPauseAuth(): Boolean {
        return !view.isVisible()
    }

    override fun listenForTouchesOutsideView(): Boolean {
        return true
    }

    companion object {
        private const val TAG = "UdfpsKeyguardViewController"
    }
}
