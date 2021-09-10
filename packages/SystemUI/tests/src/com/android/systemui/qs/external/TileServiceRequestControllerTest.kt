/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.content.ComponentName
import android.content.DialogInterface
import android.graphics.drawable.Icon
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.internal.statusbar.IAddTileResultCallback
import com.android.systemui.SysuiTestCase
import com.android.systemui.qs.QSTileHost
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.commandline.CommandRegistry
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.capture
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.anyBoolean
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyString
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import java.util.function.Consumer

@SmallTest
@RunWith(AndroidTestingRunner::class)
class TileServiceRequestControllerTest : SysuiTestCase() {

    companion object {
        private val TEST_COMPONENT = ComponentName("test_pkg", "test_cls")
        private const val TEST_APP_NAME = "App"
        private const val TEST_LABEL = "Label"
    }

    @Mock
    private lateinit var tileRequestDialog: TileRequestDialog
    @Mock
    private lateinit var qsTileHost: QSTileHost
    @Mock
    private lateinit var commandRegistry: CommandRegistry
    @Mock
    private lateinit var commandQueue: CommandQueue
    @Mock
    private lateinit var icon: Icon

    private lateinit var controller: TileServiceRequestController

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        // Tile not present by default
        `when`(qsTileHost.indexOf(anyString())).thenReturn(-1)

        controller = TileServiceRequestController(qsTileHost, commandQueue, commandRegistry) {
            tileRequestDialog
        }

        controller.init()
    }

    @Test
    fun requestTileAdd_dataIsPassedToDialog() {
        controller.requestTileAdd(TEST_COMPONENT, TEST_APP_NAME, TEST_LABEL, icon, Callback())

        verify(tileRequestDialog).setTileData(
                TileRequestDialog.TileData(TEST_APP_NAME, TEST_LABEL, icon)
        )
    }

    @Test
    fun tileAlreadyAdded_correctResult() {
        `when`(qsTileHost.indexOf(CustomTile.toSpec(TEST_COMPONENT))).thenReturn(2)

        val callback = Callback()
        controller.requestTileAdd(TEST_COMPONENT, TEST_APP_NAME, TEST_LABEL, icon, callback)

        assertThat(callback.lastAccepted).isEqualTo(TileServiceRequestController.TILE_ALREADY_ADDED)
        verify(qsTileHost, never()).addTile(any(ComponentName::class.java), anyBoolean())
    }

    @Test
    fun showAllUsers_set() {
        controller.requestTileAdd(TEST_COMPONENT, TEST_APP_NAME, TEST_LABEL, icon, Callback())
        verify(tileRequestDialog).setShowForAllUsers(true)
    }

    @Test
    fun cancelOnTouchOutside_set() {
        controller.requestTileAdd(TEST_COMPONENT, TEST_APP_NAME, TEST_LABEL, icon, Callback())
        verify(tileRequestDialog).setCanceledOnTouchOutside(true)
    }

    @Test
    fun cancelListener_dismissResult() {
        val cancelListenerCaptor =
                ArgumentCaptor.forClass(DialogInterface.OnCancelListener::class.java)

        val callback = Callback()
        controller.requestTileAdd(TEST_COMPONENT, TEST_APP_NAME, TEST_LABEL, icon, callback)
        verify(tileRequestDialog).setOnCancelListener(capture(cancelListenerCaptor))

        cancelListenerCaptor.value.onCancel(tileRequestDialog)
        assertThat(callback.lastAccepted).isEqualTo(TileServiceRequestController.DISMISSED)
        verify(qsTileHost, never()).addTile(any(ComponentName::class.java), anyBoolean())
    }

    @Test
    fun positiveActionListener_tileAddedResult() {
        val clickListenerCaptor =
                ArgumentCaptor.forClass(DialogInterface.OnClickListener::class.java)

        val callback = Callback()
        controller.requestTileAdd(TEST_COMPONENT, TEST_APP_NAME, TEST_LABEL, icon, callback)
        verify(tileRequestDialog).setPositiveButton(anyInt(), capture(clickListenerCaptor))

        clickListenerCaptor.value.onClick(tileRequestDialog, DialogInterface.BUTTON_POSITIVE)

        assertThat(callback.lastAccepted).isEqualTo(TileServiceRequestController.ADD_TILE)
        verify(qsTileHost).addTile(TEST_COMPONENT, /* end */ true)
    }

    @Test
    fun negativeActionListener_tileNotAddedResult() {
        val clickListenerCaptor =
                ArgumentCaptor.forClass(DialogInterface.OnClickListener::class.java)

        val callback = Callback()
        controller.requestTileAdd(TEST_COMPONENT, TEST_APP_NAME, TEST_LABEL, icon, callback)
        verify(tileRequestDialog).setNegativeButton(anyInt(), capture(clickListenerCaptor))

        clickListenerCaptor.value.onClick(tileRequestDialog, DialogInterface.BUTTON_NEGATIVE)

        assertThat(callback.lastAccepted).isEqualTo(TileServiceRequestController.DONT_ADD_TILE)
        verify(qsTileHost, never()).addTile(any(ComponentName::class.java), anyBoolean())
    }

    @Test
    fun commandQueueCallback_registered() {
        verify(commandQueue).addCallback(any())
    }

    @Test
    fun commandQueueCallback_dataPassedToDialog() {
        val captor = ArgumentCaptor.forClass(CommandQueue.Callbacks::class.java)
        verify(commandQueue, atLeastOnce()).addCallback(capture(captor))

        captor.value.requestAddTile(TEST_COMPONENT, TEST_APP_NAME, TEST_LABEL, icon, Callback())

        verify(tileRequestDialog).setTileData(
                TileRequestDialog.TileData(TEST_APP_NAME, TEST_LABEL, icon)
        )
    }

    @Test
    fun commandQueueCallback_callbackCalled() {
        `when`(qsTileHost.indexOf(CustomTile.toSpec(TEST_COMPONENT))).thenReturn(2)
        val captor = ArgumentCaptor.forClass(CommandQueue.Callbacks::class.java)
        verify(commandQueue, atLeastOnce()).addCallback(capture(captor))
        val c = Callback()

        captor.value.requestAddTile(TEST_COMPONENT, TEST_APP_NAME, TEST_LABEL, icon, c)

        assertThat(c.lastAccepted).isEqualTo(TileServiceRequestController.TILE_ALREADY_ADDED)
    }

    private class Callback : IAddTileResultCallback.Stub(), Consumer<Int> {
        var lastAccepted: Int? = null
            private set
        override fun accept(t: Int) {
            lastAccepted = t
        }

        override fun onTileRequest(r: Int) {
            accept(r)
        }
    }
}