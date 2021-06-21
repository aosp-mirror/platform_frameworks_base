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

import android.annotation.ColorInt
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.server.wm.WindowManagerState.getLogicalDisplaySize
import android.view.cts.surfacevalidator.CapturedActivity
import android.view.cts.surfacevalidator.ISurfaceValidatorTestCase
import android.view.cts.surfacevalidator.PixelChecker
import android.view.cts.surfacevalidator.RectChecker
import android.widget.FrameLayout
import androidx.test.rule.ActivityTestRule
import org.junit.After
import org.junit.Before
import org.junit.Rule
import java.util.concurrent.CountDownLatch

open class ScreenRecordTestBase(useBlastAdapter: Boolean) :
        SurfaceViewBufferTestBase(useBlastAdapter) {
    @get:Rule
    var mActivityRule = ActivityTestRule(MainActivity::class.java)

    private lateinit var mActivity: MainActivity

    @Before
    override fun setup() {
        super.setup()
        mActivity = mActivityRule.launchActivity(Intent())
        lateinit var surfaceReadyLatch: CountDownLatch
        runOnUiThread {
            it.dismissPermissionDialog()
            it.setLogicalDisplaySize(getLogicalDisplaySize())
            surfaceReadyLatch = it.addSurfaceView(defaultBufferSize)
        }
        surfaceReadyLatch.await()
        // sleep to finish animations
        instrumentation.waitForIdleSync()
    }

    @After
    override fun teardown() {
        super.teardown()
        mActivityRule.finishActivity()
    }

    fun runOnUiThread(predicate: (it: MainActivity) -> Unit) {
        mActivityRule.runOnUiThread {
            predicate(mActivity)
        }
    }

    fun withScreenRecording(
        boundsToCheck: Rect,
        @ColorInt color: Int,
        predicate: (it: MainActivity) -> Unit
    ): CapturedActivity.TestResult {
        val testCase = object : ISurfaceValidatorTestCase {
            override fun getChecker(): PixelChecker = RectChecker(boundsToCheck, color)
            override fun start(context: Context, parent: FrameLayout) {
                predicate(mActivity)
            }
            override fun end() { /* do nothing */ }
        }

        return mActivity.runTest(testCase)
    }
}