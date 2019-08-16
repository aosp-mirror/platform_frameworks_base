/*
 * Copyright (C) 2019 The Android Open Source Project
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
 * limitations under the License
 */

package android.content.res.loader.test

import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Tests generic ResourceLoader behavior. Intentionally abstract in its test methodology because
 * the behavior being verified isn't specific to any resource type. As long as it can pass an
 * equals check.
 *
 * Currently tests strings and dimens since String and any Number seemed most relevant to verify.
 */
@RunWith(Parameterized::class)
class ResourceLoaderValuesTest : ResourceLoaderTestBase() {

    companion object {
        @Parameterized.Parameters(name = "{1} {0}")
        @JvmStatic
        fun parameters(): Array<Any> {
            val parameters = mutableListOf<Parameter>()

            // R.string
            parameters += Parameter(
                    { getString(android.R.string.cancel) },
                    "stringOne", { "SomeRidiculouslyUnlikelyStringOne" },
                    "stringTwo", { "SomeRidiculouslyUnlikelyStringTwo" },
                    listOf(DataType.APK, DataType.ARSC)
            )

            // R.dimen
            parameters += Parameter(
                    { resources.getDimensionPixelSize(android.R.dimen.app_icon_size) },
                    "dimenOne", { 564716.dpToPx(resources) },
                    "dimenTwo", { 565717.dpToPx(resources) },
                    listOf(DataType.APK, DataType.ARSC)
            )

            // File in the assets directory
            parameters += Parameter(
                    { assets.open("Asset.txt").reader().readText() },
                    "assetOne", { "assetOne" },
                    "assetTwo", { "assetTwo" },
                    listOf(DataType.ASSET)
            )

            // From assets directory returning file descriptor
            parameters += Parameter(
                    { assets.openFd("Asset.txt").readText() },
                    "assetOne", { "assetOne" },
                    "assetTwo", { "assetTwo" },
                    listOf(DataType.ASSET_FD)
            )

            // From root directory returning file descriptor
            parameters += Parameter(
                    { assets.openNonAssetFd("NonAsset.txt").readText() },
                    "NonAssetOne", { "NonAssetOne" },
                    "NonAssetTwo", { "NonAssetTwo" },
                    listOf(DataType.NON_ASSET)
            )

            // Asset as compiled XML drawable
            parameters += Parameter(
                    { (getDrawable(R.drawable.non_asset_drawable) as ColorDrawable).color },
                    "nonAssetDrawableOne", { Color.parseColor("#A3C3E3") },
                    "nonAssetDrawableTwo", { Color.parseColor("#3A3C3E") },
                    listOf(DataType.NON_ASSET_DRAWABLE)
            )

            // Asset as compiled bitmap drawable
            parameters += Parameter(
                    {
                        (getDrawable(R.drawable.non_asset_bitmap) as BitmapDrawable)
                                .bitmap.getColor(0, 0).toArgb()
                    },
                    "nonAssetBitmapGreen", { Color.GREEN },
                    "nonAssetBitmapBlue", { Color.BLUE },
                    listOf(DataType.NON_ASSET_BITMAP)
            )

            // Asset as compiled XML layout
            parameters += Parameter(
                    { getLayout(R.layout.layout).advanceToRoot().name },
                    "layoutOne", { "RelativeLayout" },
                    "layoutTwo", { "LinearLayout" },
                    listOf(DataType.NON_ASSET_LAYOUT)
            )

            // Isolated resource split
            parameters += Parameter(
                    { getString(R.string.split_overlaid) },
                    "split_one", { "Split ONE Overlaid" },
                    "split_two", { "Split TWO Overlaid" },
                    listOf(DataType.SPLIT)
            )

            return parameters.flatMap { parameter ->
                parameter.dataTypes.map { dataType ->
                    arrayOf(dataType, parameter)
                }
            }.toTypedArray()
        }
    }

    @Suppress("LateinitVarOverridesLateinitVar")
    @field:Parameterized.Parameter(0)
    override lateinit var dataType: DataType

    @field:Parameterized.Parameter(1)
    lateinit var parameter: Parameter

    private val valueOne by lazy { parameter.valueOne(this) }
    private val valueTwo by lazy { parameter.valueTwo(this) }

    private fun openOne() = parameter.loaderOne.openLoader()
    private fun openTwo() = parameter.loaderTwo.openLoader()

    // Class method for syntax highlighting purposes
    private fun getValue() = parameter.getValue(this)

    @Test
    fun verifyValueUniqueness() {
        // Ensure the parameters are valid in case of coding errors
        assertNotEquals(valueOne, getValue())
        assertNotEquals(valueTwo, getValue())
        assertNotEquals(valueOne, valueTwo)
    }

    @Test
    fun addMultipleLoaders() {
        val originalValue = getValue()
        val testOne = openOne()
        val testTwo = openTwo()

        addLoader(testOne, testTwo)

        assertEquals(valueTwo, getValue())

        removeLoader(testTwo)

        assertEquals(valueOne, getValue())

        removeLoader(testOne)

        assertEquals(originalValue, getValue())
    }

    @Test
    fun setMultipleLoaders() {
        val originalValue = getValue()
        val testOne = openOne()
        val testTwo = openTwo()

        setLoaders(testOne, testTwo)

        assertEquals(valueTwo, getValue())

        removeLoader(testTwo)

        assertEquals(valueOne, getValue())

        setLoaders()

        assertEquals(originalValue, getValue())
    }

    @Test
    fun getLoadersContainsAll() {
        val testOne = openOne()
        val testTwo = openTwo()

        addLoader(testOne, testTwo)

        assertThat(getLoaders()).containsAllOf(testOne, testTwo)
    }

    @Test
    fun getLoadersDoesNotLeakMutability() {
        val originalValue = getValue()
        val testOne = openOne()
        val testTwo = openTwo()

        addLoader(testOne)

        assertEquals(valueOne, getValue())

        val loaders = getLoaders()
        loaders += testTwo

        assertEquals(valueOne, getValue())

        removeLoader(testOne)

        assertEquals(originalValue, getValue())
    }

    @Test(expected = IllegalArgumentException::class)
    fun alreadyAddedThrows() {
        val testOne = openOne()
        val testTwo = openTwo()

        addLoader(testOne)
        addLoader(testTwo)
        addLoader(testOne)
    }

    @Test(expected = IllegalArgumentException::class)
    fun alreadyAddedAndSetThrows() {
        val testOne = openOne()
        val testTwo = openTwo()

        addLoader(testOne)
        addLoader(testTwo)
        setLoaders(testTwo)
    }

    @Test
    fun repeatedRemoveSucceeds() {
        val originalValue = getValue()
        val testOne = openOne()

        addLoader(testOne)

        assertNotEquals(originalValue, getValue())

        removeLoader(testOne)

        assertEquals(originalValue, getValue())

        removeLoader(testOne)

        assertEquals(originalValue, getValue())
    }

    @Test
    fun addToFront() {
        val testOne = openOne()
        val testTwo = openTwo()

        addLoader(testOne)

        assertEquals(valueOne, getValue())

        addLoader(testTwo, 0)

        assertEquals(valueOne, getValue())

        // Remove top loader, so previously added to front should now resolve
        removeLoader(testOne)
        assertEquals(valueTwo, getValue())
    }

    @Test
    fun addToEnd() {
        val testOne = openOne()
        val testTwo = openTwo()

        addLoader(testOne)

        assertEquals(valueOne, getValue())

        addLoader(testTwo, 1)

        assertEquals(valueTwo, getValue())
    }

    @Test(expected = IndexOutOfBoundsException::class)
    fun addPastEnd() {
        val testOne = openOne()
        val testTwo = openTwo()

        addLoader(testOne)

        assertEquals(valueOne, getValue())

        addLoader(testTwo, 2)
    }

    @Test(expected = IndexOutOfBoundsException::class)
    fun addBeforeFront() {
        val testOne = openOne()
        val testTwo = openTwo()

        addLoader(testOne)

        assertEquals(valueOne, getValue())

        addLoader(testTwo, -1)
    }

    @Test
    fun reorder() {
        val originalValue = getValue()
        val testOne = openOne()
        val testTwo = openTwo()

        addLoader(testOne, testTwo)

        assertEquals(valueTwo, getValue())

        removeLoader(testOne)

        assertEquals(valueTwo, getValue())

        addLoader(testOne)

        assertEquals(valueOne, getValue())

        removeLoader(testTwo)

        assertEquals(valueOne, getValue())

        removeLoader(testOne)

        assertEquals(originalValue, getValue())
    }

    data class Parameter(
        val getValue: ResourceLoaderValuesTest.() -> Any,
        val loaderOne: String,
        val valueOne: ResourceLoaderValuesTest.() -> Any,
        val loaderTwo: String,
        val valueTwo: ResourceLoaderValuesTest.() -> Any,
        val dataTypes: List<DataType>
    ) {
        override fun toString(): String {
            val prefix = loaderOne.commonPrefixWith(loaderTwo)
            return "$prefix${loaderOne.removePrefix(prefix)}|${loaderTwo.removePrefix(prefix)}"
        }
    }
}
