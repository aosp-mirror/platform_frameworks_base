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

package com.android.settingslib.devicestate

import android.content.Context
import android.content.res.Resources
import android.provider.Settings.Secure.DEVICE_STATE_ROTATION_KEY_FOLDED
import android.provider.Settings.Secure.DEVICE_STATE_ROTATION_KEY_HALF_FOLDED
import android.provider.Settings.Secure.DEVICE_STATE_ROTATION_KEY_REAR_DISPLAY
import android.provider.Settings.Secure.DEVICE_STATE_ROTATION_KEY_UNFOLDED
import android.provider.Settings.Secure.DEVICE_STATE_ROTATION_KEY_UNKNOWN
import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4
import com.android.internal.R
import com.google.common.truth.Expect
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

private const val DEVICE_STATE_UNKNOWN = 0
private const val DEVICE_STATE_CLOSED = 1
private const val DEVICE_STATE_HALF_FOLDED = 2
private const val DEVICE_STATE_OPEN = 3
private const val DEVICE_STATE_REAR_DISPLAY = 4

@SmallTest
@RunWith(AndroidJUnit4::class)
class PosturesHelperTest {

    @get:Rule val expect: Expect = Expect.create()

    @Mock private lateinit var context: Context

    @Mock private lateinit var resources: Resources

    private lateinit var posturesHelper: PosturesHelper

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        whenever(context.resources).thenReturn(resources)
        whenever(resources.getIntArray(R.array.config_foldedDeviceStates))
            .thenReturn(intArrayOf(DEVICE_STATE_CLOSED))
        whenever(resources.getIntArray(R.array.config_halfFoldedDeviceStates))
            .thenReturn(intArrayOf(DEVICE_STATE_HALF_FOLDED))
        whenever(resources.getIntArray(R.array.config_openDeviceStates))
            .thenReturn(intArrayOf(DEVICE_STATE_OPEN))
        whenever(resources.getIntArray(R.array.config_rearDisplayDeviceStates))
            .thenReturn(intArrayOf(DEVICE_STATE_REAR_DISPLAY))

        posturesHelper = PosturesHelper(context)
    }

    @Test
    fun deviceStateToPosture_mapsCorrectly() {
        expect
            .that(posturesHelper.deviceStateToPosture(DEVICE_STATE_CLOSED))
            .isEqualTo(DEVICE_STATE_ROTATION_KEY_FOLDED)
        expect
            .that(posturesHelper.deviceStateToPosture(DEVICE_STATE_HALF_FOLDED))
            .isEqualTo(DEVICE_STATE_ROTATION_KEY_HALF_FOLDED)
        expect
            .that(posturesHelper.deviceStateToPosture(DEVICE_STATE_OPEN))
            .isEqualTo(DEVICE_STATE_ROTATION_KEY_UNFOLDED)
        expect
            .that(posturesHelper.deviceStateToPosture(DEVICE_STATE_REAR_DISPLAY))
            .isEqualTo(DEVICE_STATE_ROTATION_KEY_REAR_DISPLAY)
        expect
            .that(posturesHelper.deviceStateToPosture(DEVICE_STATE_UNKNOWN))
            .isEqualTo(DEVICE_STATE_ROTATION_KEY_UNKNOWN)
    }

    @Test
    fun postureToDeviceState_mapsCorrectly() {
        expect
            .that(posturesHelper.postureToDeviceState(DEVICE_STATE_ROTATION_KEY_FOLDED))
            .isEqualTo(DEVICE_STATE_CLOSED)
        expect
            .that(posturesHelper.postureToDeviceState(DEVICE_STATE_ROTATION_KEY_HALF_FOLDED))
            .isEqualTo(DEVICE_STATE_HALF_FOLDED)
        expect
            .that(posturesHelper.postureToDeviceState(DEVICE_STATE_ROTATION_KEY_UNFOLDED))
            .isEqualTo(DEVICE_STATE_OPEN)
        expect
            .that(posturesHelper.postureToDeviceState(DEVICE_STATE_ROTATION_KEY_REAR_DISPLAY))
            .isEqualTo(DEVICE_STATE_REAR_DISPLAY)
        expect.that(posturesHelper.postureToDeviceState(DEVICE_STATE_ROTATION_KEY_UNKNOWN)).isNull()
    }
}
