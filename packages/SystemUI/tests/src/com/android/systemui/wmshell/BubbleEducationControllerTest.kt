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
package com.android.systemui.wmshell

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ShortcutInfo
import android.content.res.Resources
import android.os.UserHandle
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.core.content.edit
import androidx.test.filters.SmallTest
import com.android.systemui.model.SysUiStateTest
import com.android.wm.shell.bubbles.Bubble
import com.android.wm.shell.bubbles.BubbleEducationController
import com.android.wm.shell.bubbles.PREF_MANAGED_EDUCATION
import com.android.wm.shell.bubbles.PREF_STACK_EDUCATION
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.MoreExecutors.directExecutor
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class BubbleEducationControllerTest : SysUiStateTest() {

    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var sut: BubbleEducationController

    @Before
    fun setUp() {
        sharedPrefs = mContext.getSharedPreferences(mContext.packageName, Context.MODE_PRIVATE)
        sharedPrefs.edit {
            remove(PREF_STACK_EDUCATION)
            remove(PREF_MANAGED_EDUCATION)
        }
        sut = BubbleEducationController(mContext)
    }

    @Test
    fun testSeenStackEducation_read() {
        sharedPrefs.edit { putBoolean(PREF_STACK_EDUCATION, true) }
        assertEquals(sut.hasSeenStackEducation, true)
    }

    @Test
    fun testSeenStackEducation_write() {
        sut.hasSeenStackEducation = true
        assertThat(sharedPrefs.getBoolean(PREF_STACK_EDUCATION, false)).isTrue()
    }

    @Test
    fun testSeenManageEducation_read() {
        sharedPrefs.edit { putBoolean(PREF_MANAGED_EDUCATION, true) }
        assertEquals(sut.hasSeenManageEducation, true)
    }

    @Test
    fun testSeenManageEducation_write() {
        sut.hasSeenManageEducation = true
        assertThat(sharedPrefs.getBoolean(PREF_MANAGED_EDUCATION, false)).isTrue()
    }

    @Test
    fun testShouldShowStackEducation() {
        // When bubble is null
        assertEquals(sut.shouldShowStackEducation(null), false)
        var bubble = createFakeBubble(isConversational = false)
        // When bubble is not conversation
        assertEquals(sut.shouldShowStackEducation(bubble), false)
        // When bubble is conversation and has seen stack edu
        bubble = createFakeBubble(isConversational = true)
        sharedPrefs.edit { putBoolean(PREF_STACK_EDUCATION, true) }
        assertEquals(sut.shouldShowStackEducation(bubble), false)
        // When bubble is conversation and has not seen stack edu
        sharedPrefs.edit { remove(PREF_STACK_EDUCATION) }
        assertEquals(sut.shouldShowStackEducation(bubble), true)
    }

    @Test
    fun testShouldShowManageEducation() {
        // When bubble is null
        assertEquals(sut.shouldShowManageEducation(null), false)
        var bubble = createFakeBubble(isConversational = false)
        // When bubble is not conversation
        assertEquals(sut.shouldShowManageEducation(bubble), false)
        // When bubble is conversation and has seen stack edu
        bubble = createFakeBubble(isConversational = true)
        sharedPrefs.edit { putBoolean(PREF_MANAGED_EDUCATION, true) }
        assertEquals(sut.shouldShowManageEducation(bubble), false)
        // When bubble is conversation and has not seen stack edu
        sharedPrefs.edit { remove(PREF_MANAGED_EDUCATION) }
        assertEquals(sut.shouldShowManageEducation(bubble), true)
    }

    private fun createFakeBubble(isConversational: Boolean): Bubble {
        return if (isConversational) {
            val shortcutInfo = ShortcutInfo.Builder(mContext, "fakeId").build()
            Bubble(
                "key",
                shortcutInfo,
                /* desiredHeight= */ 6,
                Resources.ID_NULL,
                "title",
                /* taskId= */ 0,
                "locus",
                /* isDismissable= */ true,
                directExecutor()
            ) {}
        } else {
            val intent = Intent(Intent.ACTION_VIEW).setPackage(mContext.packageName)
            Bubble.createAppBubble(intent, UserHandle(1), null, directExecutor())
        }
    }
}
