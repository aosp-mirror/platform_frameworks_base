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

package com.android.systemui.flags

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import java.io.PrintWriter
import kotlin.test.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class FlagDependenciesTest : SysuiTestCase() {
    @Test
    fun testRelease() {
        testFlagDependencies(teamfood = false).start()
    }

    @Test
    fun testTeamfood() {
        testFlagDependencies(teamfood = true).start()
    }

    private fun testFlagDependencies(teamfood: Boolean) =
        FlagDependencies(TestFeatureFlags(teamfood = teamfood), TestHandler())

    private class TestHandler : FlagDependenciesBase.Handler {
        override val enableDependencies: Boolean
            get() = true
        override fun warnAboutBadFlagConfiguration(
            all: List<FlagDependenciesBase.Dependency>,
            unmet: List<FlagDependenciesBase.Dependency>
        ) {
            val title = "Invalid flag dependencies: ${unmet.size}"
            val details = unmet.joinToString("\n")
            fail("$title:\n$details")
        }

        override fun onCollected(all: List<FlagDependenciesBase.Dependency>) {
            Log.d("FlagDependencies", "All: ${all.size}")
            all.forEach { Log.d("FlagDependencies", "  $it") }
        }
    }

    private class TestFeatureFlags(val teamfood: Boolean) : FeatureFlagsClassic {
        private val unsupported: Nothing
            get() = fail("Unsupported")

        override fun isEnabled(flag: ReleasedFlag): Boolean = true
        override fun isEnabled(flag: UnreleasedFlag): Boolean = teamfood && flag.teamfood
        override fun isEnabled(flag: ResourceBooleanFlag): Boolean = unsupported
        override fun isEnabled(flag: SysPropBooleanFlag): Boolean = unsupported
        override fun getString(flag: StringFlag): String = unsupported
        override fun getString(flag: ResourceStringFlag): String = unsupported
        override fun getInt(flag: IntFlag): Int = unsupported
        override fun getInt(flag: ResourceIntFlag): Int = unsupported
        override fun addListener(flag: Flag<*>, listener: FlagListenable.Listener) = unsupported
        override fun removeListener(listener: FlagListenable.Listener) = unsupported
        override fun dump(writer: PrintWriter, args: Array<out String>?) = unsupported
    }
}
