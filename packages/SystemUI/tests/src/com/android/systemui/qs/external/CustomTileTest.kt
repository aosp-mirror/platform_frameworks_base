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

import android.app.IUriGrantsManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Parcel
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
import com.android.systemui.animation.view.LaunchableFrameLayout
import com.android.systemui.classifier.FalsingManagerFake
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.res.R
import com.android.systemui.settings.FakeDisplayTracker
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.nullable
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
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
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import java.util.Arrays


@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class CustomTileTest : SysuiTestCase() {

    companion object {
        const val className = "test_class"
        val UID = 12345
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
    @Mock private lateinit var uiEventLogger: QsEventLogger
    @Mock private lateinit var ugm: IUriGrantsManager

    private var displayTracker = FakeDisplayTracker(mContext)
    private lateinit var customTile: CustomTile
    private lateinit var testableLooper: TestableLooper
    private val packageName = context.packageName
    private val componentName = ComponentName(packageName, className)
    private val TILE_SPEC = CustomTile.toSpec(componentName)

    private val customTileFactory = object : CustomTile.Factory {
        override fun create(action: String, userContext: Context): CustomTile {
            return CustomTile(
                { tileHost },
                uiEventLogger,
                testableLooper.looper,
                Handler(testableLooper.looper),
                FalsingManagerFake(),
                metricsLogger,
                statusBarStateController,
                activityStarter,
                qsLogger,
                action,
                userContext,
                customTileStatePersister,
                tileServices,
                displayTracker,
                ugm,
            )
        }
    }

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
        `when`(packageManager.getResourcesForApplication(any<ApplicationInfo>()))
                .thenReturn(context.resources)

        serviceInfo.applicationInfo = applicationInfo


        customTile = CustomTile.create(customTileFactory, TILE_SPEC, mContext)
        customTile.initialize()
        testableLooper.processAllMessages()
    }

    @Test
    fun testCorrectUser() {
        assertEquals(0, customTile.user)

        val userContext = mock(Context::class.java)
        `when`(userContext.packageManager).thenReturn(packageManager)
        `when`(userContext.userId).thenReturn(10)

        val tile = CustomTile.create(customTileFactory, TILE_SPEC, userContext)
        tile.initialize()
        testableLooper.processAllMessages()

        assertEquals(10, tile.user)
    }

    @Test
    fun testToggleableTileHasBooleanState() {
        `when`(tileServiceManager.isToggleableTile).thenReturn(true)
        customTile = CustomTile.create(customTileFactory, TILE_SPEC, mContext)
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
        customTile = CustomTile.create(customTileFactory, TILE_SPEC, mContext)
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
        customTile.updateTileState(t, UID)
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
        val tile = CustomTile.create(customTileFactory, TILE_SPEC, mContext)
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

        val tile = CustomTile.create(customTileFactory, TILE_SPEC, mContext)
        tile.initialize()
        testableLooper.processAllMessages()

        tile.updateTileState(t, UID)

        testableLooper.processAllMessages()

        verify(customTileStatePersister)
                .persistState(TileServiceKey(componentName, customTile.user), t)
    }

    @Test
    fun testAvailableBeforeInitialization() {
        `when`(packageManager.getApplicationInfo(anyString(), anyInt()))
                .thenThrow(PackageManager.NameNotFoundException())
        val tile = CustomTile.create(customTileFactory, TILE_SPEC, mContext)
        assertTrue(tile.isAvailable)
    }

    @Test
    fun testNotAvailableAfterInitializationWithoutIcon() {
        val tile = CustomTile.create(customTileFactory, TILE_SPEC, mContext)
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
        val tile = CustomTile.create(customTileFactory, TILE_SPEC, mContext)

        assertThrows(IllegalArgumentException::class.java) {
            tile.qsTile.activityLaunchForClick = pi
        }

        tile.handleClick(mock(View::class.java))
        testableLooper.processAllMessages()

        verify(activityStarter, never())
            .startPendingIntentMaybeDismissingKeyguard(any(), nullable(), nullable())
    }

    @Test
    fun testValidPendingIntentWithNoClickDoesNotStartActivity() {
        val pi = mock(PendingIntent::class.java)
        `when`(pi.isActivity).thenReturn(true)
        val tile = CustomTile.create(customTileFactory, TILE_SPEC, mContext)
        tile.qsTile.activityLaunchForClick = pi

        testableLooper.processAllMessages()

        verify(activityStarter, never())
            .startPendingIntentMaybeDismissingKeyguard(any(), nullable(), nullable())
    }

    @Test
    fun testValidPendingIntentStartsActivity() {
        val pi = mock(PendingIntent::class.java)
        `when`(pi.isActivity).thenReturn(true)
        val tile = CustomTile.create(customTileFactory, TILE_SPEC, mContext)
        tile.qsTile.activityLaunchForClick = pi

        tile.handleClick(mock(LaunchableFrameLayout::class.java))

        testableLooper.processAllMessages()

        verify(activityStarter)
            .startPendingIntentMaybeDismissingKeyguard(
                eq(pi), nullable(), nullable<ActivityLaunchAnimator.Controller>())
    }

    @Test
    fun testActiveTileListensOnceAfterCreated() {
        `when`(tileServiceManager.isActiveTile).thenReturn(true)

        val tile = CustomTile.create(customTileFactory, TILE_SPEC, mContext)
        tile.initialize()
        tile.postStale()
        testableLooper.processAllMessages()

        verify(tileServiceManager).setBindRequested(true)
        verify(tileService).onStartListening()
    }

    @Test
    fun testActiveTileDoesntListenAfterFirstTime() {
        `when`(tileServiceManager.isActiveTile).thenReturn(true)

        val tile = CustomTile.create(customTileFactory, TILE_SPEC, mContext)
        tile.initialize()
        // Make sure we have an icon in the tile because we don't have a default icon
        // This should not be overridden by the retrieved tile that has null icon.
        tile.qsTile.icon = mock(Icon::class.java)
        `when`(tile.qsTile.icon.loadDrawable(any(Context::class.java)))
                .thenReturn(mock(Drawable::class.java))

        tile.postStale()
        testableLooper.processAllMessages()

        // postStale will set it to not listening after it's done
        verify(tileService).onStopListening()

        clearInvocations(tileServiceManager, tileService)

        tile.setListening(Any(), true)
        testableLooper.processAllMessages()

        verify(tileServiceManager, never()).setBindRequested(true)
        verify(tileService, never()).onStartListening()
    }

    @Test
    fun testAlwaysUseDefaultLabelIfNoLabelIsSet() {
        // Give it an icon to prevent issues
        serviceInfo.icon = R.drawable.android

        val label1 = "Label 1"
        val label2 = "Label 2"

        `when`(serviceInfo.loadLabel(any())).thenReturn(label1)
        customTile.handleSetListening(true)
        testableLooper.processAllMessages()
        customTile.handleSetListening(false)
        testableLooper.processAllMessages()

        assertThat(customTile.state.label).isEqualTo(label1)

        // Retrieve the tile as if bound (a separate copy)
        val tile = copyTileUsingParcel(customTile.qsTile)

        // Change the language
        `when`(serviceInfo.loadLabel(any())).thenReturn(label2)

        // Set the tile to listening and apply the tile (unmodified)
        customTile.handleSetListening(true)
        testableLooper.processAllMessages()
        customTile.updateTileState(tile, UID)
        customTile.refreshState()
        testableLooper.processAllMessages()

        assertThat(customTile.state.label).isEqualTo(label2)
    }

    @Test
    fun uriIconLoadSuccess_correctIcon() {
        val size = 100
        val icon = mock(Icon::class.java)
        val drawable = context.getDrawable(R.drawable.cloud)!!
        whenever(icon.loadDrawable(any())).thenReturn(drawable)
        whenever(icon.loadDrawableCheckingUriGrant(
            any(),
            eq(ugm),
            anyInt(),
            anyString())
        ).thenReturn(drawable)

        serviceInfo.icon = R.drawable.android

        customTile.handleSetListening(true)
        testableLooper.processAllMessages()
        customTile.handleSetListening(false)
        testableLooper.processAllMessages()

        val tile = copyTileUsingParcel(customTile.qsTile)
        tile.icon = icon

        customTile.updateTileState(tile, UID)

        customTile.refreshState()
        testableLooper.processAllMessages()

        verify(icon).loadDrawableCheckingUriGrant(context, ugm, UID, packageName)

        assertThat(
                areDrawablesEqual(
                        customTile.state.iconSupplier.get().getDrawable(context),
                        drawable,
                        size
                )
        ).isTrue()
    }

    @Test
    fun uriIconLoadFailsWithoutGrant_defaultIcon() {
        val size = 100
        val drawable = context.getDrawable(R.drawable.cloud)!!
        val icon = mock(Icon::class.java)
        whenever(icon.loadDrawable(any())).thenReturn(drawable)
        whenever(icon.loadDrawableCheckingUriGrant(
            any(),
            eq(ugm),
            anyInt(),
            anyString())
        ).thenReturn(null)

        // Give it an icon to prevent issues
        serviceInfo.icon = R.drawable.android

        customTile.handleSetListening(true)
        testableLooper.processAllMessages()
        customTile.handleSetListening(false)
        testableLooper.processAllMessages()

        val tile = copyTileUsingParcel(customTile.qsTile)
        tile.icon = icon

        customTile.updateTileState(tile, UID)

        customTile.refreshState()
        testableLooper.processAllMessages()

        verify(icon).loadDrawableCheckingUriGrant(context, ugm, UID, packageName)

        assertThat(
                areDrawablesEqual(
                        customTile.state.iconSupplier.get().getDrawable(context),
                        context.getDrawable(R.drawable.android)!!,
                        size
                )
        ).isTrue()
    }
}

private fun areDrawablesEqual(drawable1: Drawable, drawable2: Drawable, size: Int = 24): Boolean {
    val bm1 = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val bm2 = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)

    val canvas1 = Canvas(bm1)
    val canvas2 = Canvas(bm2)

    drawable1.setBounds(0, 0, size, size)
    drawable2.setBounds(0, 0, size, size)

    drawable1.draw(canvas1)
    drawable2.draw(canvas2)

    return equalBitmaps(bm1, bm2).also {
        bm1.recycle()
        bm2.recycle()
    }
}

private fun equalBitmaps(a: Bitmap, b: Bitmap): Boolean {
    if (a.width != b.width || a.height != b.height) return false
    val w = a.width
    val h = a.height
    val aPix = IntArray(w * h)
    val bPix = IntArray(w * h)
    a.getPixels(aPix, 0, w, 0, 0, w, h)
    b.getPixels(bPix, 0, w, 0, 0, w, h)
    return Arrays.equals(aPix, bPix)
}

private fun copyTileUsingParcel(t: Tile): Tile {
    val parcel = Parcel.obtain()
    parcel.setDataPosition(0)
    t.writeToParcel(parcel, 0)
    parcel.setDataPosition(0)

    return Tile.CREATOR.createFromParcel(parcel)
}