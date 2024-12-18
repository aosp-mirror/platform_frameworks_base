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
import org.junit.Assert.fail
import org.junit.rules.MethodRule
import org.junit.runners.model.FrameworkMethod
import org.junit.runners.model.Statement

/**
 * A Test rule which prevents us from using the UiThreadTest annotation. See
 * go/android_junit4_uithreadtest (b/352170965)
 */
public class NoUiThreadTestRule : MethodRule {
    override fun apply(base: Statement, method: FrameworkMethod, target: Any): Statement? {
        if (hasUiThreadAnnotation(method, target)) {
            fail("UiThreadTest doesn't actually run on the UiThread")
        }
        return base
    }

    private fun hasUiThreadAnnotation(method: FrameworkMethod, target: Any): Boolean {
        if (method.getAnnotation(UiThreadTest::class.java) != null) {
            return true
        } else {
            return target.javaClass.getAnnotation(UiThreadTest::class.java) != null
        }
    }
}
