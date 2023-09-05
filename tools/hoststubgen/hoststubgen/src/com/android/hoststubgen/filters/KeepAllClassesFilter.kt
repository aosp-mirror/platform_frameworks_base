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
package com.android.hoststubgen.filters

/**
 * An [OutputFilter] that keeps all classes by default. (but none of its members)
 *
 * We're not currently using it, but using it *might* make certain things easier. For example, with
 * this, all classes would at least be loadable.
 */
class KeepAllClassesFilter(fallback: OutputFilter) : DelegatingFilter(fallback) {
    override fun getPolicyForClass(className: String): FilterPolicyWithReason {
        // If the default visibility wouldn't keep it, change it to "keep".
        val f = super.getPolicyForClass(className)
        return f.promoteToKeep("keep-all-classes")
    }
}