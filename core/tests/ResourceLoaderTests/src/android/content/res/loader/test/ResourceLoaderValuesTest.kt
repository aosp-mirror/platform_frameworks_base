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

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.content.res.loader.ResourcesLoader
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.os.IBinder
import androidx.test.rule.ActivityTestRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.Collections

/**
 * Tests generic ResourceLoader behavior. Intentionally abstract in its test methodology because
 * the behavior being verified isn't specific to any resource type. As long as it can pass an
 * equals check.
 *
 * Currently tests strings and dimens since String and any Number seemed most relevant to verify.
 */
@RunWith(Parameterized::class)
class ResourceLoaderValuesTest : ResourceLoaderTestBase() {

    @get:Rule
    private val mTestActivityRule = ActivityTestRule<TestActivity>(TestActivity::class.java)

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
                    "stringThree", { "SomeRidiculouslyUnlikelyStringThree" },
                    "stringFour", { "SomeRidiculouslyUnlikelyStringFour" },
                    listOf(DataType.APK, DataType.ARSC)
            )

            // R.dimen
            parameters += Parameter(
                    { getDimensionPixelSize(android.R.dimen.app_icon_size) },
                    "dimenOne", { 100.dpToPx(resources) },
                    "dimenTwo", { 200.dpToPx(resources) },
                    "dimenThree", { 300.dpToPx(resources) },
                    "dimenFour", { 400.dpToPx(resources) },
                    listOf(DataType.APK, DataType.ARSC)
            )

            // File in the assets directory
            parameters += Parameter(
                    { assets.open("Asset.txt").reader().readText() },
                    "assetOne", { "assetOne" },
                    "assetTwo", { "assetTwo" },
                    "assetFour", { "assetFour" },
                    "assetThree", { "assetThree" },
                    listOf(DataType.ASSET)
            )

            // From assets directory returning file descriptor
            parameters += Parameter(
                    { assets.openFd("Asset.txt").readText() },
                    "assetOne", { "assetOne" },
                    "assetTwo", { "assetTwo" },
                    "assetFour", { "assetFour" },
                    "assetThree", { "assetThree" },
                    listOf(DataType.ASSET_FD)
            )

            // From root directory returning file descriptor
            parameters += Parameter(
                    { assets.openNonAssetFd("NonAsset.txt").readText() },
                    "NonAssetOne", { "NonAssetOne" },
                    "NonAssetTwo", { "NonAssetTwo" },
                    "NonAssetThree", { "NonAssetThree" },
                    "NonAssetFour", { "NonAssetFour" },
                    listOf(DataType.NON_ASSET)
            )

            // Asset as compiled XML drawable
            parameters += Parameter(
                    { (getDrawable(R.drawable.non_asset_drawable) as ColorDrawable).color },
                    "nonAssetDrawableOne", { Color.parseColor("#000001") },
                    "nonAssetDrawableTwo", { Color.parseColor("#000002") },
                    "nonAssetDrawableThree", { Color.parseColor("#000003") },
                    "nonAssetDrawableFour", { Color.parseColor("#000004") },
                    listOf(DataType.NON_ASSET_DRAWABLE)
            )

            // Asset as compiled bitmap drawable
            parameters += Parameter(
                    {
                        (getDrawable(R.drawable.non_asset_bitmap) as BitmapDrawable)
                                .bitmap.getColor(0, 0).toArgb()
                    },
                    "nonAssetBitmapRed", { Color.RED },
                    "nonAssetBitmapGreen", { Color.GREEN },
                    "nonAssetBitmapBlue", { Color.BLUE },
                    "nonAssetBitmapWhite", { Color.WHITE },
                    listOf(DataType.NON_ASSET_BITMAP)
            )

            // Asset as compiled XML layout
            parameters += Parameter(
                    { getLayout(R.layout.layout).advanceToRoot().name },
                    "layoutOne", { "RelativeLayout" },
                    "layoutTwo", { "LinearLayout" },
                    "layoutThree", { "FrameLayout" },
                    "layoutFour", { "TableLayout" },
                    listOf(DataType.NON_ASSET_LAYOUT)
            )

            // Isolated resource split
            parameters += Parameter(
                    { getString(R.string.split_overlaid) },
                    "split_one", { "Split ONE Overlaid" },
                    "split_two", { "Split TWO Overlaid" },
                    "split_three", { "Split THREE Overlaid" },
                    "split_four", { "Split FOUR Overlaid" },
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
    private val valueThree by lazy { parameter.valueThree(this) }
    private val valueFour by lazy { parameter.valueFour(this) }

    private fun openOne() = parameter.providerOne.openProvider()
    private fun openTwo() = parameter.providerTwo.openProvider()
    private fun openThree() = parameter.providerThree.openProvider()
    private fun openFour() = parameter.providerFour.openProvider()

    // Class method for syntax highlighting purposes
    private fun getValue(c: Context = context) = parameter.getValue(c.resources)

    @Test
    fun assertValueUniqueness() {
        // Ensure the parameters are valid in case of coding errors
        val original = getValue()
        assertNotEquals(valueOne, original)
        assertNotEquals(valueTwo, original)
        assertNotEquals(valueThree, original)
        assertNotEquals(valueFour, original)
        assertNotEquals(valueTwo, valueOne)
        assertNotEquals(valueThree, valueOne)
        assertNotEquals(valueFour, valueOne)
        assertNotEquals(valueThree, valueTwo)
        assertNotEquals(valueFour, valueTwo)
        assertNotEquals(valueFour, valueThree)
    }

    @Test
    fun addProvidersRepeatedly() {
        val originalValue = getValue()
        val testOne = openOne()
        val testTwo = openTwo()
        val loader = ResourcesLoader()

        resources.addLoaders(loader)
        loader.addProvider(testOne)
        assertEquals(valueOne, getValue())

        loader.addProvider(testTwo)
        assertEquals(valueTwo, getValue())

        loader.removeProvider(testOne)
        assertEquals(valueTwo, getValue())

        loader.removeProvider(testTwo)
        assertEquals(originalValue, getValue())
    }

    @Test
    fun addLoadersRepeatedly() {
        val originalValue = getValue()
        val testOne = openOne()
        val testTwo = openTwo()
        val loader1 = ResourcesLoader()
        val loader2 = ResourcesLoader()

        resources.addLoaders(loader1)
        loader1.addProvider(testOne)
        assertEquals(valueOne, getValue())

        resources.addLoaders(loader2)
        loader2.addProvider(testTwo)
        assertEquals(valueTwo, getValue())

        resources.removeLoaders(loader1)
        assertEquals(valueTwo, getValue())

        resources.removeLoaders(loader2)
        assertEquals(originalValue, getValue())
    }

    @Test
    fun setMultipleProviders() {
        val originalValue = getValue()
        val testOne = openOne()
        val testTwo = openTwo()
        val loader = ResourcesLoader()

        resources.addLoaders(loader)
        loader.providers = listOf(testOne, testTwo)
        assertEquals(valueTwo, getValue())

        loader.removeProvider(testTwo)
        assertEquals(valueOne, getValue())

        loader.providers = Collections.emptyList()
        assertEquals(originalValue, getValue())
    }

    @Test
    fun addMultipleLoaders() {
        val originalValue = getValue()
        val loader1 = ResourcesLoader()
        loader1.addProvider(openOne())
        val loader2 = ResourcesLoader()
        loader2.addProvider(openTwo())

        resources.addLoaders(loader1, loader2)
        assertEquals(valueTwo, getValue())

        resources.removeLoaders(loader2)
        assertEquals(valueOne, getValue())

        resources.removeLoaders(loader1)
        assertEquals(originalValue, getValue())
    }

    @Test(expected = UnsupportedOperationException::class)
    fun getProvidersDoesNotLeakMutability() {
        val testOne = openOne()
        val loader = ResourcesLoader()
        val providers = loader.providers
        providers += testOne
    }

    @Test(expected = UnsupportedOperationException::class)
    fun getLoadersDoesNotLeakMutability() {
        val loaders = resources.loaders
        loaders += ResourcesLoader()
    }

    @Test
    fun alreadyAddedProviderNoOps() {
        val testOne = openOne()
        val testTwo = openTwo()
        val loader = ResourcesLoader()

        resources.addLoaders(loader)
        loader.addProvider(testOne)
        loader.addProvider(testTwo)
        loader.addProvider(testOne)

        assertEquals(2, loader.providers.size)
        assertEquals(loader.providers[0], testOne)
        assertEquals(loader.providers[1], testTwo)
    }

    @Test
    fun alreadyAddedLoaderNoOps() {
        val loader1 = ResourcesLoader()
        loader1.addProvider(openOne())
        val loader2 = ResourcesLoader()
        loader2.addProvider(openTwo())

        resources.addLoaders(loader1)
        resources.addLoaders(loader2)
        resources.addLoaders(loader1)

        assertEquals(2, resources.loaders.size)
        assertEquals(resources.loaders[0], loader1)
        assertEquals(resources.loaders[1], loader2)
    }

    @Test
    fun repeatedRemoveProviderNoOps() {
        val testOne = openOne()
        val testTwo = openTwo()
        val loader = ResourcesLoader()

        resources.addLoaders(loader)
        loader.addProvider(testOne)
        loader.addProvider(testTwo)

        loader.removeProvider(testOne)
        loader.removeProvider(testOne)

        assertEquals(1, loader.providers.size)
        assertEquals(loader.providers[0], testTwo)
    }

    @Test
    fun repeatedRemoveLoaderNoOps() {
        val loader1 = ResourcesLoader()
        loader1.addProvider(openOne())
        val loader2 = ResourcesLoader()
        loader2.addProvider(openTwo())

        resources.addLoaders(loader1, loader2)
        resources.removeLoaders(loader1)
        resources.removeLoaders(loader1)

        assertEquals(1, resources.loaders.size)
        assertEquals(resources.loaders[0], loader2)

        resources.removeLoaders(loader2, loader2)

        assertEquals(0, resources.loaders.size)
    }

    @Test
    fun repeatedSetProvider() {
        val testOne = openOne()
        val testTwo = openTwo()
        val loader = ResourcesLoader()

        resources.addLoaders(loader)
        loader.providers = listOf(testOne, testTwo)
        loader.providers = listOf(testOne, testTwo)

        assertEquals(2, loader.providers.size)
        assertEquals(loader.providers[0], testOne)
        assertEquals(loader.providers[1], testTwo)
    }

    @Test
    fun repeatedAddMultipleLoaders() {
        val loader1 = ResourcesLoader()
        loader1.addProvider(openOne())
        val loader2 = ResourcesLoader()
        loader2.addProvider(openTwo())

        resources.addLoaders(loader1, loader2)
        resources.addLoaders(loader1, loader2)

        assertEquals(2, resources.loaders.size)
        assertEquals(resources.loaders[0], loader1)
        assertEquals(resources.loaders[1], loader2)
    }

    @Test
    fun reorderProviders() {
        val originalValue = getValue()
        val testOne = openOne()
        val testTwo = openTwo()
        val loader = ResourcesLoader()

        resources.addLoaders(loader)
        loader.addProvider(testOne)
        loader.addProvider(testTwo)
        assertEquals(valueTwo, getValue())

        loader.removeProvider(testOne)
        assertEquals(valueTwo, getValue())

        loader.addProvider(testOne)
        assertEquals(valueOne, getValue())

        loader.removeProvider(testTwo)
        assertEquals(valueOne, getValue())

        loader.removeProvider(testOne)
        assertEquals(originalValue, getValue())
    }

    @Test
    fun reorderLoaders() {
        val originalValue = getValue()
        val testOne = openOne()
        val testTwo = openTwo()
        val loader1 = ResourcesLoader()
        loader1.addProvider(testOne)
        val loader2 = ResourcesLoader()
        loader2.addProvider(testTwo)

        resources.addLoaders(loader1)
        resources.addLoaders(loader2)
        assertEquals(valueTwo, getValue())

        resources.removeLoaders(loader1)
        assertEquals(valueTwo, getValue())

        resources.addLoaders(loader1)
        assertEquals(valueOne, getValue())

        resources.removeLoaders(loader2)
        assertEquals(valueOne, getValue())

        resources.removeLoaders(loader1)
        assertEquals(originalValue, getValue())
    }

    @Test
    fun reorderMultipleLoadersAndProviders() {
        val testOne = openOne()
        val testTwo = openTwo()
        val testThree = openThree()
        val testFour = openFour()

        val loader1 = ResourcesLoader()
        loader1.providers = listOf(testOne, testTwo)

        val loader2 = ResourcesLoader()
        loader2.providers = listOf(testThree, testFour)

        resources.addLoaders(loader1, loader2)
        assertEquals(valueFour, getValue())

        resources.removeLoaders(loader1)
        resources.addLoaders(loader1)
        assertEquals(valueTwo, getValue())

        loader1.removeProvider(testTwo)
        assertEquals(valueOne, getValue())

        loader1.removeProvider(testOne)
        assertEquals(valueFour, getValue())
    }

    private fun createContext(context: Context, id: Int): Context {
        val overrideConfig = Configuration()
        overrideConfig.orientation = Int.MAX_VALUE - id
        return context.createConfigurationContext(overrideConfig)
    }

    @Test
    fun copyContextLoaders() {
        val originalValue = getValue()
        val loader1 = ResourcesLoader()
        loader1.addProvider(openOne())
        val loader2 = ResourcesLoader()
        loader2.addProvider(openTwo())

        resources.addLoaders(loader1)
        assertEquals(valueOne, getValue())

        // The child context should include the loaders of the original context.
        val childContext = createContext(context, 0)
        assertEquals(valueOne, getValue(childContext))

        // Changing the loaders of the child context should not affect the original context.
        childContext.resources.addLoaders(loader2)
        assertEquals(valueOne, getValue())
        assertEquals(valueTwo, getValue(childContext))

        // Changing the loaders of the original context should not affect the child context.
        resources.removeLoaders(loader1)
        assertEquals(originalValue, getValue())
        assertEquals(valueTwo, getValue(childContext))

        // A new context created from the original after an update to the original's loaders should
        // have the updated loaders.
        val originalPrime = createContext(context, 2)
        assertEquals(originalValue, getValue(originalPrime))

        // A new context created from the child context after an update to the child's loaders
        // should have the updated loaders.
        val childPrime = createContext(childContext, 1)
        assertEquals(valueTwo, getValue(childPrime))
    }

    @Test
    fun loaderUpdatesAffectContexts() {
        val originalValue = getValue()
        val testOne = openOne()
        val testTwo = openTwo()
        val loader = ResourcesLoader()

        resources.addLoaders(loader)
        loader.addProvider(testOne)
        assertEquals(valueOne, getValue())

        val childContext = createContext(context, 0)
        assertEquals(valueOne, getValue(childContext))

        // Adding a provider to a loader affects all contexts that use the loader.
        loader.addProvider(testTwo)
        assertEquals(valueTwo, getValue())
        assertEquals(valueTwo, getValue(childContext))

        // Changes to the loaders for a context do not affect providers.
        resources.clearLoaders()
        assertEquals(originalValue, getValue())
        assertEquals(valueTwo, getValue(childContext))

        val childContext2 = createContext(context, 1)
        assertEquals(originalValue, getValue())
        assertEquals(originalValue, getValue(childContext2))

        childContext2.resources.addLoaders(loader)
        assertEquals(originalValue, getValue())
        assertEquals(valueTwo, getValue(childContext))
        assertEquals(valueTwo, getValue(childContext2))
    }

    @Test
    fun appLoadersIncludedInActivityContexts() {
        val loader = ResourcesLoader()
        loader.addProvider(openOne())

        val applicationContext = context.applicationContext
        applicationContext.resources.addLoaders(loader)
        assertEquals(valueOne, getValue(applicationContext))

        val activity = mTestActivityRule.launchActivity(Intent())
        assertEquals(valueOne, getValue(activity))

        applicationContext.resources.clearLoaders()
    }

    @Test
    fun loadersApplicationInfoChanged() {
        val loader1 = ResourcesLoader()
        loader1.addProvider(openOne())
        val loader2 = ResourcesLoader()
        loader2.addProvider(openTwo())

        val applicationContext = context.applicationContext
        applicationContext.resources.addLoaders(loader1)
        assertEquals(valueOne, getValue(applicationContext))

        var token: IBinder? = null
        val activity = mTestActivityRule.launchActivity(Intent())
        mTestActivityRule.runOnUiThread(Runnable {
            token = activity.activityToken
            val at = activity.activityThread

            // The activity should have the loaders from the application.
            assertEquals(valueOne, getValue(applicationContext))
            assertEquals(valueOne, getValue(activity))

            activity.resources.addLoaders(loader2)
            assertEquals(valueOne, getValue(applicationContext))
            assertEquals(valueTwo, getValue(activity))

            // Relaunches the activity.
            at.handleApplicationInfoChanged(activity.applicationInfo)
        })

        mTestActivityRule.runOnUiThread(Runnable {
            val activityThread = activity.activityThread
            val newActivity = activityThread.getActivity(token)

            // The loader added to the activity loaders should not be persisted.
            assertEquals(valueOne, getValue(applicationContext))
            assertEquals(valueOne, getValue(newActivity))
        })

        applicationContext.resources.clearLoaders()
    }

    @Test
    fun multipleLoadersHaveSameProviders() {
        val provider1 = openOne()
        val loader1 = ResourcesLoader()
        loader1.addProvider(provider1)
        val loader2 = ResourcesLoader()
        loader2.addProvider(provider1)
        loader2.addProvider(openTwo())

        resources.addLoaders(loader1, loader2)
        assertEquals(valueTwo, getValue())

        resources.removeLoaders(loader1)
        resources.addLoaders(loader1)
        assertEquals(valueOne, getValue())

        assertEquals(2, resources.assets.apkAssets.count { apkAssets -> apkAssets.isForLoader })
    }

    @Test(expected = IllegalStateException::class)
    fun cannotUseClosedProvider() {
        val provider = openOne()
        provider.close()
        val loader = ResourcesLoader()
        loader.addProvider(provider)
    }

    @Test(expected = IllegalStateException::class)
    fun cannotCloseUsedProvider() {
        val provider = openOne()
        val loader = ResourcesLoader()
        loader.addProvider(provider)
        provider.close()
    }

    data class Parameter(
        val getValue: Resources.() -> Any,
        val providerOne: String,
        val valueOne: ResourceLoaderValuesTest.() -> Any,
        val providerTwo: String,
        val valueTwo: ResourceLoaderValuesTest.() -> Any,
        val providerThree: String,
        val valueThree: ResourceLoaderValuesTest.() -> Any,
        val providerFour: String,
        val valueFour: ResourceLoaderValuesTest.() -> Any,
        val dataTypes: List<DataType>
    ) {
        override fun toString(): String {
            val prefix = providerOne.commonPrefixWith(providerTwo)
            return "$prefix${providerOne.removePrefix(prefix)}|${providerTwo.removePrefix(prefix)}"
        }
    }
}

class TestActivity : Activity()