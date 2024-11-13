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

package com.android.compose.animation.scene

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag

/** A [SemanticsMatcher] that matches [element], optionally restricted to content [content]. */
fun isElement(element: ElementKey, content: ContentKey? = null): SemanticsMatcher {
    return if (content == null) {
        hasTestTag(element.testTag)
    } else {
        hasTestTag(element.testTag) and inContent(content)
    }
}

/** A [SemanticsMatcher] that matches anything inside [content]. */
fun inContent(content: ContentKey): SemanticsMatcher {
    return hasAnyAncestor(hasTestTag(content.testTag))
}
