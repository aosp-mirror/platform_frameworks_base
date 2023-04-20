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

import android.app.PendingIntent
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
import android.view.View
import com.android.internal.logging.MetricsLogger
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.ActivityLaunchAnimator
import com.android.systemui.classifier.FalsingManagerFake
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.nullable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
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
    @Mock private lateinit var customTileStatePersister: CustomTileStatePersister

    private lateinit var customTile: CustomTile
    private lateinit var testableLooper: TestableLooper
    private lateinit var customTileBuilder: CustomTile.Builder

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        testableLooper = TestableLooper.get(this)

        mContext.addMockSystemService("window", windowService)
        mContext.setMockPackageManager(packageManager)
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
                FalsingManagerFake(),
                metricsLogger,
                statusBarStateController,
                activityStarter,
                qsLogger,
                customTileStatePersister,
                tileServices
        )

        customTile = CustomTile.create(customTileBuilder, TILE_SPEC, mContext)
        customTile.initialize()
        testableLooper.processAllMessages()
    }

    @Test
    fun testCorrectUser() {
        assertEquals(0, customTile.user)

        val userContext = mock(Context::class.java)
        `when`(userContext.packageManager).thenReturn(packageManager)
        `when`(userContext.userId).thenReturn(10)

        val tile = CustomTile.create(customTileBuilder, TILE_SPEC, userContext)
        tile.initialize()
        testableLooper.processAllMessages()

        assertEquals(10, tile.user)
    }

    @Test
    fun testToggleableTileHasBooleanState() {
        `when`(tileServiceManager.isToggleableTile).thenReturn(true)
        customTile = CustomTile.create(customTileBuilder, TILE_SPEC, mContext)
        customTile.initialize()
        testableLooper.processAllMessages()

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
        customTile.initialize()
        testableLooper.processAllMessages()

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

    @Test
    fun testNoLoadStateTileNotActive() {
        // Not active by default
        testableLooper.processAllMessages()

        verify(customTileStatePersister, never()).readState(any())
    }

    @Test
    fun testNoPersistedStateTileNotActive() {
        // Not active by default
        val t = Tile().apply {
            state = Tile.STATE_INACTIVE
        }
        customTile.updateTileState(t)
        testableLooper.processAllMessages()

        verify(customTileStatePersister, never()).persistState(any(), any())
    }

    @Test
    fun testPersistedStateRetrieved() {
        val state = Tile.STATE_INACTIVE
        val label = "test_label"
        val subtitle = "test_subtitle"
        val contentDescription = "test_content_description"
        val stateDescription = "test_state_description"

        val t = Tile().apply {
            this.state = state
            this.label = label
            this.subtitle = subtitle
            this.contentDescription = contentDescription
            this.stateDescription = stateDescription
        }
        `when`(tileServiceManager.isActiveTile).thenReturn(true)
        `when`(customTileStatePersister
                .readState(TileServiceKey(componentName, customTile.user))).thenReturn(t)
        val tile = CustomTile.create(customTileBuilder, TILE_SPEC, mContext)
        tile.initialize()
        testableLooper.processAllMessages()

        // Make sure we have an icon in the tile because we don't have a default icon
        // This should not be overridden by the retrieved tile that has null icon.
        tile.qsTile.icon = mock(Icon::class.java)
        `when`(tile.qsTile.icon.loadDrawable(any(Context::class.java)))
                .thenReturn(mock(Drawable::class.java))

        val pi = mock(PendingIntent::class.java)
        `when`(pi.isActivity).thenReturn(true)
        tile.qsTile.activityLaunchForClick = pi

        tile.refreshState()

        testableLooper.processAllMessages()

        val tileState = tile.state

        assertEquals(state, tileState.state)
        assertEquals(label, tileState.label)
        assertEquals(subtitle, tileState.secondaryLabel)
        assertEquals(contentDescription, tileState.contentDescription)
        assertEquals(stateDescription, tileState.stateDescription)
    }

    @Test
    fun testStoreStateOnChange() {
        val t = Tile().apply {
            state = Tile.STATE_INACTIVE
            label = "test_label"
            subtitle = "test_subtitle"
            contentDescription = "test_content_description"
            stateDescription = "test_state_description"
        }
        `when`(tileServiceManager.isActiveTile).thenReturn(true)

        val tile = CustomTile.create(customTileBuilder, TILE_SPEC, mContext)
        tile.initialize()
        testableLooper.processAllMessages()

        tile.updateTileState(t)

        testableLooper.processAllMessages()

        verify(customTileStatePersister)
                .persistState(TileServiceKey(componentName, customTile.user), t)
    }

    @Test
    fun testAvailableBeforeInitialization() {
        `when`(packageManager.getApplicationInfo(anyString(), anyInt()))
                .thenThrow(PackageManager.NameNotFoundException())
        val tile = CustomTile.create(customTileBuilder, TILE_SPEC, mContext)
        assertTrue(tile.isAvailable)
    }

    @Test
    fun testNotAvailableAfterInitializationWithoutIcon() {
        val tile = CustomTile.create(customTileBuilder, TILE_SPEC, mContext)
        reset(tileHost)
        tile.initialize()
        testableLooper.processAllMessages()
        assertFalse(tile.isAvailable)
        verify(tileHost).removeTile(tile.tileSpec)
    }

    @Test
    fun testInvalidPendingIntentDoesNotStartActivity() {
        val pi = mock(PendingIntent::class.java)
        `when`(pi.isActivity).thenReturn(false)
        val tile = CustomTile.create(customTileBuilder, TILE_SPEC, mContext)

        assertThrows(IllegalArgumentException::class.java) {
            tile.qsTile.activityLaunchForClick = pi
        }

        tile.handleClick(mock(View::class.java))
        testableLooper.processAllMessages()

        verify(activityStarter, never())
            .startPendingIntentDismissingKeyguard(
                any(), any(), any(ActivityLaunchAnimator.Controller::class.java))
    }

    @Test
    fun testValidPendingIntentWithNoClickDoesNotStartActivity() {
        val pi = mock(PendingIntent::class.java)
        `when`(pi.isActivity).thenReturn(true)
        val tile = CustomTile.create(customTileBuilder, TILE_SPEC, mContext)
        tile.qsTile.activityLaunchForClick = pi

        testableLooper.processAllMessages()

        verify(activityStarter, never())
            .startPendingIntentDismissingKeyguard(
                any(), any(), any(ActivityLaunchAnimator.Controller::class.java))
    }

    @Test
    fun testValidPendingIntentStartsActivity() {
        val pi = mock(PendingIntent::class.java)
        `when`(pi.isActivity).thenReturn(true)
        val tile = CustomTile.create(customTileBuilder, TILE_SPEC, mContext)
        tile.qsTile.activityLaunchForClick = pi

        tile.handleClick(mock(View::class.java))

        testableLooper.processAllMessages()

        verify(activityStarter)
            .startPendingIntentDismissingKeyguard(
                eq(pi), nullable(), nullable<ActivityLaunchAnimator.Controller>())
    }
}