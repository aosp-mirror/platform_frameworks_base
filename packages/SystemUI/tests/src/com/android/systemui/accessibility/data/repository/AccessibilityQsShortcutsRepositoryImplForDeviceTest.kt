/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.accessibility.data.repository

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.view.accessibility.AccessibilityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.accessibility.AccessibilityShortcutController
import com.android.systemui.SysuiTestCase
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.ColorCorrectionTile
import com.android.systemui.qs.tiles.ColorInversionTile
import com.android.systemui.qs.tiles.FontScalingTile
import com.android.systemui.qs.tiles.OneHandedModeTile
import com.android.systemui.qs.tiles.ReduceBrightColorsTile
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.settings.FakeSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.internal.util.reflection.FieldSetter
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule

/**
 * Unit tests for AccessibilityQsShortcutsRepositoryImpl that requires a device. For example, we
 * can't mock the AccessibilityShortcutInfo for test. MultiValentTest doesn't compile when using
 * newly introduced methods and constants.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class AccessibilityQsShortcutsRepositoryImplForDeviceTest : SysuiTestCase() {
    @Rule @JvmField val mockitoRule: MockitoRule = MockitoJUnit.rule()

    // mocks
    @Mock private lateinit var a11yManager: AccessibilityManager
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val secureSettings = FakeSettings()

    private val userA11yQsShortcutsRepositoryFactory =
        object : UserA11yQsShortcutsRepository.Factory {
            override fun create(userId: Int): UserA11yQsShortcutsRepository {
                return UserA11yQsShortcutsRepository(
                    userId,
                    secureSettings,
                    testScope.backgroundScope,
                    testDispatcher,
                )
            }
        }

    private lateinit var underTest: AccessibilityQsShortcutsRepositoryImpl

    @Before
    fun setUp() {
        underTest =
            AccessibilityQsShortcutsRepositoryImpl(
                a11yManager,
                userA11yQsShortcutsRepositoryFactory,
                testDispatcher
            )
    }

    @Test
    fun testTileSpecToComponentMappingContent() {
        val mapping = AccessibilityQsShortcutsRepositoryImpl.TILE_SPEC_TO_COMPONENT_MAPPING

        assertThat(mapping.size).isEqualTo(5)
        assertThat(mapping[ColorCorrectionTile.TILE_SPEC])
            .isEqualTo(AccessibilityShortcutController.DALTONIZER_TILE_COMPONENT_NAME)
        assertThat(mapping[ColorInversionTile.TILE_SPEC])
            .isEqualTo(AccessibilityShortcutController.COLOR_INVERSION_TILE_COMPONENT_NAME)
        assertThat(mapping[OneHandedModeTile.TILE_SPEC])
            .isEqualTo(AccessibilityShortcutController.ONE_HANDED_TILE_COMPONENT_NAME)
        assertThat(mapping[ReduceBrightColorsTile.TILE_SPEC])
            .isEqualTo(
                AccessibilityShortcutController.REDUCE_BRIGHT_COLORS_TILE_SERVICE_COMPONENT_NAME
            )
        assertThat(mapping[FontScalingTile.TILE_SPEC])
            .isEqualTo(AccessibilityShortcutController.FONT_SIZE_TILE_COMPONENT_NAME)
    }

    @Test
    fun notifyAccessibilityManagerTilesChanged_customTiles_onlyNotifyA11yTileServices() =
        testScope.runTest {
            val a11yServiceTileService = ComponentName("a11yPackageName", "TileServiceClassName")
            setupInstalledAccessibilityServices(a11yServiceTileService)
            // TileService should match accessibility_shortcut_test_activity.xml,
            // because this test uses the real installed activity list
            val a11yShortcutTileService =
                ComponentName(
                    mContext.packageName,
                    "com.android.systemui.accessibility.TileService"
                )
            setupInstalledAccessibilityShortcutTargets()
            // Other custom tile service that isn't linked to an accessibility feature
            val nonA11yTileService = ComponentName("C", "c")

            val changedTiles =
                listOf(
                    TileSpec.create(a11yServiceTileService),
                    TileSpec.create(a11yShortcutTileService),
                    TileSpec.create(nonA11yTileService)
                )

            underTest.notifyAccessibilityManagerTilesChanged(context, changedTiles)
            runCurrent()

            Mockito.verify(a11yManager, Mockito.times(1))
                .notifyQuickSettingsTilesChanged(
                    context.userId,
                    listOf(a11yServiceTileService, a11yShortcutTileService)
                )
        }

    @Test
    fun notifyAccessibilityManagerTilesChanged_noMatchingA11yFrameworkTiles() =
        testScope.runTest {
            val changedTiles = listOf(TileSpec.create("a"))

            underTest.notifyAccessibilityManagerTilesChanged(context, changedTiles)
            runCurrent()

            Mockito.verify(a11yManager, Mockito.times(1))
                .notifyQuickSettingsTilesChanged(context.userId, emptyList())
        }

    @Test
    fun notifyAccessibilityManagerTilesChanged_convertA11yTilesSpecToComponentName() =
        testScope.runTest {
            val changedTiles =
                listOf(
                    TileSpec.create(ColorCorrectionTile.TILE_SPEC),
                    TileSpec.create(ColorInversionTile.TILE_SPEC),
                    TileSpec.create(OneHandedModeTile.TILE_SPEC),
                    TileSpec.create(ReduceBrightColorsTile.TILE_SPEC),
                    TileSpec.create(FontScalingTile.TILE_SPEC)
                )

            underTest.notifyAccessibilityManagerTilesChanged(context, changedTiles)
            runCurrent()

            Mockito.verify(a11yManager, Mockito.times(1))
                .notifyQuickSettingsTilesChanged(
                    context.userId,
                    listOf(
                        AccessibilityShortcutController.DALTONIZER_TILE_COMPONENT_NAME,
                        AccessibilityShortcutController.COLOR_INVERSION_TILE_COMPONENT_NAME,
                        AccessibilityShortcutController.ONE_HANDED_TILE_COMPONENT_NAME,
                        AccessibilityShortcutController
                            .REDUCE_BRIGHT_COLORS_TILE_SERVICE_COMPONENT_NAME,
                        AccessibilityShortcutController.FONT_SIZE_TILE_COMPONENT_NAME
                    )
                )
        }

    private fun setupInstalledAccessibilityShortcutTargets() {
        // Can't create a mock AccessibilityShortcutInfo because it's final.
        // Use the real AccessibilityManager to get the AccessibilityShortcutInfo
        val realA11yManager = context.getSystemService(AccessibilityManager::class.java)!!
        val installedA11yActivities =
            realA11yManager.getInstalledAccessibilityShortcutListAsUser(context, context.userId)

        whenever(a11yManager.getInstalledAccessibilityShortcutListAsUser(context, context.userId))
            .thenReturn(installedA11yActivities)
    }

    private fun setupInstalledAccessibilityServices(tileService: ComponentName) {
        whenever(a11yManager.installedAccessibilityServiceList)
            .thenReturn(
                listOf(
                    createFakeAccessibilityServiceInfo(
                        tileService.packageName,
                        tileService.className
                    )
                )
            )
    }

    private fun createFakeAccessibilityServiceInfo(
        packageName: String,
        tileServiceClass: String
    ): AccessibilityServiceInfo {
        val serviceInfo = ServiceInfo().also { it.packageName = packageName }
        val resolveInfo = ResolveInfo().also { it.serviceInfo = serviceInfo }

        val a11yServiceInfo = AccessibilityServiceInfo().also { it.resolveInfo = resolveInfo }

        // Somehow unable to mock the a11yServiceInfo.tileServiceName
        // Use reflection instead.
        FieldSetter.setField(
            a11yServiceInfo,
            AccessibilityServiceInfo::class.java.getDeclaredField("mTileServiceName"),
            tileServiceClass
        )

        return a11yServiceInfo
    }
}
