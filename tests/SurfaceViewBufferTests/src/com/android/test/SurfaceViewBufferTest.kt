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
import android.graphics.Rect
import android.provider.Settings
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.android.server.wm.flicker.monitor.LayersTraceMonitor
import com.android.server.wm.flicker.monitor.withSFTracing
import com.android.server.wm.flicker.traces.layers.LayersTraceSubject.Companion.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.concurrent.CountDownLatch
import kotlin.properties.Delegates

@RunWith(Parameterized::class)
class SurfaceViewBufferTest(val useBlastAdapter: Boolean) {
    private var mInitialUseBlastConfig by Delegates.notNull<Int>()

    @get:Rule
    var scenarioRule: ActivityScenarioRule<MainActivity> =
            ActivityScenarioRule<MainActivity>(MainActivity::class.java)

    protected val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    val defaultBufferSize = Rect(0, 0, 640, 480)

    @Before
    fun setup() {
        mInitialUseBlastConfig = Settings.Global.getInt(instrumentation.context.contentResolver,
                "use_blast_adapter_sv", 0)
        val enable = if (useBlastAdapter) 1 else 0
        Settings.Global.putInt(instrumentation.context.contentResolver, "use_blast_adapter_sv",
                enable)
        val tmpDir = instrumentation.targetContext.dataDir.toPath()
        LayersTraceMonitor(tmpDir).stop()

        lateinit var surfaceReadyLatch: CountDownLatch
        scenarioRule.getScenario().onActivity {
            surfaceReadyLatch = it.addSurfaceView(defaultBufferSize)
        }
        surfaceReadyLatch.await()
    }

    @After
    fun teardown() {
        scenarioRule.getScenario().close()
        Settings.Global.putInt(instrumentation.context.contentResolver,
                "use_blast_adapter_sv", mInitialUseBlastConfig)
    }

    @Test
    fun testSetBuffersGeometry_0x0_resetsBufferSize() {
        val trace = withSFTracing(instrumentation, TRACE_FLAGS) {
            scenarioRule.getScenario().onActivity {
                it.mSurfaceProxy.ANativeWindowSetBuffersGeometry(it.surface!!, 0, 0,
                        R8G8B8A8_UNORM)
                it.mSurfaceProxy.ANativeWindowLock()
                it.mSurfaceProxy.ANativeWindowUnlockAndPost()
                it.mSurfaceProxy.waitUntilBufferDisplayed(1, 1 /* sec */)
            }
        }

        // verify buffer size is reset to default buffer size
        assertThat(trace).layer("SurfaceView", 1).hasBufferSize(defaultBufferSize)
    }

    @Test
    fun testSetBuffersGeometry_0x0_rejectsBuffer() {
        val trace = withSFTracing(instrumentation, TRACE_FLAGS) {
            scenarioRule.getScenario().onActivity {
                it.mSurfaceProxy.ANativeWindowSetBuffersGeometry(it.surface!!, 100, 100,
                        R8G8B8A8_UNORM)
                it.mSurfaceProxy.ANativeWindowLock()
                it.mSurfaceProxy.ANativeWindowUnlockAndPost()
                it.mSurfaceProxy.ANativeWindowLock()
                it.mSurfaceProxy.ANativeWindowSetBuffersGeometry(it.surface!!, 0, 0, R8G8B8A8_UNORM)
                // Submit buffer one with a different size which should be rejected
                it.mSurfaceProxy.ANativeWindowUnlockAndPost()

                // submit a buffer with the default buffer size
                it.mSurfaceProxy.ANativeWindowLock()
                it.mSurfaceProxy.ANativeWindowUnlockAndPost()
                it.mSurfaceProxy.waitUntilBufferDisplayed(3, 1 /* sec */)
            }
        }
        // Verify we reject buffers since scaling mode == NATIVE_WINDOW_SCALING_MODE_FREEZE
        assertThat(trace).layer("SurfaceView", 2).doesNotExist()

        // Verify the next buffer is submitted with the correct size
        assertThat(trace).layer("SurfaceView", 3).also {
            it.hasBufferSize(defaultBufferSize)
            it.hasScalingMode(0 /* NATIVE_WINDOW_SCALING_MODE_FREEZE */)
        }
    }

    @Test
    fun testSetBuffersGeometry_smallerThanBuffer() {
        val bufferSize = Rect(0, 0, 300, 200)
        val trace = withSFTracing(instrumentation, TRACE_FLAGS) {
            scenarioRule.getScenario().onActivity {
                it.mSurfaceProxy.ANativeWindowSetBuffersGeometry(it.surface!!, bufferSize.width(),
                        bufferSize.height(), R8G8B8A8_UNORM)
                it.drawFrame()
                it.mSurfaceProxy.waitUntilBufferDisplayed(1, 1 /* sec */)
            }
        }

        assertThat(trace).layer("SurfaceView", 1).also {
            it.hasBufferSize(bufferSize)
            it.hasLayerSize(defaultBufferSize)
            it.hasScalingMode(1 /* NATIVE_WINDOW_SCALING_MODE_SCALE_TO_WINDOW */)
        }
    }

    @Test
    fun testSetBuffersGeometry_largerThanBuffer() {
        val bufferSize = Rect(0, 0, 3000, 2000)
        val trace = withSFTracing(instrumentation, TRACE_FLAGS) {
            scenarioRule.getScenario().onActivity {
                it.mSurfaceProxy.ANativeWindowSetBuffersGeometry(it.surface!!, bufferSize.width(),
                        bufferSize.height(), R8G8B8A8_UNORM)
                it.drawFrame()
                it.mSurfaceProxy.waitUntilBufferDisplayed(1, 1 /* sec */)
            }
        }

        assertThat(trace).layer("SurfaceView", 1).also {
            it.hasBufferSize(bufferSize)
            it.hasLayerSize(defaultBufferSize)
            it.hasScalingMode(1 /* NATIVE_WINDOW_SCALING_MODE_SCALE_TO_WINDOW */)
        }
    }

    /** Submit buffers as fast as possible and make sure they are queued */
    @Test
    fun testQueueBuffers() {
        val trace = withSFTracing(instrumentation, TRACE_FLAGS) {
            scenarioRule.getScenario().onActivity {
                it.mSurfaceProxy.ANativeWindowSetBuffersGeometry(it.surface!!, 100, 100,
                        R8G8B8A8_UNORM)
                for (i in 0..100) {
                    it.mSurfaceProxy.ANativeWindowLock()
                    it.mSurfaceProxy.ANativeWindowUnlockAndPost()
                }
                it.mSurfaceProxy.waitUntilBufferDisplayed(100, 1 /* sec */)
            }
        }
        for (frameNumber in 1..100) {
            assertThat(trace).layer("SurfaceView", frameNumber.toLong())
        }
    }

    companion object {
        private const val TRACE_FLAGS = 0x1 // TRACE_CRITICAL
        private const val R8G8B8A8_UNORM = 1

        @JvmStatic
        @Parameterized.Parameters(name = "blast={0}")
        fun data(): Collection<Array<Any>> {
            return listOf(
                    arrayOf(false), // First test:  submit buffers via bufferqueue
                    arrayOf(true)   // Second test: submit buffers via blast adapter
            )
        }
    }
}