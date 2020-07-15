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

package com.android.systemui.qs.external

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Handler
import android.service.quicksettings.IQSTileService
import android.service.quicksettings.Tile
import android.test.suitebuilder.annotation.SmallTest
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.IWindowManager
import com.android.internal.logging.MetricsLogger
import com.android.systemui.SysuiTestCase
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.logging.QSLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.any
import org.mockito.Mockito.mock
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class CustomTileTest : SysuiTestCase() {

    companion object {
        const val packageName = "test_package"
        const val className = "test_class"
        val componentName = ComponentName(packageName, className)
        val TILE_SPEC = CustomTile.toSpec(componentName)
    }

    @Mock private lateinit var tileHost: QSHost
    @Mock private lateinit var metricsLogger: MetricsLogger
    @Mock private lateinit var statusBarStateController: StatusBarStateController
    @Mock private lateinit var activityStarter: ActivityStarter
    @Mock private lateinit var qsLogger: QSLogger
    @Mock private lateinit var tileService: IQSTileService
    @Mock private lateinit var tileServices: TileServices
    @Mock private lateinit var tileServiceManager: TileServiceManager
    @Mock private lateinit var windowService: IWindowManager
    @Mock private lateinit var packageManager: PackageManager
    @Mock private lateinit var applicationInfo: ApplicationInfo
    @Mock private lateinit var serviceInfo: ServiceInfo

    private lateinit var customTile: CustomTile
    private lateinit var testableLooper: TestableLooper
    private lateinit var customTileBuilder: CustomTile.Builder

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        testableLooper = TestableLooper.get(this)

        mContext.addMockSystemService("window", windowService)
        mContext.setMockPackageManager(packageManager)
        `when`(tileHost.tileServices).thenReturn(tileServices)
        `when`(tileHost.context).thenReturn(mContext)
        `when`(tileServices.getTileWrapper(any(CustomTile::class.java)))
                .thenReturn(tileServiceManager)
        `when`(tileServiceManager.tileService).thenReturn(tileService)
        `when`(packageManager.getApplicationInfo(anyString(), anyInt()))
                .thenReturn(applicationInfo)

        `when`(packageManager.getServiceInfo(any(ComponentName::class.java), anyInt()))
                .thenReturn(serviceInfo)
        serviceInfo.applicationInfo = applicationInfo

        customTileBuilder = CustomTile.Builder(
                { tileHost },
                testableLooper.looper,
                Handler(testableLooper.looper),
                metricsLogger,
                statusBarStateController,
                activityStarter,
                qsLogger
        )

        customTile = CustomTile.create(customTileBuilder, TILE_SPEC, mContext)
    }

    @Test
    fun testCorrectUser() {
        assertEquals(0, customTile.user)

        val userContext = mock(Context::class.java)
        `when`(userContext.packageManager).thenReturn(packageManager)
        `when`(userContext.userId).thenReturn(10)

        val tile = CustomTile.create(customTileBuilder, TILE_SPEC, userContext)

        assertEquals(10, tile.user)
    }

    @Test
    fun testToggleableTileHasBooleanState() {
        `when`(tileServiceManager.isToggleableTile).thenReturn(true)
        customTile = CustomTile.create(customTileBuilder, TILE_SPEC, mContext)

        assertTrue(customTile.state is QSTile.BooleanState)
        assertTrue(customTile.newTileState() is QSTile.BooleanState)
    }

    @Test
    fun testRegularTileHasNotBooleanState() {
        assertFalse(customTile.state is QSTile.BooleanState)
        assertFalse(customTile.newTileState() is QSTile.BooleanState)
    }

    @Test
    fun testValueUpdatedInBooleanTile() {
        `when`(tileServiceManager.isToggleableTile).thenReturn(true)
        customTile = CustomTile.create(customTileBuilder, TILE_SPEC, mContext)
        customTile.qsTile.icon = mock(Icon::class.java)
        `when`(customTile.qsTile.icon.loadDrawable(any(Context::class.java)))
                .thenReturn(mock(Drawable::class.java))

        val state = customTile.newTileState()
        assertTrue(state is QSTile.BooleanState)

        customTile.qsTile.state = Tile.STATE_INACTIVE
        customTile.handleUpdateState(state, null)
        assertFalse((state as QSTile.BooleanState).value)

        customTile.qsTile.state = Tile.STATE_ACTIVE
        customTile.handleUpdateState(state, null)
        assertTrue(state.value)

        customTile.qsTile.state = Tile.STATE_UNAVAILABLE
        customTile.handleUpdateState(state, null)
        assertFalse(state.value)
    }

    @Test
    fun testNoCrashOnNullDrawable() {
        customTile.qsTile.icon = mock(Icon::class.java)
        `when`(customTile.qsTile.icon.loadDrawable(any(Context::class.java)))
                .thenReturn(null)
        customTile.handleUpdateState(customTile.newTileState(), null)
    }
}