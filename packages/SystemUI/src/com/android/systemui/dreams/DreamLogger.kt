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

package com.android.systemui.dreams

import com.android.systemui.log.core.Logger
import com.android.systemui.log.core.MessageBuffer

/** Logs dream-related stuff to a {@link LogBuffer}. */
class DreamLogger(buffer: MessageBuffer, tag: String) : Logger(buffer, tag) {
    fun logDreamOverlayEnabled(enabled: Boolean) =
        d({ "Dream overlay enabled: $bool1" }) { bool1 = enabled }

    fun logIgnoreAddComplication(reason: String, complication: String) =
        d({ "Ignore adding complication, reason: $str1, complication: $str2" }) {
            str1 = reason
            str2 = complication
        }

    fun logIgnoreRemoveComplication(reason: String, complication: String) =
        d({ "Ignore removing complication, reason: $str1, complication: $str2" }) {
            str1 = reason
            str2 = complication
        }

    fun logAddComplication(complication: String) =
        d({ "Add dream complication: $str1" }) { str1 = complication }

    fun logRemoveComplication(complication: String) =
        d({ "Remove dream complication: $str1" }) { str1 = complication }

    fun logOverlayActive(active: Boolean) = d({ "Dream overlay active: $bool1" }) { bool1 = active }

    fun logLowLightActive(active: Boolean) =
        d({ "Low light mode active: $bool1" }) { bool1 = active }

    fun logHasAssistantAttention(hasAttention: Boolean) =
        d({ "Dream overlay has Assistant attention: $bool1" }) { bool1 = hasAttention }

    fun logStatusBarVisible(visible: Boolean) =
        d({ "Dream overlay status bar visible: $bool1" }) { bool1 = visible }

    fun logAvailableComplicationTypes(types: Int) =
        d({ "Available complication types: $int1" }) { int1 = types }

    fun logShouldShowComplications(showComplications: Boolean) =
        d({ "Dream overlay should show complications: $bool1" }) { bool1 = showComplications }

    fun logShowOrHideStatusBarItem(show: Boolean, type: String) =
        d({ "${if (bool1) "Showing" else "Hiding"} dream status bar item: $int1" }) {
            bool1 = show
            str1 = type
        }
}
