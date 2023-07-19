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

package com.android.systemui.statusbar.pipeline.shared.ui.view

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.StatusBarIconView.STATE_DOT
import com.android.systemui.statusbar.StatusBarIconView.STATE_HIDDEN
import com.android.systemui.statusbar.StatusBarIconView.STATE_ICON
import com.android.systemui.statusbar.pipeline.shared.ui.binder.ModernStatusBarViewBinding
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper(setAsMainLooper = true)
class ModernStatusBarViewTest : SysuiTestCase() {

    private lateinit var binding: TestBinding

    @Test
    fun initView_hasCorrectSlot() {
        val view = ModernStatusBarView(context, null)
        val binding = TestBinding()

        view.initView("slotName") { binding }

        assertThat(view.slot).isEqualTo("slotName")
    }

    @Test
    fun getVisibleState_icon_returnsIcon() {
        val view = createAndInitView()

        view.setVisibleState(STATE_ICON, /* animate= */ false)

        assertThat(view.visibleState).isEqualTo(STATE_ICON)
    }

    @Test
    fun getVisibleState_dot_returnsDot() {
        val view = createAndInitView()

        view.setVisibleState(STATE_DOT, /* animate= */ false)

        assertThat(view.visibleState).isEqualTo(STATE_DOT)
    }

    @Test
    fun getVisibleState_hidden_returnsHidden() {
        val view = createAndInitView()

        view.setVisibleState(STATE_HIDDEN, /* animate= */ false)

        assertThat(view.visibleState).isEqualTo(STATE_HIDDEN)
    }

    @Test
    fun onDarkChanged_bindingReceivesIconAndDecorTint() {
        val view = createAndInitView()

        view.onDarkChanged(arrayListOf(), 1.0f, 0x12345678)

        assertThat(binding.iconTint).isEqualTo(0x12345678)
        assertThat(binding.decorTint).isEqualTo(0x12345678)
    }

    @Test
    fun setStaticDrawableColor_bindingReceivesIconTint() {
        val view = createAndInitView()

        view.setStaticDrawableColor(0x12345678)

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

        binding.shouldIconBeVisibleInternal = true

        assertThat(view.isIconVisible).isEqualTo(true)
    }

    @Test
    fun isIconVisible_usesBinding_false() {
        val view = createAndInitView()

        binding.shouldIconBeVisibleInternal = false

        assertThat(view.isIconVisible).isEqualTo(false)
    }

    private fun createAndInitView(): ModernStatusBarView {
        val view = ModernStatusBarView(context, null)
        binding = TestBinding()
        view.initView(SLOT_NAME) { binding }
        return view
    }

    inner class TestBinding : ModernStatusBarViewBinding {
        var iconTint: Int? = null
        var decorTint: Int? = null
        var onVisibilityStateChangedCalled: Boolean = false

        var shouldIconBeVisibleInternal: Boolean = true

        override fun onIconTintChanged(newTint: Int) {
            iconTint = newTint
        }

        override fun onDecorTintChanged(newTint: Int) {
            decorTint = newTint
        }

        override fun onVisibilityStateChanged(state: Int) {
            onVisibilityStateChangedCalled = true
        }

        override fun getShouldIconBeVisible(): Boolean {
            return shouldIconBeVisibleInternal
        }
    }
}

private const val SLOT_NAME = "TestSlotName"
