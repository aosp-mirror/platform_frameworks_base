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

import android.platform.test.flag.junit.DeviceFlagsValueProvider
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.wm.shell.compatui.api.CompatUIRepository
import com.android.wm.shell.compatui.api.CompatUISpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests for {@link DefaultCompatUIRepository}.
 *
 * Build/Install/Run:
 *  atest WMShellUnitTests:DefaultCompatUIRepositoryTest
 */
@RunWith(AndroidTestingRunner::class)
@SmallTest
class DefaultCompatUIRepositoryTest {

    lateinit var repository: CompatUIRepository

    @get:Rule
    val mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule()

    @Before
    fun setUp() {
        repository = DefaultCompatUIRepository()
    }

    @Test(expected = IllegalStateException::class)
    fun `addSpec throws exception with specs with duplicate id`() {
        repository.addSpec(CompatUISpec("one"))
        repository.addSpec(CompatUISpec("one"))
    }

    @Test
    fun `iterateOn invokes the consumer`() {
        with(repository) {
            addSpec(CompatUISpec("one"))
            addSpec(CompatUISpec("two"))
            addSpec(CompatUISpec("three"))
            val consumer = object : (CompatUISpec) -> Unit {
                var acc = ""
                override fun invoke(spec: CompatUISpec) {
                    acc += spec.name
                }
            }
            iterateOn(consumer)
            assertEquals("onetwothree", consumer.acc)
        }
    }

    @Test
    fun `findSpec returns existing specs`() {
        with(repository) {
            val one = CompatUISpec("one")
            val two = CompatUISpec("two")
            val three = CompatUISpec("three")
            addSpec(one)
            addSpec(two)
            addSpec(three)
            assertEquals(findSpec("one"), one)
            assertEquals(findSpec("two"), two)
            assertEquals(findSpec("three"), three)
            assertNull(findSpec("abc"))
        }
    }
}