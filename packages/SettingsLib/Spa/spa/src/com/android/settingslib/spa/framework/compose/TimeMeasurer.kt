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

@file:OptIn(ExperimentalTime::class)

package com.android.settingslib.spa.framework.compose

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource

const val ENABLE_MEASURE_TIME = false

interface TimeMeasurer {
    fun log(msg: String) {}
    fun logFirst(msg: String) {}

    companion object {
        private object EmptyTimeMeasurer : TimeMeasurer

        @Composable
        fun rememberTimeMeasurer(tag: String): TimeMeasurer = remember {
            if (ENABLE_MEASURE_TIME) TimeMeasurerImpl(tag) else EmptyTimeMeasurer
        }
    }
}

private class TimeMeasurerImpl(private val tag: String) : TimeMeasurer {
    private val mark = TimeSource.Monotonic.markNow()
    private val msgLogged = mutableSetOf<String>()

    override fun log(msg: String) {
        Log.d(tag, "Timer $msg: ${mark.elapsedNow()}")
    }

    override fun logFirst(msg: String) {
        if (msgLogged.add(msg)) {
            Log.d(tag, "Timer $msg: ${mark.elapsedNow()}")
        }
    }
}
