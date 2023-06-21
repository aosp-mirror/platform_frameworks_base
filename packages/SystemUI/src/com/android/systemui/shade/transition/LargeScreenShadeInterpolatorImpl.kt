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

package com.android.systemui.shade.transition

import android.content.Context
import android.content.res.Configuration
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.LargeScreenUtils
import javax.inject.Inject

/** Interpolator responsible for the shade when on large screens. */
@SysUISingleton
internal class LargeScreenShadeInterpolatorImpl
@Inject
internal constructor(
    configurationController: ConfigurationController,
    private val context: Context,
    private val splitShadeInterpolator: SplitShadeInterpolator,
    private val portraitShadeInterpolator: LargeScreenPortraitShadeInterpolator,
) : LargeScreenShadeInterpolator {

    private var inSplitShade = false

    init {
        configurationController.addCallback(
            object : ConfigurationController.ConfigurationListener {
                override fun onConfigChanged(newConfig: Configuration?) {
                    updateResources()
                }
            }
        )
        updateResources()
    }

    private fun updateResources() {
        inSplitShade = LargeScreenUtils.shouldUseSplitNotificationShade(context.resources)
    }

    private val impl: LargeScreenShadeInterpolator
        get() =
            if (inSplitShade) {
                splitShadeInterpolator
            } else {
                portraitShadeInterpolator
            }

    override fun getBehindScrimAlpha(fraction: Float) = impl.getBehindScrimAlpha(fraction)

    override fun getNotificationScrimAlpha(fraction: Float) =
        impl.getNotificationScrimAlpha(fraction)

    override fun getNotificationContentAlpha(fraction: Float) =
        impl.getNotificationContentAlpha(fraction)

    override fun getNotificationFooterAlpha(fraction: Float) =
        impl.getNotificationFooterAlpha(fraction)

    override fun getQsAlpha(fraction: Float) = impl.getQsAlpha(fraction)
}
