/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.net.ParseException
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.testutils.DevSdkIgnoreRule
import junit.framework.Assert.assertEquals
import junit.framework.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class ParseExceptionTest {
    @get:Rule
    val ignoreRule = DevSdkIgnoreRule(ignoreClassUpTo = Build.VERSION_CODES.R)

    @Test
    fun testConstructor_WithCause() {
        val testMessage = "Test message"
        val base = Exception("Test")
        val exception = ParseException(testMessage, base)

        assertEquals(testMessage, exception.response)
        assertEquals(base, exception.cause)
    }

    @Test
    fun testConstructor_NoCause() {
        val testMessage = "Test message"
        val exception = ParseException(testMessage)

        assertEquals(testMessage, exception.response)
        assertNull(exception.cause)
    }
}