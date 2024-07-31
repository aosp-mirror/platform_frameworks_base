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

/** An interface to match one or more elements. */
interface ElementMatcher {
    /** Whether the element with key [key] in scene [content] matches this matcher. */
    fun matches(key: ElementKey, content: ContentKey): Boolean
}

/**
 * Returns an [ElementMatcher] that matches elements in [content] also matching [this]
 * [ElementMatcher].
 */
fun ElementMatcher.inContent(content: ContentKey): ElementMatcher {
    val delegate = this
    val matcherScene = content
    return object : ElementMatcher {
        override fun matches(key: ElementKey, content: ContentKey): Boolean {
            return content == matcherScene && delegate.matches(key, content)
        }
    }
}

@Deprecated("Use inContent() instead", replaceWith = ReplaceWith("inContent(scene)"))
fun ElementMatcher.inScene(scene: SceneKey) = inContent(scene)
