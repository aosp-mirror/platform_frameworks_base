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
package com.android.test

import android.app.Instrumentation
import android.graphics.Point
import android.provider.Settings
import android.tools.common.datatypes.Size
import android.tools.common.flicker.subject.layers.LayerSubject
import androidx.test.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TestName
import org.junit.runners.Parameterized
import kotlin.properties.Delegates

open class SurfaceViewBufferTestBase(val useBlastAdapter: Boolean) {
    private var mInitialBlastConfig by Delegates.notNull<Boolean>()

    val instrumentation: Instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    @get:Rule
    var mName = TestName()

    @Before
    open fun setup() {
        mInitialBlastConfig = getBlastAdapterSvEnabled()
        setBlastAdapterSvEnabled(useBlastAdapter)
    }

    @After
    open fun teardown() {
        setBlastAdapterSvEnabled(mInitialBlastConfig)
    }

    private fun getBlastAdapterSvEnabled(): Boolean {
        return Settings.Global.getInt(instrumentation.context.contentResolver,
                "use_blast_adapter_sv", 0) != 0
    }

    private fun setBlastAdapterSvEnabled(enable: Boolean) {
        Settings.Global.putInt(instrumentation.context.contentResolver, "use_blast_adapter_sv",
                if (enable) 1 else 0)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "blast={0}")
        fun data(): Collection<Array<Any>> {
            return listOf(
                    arrayOf(false), // First test:  submit buffers via bufferqueue
                    arrayOf(true)   // Second test: submit buffers via blast adapter
            )
        }

        const val R8G8B8A8_UNORM = 1
        val defaultBufferSize = Point(640, 480)

        fun LayerSubject.hasBufferSize(point: Point) = hasBufferSize(Size.from(point.x, point.y))

        fun LayerSubject.hasLayerSize(point: Point) = hasLayerSize(Size.from(point.x, point.y))

        // system/window.h definitions
        enum class ScalingMode() {
            FREEZE, // = 0
            SCALE_TO_WINDOW, // =1
            SCALE_CROP, // = 2
            NO_SCALE_CROP // = 3
        }

        // system/window.h definitions
        enum class Transform(val value: Int) {
            /* flip source image horizontally */
            FLIP_H(1),
            /* flip source image vertically */
            FLIP_V(2),
            /* rotate source image 90 degrees clock-wise, and is applied after TRANSFORM_FLIP_{H|V} */
            ROT_90(4),
            /* rotate source image 180 degrees */
            ROT_180(3),
            /* rotate source image 270 degrees clock-wise */
            ROT_270(7),
            /* transforms source by the inverse transform of the screen it is displayed onto. This
             * transform is applied last */
            INVERSE_DISPLAY(0x08)
        }
    }
}