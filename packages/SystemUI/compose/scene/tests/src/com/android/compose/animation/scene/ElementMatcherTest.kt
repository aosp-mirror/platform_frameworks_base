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

package com.android.compose.animation.scene

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compose.animation.scene.TestElements.Bar
import com.android.compose.animation.scene.TestElements.Foo
import com.android.compose.animation.scene.TestScenes.SceneA
import com.android.compose.animation.scene.TestScenes.SceneB
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ElementMatcherTest {
    @Test
    fun and() {
        val matcher = Foo and inContent(SceneA)
        assertThat(matcher.matches(Foo, SceneA)).isTrue()
        assertThat(matcher.matches(Foo, SceneB)).isFalse()
        assertThat(matcher.matches(Bar, SceneA)).isFalse()
        assertThat(matcher.matches(Bar, SceneB)).isFalse()
    }

    @Test
    fun or() {
        val matcher = Foo or inContent(SceneA)
        assertThat(matcher.matches(Foo, SceneA)).isTrue()
        assertThat(matcher.matches(Foo, SceneB)).isTrue()
        assertThat(matcher.matches(Bar, SceneA)).isTrue()
        assertThat(matcher.matches(Bar, SceneB)).isFalse()
    }

    @Test
    fun not() {
        val matcher = !Foo
        assertThat(matcher.matches(Foo, SceneA)).isFalse()
        assertThat(matcher.matches(Foo, SceneB)).isFalse()
        assertThat(matcher.matches(Bar, SceneA)).isTrue()
        assertThat(matcher.matches(Bar, SceneB)).isTrue()
    }
}
