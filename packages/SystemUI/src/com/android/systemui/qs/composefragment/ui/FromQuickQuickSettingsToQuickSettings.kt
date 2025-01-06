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

package com.android.systemui.qs.composefragment.ui

import com.android.compose.animation.scene.TransitionBuilder
import com.android.systemui.qs.composefragment.SceneKeys
import com.android.systemui.qs.shared.ui.ElementKeys

fun TransitionBuilder.quickQuickSettingsToQuickSettings(
    animateTilesExpansion: () -> Boolean = { true }
) {

    fractionRange(start = 0.5f) { fade(ElementKeys.QuickSettingsContent) }

    fractionRange(start = 0.9f) { fade(ElementKeys.FooterActions) }

    anchoredTranslate(ElementKeys.QuickSettingsContent, ElementKeys.GridAnchor)

    sharedElement(ElementKeys.TileElementMatcher, enabled = animateTilesExpansion())

    // This will animate between 0f (QQS) and 0.6, fading in the QQS tiles when coming back
    // from non first page QS. The QS content ends fading out at 0.5f, so there's a brief
    // overlap, but because they are really faint, it looks better than complete black without
    // overlap.
    fractionRange(end = 0.6f) { fade(SceneKeys.QqsTileElementMatcher) }
    anchoredTranslate(SceneKeys.QqsTileElementMatcher, ElementKeys.GridAnchor)
}
