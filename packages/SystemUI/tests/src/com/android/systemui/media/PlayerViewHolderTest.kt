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

package com.android.systemui.media

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout

import androidx.test.filters.SmallTest

import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat

import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests for PlayerViewHolder.
 */
@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class PlayerViewHolderTest : SysuiTestCase() {

    private lateinit var inflater: LayoutInflater
    private lateinit var parent: ViewGroup

    @Before
    fun setUp() {
        inflater = LayoutInflater.from(context)
        parent = FrameLayout(context)
    }

    @Test
    fun create() {
        val holder = PlayerViewHolder.create(inflater, parent)
        assertThat(holder.player).isNotNull()
    }

    @Test
    fun backgroundIsIlluminationDrawable() {
        val holder = PlayerViewHolder.create(inflater, parent)
        assertThat(holder.player.background as IlluminationDrawable).isNotNull()
    }
}
