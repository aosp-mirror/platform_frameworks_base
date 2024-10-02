/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.android.systemui.util

import android.testing.UiThreadTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import java.lang.AssertionError
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.model.FrameworkMethod
import org.junit.runners.model.Statement

/**
 * Test that NoUiThreadTestRule asserts when it finds a framework method with a UiThreadTest
 * annotation.
 */
@RunWith(AndroidJUnit4::class)
@SmallTest
public class NoUiThreadTestRuleTest : SysuiTestCase() {

    class TestStatement : Statement() {
        override fun evaluate() {}
    }

    inner class TestInner {
        @Test @UiThreadTest fun simpleUiTest() {}

        @Test fun simpleTest() {}
    }

    /**
     * Test that NoUiThreadTestRule throws an asserts false if a test is annotated
     * with @UiThreadTest
     */
    @Test(expected = AssertionError::class)
    fun testNoUiThreadFail() {
        val method = TestInner::class.java.getDeclaredMethod("simpleUiTest")
        val frameworkMethod = FrameworkMethod(method)
        val noUiThreadTestRule = NoUiThreadTestRule()
        val testStatement = TestStatement()
        // target needs to be non-null
        val obj = Object()
        noUiThreadTestRule.apply(testStatement, frameworkMethod, obj)
    }

    /**
     * Test that NoUiThreadTestRule throws an asserts false if a test is annotated
     * with @UiThreadTest
     */
    fun testNoUiThreadOK() {
        val method = TestInner::class.java.getDeclaredMethod("simpleUiTest")
        val frameworkMethod = FrameworkMethod(method)
        val noUiThreadTestRule = NoUiThreadTestRule()
        val testStatement = TestStatement()

        // because target needs to be non-null
        val obj = Object()
        val newStatement = noUiThreadTestRule.apply(testStatement, frameworkMethod, obj)
        Assert.assertEquals(newStatement, testStatement)
    }
}
