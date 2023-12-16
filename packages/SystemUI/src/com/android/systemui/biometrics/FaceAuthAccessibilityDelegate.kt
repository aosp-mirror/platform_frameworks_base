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
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.domain.interactor.KeyguardFaceAuthInteractor
import com.android.systemui.res.R
import javax.inject.Inject

/**
 * Accessibility delegate that will add a click accessibility action to a view when face auth can
 * run. When the click a11y action is triggered, face auth will retry.
 */
@SysUISingleton
class FaceAuthAccessibilityDelegate
@Inject
constructor(
    @Main private val resources: Resources,
    private val faceAuthInteractor: KeyguardFaceAuthInteractor,
) : View.AccessibilityDelegate() {
    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(host, info)
        if (faceAuthInteractor.canFaceAuthRun()) {
            val clickActionToRetryFace =
                AccessibilityNodeInfo.AccessibilityAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK.id,
                    resources.getString(R.string.retry_face)
                )
            info.addAction(clickActionToRetryFace)
        }
    }

    override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
        return if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK.id) {
            faceAuthInteractor.onAccessibilityAction()
            true
        } else super.performAccessibilityAction(host, action, args)
    }
}
