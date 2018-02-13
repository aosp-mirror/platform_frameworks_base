/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.sdkparcelables

import junit.framework.TestCase.assertEquals
import org.junit.Test

class ParcelableDetectorTest {
    @Test
    fun `detect implements`() {
        val ancestorMap = mapOf(
                testAncestors("android/test/Parcelable",null, "android/os/Parcelable"),
                testAncestors("android/os/Parcelable", null))

        val parcelables = ParcelableDetector.ancestorsToParcelables(ancestorMap)

        assertEquals(parcelables, listOf("android/test/Parcelable"))
    }

    @Test
    fun `detect implements in reverse order`() {
        val ancestorMap = mapOf(
                testAncestors("android/os/Parcelable", null),
                testAncestors("android/test/Parcelable",null, "android/os/Parcelable"))

        val parcelables = ParcelableDetector.ancestorsToParcelables(ancestorMap)

        assertEquals(parcelables, listOf("android/test/Parcelable"))
    }

    @Test
    fun `detect super implements`() {
        val ancestorMap = mapOf(
                testAncestors("android/test/SuperParcelable",null, "android/os/Parcelable"),
                testAncestors("android/test/Parcelable","android/test/SuperParcelable"),
                testAncestors("android/os/Parcelable", null))

        val parcelables = ParcelableDetector.ancestorsToParcelables(ancestorMap)

        assertEquals(parcelables, listOf("android/test/Parcelable", "android/test/SuperParcelable"))
    }

    @Test
    fun `detect super interface`() {
        val ancestorMap = mapOf(
                testAncestors("android/test/IParcelable",null, "android/os/Parcelable"),
                testAncestors("android/test/Parcelable",null, "android/test/IParcelable"),
                testAncestors("android/os/Parcelable", null))

        val parcelables = ParcelableDetector.ancestorsToParcelables(ancestorMap)

        assertEquals(parcelables, listOf("android/test/IParcelable", "android/test/Parcelable"))
    }

}

private fun testAncestors(name: String, superName: String?, vararg interfaces: String): Pair<String, Ancestors> {
    return Pair(name, Ancestors(superName, interfaces.toList()))
}
