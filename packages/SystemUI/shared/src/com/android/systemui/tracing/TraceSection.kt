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

package com.android.systemui.tracing

import com.android.systemui.tracing.TraceUtils.Companion.traceCoroutine

/**
 * Represents a section of code executing in a coroutine. This can be split up into multiple slices
 * on different threads as the coroutine is suspended and resumed.
 *
 * This is internal machinery for [traceCoroutine]. It cannot be made `internal` or `private`
 * because [traceCoroutine] is a Public-API inline function.
 *
 * @param name the name of the slice to appear on the current thread's track.
 * @param id used for matching the beginning and end of trace sections and validating correctness
 * @see traceCoroutine
 */
data class TraceSection(
    val name: String,
    val id: Int,
)
