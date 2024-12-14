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

package com.android.systemui.statusbar.window

import android.content.Context
import android.view.LayoutInflater
import com.android.systemui.res.R
import javax.inject.Inject

/**
 * Inflates a [StatusBarWindowView]. Exists so that it can be injected into
 * [StatusBarWindowControllerImpl] and be swapped for a fake implementation in tests.
 */
interface StatusBarWindowViewInflater {
    fun inflate(context: Context): StatusBarWindowView
}

class StatusBarWindowViewInflaterImpl @Inject constructor() : StatusBarWindowViewInflater {

    override fun inflate(context: Context): StatusBarWindowView {
        val layoutInflater = LayoutInflater.from(context)
        return layoutInflater.inflate(R.layout.super_status_bar, /* root= */ null)
            as StatusBarWindowView?
            ?: throw IllegalStateException(
                "R.layout.super_status_bar could not be properly inflated"
            )
    }
}
