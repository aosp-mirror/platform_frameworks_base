/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.controls.ui

import android.service.controls.Control

import com.android.systemui.R

class StatusBehavior : Behavior {
    lateinit var cvh: ControlViewHolder

    override fun initialize(cvh: ControlViewHolder) {
        this.cvh = cvh
    }

    override fun bind(cws: ControlWithState, colorOffset: Int) {
        val status = cws.control?.status ?: Control.STATUS_UNKNOWN
        val msg = when (status) {
            Control.STATUS_ERROR -> R.string.controls_error_generic
            Control.STATUS_NOT_FOUND -> R.string.controls_error_removed
            else -> com.android.internal.R.string.loading
        }
        cvh.status.setText(cvh.context.getString(msg))
        cvh.applyRenderInfo(false, colorOffset)
    }
}
