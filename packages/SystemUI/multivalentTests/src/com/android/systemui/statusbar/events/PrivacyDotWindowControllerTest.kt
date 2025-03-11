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

package com.android.systemui.statusbar.events

import android.view.Gravity.BOTTOM
import android.view.Gravity.LEFT
import android.view.Gravity.RIGHT
import android.view.Gravity.TOP
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.view.fakeWindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.concurrency.fakeExecutor
import com.android.systemui.res.R
import com.android.systemui.testKosmos
import com.google.common.truth.Expect
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

@SmallTest
@RunWith(AndroidJUnit4::class)
class PrivacyDotWindowControllerTest : SysuiTestCase() {

    @get:Rule val expect: Expect = Expect.create()

    private val kosmos = testKosmos()
    private val underTest = kosmos.privacyDotWindowController
    private val viewController = kosmos.privacyDotViewController
    private val windowManager = kosmos.fakeWindowManager
    private val executor = kosmos.fakeExecutor

    @After
    fun cleanUpCustomDisplay() {
        context.display = null
    }

    @Test
    fun start_beforeUiThreadExecutes_doesNotAddWindows() {
        underTest.start()

        assertThat(windowManager.addedViews).isEmpty()
    }

    @Test
    fun start_beforeUiThreadExecutes_doesNotInitializeViewController() {
        underTest.start()

        assertThat(viewController.isInitialized).isFalse()
    }

    @Test
    fun start_afterUiThreadExecutes_addsWindowsOnUiThread() {
        underTest.start()

        executor.runAllReady()

        assertThat(windowManager.addedViews).hasSize(4)
    }

    @Test
    fun start_afterUiThreadExecutes_initializesViewController() {
        underTest.start()

        executor.runAllReady()

        assertThat(viewController.isInitialized).isTrue()
    }

    @Test
    fun start_initializesTopLeft() {
        underTest.start()
        executor.runAllReady()

        assertThat(viewController.topLeft?.id).isEqualTo(R.id.privacy_dot_top_left_container)
    }

    @Test
    fun start_initializesTopRight() {
        underTest.start()
        executor.runAllReady()

        assertThat(viewController.topRight?.id).isEqualTo(R.id.privacy_dot_top_right_container)
    }

    @Test
    fun start_initializesTopBottomLeft() {
        underTest.start()
        executor.runAllReady()

        assertThat(viewController.bottomLeft?.id).isEqualTo(R.id.privacy_dot_bottom_left_container)
    }

    @Test
    fun start_initializesBottomRight() {
        underTest.start()
        executor.runAllReady()

        assertThat(viewController.bottomRight?.id)
            .isEqualTo(R.id.privacy_dot_bottom_right_container)
    }

    @Test
    fun start_viewsAddedInRespectiveCorners() {
        context.display = mock { on { rotation } doReturn Surface.ROTATION_0 }

        underTest.start()
        executor.runAllReady()

        expect.that(gravityForView(viewController.topLeft!!)).isEqualTo(TOP or LEFT)
        expect.that(gravityForView(viewController.topRight!!)).isEqualTo(TOP or RIGHT)
        expect.that(gravityForView(viewController.bottomLeft!!)).isEqualTo(BOTTOM or LEFT)
        expect.that(gravityForView(viewController.bottomRight!!)).isEqualTo(BOTTOM or RIGHT)
    }

    @Test
    fun start_rotation90_viewsPositionIsShifted90degrees() {
        context.display = mock { on { rotation } doReturn Surface.ROTATION_90 }

        underTest.start()
        executor.runAllReady()

        expect.that(gravityForView(viewController.topLeft!!)).isEqualTo(BOTTOM or LEFT)
        expect.that(gravityForView(viewController.topRight!!)).isEqualTo(TOP or LEFT)
        expect.that(gravityForView(viewController.bottomLeft!!)).isEqualTo(BOTTOM or RIGHT)
        expect.that(gravityForView(viewController.bottomRight!!)).isEqualTo(TOP or RIGHT)
    }

    @Test
    fun start_rotation180_viewsPositionIsShifted180degrees() {
        context.display = mock { on { rotation } doReturn Surface.ROTATION_180 }

        underTest.start()
        executor.runAllReady()

        expect.that(gravityForView(viewController.topLeft!!)).isEqualTo(BOTTOM or RIGHT)
        expect.that(gravityForView(viewController.topRight!!)).isEqualTo(BOTTOM or LEFT)
        expect.that(gravityForView(viewController.bottomLeft!!)).isEqualTo(TOP or RIGHT)
        expect.that(gravityForView(viewController.bottomRight!!)).isEqualTo(TOP or LEFT)
    }

    @Test
    fun start_rotation270_viewsPositionIsShifted270degrees() {
        context.display = mock { on { rotation } doReturn Surface.ROTATION_270 }

        underTest.start()
        executor.runAllReady()

        expect.that(gravityForView(viewController.topLeft!!)).isEqualTo(TOP or RIGHT)
        expect.that(gravityForView(viewController.topRight!!)).isEqualTo(BOTTOM or RIGHT)
        expect.that(gravityForView(viewController.bottomLeft!!)).isEqualTo(TOP or LEFT)
        expect.that(gravityForView(viewController.bottomRight!!)).isEqualTo(BOTTOM or LEFT)
    }

    private fun paramsForView(view: View): WindowManager.LayoutParams {
        return windowManager.addedViews.entries
            .first { it.key == view || it.key.findViewById<View>(view.id) != null }
            .value
    }

    private fun gravityForView(view: View): Int {
        return paramsForView(view).gravity
    }
}
