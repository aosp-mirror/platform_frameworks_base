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

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.systemui.model.SysUiStateTest
import com.android.wm.shell.bubbles.Bubble
import com.android.wm.shell.bubbles.BubbleEducationController
import com.android.wm.shell.bubbles.PREF_MANAGED_EDUCATION
import com.android.wm.shell.bubbles.PREF_STACK_EDUCATION
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class BubbleEducationControllerTest : SysUiStateTest() {
    private val sharedPrefsEditor = Mockito.mock(SharedPreferences.Editor::class.java)
    private val sharedPrefs = Mockito.mock(SharedPreferences::class.java)
    private val context = Mockito.mock(Context::class.java)
    private lateinit var sut: BubbleEducationController

    @Before
    fun setUp() {
        Mockito.`when`(context.packageName).thenReturn("packageName")
        Mockito.`when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(sharedPrefs)
        Mockito.`when`(context.contentResolver)
            .thenReturn(Mockito.mock(ContentResolver::class.java))
        Mockito.`when`(sharedPrefs.edit()).thenReturn(sharedPrefsEditor)
        sut = BubbleEducationController(context)
    }

    @Test
    fun testSeenStackEducation_read() {
        Mockito.`when`(sharedPrefs.getBoolean(anyString(), anyBoolean())).thenReturn(true)
        assertEquals(sut.hasSeenStackEducation, true)
        Mockito.verify(sharedPrefs).getBoolean(PREF_STACK_EDUCATION, false)
    }

    @Test
    fun testSeenStackEducation_write() {
        sut.hasSeenStackEducation = true
        Mockito.verify(sharedPrefsEditor).putBoolean(PREF_STACK_EDUCATION, true)
    }

    @Test
    fun testSeenManageEducation_read() {
        Mockito.`when`(sharedPrefs.getBoolean(anyString(), anyBoolean())).thenReturn(true)
        assertEquals(sut.hasSeenManageEducation, true)
        Mockito.verify(sharedPrefs).getBoolean(PREF_MANAGED_EDUCATION, false)
    }

    @Test
    fun testSeenManageEducation_write() {
        sut.hasSeenManageEducation = true
        Mockito.verify(sharedPrefsEditor).putBoolean(PREF_MANAGED_EDUCATION, true)
    }

    @Test
    fun testShouldShowStackEducation() {
        val bubble = Mockito.mock(Bubble::class.java)
        // When bubble is null
        assertEquals(sut.shouldShowStackEducation(null), false)
        // When bubble is not conversation
        Mockito.`when`(bubble.isConversation).thenReturn(false)
        assertEquals(sut.shouldShowStackEducation(bubble), false)
        // When bubble is conversation and has seen stack edu
        Mockito.`when`(bubble.isConversation).thenReturn(true)
        Mockito.`when`(sharedPrefs.getBoolean(anyString(), anyBoolean())).thenReturn(true)
        assertEquals(sut.shouldShowStackEducation(bubble), false)
        // When bubble is conversation and has not seen stack edu
        Mockito.`when`(bubble.isConversation).thenReturn(true)
        Mockito.`when`(sharedPrefs.getBoolean(anyString(), anyBoolean())).thenReturn(false)
        assertEquals(sut.shouldShowStackEducation(bubble), true)
    }

    @Test
    fun testShouldShowManageEducation() {
        val bubble = Mockito.mock(Bubble::class.java)
        // When bubble is null
        assertEquals(sut.shouldShowManageEducation(null), false)
        // When bubble is not conversation
        Mockito.`when`(bubble.isConversation).thenReturn(false)
        assertEquals(sut.shouldShowManageEducation(bubble), false)
        // When bubble is conversation and has seen stack edu
        Mockito.`when`(bubble.isConversation).thenReturn(true)
        Mockito.`when`(sharedPrefs.getBoolean(anyString(), anyBoolean())).thenReturn(true)
        assertEquals(sut.shouldShowManageEducation(bubble), false)
        // When bubble is conversation and has not seen stack edu
        Mockito.`when`(bubble.isConversation).thenReturn(true)
        Mockito.`when`(sharedPrefs.getBoolean(anyString(), anyBoolean())).thenReturn(false)
        assertEquals(sut.shouldShowManageEducation(bubble), true)
    }
}
