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

package com.android.systemui.qs.external

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.applicationContext
import android.content.packageManager
import android.os.Binder
import android.os.Handler
import android.os.RemoteException
import android.os.UserHandle
import android.platform.test.annotations.EnableFlags
import android.service.quicksettings.Tile
import android.testing.TestableContext
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags.FLAG_QS_CUSTOM_TILE_CLICK_GUARANTEED_BUG_FIX
import com.android.systemui.SysuiTestCase
import com.android.systemui.concurrency.fakeExecutor
import com.android.systemui.kosmos.testCase
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.impl.custom.packageManagerAdapterFacade
import com.android.systemui.qs.tiles.impl.custom.customTileSpec
import com.android.systemui.testKosmos
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.fakeSystemClock
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString

@RunWith(AndroidJUnit4::class)
@SmallTest
class CloseShadeRightAfterClickTestB339290820 : SysuiTestCase() {

    private val testableContext: TestableContext
    private val bindDelayExecutor: FakeExecutor
    private val kosmos =
        testKosmos().apply {
            testableContext = testCase.context
            bindDelayExecutor = FakeExecutor(fakeSystemClock)
            testableContext.setMockPackageManager(packageManager)
            customTileSpec = TileSpec.create(testComponentName)
            applicationContext = ContextWrapperDelayedBind(testableContext, bindDelayExecutor)
        }

    @Before
    fun setUp() {
        kosmos.apply {
            whenever(packageManager.getPackageUidAsUser(anyString(), anyInt(), anyInt()))
                .thenReturn(Binder.getCallingUid())
            packageManagerAdapterFacade.setIsActive(true)
            testableContext.addMockService(testComponentName, iQSTileService.asBinder())
        }
    }

    @Test
    @EnableFlags(FLAG_QS_CUSTOM_TILE_CLICK_GUARANTEED_BUG_FIX)
    fun testStopListeningShortlyAfterClick_clickIsSent() {
        with(kosmos) {
            val tile = FakeCustomTileInterface(tileServices)
            // Flush any bind from startup
            FakeExecutor.exhaustExecutors(fakeExecutor, bindDelayExecutor)

            // Open QS
            tile.setListening(true)
            fakeExecutor.runAllReady()
            tile.click()
            fakeExecutor.runAllReady()

            // No clicks yet because the latch is preventing the bind
            assertThat(iQSTileService.clicks).isEmpty()

            // Close QS
            tile.setListening(false)
            fakeExecutor.runAllReady()
            // And finally bind
            FakeExecutor.exhaustExecutors(fakeExecutor, bindDelayExecutor)

            assertThat(iQSTileService.clicks).containsExactly(tile.token)
        }
    }
}

private val testComponentName = ComponentName("pkg", "srv")

// This is a fake `CustomTile` that implements what we need for the test. Mainly setListening and
// click
private class FakeCustomTileInterface(tileServices: TileServices) : CustomTileInterface {
    override val user: Int
        get() = 0
    override val qsTile: Tile = Tile()
    override val component: ComponentName = testComponentName
    private var listening = false
    private val serviceManager = tileServices.getTileWrapper(this)
    private val serviceInterface = serviceManager.tileService

    val token = Binder()

    override fun getTileSpec(): String {
        return CustomTile.toSpec(component)
    }

    override fun refreshState() {}

    override fun updateTileState(tile: Tile, uid: Int) {}

    override fun onDialogShown() {}

    override fun onDialogHidden() {}

    override fun startActivityAndCollapse(pendingIntent: PendingIntent) {}

    override fun startUnlockAndRun() {}

    fun setListening(listening: Boolean) {
        if (listening == this.listening) return
        this.listening = listening

        try {
            if (listening) {
                if (!serviceManager.isActiveTile) {
                    serviceManager.setBindRequested(true)
                    serviceInterface.onStartListening()
                }
            } else {
                serviceInterface.onStopListening()
                serviceManager.setBindRequested(false)
            }
        } catch (e: RemoteException) {
            // Called through wrapper, won't happen here.
        }
    }

    fun click() {
        try {
            if (serviceManager.isActiveTile) {
                serviceManager.setBindRequested(true)
                serviceInterface.onStartListening()
            }
            serviceInterface.onClick(token)
        } catch (e: RemoteException) {
            // Called through wrapper, won't happen here.
        }
    }
}

private class ContextWrapperDelayedBind(
    val context: Context,
    val executor: FakeExecutor,
) : ContextWrapper(context) {
    override fun bindServiceAsUser(
        service: Intent,
        conn: ServiceConnection,
        flags: Int,
        user: UserHandle
    ): Boolean {
        executor.execute { super.bindServiceAsUser(service, conn, flags, user) }
        return true
    }

    override fun bindServiceAsUser(
        service: Intent,
        conn: ServiceConnection,
        flags: BindServiceFlags,
        user: UserHandle
    ): Boolean {
        executor.execute { super.bindServiceAsUser(service, conn, flags, user) }
        return true
    }

    override fun bindServiceAsUser(
        service: Intent?,
        conn: ServiceConnection?,
        flags: Int,
        handler: Handler?,
        user: UserHandle?
    ): Boolean {
        executor.execute { super.bindServiceAsUser(service, conn, flags, handler, user) }
        return true
    }

    override fun bindServiceAsUser(
        service: Intent,
        conn: ServiceConnection,
        flags: BindServiceFlags,
        handler: Handler,
        user: UserHandle
    ): Boolean {
        executor.execute { super.bindServiceAsUser(service, conn, flags, handler, user) }
        return true
    }
}
