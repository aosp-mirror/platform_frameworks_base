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
 *
 */

package com.android.systemui.biometrics.ui.binder

import android.view.View
import com.android.systemui.res.R
import com.android.systemui.keyguard.ui.binder.UdfpsAodFingerprintViewBinder
import com.android.systemui.keyguard.ui.binder.UdfpsBackgroundViewBinder
import com.android.systemui.keyguard.ui.binder.UdfpsFingerprintViewBinder
import com.android.systemui.keyguard.ui.viewmodel.BackgroundViewModel
import com.android.systemui.keyguard.ui.viewmodel.FingerprintViewModel
import com.android.systemui.keyguard.ui.viewmodel.UdfpsAodViewModel
import com.android.systemui.keyguard.ui.viewmodel.UdfpsKeyguardInternalViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi

@ExperimentalCoroutinesApi
object UdfpsKeyguardInternalViewBinder {

    @JvmStatic
    fun bind(
        view: View,
        viewModel: UdfpsKeyguardInternalViewModel,
        aodViewModel: UdfpsAodViewModel,
        fingerprintViewModel: FingerprintViewModel,
        backgroundViewModel: BackgroundViewModel,
    ) {
        view.accessibilityDelegate = viewModel.accessibilityDelegate

        // bind child views
        UdfpsAodFingerprintViewBinder.bind(view.requireViewById(R.id.udfps_aod_fp), aodViewModel)
        UdfpsFingerprintViewBinder.bind(
            view.requireViewById(R.id.udfps_lockscreen_fp),
            fingerprintViewModel
        )
        UdfpsBackgroundViewBinder.bind(
            view.requireViewById(R.id.udfps_keyguard_fp_bg),
            backgroundViewModel
        )
    }
}
