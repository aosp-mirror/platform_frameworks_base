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

package com.android.systemui.biometrics

import android.content.res.Resources
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import com.android.systemui.res.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager
import javax.inject.Inject

@SysUISingleton
class UdfpsKeyguardAccessibilityDelegate
@Inject
constructor(
    @Main private val resources: Resources,
    private val keyguardViewManager: StatusBarKeyguardViewManager,
) : View.AccessibilityDelegate() {
    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(host, info)
        val clickAction =
            AccessibilityNodeInfo.AccessibilityAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK.id,
                resources.getString(R.string.accessibility_bouncer)
            )
        info.addAction(clickAction)
    }

    override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
        // when an a11y service is enabled, double tapping on the fingerprint sensor should
        // show the primary bouncer
        return if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK.id) {
            keyguardViewManager.showPrimaryBouncer(/* scrimmed */ true)
            true
        } else super.performAccessibilityAction(host, action, args)
    }
}
