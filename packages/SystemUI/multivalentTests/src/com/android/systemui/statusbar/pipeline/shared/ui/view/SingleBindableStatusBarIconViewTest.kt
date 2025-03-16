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

package com.android.systemui.statusbar.pipeline.shared.ui.view

import android.graphics.Rect
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.StatusBarIconView
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Being a simple subclass of [ModernStatusBarView], use the same basic test cases to verify the
 * root behavior, and add testing for the new [SingleBindableStatusBarIconView.withDefaultBinding]
 * method.
 */
@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class SingleBindableStatusBarIconViewTest : SysuiTestCase() {
    private lateinit var binding: SingleBindableStatusBarIconViewBinding

    // Visibility is outsourced to view-models. This simulates it
    private var isVisible = true
    private var visibilityFn: () -> Boolean = { isVisible }

    @Test
    fun initView_hasCorrectSlot() {
        val view = createAndInitView()

        assertThat(view.slot).isEqualTo(SLOT_NAME)
    }

    @Test
    fun getVisibleState_icon_returnsIcon() {
        val view = createAndInitView()

        view.setVisibleState(StatusBarIconView.STATE_ICON, /* animate= */ false)

        assertThat(view.visibleState).isEqualTo(StatusBarIconView.STATE_ICON)
    }

    @Test
    fun getVisibleState_dot_returnsDot() {
        val view = createAndInitView()

        view.setVisibleState(StatusBarIconView.STATE_DOT, /* animate= */ false)

        assertThat(view.visibleState).isEqualTo(StatusBarIconView.STATE_DOT)
    }

    @Test
    fun getVisibleState_hidden_returnsHidden() {
        val view = createAndInitView()

        view.setVisibleState(StatusBarIconView.STATE_HIDDEN, /* animate= */ false)

        assertThat(view.visibleState).isEqualTo(StatusBarIconView.STATE_HIDDEN)
    }

    @Test
    fun onDarkChanged_bindingReceivesIconAndDecorTint() {
        val view = createAndInitView()

        view.onDarkChangedWithContrast(arrayListOf(), 0x12345678, 0x12344321)

        assertThat(binding.iconTint).isEqualTo(0x12345678)
        assertThat(binding.decorTint).isEqualTo(0x12345678)
    }

    @Test
    fun setStaticDrawableColor_bindingReceivesIconTint() {
        val view = createAndInitView()

        view.setStaticDrawableColor(0x12345678, 0x12344321)

        assertThat(binding.iconTint).isEqualTo(0x12345678)
    }

    @Test
    fun setDecorColor_bindingReceivesDecorColor() {
        val view = createAndInitView()

        view.setDecorColor(0x23456789)

        assertThat(binding.decorTint).isEqualTo(0x23456789)
    }

    @Test
    fun isIconVisible_usesBinding_true() {
        val view = createAndInitView()

        isVisible = true

        assertThat(view.isIconVisible).isEqualTo(true)
    }

    @Test
    fun isIconVisible_usesBinding_false() {
        val view = createAndInitView()

        isVisible = false

        assertThat(view.isIconVisible).isEqualTo(false)
    }

    @Test
    fun getDrawingRect_takesTranslationIntoAccount() {
        val view = createAndInitView()

        view.translationX = 50f
        view.translationY = 60f

        val drawingRect = Rect()
        view.getDrawingRect(drawingRect)

        assertThat(drawingRect.left).isEqualTo(view.left + 50)
        assertThat(drawingRect.right).isEqualTo(view.right + 50)
        assertThat(drawingRect.top).isEqualTo(view.top + 60)
        assertThat(drawingRect.bottom).isEqualTo(view.bottom + 60)
    }

    private fun createAndInitView(): SingleBindableStatusBarIconView {
        val view = SingleBindableStatusBarIconView.createView(context)
        binding = SingleBindableStatusBarIconView.withDefaultBinding(view, visibilityFn) {}
        view.initView(SLOT_NAME) { binding }
        return view
    }

    companion object {
        private const val SLOT_NAME = "test_slot"
    }
}
