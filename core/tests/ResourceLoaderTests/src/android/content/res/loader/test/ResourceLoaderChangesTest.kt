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
 * limitations under the License.
 */

package android.content.res.loader.test

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.widget.FrameLayout
import androidx.test.InstrumentationRegistry
import androidx.test.rule.ActivityTestRule
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.Arrays
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

@RunWith(Parameterized::class)
class ResourceLoaderChangesTest : ResourceLoaderTestBase() {

    companion object {
        private const val TIMEOUT = 30L
        private const val OVERLAY_PACKAGE = "android.content.res.loader.test.overlay"

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data() = arrayOf(DataType.APK, DataType.ARSC)
    }

    @field:Parameterized.Parameter(0)
    override lateinit var dataType: DataType

    @get:Rule
    val activityRule: ActivityTestRule<TestActivity> =
            ActivityTestRule<TestActivity>(TestActivity::class.java, false, true)

    // Redirect to the Activity's resources
    override val resources: Resources
        get() = activityRule.getActivity().resources

    private val activity: TestActivity
        get() = activityRule.getActivity()

    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before
    @After
    fun disableOverlay() {
        enableOverlay(OVERLAY_PACKAGE, false)
    }

    @Test
    fun activityRecreate() = verifySameBeforeAndAfter {
        val oldActivity = activity
        var newActivity: Activity? = null
        instrumentation.runOnMainSync { oldActivity.recreate() }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            newActivity = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .single()
        }

        assertThat(newActivity).isNotNull()
        assertThat(newActivity).isNotSameAs(oldActivity)

        // Return the new resources to assert on
        return@verifySameBeforeAndAfter newActivity!!.resources
    }

    @Test
    fun activityHandledOrientationChange() = verifySameBeforeAndAfter {
        val latch = CountDownLatch(1)
        val oldConfig = Configuration().apply { setTo(resources.configuration) }
        var changedConfig: Configuration? = null

        activity.callback = object : TestActivity.Callback {
            override fun onConfigurationChanged(newConfig: Configuration) {
                changedConfig = newConfig
                latch.countDown()
            }
        }

        val isPortrait = resources.displayMetrics.run { widthPixels < heightPixels }
        val newRotation = if (isPortrait) {
            UiAutomation.ROTATION_FREEZE_90
        } else {
            UiAutomation.ROTATION_FREEZE_0
        }

        instrumentation.uiAutomation.setRotation(newRotation)

        assertThat(latch.await(TIMEOUT, TimeUnit.SECONDS)).isTrue()
        assertThat(changedConfig).isNotEqualTo(oldConfig)
        return@verifySameBeforeAndAfter activity.resources
    }

    @Test
    fun enableOverlayCausingPathChange() = verifySameBeforeAndAfter {
        assertThat(getString(R.string.loader_path_change_test)).isEqualTo("Not overlaid")

        enableOverlay(OVERLAY_PACKAGE, true)

        assertThat(getString(R.string.loader_path_change_test)).isEqualTo("Overlaid")

        return@verifySameBeforeAndAfter activity.resources
    }

    @Test
    fun enableOverlayChildContextUnaffected() {
        val childContext = activity.createConfigurationContext(Configuration())
        val childResources = childContext.resources
        val originalValue = childResources.getString(android.R.string.cancel)
        assertThat(childResources.getString(R.string.loader_path_change_test))
                .isEqualTo("Not overlaid")

        verifySameBeforeAndAfter {
            enableOverlay(OVERLAY_PACKAGE, true)
            return@verifySameBeforeAndAfter activity.resources
        }

        // Loader not applied, but overlay change propagated
        assertThat(childResources.getString(android.R.string.cancel)).isEqualTo(originalValue)
        assertThat(childResources.getString(R.string.loader_path_change_test))
                .isEqualTo("Overlaid")
    }

    // All these tests assert for the exact same loaders/values, so extract that logic out
    private fun verifySameBeforeAndAfter(block: () -> Resources) {
        fun Resources.resource() = this.getString(android.R.string.cancel)
        fun Resources.asset() = this.assets.open("Asset.txt").reader().readText()

        val originalResource = resources.resource()
        val originalAsset = resources.asset()

        val loaderResource = "stringOne".openLoader()
        val loaderAsset = "assetOne".openLoader(dataType = DataType.ASSET)
        addLoader(loaderResource)
        addLoader(loaderAsset)

        val oldLoaders = resources.loaders
        val oldResource = resources.resource()
        val oldAsset = resources.asset()

        assertThat(oldResource).isNotEqualTo(originalResource)
        assertThat(oldAsset).isNotEqualTo(originalAsset)

        val newResources = block()

        val newLoaders = newResources.loaders
        val newResource = newResources.resource()
        val newAsset = newResources.asset()

        assertThat(newResource).isEqualTo(oldResource)
        assertThat(newAsset).isEqualTo(oldAsset)
        assertThat(newLoaders).isEqualTo(oldLoaders)
    }

    // Copied from overlaytests LocalOverlayManager
    private fun enableOverlay(packageName: String, enable: Boolean) {
        val executor = Executor { Thread(it).start() }
        val pattern = (if (enable) "[x]" else "[ ]") + " " + packageName
        if (executeShellCommand("cmd overlay list").contains(pattern)) {
            // nothing to do, overlay already in the requested state
            return
        }

        val oldApkPaths = resources.assets.apkPaths
        val task = FutureTask {
            while (true) {
                if (!Arrays.equals(oldApkPaths, resources.assets.apkPaths)) {
                    return@FutureTask true
                }
                Thread.sleep(10)
            }

            @Suppress("UNREACHABLE_CODE")
            return@FutureTask false
        }

        val command = if (enable) "enable" else "disable"
        executeShellCommand("cmd overlay $command $packageName")
        executor.execute(task)
        assertThat(task.get(TIMEOUT, TimeUnit.SECONDS)).isTrue()
    }

    private fun executeShellCommand(command: String): String {
        val uiAutomation = instrumentation.uiAutomation
        val pfd = uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.reader().readText() }
    }
}

class TestActivity : Activity() {

    var callback: Callback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLUE)
        })
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        callback?.onConfigurationChanged(newConfig)
    }

    interface Callback {
        fun onConfigurationChanged(newConfig: Configuration)
    }
}
