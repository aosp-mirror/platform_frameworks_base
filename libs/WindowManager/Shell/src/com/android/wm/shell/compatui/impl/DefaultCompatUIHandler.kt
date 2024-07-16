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

package com.android.wm.shell.compatui.impl

import com.android.wm.shell.compatui.api.CompatUIEvent
import com.android.wm.shell.compatui.api.CompatUIHandler
import com.android.wm.shell.compatui.api.CompatUIInfo
import com.android.wm.shell.compatui.api.CompatUIRepository
import java.util.function.Consumer

/**
 * Default implementation of {@link CompatUIHandler} to handle CompatUI components
 */
class DefaultCompatUIHandler(
    private val compatUIRepository: CompatUIRepository
) : CompatUIHandler {

    private var compatUIEventSender: Consumer<CompatUIEvent>? = null
    override fun onCompatInfoChanged(compatUIInfo: CompatUIInfo) {
        // Empty at the moment
    }

    override fun setCallback(compatUIEventSender: Consumer<CompatUIEvent>?) {
        this.compatUIEventSender = compatUIEventSender
    }
}