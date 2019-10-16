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
import android.service.quicksettings.IQSTileService
import android.service.quicksettings.Tile
import android.test.suitebuilder.annotation.SmallTest
import android.view.IWindowManager
import androidx.test.runner.AndroidJUnit4
import com.android.systemui.SysuiTestCase
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.qs.QSTileHost
import junit.framework.Assert.assertFalse
import junit.framework.Assert.assertTrue
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
@RunWith(AndroidJUnit4::class)
class CustomTileTest : SysuiTestCase() {

    companion object {
        const val packageName = "test_package"
        const val className = "test_class"
        val componentName = ComponentName(packageName, className)
        val TILE_SPEC = CustomTile.toSpec(componentName)
    }

    @Mock private lateinit var mTileHost: QSTileHost
    @Mock private lateinit var mTileService: IQSTileService
    @Mock private lateinit var mTileServices: TileServices
    @Mock private lateinit var mTileServiceManager: TileServiceManager
    @Mock private lateinit var mWindowService: IWindowManager
    @Mock private lateinit var mPackageManager: PackageManager
    @Mock private lateinit var mApplicationInfo: ApplicationInfo
    @Mock private lateinit var mServiceInfo: ServiceInfo

    private lateinit var customTile: CustomTile

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        mContext.addMockSystemService("window", mWindowService)
        mContext.setMockPackageManager(mPackageManager)
        `when`(mTileHost.tileServices).thenReturn(mTileServices)
        `when`(mTileHost.context).thenReturn(mContext)
        `when`(mTileServices.getTileWrapper(any(CustomTile::class.java)))
                .thenReturn(mTileServiceManager)
        `when`(mTileServiceManager.tileService).thenReturn(mTileService)
        `when`(mPackageManager.getApplicationInfo(anyString(), anyInt()))
                .thenReturn(mApplicationInfo)

        `when`(mPackageManager.getServiceInfo(any(ComponentName::class.java), anyInt()))
                .thenReturn(mServiceInfo)
        mServiceInfo.applicationInfo = mApplicationInfo

        customTile = CustomTile.create(mTileHost, TILE_SPEC)
    }

    @Test
    fun testBooleanTileHasBooleanState() {
        `when`(mTileServiceManager.isBooleanTile).thenReturn(true)
        customTile = CustomTile.create(mTileHost, TILE_SPEC)

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
        `when`(mTileServiceManager.isBooleanTile).thenReturn(true)
        customTile = CustomTile.create(mTileHost, TILE_SPEC)
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
}