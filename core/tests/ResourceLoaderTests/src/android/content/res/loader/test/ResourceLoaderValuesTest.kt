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
import android.util.ArrayMap
import androidx.test.rule.ActivityTestRule
import org.json.JSONObject
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
        /** Converts the map to a stable JSON string representation. */
        private fun mapToString(m : Map<String, String>) :String {
            return JSONObject(ArrayMap<String, String>().apply { putAll(m) }).toString()
        }

        /** Creates a lambda that runs multiple resources queries and concatenates the results. */
        fun query(queries : Map<String, (Resources) -> String>) :Resources.() -> String {
            return {
                val resultMap = ArrayMap<String, String>()
                queries.forEach { q ->
                    resultMap[q.key] = try {
                        q.value.invoke(this)
                    } catch (e : Exception) {
                        e.javaClass.simpleName
                    }
                }
                mapToString(resultMap)
            }
        }

        @Parameterized.Parameters(name = "{1} {0}")
        @JvmStatic
        fun parameters(): Array<Any> {
            val parameters = mutableListOf<Parameter>()

            // Test resolution of resources encoded within the resources.arsc.
            parameters += Parameter(
                    "tableBased",
                    query(mapOf(
                            "getOverlaid" to { res ->
                                res.getString(R.string.test)
                            },
                            "getAdditional" to { res ->
                                res.getString(0x7f0400fe /* R.string.additional */)
                            }
                    )),
                    mapOf("getOverlaid" to "Not overlaid",
                            "getAdditional" to "NotFoundException"),

                    mapOf("getOverlaid" to "One",
                            "getAdditional" to "One"),

                    mapOf("getOverlaid" to "Two",
                            "getAdditional" to "Two"),

                    mapOf("getOverlaid" to "Three",
                            "getAdditional" to "Three"),

                    mapOf("getOverlaid" to "Four",
                            "getAdditional" to "Four"),
                    listOf(DataType.APK_DISK_FD, DataType.APK_DISK_FD_OFFSETS, DataType.APK_RAM_FD,
                            DataType.APK_RAM_OFFSETS, DataType.ARSC_DISK_FD,
                            DataType.ARSC_DISK_FD_OFFSETS, DataType.ARSC_RAM_MEMORY,
                            DataType.ARSC_RAM_MEMORY_OFFSETS, DataType.SPLIT)
            )

            // Test resolution of file-based resources and assets with no assets provider.
            parameters += Parameter(
                    "fileBased",
                    query(mapOf(
                            // Drawable xml in res directory
                            "drawableXml" to { res ->
                                (res.getDrawable(R.drawable.drawable_xml) as ColorDrawable)
                                        .color.toString()
                            },
                            // File in the assets directory
                            "openAsset" to { res ->
                                res.assets.open("asset.txt").reader().readText()
                            },
                            // From assets directory returning file descriptor
                            "openAssetFd" to { res ->
                                res.assets.openFd("asset.txt").readText()
                            },
                            // Asset as compiled XML layout in res directory
                            "layout" to { res ->
                                res.getLayout(R.layout.layout).advanceToRoot().name
                            },
                            // Bitmap drawable in res directory
                            "drawablePng" to { res ->
                                (res.getDrawable(R.drawable.drawable_png) as BitmapDrawable)
                                        .bitmap.getColor(0, 0).toArgb().toString()
                            }
                    )),
                    mapOf("drawableXml" to Color.parseColor("#B2D2F2").toString(),
                            "openAsset" to "In assets directory",
                            "openAssetFd" to "In assets directory",
                            "layout" to "MysteryLayout",
                            "drawablePng" to Color.parseColor("#FF00FF").toString()),

                    mapOf("drawableXml" to Color.parseColor("#000001").toString(),
                            "openAsset" to "One",
                            "openAssetFd" to "One",
                            "layout" to "RelativeLayout",
                            "drawablePng" to Color.RED.toString()),

                    mapOf("drawableXml" to Color.parseColor("#000002").toString(),
                            "openAsset" to "Two",
                            "openAssetFd" to "Two",
                            "layout" to "LinearLayout",
                            "drawablePng" to Color.GREEN.toString()),

                    mapOf("drawableXml" to Color.parseColor("#000003").toString(),
                            "openAsset" to "Three",
                            "openAssetFd" to "Three",
                            "layout" to "FrameLayout",
                            "drawablePng" to Color.BLUE.toString()),

                    mapOf("drawableXml" to Color.parseColor("#000004").toString(),
                            "openAsset" to "Four",
                            "openAssetFd" to "Four",
                            "layout" to "TableLayout",
                            "drawablePng" to Color.WHITE.toString()),
                    listOf(DataType.APK_DISK_FD, DataType.APK_DISK_FD_OFFSETS, DataType.APK_RAM_FD,
                            DataType.APK_RAM_OFFSETS, DataType.SPLIT)
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

    private val valueOriginal by lazy { mapToString(parameter.valueOriginal) }
    private val valueOne by lazy {  mapToString(parameter.valueOne) }
    private val valueTwo by lazy { mapToString(parameter.valueTwo) }
    private val valueThree by lazy { mapToString(parameter.valueThree) }
    private val valueFour by lazy { mapToString(parameter.valueFour) }

    private fun openOne() = PROVIDER_ONE.openProvider(dataType)
    private fun openTwo() = PROVIDER_TWO.openProvider(dataType)
    private fun openThree() = PROVIDER_THREE.openProvider(dataType)
    private fun openFour() = PROVIDER_FOUR.openProvider(dataType)

    // Class method for syntax highlighting purposes
    private fun getValue(c: Context = context) = parameter.getValue(c.resources)

    @Test
    fun assertValueUniqueness() {
        // Ensure the parameters are valid in case of coding errors
        val original = getValue()
        assertEquals(valueOriginal, original)
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
        assertEquals(valueOriginal, getValue())
    }

    @Test
    fun addLoadersRepeatedly() {
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
        assertEquals(valueOriginal, getValue())
    }

    @Test
    fun setMultipleProviders() {
        val testOne = openOne()
        val testTwo = openTwo()
        val loader = ResourcesLoader()

        resources.addLoaders(loader)
        loader.providers = listOf(testOne, testTwo)
        assertEquals(valueTwo, getValue())

        loader.removeProvider(testTwo)
        assertEquals(valueOne, getValue())

        loader.providers = Collections.emptyList()
        assertEquals(valueOriginal, getValue())
    }

    @Test
    fun addMultipleLoaders() {
        val loader1 = ResourcesLoader()
        loader1.addProvider(openOne())
        val loader2 = ResourcesLoader()
        loader2.addProvider(openTwo())

        resources.addLoaders(loader1, loader2)
        assertEquals(valueTwo, getValue())

        resources.removeLoaders(loader2)
        assertEquals(valueOne, getValue())

        resources.removeLoaders(loader1)
        assertEquals(valueOriginal, getValue())
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
        assertEquals(valueOriginal, getValue())
    }

    @Test
    fun reorderLoaders() {
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
        assertEquals(valueOriginal, getValue())
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
        assertEquals(valueOriginal, getValue())
        assertEquals(valueTwo, getValue(childContext))

        // A new context created from the original after an update to the original's loaders should
        // have the updated loaders.
        val originalPrime = createContext(context, 2)
        assertEquals(valueOriginal, getValue(originalPrime))

        // A new context created from the child context after an update to the child's loaders
        // should have the updated loaders.
        val childPrime = createContext(childContext, 1)
        assertEquals(valueTwo, getValue(childPrime))
    }

    @Test
    fun loaderUpdatesAffectContexts() {
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
        assertEquals(valueOriginal, getValue())
        assertEquals(valueTwo, getValue(childContext))

        val childContext2 = createContext(context, 1)
        assertEquals(valueOriginal, getValue())
        assertEquals(valueOriginal, getValue(childContext2))

        childContext2.resources.addLoaders(loader)
        assertEquals(valueOriginal, getValue())
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
        val testPrefix: String,
        val getValue: Resources.() -> String,
        val valueOriginal: Map<String, String>,
        val valueOne: Map<String, String>,
        val valueTwo: Map<String, String>,
        val valueThree: Map<String, String>,
        val valueFour: Map<String, String>,
        val dataTypes: List<DataType>
    ) {
        override fun toString() = testPrefix
    }
}

class TestActivity : Activity()