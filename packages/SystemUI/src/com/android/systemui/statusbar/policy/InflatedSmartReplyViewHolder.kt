/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.systemui.statusbar.policy

import android.widget.Button

/**
 * Holder for inflated smart replies and actions. These objects should be inflated on a background
 * thread, to later be accessed and modified on the (performance critical) UI thread.
 */
class InflatedSmartReplyViewHolder(
    val smartReplyView: SmartReplyView?,
    val smartSuggestionButtons: List<Button>?
)