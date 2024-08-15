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

package com.android.systemui.gesture.domain

import android.app.WindowConfiguration
import android.content.ComponentName
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.navigationbar.gestural.domain.TaskInfo
import com.android.systemui.navigationbar.gestural.domain.TaskMatcher
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock

@RunWith(AndroidJUnit4::class)
@SmallTest
class TaskMatcherTest : SysuiTestCase() {
    @Test
    fun activityMatcher_matchesComponentName() {
        val componentName = ComponentName.unflattenFromString("com.foo/.bar")!!
        val matcher = TaskMatcher.TopActivityComponent(componentName)

        val taskInfo = TaskInfo(componentName, WindowConfiguration.ACTIVITY_TYPE_STANDARD)
        assertThat(matcher.matches(taskInfo)).isTrue()
    }

    @Test
    fun activityMatcher_doesNotMatchComponentName() {
        val componentName = ComponentName.unflattenFromString("com.foo/.bar")!!
        val matcher = TaskMatcher.TopActivityComponent(componentName)

        val taskInfo =
            TaskInfo(
                ComponentName.unflattenFromString("com.bar/.baz"),
                WindowConfiguration.ACTIVITY_TYPE_STANDARD
            )
        assertThat(matcher.matches(taskInfo)).isFalse()
    }

    @Test
    fun activityMatcher_matchesActivityType() {
        val activityType = WindowConfiguration.ACTIVITY_TYPE_HOME
        val matcher = TaskMatcher.TopActivityType(activityType)

        val taskInfo = TaskInfo(mock<ComponentName>(), activityType)
        assertThat(matcher.matches(taskInfo)).isTrue()
    }

    @Test
    fun activityMatcher_doesNotMatchEmptyActivityType() {
        val activityType = WindowConfiguration.ACTIVITY_TYPE_HOME
        val matcher = TaskMatcher.TopActivityType(activityType)

        val taskInfo = TaskInfo(null, activityType)
        assertThat(matcher.matches(taskInfo)).isFalse()
    }

    @Test
    fun activityMatcher_doesNotMatchActivityType() {
        val activityType = WindowConfiguration.ACTIVITY_TYPE_HOME
        val matcher = TaskMatcher.TopActivityType(activityType)

        val taskInfo = TaskInfo(mock<ComponentName>(), WindowConfiguration.ACTIVITY_TYPE_STANDARD)
        assertThat(matcher.matches(taskInfo)).isFalse()
    }

    @Test
    fun activityMatcher_equivalentMatchersAreNotEqual() {
        val activityType = WindowConfiguration.ACTIVITY_TYPE_HOME
        val matcher1 = TaskMatcher.TopActivityType(activityType)
        val matcher2 = TaskMatcher.TopActivityType(activityType)

        assertThat(matcher1).isNotEqualTo(matcher2)
    }
}
