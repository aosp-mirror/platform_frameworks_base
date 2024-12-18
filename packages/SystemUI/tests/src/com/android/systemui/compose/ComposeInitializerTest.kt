/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.compose

import android.content.Context
import android.testing.ViewUtils
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class ComposeInitializerTest : SysuiTestCase() {
    @Test
    fun testCanAddComposeViewInInitializedWindow() {
        val root = TestWindowRoot(context)
        try {
            runOnMainThreadAndWaitForIdleSync { ViewUtils.attachView(root) }
            assertThat(root.isAttachedToWindow).isTrue()

            runOnMainThreadAndWaitForIdleSync { root.addView(ComposeView(context)) }
        } finally {
            runOnMainThreadAndWaitForIdleSync { ViewUtils.detachView(root) }
        }
    }

    private fun runOnMainThreadAndWaitForIdleSync(f: () -> Unit) {
        mContext.mainExecutor.execute(f)
        waitForIdleSync()
    }

    class TestWindowRoot(context: Context) : FrameLayout(context) {
        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            ComposeInitializer.onAttachedToWindow(this)
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            ComposeInitializer.onDetachedFromWindow(this)
        }
    }
}
