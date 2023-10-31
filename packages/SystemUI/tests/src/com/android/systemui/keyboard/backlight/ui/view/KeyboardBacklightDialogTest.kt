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
 *
 */

package com.android.systemui.keyboard.backlight.ui.view

import android.testing.TestableLooper.RunWithLooper
import android.view.View
import android.view.accessibility.AccessibilityEvent
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.res.R
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWithLooper
@SmallTest
@RunWith(JUnit4::class)
class KeyboardBacklightDialogTest : SysuiTestCase() {

    private lateinit var dialog: KeyboardBacklightDialog
    private lateinit var rootView: View
    private val descriptionString = context.getString(R.string.keyboard_backlight_value)

    @Before
    fun setUp() {
        dialog =
            KeyboardBacklightDialog(context, initialCurrentLevel = 0, initialMaxLevel = MAX_LEVEL)
        dialog.show()
        rootView = dialog.requireViewById(R.id.keyboard_backlight_dialog_container)
    }

    @Test
    fun rootViewContentDescription_containsInitialLevel() {
        assertThat(rootView.contentDescription).isEqualTo(contentDescriptionForLevel(INITIAL_LEVEL))
    }

    @Test
    fun contentDescriptionUpdated_afterEveryLevelUpdate() {
        val events = startCollectingAccessibilityEvents(rootView)

        dialog.updateState(current = 1, max = MAX_LEVEL)

        assertThat(rootView.contentDescription).isEqualTo(contentDescriptionForLevel(1))
        assertThat(events).contains(AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION)
    }

    private fun contentDescriptionForLevel(level: Int): String {
        return String.format(descriptionString, level, MAX_LEVEL)
    }

    private fun startCollectingAccessibilityEvents(rootView: View): MutableList<Int> {
        val events = mutableListOf<Int>()
        rootView.accessibilityDelegate =
            object : View.AccessibilityDelegate() {
                override fun sendAccessibilityEvent(host: View, eventType: Int) {
                    super.sendAccessibilityEvent(host, eventType)
                    events.add(eventType)
                }
            }
        return events
    }

    companion object {
        private const val MAX_LEVEL = 5
        private const val INITIAL_LEVEL = 0
    }
}
