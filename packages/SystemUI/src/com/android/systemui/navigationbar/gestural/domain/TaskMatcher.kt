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

package com.android.systemui.navigationbar.gestural.domain

import android.content.ComponentName

/**
 * A simple data class for capturing details around a task. Implements equality to ensure changes
 * can be identified between emitted values.
 */
data class TaskInfo(val topActivity: ComponentName?, val topActivityType: Int) {
    override fun equals(other: Any?): Boolean {
        return other is TaskInfo &&
            other.topActivityType == topActivityType &&
            other.topActivity == topActivity
    }
}

/**
 * [TaskMatcher] provides a way to identify a task based on particular attributes, such as the top
 * activity type or component name.
 */
sealed interface TaskMatcher {
    fun matches(info: TaskInfo): Boolean

    class TopActivityType(private val type: Int) : TaskMatcher {
        override fun matches(info: TaskInfo): Boolean {
            return info.topActivity != null && info.topActivityType == type
        }
    }

    class TopActivityComponent(private val component: ComponentName) : TaskMatcher {
        override fun matches(info: TaskInfo): Boolean {
            return component == info.topActivity
        }
    }
}
