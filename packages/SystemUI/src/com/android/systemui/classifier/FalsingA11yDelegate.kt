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

package com.android.systemui.classifier

import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK
import javax.inject.Inject

/**
 * Class that injects an artificial tap into the falsing collector.
 *
 * This is used for views that can be interacted with by A11y services and have falsing checks, as
 * the gestures made by the A11y framework do not propagate motion events down the view hierarchy.
 */
class FalsingA11yDelegate @Inject constructor(private val falsingCollector: FalsingCollector) :
    View.AccessibilityDelegate() {
    override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
        if (action == ACTION_CLICK) {
            falsingCollector.onA11yAction()
        }
        return super.performAccessibilityAction(host, action, args)
    }
}
